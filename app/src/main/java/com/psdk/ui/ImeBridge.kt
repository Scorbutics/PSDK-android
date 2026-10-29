package com.psdk.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.scorbutics.litergss.NativeSurface

/**
 * Owns the soft-keyboard bridge: a hidden [EditText] that accepts IME
 * input, a [TextWatcher] that forwards each typed codepoint into SFML
 * as `sf::Event::TextEntered`, and the [NativeSurface.VirtualKeyboardListener]
 * registration so Ruby code can request show/hide via
 * `cgss::android::requestVirtualKeyboard`.
 *
 * Lifecycle expected from the host:
 *   - construct in onCreate with the hidden EditText, a focus-restore
 *     target (typically the LiteRgssSurfaceView), and the Activity
 *     Context (for InputMethodManager lookup)
 *   - call [start] when the game workload becomes active (registers the
 *     VirtualKeyboardListener on NativeSurface)
 *   - call [stop] when the workload exits (unregisters listener, hides
 *     any visible keyboard)
 *
 * Threading: the VirtualKeyboardListener fires on whatever thread
 * called `cgss::android::requestVirtualKeyboard` (Ruby render thread
 * in practice). All View interactions are posted to the UI thread via
 * the provided Handler.
 */
class ImeBridge(
    private val context: Context,
    private val imeSink: EditText,
    private val focusRestoreTarget: View,
) {

    private val uiHandler = Handler(Looper.getMainLooper())
    private val keyTap = KeyTap(
        injectKey = { keyCode, down ->
            if (down) NativeSurface.injectKeyDown(keyCode, 0, 0) else NativeSurface.injectKeyUp(keyCode, 0)
        },
        injectText = { codePoint -> NativeSurface.injectText(codePoint) },
        postDelayed = { delayMs, action -> uiHandler.postDelayed(action, delayMs) },
    )
    private var imeSinkWatcher: TextWatcher? = null
    // The keyboard took room on screen at the last insets dispatch
    private var imeInsetsShown = false

    private val virtualKeyboardListener = NativeSurface.VirtualKeyboardListener { show ->
        uiHandler.post {
            if (show) showOnUiThread() else hideOnUiThread()
        }
    }

    /**
     * True iff the soft keyboard is on screen. Read from the window insets,
     * not from the sink's focus: the keyboard can be opened by other paths
     * than [showOnUiThread] (SFML's own JNI call) or closed by the system
     * (back gesture) while the sink's focus says otherwise.
     */
    val isImeActive: Boolean
        get() = ViewCompat.getRootWindowInsets(imeSink)?.isVisible(WindowInsetsCompat.Type.ime()) == true

    /**
     * Arm the native→Java IME bridge. Idempotent at the
     * NativeSurface level (setVirtualKeyboardListener overwrites).
     */
    fun start() {
        // Installed up front: whatever opened the keyboard, the text it
        // commits into the sink must reach the game.
        installWatcher()
        // The keyboard's action key (done, go...) is an editor action, not a
        // key event: send the game the Enter a physical keyboard would, key
        // and typed newline (PSDK confirms a name on the newline), see
        // KeyTap. Returning false keeps the default handling, which hides the
        // keyboard.
        imeSink.setOnEditorActionListener { _, _, event ->
            if (event == null) {
                keyTap.tap(KeyEvent.KEYCODE_ENTER, '\n'.code)
            }
            false
        }
        // The sink is only focusable while the keyboard is requested: a
        // focused editor gets the hardware keys before the activity (the
        // IME pre-processes them), which ate the physical keyboard's keys.
        // Follow the keyboard whoever shows or hides it: SFML's
        // Sf::Keyboard.virtual_keyboard= calls the InputMethodManager itself
        // (with no text field focused, the keyboard cannot type), the system
        // hides it on a back gesture.
        // Only a visible -> hidden change means the system hid it: a keyboard
        // taking no room (Gboard's toolbar while a hardware keyboard is
        // plugged) never shows in the insets, and must keep its text field.
        ViewCompat.setOnApplyWindowInsetsListener(imeSink) { _, insets ->
            val shown = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (shown && !imeSink.isFocused) {
                focusSink()
                imm()?.restartInput(imeSink)
            } else if (!shown && imeInsetsShown && imeSink.isFocused) {
                releaseSink()
            }
            imeInsetsShown = shown
            insets
        }
        NativeSurface.setVirtualKeyboardListener(virtualKeyboardListener)
    }

    /**
     * Disarm the bridge and ensure the soft keyboard is hidden. Safe to
     * call from any thread; View work is posted to the UI thread.
     */
    fun stop() {
        NativeSurface.setVirtualKeyboardListener(null)
        uiHandler.post { hideOnUiThread() }
    }

    private fun showOnUiThread() {
        installWatcher()
        focusSink()
        imm()?.showSoftInput(imeSink, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun imm(): InputMethodManager? =
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

    private fun focusSink() {
        imeSink.isFocusable = true
        imeSink.isFocusableInTouchMode = true
        imeSink.requestFocus()
    }

    // Use removeTextChangedListener inside afterTextChanged to avoid
    // recursion when we wipe the field.
    private fun installWatcher() {
        if (imeSinkWatcher == null) {
            val watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val text = s ?: return
                    if (text.isEmpty()) return
                    var i = 0
                    while (i < text.length) {
                        val cp = Character.codePointAt(text, i)
                        NativeSurface.injectText(cp)
                        i += Character.charCount(cp)
                    }
                    // Detach during the wipe so setText("") doesn't reenter
                    // afterTextChanged → infinite loop.
                    imeSink.removeTextChangedListener(this)
                    imeSink.setText("")
                    imeSink.addTextChangedListener(this)
                }
            }
            imeSink.addTextChangedListener(watcher)
            imeSinkWatcher = watcher
        }
    }

    private fun hideOnUiThread() {
        imm()?.hideSoftInputFromWindow(imeSink.windowToken, 0)
        releaseSink()
        // Watcher stays installed across hide/show cycles — cheaper than
        // re-attaching every time.
    }

    private fun releaseSink() {
        imeSink.clearFocus()
        imeSink.isFocusableInTouchMode = false
        imeSink.isFocusable = false
        // Restore focus to the surface view so subsequent hardware-key
        // events reach the activity's dispatchKeyEvent through the
        // right View hierarchy path.
        focusRestoreTarget.requestFocus()
    }
}
