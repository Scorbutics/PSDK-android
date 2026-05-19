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
}
