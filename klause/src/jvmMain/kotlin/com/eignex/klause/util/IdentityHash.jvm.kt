package com.eignex.klause.util

internal actual fun identityHash(value: Any): Int = System.identityHashCode(value)
