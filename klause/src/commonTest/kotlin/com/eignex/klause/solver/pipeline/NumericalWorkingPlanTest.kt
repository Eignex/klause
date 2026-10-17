package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.LinearSourceObjective
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class NumericalWorkingPlanTest {
    @Test
    fun `a private satisfaction objective should retain the canonical numerical source plan`() {
        val source = LinearSourceProblem(emptyList(), emptyList())
        val model = Problem(0, 0, emptyArray(), emptyArray())
        val plan = PreparedSourcePlan(source, PreparedExecution.Numerical(source, model), emptyList(), 0, 0, 0)
        val objective = LinearObjective()

        val working = plan.numericalWorkingPlan(model, objective, false)

        assertSame(source, plan.originalSource)
        assertNull(plan.objective)
        assertSame(model, working.originalSource)
        assertSame(objective, working.objective)
        assertEquals(PreparedExecutionKind.NUMERICAL, working.kind)
        assertNull(working.componentPlan)
    }

    @Test
    fun `a numerical working plan should reject a different model`() {
        val source = LinearSourceProblem(emptyList(), emptyList())
        val model = Problem(0, 0, emptyArray(), emptyArray())
        val other = Problem(0, 0, emptyArray(), emptyArray())
        val plan = PreparedSourcePlan(source, PreparedExecution.Numerical(source, model), emptyList(), 0, 0, 0)

        assertFailsWith<IllegalArgumentException> {
            plan.numericalWorkingPlan(other, LinearObjective(), false)
        }
    }

    @Test
    fun `a numerical working plan should reject a different captured objective`() {
        val source = LinearSourceProblem(
            emptyList(),
            emptyList(),
            LinearSourceObjective(emptyList(), constant = SemanticRational.ofLong(3)),
        )
        val model = Problem(0, 0, emptyArray(), emptyArray())
        val objective = LinearObjective(constant = 3)
        val plan = PreparedSourcePlan(
            source,
            PreparedExecution.Numerical(source, model),
            emptyList(),
            0,
            0,
            0,
            objective,
        )

        assertFailsWith<IllegalArgumentException> {
            plan.numericalWorkingPlan(model, LinearObjective(constant = 4), false)
        }
    }

    @Test
    fun `a numerical working plan should reject a different captured direction`() {
        val source = LinearSourceProblem(
            emptyList(),
            emptyList(),
            LinearSourceObjective(emptyList(), sense = ObjectiveSense.MAXIMIZE),
        )
        val model = Problem(0, 0, emptyArray(), emptyArray())
        val objective = LinearObjective()
        val plan = PreparedSourcePlan(
            source,
            PreparedExecution.Numerical(source, model),
            emptyList(),
            0,
            0,
            0,
            objective,
            maximize = true,
        )

        assertFailsWith<IllegalArgumentException> {
            plan.numericalWorkingPlan(model, objective, false)
        }
    }
}
