package com.hidsquid.rootrecoveryhelper.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbDiagnosticsHistoryTest {
    @Test
    fun `new boot diagnostics are prepended without deleting older boots`() {
        val first = AdbDiagnosticsHistory.append(
            history = "",
            bootSequence = 1L,
            bootReceivedAtMillis = 100L,
            recordedAtMillis = 110L,
            diagnostics = "BEFORE_ADBD_STATE=stopped",
        )
        val second = AdbDiagnosticsHistory.append(
            history = first,
            bootSequence = 2L,
            bootReceivedAtMillis = 50L,
            recordedAtMillis = 210L,
            diagnostics = "BEFORE_ADBD_STATE=running",
        )

        val entries = AdbDiagnosticsHistory.entries(second)
        assertEquals(2, entries.size)
        assertTrue(entries[0].contains("BOOT_SEQUENCE=2"))
        assertTrue(entries[0].contains("BOOT_RECEIVED_AT=50"))
        assertTrue(entries[1].contains("BOOT_SEQUENCE=1"))
        assertTrue(entries[1].contains("BOOT_RECEIVED_AT=100"))
    }

    @Test
    fun `rerun in the same boot replaces that boot entry`() {
        val first = AdbDiagnosticsHistory.append("", 7L, 100L, 110L, "STATE=first")
        val replaced = AdbDiagnosticsHistory.append(
            first,
            7L,
            100L,
            120L,
            "STATE=second",
        )

        val entries = AdbDiagnosticsHistory.entries(replaced)
        assertEquals(1, entries.size)
        assertTrue(entries.single().contains("STATE=second"))
        assertFalse(entries.single().contains("STATE=first"))
    }

    @Test
    fun `history keeps only the latest three boots by default`() {
        var history = ""
        repeat(7) { index ->
            history = AdbDiagnosticsHistory.append(
                history = history,
                bootSequence = index.toLong() + 1L,
                bootReceivedAtMillis = index.toLong() + 1L,
                recordedAtMillis = index.toLong() + 10L,
                diagnostics = "INDEX=$index",
            )
        }

        val entries = AdbDiagnosticsHistory.entries(history)
        assertEquals(3, entries.size)
        assertTrue(entries.first().contains("INDEX=6"))
        assertTrue(entries.last().contains("INDEX=4"))
    }
}
