package com.hidsquid.rootrecoveryhelper.boot

import com.hidsquid.rootrecoveryhelper.root.LsposedModuleState
import com.hidsquid.rootrecoveryhelper.root.ZygiskState
import org.junit.Assert.assertEquals
import org.junit.Test

class DelayedDialogTargetResolverTest {
    @Test
    fun `disabled zygisk LSPosed module opens root recovery consent dialog`() {
        assertEquals(
            DelayedDialogTarget.ROOT_RECOVERY,
            DelayedDialogTargetResolver.resolve(
                pendingLsposedSetup = false,
                lsposedModuleState = LsposedModuleState.DISABLED,
                zygiskState = ZygiskState.ENABLED,
            ),
        )
    }

    @Test
    fun `post recovery boot opens LSPosed setup dialog`() {
        assertEquals(
            DelayedDialogTarget.LSPOSED_SETUP,
            DelayedDialogTargetResolver.resolve(
                pendingLsposedSetup = true,
                lsposedModuleState = LsposedModuleState.ENABLED,
                zygiskState = ZygiskState.ENABLED,
            ),
        )
    }

    @Test
    fun `disabled zygisk LSPosed takes precedence over pending setup`() {
        assertEquals(
            DelayedDialogTarget.ROOT_RECOVERY,
            DelayedDialogTargetResolver.resolve(
                pendingLsposedSetup = true,
                lsposedModuleState = LsposedModuleState.DISABLED,
                zygiskState = ZygiskState.DISABLED,
            ),
        )
    }

    @Test
    fun `enabled zygisk LSPosed module opens no dialog`() {
        assertEquals(
            DelayedDialogTarget.NONE,
            DelayedDialogTargetResolver.resolve(
                pendingLsposedSetup = false,
                lsposedModuleState = LsposedModuleState.ENABLED,
                zygiskState = ZygiskState.ENABLED,
            ),
        )
    }

    @Test
    fun `unknown zygisk LSPosed state opens no dialog`() {
        assertEquals(
            DelayedDialogTarget.NONE,
            DelayedDialogTargetResolver.resolve(
                pendingLsposedSetup = true,
                lsposedModuleState = LsposedModuleState.UNKNOWN,
                zygiskState = ZygiskState.UNKNOWN,
            ),
        )
    }

    @Test
    fun `disabled Zygisk opens root recovery even without module marker`() {
        assertEquals(
            DelayedDialogTarget.ROOT_RECOVERY,
            DelayedDialogTargetResolver.resolve(
                pendingLsposedSetup = false,
                lsposedModuleState = LsposedModuleState.ENABLED,
                zygiskState = ZygiskState.DISABLED,
            ),
        )
    }

    @Test
    fun `permanent ignore suppresses root recovery dialog`() {
        assertEquals(
            DelayedDialogTarget.NONE,
            DelayedDialogTargetResolver.resolve(
                pendingLsposedSetup = false,
                lsposedModuleState = LsposedModuleState.DISABLED,
                zygiskState = ZygiskState.DISABLED,
                ignoreRecoveryDialogsForever = true,
            ),
        )
    }

    @Test
    fun `permanent ignore suppresses pending LSPosed setup too`() {
        assertEquals(
            DelayedDialogTarget.NONE,
            DelayedDialogTargetResolver.resolve(
                pendingLsposedSetup = true,
                lsposedModuleState = LsposedModuleState.ENABLED,
                zygiskState = ZygiskState.ENABLED,
                ignoreRecoveryDialogsForever = true,
            ),
        )
    }
}
