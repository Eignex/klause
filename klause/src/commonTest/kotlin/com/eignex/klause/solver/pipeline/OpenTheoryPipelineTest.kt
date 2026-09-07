package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Bits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenTheoryPipelineTest {

    @Test
    fun `preparation reports LP work used to close an open side`() {
        val openUpper = Bits(1).also { it.set(0) }
        val request = OpenTheoryRequest(
            model = Problem(
                numBoolVars = 0,
                intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, openUpper),
                factors = arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 7)),
            ),
        )

        val preparation = OpenTheoryPipeline.prepare(request)

        assertEquals(1, preparation.closedSides)
        val stats = assertNotNull(preparation.stats)
        assertTrue(
            stats.lpStats.standalonePasses.sum + stats.lpStats.componentPasses.sum > 0.0,
            "the LP solve that closed the side must be reported",
        )
    }

    @Test
    fun `selects and executes a satisfiability route`() {
        val openUpper = Bits(1).also { it.set(0) }
        val request = OpenTheoryRequest(
            model = Problem(
                numBoolVars = 0,
                intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, openUpper),
                factors = emptyArray(),
            ),
        )

        val result = OpenTheoryPipeline.execute(request)

        val satisfiability = assertIs<OpenTheoryExecution.Satisfy>(result).result
        val assignment = assertIs<OpenTheoryResult.Sat>(satisfiability).assignment
        assertEquals(0L, assertIs<OpenTheoryAssignment.Difference>(assignment).sample.ints[0])
    }
}
