package com.hidsquid.rootrecoveryhelper.autorecovery

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class AutoRecoveryRunResult(
    val exitCode: Int,
    val timedOut: Boolean,
    val rootGranted: Boolean,
    val adbEnabled: Boolean,
    val moduleResults: Map<String, ModuleRecoveryStatus>,
    val zygiskStatus: ZygiskRecoveryStatus?,
    val zygiskChanged: Boolean,
    val deletedCount: Int,
    val failureCount: Int,
    val rebootRequested: Boolean,
    val startFailure: String = "",
)

class AutoRecoveryRunner(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val processStarter: (String) -> Process = { command ->
        ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
    },
) {
    suspend fun run(
        onEvent: suspend (AutoRecoveryEvent) -> Unit,
    ): AutoRecoveryRunResult = withContext(Dispatchers.IO) {
        val command = AutoRecoveryScript.build()
        val process = try {
            processStarter(command)
        } catch (exception: Exception) {
            return@withContext AutoRecoveryRunResult(
                exitCode = START_FAILED_EXIT_CODE,
                timedOut = false,
                rootGranted = false,
                adbEnabled = false,
                moduleResults = emptyMap(),
                zygiskStatus = null,
                zygiskChanged = false,
                deletedCount = 0,
                failureCount = 1,
                rebootRequested = false,
                startFailure = exception.message.orEmpty(),
            )
        }

        process.outputStream.closeQuietly()
        val state = MutableRunState()
        var timedOut = false

        try {
            withTimeout(timeoutMillis) {
                process.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = runInterruptible { reader.readLine() } ?: break
                        val event = AutoRecoveryProtocol.parse(line) ?: continue
                        state.apply(event)
                        onEvent(event)
                    }
                }
                runInterruptible {
                    process.waitFor(PROCESS_EXIT_GRACE_MILLIS, TimeUnit.MILLISECONDS)
                }
            }
        } catch (_: TimeoutCancellationException) {
            timedOut = true
        } finally {
            if (process.isAlive) {
                process.destroy()
                runCatching {
                    if (!process.waitFor(PROCESS_DESTROY_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly()
                    }
                }
            }
            process.inputStream.closeQuietly()
            process.errorStream.closeQuietly()
            process.outputStream.closeQuietly()
        }

        AutoRecoveryRunResult(
            exitCode = if (timedOut || process.isAlive) {
                TIMEOUT_EXIT_CODE
            } else {
                runCatching { process.exitValue() }.getOrDefault(TIMEOUT_EXIT_CODE)
            },
            timedOut = timedOut,
            rootGranted = state.rootGranted,
            adbEnabled = state.adbEnabled,
            moduleResults = state.moduleResults.toMap(),
            zygiskStatus = state.zygiskStatus,
            zygiskChanged = state.zygiskChanged,
            deletedCount = state.deletedCount,
            failureCount = state.failureCount,
            rebootRequested = state.rebootRequested,
        )
    }

    private class MutableRunState {
        var rootGranted: Boolean = false
        var adbEnabled: Boolean = false
        val moduleResults = linkedMapOf<String, ModuleRecoveryStatus>()
        var zygiskStatus: ZygiskRecoveryStatus? = null
        var zygiskChanged: Boolean = false
        var deletedCount: Int = 0
        var failureCount: Int = 0
        var rebootRequested: Boolean = false

        fun apply(event: AutoRecoveryEvent) {
            when (event) {
                is AutoRecoveryEvent.RootResult -> rootGranted = event.granted
                is AutoRecoveryEvent.AdbResult -> adbEnabled = event.succeeded
                is AutoRecoveryEvent.ModuleStarted -> Unit
                is AutoRecoveryEvent.ModuleFinished -> {
                    moduleResults[event.moduleId] = event.status
                }
                is AutoRecoveryEvent.ZygiskResult -> {
                    zygiskStatus = event.status
                }
                is AutoRecoveryEvent.Summary -> {
                    deletedCount = event.deletedCount
                    failureCount = event.failureCount
                    zygiskChanged = event.zygiskChanged
                }
                is AutoRecoveryEvent.RebootCountdown -> Unit
                is AutoRecoveryEvent.RebootResult -> {
                    rebootRequested = event.status == "REQUESTED"
                }
            }
        }
    }

    private fun AutoCloseable.closeQuietly() {
        runCatching { close() }
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MILLIS = 60_000L
        private const val PROCESS_EXIT_GRACE_MILLIS = 2_000L
        private const val PROCESS_DESTROY_GRACE_MILLIS = 500L
        private const val START_FAILED_EXIT_CODE = -1
        private const val TIMEOUT_EXIT_CODE = -2
    }
}
