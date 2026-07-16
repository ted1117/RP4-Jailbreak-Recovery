package com.hidsquid.rootrecoveryhelper.autorecovery

enum class ModuleRecoveryStatus {
    DELETED,
    ABSENT,
    MISSING,
    FAILED,
    UNKNOWN,
}

enum class ZygiskRecoveryStatus {
    ENABLING,
    ENABLED,
    ALREADY_ENABLED,
    FAILED,
    UNSUPPORTED,
    ;

    val ready: Boolean
        get() = this == ENABLED || this == ALREADY_ENABLED
}

sealed interface AutoRecoveryEvent {
    data class RootResult(val granted: Boolean) : AutoRecoveryEvent

    data class AdbResult(
        val settingsExitCode: Int?,
        val daemonExitCode: Int?,
    ) : AutoRecoveryEvent {
        val succeeded: Boolean
            get() = settingsExitCode == 0 && daemonExitCode == 0
    }

    data class ModuleStarted(val moduleId: String) : AutoRecoveryEvent

    data class ModuleFinished(
        val moduleId: String,
        val status: ModuleRecoveryStatus,
    ) : AutoRecoveryEvent

    data class ZygiskResult(
        val status: ZygiskRecoveryStatus,
    ) : AutoRecoveryEvent

    data class Summary(
        val deletedCount: Int,
        val failureCount: Int,
        val zygiskReady: Boolean,
        val zygiskChanged: Boolean,
    ) : AutoRecoveryEvent

    data class RebootCountdown(val seconds: Int) : AutoRecoveryEvent

    data class RebootResult(val status: String) : AutoRecoveryEvent
}

object AutoRecoveryProtocol {
    private const val PREFIX = "RRH|"

    fun parse(line: String): AutoRecoveryEvent? {
        if (!line.startsWith(PREFIX)) {
            return null
        }

        val parts = line.split('|')
        return when (parts.getOrNull(1)) {
            "ROOT" -> AutoRecoveryEvent.RootResult(parts.getOrNull(2) == "OK")
            "ADB" -> AutoRecoveryEvent.AdbResult(
                settingsExitCode = parts.getOrNull(2)?.toIntOrNull(),
                daemonExitCode = parts.getOrNull(3)?.toIntOrNull(),
            )
            "MODULE_START" -> parts.getOrNull(2)?.let(AutoRecoveryEvent::ModuleStarted)
            "MODULE_RESULT" -> {
                val moduleId = parts.getOrNull(2) ?: return null
                val status = parts.getOrNull(3)
                    ?.let { runCatching { ModuleRecoveryStatus.valueOf(it) }.getOrNull() }
                    ?: return null
                AutoRecoveryEvent.ModuleFinished(moduleId, status)
            }
            "ZYGISK" -> parts.getOrNull(2)
                ?.let { runCatching { ZygiskRecoveryStatus.valueOf(it) }.getOrNull() }
                ?.let(AutoRecoveryEvent::ZygiskResult)
            "SUMMARY" -> AutoRecoveryEvent.Summary(
                deletedCount = parts.getOrNull(2)?.toIntOrNull() ?: 0,
                failureCount = parts.getOrNull(3)?.toIntOrNull() ?: 0,
                zygiskReady = parts.getOrNull(4) == "1",
                zygiskChanged = parts.getOrNull(5) == "1",
            )
            "COUNTDOWN" -> parts.getOrNull(2)?.toIntOrNull()?.let(
                AutoRecoveryEvent::RebootCountdown,
            )
            "REBOOT" -> parts.getOrNull(2)?.let(AutoRecoveryEvent::RebootResult)
            else -> null
        }
    }
}
