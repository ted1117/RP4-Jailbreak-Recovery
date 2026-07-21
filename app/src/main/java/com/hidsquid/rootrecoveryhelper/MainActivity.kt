package com.hidsquid.rootrecoveryhelper

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import com.hidsquid.rootrecoveryhelper.diagnostics.BootDiagnostics
import com.hidsquid.rootrecoveryhelper.diagnostics.BootDiagnosticsSnapshot
import com.hidsquid.rootrecoveryhelper.root.AdbActivationReason
import com.hidsquid.rootrecoveryhelper.root.LsposedModuleState
import com.hidsquid.rootrecoveryhelper.root.RootCommandExecutor
import com.hidsquid.rootrecoveryhelper.root.RootStateChecker
import com.hidsquid.rootrecoveryhelper.root.ZygiskState
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private val activityScope = MainScope()
    private val stateChecker = RootStateChecker(RootCommandExecutor())

    private lateinit var statusText: TextView
    private lateinit var adbTestStatusText: TextView
    private lateinit var moduleStatusText: TextView
    private lateinit var bootDiagnosticsText: TextView

    private val bootDiagnostics by lazy { BootDiagnostics(applicationContext) }
    private val dateFormat by lazy {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        adbTestStatusText = findViewById(R.id.adbTestStatusText)
        moduleStatusText = findViewById(R.id.moduleStatusText)
        bootDiagnosticsText = findViewById(R.id.bootDiagnosticsText)
        findViewById<Button>(R.id.refreshDiagnosticsButton).setOnClickListener {
            renderBootDiagnostics()
        }
        renderBootDiagnostics()
        checkRootAndModuleState()
    }

    override fun onResume() {
        super.onResume()
        renderBootDiagnostics()
    }

    private fun checkRootAndModuleState() {
        statusText.setText(R.string.root_checking)
        moduleStatusText.text = ""

        activityScope.launch {
            val check = stateChecker.runDelayedBootActions()
            if (!check.hasRootAccess) {
                statusText.setText(R.string.root_required)
                adbTestStatusText.setText(R.string.adb_test_mode_root_unavailable)
                renderBootDiagnostics()
                return@launch
            }

            statusText.setText(R.string.setup_complete)
            adbTestStatusText.setText(
                when {
                    check.adbActivationReason == AdbActivationReason.NORMAL ->
                        R.string.adb_test_mode_not_needed
                    check.adbEnabled -> R.string.adb_test_mode_enabled
                    else -> R.string.adb_test_mode_failed
                },
            )
            val moduleStatusResource = when {
                check.moduleState == LsposedModuleState.DISABLED ||
                    check.zygiskState == ZygiskState.DISABLED ->
                    R.string.module_state_disabled
                check.moduleState == LsposedModuleState.UNKNOWN ||
                    check.zygiskState == ZygiskState.UNKNOWN ->
                    R.string.module_state_unknown
                else -> R.string.module_state_normal
            }
            moduleStatusText.setText(moduleStatusResource)
            renderBootDiagnostics()
        }
    }

    private fun renderBootDiagnostics() {
        if (!::bootDiagnosticsText.isInitialized) {
            return
        }

        val snapshot = bootDiagnostics.snapshot()
        if (!snapshot.hasBootRecord) {
            bootDiagnosticsText.setText(R.string.boot_diagnostics_no_record)
            return
        }

        val launchDescription = describeMainLaunch(snapshot)
        val stageDescription = describeCheckStage(snapshot.checkStage)
        val detail = snapshot.checkDetail.ifBlank {
            getString(R.string.boot_diagnostics_detail_empty)
        }
        val adbDiagnostics = snapshot.adbDiagnostics.ifBlank {
            getString(R.string.boot_diagnostics_detail_empty)
        }
        val adbDiagnosticsHistory = snapshot.adbDiagnosticsHistory.ifBlank {
            getString(R.string.boot_diagnostics_detail_empty)
        }
        val interpretation = buildString {
            append(getString(R.string.boot_diagnostics_hint_receiver_missing))
            append('\n')
            append(
                when (snapshot.mainLaunchStatus) {
                    BootDiagnostics.MAIN_LAUNCH_REQUESTED -> getString(
                        R.string.boot_diagnostics_hint_launch_requested,
                    )
                    BootDiagnostics.MAIN_LAUNCH_FAILED -> getString(
                        R.string.boot_diagnostics_hint_launch_failed,
                    )
                    BootDiagnostics.MAIN_LAUNCH_SKIPPED -> getString(
                        R.string.boot_diagnostics_hint_launch_skipped,
                    )
                    else -> getString(R.string.boot_diagnostics_hint_in_progress)
                },
            )
        }

        bootDiagnosticsText.text = getString(
            R.string.boot_diagnostics_format,
            formatTimestamp(snapshot.bootReceivedAtMillis),
            formatTimestamp(snapshot.updatedAtMillis),
            launchDescription,
            stageDescription,
            detail,
            adbDiagnostics,
            adbDiagnosticsHistory,
            interpretation,
        )
    }

    private fun describeMainLaunch(snapshot: BootDiagnosticsSnapshot): String =
        when (snapshot.mainLaunchStatus) {
            BootDiagnostics.MAIN_LAUNCH_SCHEDULED -> getString(
                R.string.boot_diagnostics_launch_scheduled,
            )
            BootDiagnostics.MAIN_LAUNCH_REQUESTED -> getString(
                R.string.boot_diagnostics_launch_requested,
            )
            BootDiagnostics.MAIN_LAUNCH_FAILED -> getString(
                R.string.boot_diagnostics_launch_failed,
                snapshot.mainLaunchDetail.ifBlank {
                    getString(R.string.boot_diagnostics_detail_empty)
                },
            )
            BootDiagnostics.MAIN_LAUNCH_SKIPPED -> getString(
                R.string.boot_diagnostics_launch_skipped,
                snapshot.mainLaunchDetail.ifBlank {
                    getString(R.string.boot_diagnostics_detail_empty)
                },
            )
            else -> getString(R.string.boot_diagnostics_launch_not_attempted)
        }

    private fun describeCheckStage(stage: String): String = when (stage) {
        BootDiagnostics.STAGE_BOOT_RECEIVED -> getString(
            R.string.boot_diagnostics_stage_boot_received,
        )
        BootDiagnostics.STAGE_CHECKING_ROOT -> getString(
            R.string.boot_diagnostics_stage_checking_root,
        )
        BootDiagnostics.STAGE_ROOT_UNAVAILABLE -> getString(
            R.string.boot_diagnostics_stage_root_unavailable,
        )
        BootDiagnostics.STAGE_ADB_SCHEDULED -> getString(
            R.string.boot_diagnostics_stage_adb_scheduled,
        )
        BootDiagnostics.STAGE_ADB_SCHEDULE_FAILED -> getString(
            R.string.boot_diagnostics_stage_adb_schedule_failed,
        )
        BootDiagnostics.STAGE_ENABLING_ADB -> getString(
            R.string.boot_diagnostics_stage_enabling_adb,
        )
        BootDiagnostics.STAGE_CHECKING_MODULE -> getString(
            R.string.boot_diagnostics_stage_checking_module,
        )
        BootDiagnostics.STAGE_MODULE_CHECK_FAILED -> getString(
            R.string.boot_diagnostics_stage_module_failed,
        )
        BootDiagnostics.STAGE_LSPOSED_DISABLED -> getString(
            R.string.boot_diagnostics_stage_disabled,
        )
        BootDiagnostics.STAGE_LSPOSED_REENABLED -> getString(
            R.string.boot_diagnostics_stage_reenabled,
        )
        BootDiagnostics.STAGE_NORMAL -> getString(R.string.boot_diagnostics_stage_normal)
        BootDiagnostics.STAGE_CHECK_ERROR -> getString(
            R.string.boot_diagnostics_stage_error,
        )
        BootDiagnostics.STAGE_DELAYED_ADB_CHECKING_ROOT -> getString(
            R.string.boot_diagnostics_stage_delayed_adb_root,
        )
        BootDiagnostics.STAGE_DELAYED_ADB_ROOT_UNAVAILABLE -> getString(
            R.string.boot_diagnostics_stage_delayed_adb_unavailable,
        )
        BootDiagnostics.STAGE_DELAYED_ADB_RESULT -> getString(
            R.string.boot_diagnostics_stage_delayed_adb_result,
        )
        BootDiagnostics.STAGE_AUTO_RECOVERY_LAUNCH -> getString(
            R.string.boot_diagnostics_stage_auto_recovery_launch,
        )
        BootDiagnostics.STAGE_AUTO_RECOVERY_RUNNING -> getString(
            R.string.boot_diagnostics_stage_auto_recovery_running,
        )
        BootDiagnostics.STAGE_AUTO_RECOVERY_RESULT -> getString(
            R.string.boot_diagnostics_stage_auto_recovery_result,
        )
        BootDiagnostics.STAGE_AUTO_RECOVERY_REBOOT -> getString(
            R.string.boot_diagnostics_stage_auto_recovery_reboot,
        )
        else -> getString(R.string.boot_diagnostics_stage_not_recorded)
    }

    private fun formatTimestamp(timestampMillis: Long): String =
        if (timestampMillis > 0L) {
            dateFormat.format(Date(timestampMillis))
        } else {
            getString(R.string.boot_diagnostics_detail_empty)
        }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }
}
