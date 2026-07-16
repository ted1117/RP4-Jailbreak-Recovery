package com.hidsquid.rootrecoveryhelper.diagnostics

object AdbDiagnosticsHistory {
    fun append(
        history: String,
        bootReceivedAtMillis: Long,
        recordedAtMillis: Long,
        diagnostics: String,
        maxEntries: Int = DEFAULT_MAX_ENTRIES,
    ): String {
        if (diagnostics.isBlank() || maxEntries <= 0) {
            return history
        }

        val bootId = bootReceivedAtMillis.takeIf { it > 0L } ?: recordedAtMillis
        val newEntry = buildString {
            append(BOOT_ID_PREFIX)
            append(bootId)
            append('\n')
            append("RECORDED_AT=")
            append(recordedAtMillis)
            append('\n')
            append(diagnostics.trim())
        }
        val previousEntries = entries(history)
            .filterNot { entry -> entry.bootId() == bootId }

        return (listOf(newEntry) + previousEntries)
            .take(maxEntries)
            .joinToString(separator = ENTRY_SEPARATOR)
    }

    fun entries(history: String): List<String> = history
        .split(ENTRY_SEPARATOR)
        .map(String::trim)
        .filter(String::isNotBlank)

    private fun String.bootId(): Long? = lineSequence()
        .firstOrNull { it.startsWith(BOOT_ID_PREFIX) }
        ?.substringAfter('=')
        ?.toLongOrNull()

    private const val BOOT_ID_PREFIX = "BOOT_RECEIVED_AT="
    private const val ENTRY_SEPARATOR = "\n\n===== PREVIOUS_BOOT =====\n\n"
    private const val DEFAULT_MAX_ENTRIES = 5
}
