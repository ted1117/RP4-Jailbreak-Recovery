package com.hidsquid.rootrecoveryhelper.boot

import org.junit.Assert.assertEquals
import org.junit.Test

class RecoveryStateResolverTest {
    @Test
    fun `root unavailable takes precedence`() {
        val state = RecoveryStateResolver.resolve(
            hasRootAccess = false,
            isLsposedDisabled = true,
            pendingLsposedSetup = true,
        )

        assertEquals(RecoveryState.ROOT_UNAVAILABLE, state)
    }

    @Test
    fun `disabled module requires root recovery`() {
        val state = RecoveryStateResolver.resolve(
            hasRootAccess = true,
            isLsposedDisabled = true,
            pendingLsposedSetup = false,
        )

        assertEquals(RecoveryState.LSPOSED_DISABLED, state)
    }

    @Test
    fun `reenabled module with pending flag requires LSPosed setup`() {
        val state = RecoveryStateResolver.resolve(
            hasRootAccess = true,
            isLsposedDisabled = false,
            pendingLsposedSetup = true,
        )

        assertEquals(RecoveryState.LSPOSED_REENABLED_PENDING_SETUP, state)
    }

    @Test
    fun `enabled module without pending flag is normal`() {
        val state = RecoveryStateResolver.resolve(
            hasRootAccess = true,
            isLsposedDisabled = false,
            pendingLsposedSetup = false,
        )

        assertEquals(RecoveryState.NORMAL, state)
    }
}
