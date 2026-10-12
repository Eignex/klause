package com.eignex.klause.backtrack

import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Int128

/** A bound the objective cutoff proves on an integer variable: `varId ≤ hi`. */
internal class CutoffBound(val varId: Int, val hi: Long)

/*
 * Bounds open integer columns using the incumbent cutoff `Σ c·x + constant ≤ incumbent − 1`.
 * For `c_j > 0`, propagation gives `lo_j + ⌊(incumbent − 1 − L) / c_j⌋`, where `L` is the
 * objective minimum over declared domains. Every other term is at least its own minimum,
 * so this bound is sound without an LP relaxation.
 *
 * The cutoff retains only strictly better integral objectives. The caller must restrict it
 * to branch-and-bound optimization, never enumeration.
 *
 * `L` accumulates exactly in 128 bits. An inexact column minimum yields no bound. Columns
 * with an invented lower endpoint are skipped because the formula would bound the search box
 * rather than the source model.
 *
 * Crowder, Johnson, Padberg, "Solving Large-Scale Zero-One Linear Programming Problems",
 * Operations Research 31(5), 1983.
 */
internal fun objectiveCutoffBounds(
    problem: BakedProblem,
    objective: LinearObjective,
    incumbent: Long,
): List<CutoffBound> {
    val coefficients = objective.intCoefficientValues
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
        val d = problem.rootIntDomain(i)
        floor.addProduct(c, if (c > 0L) d.min else d.max)
    }
    for (b in 0 until minOf(problem.numBoolVars, objective.boolWeightValues.size)) {
        val w = objective.boolWeightValues[b]
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
        val d = problem.rootIntDomain(i)
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
