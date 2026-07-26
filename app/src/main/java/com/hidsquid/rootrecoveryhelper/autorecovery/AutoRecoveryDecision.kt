package com.hidsquid.rootrecoveryhelper.autorecovery

object AutoRecoveryDecision {
    fun shouldScheduleLsposedSetup(
        deletedCount: Int,
        failureCount: Int,
        zygiskReady: Boolean,
        zygiskChanged: Boolean,
    ): Boolean =
        (deletedCount > 0 || zygiskChanged) && failureCount == 0 && zygiskReady
}
