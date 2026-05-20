package com.psdk.ui

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.scorbutics.litergss.NativeSurface

/**
 * Owns the hosted-input bridge: hardware keys, gamepad buttons / axes,
 * and gamepad hotplug. Mirrors what SFML's NativeActivity glue does
 * internally for the input subsystem, routed through [NativeSurface].
 *
 * Lifecycle expected from the host Activity:
 *   - construct in onCreate with the Activity Context
 *   - dispatchKeyEvent / dispatchGenericMotionEvent: forward via this
 *     forwarder's [handleKeyEvent] / [handleMotionEvent] — they return
 *     true if the event was consumed (i.e. forwarded into SFML), false
 *     if the caller should fall back to super.dispatchXxx
 *   - call [start] when input forwarding should activate (typically
 *     when the game workload starts) and [stop] when it should stop
 *     (when the workload exits or the host activity is destroyed)
 *
 * No reference to any Activity beyond the Context used to look up
 * InputManager. Safe to construct from a non-Activity Context as long
 * as it can reach the system services.
 *
 * Threading: all methods called from the UI thread. NativeSurface.inject*
 * is thread-safe (SFML's event queue is mutex-guarded), so the calls
 * could technically come from any thread, but this class assumes the
 * standard View-dispatch pattern.
 */
class GameInputForwarder(private val context: Context) {

    /**
     * When false, [handleKeyEvent] / [handleMotionEvent] always return
     * false (do not consume). Used by the host to suppress input
     * forwarding while a non-game workload (splash, hub fragment,
     * wizard fragment) is in foreground.
     */
    private var enabled: Boolean = false

    /**
     * Optional gate to let the host decide per-call whether to consume.
     * Typical use: if (imeSink.isFocused) return false so the EditText
     * gets text events. Defaults to "always consume when enabled".
     */
    var keyEventShouldConsume: (KeyEvent) -> Boolean = { true }

    private var inputManager: InputManager? = null
    private var inputDeviceListener: InputManager.InputDeviceListener? = null

    /**
     * Start forwarding: enable consumption, register gamepad hotplug
     * listener, pre-announce already-connected gamepads so SFML's
     * JoystickManager has them before the game's first poll.
     *
     * Idempotent: calling start twice is a no-op.
     */
    fun start() {
        if (enabled) return
        enabled = true
        registerInputDeviceListener()
    }

    /**
     * Stop forwarding: disable consumption, unregister hotplug listener.
     * Any in-flight events the OS hands us via dispatch* after stop()
     * are passed through to super (handleXxx returns false).
     *
     * Idempotent.
     */
    fun stop() {
        if (!enabled) return
        enabled = false
        unregisterInputDeviceListener()
    }

    /**
     * Forward a KeyEvent into SFML's event queue. Returns true if the
     * event was consumed.
     *
     * Volume keys are always passed through (return false) so the user
     * can adjust media volume mid-game without losing the key to the
     * game's input handler.
     */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (!enabled) return false
        if (isVolumeKey(event.keyCode)) return false
        if (!keyEventShouldConsume(event)) return false

        val isGamepad = isGamepadSource(event.source)
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
                    val unicode = event.getUnicodeChar(event.metaState)
                    if (unicode != 0) {
                        NativeSurface.injectText(unicode)
                    }
                }
                return true
            }
            else -> return false
        }
    }

    /**
     * Forward a generic MotionEvent (gamepad analog axes) into SFML's
     * event queue. Returns true if the event was consumed.
     *
     * Touches go through [LiteRgssSurfaceView.onTouchEvent], not through
     * generic-motion dispatch, so they're not handled here.
     */
    fun handleMotionEvent(event: MotionEvent): Boolean {
        if (!enabled) return false
        val isJoystick = (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        if (!isJoystick || event.action != MotionEvent.ACTION_MOVE) return false

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

    private fun registerInputDeviceListener() {
        val im = context.getSystemService(Context.INPUT_SERVICE) as? InputManager ?: return
        inputManager = im

        for (id in im.inputDeviceIds) {
            val device = im.getInputDevice(id) ?: continue
            if (isGamepadSource(device.sources)) {
                NativeSurface.injectJoystickConnected(id)
            }
        }

        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) {
                val device = im.getInputDevice(deviceId) ?: return
                if (isGamepadSource(device.sources)) {
                    NativeSurface.injectJoystickConnected(deviceId)
                }
            }
            override fun onInputDeviceRemoved(deviceId: Int) {
                NativeSurface.injectJoystickDisconnected(deviceId)
            }
            override fun onInputDeviceChanged(deviceId: Int) {
                // SFML doesn't track capability changes mid-session;
                // the next axis/button event re-syncs state.
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

    private fun isGamepadSource(source: Int): Boolean =
        (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
        (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

    private fun isVolumeKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
        keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
        keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
}
