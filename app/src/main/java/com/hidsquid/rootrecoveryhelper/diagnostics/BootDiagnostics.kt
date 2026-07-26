package com.hidsquid.rootrecoveryhelper.diagnostics

import android.content.Context
import android.util.Log
import com.hidsquid.rootrecoveryhelper.storage.DiagnosticsSettings
import java.io.File
import java.io.IOException

data class BootDiagnosticsSnapshot(
    val bootSequence: Long,
    val bootReceivedAtMillis: Long,
    val mainLaunchStatus: String,
    val mainLaunchDetail: String,
    val checkStage: String,
    val checkDetail: String,
    val adbDiagnostics: String,
    val adbDiagnosticsHistory: String,
    val updatedAtMillis: Long,
) {
    val hasBootRecord: Boolean
        get() = bootSequence > 0L || bootReceivedAtMillis > 0L
}

class BootDiagnostics(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val settings = DiagnosticsSettings(appContext)
    private val legacyBootLogDirectory = File(
        appContext.filesDir,
        BootLogStore.DIRECTORY_NAME,
    )

    fun recordBootReceived() {
        val now = System.currentTimeMillis()
        val sharedBootSequence = if (settings.saveRecentBootLogs) {
            latestSharedBootSequence()
        } else {
            0L
        }
        val currentBootSequence = preferences.getLong(KEY_BOOT_SEQUENCE, 0L)
        val previousBootSequence = maxOf(
            preferences.getLong(
                KEY_LAST_ALLOCATED_BOOT_SEQUENCE,
                currentBootSequence,
            ),
            sharedBootSequence,
        )
        val bootSequence = if (previousBootSequence in 1 until Long.MAX_VALUE) {
            previousBootSequence + 1L
        } else {
            1L
        }
        val saved = preferences.edit()
            .putLong(KEY_BOOT_SEQUENCE, bootSequence)
            .putLong(KEY_LAST_ALLOCATED_BOOT_SEQUENCE, bootSequence)
            .putLong(KEY_BOOT_RECEIVED_AT, now)
            .putString(KEY_MAIN_LAUNCH_STATUS, MAIN_LAUNCH_NOT_ATTEMPTED)
            .putString(KEY_MAIN_LAUNCH_DETAIL, "")
            .putString(KEY_CHECK_STAGE, STAGE_BOOT_RECEIVED)
            .putString(KEY_CHECK_DETAIL, "")
            .putString(KEY_ADB_DIAGNOSTICS, "")
            .putLong(KEY_UPDATED_AT, now)
            .commit()
        Log.i(LOG_TAG, "BOOT_COMPLETED received; diagnosticsSaved=$saved")
        if (saved) {
            startBootLog(
                bootSequence = bootSequence,
                bootReceivedAtMillis = now,
                detail = "DIAGNOSTICS_SAVED=true",
            )
        }
    }

    fun prepareSharedLogStorage(): Boolean {
        if (!settings.saveRecentBootLogs) {
            return false
        }
        return runCatching {
            sharedBootLogStore()
            Log.i(
                LOG_TAG,
                "Shared boot log storage ready; " +
                    SharedBootLogStorage.diagnosticSummary(appContext),
            )
        }.onFailure { exception ->
            logBootStorageFailure("prepare", exception)
        }.isSuccess
    }

    fun recordMainLaunchRequested() {
        updateMainLaunch(MAIN_LAUNCH_REQUESTED, "")
        Log.i(LOG_TAG, "Recovery dialog startActivity request returned normally")
    }

    fun recordMainLaunchScheduled() {
        updateMainLaunch(MAIN_LAUNCH_SCHEDULED, "약 10초 뒤 루팅 상태 및 조건부 ADB 처리 예정")
        Log.i(LOG_TAG, "Root state check and conditional ADB handling scheduled after boot delay")
    }

    fun recordMainLaunchFailed(exception: RuntimeException) {
        val detail = exception.toDiagnosticDetail()
        updateMainLaunch(MAIN_LAUNCH_FAILED, detail)
        Log.e(LOG_TAG, "Recovery screen startActivity request failed: $detail")
    }

    fun recordMainLaunchSkipped(detail: String) {
        val safeDetail = detail.take(MAX_DETAIL_LENGTH)
        updateMainLaunch(MAIN_LAUNCH_SKIPPED, safeDetail)
        Log.i(LOG_TAG, "Auto recovery launch skipped: $safeDetail")
    }

    fun recordCheckStage(stage: String, detail: String = "") {
        val safeDetail = detail.take(MAX_DETAIL_LENGTH)
        val now = System.currentTimeMillis()
        val saved = preferences.edit()
            .putString(KEY_CHECK_STAGE, stage)
            .putString(KEY_CHECK_DETAIL, safeDetail)
            .putLong(KEY_UPDATED_AT, now)
            .commit()
        Log.i(LOG_TAG, "checkStage=$stage; detail=$safeDetail; diagnosticsSaved=$saved")
        appendBootLog(
            recordedAtMillis = now,
            event = "CHECK_STAGE:$stage",
            detail = safeDetail,
        )
    }

    @Synchronized
    fun recordAdbDiagnostics(detail: String) {
        val safeDetail = detail.take(MAX_ADB_DIAGNOSTICS_LENGTH)
        val now = System.currentTimeMillis()
        val bootSequence = preferences.getLong(KEY_BOOT_SEQUENCE, 0L)
        val bootReceivedAtMillis = preferences.getLong(KEY_BOOT_RECEIVED_AT, 0L)
        val currentHistory = preferences.getString(KEY_ADB_DIAGNOSTICS_HISTORY, "").orEmpty()
        val history = if (settings.saveRecentBootLogs) {
            AdbDiagnosticsHistory.append(
                history = currentHistory,
                bootSequence = bootSequence,
                bootReceivedAtMillis = bootReceivedAtMillis,
                recordedAtMillis = now,
                diagnostics = safeDetail,
            )
        } else {
            currentHistory
        }
        val saved = preferences.edit()
            .putString(KEY_ADB_DIAGNOSTICS, safeDetail)
            .putString(KEY_ADB_DIAGNOSTICS_HISTORY, history)
            .putLong(KEY_UPDATED_AT, now)
            .commit()
        Log.i(
            LOG_TAG,
            "ADB_DIAGNOSTICS_BEGIN; boot=$bootReceivedAtMillis; diagnosticsSaved=$saved",
        )
        safeDetail.lineSequence().forEach { line ->
            Log.i(LOG_TAG, "ADB_DIAG $line")
        }
        Log.i(LOG_TAG, "ADB_DIAGNOSTICS_END")
        appendBootLog(
            recordedAtMillis = now,
            event = "ADB_DIAGNOSTICS",
            detail = safeDetail,
        )
    }

    fun snapshot(): BootDiagnosticsSnapshot = BootDiagnosticsSnapshot(
        bootSequence = preferences.getLong(KEY_BOOT_SEQUENCE, 0L),
        bootReceivedAtMillis = preferences.getLong(KEY_BOOT_RECEIVED_AT, 0L),
        mainLaunchStatus = preferences.getString(
            KEY_MAIN_LAUNCH_STATUS,
            MAIN_LAUNCH_NOT_ATTEMPTED,
        ).orEmpty(),
        mainLaunchDetail = preferences.getString(KEY_MAIN_LAUNCH_DETAIL, "").orEmpty(),
        checkStage = preferences.getString(KEY_CHECK_STAGE, STAGE_NOT_RECORDED).orEmpty(),
        checkDetail = preferences.getString(KEY_CHECK_DETAIL, "").orEmpty(),
        adbDiagnostics = preferences.getString(KEY_ADB_DIAGNOSTICS, "").orEmpty(),
        adbDiagnosticsHistory = preferences.getString(
            KEY_ADB_DIAGNOSTICS_HISTORY,
            "",
        ).orEmpty(),
        updatedAtMillis = preferences.getLong(KEY_UPDATED_AT, 0L),
    )

    private fun updateMainLaunch(status: String, detail: String) {
        val safeDetail = detail.take(MAX_DETAIL_LENGTH)
        val now = System.currentTimeMillis()
        val saved = preferences.edit()
            .putString(KEY_MAIN_LAUNCH_STATUS, status)
            .putString(KEY_MAIN_LAUNCH_DETAIL, safeDetail)
            .putLong(KEY_UPDATED_AT, now)
            .commit()
        if (!saved) {
            Log.e(LOG_TAG, "Failed to persist recovery screen launch diagnostics")
        }
        appendBootLog(
            recordedAtMillis = now,
            event = "MAIN_LAUNCH:$status",
            detail = safeDetail,
        )
    }

    private fun startBootLog(
        bootSequence: Long,
        bootReceivedAtMillis: Long,
        detail: String,
    ) {
        if (!settings.saveRecentBootLogs) {
            return
        }
        runCatching {
            sharedBootLogStore().startBoot(
                bootSequence = bootSequence,
                bootReceivedAtMillis = bootReceivedAtMillis,
                recordedAtMillis = bootReceivedAtMillis,
                detail = detail,
            )
        }.onFailure { exception ->
            logBootStorageFailure("start", exception)
            stageBootLogStart(
                bootSequence = bootSequence,
                bootReceivedAtMillis = bootReceivedAtMillis,
                detail = detail,
            )
        }
    }

    private fun appendBootLog(
        recordedAtMillis: Long,
        event: String,
        detail: String,
    ) {
        if (!settings.saveRecentBootLogs) {
            return
        }
        val bootSequence = preferences.getLong(KEY_BOOT_SEQUENCE, 0L)
        val bootReceivedAtMillis = preferences.getLong(KEY_BOOT_RECEIVED_AT, 0L)
        if (bootSequence <= 0L) {
            return
        }
        runCatching {
            sharedBootLogStore().append(
                bootSequence = bootSequence,
                bootReceivedAtMillis = bootReceivedAtMillis,
                recordedAtMillis = recordedAtMillis,
                event = event,
                detail = detail,
            )
        }.onFailure { exception ->
            logBootStorageFailure("append", exception)
            stageBootLogAppend(
                bootSequence = bootSequence,
                bootReceivedAtMillis = bootReceivedAtMillis,
                recordedAtMillis = recordedAtMillis,
                event = event,
                detail = detail,
            )
        }
    }

    private fun stageBootLogStart(
        bootSequence: Long,
        bootReceivedAtMillis: Long,
        detail: String,
    ) {
        runCatching {
            BootLogStore(legacyBootLogDirectory).startBoot(
                bootSequence = bootSequence,
                bootReceivedAtMillis = bootReceivedAtMillis,
                recordedAtMillis = bootReceivedAtMillis,
                detail = detail,
            )
        }.onSuccess {
            Log.w(LOG_TAG, "Shared storage unavailable; boot log staged privately")
        }.onFailure { exception ->
            Log.e(LOG_TAG, "Failed to stage boot log privately", exception)
        }
    }

    private fun stageBootLogAppend(
        bootSequence: Long,
        bootReceivedAtMillis: Long,
        recordedAtMillis: Long,
        event: String,
        detail: String,
    ) {
        runCatching {
            BootLogStore(legacyBootLogDirectory).append(
                bootSequence = bootSequence,
                bootReceivedAtMillis = bootReceivedAtMillis,
                recordedAtMillis = recordedAtMillis,
                event = event,
                detail = detail,
            )
        }.onSuccess {
            Log.w(LOG_TAG, "Shared storage unavailable; boot log event staged privately")
        }.onFailure { exception ->
            Log.e(LOG_TAG, "Failed to stage boot log event privately", exception)
        }
    }

    private fun latestSharedBootSequence(): Long = runCatching {
        sharedBootLogStore().latestBootSequence()
    }.onFailure { exception ->
        logBootStorageFailure("read latest sequence", exception)
    }.getOrDefault(0L)

    private fun sharedBootLogStore(): BootLogStore {
        val store = BootLogStore(
            SharedBootLogStorage.requireDirectory(appContext),
        )
        val importResult = store.importFrom(legacyBootLogDirectory)
        reconcileBootSequences(store, importResult)
        return store
    }

    private fun reconcileBootSequences(
        store: BootLogStore,
        importResult: BootLogImportResult,
    ) {
        val currentSequence = preferences.getLong(KEY_BOOT_SEQUENCE, 0L)
        var latestSequence = importResult.latestBootSequence
        var migratedCurrentSequence = importResult.sequenceMappings[currentSequence]
            ?: currentSequence
        if (
            currentSequence > 0L &&
            !importResult.sequenceMappings.containsKey(currentSequence) &&
            store.hasBootLog(currentSequence) &&
            store.bootReceivedAtMillis(currentSequence) != preferences.getLong(
                KEY_BOOT_RECEIVED_AT,
                0L,
            )
        ) {
            if (latestSequence == Long.MAX_VALUE) {
                throw IOException("Boot log sequence is exhausted")
            }
            migratedCurrentSequence = latestSequence + 1L
            latestSequence = migratedCurrentSequence
        }
        val lastAllocatedSequence = maxOf(
            preferences.getLong(
                KEY_LAST_ALLOCATED_BOOT_SEQUENCE,
                currentSequence,
            ),
            migratedCurrentSequence,
            latestSequence,
        )
        if (
            migratedCurrentSequence == currentSequence &&
            lastAllocatedSequence == preferences.getLong(
                KEY_LAST_ALLOCATED_BOOT_SEQUENCE,
                currentSequence,
            )
        ) {
            return
        }

        val saved = preferences.edit()
            .putLong(KEY_BOOT_SEQUENCE, migratedCurrentSequence)
            .putLong(KEY_LAST_ALLOCATED_BOOT_SEQUENCE, lastAllocatedSequence)
            .commit()
        if (!saved) {
            throw IOException("Failed to persist migrated boot log sequence")
        }
    }

    private fun logBootStorageFailure(action: String, exception: Throwable) {
        val storageState = runCatching {
            SharedBootLogStorage.diagnosticSummary(appContext)
        }.getOrElse { diagnosticFailure ->
            "diagnostic unavailable: ${diagnosticFailure.toDiagnosticDetail()}"
        }
        Log.e(
            LOG_TAG,
            "Shared boot log $action failed; $storageState",
            exception,
        )
    }

    companion object {
        private const val LOG_TAG = "RootRecoveryBoot"

        private const val MAIN_LAUNCH_NOT_ATTEMPTED = "NOT_ATTEMPTED"
        const val MAIN_LAUNCH_SCHEDULED = "SCHEDULED"
        const val MAIN_LAUNCH_REQUESTED = "REQUESTED"
        const val MAIN_LAUNCH_FAILED = "FAILED"
        const val MAIN_LAUNCH_SKIPPED = "SKIPPED"

        private const val STAGE_NOT_RECORDED = "NOT_RECORDED"
        const val STAGE_BOOT_RECEIVED = "BOOT_RECEIVED"
        const val STAGE_ROOT_UNAVAILABLE = "ROOT_UNAVAILABLE"
        const val STAGE_ADB_SCHEDULED = "ADB_SCHEDULED"
        const val STAGE_ADB_SCHEDULE_FAILED = "ADB_SCHEDULE_FAILED"
        const val STAGE_CHECKING_MODULE = "CHECKING_MODULE"
        const val STAGE_MODULE_CHECK_FAILED = "MODULE_CHECK_FAILED"
        const val STAGE_NORMAL = "NORMAL"
        const val STAGE_CHECK_ERROR = "CHECK_ERROR"
        const val STAGE_AUTO_RECOVERY_LAUNCH = "AUTO_RECOVERY_LAUNCH"
        const val STAGE_AUTO_RECOVERY_RUNNING = "AUTO_RECOVERY_RUNNING"
        const val STAGE_AUTO_RECOVERY_RESULT = "AUTO_RECOVERY_RESULT"
        const val STAGE_AUTO_RECOVERY_REBOOT = "AUTO_RECOVERY_REBOOT"

        private const val PREFERENCES_NAME = "boot_diagnostics"
        private const val KEY_BOOT_SEQUENCE = "boot_sequence"
        private const val KEY_LAST_ALLOCATED_BOOT_SEQUENCE =
            "last_allocated_boot_sequence"
        private const val KEY_BOOT_RECEIVED_AT = "boot_received_at"
        private const val KEY_MAIN_LAUNCH_STATUS = "main_launch_status"
        private const val KEY_MAIN_LAUNCH_DETAIL = "main_launch_detail"
        private const val KEY_CHECK_STAGE = "check_stage"
        private const val KEY_CHECK_DETAIL = "check_detail"
        private const val KEY_ADB_DIAGNOSTICS = "adb_diagnostics"
        private const val KEY_ADB_DIAGNOSTICS_HISTORY = "adb_diagnostics_history"
        private const val KEY_UPDATED_AT = "updated_at"
        private const val MAX_DETAIL_LENGTH = 500
        private const val MAX_ADB_DIAGNOSTICS_LENGTH = 4_000
    }
}
