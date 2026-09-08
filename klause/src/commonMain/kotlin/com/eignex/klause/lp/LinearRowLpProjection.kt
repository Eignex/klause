package com.eignex.klause.lp

import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.RealConstants
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.UnitConsts
import com.eignex.klause.ir.WideConstants
import com.eignex.klause.ir.complemented
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.subExact

internal fun LinearRow.emitLpRelaxation(builder: RelaxationBuilder, projection: LinearLpProjection? = null) {
    when (constants) {
        is WideConstants -> {
            if (activator != LinearRow.ALWAYS || !isIntegerOnly) return
            if (projection == null) {
                if (relation == LinearOp.LE || relation == LinearOp.EQ) emitWideLpRelaxation(builder)
            } else {
                projection.emitWide(this, builder)
            }
        }

        is IntegerConstants -> when {
            activator == LinearRow.ALWAYS && !strict -> emitIntegerLpRelaxation(builder)
            isIntegerOnly && !strict -> emitReifiedIntegerLpRelaxation(builder)
            else -> emitPinnedLpRelaxation(builder)
        }

        is RealConstants -> emitPinnedLpRelaxation(builder)
    }
}

private fun LinearRow.emitIntegerLpRelaxation(builder: RelaxationBuilder) {
    if (isIntegerOnly) {
        builder.linearRow(relation, intVars, requireNotNull(integerCoeffs), bound)
        return
    }
    if ((0 until size).all { Term.isBool(ref(it)) }) {
        val weights = if ((constants as IntegerConstants).coefficients is UnitConsts) null else integerCoeffs
        builder.boolRow(IntArray(size) { Term.lit(ref(it)) }, weights, relation, bound)
        return
    }
    try {
        val columns = IntArray(size)
        val coefficients = LongArray(size)
        var rhs = bound
        for (k in 0 until size) {
            val ref = ref(k)
            val coefficient = coeff(k)
            if (Term.isBool(ref)) {
                val literal = Term.lit(ref)
                columns[k] = builder.boolColumn(Lit.variable(literal))
                coefficients[k] = if (Lit.isPositive(literal)) coefficient else subExact(0L, coefficient)
                if (!Lit.isPositive(literal)) rhs = subExact(rhs, coefficient)
            } else {
                columns[k] = if (Term.isReal(ref)) {
                    builder.realColumn(Term.realVar(ref)).also { if (it < 0) return }
                } else {
                    builder.intColumn(Term.intVar(ref))
                }
                coefficients[k] = coefficient
            }
        }
        builder.row(columns, coefficients, relation, rhs)
    } catch (_: CheckedLongOverflowException) {
        // Omitting an unrepresentable row weakens the relaxation.
        return
    }
}

private fun LinearRow.emitPinnedLpRelaxation(builder: RelaxationBuilder) {
    // A reified row can use a live Boolean pin without inventing a bound for an open column.
    val truth = if (activator == LinearRow.ALWAYS) true else builder.liveBool(activator) ?: return
    val columns = IntArray(size)
    val coefficients = DoubleArray(size)
    val c = constants
    var exactRhs = when (c) {
        is IntegerConstants -> BigFraction.ofLong(c.bound)
        is RealConstants -> BigFraction.ofDouble(c.bound) ?: return
        is WideConstants -> return
    }
    for (k in 0 until size) {
        val reference = ref(k)
        val coefficient = when (c) {
            is IntegerConstants -> c.coeff(k).toDouble()

            is RealConstants -> if (k < c.intCoefficients.size) {
                c.intCoefficients.at(k)
            } else {
                c.realCoefficients.at(k - c.intCoefficients.size)
            }

            is WideConstants -> return
        }
        if (c is IntegerConstants && BigFraction.ofDouble(coefficient) != BigFraction.ofLong(c.coeff(k))) return
        columns[k] = when {
            Term.isBool(reference) -> builder.boolColumn(Lit.variable(Term.lit(reference)))
            Term.isInt(reference) -> builder.intColumn(Term.intVar(reference))
            else -> builder.realColumn(Term.realVar(reference)).also { if (it < 0) return }
        }
        if (Term.isBool(reference) && !Lit.isPositive(Term.lit(reference))) {
            coefficients[k] = -coefficient
            exactRhs -= BigFraction.ofDouble(coefficient) ?: return
        } else {
            coefficients[k] = coefficient
        }
    }
    val rhs = exactRhs.toDouble()
    if (BigFraction.ofDouble(rhs) != exactRhs) return
    val premise = if (activator == LinearRow.ALWAYS) intArrayOf() else intArrayOf(Lit.make(activator, truth))
    if (truth) {
        builder.realRow(columns, coefficients, relation, rhs, strict, premise)
    } else {
        builder.realRow(columns, coefficients, relation.complemented(), rhs, !strict, premise)
    }
}
