package com.hidsquid.rootrecoveryhelper.root

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootCommandExecutorTest {
    @Test
    fun `successful command captures both output streams and exit code`() = runBlocking {
        var receivedCommand = ""
        val process = FakeProcess.completed(
            exitCode = 7,
            stdout = "standard output",
            stderr = "standard error",
        )
        val executor = RootCommandExecutor(timeoutMillis = 1_000L) { command ->
            receivedCommand = command
            process
        }

        val result = executor.execute("test command")

        assertEquals("test command", receivedCommand)
        assertEquals(7, result.exitCode)
        assertEquals("standard output", result.stdout)
        assertEquals("standard error", result.stderr)
        assertFalse(result.timedOut)
    }

    @Test
    fun `process start failure is returned instead of thrown`() = runBlocking {
        val executor = RootCommandExecutor(timeoutMillis = 1_000L) {
            throw IOException("su is unavailable")
        }

        val result = executor.execute("id")

        assertEquals(-1, result.exitCode)
        assertEquals("", result.stdout)
        assertTrue(result.stderr.contains("su is unavailable"))
        assertFalse(result.timedOut)
    }

    @Test(timeout = 2_000L)
    fun `timeout destroys process and closes blocking streams`() = runBlocking {
        val stdout = CloseAwareBlockingInputStream()
        val stderr = CloseAwareBlockingInputStream()
        val process = FakeProcess.running(stdout, stderr)
        val executor = RootCommandExecutor(timeoutMillis = 25L) { process }

        val result = executor.execute("long running command")

        assertEquals(-2, result.exitCode)
        assertTrue(result.timedOut)
        assertTrue(process.destroyed)
        assertTrue(stdout.isClosed)
        assertTrue(stderr.isClosed)
    }

    private class FakeProcess(
        private val completesNormally: Boolean,
        private val configuredExitCode: Int,
        private val stdoutStream: InputStream,
        private val stderrStream: InputStream,
    ) : Process() {
        private val stdinStream = ByteArrayOutputStream()

        @Volatile
        var destroyed = false
            private set

        override fun getOutputStream(): OutputStream = stdinStream

        override fun getInputStream(): InputStream = stdoutStream

        override fun getErrorStream(): InputStream = stderrStream

        override fun waitFor(): Int {
            while (isAlive) {
                Thread.sleep(1L)
            }
            return exitValue()
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (completesNormally || destroyed) {
                return true
            }
            Thread.sleep(unit.toMillis(timeout))
            return destroyed
        }

        override fun exitValue(): Int {
            if (isAlive) {
                throw IllegalThreadStateException("process is still running")
            }
            return configuredExitCode
        }

        override fun destroy() {
            destroyed = true
            stdoutStream.close()
            stderrStream.close()
            stdinStream.close()
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }

        override fun isAlive(): Boolean = !completesNormally && !destroyed

        companion object {
            fun completed(exitCode: Int, stdout: String, stderr: String): FakeProcess =
                FakeProcess(
                    completesNormally = true,
                    configuredExitCode = exitCode,
                    stdoutStream = ByteArrayInputStream(stdout.toByteArray()),
                    stderrStream = ByteArrayInputStream(stderr.toByteArray()),
                )

            fun running(stdout: InputStream, stderr: InputStream): FakeProcess =
                FakeProcess(
                    completesNormally = false,
                    configuredExitCode = -2,
                    stdoutStream = stdout,
                    stderrStream = stderr,
                )
        }
    }

    private class CloseAwareBlockingInputStream : InputStream() {
        private val closedLatch = CountDownLatch(1)

        @Volatile
        var isClosed = false
            private set

        override fun read(): Int {
            closedLatch.await()
            return -1
        }

        override fun close() {
            isClosed = true
            closedLatch.countDown()
        }
    }
}
