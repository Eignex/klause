package com.eignex.klause.backtrack

import com.eignex.klause.lp.Int128
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective

/** A bound the objective cutoff proves on an integer variable: `varId ≤ hi`. */
internal class CutoffBound(val varId: Int, val hi: Long)

/**
 * Bounds the incumbent puts on the integer columns nothing else bounds.
 *
 * A column open on the high side is one no row caps: raising it only ever helps satisfy the rows it
 * appears in, so bound tightening leaves the side open and the front-end closes it at an invented
 * search box ([Problem.intBounds] records which sides those are). Cost still caps such a column — but
 * only against an incumbent, which exists neither at load nor in presolve. Once the search holds one,
 * `Σ c·x + constant ≤ incumbent − 1` is a row like any other, and propagating it bounds column `j` with
 * `c_j > 0` at `lo_j + ⌊(incumbent − 1 − L) / c_j⌋`, where `L` is the objective's minimum over the
 * declared domains. Sound because every other term is at least its own minimum, which `L` already
 * charges. This is objective bound propagation, the LP-free half of reduced-cost fixing (Crowder,
 * Johnson, Padberg, "Solving Large-Scale Zero-One Linear Programming Problems", Operations Research
 * 31(5), 1983); an LP dual bound would be tighter than `L`, but the models that carry open columns are
 * exactly the ones whose root LP does not converge inside a search slice, and the reduced-cost fixing
 * the node LP already runs covers the models where it does.
 *
 * [incumbent] is the objective of a solution already in hand, so only a strictly better one — at most
 * `incumbent − 1`, the objective being integral — has to survive. Cutting the rest is what
 * branch-and-bound proves and what enumeration must not do; the caller owns that gate.
 *
 * `L` accumulates exactly in 128 bits, so a coefficient against the invented box wraps nothing; a
 * column whose own minimum does not evaluate exactly yields no bound rather than a guess. A column the
 * box also invented a *lower* side for is skipped: its `lo_j` is the box, so what the cutoff would say
 * about it is a statement about the box rather than about the model.
 */
internal fun objectiveCutoffBounds(problem: Problem, objective: LinearObjective, incumbent: Long): List<CutoffBound> {
    val coefficients = objective.intCoefficients
    val n = minOf(problem.numIntVars, coefficients.size)
    if ((0 until n).none {
            coefficients[it] > 0L && problem.intBounds.isOpenUpper(
                it,
            ) && !problem.intBounds.isOpenLower(it)
        }
    ) {
        return emptyList()
    }
    // L = the objective's minimum over the declared domains: each term at the endpoint its coefficient's
    // sign selects, each free Boolean at whichever polarity costs least.
    val floor = Int128()
    floor.addLong(objective.constant)
    for (i in 0 until n) {
        val c = coefficients[i]
        if (c == 0L) continue
        val d = problem.intDomains[i]
        floor.addProduct(c, if (c > 0L) d.min else d.max)
    }
    for (b in 0 until minOf(problem.numBoolVars, objective.boolWeights.size)) {
        val w = objective.boolWeights[b]
        if (w < 0L) floor.addLong(w)
    }
    if (floor.overflow) return emptyList()
    // slack = (incumbent − 1) − L: what a strictly better solution may still spend on raising a column
    // above its minimum. A negative slack means nothing beats the incumbent at all — a verdict for the
    // node bound to reach, not a domain reduction.
    val slack = Int128()
    slack.addLong(incumbent)
    slack.addLong(-1L)
    slack.subtract(floor)
    if (!slack.isNonNegative()) return emptyList()
    val out = ArrayList<CutoffBound>()
    for (i in 0 until n) {
        val c = coefficients[i]
        if (c <= 0L || !problem.intBounds.isOpenUpper(i) || problem.intBounds.isOpenLower(i)) continue
        val d = problem.intDomains[i]
        val steps = slack.floorDivPositive(c) ?: continue
        val hi = addOrNull(d.min, steps) ?: continue
        if (hi < d.max) out.add(CutoffBound(i, hi))
    }
    return out
}

/** `a + b`, or null when it would wrap — a wrapped bound is one the arithmetic never proved. */
private fun addOrNull(a: Long, b: Long): Long? {
    val sum = a + b
    return if (((a xor sum) and (b xor sum)) < 0L) null else sum
}
