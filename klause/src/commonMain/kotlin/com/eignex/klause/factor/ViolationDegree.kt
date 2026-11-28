package com.eignex.klause.factor

internal const val DEFAULT_VIOLATION_SOFT_CAP: Int = 16

// Exact up to softCap, then a log tail so a few large violations cannot drown out many small ones.
internal fun compressViolation(raw: Long, softCap: Int): Int {
    if (raw <= 0L) return 0
    val cap = softCap.toLong()
    if (raw <= cap) return raw.toInt()
    val extra = raw - cap // ≥ 1
    val bits = Long.SIZE_BITS - extra.countLeadingZeroBits() // bit length of extra = ⌊log2(extra)⌋ + 1
    return (cap + bits).toInt()
}
