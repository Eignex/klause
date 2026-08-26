package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.LinearOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.util.EmptyDoubleArray
import com.eignex.klause.util.EmptyIntArray
import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * One linear row as a factor states it, read once and interpreted by whichever lane consumes it.
 *
 * A relaxation and an exact theory disagree about what to do with a row: the first weakens a conditional
 * one through a big-M and rounds a wide one outward, the second reads the Boolean assignment and takes
 * the row or its complement, exactly. They agree on what the row *is* — its terms, coefficients, operator
 * and bound — and this is that agreement, so a factor is destructured once rather than once per lane.
 *
 * The width is part of the statement rather than a detail: a row whose constants leave 64 bits cannot be
 * narrowed without either weakening or excluding, and which of those is acceptable is the consumer's
 * decision, not the factor's.
 */
sealed interface FactorRow {

    /** Integer column ids the row's discrete terms are over. */
    val intVars: IntArray

    /** LP-only continuous column ids the row's continuous terms are over; empty for a discrete row. */
    val realVars: IntArray

    /** Comparison the row states. [LinearOp.NE] is a disequality the consumer must direct. */
    val op: LinearOp

    /** Whether the comparison is strict, which only a row over continuous columns can be. */
    val strict: Boolean

    /** Boolean whose truth activates the row, or [ALWAYS] when the row holds unconditionally. */
    val activator: Int

    /** A row whose constants fit the double view both lanes carry. */
    class Doubles(
        override val intVars: IntArray,
        /** Coefficient of each integer term, index-aligned with [intVars]. */
        val intCoeffs: DoubleArray,
        override val realVars: IntArray,
        /** Coefficient of each continuous term, index-aligned with [realVars]. */
        val realCoeffs: DoubleArray,
        override val op: LinearOp,
        /** Right-hand side. */
        val bound: Double,
        override val strict: Boolean,
        override val activator: Int,
    ) : FactorRow

    /** A row whose constants exceed 64 bits and are carried exactly. */
    class Wide(
        override val intVars: IntArray,
        /** Coefficient of each integer term, index-aligned with [intVars]. */
        val coefficients: Array<BigInteger>,
        override val op: LinearOp,
        /** Right-hand side. */
        val bound: BigInteger,
        override val activator: Int,
    ) : FactorRow {
        override val realVars: IntArray get() = EmptyIntArray
        override val strict: Boolean get() = false
    }

    /** Sentinels for [activator]. */
    companion object {
        /** [activator] of a row no Boolean gates. */
        const val ALWAYS = -1
    }
}

/**
 * The linear row this factor states, or null when it states none a linear lane reads.
 *
 * A factor outside the linear shapes — a global, a table, a clause — returns null rather than an empty
 * row, because "states no linear row" and "states the empty row" are different claims and only the first
 * is true of them.
 */
fun Factor.linearRow(): FactorRow? = when (this) {
    is Linear -> when (val c = constants) {
        is WideConstants -> FactorRow.Wide(
            vars,
            c.coefficients.toTypedArray(),
            op,
            c.bound,
            FactorRow.ALWAYS,
        )

        is IntegerConstants -> FactorRow.Doubles(
            vars,
            DoubleArray(vars.size) { c.coeff(it).toDouble() },
            EmptyIntArray,
            EmptyDoubleArray,
            op,
            c.bound.toDouble(),
            strict = false,
            activator = FactorRow.ALWAYS,
        )

        is RealConstants -> FactorRow.Doubles(
            vars,
            c.intCoefficients.toDoubleArray(),
            realVars,
            c.realCoefficients.toDoubleArray(),
            op,
            c.bound,
            c.strict,
            FactorRow.ALWAYS,
        )
    }

    is ReifiedLinear -> when (val c = constants) {
        is WideConstants -> FactorRow.Wide(
            vars,
            c.coefficients.toTypedArray(),
            op,
            c.bound,
            auxBoolVar,
        )

        is IntegerConstants -> FactorRow.Doubles(
            vars,
            DoubleArray(vars.size) { c.coeff(it).toDouble() },
            EmptyIntArray,
            EmptyDoubleArray,
            op,
            c.bound.toDouble(),
            strict = false,
            activator = auxBoolVar,
        )
    }

    is ReifiedRealLinear -> FactorRow.Doubles(
        vars,
        intCoeffs,
        realVars,
        realCoeffs,
        op,
        bound,
        strict,
        aux,
    )

    else -> null
}

/** The comparison that holds when this one does not. An equality has no single complement. */
fun LinearOp.complemented(): LinearOp = when (this) {
    LinearOp.LE -> LinearOp.GE
    LinearOp.GE -> LinearOp.LE
    LinearOp.EQ -> LinearOp.NE
    LinearOp.NE -> LinearOp.EQ
}
