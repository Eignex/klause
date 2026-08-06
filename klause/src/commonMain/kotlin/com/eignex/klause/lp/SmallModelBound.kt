package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.solver.Factor
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log2

/**
 * Small-model magnitude bound for a pure-integer linear model: when [factors] are boolean
 * structure ([Clause]) over integer-linear rows ([Linear] / [ReifiedLinear]), a satisfiable model
 * has an integer witness with every coordinate's magnitude within the returned bound. This is the
 * small-model property of linear integer arithmetic — a satisfying assignment activates a
 * conjunction of at most all rows or their integer negations, and a feasible integer system of m
 * inequality rows over n variables with largest coefficient/bound magnitude a has a solution
 * bounded by (n + m) * (m * (a + 1))^(2m + 1). Restricting the domains to the bound therefore
 * preserves equisatisfiability: `unsat` within it is `unsat` outright.
 *
 * Evaluated in log2 space and returned rounded up to a power of two. Returns null — no sound
 * finite box derivable — when the bound exceeds 2^62 (the ceiling for overflow-guarded Long
 * search) or when a factor outside the covered fragment appears. The bound is exponentially
 * conservative, so only small systems fit; callers fall back to a lossy clamp.
 */
fun smallModelIntBound(numIntVars: Int, factors: List<Factor>): Long? {
    var a = 1.0
    var m = 0.0
    for (f in factors) {
        when (f) {
            is Clause -> Unit

            is Linear -> {
                if (!f.isIntegerCore) return null
                a = maxOf(a, f.maxAbsCoeff.toDouble())
                a = maxOf(a, abs(f.bound.toDouble()))
                m += 2.0
            }

            is ReifiedLinear -> {
                for (c in f.coeffs) a = maxOf(a, abs(c.toDouble()))
                a = maxOf(a, abs(f.bound.toDouble()))
                m += 2.0
            }

            else -> return null
        }
    }
    if (numIntVars <= 0 || m == 0.0) return 1L
    val log2B = log2(numIntVars + m) + (2.0 * m + 1.0) * log2(m * (a + 1.0))
    if (!log2B.isFinite() || log2B > 62.0) return null
    return 1L shl ceil(log2B).toInt().coerceIn(1, 62)
}
