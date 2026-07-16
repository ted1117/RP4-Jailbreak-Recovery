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
        val securityExitCode: Int?,
        val settingsExitCode: Int?,
        val daemonExitCode: Int?,
        val securityState: String?,
        val enabledState: String?,
        val daemonState: String?,
    ) : AutoRecoveryEvent {
        val succeeded: Boolean
            get() = securityExitCode == 0 &&
                settingsExitCode == 0 &&
                daemonExitCode == 0 &&
                securityState == "0" &&
                enabledState == "1" &&
                daemonState == "running"
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
                securityExitCode = parts.getOrNull(2)?.toIntOrNull(),
                settingsExitCode = parts.getOrNull(3)?.toIntOrNull(),
                daemonExitCode = parts.getOrNull(4)?.toIntOrNull(),
                securityState = parts.getOrNull(5),
                enabledState = parts.getOrNull(6),
                daemonState = parts.getOrNull(7),
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
