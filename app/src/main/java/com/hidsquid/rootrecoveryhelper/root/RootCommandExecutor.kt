package com.hidsquid.rootrecoveryhelper.root

import java.io.InputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

class RootCommandExecutor(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val processStarter: (String) -> Process = { command ->
        ProcessBuilder("su", "-c", command).start()
    },
) : RootCommandRunner {

    override suspend fun execute(command: String): CommandResult = withContext(Dispatchers.IO) {
        val process = try {
            processStarter(command)
        } catch (exception: Exception) {
            return@withContext CommandResult(
                exitCode = START_FAILED_EXIT_CODE,
                stdout = "",
                stderr = exception.message.orEmpty(),
            )
        }

        closeQuietly(process.outputStream)
        val stdoutReader = STREAM_READER_EXECUTOR.submit<String> {
            process.inputStream.readSafely()
        }
        val stderrReader = STREAM_READER_EXECUTOR.submit<String> {
            process.errorStream.readSafely()
        }

        try {
            val finished = runInterruptible {
                process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            }
            if (!finished) {
                terminate(process)
                closeProcessStreams(process)
            }

            CommandResult(
                exitCode = if (finished) process.exitValue() else TIMEOUT_EXIT_CODE,
                stdout = stdoutReader.getOutput(),
                stderr = stderrReader.getOutput(),
                timedOut = !finished,
            )
        } finally {
            if (process.isAlive) {
                terminate(process)
            }
            closeProcessStreams(process)
            stdoutReader.cancel(true)
            stderrReader.cancel(true)
        }
    }

    private fun Future<String>.getOutput(): String = try {
        get(STREAM_DRAIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        ""
    } catch (_: ExecutionException) {
        ""
    } catch (_: TimeoutException) {
        cancel(true)
        ""
    } catch (_: java.util.concurrent.CancellationException) {
        ""
    }

    private fun InputStream.readSafely(): String = try {
        bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        ""
    }

    private fun terminate(process: Process) {
        process.destroy()
        try {
            if (!process.waitFor(PROCESS_DESTROY_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(PROCESS_DESTROY_GRACE_MILLIS, TimeUnit.MILLISECONDS)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
        } catch (_: Exception) {
            process.destroyForcibly()
        }
    }

    private fun closeProcessStreams(process: Process) {
        closeQuietly(process.inputStream)
        closeQuietly(process.errorStream)
        closeQuietly(process.outputStream)
    }

    private fun closeQuietly(closeable: AutoCloseable) {
        try {
            closeable.close()
        } catch (_: Exception) {
            // The process may already have closed the stream.
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L

        private const val START_FAILED_EXIT_CODE = -1
        private const val TIMEOUT_EXIT_CODE = -2
        private const val STREAM_DRAIN_TIMEOUT_MILLIS = 1_000L
        private const val PROCESS_DESTROY_GRACE_MILLIS = 500L

        private val STREAM_READER_EXECUTOR = Executors.newCachedThreadPool(
            ThreadFactory { runnable ->
                Thread(runnable, "root-command-stream-reader").apply {
                    isDaemon = true
                }
            },
        )
    }
}
