package com.eignex.klause.lowering.mps

import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.solver.ProblemPipeline
import com.eignex.klause.solver.sourceRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MpsOpenPipelineTest {

    @Test
    fun `keeps an open integer row for the General LIA route`() {
        val compiled = MpsModel(
            "m",
            ObjectiveSense.MINIMIZE,
            MpsObjective("", IntArray(0), DoubleArray(0), 0.0),
            listOf(MpsVar("x", integer = true, lower = null, upper = null)),
            listOf(MpsConstraint("c", intArrayOf(0), doubleArrayOf(2.0), lower = 3.0, upper = 3.0)),
        ).toProblem()

        assertTrue(compiled.model.intBounds.isOpenLower(0))
        assertTrue(compiled.model.intBounds.isOpenUpper(0))
        assertEquals(ProblemPipeline.GENERAL_LIA, compiled.model.sourceRoute())
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
    fun `lowers a finite continuous indicated row to a reified real atom`() {
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

        assertEquals(1, compiled.model.factors.count { it is ReifiedRealLinear })
    }
}
