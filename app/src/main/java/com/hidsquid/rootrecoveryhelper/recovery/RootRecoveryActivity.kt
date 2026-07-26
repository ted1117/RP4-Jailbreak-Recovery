package com.hidsquid.rootrecoveryhelper.recovery

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.widget.CheckBox
import android.widget.Toast
import com.hidsquid.rootrecoveryhelper.R
import com.hidsquid.rootrecoveryhelper.autorecovery.AutoRecoveryActivity
import com.hidsquid.rootrecoveryhelper.diagnostics.BootDiagnostics
import com.hidsquid.rootrecoveryhelper.storage.RecoveryPreferences

class RootRecoveryActivity : Activity() {
    private lateinit var dialog: AlertDialog
    private val diagnostics by lazy { BootDiagnostics(applicationContext) }
    private val recoveryPreferences by lazy { RecoveryPreferences(applicationContext) }
    private lateinit var ignoreForeverCheckBox: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)

        ignoreForeverCheckBox = CheckBox(this).apply {
            text = getString(R.string.ignore_root_recovery_forever)
            // The platform CheckBox drawable has a small optical inset. Compensate for it
            // so the visible box lines up with the message text above it.
            translationX = -dp(4)
        }
        val dialogContentPadding = resolveDialogPreferredPadding()

        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.root_recovery_title)
            .setMessage(R.string.root_recovery_message)
            .setPositiveButton(R.string.start_root_recovery) { _, _ ->
                openAutoRecovery()
            }
            .setNegativeButton(R.string.ignore_root_recovery, null)
            .setCancelable(false)
            .create()
            .apply {
                setView(
                    ignoreForeverCheckBox,
                    dialogContentPadding,
                    0,
                    dialogContentPadding,
                    0,
                )
                setCanceledOnTouchOutside(false)
                setOnShowListener {
                    RecoveryDialogStyler.apply(this)
                    getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                        dismiss()
                        if (ignoreForeverCheckBox.isChecked) {
                            showPermanentIgnoreConfirmation()
                        } else {
                            ignoreRootRecovery()
                        }
                    }
                }
                show()
            }
    }

    private fun resolveDialogPreferredPadding(): Int {
        val value = TypedValue()
        if (!theme.resolveAttribute(android.R.attr.dialogPreferredPadding, value, true)) {
            return (24 * resources.displayMetrics.density).toInt()
        }
        return TypedValue.complexToDimensionPixelSize(value.data, resources.displayMetrics)
    }

    private fun dp(value: Int): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics,
    )

    private fun ignoreRootRecovery() {
        diagnostics.recordMainLaunchSkipped(
            "사용자가 탈옥 복구를 무시함; 모듈을 변경하지 않음",
        )
        finish()
    }

    private fun showPermanentIgnoreConfirmation() {
        AlertDialog.Builder(this)
            .setTitle(R.string.ignore_root_recovery_confirmation_title)
            .setMessage(R.string.ignore_root_recovery_confirmation_message)
            .setNegativeButton(R.string.ignore_root_recovery_confirmation_no) { _, _ ->
                recoveryPreferences.ignoreRecoveryDialogsForever = true
                diagnostics.recordMainLaunchSkipped(
                    "사용자가 탈옥 복구를 영구적으로 무시함; 이후 복구 Dialog를 표시하지 않음",
                )
                finish()
            }
            .setPositiveButton(R.string.ignore_root_recovery_confirmation_recover) { _, _ ->
                openAutoRecovery()
            }
            .setCancelable(false)
            .create()
            .apply {
                setCanceledOnTouchOutside(false)
                setOnShowListener {
                    RecoveryDialogStyler.apply(this)
                }
                show()
            }
    }

    private fun openAutoRecovery() {
        val intent = Intent(this, AutoRecoveryActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            startActivity(intent)
            diagnostics.recordCheckStage(
                BootDiagnostics.STAGE_AUTO_RECOVERY_LAUNCH,
                "사용자가 탈옥 복구를 선택하여 자동 삭제 화면 실행",
            )
            finish()
        } catch (exception: RuntimeException) {
            diagnostics.recordMainLaunchFailed(exception)
            Toast.makeText(
                this,
                R.string.auto_recovery_launch_failed,
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onDestroy() {
        if (::dialog.isInitialized && dialog.isShowing) {
            dialog.dismiss()
        }
        super.onDestroy()
    }
}
