package com.hidsquid.rootrecoveryhelper.storage

import android.content.Context

class RecoveryPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    var pendingLsposedSetup: Boolean
        get() = preferences.getBoolean(KEY_PENDING_LSPOSED_SETUP, false)
        set(value) {
            preferences.edit().putBoolean(KEY_PENDING_LSPOSED_SETUP, value).apply()
        }

    fun commitPendingLsposedSetup(value: Boolean): Boolean =
        preferences.edit().putBoolean(KEY_PENDING_LSPOSED_SETUP, value).commit()

    companion object {
        const val KEY_PENDING_LSPOSED_SETUP = "pending_lsposed_setup"

        private const val PREFERENCES_NAME = "root_recovery"
    }
}
