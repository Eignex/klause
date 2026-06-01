package com.eignex.klause.solver.factor

/**
 * Compress a raw violation residual into the graded [com.eignex.klause.solver.localsearch.LocalSearchFactor.violationDegree]
 * the LS cost sums. Two regimes:
 *
 *  - **`raw ≤ [SOFT]`** — returned verbatim, so the near-feasibility region (the last few
 *    units to satisfaction, where the gradient must be precise) keeps an exact, unit-resolution
 *    descent signal.
 *  - **`raw > [SOFT]`** — a logarithmic tail: `SOFT + ⌊log2(raw − SOFT + 1)⌋`. A residual of a
 *    thousand contributes ~25, not a thousand, so a handful of large-magnitude constraints can
 *    no longer dominate the global cost and starve the many small violations that feasibility
 *    actually needs. Without this, raw magnitudes regress previously-easy families (the search
 *    chases the biggest residual instead of clearing the most constraints).
 *
 * Monotone non-decreasing in [raw], and `0` iff `raw ≤ 0` — so `degree > 0 ⟺ violated` holds
 * and `deltaIf*` probes stay consistent (both sides apply the same compression).
 */
internal fun compressViolation(raw: Long): Int {
    if (raw <= 0L) return 0
    if (raw <= SOFT) return raw.toInt()
    var extra = raw - SOFT // ≥ 1
    var bits = 0
    while (extra > 0L) {
        extra = extra shr 1
        bits++
    } // ⌊log2(raw−SOFT)⌋ + 1
    return (SOFT + bits).toInt()
}

/** Residuals at or below this keep exact unit resolution; above, a log tail bounds domination. */
private const val SOFT: Long = 16L
