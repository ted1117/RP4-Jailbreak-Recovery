package com.hidsquid.rootrecoveryhelper.root

enum class LsposedModuleState {
    DISABLED,
    ENABLED,
    UNKNOWN,
}

data class DelayedBootCheckResult(
    val hasRootAccess: Boolean,
    val adbEnabled: Boolean,
    val moduleState: LsposedModuleState,
    val detail: String,
)

class RootStateChecker(
    private val commandRunner: RootCommandRunner,
) {
    suspend fun hasRootAccess(): Boolean {
        val result = commandRunner.execute(ROOT_ID_COMMAND)
        return result.isSuccessful && result.stdout.contains(ROOT_ID_OUTPUT)
    }

    suspend fun getLsposedModuleState(): LsposedModuleState {
        val result = commandRunner.execute("test -f $LSPOSED_DISABLE_PATH")
        if (result.timedOut) {
            return LsposedModuleState.UNKNOWN
        }

        return when (result.exitCode) {
            0 -> LsposedModuleState.DISABLED
            1 -> LsposedModuleState.ENABLED
            else -> LsposedModuleState.UNKNOWN
        }
    }

    suspend fun isLsposedDisabled(): Boolean =
        getLsposedModuleState() == LsposedModuleState.DISABLED

    suspend fun enableAdb(): Boolean {
        val enableSettingResult = commandRunner.execute(ENABLE_ADB_SETTING_COMMAND)
        val startDaemonResult = commandRunner.execute(START_ADB_DAEMON_COMMAND)
        return enableSettingResult.isSuccessful && startDaemonResult.isSuccessful
    }

    suspend fun runDelayedBootActions(): DelayedBootCheckResult {
        val result = commandRunner.execute(DELAYED_BOOT_COMMAND)
        if (result.timedOut) {
            return DelayedBootCheckResult(
                hasRootAccess = false,
                adbEnabled = false,
                moduleState = LsposedModuleState.UNKNOWN,
                detail = "통합 root 명령 timeout",
            )
        }

        val rootUid = result.stdout.markerValue(ROOT_UID_MARKER)
        val hasRoot = rootUid == "0"
        if (!hasRoot) {
            return DelayedBootCheckResult(
                hasRootAccess = false,
                adbEnabled = false,
                moduleState = LsposedModuleState.UNKNOWN,
                detail = "uid=${rootUid ?: "확인 실패"}",
            )
        }

        val adbSettingsExit = result.stdout.markerValue(ADB_SETTINGS_EXIT_MARKER)
        val adbDaemonExit = result.stdout.markerValue(ADB_DAEMON_EXIT_MARKER)
        val moduleLsExit = result.stdout.markerValue(MODULE_LS_EXIT_MARKER)
        val moduleLsOutput = result.stdout.markerValue(MODULE_LS_OUTPUT_MARKER).orEmpty()
        val moduleState = when {
            moduleLsExit == "0" -> LsposedModuleState.DISABLED
            moduleLsExit == "1" && moduleLsOutput.contains("No such file") ->
                LsposedModuleState.ENABLED
            else -> LsposedModuleState.UNKNOWN
        }

        return DelayedBootCheckResult(
            hasRootAccess = true,
            adbEnabled = adbSettingsExit == "0" && adbDaemonExit == "0",
            moduleState = moduleState,
            detail = "ADB settings exit=$adbSettingsExit, adbd exit=$adbDaemonExit, " +
                "disable 검사 exit=$moduleLsExit${moduleLsOutput.takeIf { it.isNotBlank() }?.let { ", 출력=$it" } ?: ""}",
        )
    }

    private val CommandResult.isSuccessful: Boolean
        get() = !timedOut && exitCode == 0

    private fun String.markerValue(marker: String): String? =
        lineSequence()
            .firstOrNull { it.startsWith("$marker=") }
            ?.substringAfter('=')

    companion object {
        const val LSPOSED_DISABLE_PATH = "/data/adb/modules/zygisk_lsposed/disable"

        const val ROOT_ID_COMMAND = "id"
        const val ENABLE_ADB_SETTING_COMMAND = "settings put global adb_enabled 1"
        const val START_ADB_DAEMON_COMMAND = "setprop ctl.start adbd"

        private const val ROOT_UID_MARKER = "ROOT_UID"
        private const val ADB_SETTINGS_EXIT_MARKER = "ADB_SETTINGS_EXIT"
        private const val ADB_DAEMON_EXIT_MARKER = "ADB_DAEMON_EXIT"
        private const val MODULE_LS_EXIT_MARKER = "MODULE_LS_EXIT"
        private const val MODULE_LS_OUTPUT_MARKER = "MODULE_LS_OUTPUT"
        private const val ROOT_ID_OUTPUT = "uid=0(root)"

        private val DELAYED_BOOT_COMMAND = """
            root_uid="${'$'}(id -u 2>/dev/null)"
            echo "ROOT_UID=${'$'}root_uid"
            if [ "${'$'}root_uid" != "0" ]; then
                echo "MODULE_LS_EXIT=UNKNOWN"
                exit 20
            fi
            settings put global adb_enabled 1
            echo "ADB_SETTINGS_EXIT=${'$'}?"
            setprop ctl.start adbd
            echo "ADB_DAEMON_EXIT=${'$'}?"
            module_output="${'$'}(/system/bin/ls -d $LSPOSED_DISABLE_PATH 2>&1)"
            module_ls_exit=${'$'}?
            echo "MODULE_LS_EXIT=${'$'}module_ls_exit"
            echo "MODULE_LS_OUTPUT=${'$'}module_output"
            exit 0
        """.trimIndent()
    }
}
