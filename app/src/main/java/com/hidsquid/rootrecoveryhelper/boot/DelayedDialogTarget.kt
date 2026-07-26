package com.hidsquid.rootrecoveryhelper.boot

import com.hidsquid.rootrecoveryhelper.root.LsposedModuleState
import com.hidsquid.rootrecoveryhelper.root.ZygiskState

enum class DelayedDialogTarget {
    ROOT_RECOVERY,
    LSPOSED_SETUP,
    NONE,
}

object DelayedDialogTargetResolver {
    fun resolve(
        pendingLsposedSetup: Boolean,
        lsposedModuleState: LsposedModuleState,
        zygiskState: ZygiskState,
        ignoreRecoveryDialogsForever: Boolean = false,
    ): DelayedDialogTarget = when {
        ignoreRecoveryDialogsForever -> DelayedDialogTarget.NONE
        lsposedModuleState == LsposedModuleState.DISABLED ||
            zygiskState == ZygiskState.DISABLED ->
            DelayedDialogTarget.ROOT_RECOVERY
        lsposedModuleState == LsposedModuleState.ENABLED &&
            zygiskState == ZygiskState.ENABLED &&
            pendingLsposedSetup ->
            DelayedDialogTarget.LSPOSED_SETUP
        else -> DelayedDialogTarget.NONE
    }
}
