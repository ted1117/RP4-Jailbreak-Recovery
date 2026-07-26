package com.hidsquid.rootrecoveryhelper.recovery

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.hidsquid.rootrecoveryhelper.R
import com.hidsquid.rootrecoveryhelper.autorecovery.AutoRecoveryActivity
import com.hidsquid.rootrecoveryhelper.diagnostics.BootDiagnostics

class RootRecoveryActivity : Activity() {
    private lateinit var dialog: AlertDialog
    private val diagnostics by lazy { BootDiagnostics(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)

        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.root_recovery_title)
            .setMessage(R.string.root_recovery_message)
            .setPositiveButton(R.string.start_root_recovery) { _, _ ->
                openAutoRecovery()
            }
            .setNegativeButton(R.string.ignore_root_recovery) { _, _ ->
                diagnostics.recordMainLaunchSkipped(
                    "사용자가 루팅 복구를 무시함; 모듈을 변경하지 않음",
                )
                finish()
            }
            .setCancelable(false)
            .create()
            .apply {
                setCanceledOnTouchOutside(false)
                setOnDismissListener {
                    if (!isFinishing) {
                        finish()
                    }
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
                "사용자가 루팅 복구를 선택하여 자동 삭제 화면 실행",
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
