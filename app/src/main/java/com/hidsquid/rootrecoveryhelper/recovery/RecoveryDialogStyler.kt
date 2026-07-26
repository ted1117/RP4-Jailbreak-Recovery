package com.hidsquid.rootrecoveryhelper.recovery

import android.app.AlertDialog
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

internal object RecoveryDialogStyler {
    fun apply(dialog: AlertDialog) {
        val titleTemplate = dialog.findViewById<View>(dialog.androidId("title_template"))
        val contentPanel = dialog.findViewById<View>(dialog.androidId("contentPanel"))
        val message = dialog.findViewById<TextView>(android.R.id.message)

        titleTemplate?.updateMargins(bottom = dialog.dp(16))
        contentPanel?.minimumHeight = 0
        message?.updateMargins(bottom = dialog.dp(12))
    }

    private fun AlertDialog.androidId(name: String): Int =
        context.resources.getIdentifier(name, "id", "android")

    private fun AlertDialog.dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics,
        ).toInt()

    private fun View.updateMargins(
        left: Int? = null,
        top: Int? = null,
        right: Int? = null,
        bottom: Int? = null,
    ) {
        val params = layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (left != null) params.leftMargin = left
        if (top != null) params.topMargin = top
        if (right != null) params.rightMargin = right
        if (bottom != null) params.bottomMargin = bottom
        layoutParams = params
    }
}
