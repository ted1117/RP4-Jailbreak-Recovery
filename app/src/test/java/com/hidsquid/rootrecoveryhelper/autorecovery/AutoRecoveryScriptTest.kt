package com.hidsquid.rootrecoveryhelper.autorecovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoRecoveryScriptTest {
    @Test
    fun `required module allowlist contains only the six fixed modules`() {
        assertEquals(
            listOf(
                "magisk-einklauncher",
                "magisk-insecure-adb",
                "magisk-rp4posed",
                "refreshpaper",
                "rp400-epdd-patch",
                "zygisk_lsposed",
            ),
            RequiredModules.all.map(RequiredModule::id),
        )
    }

    @Test
    fun `script addresses every allowlisted module without wildcard deletion`() {
        val script = AutoRecoveryScript.build()
        val moduleCommands = script.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("process_module \"") }
            .toList()

        assertEquals(RequiredModules.all.size, moduleCommands.size)
        RequiredModules.all.forEach { module ->
            assertTrue(
                script.contains("process_module \"${module.id}\" \"${module.directory}\""),
            )
        }
        assertFalse(script.contains("/data/adb/modules/*"))
        assertTrue(script.contains("/system/bin/rm -f \"${'$'}disable_file\""))
    }

    @Test
    fun `script disables secure ADB before enabling and starting the daemon`() {
        val script = AutoRecoveryScript.build()
        val securityIndex = script.indexOf("resetprop ro.adb.secure 0")
        val settingIndex = script.indexOf("settings put global adb_enabled 1")
        val daemonIndex = script.indexOf("setprop ctl.start adbd")

        assertTrue(securityIndex >= 0)
        assertTrue(settingIndex > securityIndex)
        assertTrue(daemonIndex > settingIndex)
        assertTrue(script.contains("getprop ro.adb.secure"))
    }

    @Test
    fun `script enables and verifies Zygisk after processing all modules`() {
        val script = AutoRecoveryScript.build()
        val lastModule = RequiredModules.all.last()
        val lastModuleIndex = script.indexOf(
            "process_module \"${lastModule.id}\" \"${lastModule.directory}\"",
        )
        val zygiskWriteIndex = script.indexOf(
            "INSERT OR REPLACE INTO settings (key, value) VALUES ('zygisk', 1);",
        )

        assertTrue(lastModuleIndex >= 0)
        assertTrue(zygiskWriteIndex > lastModuleIndex)
        assertTrue(script.contains("RRH|ZYGISK|ENABLING"))
        assertTrue(script.contains("RRH|ZYGISK|ENABLED"))
        assertTrue(script.contains("read_zygisk_state"))
    }

    @Test
    fun `script reboots after module or Zygisk recovery and successful verification`() {
        val script = AutoRecoveryScript.build()

        assertTrue(script.contains("""deleted_count" -gt 0"""))
        assertTrue(script.contains("""zygisk_changed" -eq 1"""))
        assertTrue(script.contains("""recovery_changed" -eq 1"""))
        assertTrue(script.contains("""failure_count" -eq 0"""))
        assertTrue(script.contains("""zygisk_ready" -eq 1"""))
        assertTrue(script.contains("seconds=10"))
        assertTrue(script.contains("while [ \"${'$'}seconds\" -gt 0 ]"))
        assertTrue(script.contains("RRH|REBOOT|REQUESTED"))
    }
}
