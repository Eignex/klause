package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.LinearOp
import com.eignex.klause.lp.LinearRow
import com.eignex.klause.lp.Term
import com.eignex.klause.solver.LongConstList
import com.eignex.klause.solver.RealConsts
import com.eignex.klause.solver.WideConsts
import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * The constants of a [Linear] row — its coefficients and right-hand side — at the width that holds them
 * exactly.
 *
 * A row is one of three shapes, and each shape's constants have their own arithmetic. A consumer obtains
 * the shape it can reason in ([Linear.integerConstants], [Linear.wideConstants], [Linear.realConstants])
 * and reads exact values from it; a consumer that asks for a shape the row does not have gets nothing
 * rather than a rounded or saturated stand-in.
 */
sealed interface LinearConstants

/**
 * Constants that are integers, whether or not they fit 64 bits. A consumer that reasons in exact
 * arbitrary precision — the open LIA theories, the wide propagators — reads every row of this shape
 * uniformly; one that reasons in 64 bits narrows further to [IntegerConstants].
 */
sealed interface IntegralConstants : LinearConstants {
    /** The exact coefficient of term [k]. */
    fun exactCoeff(k: Int): BigInteger

    /** The exact right-hand side. */
    val exactBound: BigInteger
}

/**
 * A plain 64-bit integer row: the shape presolve, the cut separators, the LP relaxation and local search
 * reason over. It is itself the row's [LinearRow] view, so a consumer that narrows to it needs no second
 * lookup for the terms.
 */
class IntegerConstants(
    private val vars: IntArray,
    /** Coefficients, index-aligned with the row's variables. */
    val coefficients: LongConstList,
    override val relation: LinearOp,
    override val bound: Long,
) : IntegralConstants,
    LinearRow {
    override val size: Int get() = vars.size

    override fun ref(k: Int): Int = Term.ofIntVar(vars[k])

    override fun coeff(k: Int): Long = coefficients.at(k)

    override val isIntegerOnly: Boolean get() = true

    /** Coefficients as a [LongArray], materialised on demand; prefer [coeff] for indexed reads. */
    val coeffs: LongArray get() = coefficients.toLongArray()

    /** Largest `|coefficient|`, 0 when the row has no terms — read by the presolve overflow gates. */
    val maxAbsCoeff: Long get() = coefficients.maxAbs

    override fun exactCoeff(k: Int): BigInteger = BigInteger.fromLong(coefficients.at(k))

    override val exactBound: BigInteger get() = BigInteger.fromLong(bound)
}

/**
 * A row whose coefficients or bound exceed the 64-bit range, carried exactly. It propagates through
 * [WideLinearPropagator] and enters the LP only as a directionally-rounded outer relaxation, so no
 * 64-bit consumer can read these values by accident.
 */
class WideConstants(
    /** Coefficients, index-aligned with the row's variables. */
    val coefficients: WideConsts,
    /** The exact right-hand side. */
    val bound: BigInteger,
) : IntegralConstants {
    override fun exactCoeff(k: Int): BigInteger = coefficients.at(k)

    override val exactBound: BigInteger get() = bound
}

/**
 * A row carrying a continuous term, or fractional constants on its integer terms. It is LP-only: it does
 * not propagate in CP, and its feasibility is settled by the relaxation and the exact leaf.
 */
class RealConstants(
    /** Double coefficient of each integer term, index-aligned with the row's integer variables. */
    val intCoefficients: RealConsts,
    /** Double coefficient of each real term, index-aligned with the row's real variables. */
    val realCoefficients: RealConsts,
    /** The exact right-hand side. */
    val bound: Double,
    /**
     * Strict inequality (`Σ … < bound` after the `≤` canonicalisation). The float relaxation treats it as
     * non-strict — a sound relaxation — and the exact deciders enforce the strictness.
     */
    val strict: Boolean,
) : LinearConstants
