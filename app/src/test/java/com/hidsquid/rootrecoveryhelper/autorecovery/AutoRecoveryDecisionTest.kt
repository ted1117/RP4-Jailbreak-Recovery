package com.hidsquid.rootrecoveryhelper.autorecovery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoRecoveryDecisionTest {
    @Test
    fun `successful deletion schedules LSPosed setup`() {
        assertTrue(
            AutoRecoveryDecision.shouldScheduleLsposedSetup(
                deletedCount = 1,
                failureCount = 0,
                zygiskReady = true,
                zygiskChanged = false,
            ),
        )
    }

    @Test
    fun `no deletion does not schedule LSPosed setup`() {
        assertFalse(
            AutoRecoveryDecision.shouldScheduleLsposedSetup(
                deletedCount = 0,
                failureCount = 0,
                zygiskReady = true,
                zygiskChanged = false,
            ),
        )
    }

    @Test
    fun `processing failure does not schedule LSPosed setup`() {
        assertFalse(
            AutoRecoveryDecision.shouldScheduleLsposedSetup(
                deletedCount = 1,
                failureCount = 1,
                zygiskReady = true,
                zygiskChanged = false,
            ),
        )
    }

    @Test
    fun `Zygisk failure does not schedule LSPosed setup`() {
        assertFalse(
            AutoRecoveryDecision.shouldScheduleLsposedSetup(
                deletedCount = 1,
                failureCount = 0,
                zygiskReady = false,
                zygiskChanged = true,
            ),
        )
    }

    @Test
    fun `Zygisk-only recovery schedules LSPosed setup`() {
        assertTrue(
            AutoRecoveryDecision.shouldScheduleLsposedSetup(
                deletedCount = 0,
                failureCount = 0,
                zygiskReady = true,
                zygiskChanged = true,
            ),
        )
    }
}
