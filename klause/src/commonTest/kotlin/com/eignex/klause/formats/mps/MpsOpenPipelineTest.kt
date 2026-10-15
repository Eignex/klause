package com.eignex.klause.formats.mps

import com.eignex.klause.factor.arithmetic.LinearIndicator
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.solver.pipeline.IntVariableOwner
import com.eignex.klause.solver.pipeline.SourceProblemRoute
import com.eignex.klause.solver.pipeline.pipelineRoute
import com.eignex.klause.solver.pipeline.ProblemPipeline
import com.eignex.klause.solver.pipeline.sourceRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertTrue

class MpsOpenPipelineTest {

    @Test
    fun `source fixed indicators prove finite ownership without changing the source declaration`() {
        for (trigger in listOf(0, 1)) {
            val compiled = Mps.parse(
                "ROWS\n N COST\n L R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 0\n X COST 1 R 1\n" +
                    " M1 'MARKER' 'INTEND'\nRHS\n RHS R 2\nBOUNDS\n FX B I $trigger\n LI B X 0\n" +
                    "INDICATORS\n IF R I $trigger\nENDATA",
            ).toProblem()
            val indicator = compiled.model.factors.filterIsInstance<LinearIndicator>().single()

            val route = assertIs<SourceProblemRoute.Finite>(compiled.model.pipelineRoute())

            assertTrue(compiled.model.intBounds.isOpenUpper(1))
            assertEquals(2L, route.problem.intBounds.upper(1))
            assertEquals(IntVariableOwner.CP, route.componentPlan.intOwner(1))
            assertSame(indicator, route.problem.factors.filterIsInstance<LinearIndicator>().single())
        }
    }

    @Test
    fun `keeps an open integer row for the exact LIA route`() {
        val compiled = MpsModel(
            "m",
            ObjectiveSense.MINIMIZE,
            MpsObjective("", IntArray(0), DoubleArray(0), 0.0),
            listOf(MpsVar("x", integer = true, lower = null, upper = null)),
            listOf(MpsConstraint("c", intArrayOf(0), doubleArrayOf(2.0), lower = 3.0, upper = 3.0)),
        ).toProblem()

        assertTrue(compiled.model.intBounds.isOpenLower(0))
        assertTrue(compiled.model.intBounds.isOpenUpper(0))
        assertEquals(ProblemPipeline.EXACT_LIRA, compiled.model.sourceRoute())
    }

    @Test
    fun `classifies an open mixed MPS model for the exact LIRA core`() {
        val compiled = MpsModel(
            "m",
            ObjectiveSense.MINIMIZE,
            MpsObjective("", IntArray(0), DoubleArray(0), 0.0),
            listOf(
                MpsVar("x", integer = true, lower = null, upper = null),
                MpsVar("y", integer = false, lower = null, upper = null),
            ),
            listOf(MpsConstraint("c", intArrayOf(0, 1), doubleArrayOf(1.0, 1.0), lower = 0.0, upper = null)),
        ).toProblem()

        assertEquals(ProblemPipeline.EXACT_LIRA, compiled.model.sourceRoute())
    }

    @Test
    fun `keeps a finite mixed MPS model on the CP pipeline`() {
        val compiled = MpsModel(
            "m",
            ObjectiveSense.MINIMIZE,
            MpsObjective("", IntArray(0), DoubleArray(0), 0.0),
            listOf(
                MpsVar("x", integer = true, lower = 0.0, upper = 5.0),
                MpsVar("y", integer = false, lower = null, upper = null),
            ),
            listOf(MpsConstraint("c", intArrayOf(0, 1), doubleArrayOf(1.0, 1.0), lower = 0.0, upper = null)),
        ).toProblem()

        assertEquals(ProblemPipeline.FINITE_CP, compiled.model.sourceRoute())
    }

    @Test
    fun `retains a finite continuous indicated source row`() {
        val compiled = MpsModel(
            "m",
            ObjectiveSense.MINIMIZE,
            MpsObjective("", IntArray(0), DoubleArray(0), 0.0),
            listOf(
                MpsVar("b", integer = true, lower = 0.0, upper = 1.0),
                MpsVar("x", integer = false, lower = 0.0, upper = 10.0),
            ),
            listOf(
                MpsConstraint(
                    "gated",
                    intArrayOf(1),
                    doubleArrayOf(1.0),
                    lower = null,
                    upper = 4.0,
                    indicator = MpsIndicator(column = 0, whenOne = true),
                ),
            ),
        ).toProblem()

        assertEquals(1, compiled.model.factors.count { it is LinearIndicator })
        assertEquals(0, compiled.model.numBoolVars)
    }
}
