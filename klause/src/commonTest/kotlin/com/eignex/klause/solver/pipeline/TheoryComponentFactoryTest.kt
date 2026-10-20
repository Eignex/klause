package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.theory.difference.DifferenceSearchComponent
import com.eignex.klause.theory.qflra.ExactLiraSearchComponent
import com.eignex.klause.util.Bits
import kotlin.test.Test
import kotlin.test.assertIs

class TheoryComponentFactoryTest {
    @Test
    fun `the generic integer component factory should retain eager prefix checks`() {
        val open = Bits(2).also { it.set(0); it.set(1) }
        val model = Problem(
            numBoolVars = 2,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(0, 0), open, open),
            factors = arrayOf(
                Linear(intArrayOf(2, 3), intArrayOf(0, 1), LinearOp.GE, 2),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.EQ, 0),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0),
            ),
        )
        val component = assertIs<ExactLiraSearchComponent>(model.componentPlan().theoryComponent(model))
        component.use {
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(2))
            assertIs<ComponentResult.Consistent>(session.initialize())

            val result = session.push(SearchDecision.Bool(Lit.make(0, true)))

            assertIs<ComponentResult.Conflict>(result)
            assertIs<ComponentCheck.Infeasible>(session.check())
        }
    }

    @Test
    fun `the shared real and mixed routes should retain partial arithmetic refutations`() {
        for (integers in listOf(0, 1)) {
            val open = Bits(integers).also { bits -> repeat(integers, bits::set) }
            val model = Problem(
                numBoolVars = 2,
                intBounds = IntBounds.fromModelBounds(LongArray(integers), LongArray(integers), open, open),
                numRealVars = 1,
                realLower = doubleArrayOf(1.0),
                realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
                factors = arrayOf(
                    ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(2.0),
                        LinearOp.LE, 0.0),
                ),
            )
            val component = assertIs<ExactLiraSearchComponent>(model.componentPlan().theoryComponent(model))
            component.use {
                val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(2))
                assertIs<ComponentResult.Consistent>(session.initialize())

                val result = session.push(SearchDecision.Bool(Lit.make(0, true)))

                assertIs<ComponentResult.Conflict>(result)
            }
        }
    }

    @Test
    fun `component plan should build the selected theory fragment component`() {
        val openUpper = Bits(1).also { it.set(1) }
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(3, 0), null, openUpper),
            factors = arrayOf(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.LE, 4),
            ),
        )

        val component = model.componentPlan().theoryComponent(model)

        assertIs<DifferenceSearchComponent>(component)
    }

    @Test
    fun `component plan should retain exact LRA with no integer columns`() {
        val model = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            factors = arrayOf(
                ReifiedRealLinear(
                    0,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.LE,
                    2.0,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(3.0),
        )

        val plan = model.componentPlan()

        assertIs<ExactLiraSearchComponent>(plan.theoryComponent(model))
    }
}
