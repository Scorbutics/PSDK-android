package com.psdk.ui

/**
 * The look of a question with a way forward: its positive button filled (the
 * call to action), its negative one muted text (the fallback). Colours from
 * the dialog's theme, defaults where it has none; cosmetic only, never in
 * the way of what the dialog asks.
 */
object DialogStyle {
    fun callToAction(dialog: androidx.appcompat.app.AlertDialog) = runCatching {
        val action = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
        fun color(attr: Int, default: Int) = com.google.android.material.color.MaterialColors.getColor(action, attr, default)
        (action as? com.google.android.material.button.MaterialButton)?.apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(color(androidx.appcompat.R.attr.colorPrimary, 0xFF14C8B4.toInt()))
            setTextColor(color(com.google.android.material.R.attr.colorOnPrimary, 0xFF0B1F1C.toInt()))
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, paddingTop, padding, paddingBottom)
        }
        dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)
            ?.setTextColor(color(android.R.attr.textColorSecondary, 0xFF9AA0A6.toInt()))
    }.onFailure { android.util.Log.w("DialogStyle", "buttons not styled: ${it.message}") }
}
