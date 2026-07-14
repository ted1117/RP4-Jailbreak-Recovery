package com.hidsquid.rootrecoveryhelper.diagnostics

import android.content.Context
import android.util.Log

data class BootDiagnosticsSnapshot(
    val bootReceivedAtMillis: Long,
    val mainLaunchStatus: String,
    val mainLaunchDetail: String,
    val checkStage: String,
    val checkDetail: String,
    val updatedAtMillis: Long,
) {
    val hasBootRecord: Boolean
        get() = bootReceivedAtMillis > 0L
}

class BootDiagnostics(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun recordBootReceived() {
        val now = System.currentTimeMillis()
        val saved = preferences.edit()
            .putLong(KEY_BOOT_RECEIVED_AT, now)
            .putString(KEY_MAIN_LAUNCH_STATUS, MAIN_LAUNCH_NOT_ATTEMPTED)
            .putString(KEY_MAIN_LAUNCH_DETAIL, "")
            .putString(KEY_CHECK_STAGE, STAGE_BOOT_RECEIVED)
            .putString(KEY_CHECK_DETAIL, "")
            .putLong(KEY_UPDATED_AT, now)
            .commit()
        Log.i(LOG_TAG, "BOOT_COMPLETED received; diagnosticsSaved=$saved")
    }

    fun recordMainLaunchRequested() {
        updateMainLaunch(MAIN_LAUNCH_REQUESTED, "")
        Log.i(LOG_TAG, "Recovery dialog startActivity request returned normally")
    }

    fun recordMainLaunchScheduled() {
        updateMainLaunch(MAIN_LAUNCH_SCHEDULED, "약 10초 뒤 ADB 실행 후 복구 안내 Dialog 확인 예정")
        Log.i(LOG_TAG, "Recovery dialog check scheduled after delayed ADB activation")
    }

    fun recordMainLaunchFailed(exception: RuntimeException) {
        val detail = exception.toDiagnosticDetail()
        updateMainLaunch(MAIN_LAUNCH_FAILED, detail)
        Log.e(LOG_TAG, "Recovery dialog startActivity request failed: $detail")
    }

    fun recordMainLaunchSkipped(detail: String) {
        val safeDetail = detail.take(MAX_DETAIL_LENGTH)
        updateMainLaunch(MAIN_LAUNCH_SKIPPED, safeDetail)
        Log.i(LOG_TAG, "Recovery dialog launch skipped: $safeDetail")
    }

    fun recordCheckStage(stage: String, detail: String = "") {
        val safeDetail = detail.take(MAX_DETAIL_LENGTH)
        val saved = preferences.edit()
            .putString(KEY_CHECK_STAGE, stage)
            .putString(KEY_CHECK_DETAIL, safeDetail)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .commit()
        Log.i(LOG_TAG, "checkStage=$stage; detail=$safeDetail; diagnosticsSaved=$saved")
    }

    fun snapshot(): BootDiagnosticsSnapshot = BootDiagnosticsSnapshot(
        bootReceivedAtMillis = preferences.getLong(KEY_BOOT_RECEIVED_AT, 0L),
        mainLaunchStatus = preferences.getString(
            KEY_MAIN_LAUNCH_STATUS,
            MAIN_LAUNCH_NOT_ATTEMPTED,
        ).orEmpty(),
        mainLaunchDetail = preferences.getString(KEY_MAIN_LAUNCH_DETAIL, "").orEmpty(),
        checkStage = preferences.getString(KEY_CHECK_STAGE, STAGE_NOT_RECORDED).orEmpty(),
        checkDetail = preferences.getString(KEY_CHECK_DETAIL, "").orEmpty(),
        updatedAtMillis = preferences.getLong(KEY_UPDATED_AT, 0L),
    )

    private fun updateMainLaunch(status: String, detail: String) {
        val saved = preferences.edit()
            .putString(KEY_MAIN_LAUNCH_STATUS, status)
            .putString(KEY_MAIN_LAUNCH_DETAIL, detail.take(MAX_DETAIL_LENGTH))
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .commit()
        if (!saved) {
            Log.e(LOG_TAG, "Failed to persist recovery dialog launch diagnostics")
        }
    }

    private fun RuntimeException.toDiagnosticDetail(): String {
        val type = javaClass.simpleName.ifBlank { javaClass.name }
        val message = message.orEmpty()
        return if (message.isBlank()) type else "$type: $message"
    }

    companion object {
        const val LOG_TAG = "RootRecoveryBoot"

        const val MAIN_LAUNCH_NOT_ATTEMPTED = "NOT_ATTEMPTED"
        const val MAIN_LAUNCH_SCHEDULED = "SCHEDULED"
        const val MAIN_LAUNCH_REQUESTED = "REQUESTED"
        const val MAIN_LAUNCH_FAILED = "FAILED"
        const val MAIN_LAUNCH_SKIPPED = "SKIPPED"

        const val STAGE_NOT_RECORDED = "NOT_RECORDED"
        const val STAGE_BOOT_RECEIVED = "BOOT_RECEIVED"
        const val STAGE_CHECKING_ROOT = "CHECKING_ROOT"
        const val STAGE_ROOT_UNAVAILABLE = "ROOT_UNAVAILABLE"
        const val STAGE_ADB_SCHEDULED = "ADB_SCHEDULED"
        const val STAGE_ADB_SCHEDULE_FAILED = "ADB_SCHEDULE_FAILED"
        const val STAGE_ENABLING_ADB = "ENABLING_ADB"
        const val STAGE_CHECKING_MODULE = "CHECKING_MODULE"
        const val STAGE_MODULE_CHECK_FAILED = "MODULE_CHECK_FAILED"
        const val STAGE_LSPOSED_DISABLED = "LSPOSED_DISABLED"
        const val STAGE_LSPOSED_REENABLED = "LSPOSED_REENABLED"
        const val STAGE_NORMAL = "NORMAL"
        const val STAGE_CHECK_ERROR = "CHECK_ERROR"
        const val STAGE_DELAYED_ADB_CHECKING_ROOT = "DELAYED_ADB_CHECKING_ROOT"
        const val STAGE_DELAYED_ADB_ROOT_UNAVAILABLE = "DELAYED_ADB_ROOT_UNAVAILABLE"
        const val STAGE_DELAYED_ADB_RESULT = "DELAYED_ADB_RESULT"

        private const val PREFERENCES_NAME = "boot_diagnostics"
        private const val KEY_BOOT_RECEIVED_AT = "boot_received_at"
        private const val KEY_MAIN_LAUNCH_STATUS = "main_launch_status"
        private const val KEY_MAIN_LAUNCH_DETAIL = "main_launch_detail"
        private const val KEY_CHECK_STAGE = "check_stage"
        private const val KEY_CHECK_DETAIL = "check_detail"
        private const val KEY_UPDATED_AT = "updated_at"
        private const val MAX_DETAIL_LENGTH = 500
    }
}
