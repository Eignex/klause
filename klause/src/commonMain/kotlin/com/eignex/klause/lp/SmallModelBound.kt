package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.ComparisonClause
import com.eignex.klause.factor.arithmetic.IntegralConstants
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
 * The theorem's inputs for this system — largest entry `a` and row count `m` — or null when some factor
 * is outside the admitted fragment.
 *
 * Separate from [smallModelBigIntBound] because the two questions have wildly different costs. Whether
 * the lane admits a model is one walk over its factors; the bound itself raises `m·a` to the `2m+1`, so
 * on a model with a few hundred thousand rows it is a multi-megabyte integer that takes minutes to form.
 * Routing only ever needed the first question.
 */
internal fun smallModelInputs(numIntVars: Int, factors: List<Factor>, intBounds: IntBounds): Pair<BigInteger, Int>? {
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
                val constants = f.integralConstants ?: return null
                observeRow(f.vars.size, constants, ::observe)
                if (!rows(2)) return null
            }

            is ReifiedLinear -> {
                observeRow(f.vars.size, f.constants, ::observe)
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
    if (numIntVars <= 0 || m == 0) return BigInteger.ONE to 0
    if (m > Int.MAX_VALUE - numIntVars || m > (Int.MAX_VALUE - 1) / 2) return null
    return a to m
}

/** Whether the General LIA lane admits this system at all — the cheap half of [smallModelBigIntBound]. */
internal fun admitsSmallModelBound(numIntVars: Int, factors: List<Factor>, intBounds: IntBounds): Boolean =
    smallModelInputs(numIntVars, factors, intBounds) != null

/**
 * Exact arbitrary-precision General LIA witness bound, or null when the system is outside the fragment.
 *
 * Keeps the theorem bound in [BigInteger] and includes each model-declared finite integer side as an
 * inequality row — necessary, because intersecting a theorem box with a declared side after computing it
 * can exclude every witness.
 *
 * Forms `n(ma)^(2m+1)`, so it is exponential in the row count by construction. Ask for it only when the
 * box is about to be searched, never to decide a route.
 */
internal fun smallModelBigIntBound(numIntVars: Int, factors: List<Factor>, intBounds: IntBounds): BigInteger? {
    val (a, m) = smallModelInputs(numIntVars, factors, intBounds) ?: return null
    if (m == 0) return BigInteger.ONE
    return BigInteger.fromInt(numIntVars + m) * (BigInteger.fromInt(m) * (a + BigInteger.ONE)).pow(2 * m + 1)
}

/** Feed a row's exact constants to [observe], whatever width they are stored at. */
private fun observeRow(terms: Int, constants: IntegralConstants, observe: (BigInteger) -> Unit) {
    for (i in 0 until terms) observe(constants.exactCoeff(i))
    observe(constants.exactBound)
}
