package com.hidsquid.rootrecoveryhelper.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hidsquid.rootrecoveryhelper.diagnostics.BootDiagnostics
import com.hidsquid.rootrecoveryhelper.root.LsposedModuleState
import com.hidsquid.rootrecoveryhelper.root.RootCommandExecutor
import com.hidsquid.rootrecoveryhelper.root.RootStateChecker
import com.hidsquid.rootrecoveryhelper.recovery.RootRecoveryActivity
import com.hidsquid.rootrecoveryhelper.recovery.LsposedSetupActivity
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
                var adbDescription = "시도 안 함"
                var resultStage = BootDiagnostics.STAGE_DELAYED_ADB_RESULT
                var checkError: String? = null
                var moduleDescription = "확인 안 함"
                var dialogDescription = "실행하지 않음"
                var dialogLaunchFailure: RuntimeException? = null

                try {
                    diagnostics.recordCheckStage(BootDiagnostics.STAGE_DELAYED_ADB_CHECKING_ROOT)
                    val stateChecker = RootStateChecker(
                        RootCommandExecutor(timeoutMillis = DELAYED_COMMAND_TIMEOUT_MILLIS),
                    )
                    val delayedCheck = stateChecker.runDelayedBootActions()
                    if (!delayedCheck.hasRootAccess) {
                        resultStage = BootDiagnostics.STAGE_DELAYED_ADB_ROOT_UNAVAILABLE
                        adbDescription = "루트 권한 없음"
                        diagnostics.recordMainLaunchSkipped(
                            "루트 권한이 없어 LSPosed disable 파일을 확인하지 못함",
                        )
                    } else {
                        adbDescription = if (delayedCheck.adbEnabled) "성공" else "실패"

                        val moduleState = delayedCheck.moduleState
                        moduleDescription = when (moduleState) {
                            LsposedModuleState.DISABLED -> "disable 파일 있음"
                            LsposedModuleState.ENABLED -> "disable 파일 없음"
                            LsposedModuleState.UNKNOWN -> "확인 실패"
                        }
                        val pendingLsposedSetup = RecoveryPreferences(appContext).pendingLsposedSetup
                        val dialogActivity = when {
                            moduleState == LsposedModuleState.DISABLED -> {
                                val preferences = RecoveryPreferences(appContext)
                                if (!preferences.commitPendingLsposedSetup(true)) {
                                    preferences.pendingLsposedSetup = true
                                }
                                RootRecoveryActivity::class.java
                            }
                            moduleState == LsposedModuleState.ENABLED && pendingLsposedSetup ->
                                LsposedSetupActivity::class.java
                            else -> null
                        }
                        if (dialogActivity != null) {
                            dialogDescription = if (moduleState == LsposedModuleState.DISABLED) {
                                "루팅 해제 복구 안내"
                            } else {
                                "LSPosed 후속 설정 안내"
                            }
                            val launchFailure = startRecoveryDialog(appContext, dialogActivity)
                            dialogLaunchFailure = launchFailure
                            if (launchFailure == null) {
                                diagnostics.recordMainLaunchRequested()
                            } else {
                                diagnostics.recordMainLaunchFailed(launchFailure)
                            }
                        } else {
                            diagnostics.recordMainLaunchSkipped(
                                "복구 Dialog 조건 불충족: disable 파일=$moduleDescription, " +
                                    "후속 설정 대기=$pendingLsposedSetup, ${delayedCheck.detail}",
                            )
                        }
                    }
                } catch (exception: Exception) {
                    resultStage = BootDiagnostics.STAGE_CHECK_ERROR
                    checkError = exception.toDiagnosticDetail()
                    adbDescription = "예외: $checkError"
                    diagnostics.recordMainLaunchSkipped(
                        "LSPosed 상태를 확인하지 못해 복구 Dialog를 실행하지 않음",
                    )
                }

                diagnostics.recordCheckStage(
                    resultStage,
                    buildString {
                        append("약 10초 지연 후 ADB 활성화=")
                        append(adbDescription)
                        append(", LSPosed disable 파일=")
                        append(moduleDescription)
                        append(", Dialog=")
                        append(
                            when {
                                dialogLaunchFailure == null && dialogDescription != "실행하지 않음" ->
                                    "$dialogDescription 실행 요청 전달됨"
                                moduleDescription == "확인 안 함" -> "실행하지 않음"
                                else -> "실행하지 않음 또는 요청 실패"
                            },
                        )
                        if (dialogLaunchFailure != null) {
                            append(" (")
                            append(dialogLaunchFailure.toLaunchDescription())
                            append(')')
                        }
                        if (checkError != null) {
                            append(", 검사 예외=")
                            append(checkError)
                        }
                    },
                )
            } catch (exception: Exception) {
                runCatching {
                    diagnostics.recordCheckStage(
                        BootDiagnostics.STAGE_CHECK_ERROR,
                        exception.toDiagnosticDetail(),
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun startRecoveryDialog(
        context: Context,
        activityClass: Class<*>,
    ): RuntimeException? {
        val intent = Intent(context, activityClass)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return try {
            context.startActivity(intent)
            null
        } catch (exception: RuntimeException) {
            exception
        }
    }

    private fun RuntimeException?.toLaunchDescription(): String =
        this?.toDiagnosticDetail() ?: "전달됨(표시 성공 여부 확인 불가)"

    private fun RuntimeException.toDiagnosticDetail(): String {
        val type = javaClass.simpleName.ifBlank { javaClass.name }
        val message = message.orEmpty()
        return if (message.isBlank()) type else "$type: $message"
    }

    private fun Exception.toDiagnosticDetail(): String {
        val type = javaClass.simpleName.ifBlank { javaClass.name }
        val message = message.orEmpty()
        return if (message.isBlank()) type else "$type: $message"
    }

    companion object {
        const val ACTION_DELAYED_ADB =
            "com.hidsquid.rootrecoveryhelper.action.DELAYED_ADB"

        private const val DELAYED_COMMAND_TIMEOUT_MILLIS = 2_000L
    }
}
