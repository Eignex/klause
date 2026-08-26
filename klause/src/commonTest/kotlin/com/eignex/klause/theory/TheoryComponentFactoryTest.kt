package com.eignex.klause.theory

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.solver.IntBounds
import com.eignex.klause.solver.ProblemSpec
import com.eignex.klause.solver.componentPlan
import com.eignex.klause.theory.difference.DifferenceSearchComponent
import com.eignex.klause.util.Bits
import kotlin.test.Test
import kotlin.test.assertIs

class TheoryComponentFactoryTest {

    @Test
    fun `component plan builds the selected theory fragment component`() {
        val openUpper = Bits(1).also { it.set(1) }
        val model = ProblemSpec(
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
    fun `component plan retains exact LRA with no integer columns`() {
        val model = ProblemSpec(
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

        assertIs<TheorySearchComponent<*>>(plan.theoryComponent(model))
    }
}
