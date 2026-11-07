package com.eignex.klause.cli

import kotlin.time.TimeSource

private val timeOrigin = TimeSource.Monotonic.markNow()

internal actual fun nowMillis(): Long = timeOrigin.elapsedNow().inWholeMilliseconds
