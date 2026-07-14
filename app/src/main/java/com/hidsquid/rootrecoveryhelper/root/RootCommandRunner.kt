package com.hidsquid.rootrecoveryhelper.root

interface RootCommandRunner {
    suspend fun execute(command: String): CommandResult
}
