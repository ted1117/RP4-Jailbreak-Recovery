package com.hidsquid.rootrecoveryhelper.recovery

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

abstract class RecoveryDialogActivity : Activity() {
    private lateinit var dialog: AlertDialog
    private val activityScope: CoroutineScope = MainScope()

    protected abstract val titleRes: Int

    protected abstract val messageRes: Int

    protected abstract val buttonRes: Int

    protected abstract val appNotFoundMessageRes: Int

    protected abstract suspend fun openExternalApp(): Boolean

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)

        dialog = AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setPositiveButton(buttonRes, null)
            .create()
            .apply {
                setCanceledOnTouchOutside(false)
                setOnCancelListener { finish() }
                setOnShowListener {
                    RecoveryDialogStyler.apply(this)
                    val button = getButton(AlertDialog.BUTTON_POSITIVE)
                    button.setOnClickListener {
                        button.isEnabled = false
                        activityScope.launch {
                            val launched = withContext(Dispatchers.IO) {
                                openExternalApp()
                            }
                            if (launched) {
                                dismiss()
                                finish()
                            } else {
                                button.isEnabled = true
                                Toast.makeText(
                                    this@RecoveryDialogActivity,
                                    appNotFoundMessageRes,
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    }
                }
                show()
            }
    }

    override fun onDestroy() {
        activityScope.cancel()
        if (::dialog.isInitialized && dialog.isShowing) {
            dialog.dismiss()
        }
        super.onDestroy()
    }
}
