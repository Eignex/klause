package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Abs
import com.eignex.klause.factor.arithmetic.DivMod
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.ir.DefinitionHint
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.Move
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.retainedCoordinates
import com.eignex.klause.solver.Assignment
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreparedSourceHintsTest {
    @Test
    fun `binary substitution should preserve an unrelated source definition and its objective gradient`() {
        val domains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(-2, 2), IntDomain(0, 2))
        val source = Problem(0, 4, domains, listOf(
            Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1), Abs(2, 3),
        ))
        val objective = LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1))
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(source, listOf(DefinitionHint.Abs(3, DefinitionHint.Operand.Variable(2)))),
            ExecutionOperation.OPTIMIZE, FiniteEngine.LOCAL_SEARCH, objective,
        ))
        val shape = invocation.finiteShape()
        val preparation = FinitePipeline.prepare(FinitePipelineRequest(
            shape.problem, FiniteEngine.LOCAL_SEARCH, shape.linearObjective,
            PresolveConfig.parse("binary-columns"), explicitPresolveConfig = true, preparedPlan = shape.preparedPlan,
        ))

        val view = assertNotNull(invocation.heuristicHints(preparation.problem, preparation.objective,
            preparation.sourceMapping, Cancellation.Never, setOf(ExecutionStrategy.LOCAL_SEARCH),
            assertNotNull(preparation.executionPlan).capabilities))
        val assignment = Assignment(preparation.problem.numBoolVars, preparation.problem.numIntVars)
        assignment.setInt(2, -2)
        assertNotNull(view.sweep).sweep(assignment, domains)

        assertEquals(2, preparation.problem.numBoolVars)
        assertEquals(2L, assignment.intValue(3))
        assertEquals(-1.0, assertNotNull(view.objective).deltaIfApplied(assignment, Move.IntSet(2, 1)))
    }

    @Test
    fun `affine elimination should keep a hinted cone through the column presolve retains`() {
        val domains = arrayOf(IntDomain(0, 1), IntDomain(0, 2), IntDomain(-2, 0), IntDomain(0, 2), IntDomain(0, 1))
        val source = Problem(0, domains.size, domains, listOf(
            Linear(longArrayOf(2, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
            Linear(longArrayOf(1, -1), intArrayOf(2, 1), LinearOp.EQ, -2), Abs(2, 3), Abs(0, 4),
        ))
        val variable = { id: Int -> DefinitionHint.Operand.Variable(id) }
        val hints = listOf(
            DefinitionHint.Linear(1, longArrayOf(2, -1), arrayOf(variable(0), variable(1)), 0),
            DefinitionHint.Linear(2, longArrayOf(1, -1), arrayOf(variable(2), variable(1)), -2),
            DefinitionHint.Abs(3, variable(2)),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1))
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, hints),
            ExecutionOperation.OPTIMIZE, FiniteEngine.LOCAL_SEARCH, objective))
        val shape = invocation.finiteShape()
        val preparation = FinitePipeline.prepare(FinitePipelineRequest(
            shape.problem, FiniteEngine.LOCAL_SEARCH, shape.linearObjective,
            PresolveConfig.parse("affine"), explicitPresolveConfig = true, preparedPlan = shape.preparedPlan,
        ))
        assertEquals(-1, assertNotNull(assertNotNull(preparation.sourceMapping).retainedCoordinates()).int(1))

        val view = assertNotNull(invocation.heuristicHints(preparation.problem, preparation.objective,
            preparation.sourceMapping, Cancellation.Never, setOf(ExecutionStrategy.LOCAL_SEARCH),
            assertNotNull(preparation.executionPlan).capabilities))
        val assignment = Assignment(preparation.problem.numBoolVars, preparation.problem.numIntVars)
        assertNotNull(view.sweep).sweep(assignment, domains)

        assertEquals(-2L, assignment.intValue(2))
        assertEquals(2L, assignment.intValue(3))
    }

    @Test
    fun `empty source hints should retain unhinted arithmetic maintenance and objective gradients`() {
        for (maximize in listOf(false, true)) {
            val domains = arrayOf(IntDomain(-3, 3), IntDomain(0, 3), IntDomain(0, 9), IntDomain(0, 81),
                IntDomain(0, 27), IntDomain(0, 2))
            val source = Problem(0, domains.size, domains, arrayOf(Abs(0, 1), Product(1, 1, 2), Power(2, 2, 3),
                DivMod(3, bigIntOf(3), 4, 5)))
            val objective = LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1), constant = 7)
            val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source),
                ExecutionOperation.OPTIMIZE, FiniteEngine.LOCAL_SEARCH, objective, maximize))
            val shape = invocation.finiteShape()
            val preparation = FinitePipeline.prepare(FinitePipelineRequest(shape.problem, FiniteEngine.LOCAL_SEARCH,
                shape.linearObjective, PresolveConfig.NONE, preparedPlan = shape.preparedPlan))
            assertTrue(invocation.source.definitionHints.isEmpty())

            val view = assertNotNull(invocation.heuristicHints(preparation.problem, preparation.objective,
                preparation.sourceMapping, Cancellation.Never, setOf(ExecutionStrategy.LOCAL_SEARCH),
                assertNotNull(preparation.executionPlan).capabilities))
            val assignment = Assignment(0, domains.size)
            assignment.setInt(0, -3)
            assertNotNull(view.sweep).sweep(assignment, domains)

            assertEquals(81L, assignment.intValue(3))
            assertEquals(27L, assignment.intValue(4))
            assertEquals(0L, assignment.intValue(5))
            assertEquals(if (maximize) 80.0 else -80.0,
                assertNotNull(view.objective).deltaIfApplied(assignment, Move.IntSet(0, 1)))
            assertNull(shape.localSearchObjective)
            assertNull(shape.definitionalSweep)
        }
    }
}
