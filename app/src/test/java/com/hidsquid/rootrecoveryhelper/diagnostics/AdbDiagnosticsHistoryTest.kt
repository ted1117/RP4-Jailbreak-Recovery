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
            bootReceivedAtMillis = 100L,
            recordedAtMillis = 110L,
            diagnostics = "BEFORE_ADBD_STATE=stopped",
        )
        val second = AdbDiagnosticsHistory.append(
            history = first,
            bootReceivedAtMillis = 200L,
            recordedAtMillis = 210L,
            diagnostics = "BEFORE_ADBD_STATE=running",
        )

        val entries = AdbDiagnosticsHistory.entries(second)
        assertEquals(2, entries.size)
        assertTrue(entries[0].contains("BOOT_RECEIVED_AT=200"))
        assertTrue(entries[1].contains("BOOT_RECEIVED_AT=100"))
    }

    @Test
    fun `rerun in the same boot replaces that boot entry`() {
        val first = AdbDiagnosticsHistory.append("", 100L, 110L, "STATE=first")
        val replaced = AdbDiagnosticsHistory.append(first, 100L, 120L, "STATE=second")

        val entries = AdbDiagnosticsHistory.entries(replaced)
        assertEquals(1, entries.size)
        assertTrue(entries.single().contains("STATE=second"))
        assertFalse(entries.single().contains("STATE=first"))
    }

    @Test
    fun `history keeps only the configured number of boots`() {
        var history = ""
        repeat(7) { index ->
            history = AdbDiagnosticsHistory.append(
                history = history,
                bootReceivedAtMillis = index.toLong() + 1L,
                recordedAtMillis = index.toLong() + 10L,
                diagnostics = "INDEX=$index",
                maxEntries = 5,
            )
        }

        val entries = AdbDiagnosticsHistory.entries(history)
        assertEquals(5, entries.size)
        assertTrue(entries.first().contains("INDEX=6"))
        assertTrue(entries.last().contains("INDEX=2"))
    }
}
