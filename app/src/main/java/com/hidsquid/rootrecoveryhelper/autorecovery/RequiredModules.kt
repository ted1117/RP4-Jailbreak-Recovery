package com.hidsquid.rootrecoveryhelper.autorecovery

data class RequiredModule(
    val id: String,
    val directory: String,
) {
    val disableFile: String
        get() = "$directory/disable"
}

object RequiredModules {
    val all: List<RequiredModule> = listOf(
        RequiredModule(
            id = "magisk-einklauncher",
            directory = "/data/adb/modules/magisk-einklauncher",
        ),
        RequiredModule(
            id = "magisk-insecure-adb",
            directory = "/data/adb/modules/magisk-insecure-adb",
        ),
        RequiredModule(
            id = "magisk-rp4posed",
            directory = "/data/adb/modules/magisk-rp4posed",
        ),
        RequiredModule(
            id = "refreshpaper",
            directory = "/data/adb/modules/refreshpaper",
        ),
        RequiredModule(
            id = "rp400-epdd-patch",
            directory = "/data/adb/modules/rp400-epdd-patch",
        ),
        RequiredModule(
            id = "zygisk_lsposed",
            directory = "/data/adb/modules/zygisk_lsposed",
        ),
    )
}
