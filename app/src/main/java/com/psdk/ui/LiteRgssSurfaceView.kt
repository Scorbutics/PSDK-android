package com.psdk.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.scorbutics.litergss.NativeSurface

/**
 * SurfaceView that hosts a LiteRGSS render target.
 *
 * Owns the JNI lifecycle:
 *   surfaceChanged (first call)  → NativeSurface.attach
 *   surfaceChanged (subsequent)  → NativeSurface.resized
 *   surfaceDestroyed             → NativeSurface.detach
 *
 * surfaceCreated is intentionally a no-op: width/height are zero there;
 * surfaceChanged is the first callback with real dimensions and is also the
 * only one we are guaranteed to receive before the first frame is drawn.
 *
 * Per the project rule "Ruby VM thread = draw thread", this view does not
 * own a render thread itself — the host (currently CompileWizardFragment,
 * hosted by RootActivity) launches a PsdkInterpreter whose internal
 * thread runs both the Ruby script and the GL/EGL work. [onSurfaceReady]
 * is the signal to start that thread.
 */
class LiteRgssSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    /** Fired once the surface is attached and sized. width/height are pixels. */
    var onSurfaceReady: ((width: Int, height: Int) -> Unit)? = null

    /** Fired before NativeSurface.detach is called. */
    var onSurfaceLost: (() -> Unit)? = null

    private var attached = false

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        // Real dimensions arrive in surfaceChanged.
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!attached) {
            NativeSurface.attach(holder.surface, width, height)
            attached = true
            onSurfaceReady?.invoke(width, height)
        } else {
            NativeSurface.resized(width, height)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (attached) {
            onSurfaceLost?.invoke()
            NativeSurface.detach()
            attached = false
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                NativeSurface.injectTouchDown(event.getPointerId(i), event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> {
                val i = event.actionIndex
                NativeSurface.injectTouchUp(event.getPointerId(i), event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    NativeSurface.injectTouchMove(event.getPointerId(i), event.getX(i), event.getY(i))
                }
            }
        }
        return true
    }
}
