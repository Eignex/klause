package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.ComparisonClause
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntBounds
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log2

/**
 * Papadimitriou, *On the Complexity of Integer Programming* (JACM 1981): a feasible integer system of
 * `m` rows over `n` variables with largest entry `a` has a solution inside `n(ma)^(2m+1)`. Exponential
 * in the row count, which is why it fits only small systems and why the structural transformations are
 * preferred where they apply.
 *
 * Small-model magnitude bound for a pure-integer linear model: when [factors] are boolean
 * structure ([Clause]) over integer-linear rows ([Linear] / [ReifiedLinear]) and unary comparison
 * disjunctions ([ComparisonClause]), a satisfiable model
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
                a = maxOf(a, f.maxAbsCoeff.toDouble())
                a = maxOf(a, abs(f.bound.toDouble()))
                m += 2.0
            }

            is ComparisonClause -> {
                // A satisfying assignment selects one unary comparison from the disjunction; its
                // constant joins the active conjunction used by the small-model theorem. Count two
                // rows, matching [Linear]'s conservative equality allowance.
                for (constant in f.consts) a = maxOf(a, abs(constant.toDouble()))
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

/**
 * Exact version of [smallModelIntBound] for the General LIA path.
 *
 * Unlike the finite-CP helper, this keeps the theorem bound in [BigInteger] and includes each
 * model-declared finite integer side as an inequality row. The latter is necessary: intersecting a
 * theorem box with a declared side after computing it can exclude every witness.
 */
internal fun smallModelBigIntBound(numIntVars: Int, factors: List<Factor>, intBounds: IntBounds): BigInteger? {
    var a = BigInteger.ONE
    var m = 0

    fun observe(value: BigInteger) {
        val magnitude = if (value < BigInteger.ZERO) -value else value
        if (magnitude > a) a = magnitude
    }

    fun rows(count: Int): Boolean {
        if (m > Int.MAX_VALUE - count) return false
        m += count
        return true
    }

    for (f in factors) {
        when (f) {
            is Clause -> Unit

            is Linear -> {
                if (f.hasReals) return null
                val coeffs = f.wideCoeffs
                if (coeffs == null) {
                    for (i in f.vars.indices) observe(BigInteger.fromLong(f.coeff(i)))
                    observe(BigInteger.fromLong(f.bound))
                } else {
                    for (coeff in coeffs) observe(coeff)
                    observe(requireNotNull(f.wideBound))
                }
                if (!rows(2)) return null
            }

            is ReifiedLinear -> {
                val coeffs = f.wideCoeffs
                if (coeffs == null) {
                    for (i in f.vars.indices) observe(BigInteger.fromLong(f.coeff(i)))
                    observe(BigInteger.fromLong(f.bound))
                } else {
                    for (coeff in coeffs) observe(coeff)
                    observe(requireNotNull(f.wideBound))
                }
                if (!rows(2)) return null
            }

            is ComparisonClause -> {
                for (constant in f.consts) observe(BigInteger.fromLong(constant))
                if (!rows(2)) return null
            }

            else -> return null
        }
    }
    for (v in 0 until intBounds.size) {
        if (intBounds.hasLower(v)) {
            observe(BigInteger.fromLong(intBounds.lower(v)))
            if (!rows(1)) return null
        }
        if (intBounds.hasUpper(v)) {
            observe(BigInteger.fromLong(intBounds.upper(v)))
            if (!rows(1)) return null
        }
    }
    if (numIntVars <= 0 || m == 0) return BigInteger.ONE
    if (m > Int.MAX_VALUE - numIntVars || m > (Int.MAX_VALUE - 1) / 2) return null
    val rowsBig = BigInteger.fromInt(m)
    return BigInteger.fromInt(numIntVars + m) * (rowsBig * (a + BigInteger.ONE)).pow(2 * m + 1)
}
