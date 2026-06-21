package com.eignex.klause.solver.factor

/** Default soft cap for `compressViolation` — the value
 *  [com.eignex.klause.solver.localsearch.LocalSearchParams.violationSoftCap] defaults to. Residuals
 *  at or below it keep exact unit resolution; above, a log tail bounds domination. 16 is a balanced
 *  middle (raw ≈ ∞, pure-log ≈ 0). */
internal const val DEFAULT_VIOLATION_SOFT_CAP: Int = 16

/**
 * Compress a raw violation residual into the graded [com.eignex.klause.solver.Invariant.violationDegree]
 * the LS cost sums. [softCap] (per-solve, from
 * [com.eignex.klause.solver.localsearch.LocalSearchParams.violationSoftCap] via
 * [com.eignex.klause.solver.localsearch.LocalSearchState.violationSoftCap]) splits two regimes:
 *
 *  - **`raw ≤ softCap`** — returned verbatim, so the near-feasibility region (the last few
 *    units to satisfaction, where the gradient must be precise) keeps an exact, unit-resolution
 *    descent signal.
 *  - **`raw > softCap`** — a logarithmic tail: `softCap + ⌊log2(raw − softCap + 1)⌋`. A residual
 *    of a thousand contributes ~25, not a thousand, so a handful of large-magnitude constraints
 *    can no longer dominate the global cost and starve the many small violations that feasibility
 *    actually needs. Without this, raw magnitudes regress previously-easy families (the search
 *    chases the biggest residual instead of clearing the most constraints).
 *
 * `softCap == 0` collapses to a pure log scale; a very large `softCap` approaches raw magnitudes.
 * Monotone non-decreasing in [raw], and `0` iff `raw ≤ 0` — so `degree > 0 ⟺ violated` holds
 * and `deltaIf*` probes stay consistent (both sides apply the same compression with the same cap).
 */
internal fun compressViolation(raw: Long, softCap: Int): Int {
    if (raw <= 0L) return 0
    val cap = softCap.toLong()
    if (raw <= cap) return raw.toInt()
    val extra = raw - cap // ≥ 1
    val bits = Long.SIZE_BITS - extra.countLeadingZeroBits() // bit length of extra = ⌊log2(extra)⌋ + 1
    return (cap + bits).toInt()
}
