package com.hidsquid.rootrecoveryhelper.storage

import android.annotation.SuppressLint
import android.content.Context

class DiagnosticsSettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    var showBootDiagnostics: Boolean
        get() = preferences.getBoolean(KEY_SHOW_BOOT_DIAGNOSTICS, false)
        set(value) {
            commitBoolean(KEY_SHOW_BOOT_DIAGNOSTICS, value)
        }

    var saveRecentBootLogs: Boolean
        get() = preferences.getBoolean(KEY_SAVE_RECENT_BOOT_LOGS, true)
        set(value) {
            commitBoolean(KEY_SAVE_RECENT_BOOT_LOGS, value)
        }

    var forceAdbAlways: Boolean
        get() = preferences.getBoolean(KEY_FORCE_ADB_ALWAYS, false)
        set(value) {
            commitBoolean(KEY_FORCE_ADB_ALWAYS, value)
        }

    @SuppressLint("ApplySharedPref")
    private fun commitBoolean(key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).commit()
    }

    companion object {
        private const val PREFERENCES_NAME = "diagnostics_settings"
        private const val KEY_SHOW_BOOT_DIAGNOSTICS = "show_boot_diagnostics"
        private const val KEY_SAVE_RECENT_BOOT_LOGS = "save_recent_boot_logs"
        private const val KEY_FORCE_ADB_ALWAYS = "force_adb_always"
    }
}
