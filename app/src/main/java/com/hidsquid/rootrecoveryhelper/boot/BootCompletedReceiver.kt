package com.hidsquid.rootrecoveryhelper.boot

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.hidsquid.rootrecoveryhelper.diagnostics.BootDiagnostics
import com.hidsquid.rootrecoveryhelper.diagnostics.toDiagnosticDetail

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val diagnostics = BootDiagnostics(appContext)
        try {
            diagnostics.recordBootReceived()

            // 임시 테스트 모드: 부팅 직후에는 예약만 하고 약 10초 뒤 루팅 상태를 확인한다.
            // 복구가 필요하거나 상태 확인에 실패한 경우에만 ADB를 강제로 활성화한다.
            val scheduleFailure = scheduleDelayedCheck(appContext)
            if (scheduleFailure == null) {
                diagnostics.recordCheckStage(
                    BootDiagnostics.STAGE_ADB_SCHEDULED,
                    "부팅 직후 검사는 생략하고 약 10초 후 루팅 상태 및 조건부 ADB 처리 예약됨",
                )
                diagnostics.recordMainLaunchScheduled()
            } else {
                diagnostics.recordCheckStage(
                    BootDiagnostics.STAGE_ADB_SCHEDULE_FAILED,
                    scheduleFailure.toDiagnosticDetail(),
                )
            }
        } catch (exception: Exception) {
            diagnostics.recordCheckStage(
                BootDiagnostics.STAGE_CHECK_ERROR,
                exception.toDiagnosticDetail(),
            )
        } finally {
            pendingResult.finish()
        }
    }

    private fun scheduleDelayedCheck(context: Context): RuntimeException? = try {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, DelayedAdbActivationReceiver::class.java).apply {
            action = DelayedAdbActivationReceiver.ACTION_DELAYED_ADB
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            DELAYED_ADB_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + DELAYED_ADB_MILLIS,
            pendingIntent,
        )
        null
    } catch (exception: RuntimeException) {
        exception
    }

    companion object {
        private const val DELAYED_ADB_MILLIS = 10_000L
        private const val DELAYED_ADB_REQUEST_CODE = 6001
    }
}
