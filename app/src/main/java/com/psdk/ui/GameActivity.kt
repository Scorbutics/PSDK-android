package com.psdk.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.psdk.R
import com.psdk.ruby.vm.PsdkInterpreter
import com.psdk.ruby.vm.RemoteListenerConfig
import com.psdk.ruby.vm.RubyScript
import com.psdk.ruby.vm.ScriptLocation
import com.scorbutics.litergss.NativeSurface
import com.scorbutics.rubyvm.LogListener
import com.scorbutics.rubyvm.LogMessage
import java.io.FileWriter

/**
 * Hosts the running game in-process, replacing the previous
 * `android.app.NativeActivity` entrypoint.
 *
 * Lifecycle wiring:
 *   onCreate
 *     → immersive fullscreen, inflate [LiteRgssSurfaceView] + IME EditText,
 *       set env vars, wire onSurfaceReady listener
 *   surfaceReady
 *     → start [PsdkInterpreter] with `start.rb`
 *   onResume   → [NativeSurface.injectFocusGained] (required after pause —
 *                 PSDK's `Graphics.focus?` gates every input query and
 *                 stays false without this)
 *   onPause    → [NativeSurface.injectFocusLost]
 *   onDestroy  → [NativeSurface.requestClose] + interpreter.close()
 *
 * Input routing (mirrors what SFML's NativeActivity glue does internally):
 *   dispatchKeyEvent           → [NativeSurface.injectKeyDown/Up] (+ injectText
 *                                 for printable chars on key-up) OR
 *                                 [NativeSurface.injectJoystickButton] for
 *                                 GAMEPAD/JOYSTICK sources
 *   dispatchGenericMotionEvent → [NativeSurface.injectJoystickAxis] for
 *                                 JOYSTICK sources
 *   onTouchEvent               → [LiteRgssSurfaceView] handles touches
 *                                 directly (already-working splash path)
 *   InputDeviceListener        → [NativeSurface.injectJoystickConnected/
 *                                 Disconnected] for hotplug
 *
 * IME bridge:
 *   Ruby asks for the soft keyboard via cgss::android::requestVirtualKeyboard
 *   → JNI thunk in litergss_surface_jni.c
 *   → NativeSurface.dispatchVirtualKeyboardRequest
 *   → VirtualKeyboardListener registered below
 *   → [showImeOnUiThread] / [hideImeOnUiThread]
 *
 * Each char typed into the EditText is injected as `sf::Event::TextEntered`
 * via [NativeSurface.injectText], then the EditText is wiped so the next
 * char produces another single-char text-changed event.
 */
class GameActivity : AppCompatActivity() {

    private var gameInterpreter: PsdkInterpreter? = null
    private val launchLock = Any()
    private var surfaceReady = false
    private var launched = false

    /**
     * Latched once the surface has come up once, so we don't fire a
     * spurious GainedFocus on the very first onResume (before the
     * surface is bound, sf::Event::GainedFocus reads m_size from a
     * NULL window pointer and would crash).
     */
    private var firstSurfaceReadyDone = false

    private lateinit var glSurface: LiteRgssSurfaceView
    private lateinit var imeSink: EditText
    private var imeSinkWatcher: TextWatcher? = null
    private var inputManager: InputManager? = null
    private var inputDeviceListener: InputManager.InputDeviceListener? = null

