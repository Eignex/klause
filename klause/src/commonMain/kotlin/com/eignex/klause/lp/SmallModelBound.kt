package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.ComparisonClause
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntBounds
import com.ionspin.kotlin.bignum.integer.BigInteger

/*
 * Papadimitriou, *On the Complexity of Integer Programming* (JACM 1981): a feasible integer system of
 * `m` rows over `n` variables with largest entry `a` has a solution inside `n(ma)^(2m+1)`. Exponential
 * in the row count, which is why it fits only small systems and why the structural transformations are
 * preferred where they apply.
 */
/**
 * Exact arbitrary-precision version of the General LIA witness bound.
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
