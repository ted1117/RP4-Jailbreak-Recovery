package com.hidsquid.rootrecoveryhelper.diagnostics

internal fun Throwable.toDiagnosticDetail(): String {
    val type = javaClass.simpleName.ifBlank { javaClass.name }
    val message = message.orEmpty()
    return if (message.isBlank()) type else "$type: $message"
}
