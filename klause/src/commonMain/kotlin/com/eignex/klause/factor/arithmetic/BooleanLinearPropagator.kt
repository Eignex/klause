package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.arithmetic.internals.wideAlwaysHolds
import com.eignex.klause.factor.arithmetic.internals.wideEnforceRow
import com.eignex.klause.factor.arithmetic.internals.wideNeverHolds
import com.eignex.klause.factor.arithmetic.internals.wideSumRange
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Term
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.maxOf
import com.eignex.klause.util.minOf
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus

internal class BooleanLinearPropagator(private val row: ExactLinear) : Propagator {
    private val integers = (0 until row.size).filter { Term.isInt(row.ref(it)) }
    private val vars = integers.map { Term.intVar(row.ref(it)) }.toIntArray()
    private val coefficients = integers.map { row.constants.exactCoeff(it) }.toTypedArray()
    private val booleans = (0 until row.size).filter { Term.isBool(row.ref(it)) }
    private val boolVars = booleans.map { Lit.variable(Term.lit(row.ref(it))) }.toIntArray()
    private val boolCoefficients = booleans.map { row.constants.exactCoeff(it) }.toTypedArray()
    override val initialIntEventWatches: IntArray = IntEvent.boundEventWatches(vars)

    private fun booleanRange(state: PropagationState): Pair<BigInt, BigInt> {
        var lower = BIG_ZERO
        var upper = BIG_ZERO
        for (index in boolVars.indices) {
            val coefficient = boolCoefficients[index]
            when (state.boolValues[boolVars[index]]) {
                true -> {
                    lower += coefficient
                    upper += coefficient
                }
                false -> Unit
                null -> {
                    lower += minOf(BIG_ZERO, coefficient)
                    upper += maxOf(BIG_ZERO, coefficient)
                }
            }
        }
        return lower to upper
    }

    private fun booleanPremises(state: PropagationState): IntArray =
        row.variables.boolVars.toList().mapNotNull { variable ->
            state.boolValues[variable]?.let { Lit.make(variable, !it) }
        }.toIntArray()

    private fun reason(state: PropagationState): IntArray? {
        val integerPremises = state.composeIntVarAtomAntecedents(vars)
        val booleanPremises = booleanPremises(state)
        return when {
            booleanPremises.isEmpty() -> integerPremises
            integerPremises == null -> booleanPremises
            else -> integerPremises + booleanPremises
        }
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? = reason(state)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val (integerLower, integerUpper) = wideSumRange(state, vars, coefficients)
        val (booleanLower, booleanUpper) = booleanRange(state)
        val lower = integerLower + booleanLower
        val upper = integerUpper + booleanUpper
        var relation = row.relation
        var bound = row.constants.exactBound
        if (row.activator != LinearRow.ALWAYS) {
            val always = wideAlwaysHolds(relation, lower, upper, bound)
            val never = wideNeverHolds(relation, lower, upper, bound)
            if ((always || never) && !state.pinBool(row.activator, always, reason(state))) return false
            val truth = state.boolValues[row.activator] ?: return true
            if (!truth) {
                when (relation) {
                    LinearOp.LE -> {
                        relation = LinearOp.GE
                        bound += BIG_ONE
                    }
                    LinearOp.GE -> {
                        relation = LinearOp.LE
                        bound -= BIG_ONE
                    }
                    LinearOp.EQ -> relation = LinearOp.NE
                    LinearOp.NE -> relation = LinearOp.EQ
                }
            }
        }
        if (wideNeverHolds(relation, lower, upper, bound)) return false
        for (index in boolVars.indices) {
            val variable = boolVars[index]
            if (state.boolValues[variable] != null) continue
            val coefficient = boolCoefficients[index]
            val otherLower = lower - minOf(BIG_ZERO, coefficient)
            val otherUpper = upper - maxOf(BIG_ZERO, coefficient)
            val zeroImpossible = wideNeverHolds(relation, otherLower, otherUpper, bound)
            val oneImpossible = wideNeverHolds(relation, otherLower + coefficient, otherUpper + coefficient, bound)
            if (zeroImpossible && oneImpossible) return false
            if ((zeroImpossible || oneImpossible) && !state.pinBool(variable, zeroImpossible, reason(state))) {
                return false
            }
        }
        val premises = booleanPremises(state)
        // Unknown Boolean terms contribute an interval; each numeric side uses its weakest compatible threshold.
        return when (relation) {
            LinearOp.LE -> wideEnforceRow(state, vars, coefficients, relation,
                bound - booleanLower, null, premises)
            LinearOp.GE -> wideEnforceRow(state, vars, coefficients, relation,
                bound - booleanUpper, null, premises)
            LinearOp.EQ -> wideEnforceRow(state, vars, coefficients, LinearOp.LE,
                bound - booleanLower, null, premises) &&
                wideEnforceRow(state, vars, coefficients, LinearOp.GE, bound - booleanUpper, null, premises)
            LinearOp.NE -> booleanLower != booleanUpper || wideEnforceRow(state, vars, coefficients,
                relation, bound - booleanLower, null, premises)
        }
    }
}
