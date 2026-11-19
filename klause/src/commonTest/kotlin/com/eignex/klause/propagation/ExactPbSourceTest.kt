package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBooleanPropagator
import com.eignex.klause.factor.bool.ExactPseudoBooleanPropagator
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.model.PbOp
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExactPbSourceTest {
    private val state = PropagationState(Problem(3, 0, emptyArray(), emptyArray()), Assumptions.None)

    @Test
    fun `every reified source direction should preserve all feasible Boolean assignments`() {
        for (scale in listOf(bigIntOf(1), bigIntOf(Long.MAX_VALUE) + bigIntOf(1))) {
            val weights = arrayOf(scale, scale + bigIntOf(1))
            val literals = intArrayOf(Lit.make(1, true), Lit.make(2, false))
            for (op in PbOp.entries) {
                val bound = scale + bigIntOf(1)
                val source = ReifiedPseudoBoolean(0, weights, literals, op, bound).propagatorProjection()
                for (truth in listOf(false, true)) {
                    state.boolValues[0] = truth
                    for (forced in intArrayOf(Lit.NONE, Lit.make(0, truth), Lit.make(1, true), Lit.make(2, false))) {
                        val target = PbAccumulator()
                        val budget = ArithmeticLearningBudget(state)
                        val loaded = when (source) {
                            is ExactPseudoBooleanPropagator ->
                                source.loadArithmeticReason(target, forced, state, budget)
                            is ReifiedPseudoBooleanPropagator ->
                                source.loadArithmeticReason(target, forced, state, budget)
                            else -> error("unsupported projection")
                        }
                        val wanted = if (forced != Lit.NONE && Lit.variable(forced) == 0) !truth else truth
                        assertEquals(op != PbOp.EQ || wanted, loaded)
                        if (!loaded) continue
                        val row = target.materialize() ?: continue
                        for (mask in 0..7) {
                            val assignment = BooleanArray(3) { mask and (1 shl it) != 0 }
                            var activity = BIG_ZERO
                            for (index in weights.indices) {
                                if (Lit.evaluate(literals[index], assignment[Lit.variable(literals[index])])) {
                                    activity += weights[index]
                                }
                            }
                            val body = when (op) {
                                PbOp.GE -> activity >= bound
                                PbOp.LE -> activity <= bound
                                PbOp.EQ -> activity == bound
                            }
                            if (assignment[0] != body) continue
                            var retained = BIG_ZERO
                            for (index in row.literals.indices) {
                                if (Lit.evaluate(row.literals[index], assignment[Lit.variable(row.literals[index])])) {
                                    retained += row.weights[index]
                                }
                            }
                            assertTrue(retained >= row.degree, "op=$op truth=$truth forced=$forced mask=$mask")
                        }
                    }
                }
            }
        }
    }
}
