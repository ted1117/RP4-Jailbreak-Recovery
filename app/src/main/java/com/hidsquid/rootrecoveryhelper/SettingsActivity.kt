package com.hidsquid.rootrecoveryhelper

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Switch
import android.widget.Toolbar
import android.widget.Toast
import com.hidsquid.rootrecoveryhelper.diagnostics.BootDiagnostics
import com.hidsquid.rootrecoveryhelper.diagnostics.SharedBootLogStorage
import com.hidsquid.rootrecoveryhelper.storage.DiagnosticsSettings

class SettingsActivity : Activity() {
    private val settings by lazy { DiagnosticsSettings(applicationContext) }
    private lateinit var saveRecentBootLogsSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<Toolbar>(R.id.topAppBar).setNavigationOnClickListener {
            finish()
        }

        findViewById<Switch>(R.id.showBootDiagnosticsSwitch).apply {
            isChecked = settings.showBootDiagnostics
            setOnCheckedChangeListener { _, isChecked ->
                settings.showBootDiagnostics = isChecked
            }
        }
        saveRecentBootLogsSwitch = findViewById<Switch>(
            R.id.saveRecentBootLogsSwitch,
        ).apply {
            isChecked = settings.saveRecentBootLogs
            setOnCheckedChangeListener { _, isChecked ->
                updateBootLogStorage(isChecked)
            }
        }
        findViewById<Switch>(R.id.forceAdbAlwaysSwitch).apply {
            isChecked = settings.forceAdbAlways
            setOnCheckedChangeListener { _, isChecked ->
                settings.forceAdbAlways = isChecked
            }
        }
    }

    private fun updateBootLogStorage(enabled: Boolean) {
        if (!enabled) {
            settings.saveRecentBootLogs = false
            return
        }
        if (SharedBootLogStorage.hasRequiredPermissions(this)) {
            settings.saveRecentBootLogs = true
            prepareSharedLogStorage()
            return
        }

        settings.saveRecentBootLogs = false
        requestPermissions(
            SharedBootLogStorage.requiredPermissions,
            BOOT_LOG_STORAGE_PERMISSION_REQUEST_CODE,
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != BOOT_LOG_STORAGE_PERMISSION_REQUEST_CODE) {
            return
        }

        val granted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED } &&
            SharedBootLogStorage.hasRequiredPermissions(this)
        settings.saveRecentBootLogs = granted
        saveRecentBootLogsSwitch.isChecked = granted
        if (granted) {
            prepareSharedLogStorage()
        } else {
            Toast.makeText(
                this,
                R.string.boot_log_storage_permission_denied,
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun prepareSharedLogStorage() {
        if (BootDiagnostics(applicationContext).prepareSharedLogStorage()) {
            return
        }
        Toast.makeText(
            this,
            R.string.boot_log_storage_unavailable,
            Toast.LENGTH_LONG,
        ).show()
    }

    companion object {
        private const val BOOT_LOG_STORAGE_PERMISSION_REQUEST_CODE = 7102
    }
}
