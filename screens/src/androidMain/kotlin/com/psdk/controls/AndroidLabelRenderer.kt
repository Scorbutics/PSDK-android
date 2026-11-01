package com.psdk.controls

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import java.io.ByteArrayOutputStream

/**
 * A label on the gamepad's round button, in the system's bold font: the disc
 * of the runtime's 16 pixel images, drawn [ZOOM] times larger so it matches
 * them on screen, and the text inside it.
 */
class AndroidLabelRenderer : LabelRenderer {
    override fun render(label: String): ByteArray {
        val side = DISC.size * ZOOM
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        DISC.forEachIndexed { y, row ->
            row.forEachIndexed { x, pixel ->
                val color = COLORS[pixel] ?: return@forEachIndexed
                paint.color = color
                canvas.drawRect((x * ZOOM).toFloat(), (y * ZOOM).toFloat(), ((x + 1) * ZOOM).toFloat(), ((y + 1) * ZOOM).toFloat(), paint)
            }
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_COLOR
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            textSize = side * 0.45f
        }
        // Inside the disc's flat fill
        val room = side * 0.62f
        val width = text.measureText(label)
        if (width > room) text.textSize *= room / width
        val baseline = side / 2f - (text.descent() + text.ascent()) / 2
        canvas.drawText(label, side / 2f, baseline, text)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    private companion object {
        const val ZOOM = 8
        val DISC = listOf(
            "................",
            ".....AAAAAA.....",
            "...AAAAAAAAAA...",
            "..AAAABBBBAAAA..",
            "..AABBBBBBBBAA..",
            ".AABBBBBBBBBBAA.",
            ".AABBBBBBBBBBAA.",
            ".AABBBBBBBBBBAA.",
            ".AABBBBBBBBBBAA.",
            ".AABBBBBBBBBBAA.",
            ".AABBBBBBBBBBAA.",
            "..AABBBBBBBBAA..",
            "..AAAABBBBAAAA..",
            "...AAAAAAAAAA...",
            ".....AAAAAA.....",
            "................",
        )
        // The runtime's gamepad palette (litergss/touch_overlay/*.png)
        val COLORS = mapOf('A' to 0xFF434A5F.toInt(), 'B' to 0xFF959AB1.toInt())
        const val TEXT_COLOR = 0xFFDCE1E7.toInt()
    }
}
