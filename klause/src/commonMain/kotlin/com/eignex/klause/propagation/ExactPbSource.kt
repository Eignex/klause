package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.ReifiedPseudoBooleanPropagator
import com.eignex.klause.factor.bool.ExactPseudoBooleanPropagator
import com.eignex.klause.ir.Lit
import com.eignex.klause.model.PbOp
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.minus
import com.eignex.util.numbers.plus

internal fun ExactPseudoBooleanPropagator.loadArithmeticReason(
    target: PbAccumulator,
    forced: Int,
    state: PropagationState,
    budget: ArithmeticLearningBudget,
): Boolean {
    budget.spend(bytes = literals.size.toLong() * 12 + 80)
    val weights = Array(literals.size) {
        budget.spend()
        constants.exactCoeff(it)
    }
    return PbArithmeticSource(weights, literals, constants.exactBound, op, auxBoolVar)
        .load(target, forced, state, budget)
}

internal fun ReifiedPseudoBooleanPropagator.loadArithmeticReason(
    target: PbAccumulator,
    forced: Int,
    state: PropagationState,
    budget: ArithmeticLearningBudget,
): Boolean {
    budget.spend(bytes = literals.size.toLong() * 48 + 80)
    val exact = Array(weights.size) {
        budget.spend()
        bigIntOf(weights[it])
    }
    return PbArithmeticSource(exact, literals, bigIntOf(bound), op, auxBoolVar).load(target, forced, state, budget)
}

private class PbArithmeticSource(
    val weights: Array<BigInt>,
    val literals: IntArray,
    val bound: BigInt,
    val op: PbOp,
    val indicator: Int,
) {
    fun load(target: PbAccumulator, forced: Int, state: PropagationState, budget: ArithmeticLearningBudget): Boolean {
        var relation = op
        var rhs = bound
        var guard = Lit.NONE
        if (indicator >= 0) {
            if (indicator >= state.problem.numBoolVars) return false
            val truth = state.boolValues[indicator] ?: return false
            // For an indicator deduction, use the opposite implication's contrapositive.
            val wanted = if (forced != Lit.NONE && Lit.variable(forced) == indicator) !truth else truth
            if (!wanted) {
                budget.scalar(rhs, BIG_ONE)
                when (op) {
                    PbOp.GE -> {
                        relation = PbOp.LE
                        rhs -= BIG_ONE
                    }
                    PbOp.LE -> {
                        relation = PbOp.GE
                        rhs += BIG_ONE
                    }
                    PbOp.EQ -> return false
                }
            }
            guard = Lit.make(indicator, wanted)
        }
        target.loadPb(weights, literals, rhs)
        val ge = when (relation) {
            PbOp.GE -> true
            PbOp.LE -> false
            PbOp.EQ -> geEqualitySide(target, forced, state)
        }
        if (!ge) {
            var total = BIG_ZERO
            for (weight in weights) {
                budget.scalar(total, weight)
                total += weight
            }
            budget.spend(work = literals.size.toLong(), bytes = literals.size.toLong() * Int.SIZE_BYTES)
            budget.scalar(total, rhs)
            target.loadPb(weights, IntArray(literals.size) { literals[it] xor 1 }, total - rhs)
        }
        target.guardedBy(guard)
        return true
    }

    private fun geEqualitySide(target: PbAccumulator, forced: Int, state: PropagationState): Boolean {
        if (forced != Lit.NONE && Lit.variable(forced) != indicator) {
            return (target.coefOf(Lit.variable(forced)) > BIG_ZERO) == Lit.isPositive(forced)
        }
        return target.conflicting(state)
    }
}
