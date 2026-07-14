package com.hidsquid.rootrecoveryhelper.boot

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.hidsquid.rootrecoveryhelper.diagnostics.BootDiagnostics

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

            // 임시 테스트 모드: 부팅 직후에는 예약만 하고 약 10초 뒤에 상태를 확인한다.
            val scheduleFailure = scheduleDelayedCheck(appContext)
            if (scheduleFailure == null) {
                diagnostics.recordCheckStage(
                    BootDiagnostics.STAGE_ADB_SCHEDULED,
                    "부팅 직후 검사는 생략하고 약 10초 후 ADB·복구 상태 확인 예약됨",
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
        private const val DELAYED_ADB_MILLIS = 10_000L
        private const val DELAYED_ADB_REQUEST_CODE = 6001
    }
}
