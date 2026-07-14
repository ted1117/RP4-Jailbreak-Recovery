package com.hidsquid.rootrecoveryhelper.recovery

import com.hidsquid.rootrecoveryhelper.R
import com.hidsquid.rootrecoveryhelper.launcher.ExternalAppLauncher
import com.hidsquid.rootrecoveryhelper.storage.RecoveryPreferences

class LsposedSetupActivity : RecoveryDialogActivity() {
    override val titleRes = R.string.lsposed_setup_title
    override val messageRes = R.string.lsposed_setup_message
    override val buttonRes = R.string.open_lsposed_manager
    override val appNotFoundMessageRes = R.string.lsposed_manager_not_found

    override suspend fun openExternalApp(): Boolean {
        val opened = ExternalAppLauncher.openLsposedManager()
        if (opened) {
            val preferences = RecoveryPreferences(this)
            if (!preferences.commitPendingLsposedSetup(false)) {
                preferences.pendingLsposedSetup = false
            }
        }
        return opened
    }
}
