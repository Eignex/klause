package com.eignex.klause.lowering

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit

// A shared presence literal gates the global on the true branch. The false branch witnesses equal values
// at two ordered positions, preserving `d ↔ all_different(vars)` without a quadratic pairwise encoding.
internal fun CnfLowering.reifyAllDifferentWitness(
    vars: IntArray,
    domainMin: Long,
    domainSize: Int,
    freshInt: (Long, Long) -> Int,
): Int {
    val n = vars.size
    require(n >= 2) { "reified all_different needs at least two terms" }
    val d = newBool()
    val dLit = Lit.make(d, positive = true)
    val lastPos = (n - 1).toLong()
    val p = freshInt(0L, lastPos)
    val q = freshInt(0L, lastPos)
    val witness = freshInt(domainMin, domainMin + (domainSize - 1))
    val cells = LongArray(n) { vars[it].toLong() }
    factors.add(AllDifferent(vars, domainMin, domainSize, presents = IntArray(n) { dLit }))
    factors.add(Element(p, witness, cells, arrIsVars = true, indexOffset = 0))
    factors.add(Element(q, witness, cells, arrIsVars = true, indexOffset = 0))
    factors.add(Linear(intArrayOf(1, -1), intArrayOf(p, q), LinearOp.LE, 0))
    factors.add(ReifiedLinear(d, intArrayOf(1, -1), intArrayOf(q, p), LinearOp.LE, 0))
    return dLit
}

// Below four terms, pairwise reification has a smaller search footprint.
internal const val ALL_DIFFERENT_WITNESS_MIN_ARITY: Int = 4

// The global's indexed value window must fit an Int; callers retain the pairwise encoding otherwise.
internal fun allDifferentWindowSize(min: Long, max: Long): Int? {
    if (min > max) return null
    if (min < 0L && max > Long.MAX_VALUE + min) return null
    val span = max - min
    if (span > Int.MAX_VALUE - 1L) return null
    return (span + 1L).toInt()
}

/** The value window an [AllDifferent] indexes: its first value and how many it covers. */
internal class AllDifferentWindow(
    /** Lowest value any member can take. */
    val min: Long,
    /** Count of values the global indexes, starting at [min]. */
    val size: Int,
)

/**
 * The window [AllDifferent] would index over [vars], or null when the global cannot hold them and the
 * caller must post the pairwise decomposition instead.
 *
 * [AllDifferent] filters by indexing a window of values, so it needs every member bounded on both sides
 * and a window an `Int` can address. Neither is a property of the caller, which is why this is asked
 * here rather than decided again in each front-end — one that forgot the openness half would hand the
 * plan a global over a column no theory can hold and no CP domain can index, and the model is refused
 * whole rather than in the part that is genuinely unsupported.
 *
 * Declining costs nothing the global was providing: pairwise `!=` is `x < y or x > y`, two difference
 * rows the theories own over unbounded integers, and Hall intervals deduce nothing without a finite
 * value set to draw them from.
 *
 * [lower] and [upper] report a member's bounds, `null` for a side with no bound. Fewer than two members
 * is vacuous, and reported as no window so the caller's decomposition posts nothing.
 */
internal fun allDifferentWindow(vars: IntArray, lower: (Int) -> Long?, upper: (Int) -> Long?): AllDifferentWindow? {
    if (vars.size < 2) return null
    var min = Long.MAX_VALUE
    var max = Long.MIN_VALUE
    for (v in vars) {
        val lo = lower(v) ?: return null
        val hi = upper(v) ?: return null
        if (lo < min) min = lo
        if (hi > max) max = hi
    }
    val size = allDifferentWindowSize(min, max) ?: return null
    return AllDifferentWindow(min, size)
}
