package com.scorbutics.litergss

import android.view.Surface

/**
 * Kotlin entry point for the LiteRGSS hosted-Activity flow on Android.
 *
 * Mirror of the orchestrator-side shape at
 * litergss-everywhere/kotlin/com/scorbutics/litergss/NativeSurface.kt.
 * Inlined here for now because the orchestrator does not yet publish a
 * Kotlin module for it; promote to a library dependency once it does.
 *
 * Pair this with a [android.view.SurfaceView] (or TextureView): on
 * surfaceCreated call [attach] with the holder's Surface and current
 * width/height, forward surfaceChanged to [resized], surfaceDestroyed to
 * [detach]. Per-pointer touch events go through [injectTouchDown] /
 * [injectTouchMove] / [injectTouchUp].
 *
 * Threading: [attach], [detach] and [resized] are typically invoked from the
 * Android UI thread (SurfaceHolder.Callback). They only manipulate global
 * state and acquire/release the ANativeWindow — none of them touch GL. The
 * actual EGL surface bind happens lazily on the render thread when
 * `LiteRGSS::DisplayWindow.new` runs from Ruby, per the project rule
 * "Ruby VM thread = draw thread".
 *
 * Symbols live in librgss_runtime.so. PsdkInterpreter.initialize already
 * sets the library name and the embedded-ruby-vm loader pulls it in, so the
 * defensive loadLibrary below is normally a no-op.
 */
object NativeSurface {

    init {
        try {
            System.loadLibrary("rgss_runtime")
        } catch (_: UnsatisfiedLinkError) {
            // Already loaded by the rubyvm KMP loader, fall through.
        }
    }

    @JvmStatic external fun attach(surface: Surface, width: Int, height: Int)
    @JvmStatic external fun resized(width: Int, height: Int)
    @JvmStatic external fun detach()
    @JvmStatic external fun injectTouchDown(pointerId: Int, x: Float, y: Float)
    @JvmStatic external fun injectTouchMove(pointerId: Int, x: Float, y: Float)
    @JvmStatic external fun injectTouchUp(pointerId: Int, x: Float, y: Float)

    /**
     * Push a sf::Event::Closed into the active hosted DisplayWindow's event
     * queue. The Ruby render loop drains it on its next frame, fires
     * on_closed, which (with the default loader.rb proc returning truthy)
     * makes LiteRGSS raise rb_eClosedWindow and unwind the script via
     * exception. m_gameWindow.close() runs as part of the exception path,
     * destroying WindowImplAndroid (singleInstance=NULL, looper released)
     * and EglContext (states.context cleared).
     *
     * Call this from Activity.onDestroy BEFORE PsdkInterpreter.close():
     * close() queues a synchronous :stop token on the worker thread, but
     * an infinite Ruby render loop never returns to dequeue it — UI thread
     * deadlocks. Pushing Closed first gives the loop an out, so close()
     * finishes promptly and the next activity instance starts with clean
     * SFML singleton state.
     *
     * No-op if no DisplayWindow is active (singleInstance NULL).
     */
    @JvmStatic external fun requestClose()

    // -------------------------------------------------------------------
    // Hosted input injection (mirrors litergss-everywhere additions).
    //
    // These let a hosting Activity (GameActivity) replace what SFML's
    // NativeActivity glue does internally: hardware keys, text events,
    // focus lifecycle, gamepad buttons / axes / hotplug, and the
    // soft-keyboard show/hide bridge.
    //
    // Translation from Android keycodes to sf::Keyboard / SFML joystick
    // indices happens on the native side (HostedAndroidWindow.cpp via
    // cgss::android), reusing SFML's existing translation tables so the
    // hosted path matches the NativeActivity path bit-for-bit.
    // -------------------------------------------------------------------

    /**
     * Forward a hardware key-down. `repeatCount > 0` (OS auto-repeat) is
     * dropped on the native side; consumers like PSDK run their own
     * software repeat handler ([001 Input.rb#repeat?]).
     */
    @JvmStatic external fun injectKeyDown(androidKeyCode: Int, metaState: Int, repeatCount: Int)

    /** Forward a hardware key-up. */
    @JvmStatic external fun injectKeyUp(androidKeyCode: Int, metaState: Int)

