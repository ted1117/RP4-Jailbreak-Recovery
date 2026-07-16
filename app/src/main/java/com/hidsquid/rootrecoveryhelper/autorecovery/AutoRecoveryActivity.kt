package com.hidsquid.rootrecoveryhelper.autorecovery

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import com.hidsquid.rootrecoveryhelper.R
import com.hidsquid.rootrecoveryhelper.diagnostics.BootDiagnostics
import com.hidsquid.rootrecoveryhelper.storage.RecoveryPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AutoRecoveryActivity : Activity() {
    private val activityScope = MainScope()
    private val runner = AutoRecoveryRunner()
    private val diagnostics by lazy { BootDiagnostics(applicationContext) }
    private val recoveryPreferences by lazy { RecoveryPreferences(applicationContext) }

    private lateinit var titleText: TextView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var resultText: TextView
    private lateinit var closeButton: Button

    private val resultLines = mutableListOf<String>()
    private var completedModules = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_auto_recovery)

        titleText = findViewById(R.id.autoRecoveryTitleText)
        statusText = findViewById(R.id.autoRecoveryStatusText)
        progressBar = findViewById(R.id.autoRecoveryProgressBar)
        resultText = findViewById(R.id.autoRecoveryResultText)
        closeButton = findViewById<Button>(R.id.autoRecoveryCloseButton).apply {
            setOnClickListener { finish() }
        }

        diagnostics.recordCheckStage(BootDiagnostics.STAGE_AUTO_RECOVERY_RUNNING)
        runAutoRecovery()
    }

    private fun runAutoRecovery() {
        activityScope.launch {
            val result = runner.run { event ->
                withContext(Dispatchers.Main.immediate) {
                    renderEvent(event)
                }
            }
            if (!isFinishing && !isDestroyed) {
                renderCompletion(result)
            }
        }
    }

    private fun renderEvent(event: AutoRecoveryEvent) {
        when (event) {
            is AutoRecoveryEvent.RootResult -> {
                if (event.granted) {
                    appendResult(getString(R.string.auto_recovery_root_granted))
                } else {
                    statusText.setText(R.string.auto_recovery_root_denied)
                    appendResult(getString(R.string.auto_recovery_root_denied))
                }
            }
            is AutoRecoveryEvent.AdbResult -> appendResult(
                getString(
                    if (event.succeeded) {
                        R.string.auto_recovery_adb_enabled
                    } else {
                        R.string.auto_recovery_adb_failed
                    },
                ),
            )
            is AutoRecoveryEvent.ModuleStarted -> {
                statusText.text = getString(
                    R.string.auto_recovery_processing_module,
                    event.moduleId,
                )
            }
            is AutoRecoveryEvent.ModuleFinished -> {
                completedModules += 1
                progressBar.progress = completedModules
                appendResult(describeModuleResult(event))
            }
            is AutoRecoveryEvent.ZygiskResult -> {
                val message = when (event.status) {
                    ZygiskRecoveryStatus.ENABLING -> R.string.auto_recovery_zygisk_enabling
                    ZygiskRecoveryStatus.ENABLED -> R.string.auto_recovery_zygisk_enabled
                    ZygiskRecoveryStatus.ALREADY_ENABLED -> {
                        R.string.auto_recovery_zygisk_already_enabled
                    }
                    ZygiskRecoveryStatus.FAILED -> R.string.auto_recovery_zygisk_failed
                    ZygiskRecoveryStatus.UNSUPPORTED -> {
                        R.string.auto_recovery_zygisk_unsupported
                    }
                }
                statusText.setText(message)
                appendResult(getString(message))
            }
            is AutoRecoveryEvent.Summary -> {
                if (
                    AutoRecoveryDecision.shouldScheduleLsposedSetup(
                        deletedCount = event.deletedCount,
                        failureCount = event.failureCount,
                        zygiskReady = event.zygiskReady,
                        zygiskChanged = event.zygiskChanged,
                    )
                ) {
                    titleText.setText(R.string.auto_recovery_complete_title)
                    persistPendingLsposedSetup()
                }
                diagnostics.recordCheckStage(
                    BootDiagnostics.STAGE_AUTO_RECOVERY_RESULT,
                    "삭제=${event.deletedCount}, 실패=${event.failureCount}, " +
                        "Zygisk=${event.zygiskReady}, Zygisk 변경=${event.zygiskChanged}, " +
                        resultLines.joinToString(separator = "; "),
                )
            }
            is AutoRecoveryEvent.RebootCountdown -> {
                statusText.text = getString(
                    R.string.auto_recovery_reboot_countdown,
                    event.seconds,
                )
                diagnostics.recordCheckStage(
                    BootDiagnostics.STAGE_AUTO_RECOVERY_REBOOT,
                    "${event.seconds}초 후 재부팅",
                )
            }
            is AutoRecoveryEvent.RebootResult -> {
                if (event.status == "REQUESTED") {
                    statusText.setText(R.string.auto_recovery_rebooting)
                } else if (event.status.startsWith("FAILED")) {
                    statusText.setText(R.string.auto_recovery_reboot_failed)
                    appendResult(getString(R.string.auto_recovery_reboot_failed))
                }
            }
        }
    }

    private suspend fun renderCompletion(result: AutoRecoveryRunResult) {
        val detail = buildString {
            append("root=")
            append(result.rootGranted)
            append(", ADB=")
            append(result.adbEnabled)
            append(", 삭제=")
            append(result.deletedCount)
            append(", 실패=")
            append(result.failureCount)
            append(", Zygisk=")
            append(result.zygiskStatus?.name ?: "결과 없음")
            append(", Zygisk 변경=")
            append(result.zygiskChanged)
            append(", exit=")
            append(result.exitCode)
            if (result.startFailure.isNotBlank()) {
                append(", 시작 실패=")
                append(result.startFailure)
            }
        }

        when {
            result.timedOut -> {
                statusText.setText(R.string.auto_recovery_timeout)
                showCloseButton()
                diagnostics.recordCheckStage(BootDiagnostics.STAGE_CHECK_ERROR, detail)
            }
            !result.rootGranted -> {
                statusText.setText(R.string.auto_recovery_root_denied)
                showCloseButton()
                diagnostics.recordCheckStage(
                    BootDiagnostics.STAGE_DELAYED_ADB_ROOT_UNAVAILABLE,
                    detail,
                )
            }
            result.failureCount > 0 || result.exitCode != 0 -> {
                statusText.setText(R.string.auto_recovery_failed)
                showCloseButton()
                diagnostics.recordCheckStage(BootDiagnostics.STAGE_CHECK_ERROR, detail)
            }
            result.deletedCount == 0 && !result.zygiskChanged -> {
                statusText.setText(R.string.auto_recovery_no_changes)
                diagnostics.recordCheckStage(
                    BootDiagnostics.STAGE_AUTO_RECOVERY_RESULT,
                    detail,
                )
                delay(NO_CHANGES_DISPLAY_MILLIS)
                if (!isFinishing && !isDestroyed) {
                    finish()
                }
            }
            !result.rebootRequested -> {
                statusText.setText(R.string.auto_recovery_reboot_failed)
                showCloseButton()
                diagnostics.recordCheckStage(BootDiagnostics.STAGE_CHECK_ERROR, detail)
            }
        }
    }

    private fun describeModuleResult(event: AutoRecoveryEvent.ModuleFinished): String {
        val resultResource = when (event.status) {
            ModuleRecoveryStatus.DELETED -> R.string.auto_recovery_result_deleted
            ModuleRecoveryStatus.ABSENT -> R.string.auto_recovery_result_absent
            ModuleRecoveryStatus.MISSING -> R.string.auto_recovery_result_missing
            ModuleRecoveryStatus.FAILED -> R.string.auto_recovery_result_failed
            ModuleRecoveryStatus.UNKNOWN -> R.string.auto_recovery_result_unknown
        }
        return getString(resultResource, event.moduleId)
    }

    private fun appendResult(line: String) {
        resultLines += line
        resultText.text = resultLines.joinToString(separator = "\n")
    }

    private fun showCloseButton() {
        closeButton.visibility = View.VISIBLE
    }

    private fun persistPendingLsposedSetup() {
        if (!recoveryPreferences.commitPendingLsposedSetup(true)) {
            recoveryPreferences.pendingLsposedSetup = true
        }
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NO_CHANGES_DISPLAY_MILLIS = 1_500L
    }
}
