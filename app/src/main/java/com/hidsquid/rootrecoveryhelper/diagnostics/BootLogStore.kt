package com.hidsquid.rootrecoveryhelper.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BootLogImportResult(
    val latestBootSequence: Long,
    val sequenceMappings: Map<Long, Long>,
)

class BootLogStore(
    private val directory: File,
    private val maxBootLogs: Int = DEFAULT_MAX_BOOT_LOGS,
) {
    init {
        require(maxBootLogs > 0) { "maxBootLogs must be greater than zero" }
    }

    fun startBoot(
        bootSequence: Long,
        bootReceivedAtMillis: Long,
        recordedAtMillis: Long,
        detail: String = "",
    ) {
        if (bootSequence <= 0L) {
            return
        }

        synchronized(FILE_LOCK) {
            ensureDirectory()
            writeAtomically(
                file = logFileForWrite(
                    bootSequence = bootSequence,
                    bootReceivedAtMillis = bootReceivedAtMillis,
                ),
                content = eventEntry(
                    bootSequence = bootSequence,
                    bootReceivedAtMillis = bootReceivedAtMillis,
                    recordedAtMillis = recordedAtMillis,
                    event = "BOOT_COMPLETED",
                    detail = detail,
                ),
            )
            pruneOldLogs()
        }
    }

    fun append(
        bootSequence: Long,
        bootReceivedAtMillis: Long,
        recordedAtMillis: Long,
        event: String,
        detail: String = "",
    ) {
        if (bootSequence <= 0L) {
            return
        }

        synchronized(FILE_LOCK) {
            ensureDirectory()
            val file = logFileForWrite(
                bootSequence = bootSequence,
                bootReceivedAtMillis = bootReceivedAtMillis,
            )
            val currentLog = if (file.exists() && file.length() > 0L) {
                file.readText(StandardCharsets.UTF_8)
            } else {
                eventEntry(
                    bootSequence = bootSequence,
                    bootReceivedAtMillis = bootReceivedAtMillis,
                    recordedAtMillis = recordedAtMillis,
                    event = "BOOT_LOG_STARTED",
                    detail = "LOGGING_ENABLED_DURING_BOOT=true",
                )
            }
            writeAtomically(
                file = file,
                content = currentLog + eventEntry(
                    bootSequence = bootSequence,
                    bootReceivedAtMillis = bootReceivedAtMillis,
                    recordedAtMillis = recordedAtMillis,
                    event = event,
                    detail = detail,
                ),
            )
            pruneOldLogs()
        }
    }

    fun latestBootSequence(): Long = synchronized(FILE_LOCK) {
        ensureDirectory()
        indexedBootLogFiles(directory).firstOrNull()?.first ?: 0L
    }

    fun hasBootLog(bootSequence: Long): Boolean = synchronized(FILE_LOCK) {
        ensureDirectory()
        findBootLog(bootSequence) != null
    }

    fun bootReceivedAtMillis(bootSequence: Long): Long? = synchronized(FILE_LOCK) {
        ensureDirectory()
        findBootLog(bootSequence)?.let(::readBootReceivedAtMillis)
    }

    fun importFrom(sourceDirectory: File): BootLogImportResult {
        if (
            !sourceDirectory.isDirectory ||
            sourceDirectory.absolutePath == directory.absolutePath
        ) {
            return BootLogImportResult(
                latestBootSequence = latestBootSequence(),
                sequenceMappings = emptyMap(),
            )
        }

        synchronized(FILE_LOCK) {
            ensureDirectory()
            var latestSequence = indexedBootLogFiles(directory)
                .firstOrNull()
                ?.first
                ?: 0L
            val mappings = linkedMapOf<Long, Long>()
            val importedSourceFiles = mutableListOf<File>()

            indexedBootLogFiles(sourceDirectory)
                .sortedBy { (sequence, _) -> sequence }
                .forEach { (sourceSequence, sourceFile) ->
                    val sourceContent = sourceFile.readText(StandardCharsets.UTF_8)
                    val sourceBootReceivedAt = readBootReceivedAtMillis(sourceFile)
                    val matchingBoot = sourceBootReceivedAt?.let { receivedAt ->
                        indexedBootLogFiles(directory).firstOrNull { (_, destinationFile) ->
                            readBootReceivedAtMillis(destinationFile) == receivedAt
                        }
                    }
                    val destinationSequence = matchingBoot?.first ?: if (
                        sourceSequence > latestSequence &&
                        findBootLog(sourceSequence) == null
                    ) {
                        sourceSequence
                    } else {
                        nextSequence(latestSequence)
                    }
                    val importedContent = remapBootSequence(
                        content = sourceContent,
                        destinationSequence = destinationSequence,
                    )
                    val destinationContent = matchingBoot?.second?.readText(
                        StandardCharsets.UTF_8,
                    )
                    writeAtomically(
                        file = logFileForWrite(
                            bootSequence = destinationSequence,
                            bootReceivedAtMillis = sourceBootReceivedAt ?: 0L,
                        ),
                        content = mergeBootLogContent(
                            destinationContent = destinationContent,
                            importedContent = importedContent,
                        ),
                    )
                    importedSourceFiles += sourceFile
                    mappings[sourceSequence] = destinationSequence
                    latestSequence = maxOf(latestSequence, destinationSequence)
                }

            pruneOldLogs()
            val sourceFilesDeleted = importedSourceFiles
                .map { sourceFile -> !sourceFile.exists() || sourceFile.delete() }
                .all { deleted -> deleted }
            if (sourceFilesDeleted) {
                sourceDirectory.delete()
            }
            return BootLogImportResult(
                latestBootSequence = latestSequence,
                sequenceMappings = mappings,
            )
        }
    }

    private fun readBootReceivedAtMillis(file: File): Long? =
        file.useLines(StandardCharsets.UTF_8) { lines ->
            lines.firstOrNull { line ->
                line.startsWith(BOOT_RECEIVED_AT_PREFIX)
            }?.substringAfter('=')?.toLongOrNull()
        }

    private fun mergeBootLogContent(
        destinationContent: String?,
        importedContent: String,
    ): String {
        if (destinationContent.isNullOrBlank()) {
            return importedContent
        }
        val normalizedImport = importedContent.trim()
        if (destinationContent.contains(normalizedImport)) {
            return destinationContent
        }
        return destinationContent.trimEnd() + "\n\n" + importedContent
    }

    private fun nextSequence(currentSequence: Long): Long {
        if (currentSequence == Long.MAX_VALUE) {
            throw IOException("Boot log sequence is exhausted")
        }
        return currentSequence + 1L
    }

    private fun remapBootSequence(
        content: String,
        destinationSequence: Long,
    ): String {
        val remapped = BOOT_SEQUENCE_LINE_PATTERN.replace(
            content,
            "BOOT_SEQUENCE=$destinationSequence",
        )
        return if (remapped == content && !BOOT_SEQUENCE_LINE_PATTERN.containsMatchIn(content)) {
            "BOOT_SEQUENCE=$destinationSequence\n$content"
        } else {
            remapped
        }
    }

    private fun writeAtomically(file: File, content: String) {
        val temporaryFile = File(directory, "${file.name}.tmp")
        try {
            writeSynced(temporaryFile, content)
            try {
                Files.move(
                    temporaryFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (atomicMoveFailure: Exception) {
                try {
                    Files.move(
                        temporaryFile.toPath(),
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (fallbackFailure: Exception) {
                    fallbackFailure.addSuppressed(atomicMoveFailure)
                    throw fallbackFailure
                }
            }
        } finally {
            if (temporaryFile.exists()) {
                temporaryFile.delete()
            }
        }
    }

    private fun writeSynced(file: File, content: String) {
        FileOutputStream(file, false).use { output ->
            output.write(content.toByteArray(StandardCharsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
    }

    private fun ensureDirectory() {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Failed to create boot log directory: ${directory.path}")
        }
        if (!directory.isDirectory) {
            throw IOException("Boot log path is not a directory: ${directory.path}")
        }
        migrateLegacyFileNames()
    }

    private fun migrateLegacyFileNames() {
        indexedBootLogFiles(directory).forEach { (bootSequence, file) ->
            val bootReceivedAtMillis = readBootReceivedAtMillis(file) ?: return@forEach
            val expectedFile = File(directory, timestampFileName(bootReceivedAtMillis))
            if (file.name == expectedFile.name) {
                return@forEach
            }
            if (!expectedFile.exists()) {
                moveFile(file, expectedFile)
                return@forEach
            }
            if (readBootSequence(expectedFile) == bootSequence) {
                if (!file.delete()) {
                    throw IOException("Failed to remove duplicate boot log: ${file.path}")
                }
                return@forEach
            }
            val collisionFile = collisionFile(bootReceivedAtMillis, bootSequence)
            if (!collisionFile.exists()) {
                moveFile(file, collisionFile)
            }
        }
    }

    private fun pruneOldLogs() {
        indexedBootLogFiles(directory)
            .drop(maxBootLogs)
            .forEach { (_, file) ->
                if (!file.delete()) {
                    throw IOException("Failed to delete old boot log: ${file.path}")
                }
            }
    }

    private fun indexedBootLogFiles(sourceDirectory: File): List<Pair<Long, File>> =
        sourceDirectory.listFiles()
            .orEmpty()
            .filter(File::isFile)
            .mapNotNull { file ->
                val bootSequence = readBootSequence(file)
                bootSequence?.let { it to file }
            }
            .sortedByDescending { (bootSequence, _) -> bootSequence }

    private fun readBootSequence(file: File): Long? =
        file.useLines(StandardCharsets.UTF_8) { lines ->
            lines.firstOrNull { line ->
                line.startsWith(BOOT_SEQUENCE_PREFIX)
            }?.substringAfter('=')?.toLongOrNull()
        }

    private fun findBootLog(bootSequence: Long): File? =
        indexedBootLogFiles(directory).firstOrNull { (sequence, _) ->
            sequence == bootSequence
        }?.second

    private fun logFileForWrite(
        bootSequence: Long,
        bootReceivedAtMillis: Long,
    ): File {
        val expectedFile = File(directory, timestampFileName(bootReceivedAtMillis))
        val existingFile = findBootLog(bootSequence)
        if (existingFile == null) {
            return if (
                !expectedFile.exists() || readBootSequence(expectedFile) == bootSequence
            ) {
                expectedFile
            } else {
                collisionFile(bootReceivedAtMillis, bootSequence)
            }
        }
        if (existingFile.name == expectedFile.name) {
            return existingFile
        }
        if (!expectedFile.exists()) {
            moveFile(existingFile, expectedFile)
            return expectedFile
        }
        if (readBootSequence(expectedFile) == bootSequence) {
            if (!existingFile.delete()) {
                throw IOException("Failed to remove duplicate boot log: ${existingFile.path}")
            }
            return expectedFile
        }
        val collisionFile = collisionFile(bootReceivedAtMillis, bootSequence)
        if (!collisionFile.exists()) {
            moveFile(existingFile, collisionFile)
        }
        return collisionFile
    }

    private fun collisionFile(
        bootReceivedAtMillis: Long,
        bootSequence: Long,
    ): File = File(
        directory,
        "${timestampBaseName(bootReceivedAtMillis)}-$bootSequence.log",
    )

    private fun moveFile(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (atomicMoveFailure: Exception) {
            try {
                Files.move(source.toPath(), destination.toPath())
            } catch (fallbackFailure: Exception) {
                fallbackFailure.addSuppressed(atomicMoveFailure)
                throw fallbackFailure
            }
        }
    }

    private fun timestampFileName(bootReceivedAtMillis: Long): String =
        "${timestampBaseName(bootReceivedAtMillis)}.log"

    private fun timestampBaseName(bootReceivedAtMillis: Long): String =
        SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US).format(Date(bootReceivedAtMillis))

    private fun eventEntry(
        bootSequence: Long,
        bootReceivedAtMillis: Long,
        recordedAtMillis: Long,
        event: String,
        detail: String,
    ): String = buildString {
        append("===== EVENT =====\n")
        append("BOOT_SEQUENCE=")
        append(bootSequence)
        append('\n')
        append("BOOT_RECEIVED_AT=")
        append(bootReceivedAtMillis)
        append('\n')
        append("RECORDED_AT=")
        append(recordedAtMillis)
        append('\n')
        append("TYPE=")
        append(event)
        append('\n')
        if (detail.isNotBlank()) {
            append(detail.trim())
            append('\n')
        }
        append('\n')
    }

    companion object {
        const val DIRECTORY_NAME = "boot_logs"

        private const val DEFAULT_MAX_BOOT_LOGS = 3
        private const val TIMESTAMP_PATTERN = "yyyy-MM-dd-HH-mm-ss"
        private const val BOOT_SEQUENCE_PREFIX = "BOOT_SEQUENCE="
        private const val BOOT_RECEIVED_AT_PREFIX = "BOOT_RECEIVED_AT="
        private val BOOT_SEQUENCE_LINE_PATTERN = Regex("(?m)^BOOT_SEQUENCE=\\d+$")
        private val FILE_LOCK = Any()
    }
}
