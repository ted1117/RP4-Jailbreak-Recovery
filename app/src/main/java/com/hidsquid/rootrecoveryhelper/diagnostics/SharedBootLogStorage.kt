package com.hidsquid.rootrecoveryhelper.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import java.io.File
import java.io.IOException

object SharedBootLogStorage {
    val requiredPermissions: Array<String>
        get() = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )

    fun hasRequiredPermissions(context: Context): Boolean =
        requiredPermissions.all { permission ->
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }

    fun requireDirectory(context: Context): File {
        if (!hasRequiredPermissions(context)) {
            throw SecurityException("Shared storage permission is not granted")
        }
        val state = Environment.getExternalStorageState()
        if (state != Environment.MEDIA_MOUNTED) {
            throw IOException("Shared storage is not writable: state=$state")
        }
        return directory()
    }

    @Suppress("DEPRECATION")
    fun directory(): File = File(
        Environment.getExternalStorageDirectory(),
        "$ROOT_DIRECTORY_NAME/${BootLogStore.DIRECTORY_NAME}",
    )

    @Suppress("DEPRECATION")
    fun diagnosticSummary(context: Context): String = buildString {
        append("path=")
        append(directory().path)
        append(", state=")
        append(Environment.getExternalStorageState())
        append(", permission=")
        append(hasRequiredPermissions(context))
        append(", legacy=")
        append(Environment.isExternalStorageLegacy())
    }

    private const val ROOT_DIRECTORY_NAME = "RootRecoveryHelper"
}
