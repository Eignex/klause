package com.eignex.klause.cli

internal expect fun <T> captureCliCpu(operation: () -> T): T
