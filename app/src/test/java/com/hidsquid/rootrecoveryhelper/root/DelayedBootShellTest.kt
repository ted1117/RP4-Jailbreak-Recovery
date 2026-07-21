package com.hidsquid.rootrecoveryhelper.root

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DelayedBootShellTest {
    @Test
    fun `normal state skips all forced ADB commands`() {
        val execution = runShell(
            moduleExit = 1,
            moduleOutput = "ls: disable: No such file or directory",
            zygiskExit = 0,
            zygiskOutput = "warning before result\nzygisk_enabled=1",
        )

        val result = parse(execution)

        assertEquals(AdbActivationReason.NORMAL, result.adbActivationReason)
        assertEquals(ZygiskState.ENABLED, result.zygiskState)
        assertForcedAdbCalls(execution.calls, expected = false)
    }

    @Test
    fun `disabled Zygisk enables ADB even when result follows a warning`() {
        val execution = runShell(
            moduleExit = 1,
            moduleOutput = "ls: disable: No such file or directory",
            zygiskExit = 0,
            zygiskOutput = "warning before result\nzygisk_enabled=0",
        )

        val result = parse(execution)

        assertEquals(AdbActivationReason.RECOVERY_DETECTED, result.adbActivationReason)
        assertEquals(ZygiskState.DISABLED, result.zygiskState)
        assertForcedAdbCalls(execution.calls, expected = true)
    }

    @Test
    fun `state check failure enables ADB`() {
        val execution = runShell(
            moduleExit = 1,
            moduleOutput = "ls: disable: Permission denied",
            zygiskExit = 0,
            zygiskOutput = "zygisk_enabled=1",
        )

        val result = parse(execution)

        assertEquals(AdbActivationReason.STATE_CHECK_FAILED, result.adbActivationReason)
        assertEquals(LsposedModuleState.UNKNOWN, result.moduleState)
        assertForcedAdbCalls(execution.calls, expected = true)
    }

    private fun parse(execution: ShellExecution): DelayedBootCheckResult = runBlocking {
        RootStateChecker(
            object : RootCommandRunner {
                override suspend fun execute(command: String): CommandResult = CommandResult(
                    exitCode = execution.exitCode,
                    stdout = execution.output,
                    stderr = "",
                )
            },
        ).runDelayedBootActions()
    }

    private fun assertForcedAdbCalls(calls: List<String>, expected: Boolean) {
        assertEquals(
            expected,
            calls.contains("resetprop ro.adb.secure 0"),
        )
        assertEquals(
            expected,
            calls.contains("settings put global adb_enabled 1"),
        )
        assertEquals(
            expected,
            calls.contains("setprop ctl.start adbd"),
        )
    }

    private fun runShell(
        moduleExit: Int,
        moduleOutput: String,
        zygiskExit: Int,
        zygiskOutput: String,
    ): ShellExecution {
        val stubDirectory = Files.createTempDirectory("root-recovery-shell-test")
        return try {
            val callsFile = Files.createFile(stubDirectory.resolve("calls.log"))
            installStubs(stubDirectory)
            val process = ProcessBuilder(
                "/bin/sh",
                "-c",
                RootStateChecker.buildDelayedBootCommand(moduleLsCommand = "ls"),
            )
                .redirectErrorStream(true)
                .apply {
                    environment()["PATH"] =
                        "${stubDirectory.toAbsolutePath()}:/usr/bin:/bin"
                    environment()["CALLS_FILE"] = callsFile.toAbsolutePath().toString()
                    environment()["MODULE_EXIT"] = moduleExit.toString()
                    environment()["MODULE_OUTPUT"] = moduleOutput
                    environment()["ZYGISK_EXIT"] = zygiskExit.toString()
                    environment()["ZYGISK_OUTPUT"] = zygiskOutput
                }
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            assertEquals("shell output:\n$output", 0, exitCode)
            ShellExecution(
                exitCode = exitCode,
                output = output,
                calls = Files.readAllLines(callsFile),
            )
        } finally {
            stubDirectory.toFile().deleteRecursively()
        }
    }

    private fun installStubs(directory: Path) {
        val script = """
            #!/bin/sh
            command_name="${'$'}{0##*/}"
            printf '%s\n' "${'$'}command_name ${'$'}*" >> "${'$'}CALLS_FILE"
            case "${'$'}command_name" in
                id)
                    printf '0\n'
                    ;;
                date)
                    printf '12345\n'
                    ;;
                settings)
                    if [ "${'$'}1" = "get" ]; then
                        printf '1\n'
                    fi
                    ;;
                getprop)
                    case "${'$'}1" in
                        init.svc.adbd) printf 'running\n' ;;
                        sys.usb.config|sys.usb.state|persist.sys.usb.config)
                            printf 'mtp,adb\n'
                            ;;
                        ro.adb.secure) printf '0\n' ;;
                        ro.debuggable) printf '1\n' ;;
                    esac
                    ;;
                ls)
                    printf '%s\n' "${'$'}MODULE_OUTPUT"
                    exit "${'$'}MODULE_EXIT"
                    ;;
                magisk)
                    printf '%s\n' "${'$'}ZYGISK_OUTPUT"
                    exit "${'$'}ZYGISK_EXIT"
                    ;;
                resetprop|setprop|sleep)
                    ;;
                *)
                    exit 127
                    ;;
            esac
            exit 0
        """.trimIndent()

        listOf(
            "id",
            "date",
            "settings",
            "getprop",
            "ls",
            "magisk",
            "resetprop",
            "setprop",
            "sleep",
        ).forEach { command ->
            val stub = directory.resolve(command)
            Files.write(stub, script.toByteArray(StandardCharsets.UTF_8))
            assertTrue("failed to make $command executable", stub.toFile().setExecutable(true))
        }
    }

    private data class ShellExecution(
        val exitCode: Int,
        val output: String,
        val calls: List<String>,
    )
}
