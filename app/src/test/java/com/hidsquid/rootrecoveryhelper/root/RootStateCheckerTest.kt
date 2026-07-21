package com.hidsquid.rootrecoveryhelper.root

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootStateCheckerTest {
    @Test
    fun `root access requires a successful root id result`() = runBlocking {
        val runner = FakeRootCommandRunner(
            CommandResult(0, "uid=0(root) gid=0(root)", ""),
        )

        assertTrue(RootStateChecker(runner).hasRootAccess())
        assertEquals(listOf(RootStateChecker.ROOT_ID_COMMAND), runner.commands)
    }

    @Test
    fun `non-root id output is rejected`() = runBlocking {
        val runner = FakeRootCommandRunner(
            CommandResult(0, "uid=2000(shell) gid=2000(shell)", ""),
        )

        assertFalse(RootStateChecker(runner).hasRootAccess())
    }

    @Test
    fun `disable file exit zero means disabled`() = runBlocking {
        val checker = RootStateChecker(FakeRootCommandRunner(CommandResult(0, "", "")))

        assertEquals(LsposedModuleState.DISABLED, checker.getLsposedModuleState())
    }

    @Test
    fun `disable file exit one means enabled`() = runBlocking {
        val checker = RootStateChecker(FakeRootCommandRunner(CommandResult(1, "", "")))

        assertEquals(LsposedModuleState.ENABLED, checker.getLsposedModuleState())
    }

    @Test
    fun `unexpected disable check failure stays unknown`() = runBlocking {
        val checker = RootStateChecker(FakeRootCommandRunner(CommandResult(127, "", "failed")))

        assertEquals(LsposedModuleState.UNKNOWN, checker.getLsposedModuleState())
    }

    @Test
    fun `timed out disable check stays unknown even with exit zero`() = runBlocking {
        val checker = RootStateChecker(
            FakeRootCommandRunner(CommandResult(0, "", "", timedOut = true)),
        )

        assertEquals(LsposedModuleState.UNKNOWN, checker.getLsposedModuleState())
    }

    @Test
    fun `ADB enable always attempts security setting and daemon commands`() = runBlocking {
        val runner = FakeRootCommandRunner(
            CommandResult(0, "", ""),
            CommandResult(1, "", "settings failed"),
            CommandResult(0, "", ""),
        )

        assertFalse(RootStateChecker(runner).enableAdb())
        assertEquals(
            listOf(
                RootStateChecker.DISABLE_ADB_SECURITY_COMMAND,
                RootStateChecker.ENABLE_ADB_SETTING_COMMAND,
                RootStateChecker.START_ADB_DAEMON_COMMAND,
            ),
            runner.commands,
        )
    }

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
        assertFalse(result.adbActivationAttempted)
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
        assertTrue(command.contains(RootStateChecker.DISABLE_ADB_SECURITY_COMMAND))
        assertTrue(command.contains(RootStateChecker.ENABLE_ADB_SETTING_COMMAND))
        assertTrue(command.contains("key='zygisk'"))
        assertTrue(command.contains("adb_activation_reason\" = \"NORMAL"))
        assertTrue(runner.commands.single().contains("sleep 1"))
        assertTrue(
            command.indexOf("/system/bin/ls -d ${RootStateChecker.LSPOSED_DISABLE_PATH}") <
                command.indexOf(RootStateChecker.DISABLE_ADB_SECURITY_COMMAND),
        )
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
        assertTrue(result.adbActivationAttempted)
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
        assertTrue(result.adbActivationAttempted)
        assertEquals(AdbActivationReason.RECOVERY_DETECTED, result.adbActivationReason)
        assertEquals(LsposedModuleState.DISABLED, result.moduleState)
        assertEquals(1, runner.commands.size)
        assertTrue(
            runner.commands.single().contains(
                "/system/bin/ls -d ${RootStateChecker.LSPOSED_DISABLE_PATH}",
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
        assertFalse(result.adbActivationAttempted)
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
        assertTrue(result.adbActivationAttempted)
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
        assertTrue(result.adbActivationAttempted)
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
        assertTrue(result.adbActivationAttempted)
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
