package com.hidsquid.rootrecoveryhelper.boot

enum class RecoveryState {
    ROOT_UNAVAILABLE,
    LSPOSED_DISABLED,
    LSPOSED_REENABLED_PENDING_SETUP,
    NORMAL,
}

object RecoveryStateResolver {
    fun resolve(
        hasRootAccess: Boolean,
        isLsposedDisabled: Boolean,
        pendingLsposedSetup: Boolean,
    ): RecoveryState = when {
        !hasRootAccess -> RecoveryState.ROOT_UNAVAILABLE
        isLsposedDisabled -> RecoveryState.LSPOSED_DISABLED
        pendingLsposedSetup -> RecoveryState.LSPOSED_REENABLED_PENDING_SETUP
        else -> RecoveryState.NORMAL
    }
}
