package com.psdk.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
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
    private var imeSinkWatcher: TextWatcher? = null

    private val virtualKeyboardListener = NativeSurface.VirtualKeyboardListener { show ->
        uiHandler.post {
            if (show) showOnUiThread() else hideOnUiThread()
        }
    }

    /** True iff the IME sink currently has focus (i.e. the soft keyboard is up). */
    val isImeActive: Boolean get() = imeSink.isFocused

    /**
     * Arm the native→Java IME bridge. Idempotent at the
     * NativeSurface level (setVirtualKeyboardListener overwrites).
     */
    fun start() {
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
        // Install the watcher lazily on first show. Use removeTextChangedListener
        // inside afterTextChanged to avoid recursion when we wipe the field.
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
        imeSink.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.showSoftInput(imeSink, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideOnUiThread() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(imeSink.windowToken, 0)
        imeSink.clearFocus()
        // Restore focus to the surface view so subsequent hardware-key
        // events reach the activity's dispatchKeyEvent through the
        // right View hierarchy path.
        focusRestoreTarget.requestFocus()
        // Watcher stays installed across hide/show cycles — cheaper than
        // re-attaching every time.
    }
}
