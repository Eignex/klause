package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.IntBounds
import com.eignex.klause.solver.ProblemSpec
import com.eignex.klause.util.Bits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OpenTheoryPipelineTest {

    @Test
    fun `selects and executes a satisfiability route`() {
        val openUpper = Bits(1).also { it.set(0) }
        val request = OpenTheoryRequest(
            model = ProblemSpec(
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
