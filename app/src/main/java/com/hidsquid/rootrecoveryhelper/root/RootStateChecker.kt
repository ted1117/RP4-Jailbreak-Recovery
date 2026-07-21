package com.hidsquid.rootrecoveryhelper.root

enum class LsposedModuleState {
    DISABLED,
    ENABLED,
    UNKNOWN,
}

enum class ZygiskState {
    DISABLED,
    ENABLED,
    UNKNOWN,
}

enum class AdbActivationReason {
    RECOVERY_DETECTED,
    STATE_CHECK_FAILED,
    DIALOG_REQUIRED,
    NORMAL,
    NOT_RUN,
    UNKNOWN,
}

data class DelayedBootCheckResult(
    val hasRootAccess: Boolean,
    val adbEnabled: Boolean,
    val adbActivationReason: AdbActivationReason,
    val moduleState: LsposedModuleState,
    val zygiskState: ZygiskState,
    val adbDiagnostics: String,
    val detail: String,
)

class RootStateChecker(
    private val commandRunner: RootCommandRunner,
) {
    suspend fun runDelayedBootActions(
        forceAdbForDialog: Boolean = false,
    ): DelayedBootCheckResult {
        val result = commandRunner.execute(
            buildDelayedBootCommand(forceAdbForDialog = forceAdbForDialog),
        )
        if (result.timedOut) {
            return DelayedBootCheckResult(
                hasRootAccess = false,
                adbEnabled = false,
                adbActivationReason = AdbActivationReason.NOT_RUN,
                moduleState = LsposedModuleState.UNKNOWN,
                zygiskState = ZygiskState.UNKNOWN,
                adbDiagnostics = buildAdbDiagnostics(result),
                detail = "통합 root 명령 timeout",
            )
        }

        val rootUid = result.stdout.markerValue(ROOT_UID_MARKER)
        val hasRoot = rootUid == "0"
        if (!hasRoot) {
            return DelayedBootCheckResult(
                hasRootAccess = false,
                adbEnabled = false,
                adbActivationReason = AdbActivationReason.NOT_RUN,
                moduleState = LsposedModuleState.UNKNOWN,
                zygiskState = ZygiskState.UNKNOWN,
                adbDiagnostics = buildAdbDiagnostics(result),
                detail = "uid=${rootUid ?: "확인 실패"}",
            )
        }

        val adbSettingsExit = result.stdout.markerValue(ADB_SETTINGS_EXIT_MARKER)
        val adbSettingsOutput = result.stdout.markerValue(ADB_SETTINGS_OUTPUT_MARKER).orEmpty()
        val adbSecurityExit = result.stdout.markerValue(ADB_SECURITY_EXIT_MARKER)
        val adbSecurityOutput = result.stdout.markerValue(ADB_SECURITY_OUTPUT_MARKER).orEmpty()
        val adbDaemonExit = result.stdout.markerValue(ADB_DAEMON_EXIT_MARKER)
        val adbDaemonOutput = result.stdout.markerValue(ADB_DAEMON_OUTPUT_MARKER).orEmpty()
        val adbActivationReason = result.stdout.markerValue(ADB_ACTIVATION_REASON_MARKER)
            .toAdbActivationReason()
        val afterAdbEnabled = result.stdout.markerValue(AFTER_ADB_ENABLED_MARKER)
        val afterAdbdState = result.stdout.markerValue(AFTER_ADBD_STATE_MARKER)
        val afterRoAdbSecure = result.stdout.markerValue(AFTER_RO_ADB_SECURE_MARKER)
        val moduleLsExit = result.stdout.markerValue(MODULE_LS_EXIT_MARKER)
        val moduleLsOutput = result.stdout.markerValue(MODULE_LS_OUTPUT_MARKER).orEmpty()
        val zygiskQueryExit = result.stdout.markerValue(ZYGISK_QUERY_EXIT_MARKER)
        val zygiskQueryOutput = result.stdout.markerValue(ZYGISK_QUERY_OUTPUT_MARKER).orEmpty()
        val normalizedZygiskState = result.stdout.markerValue(ZYGISK_STATE_MARKER)
        val moduleState = when {
            moduleLsExit == "0" -> LsposedModuleState.DISABLED
            moduleLsExit == "1" && moduleLsOutput.contains("No such file") ->
                LsposedModuleState.ENABLED
            else -> LsposedModuleState.UNKNOWN
        }
        val zygiskState = normalizedZygiskState.toZygiskState()

        return DelayedBootCheckResult(
            hasRootAccess = true,
            adbEnabled = afterAdbEnabled == "1" &&
                afterAdbdState == "running" &&
                afterRoAdbSecure == "0",
            adbActivationReason = adbActivationReason,
            moduleState = moduleState,
            zygiskState = zygiskState,
            adbDiagnostics = buildAdbDiagnostics(result),
            detail = buildString {
                append("ADB 조건=")
                append(adbActivationReason.name)
                if (adbActivationReason == AdbActivationReason.NORMAL) {
                    append(", 강제 활성화 건너뜀")
                } else {
                    append(", security exit=$adbSecurityExit")
                    adbSecurityOutput.takeIf { it.isNotBlank() }
                        ?.let { append(", 출력=$it") }
                    append(", settings exit=$adbSettingsExit")
                    adbSettingsOutput.takeIf { it.isNotBlank() }
                        ?.let { append(", 출력=$it") }
                    append(", adbd exit=$adbDaemonExit")
                    adbDaemonOutput.takeIf { it.isNotBlank() }
                        ?.let { append(", 출력=$it") }
                }
                append(", 검증 adb_enabled=$afterAdbEnabled, adbd=$afterAdbdState, ")
                append("ro.adb.secure=$afterRoAdbSecure, ")
                append(
                    "disable 검사 exit=$moduleLsExit" +
                        moduleLsOutput.takeIf { it.isNotBlank() }
                            ?.let { ", 출력=$it" }
                            .orEmpty() +
                        ", Zygisk 검사 exit=$zygiskQueryExit" +
                        ", 판정=${zygiskState.name}" +
                        zygiskQueryOutput.takeIf { it.isNotBlank() }
                            ?.let { ", 출력=$it" }
                            .orEmpty(),
                )
            },
        )
    }

    private fun buildAdbDiagnostics(result: CommandResult): String = buildString {
        ADB_DIAGNOSTIC_MARKERS.forEach { (label, marker) ->
            append(label)
            append('=')
            append(result.stdout.markerValue(marker) ?: "기록 없음")
            append('\n')
        }
        append("ROOT_COMMAND_EXIT=")
        append(result.exitCode)
        append('\n')
        append("ROOT_COMMAND_TIMEOUT=")
        append(result.timedOut)
        append('\n')
        append("ROOT_COMMAND_STDERR=")
        append(result.stderr.ifBlank { "없음" }.replace('\n', ' '))
    }

    private fun String.markerValue(marker: String): String? =
        lineSequence()
            .firstOrNull { it.startsWith("$marker=") }
            ?.substringAfter('=')

    private fun String?.toZygiskState(): ZygiskState = when (this) {
        ZygiskState.ENABLED.name -> ZygiskState.ENABLED
        ZygiskState.DISABLED.name -> ZygiskState.DISABLED
        else -> ZygiskState.UNKNOWN
    }

    private fun String?.toAdbActivationReason(): AdbActivationReason = when (this) {
        AdbActivationReason.RECOVERY_DETECTED.name -> AdbActivationReason.RECOVERY_DETECTED
        AdbActivationReason.STATE_CHECK_FAILED.name -> AdbActivationReason.STATE_CHECK_FAILED
        AdbActivationReason.DIALOG_REQUIRED.name -> AdbActivationReason.DIALOG_REQUIRED
        AdbActivationReason.NORMAL.name -> AdbActivationReason.NORMAL
        else -> AdbActivationReason.UNKNOWN
    }

    companion object {
        private const val LSPOSED_DISABLE_PATH =
            "/data/adb/modules/zygisk_lsposed/disable"

        private const val ROOT_UID_MARKER = "ROOT_UID"
        private const val ADB_SETTINGS_EXIT_MARKER = "ADB_SETTINGS_EXIT"
        private const val ADB_SETTINGS_OUTPUT_MARKER = "ADB_SETTINGS_OUTPUT"
        private const val ADB_SECURITY_EXIT_MARKER = "ADB_SECURITY_EXIT"
        private const val ADB_SECURITY_OUTPUT_MARKER = "ADB_SECURITY_OUTPUT"
        private const val ADB_DAEMON_EXIT_MARKER = "ADB_DAEMON_EXIT"
        private const val ADB_DAEMON_OUTPUT_MARKER = "ADB_DAEMON_OUTPUT"
        private const val ADB_ACTIVATION_REASON_MARKER = "ADB_ACTIVATION_REASON"
        private const val ADB_DIAG_TIMESTAMP_MARKER = "ADB_DIAG_TIMESTAMP"
        private const val BEFORE_ADB_ENABLED_MARKER = "BEFORE_ADB_ENABLED"
        private const val BEFORE_DEVELOPMENT_SETTINGS_MARKER =
            "BEFORE_DEVELOPMENT_SETTINGS"
        private const val BEFORE_ADBD_STATE_MARKER = "BEFORE_ADBD_STATE"
        private const val BEFORE_SYS_USB_CONFIG_MARKER = "BEFORE_SYS_USB_CONFIG"
        private const val BEFORE_SYS_USB_STATE_MARKER = "BEFORE_SYS_USB_STATE"
        private const val BEFORE_PERSIST_USB_CONFIG_MARKER = "BEFORE_PERSIST_USB_CONFIG"
        private const val BEFORE_RO_ADB_SECURE_MARKER = "BEFORE_RO_ADB_SECURE"
        private const val BEFORE_RO_DEBUGGABLE_MARKER = "BEFORE_RO_DEBUGGABLE"
        private const val AFTER_ADB_ENABLED_MARKER = "AFTER_ADB_ENABLED"
        private const val AFTER_DEVELOPMENT_SETTINGS_MARKER = "AFTER_DEVELOPMENT_SETTINGS"
        private const val AFTER_ADBD_STATE_MARKER = "AFTER_ADBD_STATE"
        private const val AFTER_SYS_USB_CONFIG_MARKER = "AFTER_SYS_USB_CONFIG"
        private const val AFTER_SYS_USB_STATE_MARKER = "AFTER_SYS_USB_STATE"
        private const val AFTER_PERSIST_USB_CONFIG_MARKER = "AFTER_PERSIST_USB_CONFIG"
        private const val AFTER_RO_ADB_SECURE_MARKER = "AFTER_RO_ADB_SECURE"
        private const val AFTER_RO_DEBUGGABLE_MARKER = "AFTER_RO_DEBUGGABLE"
        private const val MODULE_LS_EXIT_MARKER = "MODULE_LS_EXIT"
        private const val MODULE_LS_OUTPUT_MARKER = "MODULE_LS_OUTPUT"
        private const val ZYGISK_QUERY_EXIT_MARKER = "ZYGISK_QUERY_EXIT"
        private const val ZYGISK_QUERY_OUTPUT_MARKER = "ZYGISK_QUERY_OUTPUT"
        private const val ZYGISK_STATE_MARKER = "ZYGISK_STATE"
        private val ADB_DIAGNOSTIC_MARKERS = listOf(
            "TIMESTAMP" to ADB_DIAG_TIMESTAMP_MARKER,
            "BEFORE_ADB_ENABLED" to BEFORE_ADB_ENABLED_MARKER,
            "BEFORE_DEVELOPMENT_SETTINGS" to BEFORE_DEVELOPMENT_SETTINGS_MARKER,
            "BEFORE_ADBD_STATE" to BEFORE_ADBD_STATE_MARKER,
            "BEFORE_SYS_USB_CONFIG" to BEFORE_SYS_USB_CONFIG_MARKER,
            "BEFORE_SYS_USB_STATE" to BEFORE_SYS_USB_STATE_MARKER,
            "BEFORE_PERSIST_USB_CONFIG" to BEFORE_PERSIST_USB_CONFIG_MARKER,
            "BEFORE_RO_ADB_SECURE" to BEFORE_RO_ADB_SECURE_MARKER,
            "BEFORE_RO_DEBUGGABLE" to BEFORE_RO_DEBUGGABLE_MARKER,
            "ACTIVATION_REASON" to ADB_ACTIVATION_REASON_MARKER,
            "SECURITY_RESET_EXIT" to ADB_SECURITY_EXIT_MARKER,
            "SECURITY_RESET_OUTPUT" to ADB_SECURITY_OUTPUT_MARKER,
            "SETTINGS_EXIT" to ADB_SETTINGS_EXIT_MARKER,
            "SETTINGS_OUTPUT" to ADB_SETTINGS_OUTPUT_MARKER,
            "ADBD_START_EXIT" to ADB_DAEMON_EXIT_MARKER,
            "ADBD_START_OUTPUT" to ADB_DAEMON_OUTPUT_MARKER,
            "ZYGISK_STATE" to ZYGISK_STATE_MARKER,
            "AFTER_ADB_ENABLED" to AFTER_ADB_ENABLED_MARKER,
            "AFTER_DEVELOPMENT_SETTINGS" to AFTER_DEVELOPMENT_SETTINGS_MARKER,
            "AFTER_ADBD_STATE" to AFTER_ADBD_STATE_MARKER,
            "AFTER_SYS_USB_CONFIG" to AFTER_SYS_USB_CONFIG_MARKER,
            "AFTER_SYS_USB_STATE" to AFTER_SYS_USB_STATE_MARKER,
            "AFTER_PERSIST_USB_CONFIG" to AFTER_PERSIST_USB_CONFIG_MARKER,
            "AFTER_RO_ADB_SECURE" to AFTER_RO_ADB_SECURE_MARKER,
            "AFTER_RO_DEBUGGABLE" to AFTER_RO_DEBUGGABLE_MARKER,
        )

        internal fun buildDelayedBootCommand(
            moduleLsCommand: String = "/system/bin/ls",
            forceAdbForDialog: Boolean = false,
        ): String = """
            root_uid="${'$'}(id -u 2>/dev/null)"
            echo "ROOT_UID=${'$'}root_uid"
            if [ "${'$'}root_uid" != "0" ]; then
                echo "MODULE_LS_EXIT=UNKNOWN"
                exit 20
            fi
            echo "ADB_DIAG_TIMESTAMP=${'$'}(date +%s 2>/dev/null)"
            echo "BEFORE_ADB_ENABLED=${'$'}(settings get global adb_enabled 2>&1)"
            echo "BEFORE_DEVELOPMENT_SETTINGS=${'$'}(settings get global development_settings_enabled 2>&1)"
            echo "BEFORE_ADBD_STATE=${'$'}(getprop init.svc.adbd 2>&1)"
            echo "BEFORE_SYS_USB_CONFIG=${'$'}(getprop sys.usb.config 2>&1)"
            echo "BEFORE_SYS_USB_STATE=${'$'}(getprop sys.usb.state 2>&1)"
            echo "BEFORE_PERSIST_USB_CONFIG=${'$'}(getprop persist.sys.usb.config 2>&1)"
            echo "BEFORE_RO_ADB_SECURE=${'$'}(getprop ro.adb.secure 2>&1)"
            echo "BEFORE_RO_DEBUGGABLE=${'$'}(getprop ro.debuggable 2>&1)"

            module_output="${'$'}($moduleLsCommand -d $LSPOSED_DISABLE_PATH 2>&1)"
            module_ls_exit=${'$'}?
            echo "MODULE_LS_EXIT=${'$'}module_ls_exit"
            echo "MODULE_LS_OUTPUT=${'$'}module_output"
            zygisk_output="${'$'}(magisk --sqlite "SELECT CASE WHEN EXISTS(SELECT 1 FROM settings WHERE key='zygisk' AND value=1) THEN 1 ELSE 0 END AS zygisk_enabled;" 2>&1)"
            zygisk_query_exit=${'$'}?
            echo "ZYGISK_QUERY_EXIT=${'$'}zygisk_query_exit"

            recovery_detected=0
            state_check_failed=0
            force_adb_for_dialog=${if (forceAdbForDialog) 1 else 0}
            zygisk_state="UNKNOWN"
            if [ "${'$'}module_ls_exit" -eq 0 ]; then
                recovery_detected=1
            elif [ "${'$'}module_ls_exit" -eq 1 ]; then
                case "${'$'}module_output" in
                    *"No such file"*) ;;
                    *) state_check_failed=1 ;;
                esac
            else
                state_check_failed=1
            fi

            if [ "${'$'}zygisk_query_exit" -eq 0 ]; then
                case "${'$'}zygisk_output" in
                    "1"|*"zygisk_enabled=1"*) zygisk_state="ENABLED" ;;
                    "0"|*"zygisk_enabled=0"*)
                        zygisk_state="DISABLED"
                        recovery_detected=1
                        ;;
                    *) state_check_failed=1 ;;
                esac
            else
                state_check_failed=1
            fi
            echo "ZYGISK_STATE=${'$'}zygisk_state"

            if [ "${'$'}recovery_detected" -eq 1 ]; then
                adb_activation_reason="RECOVERY_DETECTED"
            elif [ "${'$'}state_check_failed" -eq 1 ]; then
                adb_activation_reason="STATE_CHECK_FAILED"
            elif [ "${'$'}force_adb_for_dialog" -eq 1 ]; then
                adb_activation_reason="DIALOG_REQUIRED"
            else
                adb_activation_reason="NORMAL"
            fi
            echo "ADB_ACTIVATION_REASON=${'$'}adb_activation_reason"
            echo "ZYGISK_QUERY_OUTPUT=${'$'}zygisk_output"

            if [ "${'$'}adb_activation_reason" = "NORMAL" ]; then
                echo "ADB_SECURITY_EXIT=SKIPPED"
                echo "ADB_SECURITY_OUTPUT=normal root state"
                echo "ADB_SETTINGS_EXIT=SKIPPED"
                echo "ADB_SETTINGS_OUTPUT=normal root state"
                echo "ADB_DAEMON_EXIT=SKIPPED"
                echo "ADB_DAEMON_OUTPUT=normal root state"
            else
                adb_security_output="${'$'}(resetprop ro.adb.secure 0 2>&1)"
                adb_security_exit=${'$'}?
                echo "ADB_SECURITY_EXIT=${'$'}adb_security_exit"
                echo "ADB_SECURITY_OUTPUT=${'$'}adb_security_output"

                adb_settings_output="${'$'}(settings put global adb_enabled 1 2>&1)"
                adb_settings_exit=${'$'}?
                echo "ADB_SETTINGS_EXIT=${'$'}adb_settings_exit"
                echo "ADB_SETTINGS_OUTPUT=${'$'}adb_settings_output"

                adb_daemon_output="${'$'}(setprop ctl.start adbd 2>&1)"
                adb_daemon_exit=${'$'}?
                echo "ADB_DAEMON_EXIT=${'$'}adb_daemon_exit"
                echo "ADB_DAEMON_OUTPUT=${'$'}adb_daemon_output"
                sleep 1
            fi

            echo "AFTER_ADB_ENABLED=${'$'}(settings get global adb_enabled 2>&1)"
            echo "AFTER_DEVELOPMENT_SETTINGS=${'$'}(settings get global development_settings_enabled 2>&1)"
            echo "AFTER_ADBD_STATE=${'$'}(getprop init.svc.adbd 2>&1)"
            echo "AFTER_SYS_USB_CONFIG=${'$'}(getprop sys.usb.config 2>&1)"
            echo "AFTER_SYS_USB_STATE=${'$'}(getprop sys.usb.state 2>&1)"
            echo "AFTER_PERSIST_USB_CONFIG=${'$'}(getprop persist.sys.usb.config 2>&1)"
            echo "AFTER_RO_ADB_SECURE=${'$'}(getprop ro.adb.secure 2>&1)"
            echo "AFTER_RO_DEBUGGABLE=${'$'}(getprop ro.debuggable 2>&1)"
            exit 0
        """.trimIndent()
    }
}
