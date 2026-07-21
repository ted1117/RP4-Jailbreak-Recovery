package com.hidsquid.rootrecoveryhelper.root

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootStateCheckerTest {
    @Test
    fun `normal delayed boot actions skip forced ADB and parse all results`() = runBlocking {
        val runner = FakeRootCommandRunner(
            CommandResult(
                0,
                """
                    ROOT_UID=0
                    ADB_DIAG_TIMESTAMP=12345
                    BEFORE_ADB_ENABLED=1
                    BEFORE_ADBD_STATE=running
                    BEFORE_RO_ADB_SECURE=0
                    ADB_ACTIVATION_REASON=NORMAL
                    ADB_SECURITY_EXIT=SKIPPED
                    ADB_SECURITY_OUTPUT=normal root state
                    ADB_SETTINGS_EXIT=SKIPPED
                    ADB_SETTINGS_OUTPUT=normal root state
                    ADB_DAEMON_EXIT=SKIPPED
                    ADB_DAEMON_OUTPUT=normal root state
                    AFTER_ADB_ENABLED=1
                    AFTER_ADBD_STATE=running
                    AFTER_SYS_USB_CONFIG=mtp,adb
                    AFTER_RO_ADB_SECURE=0
                    MODULE_LS_EXIT=1
                    MODULE_LS_OUTPUT=ls: disable: No such file or directory
                    ZYGISK_QUERY_EXIT=0
                    ZYGISK_QUERY_OUTPUT=zygisk_enabled=1
                    ZYGISK_STATE=ENABLED
                """.trimIndent(),
                "",
            ),
        )

        val result = RootStateChecker(runner).runDelayedBootActions()

        assertTrue(result.hasRootAccess)
        assertTrue(result.adbEnabled)
        assertEquals(AdbActivationReason.NORMAL, result.adbActivationReason)
        assertEquals(LsposedModuleState.ENABLED, result.moduleState)
        assertEquals(ZygiskState.ENABLED, result.zygiskState)
        assertTrue(result.adbDiagnostics.contains("BEFORE_ADBD_STATE=running"))
        assertTrue(result.adbDiagnostics.contains("AFTER_ADBD_STATE=running"))
        assertTrue(result.adbDiagnostics.contains("BEFORE_RO_ADB_SECURE=0"))
        assertTrue(result.adbDiagnostics.contains("AFTER_RO_ADB_SECURE=0"))
        assertTrue(result.adbDiagnostics.contains("ACTIVATION_REASON=NORMAL"))
        assertTrue(result.adbDiagnostics.contains("ROOT_COMMAND_EXIT=0"))
        assertEquals(1, runner.commands.size)
        val command = runner.commands.single()
        assertTrue(command.contains("resetprop ro.adb.secure 0"))
        assertTrue(command.contains("settings put global adb_enabled 1"))
        assertTrue(command.contains("key='zygisk'"))
        assertTrue(command.contains("adb_activation_reason\" = \"NORMAL"))
        assertTrue(runner.commands.single().contains("sleep 1"))
        assertTrue(
            command.indexOf(
                "/system/bin/ls -d /data/adb/modules/zygisk_lsposed/disable",
            ) < command.indexOf("resetprop ro.adb.secure 0"),
        )
    }

    @Test
    fun `dialog-required ADB reason is parsed from the integrated command`() = runBlocking {
        val runner = FakeRootCommandRunner(
            CommandResult(
                0,
                """
                    ROOT_UID=0
                    ADB_ACTIVATION_REASON=DIALOG_REQUIRED
                    ADB_SECURITY_EXIT=0
                    ADB_SETTINGS_EXIT=0
                    ADB_DAEMON_EXIT=0
                    AFTER_ADB_ENABLED=1
                    AFTER_ADBD_STATE=running
                    AFTER_RO_ADB_SECURE=0
                    MODULE_LS_EXIT=1
                    MODULE_LS_OUTPUT=ls: disable: No such file or directory
                    ZYGISK_QUERY_EXIT=0
                    ZYGISK_QUERY_OUTPUT=zygisk_enabled=1
                    ZYGISK_STATE=ENABLED
                """.trimIndent(),
                "",
            ),
        )

        val result = RootStateChecker(runner).runDelayedBootActions(forceAdbForDialog = true)

        assertEquals(AdbActivationReason.DIALOG_REQUIRED, result.adbActivationReason)
        assertTrue(result.adbEnabled)
        assertTrue(runner.commands.single().contains("force_adb_for_dialog=1"))
    }

    @Test
    fun `successful ADB command exits do not count without verified final state`() = runBlocking {
        val runner = FakeRootCommandRunner(
            CommandResult(
                0,
                """
                    ROOT_UID=0
                    ADB_ACTIVATION_REASON=RECOVERY_DETECTED
                    ADB_SECURITY_EXIT=0
                    ADB_SETTINGS_EXIT=0
                    ADB_DAEMON_EXIT=0
                    AFTER_ADB_ENABLED=1
                    AFTER_ADBD_STATE=stopped
                    AFTER_RO_ADB_SECURE=0
                    MODULE_LS_EXIT=0
                    MODULE_LS_OUTPUT=/data/adb/modules/zygisk_lsposed/disable
                    ZYGISK_QUERY_EXIT=0
                    ZYGISK_QUERY_OUTPUT=zygisk_enabled=1
                    ZYGISK_STATE=ENABLED
                """.trimIndent(),
                "",
            ),
        )

        val result = RootStateChecker(runner).runDelayedBootActions()

        assertFalse(result.adbEnabled)
        assertEquals(AdbActivationReason.RECOVERY_DETECTED, result.adbActivationReason)
        assertTrue(
            result.detail.contains(
                "검증 adb_enabled=1, adbd=stopped, ro.adb.secure=0",
            ),
        )
    }

    @Test
    fun `boot actions report disabled when zygisk LSPosed disable exists`() = runBlocking {
        val runner = FakeRootCommandRunner(
            CommandResult(
                0,
                """
                    ROOT_UID=0
                    ADB_ACTIVATION_REASON=RECOVERY_DETECTED
                    ADB_SETTINGS_EXIT=0
                    ADB_DAEMON_EXIT=0
                    MODULE_LS_EXIT=0
                    MODULE_LS_OUTPUT=/data/adb/modules/zygisk_lsposed/disable
                    ZYGISK_QUERY_EXIT=0
                    ZYGISK_QUERY_OUTPUT=zygisk_enabled=1
                    ZYGISK_STATE=ENABLED
                """.trimIndent(),
                "",
            ),
        )

        val result = RootStateChecker(runner).runDelayedBootActions()

        assertTrue(result.hasRootAccess)
        assertEquals(AdbActivationReason.RECOVERY_DETECTED, result.adbActivationReason)
        assertEquals(LsposedModuleState.DISABLED, result.moduleState)
        assertEquals(1, runner.commands.size)
        assertTrue(
            runner.commands.single().contains(
                "/system/bin/ls -d /data/adb/modules/zygisk_lsposed/disable",
            ),
        )
    }

    @Test
    fun `boot actions report enabled when zygisk LSPosed disable is absent`() = runBlocking {
        val runner = FakeRootCommandRunner(
            CommandResult(
                0,
                """
                    ROOT_UID=0
                    ADB_ACTIVATION_REASON=NORMAL
                    ADB_SETTINGS_EXIT=0
                    ADB_DAEMON_EXIT=0
                    MODULE_LS_EXIT=1
                    MODULE_LS_OUTPUT=ls: /data/adb/modules/zygisk_lsposed/disable: No such file or directory
                    ZYGISK_QUERY_EXIT=0
                    ZYGISK_QUERY_OUTPUT=zygisk_enabled=1
                    ZYGISK_STATE=ENABLED
                """.trimIndent(),
                "",
            ),
        )

        val result = RootStateChecker(runner).runDelayedBootActions()

        assertTrue(result.hasRootAccess)
        assertEquals(LsposedModuleState.ENABLED, result.moduleState)
        assertEquals(ZygiskState.ENABLED, result.zygiskState)
        assertEquals(AdbActivationReason.NORMAL, result.adbActivationReason)
    }

    @Test
    fun `boot actions do not treat permission denial as an absent disable file`() = runBlocking {
        val runner = FakeRootCommandRunner(
            CommandResult(
                0,
                """
                    ROOT_UID=0
                    ADB_ACTIVATION_REASON=STATE_CHECK_FAILED
                    ADB_SETTINGS_EXIT=0
                    ADB_DAEMON_EXIT=0
                    MODULE_LS_EXIT=1
                    MODULE_LS_OUTPUT=ls: /data/adb/modules/zygisk_lsposed/disable: Permission denied
                    ZYGISK_QUERY_EXIT=0
                    ZYGISK_QUERY_OUTPUT=zygisk_enabled=1
                    ZYGISK_STATE=ENABLED
                """.trimIndent(),
                "",
            ),
        )

        val result = RootStateChecker(runner).runDelayedBootActions()

        assertTrue(result.hasRootAccess)
        assertEquals(LsposedModuleState.UNKNOWN, result.moduleState)
        assertEquals(AdbActivationReason.STATE_CHECK_FAILED, result.adbActivationReason)
    }

    @Test
    fun `boot actions detect disabled Zygisk independently of module marker`() = runBlocking {
        val runner = FakeRootCommandRunner(
            CommandResult(
                0,
                """
                    ROOT_UID=0
                    ADB_ACTIVATION_REASON=RECOVERY_DETECTED
                    ADB_SETTINGS_EXIT=0
                    ADB_DAEMON_EXIT=0
                    MODULE_LS_EXIT=1
                    MODULE_LS_OUTPUT=ls: disable: No such file or directory
                    ZYGISK_QUERY_EXIT=0
                    ZYGISK_QUERY_OUTPUT=zygisk_enabled=0
                    ZYGISK_STATE=DISABLED
                """.trimIndent(),
                "",
            ),
        )

        val result = RootStateChecker(runner).runDelayedBootActions()

        assertEquals(LsposedModuleState.ENABLED, result.moduleState)
        assertEquals(ZygiskState.DISABLED, result.zygiskState)
        assertEquals(AdbActivationReason.RECOVERY_DETECTED, result.adbActivationReason)
    }

    @Test
    fun `failed Zygisk query remains unknown`() = runBlocking {
        val runner = FakeRootCommandRunner(
            CommandResult(
                0,
                """
                    ROOT_UID=0
                    ADB_ACTIVATION_REASON=STATE_CHECK_FAILED
                    ADB_SETTINGS_EXIT=0
                    ADB_DAEMON_EXIT=0
                    MODULE_LS_EXIT=1
                    MODULE_LS_OUTPUT=ls: disable: No such file or directory
                    ZYGISK_QUERY_EXIT=127
                    ZYGISK_QUERY_OUTPUT=magisk: not found
                    ZYGISK_STATE=UNKNOWN
                """.trimIndent(),
                "",
            ),
        )

        val result = RootStateChecker(runner).runDelayedBootActions()

        assertEquals(ZygiskState.UNKNOWN, result.zygiskState)
        assertEquals(AdbActivationReason.STATE_CHECK_FAILED, result.adbActivationReason)
    }

    private class FakeRootCommandRunner(
        vararg results: CommandResult,
    ) : RootCommandRunner {
        private val results = ArrayDeque(results.toList())
        val commands = mutableListOf<String>()

        override suspend fun execute(command: String): CommandResult {
            commands += command
            return results.removeFirst()
        }
    }
}
