package com.eignex.klause.solver.factor

// 16: balanced between raw (≈ ∞) and pure-log (≈ 0) compression; see compressViolation.
internal const val DEFAULT_VIOLATION_SOFT_CAP: Int = 16

// raw ≤ softCap: returned verbatim (exact gradient near feasibility).
// raw > softCap: softCap + ⌊log2(raw − softCap + 1)⌋ — log tail prevents a handful of
// large-magnitude constraints from dominating the cost and starving the many small violations.
internal fun compressViolation(raw: Long, softCap: Int): Int {
    if (raw <= 0L) return 0
    val cap = softCap.toLong()
    if (raw <= cap) return raw.toInt()
    val extra = raw - cap // ≥ 1
    val bits = Long.SIZE_BITS - extra.countLeadingZeroBits() // bit length of extra = ⌊log2(extra)⌋ + 1
    return (cap + bits).toInt()
}
