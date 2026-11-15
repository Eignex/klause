package com.eignex.klause.util

import kotlin.time.TimeSource

private val origin = TimeSource.Monotonic.markNow()

internal actual fun monotonicNanos(): Long = origin.elapsedNow().inWholeNanoseconds
