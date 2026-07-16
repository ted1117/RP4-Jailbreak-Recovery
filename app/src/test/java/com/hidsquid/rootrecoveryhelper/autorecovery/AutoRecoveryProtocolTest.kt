package com.hidsquid.rootrecoveryhelper.autorecovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoRecoveryProtocolTest {
    @Test
    fun `module deletion result is parsed`() {
        assertEquals(
            AutoRecoveryEvent.ModuleFinished(
                moduleId = "zygisk_lsposed",
                status = ModuleRecoveryStatus.DELETED,
            ),
            AutoRecoveryProtocol.parse("RRH|MODULE_RESULT|zygisk_lsposed|DELETED"),
        )
    }

    @Test
    fun `ADB result exposes combined success`() {
        val event = AutoRecoveryProtocol.parse("RRH|ADB|0|0|0|0|1|running")

        assertTrue(event is AutoRecoveryEvent.AdbResult && event.succeeded)
    }

    @Test
    fun `countdown result is parsed`() {
        assertEquals(
            AutoRecoveryEvent.RebootCountdown(10),
            AutoRecoveryProtocol.parse("RRH|COUNTDOWN|10"),
        )
    }

    @Test
    fun `Zygisk activation result is parsed`() {
        assertEquals(
            AutoRecoveryEvent.ZygiskResult(ZygiskRecoveryStatus.ENABLED),
            AutoRecoveryProtocol.parse("RRH|ZYGISK|ENABLED"),
        )
    }

    @Test
    fun `summary contains verified Zygisk state`() {
        assertEquals(
            AutoRecoveryEvent.Summary(
                deletedCount = 2,
                failureCount = 0,
                zygiskReady = true,
                zygiskChanged = true,
            ),
            AutoRecoveryProtocol.parse("RRH|SUMMARY|2|0|1|1"),
        )
    }

    @Test
    fun `unmarked output is ignored`() {
        assertNull(AutoRecoveryProtocol.parse("permission denied"))
    }
}
