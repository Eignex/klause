package com.eignex.klause.solver.objective

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.impliedLinearRows
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.addExact
import com.eignex.klause.util.mulExact
import com.eignex.klause.util.subExact

internal fun LinearObjective.throughBooleanChannels(problem: Problem): LinearObjective? {
    val objectiveVar = singleIntObjective()?.varId ?: return null
    if (!problem.supportsBooleanObjectiveProjection(objectiveVar)) return null
    val channels = IntArray(problem.numIntVars) { Lit.NONE }
    for (factor in problem.factors) {
        val channel = factor as? ReifiedLinear ?: continue
        val row = channel.integerConstants ?: continue
        if (channel.op != LinearOp.EQ || channel.vars.size != 1 || row.coeff(0) != 1L ||
            row.bound !in 0L..1L
        ) continue
        val variable = channel.vars[0]
        val bounds = problem.intBounds
        if (!bounds.hasLower(variable) || !bounds.hasUpper(variable) ||
            bounds.lower(variable) < 0L || bounds.upper(variable) > 1L
        ) continue
        channels[variable] = Lit.make(channel.auxBoolVar, row.bound == 1L)
    }
    for (factor in problem.factors) {
        for (row in factor.impliedLinearRows) {
            if (!row.isLongUnconditional || row.relation != LinearOp.EQ) continue
            val projected = try {
                projectBooleanRow(problem, row, objectiveVar, intCoefficients[objectiveVar], constant, channels)
            } catch (_: CheckedLongOverflowException) {
                null
            }
            if (projected != null) return projected
        }
    }
    return null
}

private fun Problem.supportsBooleanObjectiveProjection(objectiveVar: Int): Boolean =
    numRealVars == 0 && factors.all { factor ->
        if (factor.intVars.isEmpty()) true else when (factor) {
            is Linear -> factor.integerConstants != null && factor.op != LinearOp.NE &&
                factor.vars.all { it == objectiveVar || intBounds.isFixedOrBinary(it) }

            is ReifiedLinear -> factor.integerConstants != null && factor.vars.size == 1 &&
                intBounds.isFixedOrBinary(factor.vars.single())

            else -> false
        }
    }

private fun IntBounds.isFixedOrBinary(variable: Int): Boolean =
    hasLower(variable) && hasUpper(variable) &&
        (lower(variable) == upper(variable) || lower(variable) >= 0L && upper(variable) <= 1L)

private fun projectBooleanRow(
    problem: Problem,
    row: LinearRow,
    objectiveVar: Int,
    objectiveCoeff: Long,
    objectiveConstant: Long,
    channels: IntArray,
): LinearObjective? {
    var pivot = 0L
    for (k in 0 until row.size) {
        val ref = row.ref(k)
        if (Term.isInt(ref) && Term.intVar(ref) == objectiveVar) pivot = addExact(pivot, row.coeff(k))
    }
    if (pivot != 1L && pivot != -1L) return null
    val scale = if (pivot == 1L) objectiveCoeff else subExact(0L, objectiveCoeff)
    var constant = addExact(objectiveConstant, mulExact(scale, row.bound))
    val weights = LongArray(problem.numBoolVars)
    for (k in 0 until row.size) {
        val ref = row.ref(k)
        if (Term.isInt(ref) && Term.intVar(ref) == objectiveVar) continue
        val weight = subExact(0L, mulExact(scale, row.coeff(k)))
        if (weight == 0L) continue
        val literal = if (Term.isBool(ref)) {
            Term.lit(ref)
        } else {
            val variable = Term.intVar(ref)
            val bounds = problem.intBounds
            if (bounds.hasLower(variable) && bounds.hasUpper(variable) &&
                bounds.lower(variable) == bounds.upper(variable)
            ) {
                constant = addExact(constant, mulExact(weight, bounds.lower(variable)))
                continue
            }
            channels[variable].takeUnless { it == Lit.NONE } ?: return null
        }
        val variable = Lit.variable(literal)
        if (Lit.isPositive(literal)) {
            weights[variable] = addExact(weights[variable], weight)
        } else {
            constant = addExact(constant, weight)
            weights[variable] = subExact(weights[variable], weight)
        }
    }
    var minimum = constant
    var maximum = constant
    for (weight in weights) {
        if (weight < 0L) minimum = addExact(minimum, weight) else maximum = addExact(maximum, weight)
    }
    subExact(maximum, minimum)
    return LinearObjective(boolWeights = weights, constant = constant)
}
