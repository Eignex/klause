package com.eignex.klause.util

internal const val CONSTRUCTION_BATCH_SIZE: Int = 256

internal suspend fun SequenceScope<Unit>.constructionIntArray(size: Int, value: (Int) -> Int): IntArray {
    val out = IntArray(size)
    yield(Unit)
    for (i in out.indices) {
        out[i] = value(i)
        if ((i + 1) % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
    }
    return out
}

internal suspend fun SequenceScope<Unit>.constructionLongArray(size: Int, value: (Int) -> Long): LongArray {
    val out = LongArray(size)
    yield(Unit)
    for (i in out.indices) {
        out[i] = value(i)
        if ((i + 1) % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
    }
    return out
}
