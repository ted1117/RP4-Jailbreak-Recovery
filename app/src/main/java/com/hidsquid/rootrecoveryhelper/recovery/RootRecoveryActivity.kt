package com.hidsquid.rootrecoveryhelper.recovery

import com.hidsquid.rootrecoveryhelper.R
import com.hidsquid.rootrecoveryhelper.launcher.ExternalAppLauncher

class RootRecoveryActivity : RecoveryDialogActivity() {
    override val titleRes = R.string.root_recovery_title
    override val messageRes = R.string.root_recovery_message
    override val buttonRes = R.string.open_magisk
    override val appNotFoundMessageRes = R.string.magisk_not_found

    override suspend fun openExternalApp(): Boolean = ExternalAppLauncher.openMagisk(this)
}
