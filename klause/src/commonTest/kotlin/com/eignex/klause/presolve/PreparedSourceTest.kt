package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.util.Bits
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The one source-safe phase every route is planned from, and what the phases behind it may assume. */
class PreparedSourceTest {

    /** `x = y + z` with `y + z <= 4`, every column open: the aggregate pass rewrites the second row. */
    private fun aggregatable(): Problem {
        val open = Bits(3).also { bits -> repeat(3) { bits.set(it) } }
        return Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(LongArray(3), LongArray(3), null, open),
            factors = arrayOf<Factor>(
                Linear(longArrayOf(1, -1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0L),
                Linear(longArrayOf(1, 1), intArrayOf(1, 2), LinearOp.LE, 4L),
            ),
        )
    }

    @Test
    fun `preparation reports the passes that rewrote the model`() {
        val prepared = PresolvePipeline.prepareSource(aggregatable())

        assertTrue(prepared.changed)
        assertContains(prepared.stats.passes, PresolvePass.AGGREGATE_SUB_SUMS.id)
    }

    @Test
    fun `an untouched model is its own preparation`() {
        val problem = aggregatable()

        val prepared = PresolvePipeline.prepareSource(problem, PresolveConfig.NONE)

        assertSame(problem, prepared.problem)
        assertFalse(prepared.changed)
    }

    @Test
    fun `a model that already passed the phase is not put through it again`() {
        // The same rewritable pair, already baked: reaching this boundary is what says the phase ran.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(0, 9) },
            factors = arrayOf<Factor>(
                Linear(longArrayOf(1, -1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0L),
                Linear(longArrayOf(1, 1), intArrayOf(1, 2), LinearOp.LE, 4L),
            ),
        ).bake()

        val prepared = PresolvePipeline.prepareSource(problem)

        assertSame(problem, prepared.problem)
    }

    @Test
    fun `the bounded lane presolves the prepared model rather than the caller's own`() {
        // Two columns against the source model's three, so the outcome names which model the lane read.
        val prepared = PreparedSource(
            source = aggregatable(),
            problem = Problem(
                numBoolVars = 0,
                intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(3, 3), null, null),
                factors = arrayOf<Factor>(Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 4L)),
            ),
            infeasible = false,
            objective = null,
            passesFired = emptyList(),
            budget = null,
        )

        val outcome = PresolvePipeline.run(prepared, null, PresolveConfig.DEFAULT, solutionSetSensitive = false)

        assertEquals(2, outcome.problem.numIntVars)
    }
}
