package com.hidsquid.rootrecoveryhelper.launcher

import com.hidsquid.rootrecoveryhelper.root.RootCommandExecutor

object ExternalAppLauncher {
    suspend fun openLsposedManager(): Boolean {
        val result = rootCommandExecutor.execute(LSPOSED_MANAGER_LAUNCH_COMMAND)
        return !result.timedOut && result.exitCode == 0
    }
    private const val LSPOSED_MANAGER_LAUNCH_CATEGORY =
        "org.lsposed.manager.LAUNCH_MANAGER"
    private const val LSPOSED_MANAGER_SHELL_COMPONENT =
        "com.android.shell/.BugreportWarningActivity"
    private const val LSPOSED_MANAGER_LAUNCH_COMMAND =
        "am start -c $LSPOSED_MANAGER_LAUNCH_CATEGORY $LSPOSED_MANAGER_SHELL_COMPONENT"

    private val rootCommandExecutor = RootCommandExecutor(timeoutMillis = 5_000L)
}
