package com.eignex.klause.cli

internal actual fun <T> captureCliCpu(operation: () -> T): T = operation()
