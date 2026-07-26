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
    fun `ADB enable always attempts both commands`() = runBlocking {
        val runner = FakeRootCommandRunner(
            CommandResult(1, "", "settings failed"),
            CommandResult(0, "", ""),
        )

        assertFalse(RootStateChecker(runner).enableAdb())
        assertEquals(
            listOf(
                RootStateChecker.ENABLE_ADB_SETTING_COMMAND,
                RootStateChecker.START_ADB_DAEMON_COMMAND,
            ),
            runner.commands,
        )
    }

    @Test
    fun `delayed boot actions use one root command and parse all results`() = runBlocking {
        val runner = FakeRootCommandRunner(
            CommandResult(
                0,
                """
                    ROOT_UID=0
                    ADB_SETTINGS_EXIT=0
                    ADB_DAEMON_EXIT=0
                    MODULE_LS_EXIT=1
                    MODULE_LS_OUTPUT=ls: disable: No such file or directory
                """.trimIndent(),
                "",
            ),
        )

        val result = RootStateChecker(runner).runDelayedBootActions()

        assertTrue(result.hasRootAccess)
        assertTrue(result.adbEnabled)
        assertEquals(LsposedModuleState.ENABLED, result.moduleState)
        assertEquals(1, runner.commands.size)
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
