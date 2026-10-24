package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearIndicator
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.bool.internals.permitsLongPseudoBoolean
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.finiteRelaxationSupported
import com.eignex.klause.solver.objective.LinearObjective

private fun Factor.extendsLpObjectiveCone(): Boolean = when (this) {
    is ArrayMinMax, is Cardinality, is Clause -> true
    is PseudoBoolean -> permitsLongPseudoBoolean(constants)
    is Linear -> relation != LinearOp.NE
    is ExactLinear -> finiteRelaxationSupported() &&
        (activator == LinearRow.ALWAYS || variables.reals.isNotEmpty() || strict)
    is LinearIndicator -> row.finiteRelaxationSupported()
    is ReifiedRealLinear -> true
    else -> false
}

internal class FactorConeProjection(problem: Problem, objective: LinearObjective?) {
    private val integers = BooleanArray(problem.numIntVars)
    private val booleans = BooleanArray(problem.numBoolVars)
    private val reals = BooleanArray(problem.numRealVars)
    private val factors = BooleanArray(problem.factors.size)

    init {
        objective?.let { obj ->
            for (i in obj.intCoefficientValues.indices) integers[i] = obj.intCoefficientValues[i] != 0L
            for (b in obj.boolWeightValues.indices) booleans[b] = obj.boolWeightValues[b] != 0L
            for (r in obj.realCoefficientValues.indices) reals[r] = obj.realCoefficientValues[r] != 0.0
        }
        val candidates = problem.factors.indices.filter { problem.factors[it].extendsLpObjectiveCone() }
        var changed = true
        while (changed) {
            changed = false
            for (id in candidates) {
                if (factors[id]) continue
                val factor = problem.factors[id]
                if (!touches(factor)) continue
                factors[id] = true
                changed = mark(factor.intVars, integers) || changed
                changed = mark(factor.boolVars, booleans) || changed
                changed = mark(factor.variables.reals, reals) || changed
            }
        }
    }

    fun contains(factorId: Int): Boolean = factors[factorId]

    private fun touches(factor: Factor): Boolean =
        factor.intVars.any { integers[it] } || factor.boolVars.any { booleans[it] } ||
            factor.variables.reals.any { reals[it] }

    private fun mark(variables: IntArray, membership: BooleanArray): Boolean {
        var changed = false
        for (variable in variables) {
            if (!membership[variable]) {
                membership[variable] = true
                changed = true
            }
        }
        return changed
    }
}