    private val virtualKeyboardListener = NativeSurface.VirtualKeyboardListener { show ->
        // Fires on the Ruby render thread; bounce to UI thread before
        // touching View state.
        runOnUiThread {
            if (show) showImeOnUiThread() else hideImeOnUiThread()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep the screen on while the game runs — matches what
        // NativeActivity does via its FLAG_KEEP_SCREEN_ON manifest hint.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Edge-to-edge with system bars hidden. Behave like a NativeActivity
        // game: no status bar, no nav bar, fullscreen.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        setContentView(R.layout.activity_game)
        glSurface = findViewById(R.id.glSurface)
        imeSink = findViewById(R.id.imeSink)

        val executionLocation = intent.getStringExtra(EXTRA_EXECUTION_LOCATION) ?: run {
            Log.e(TAG, "missing EXTRA_EXECUTION_LOCATION; finishing")
            finish()
            return
        }

        // Mirror the env vars GameLauncher used to publish before launching
        // NativeActivity. start.rb still reads them (RGSS_EXECUTION_LOCATION,
        // RGSS_LOG_FILE, RGSS_ERROR_LOG_FILE, RGSS_REMOTE_EVAL_TOKEN). The
        // KMP RubyInterpreter inherits the process env, so setting them
        // here is equivalent to GameLauncher's pre-launch Os.setenv.
        configureEnv(executionLocation)

        // The hidden EditText accepts soft-keyboard input. Watch each
        // character as it arrives and forward as TextEntered. See [TextWatcher]
        // installation in [showImeOnUiThread] / removal in [hideImeOnUiThread]
        // — we only watch while the keyboard is visible, so a stray focus
        // change can't fire injectText spuriously.

        glSurface.onSurfaceReady = { w, h ->
            Log.i(TAG, "surface ready (${w}x${h})")
            synchronized(launchLock) {
                surfaceReady = true
                firstSurfaceReadyDone = true
                tryLaunchLocked(executionLocation)
            }
        }

        // Arm the IME bridge — native code (Ruby) calls
        // cgss::android::requestVirtualKeyboard → JNI thunk →
        // dispatchVirtualKeyboardRequest → this listener.
        NativeSurface.setVirtualKeyboardListener(virtualKeyboardListener)

        // Shared-RenderWindow opt-in lives in EntryActivity now (process-
        // wide) so both the splash and the game inherit the same
        // sf::RenderWindow + EglContext, keeping SFML's default-shader
        // handles valid across the splash→game transition.

        registerInputDeviceListener()
    }

    private fun configureEnv(executionLocation: String) {
        val releaseLocation = "$executionLocation/Release"
        // start.rb reads these.
        android.system.Os.setenv("RGSS_EXECUTION_LOCATION", executionLocation, true)
        android.system.Os.setenv("RGSS_LOG_FILE", "$executionLocation/last_stdout.log", true)
        android.system.Os.setenv("RGSS_ERROR_LOG_FILE", "$releaseLocation/Error.log", true)

        // Reset stdout log so old output doesn't bleed into this run.
        try {
            FileWriter("$executionLocation/last_stdout.log", false).flush()
        } catch (e: Exception) {
            Log.w(TAG, "failed to reset stdout log: ${e.message}")
        }

        if (RemoteListenerConfig.enabled) {
            android.system.Os.setenv("RGSS_REMOTE_DEBUG_HOST",    RemoteListenerConfig.DEBUG_HOST,           true)
            android.system.Os.setenv("RGSS_REMOTE_DEBUG_PORT",    RemoteListenerConfig.DEBUG_PORT.toString(), true)
            android.system.Os.setenv("RGSS_REMOTE_DEBUG_TOKEN",   RemoteListenerConfig.TOKEN,                true)
            android.system.Os.setenv("RGSS_REMOTE_DEBUG_SESSION", "psdk-game-debug",                          true)
            android.system.Os.setenv("RGSS_REMOTE_EVAL_HOST",     RemoteListenerConfig.EVAL_HOST,            true)
            android.system.Os.setenv("RGSS_REMOTE_EVAL_PORT",     RemoteListenerConfig.EVAL_PORT.toString(),  true)
            android.system.Os.setenv("RGSS_REMOTE_EVAL_TOKEN",    RemoteListenerConfig.TOKEN,                true)
            android.system.Os.setenv("RGSS_REMOTE_EVAL_SESSION",  "psdk-game-eval",                           true)
        }
    }

    /** Caller MUST hold [launchLock]. */
    private fun tryLaunchLocked(executionLocation: String) {
        if (launched) return
        if (!surfaceReady) return

        Log.i(TAG, "launching start.rb (executionLocation=$executionLocation)")

        val listener = object : LogListener {
            override fun onLogMessage(logMessage: LogMessage) {
                // Funnel game stdout/stderr into logcat so adb-logcat
                // captures it; the on-disk RGSS_LOG_FILE is still
                // written via SafeRunner in start.rb.
                //Log.i(TAG, logMessage.message)
            }
        }
        val onErr: (Exception) -> Unit = { e -> Log.e(TAG, "interpreter error", e) }

        val interpreter = PsdkInterpreter.create(this, listener, onErr)
        gameInterpreter = interpreter

        val script = RubyScript(assets, START_SCRIPT, START_PRELUDES)
        interpreter.enqueue(
            script = script,
            location = ScriptLocation(executionLocation = executionLocation, archive = null),
            onComplete = { exitCode ->
                Log.i(TAG, "start.rb exited (code=$exitCode); finishing GameActivity")
                runOnUiThread { if (!isFinishing) finish() }
            }
        )

        launched = true
    }

    override fun onResume() {
        super.onResume()
        // Only push GainedFocus if the surface has already been attached
        // once. forwardEvent(GainedFocus) reads ANativeWindow_getWidth on
        // states.window, and a NULL window pointer (pre-first-attach)
        // would crash. The very first surfaceChanged → attach implicitly
        // creates the EGL surface anyway, so missing the first focus
        // event is harmless.
        if (firstSurfaceReadyDone) {
            NativeSurface.injectFocusGained()
        }
    }

    override fun onPause() {
        // Drives PSDK's Graphics.@has_focus = false. Without it,
        // [001 Input.rb#press?]/trigger? would still see focus on
        // resume and ignore the next GainedFocus.
        if (firstSurfaceReadyDone) {
            NativeSurface.injectFocusLost()
        }
        super.onPause()
    }

    override fun onDestroy() {
        unregisterInputDeviceListener()
        NativeSurface.setVirtualKeyboardListener(null)

        // Order matters, same as the splash teardown in RootActivity:
        // requestClose pushes sf::Event::Closed into the game's event
        // queue. The Ruby render loop consumes it on its next frame,
        // raises rb_eClosedWindow, unwinds the script. THEN close()
        // — which queues a synchronous :stop — returns. Reversing the
        // order deadlocks the UI thread because :stop sits behind an
        // infinite game loop.
        NativeSurface.requestClose()
        gameInterpreter?.close()
        gameInterpreter = null
        super.onDestroy()
    }

    // -------------------------------------------------------------------
    // Input dispatch
    // -------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val source = event.source
        val isGamepad = (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                        (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

        // Let the system handle volume keys (and BACK pre-Android-T) at
        // the activity level so the user can adjust media volume mid-
        // game. Everything else we forward.
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(event)
        }

        // When the IME is up, let the EditText handle text input so the
        // TextWatcher fires. Otherwise typed text never reaches us.
        if (imeSink.isFocused && !isGamepad) {
            return super.dispatchKeyEvent(event)
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (isGamepad) {
                    NativeSurface.injectJoystickButton(event.deviceId, event.keyCode, true)
                } else {
                    NativeSurface.injectKeyDown(event.keyCode, event.metaState, event.repeatCount)
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                if (isGamepad) {
                    NativeSurface.injectJoystickButton(event.deviceId, event.keyCode, false)
                } else {
                    NativeSurface.injectKeyUp(event.keyCode, event.metaState)
                    // Mirror SFML's NativeActivity flow: emit TextEntered
                    // on UP for keys that produce a printable character.
                    // getUnicodeChar returns 0 for non-printable keys.
                    val unicode = event.getUnicodeChar(event.metaState)
                    if (unicode != 0) {
                        NativeSurface.injectText(unicode)
                    }
                }
                return true
            }
            else -> return super.dispatchKeyEvent(event)
        }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        // Gamepad analog axes arrive as generic motion events on
        // SOURCE_JOYSTICK. Touches go through onTouchEvent on the
        // SurfaceView, not here.
        val isJoystick = (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        if (isJoystick && event.action == MotionEvent.ACTION_MOVE) {
            NativeSurface.injectJoystickAxis(
                event.deviceId,
                event.getAxisValue(MotionEvent.AXIS_X),
                event.getAxisValue(MotionEvent.AXIS_Y),
                event.getAxisValue(MotionEvent.AXIS_Z),
                event.getAxisValue(MotionEvent.AXIS_RZ),
                event.getAxisValue(MotionEvent.AXIS_HAT_X),
                event.getAxisValue(MotionEvent.AXIS_HAT_Y),
                event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
            )
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    // -------------------------------------------------------------------
    // Gamepad hotplug
    // -------------------------------------------------------------------

    private fun registerInputDeviceListener() {
        val im = getSystemService(Context.INPUT_SERVICE) as? InputManager ?: return
        inputManager = im

        // Pre-announce devices already present at activity-start so SFML's
        // JoystickManager state matches reality without waiting for an
        // event. Same rationale as enumerating SFML joysticks at boot in
        // the NativeActivity flow.
        for (id in im.inputDeviceIds) {
            val device = im.getInputDevice(id) ?: continue
            if ((device.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (device.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                NativeSurface.injectJoystickConnected(id)
            }
        }

        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) {
                val device = im.getInputDevice(deviceId) ?: return
                if ((device.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                    (device.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                    NativeSurface.injectJoystickConnected(deviceId)
                }
            }
            override fun onInputDeviceRemoved(deviceId: Int) {
                NativeSurface.injectJoystickDisconnected(deviceId)
            }
            override fun onInputDeviceChanged(deviceId: Int) {
                // No-op: SFML doesn't track capability changes; reading
                // axes/buttons after a change is what re-syncs state.
            }
        }
        im.registerInputDeviceListener(listener, null)
        inputDeviceListener = listener
    }

    private fun unregisterInputDeviceListener() {
        val l = inputDeviceListener ?: return
        inputManager?.unregisterInputDeviceListener(l)
        inputDeviceListener = null
        inputManager = null
    }

    // -------------------------------------------------------------------
    // IME bridge
    // -------------------------------------------------------------------

    private fun showImeOnUiThread() {
        // Install the watcher BEFORE focusing the EditText so the very
        // first text event hits us. Use removeTextChangedListener inside
        // afterTextChanged to avoid recursion when we wipe the field.
        if (imeSinkWatcher == null) {
            val watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val text = s ?: return
                    if (text.isEmpty()) return
                    // Inject each codepoint. PSDK's on_text_entered builds
                    // its own buffer from these, so order matters but
                    // batching doesn't.
                    var i = 0
                    while (i < text.length) {
                        val cp = Character.codePointAt(text, i)
                        NativeSurface.injectText(cp)
                        i += Character.charCount(cp)
                    }
                    // Detach during the wipe so setText(emptyString)
                    // doesn't reenter afterTextChanged → infinite loop.
                    imeSink.removeTextChangedListener(this)
                    imeSink.setText("")
                    imeSink.addTextChangedListener(this)
                }
            }
            imeSink.addTextChangedListener(watcher)
            imeSinkWatcher = watcher
        }
        imeSink.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.showSoftInput(imeSink, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideImeOnUiThread() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(imeSink.windowToken, 0)
        imeSink.clearFocus()
        // Refocus the SurfaceView so subsequent hardware-key events
        // reach dispatchKeyEvent through the right hierarchy path.
        glSurface.requestFocus()
        // Leave the watcher installed across hide/show cycles — cheaper
        // than re-attaching on every keyboard open. Detach is in
        // onDestroy via setText("") wiping any pending state.
    }

    companion object {
        private const val TAG = "GameActivity"
        private const val START_SCRIPT = "start.rb"

        /**
         * Helper modules concatenated before start.rb itself. The embedded
         * VM `eval`s start.rb as a string (__FILE__ is "<socket-script-N>"),
         * so require_relative can't resolve sibling files. Same convention
         * RootActivity uses for the loader (see LOADER_PRELUDES).
         */
        private val START_PRELUDES = listOf(
            "psdk_vm_snapshot.rb",
        )

        const val EXTRA_EXECUTION_LOCATION = "EXECUTION_LOCATION"

        fun intent(activity: Activity, executionLocation: String): Intent =
            Intent(activity, GameActivity::class.java)
                .putExtra(EXTRA_EXECUTION_LOCATION, executionLocation)
    }
}
