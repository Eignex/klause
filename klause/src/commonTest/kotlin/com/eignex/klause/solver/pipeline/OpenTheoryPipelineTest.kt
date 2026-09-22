package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Bits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenTheoryPipelineTest {

    @Test
    fun `preparation reads a maximized objective in minimize sense`() {
        // `maximize x` subject to `x <= 7`. Lowering x is safe, so a source pass reading the raw
        // coefficient sees `+1 >= 0` and pins x at 0 — the one value maximization rules out. Preparation
        // has to resolve the sense the way execution does, or it reports a model the solve never sees.
        val openUpper = Bits(1).also { it.set(0) }
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, openUpper),
            factors = arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 7)),
        )
        val request = OpenTheoryRequest(
            model = model,
            objective = LinearObjective(intCoefficients = longArrayOf(1L)),
            maximize = true,
        )

        val preparation = OpenTheoryPipeline.prepare(request)

        assertEquals(7L, preparation.model.intBounds.upper(0), "x must keep the values maximization wants")
    }

    @Test
    fun `preparation reports LP work used to close an open side`() {
        val openUpper = Bits(1).also { it.set(0) }
        // The `>= 2` row is what keeps dual fixing off this column: with only the `<= 7` row, lowering
        // would be safe everywhere and the pin would close the side before any LP ran.
        val request = OpenTheoryRequest(
            model = Problem(
                numBoolVars = 0,
                intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, openUpper),
                factors = arrayOf<Factor>(
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 7),
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 2),
                ),
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
