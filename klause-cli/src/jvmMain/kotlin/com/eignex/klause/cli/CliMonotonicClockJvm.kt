package com.eignex.klause.cli

private val timeOriginNanos = System.nanoTime()

internal actual fun nowMillis(): Long = elapsedMonotonicMillis(timeOriginNanos, System.nanoTime())

internal fun elapsedMonotonicMillis(originNanos: Long, readingNanos: Long): Long =
    (readingNanos - originNanos) / 1_000_000L