    /**
     * Forward a single Unicode codepoint as `sf::Event::TextEntered`. Use
     * for both hardware-key text (host computes via KeyEvent.getUnicodeChar)
     * and soft-keyboard input from a hidden EditText. Codepoint 0 is a
     * no-op on the native side.
     */
    @JvmStatic external fun injectText(unicodeCodepoint: Int)

    /**
     * Forward `sf::Event::GainedFocus`. **Required** after every onResume:
     * PSDK's `Graphics.focus?` gates every `Input.*?` call (see
     * [001 Input.rb#press?]) and stays false until a GainedFocus event
     * arrives.
     */
    @JvmStatic external fun injectFocusGained()

    /** Forward `sf::Event::LostFocus`. */
    @JvmStatic external fun injectFocusLost()

    /**
     * Forward a gamepad button press/release. `androidKeyCode` must be one
     * of the AKEYCODE_BUTTON_* / AKEYCODE_DPAD_* values that SFML's
     * `androidJoystickKeyToIndex` recognises. Others are silently dropped.
     */
    @JvmStatic external fun injectJoystickButton(deviceId: Int, androidKeyCode: Int, pressed: Boolean)

    /**
     * Forward gamepad analog axes. Values are Android's native [-1, 1];
     * SFML scales to [-100, 100] internally.
     */
    @JvmStatic external fun injectJoystickAxis(
        deviceId: Int,
        axisX: Float, axisY: Float,
        axisZ: Float, axisRz: Float,
        hatX: Float,  hatY: Float,
        lTrigger: Float, rTrigger: Float,
    )

    /** Forward a gamepad-connected event. */
    @JvmStatic external fun injectJoystickConnected(deviceId: Int)

    /** Forward a gamepad-disconnected event. */
    @JvmStatic external fun injectJoystickDisconnected(deviceId: Int)

    /**
     * Listener invoked when native code requests the soft keyboard be
     * shown or hidden. Ruby code requesting the IME calls
     * cgss::android::requestVirtualKeyboard → JNI thunk →
     * [dispatchVirtualKeyboardRequest] → this listener.
     *
     * Threading: callback fires on whichever thread asked for the
     * keyboard (Ruby render thread in practice). Listeners that touch
     * Android View state MUST hop to the UI thread themselves.
     */
    fun interface VirtualKeyboardListener {
        fun onVirtualKeyboardRequested(show: Boolean)
    }

    @Volatile
    private var virtualKeyboardListener: VirtualKeyboardListener? = null

    /** Register (or clear, with null) the soft-keyboard request listener. */
    @JvmStatic
    fun setVirtualKeyboardListener(listener: VirtualKeyboardListener?) {
        virtualKeyboardListener = listener
        nativeSetVirtualKeyboardCallback(listener != null)
    }

    /** JNI entry. Called by litergss_surface_jni.c via the registered C callback. */
    @JvmStatic
    @Suppress("unused")
    private fun dispatchVirtualKeyboardRequest(show: Boolean) {
        virtualKeyboardListener?.onVirtualKeyboardRequested(show)
    }

    @JvmStatic private external fun nativeSetVirtualKeyboardCallback(armed: Boolean)

    /**
     * Opt the calling process into the preserved (process-shared)
     * sf::RenderWindow path inside cgss::DisplayWindow.
     *
     * Set true from a host Activity (e.g. GameActivity) that wants its
     * Ruby code's `DisplayWindow.new` to reuse the same underlying
     * sf::RenderWindow + WindowImplAndroid + EGL context across multiple
     * DisplayWindow lifetimes. Default false: each DisplayWindow gets
     * its own owned RenderWindow destroyed with it — what loader.rb
     * needs so the splash tears down cleanly.
     *
     * Effect is process-wide and persists until cleared.
     *
     * Caveat: the preserved WindowImplAndroid keeps the ALooper of
     * whichever thread first constructed it. A subsequent
     * DisplayWindow.new from a DIFFERENT thread will hit "No looper
     * for this thread". Safe as long as the host activity uses a
     * single PsdkInterpreter (single render thread) for its whole
     * lifetime.
     */
    @JvmStatic external fun setReuseSharedWindow(enabled: Boolean)

    /** Read-back accessor; for diagnostics / tests. */
    @JvmStatic external fun isReuseSharedWindowEnabled(): Boolean
}
