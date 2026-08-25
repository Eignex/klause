package com.eignex.klause.factor.arithmetic.internals

internal fun successorOrNull(value: Long): Long? = if (value == Long.MAX_VALUE) null else value + 1

internal fun predecessorOrNull(value: Long): Long? = if (value == Long.MIN_VALUE) null else value - 1
