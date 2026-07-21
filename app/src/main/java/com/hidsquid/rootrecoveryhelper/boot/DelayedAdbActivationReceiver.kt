package com.hidsquid.rootrecoveryhelper.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hidsquid.rootrecoveryhelper.diagnostics.BootDiagnostics
import com.hidsquid.rootrecoveryhelper.diagnostics.toDiagnosticDetail
import com.hidsquid.rootrecoveryhelper.recovery.LsposedSetupActivity
import com.hidsquid.rootrecoveryhelper.recovery.RootRecoveryActivity
import com.hidsquid.rootrecoveryhelper.root.LsposedModuleState
import com.hidsquid.rootrecoveryhelper.root.RootCommandExecutor
import com.hidsquid.rootrecoveryhelper.root.RootStateChecker
import com.hidsquid.rootrecoveryhelper.root.ZygiskState
import com.hidsquid.rootrecoveryhelper.storage.RecoveryPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DelayedAdbActivationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DELAYED_ADB) {
            return
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val diagnostics = BootDiagnostics(appContext)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                routeDelayedDialog(appContext, diagnostics)
            } catch (exception: Exception) {
                diagnostics.recordMainLaunchSkipped(
                    "루팅 상태 검사 또는 조건부 ADB 처리 예외로 Dialog 실행 안 함",
                )
                diagnostics.recordCheckStage(
                    BootDiagnostics.STAGE_CHECK_ERROR,
                    exception.toDiagnosticDetail(),
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun routeDelayedDialog(
        context: Context,
        diagnostics: BootDiagnostics,
    ) {
        val pendingLsposedSetup = RecoveryPreferences(context).pendingLsposedSetup
        diagnostics.recordCheckStage(
            BootDiagnostics.STAGE_CHECKING_MODULE,
            "LSPosed disable 파일과 Zygisk 설정 확인 후 필요한 경우에만 ADB 활성화",
        )
        val check = RootStateChecker(
            RootCommandExecutor(timeoutMillis = DETECTION_TIMEOUT_MILLIS),
        ).runDelayedBootActions()
        diagnostics.recordAdbDiagnostics(check.adbDiagnostics)
        val target = DelayedDialogTargetResolver.resolve(
            pendingLsposedSetup = pendingLsposedSetup,
            lsposedModuleState = check.moduleState,
            zygiskState = check.zygiskState,
        )

        when (target) {
            DelayedDialogTarget.ROOT_RECOVERY -> launchDialog(
                context = context,
                diagnostics = diagnostics,
                dialogActivity = RootRecoveryActivity::class.java,
                description = "루팅 해제 감지 Dialog",
                checkDetail = check.detail,
            )
            DelayedDialogTarget.LSPOSED_SETUP -> launchDialog(
                context = context,
                diagnostics = diagnostics,
                dialogActivity = LsposedSetupActivity::class.java,
                description = "LSPosed 후속 설정 Dialog",
                checkDetail = check.detail,
            )
            DelayedDialogTarget.NONE -> {
                val detail = when {
                    !check.hasRootAccess -> "루트 권한 없음: ${check.detail}"
                    check.moduleState == LsposedModuleState.ENABLED &&
                        check.zygiskState == ZygiskState.ENABLED ->
                        "LSPosed 모듈과 Zygisk 정상: ${check.detail}"
                    else -> "LSPosed 또는 Zygisk 상태 확인 불가: ${check.detail}"
                }
                diagnostics.recordMainLaunchSkipped(detail)
                diagnostics.recordCheckStage(
                    if (
                        check.moduleState == LsposedModuleState.ENABLED &&
                        check.zygiskState == ZygiskState.ENABLED
                    ) {
                        BootDiagnostics.STAGE_NORMAL
                    } else {
                        BootDiagnostics.STAGE_MODULE_CHECK_FAILED
                    },
                    detail,
                )
            }
        }
    }

    private fun launchDialog(
        context: Context,
        diagnostics: BootDiagnostics,
        dialogActivity: Class<*>,
        description: String,
        checkDetail: String,
    ) {
        diagnostics.recordCheckStage(
            BootDiagnostics.STAGE_AUTO_RECOVERY_LAUNCH,
            "부팅 약 10초 후 $description 실행 요청: $checkDetail",
        )

        val launchIntent = Intent(context, dialogActivity).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
        )
        try {
            context.startActivity(launchIntent)
            diagnostics.recordMainLaunchRequested()
        } catch (exception: RuntimeException) {
            diagnostics.recordMainLaunchFailed(exception)
            diagnostics.recordCheckStage(
                BootDiagnostics.STAGE_CHECK_ERROR,
                exception.toDiagnosticDetail(),
            )
        }
    }

    companion object {
        const val ACTION_DELAYED_ADB =
            "com.hidsquid.rootrecoveryhelper.action.DELAYED_ADB"

        private const val DETECTION_TIMEOUT_MILLIS = 10_000L
    }
}
