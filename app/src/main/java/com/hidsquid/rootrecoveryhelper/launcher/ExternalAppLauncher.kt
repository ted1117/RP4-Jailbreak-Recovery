package com.hidsquid.rootrecoveryhelper.launcher

import android.content.Context
import android.content.Intent
import com.hidsquid.rootrecoveryhelper.root.RootCommandExecutor

object ExternalAppLauncher {
    fun openMagisk(context: Context): Boolean = openPackage(context, MAGISK_PACKAGE)

    suspend fun openLsposedManager(): Boolean {
        val result = rootCommandExecutor.execute(LSPOSED_MANAGER_LAUNCH_COMMAND)
        return !result.timedOut && result.exitCode == 0
    }

    private fun openPackage(context: Context, packageName: String): Boolean {
        val launchIntent = try {
            context.packageManager.getLaunchIntentForPackage(packageName)
        } catch (_: RuntimeException) {
            null
        } ?: return false

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(launchIntent)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    private const val MAGISK_PACKAGE = "com.topjohnwu.magisk"
    private const val LSPOSED_MANAGER_LAUNCH_CATEGORY =
        "org.lsposed.manager.LAUNCH_MANAGER"
    private const val LSPOSED_MANAGER_SHELL_COMPONENT =
        "com.android.shell/.BugreportWarningActivity"
    private const val LSPOSED_MANAGER_LAUNCH_COMMAND =
        "am start -c $LSPOSED_MANAGER_LAUNCH_CATEGORY $LSPOSED_MANAGER_SHELL_COMPONENT"

    private val rootCommandExecutor = RootCommandExecutor(timeoutMillis = 5_000L)
}
