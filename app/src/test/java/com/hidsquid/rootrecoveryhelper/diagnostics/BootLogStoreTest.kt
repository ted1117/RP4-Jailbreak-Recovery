package com.hidsquid.rootrecoveryhelper.diagnostics

import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootLogStoreTest {
    @Test
    fun `events from one boot are appended to the same log`() = withTemporaryDirectory { directory ->
        val store = BootLogStore(directory)

        store.startBoot(7L, 100L, 101L, "receiver saved")
        store.append(7L, 100L, 102L, "CHECK_STAGE:NORMAL", "root state normal")

        val logFile = directory.listFiles().orEmpty().single()
        assertTimestampFileName(logFile.name)
        val log = logFile.readText()
        assertTrue(log.contains("BOOT_SEQUENCE=7"))
        assertTrue(log.contains("BOOT_RECEIVED_AT=100"))
        assertTrue(log.contains("TYPE=BOOT_COMPLETED"))
        assertTrue(log.contains("TYPE=CHECK_STAGE:NORMAL"))
        assertTrue(log.contains("root state normal"))
    }

    @Test
    fun `only the latest three boot logs are retained`() = withTemporaryDirectory { directory ->
        val store = BootLogStore(directory)

        repeat(5) { index ->
            val bootSequence = index.toLong() + 1L
            val wallClockGoingBackwards = 50_000L - (bootSequence * 10_000L)
            store.startBoot(
                bootSequence = bootSequence,
                bootReceivedAtMillis = wallClockGoingBackwards,
                recordedAtMillis = wallClockGoingBackwards,
            )
        }

        val fileNames = directory.listFiles().orEmpty().map { it.name }.sorted()
        assertEquals(
            listOf(
                timestampFileName(0L),
                timestampFileName(10_000L),
                timestampFileName(20_000L),
            ).sorted(),
            fileNames,
        )
    }

    @Test
    fun `starting the same boot again replaces its previous contents`() =
        withTemporaryDirectory { directory ->
            val store = BootLogStore(directory)

            store.startBoot(7L, 100L, 101L)
            store.append(7L, 100L, 102L, "OLD_EVENT")
            store.startBoot(7L, 100L, 103L, "restarted")

            val log = directory.listFiles().orEmpty().single().readText()
            assertFalse(log.contains("OLD_EVENT"))
            assertTrue(log.contains("restarted"))
        }

    @Test
    fun `enabling logging during a boot creates a header before the first event`() =
        withTemporaryDirectory { directory ->
            val store = BootLogStore(directory)

            store.append(7L, 0L, 102L, "CHECK_STAGE:NORMAL")

            val log = directory.listFiles().orEmpty().single().readText()
            assertTrue(log.contains("BOOT_RECEIVED_AT=0"))
            assertTrue(log.contains("TYPE=BOOT_LOG_STARTED"))
            assertTrue(log.contains("LOGGING_ENABLED_DURING_BOOT=true"))
            assertTrue(log.contains("TYPE=CHECK_STAGE:NORMAL"))
        }

    @Test
    fun `private logs are remapped after existing shared logs and only three remain`() =
        withTwoTemporaryDirectories { sharedDirectory, privateDirectory ->
            val sharedStore = BootLogStore(sharedDirectory)
            val privateStore = BootLogStore(privateDirectory)
            sharedStore.startBoot(1L, 100_000L, 100_000L)
            sharedStore.startBoot(2L, 200_000L, 200_000L)
            privateStore.startBoot(1L, 300_000L, 300_000L)
            privateStore.startBoot(2L, 400_000L, 400_000L)

            val result = sharedStore.importFrom(privateDirectory)

            assertEquals(mapOf(1L to 3L, 2L to 4L), result.sequenceMappings)
            assertEquals(4L, result.latestBootSequence)
            assertEquals(4L, sharedStore.latestBootSequence())
            assertEquals(300_000L, sharedStore.bootReceivedAtMillis(3L))
            assertEquals(400_000L, sharedStore.bootReceivedAtMillis(4L))
            assertEquals(
                listOf(
                    timestampFileName(200_000L),
                    timestampFileName(300_000L),
                    timestampFileName(400_000L),
                ).sorted(),
                sharedDirectory.listFiles().orEmpty().map { it.name }.sorted(),
            )
            assertTrue(privateDirectory.listFiles().orEmpty().isEmpty())
            assertTrue(
                sharedDirectory.listFiles().orEmpty()
                    .single { it.name == timestampFileName(300_000L) }
                    .readText()
                    .contains("BOOT_SEQUENCE=3"),
            )
        }

    @Test
    fun `private log sequence is preserved when shared storage is empty`() =
        withTwoTemporaryDirectories { sharedDirectory, privateDirectory ->
            val sharedStore = BootLogStore(sharedDirectory)
            BootLogStore(privateDirectory).startBoot(7L, 700L, 700L)

            val result = sharedStore.importFrom(privateDirectory)

            assertEquals(mapOf(7L to 7L), result.sequenceMappings)
            assertTrue(sharedStore.hasBootLog(7L))
            assertEquals(7L, sharedStore.latestBootSequence())
        }

    @Test
    fun `staged events for an existing boot are merged without creating another boot`() =
        withTwoTemporaryDirectories { sharedDirectory, privateDirectory ->
            val sharedStore = BootLogStore(sharedDirectory)
            sharedStore.startBoot(7L, 100L, 100L)
            BootLogStore(privateDirectory).append(
                bootSequence = 1L,
                bootReceivedAtMillis = 100L,
                recordedAtMillis = 120L,
                event = "CHECK_STAGE:NORMAL",
            )

            val result = sharedStore.importFrom(privateDirectory)

            assertEquals(mapOf(1L to 7L), result.sequenceMappings)
            assertEquals(
                listOf(timestampFileName(100L)),
                sharedDirectory.listFiles()?.map { it.name },
            )
            val mergedLog = sharedDirectory.listFiles().orEmpty().single().readText()
            assertTrue(mergedLog.contains("TYPE=BOOT_COMPLETED"))
            assertTrue(mergedLog.contains("TYPE=CHECK_STAGE:NORMAL"))
            assertTrue(mergedLog.contains("BOOT_SEQUENCE=7"))
            assertFalse(mergedLog.contains("BOOT_SEQUENCE=1"))
        }

    @Test
    fun `legacy sequence file is renamed to its timestamp`() =
        withTemporaryDirectory { directory ->
            directory.resolve("boot-7.log").writeText(
                "BOOT_SEQUENCE=7\nBOOT_RECEIVED_AT=700000\nTYPE=BOOT_COMPLETED\n",
            )

            val store = BootLogStore(directory)
            assertEquals(7L, store.latestBootSequence())

            assertFalse(directory.resolve("boot-7.log").exists())
            assertTrue(directory.resolve(timestampFileName(700000L)).exists())
        }

    private fun withTemporaryDirectory(block: (java.io.File) -> Unit) {
        val directory = Files.createTempDirectory("root-recovery-boot-logs").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun withTwoTemporaryDirectories(
        block: (java.io.File, java.io.File) -> Unit,
    ) {
        withTemporaryDirectory { first ->
            withTemporaryDirectory { second ->
                block(first, second)
            }
        }
    }

    private fun assertTimestampFileName(fileName: String) {
        assertTrue(fileName.matches(Regex("\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}\\.log")))
    }

    private fun timestampFileName(timestampMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(Date(timestampMillis)) + ".log"
}
