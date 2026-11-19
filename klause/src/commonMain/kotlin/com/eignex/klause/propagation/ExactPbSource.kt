package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.ExactPseudoBooleanPropagator
import com.eignex.klause.ir.Lit
import com.eignex.klause.model.PbOp
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.minus
import com.eignex.util.numbers.plus

internal fun ExactPseudoBooleanPropagator.loadArithmeticReason(
    target: PbAccumulator,
    forced: Int,
    state: PropagationState,
    budget: ArithmeticLearningBudget,
): Boolean {
    if (auxBoolVar >= 0) return false
    budget.spend(bytes = literals.size.toLong() * 12)
    val weights = Array(literals.size) { constants.exactCoeff(it) }
    target.loadPb(weights, literals, constants.exactBound)
    val ge = when (op) {
        PbOp.GE -> true
        PbOp.LE -> false
        PbOp.EQ -> if (forced != Lit.NONE) {
            (target.coefOf(Lit.variable(forced)) > BIG_ZERO) == Lit.isPositive(forced)
        } else {
            var available = BIG_ZERO
            for (index in literals.indices) {
                budget.spend()
                if (!state.litFalse(literals[index])) available += weights[index]
            }
            available < constants.exactBound
        }
    }
    if (ge) return true
    var total = BIG_ZERO
    for (weight in weights) {
        budget.scalar(total, weight)
        total += weight
    }
    return target.loadPb(weights, IntArray(literals.size) { literals[it] xor 1 }, total - constants.exactBound)
}
