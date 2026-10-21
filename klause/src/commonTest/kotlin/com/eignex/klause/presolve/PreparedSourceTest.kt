package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.time.Duration
import kotlin.test.assertTrue

class PreparedSourceTest {
    @Test
    fun `unchanged source preparation should retain attempted pass effort`() {
        val problem = Problem(0, 0, emptyArray(), emptyArray())
        val budget = PresolveBudget(1_000_000)
        val config = PresolveConfig.parse("off,+strengthen")

        val prepared = PresolvePipeline.prepareSource(problem, config, presolveBudget = budget)

        assertSame(problem, prepared.problem)
        assertFalse(prepared.changed)
        val effort = assertNotNull(prepared.stats.effort)
        assertEquals(budget.spent(), effort.work)
        assertEquals(1, effort.passCalls[PresolvePass.STRENGTHEN_COEFFICIENTS.id])
        assertEquals(budget.rounds, effort.rounds)
        assertTrue(effort.elapsed >= Duration.ZERO)
    }

    @Test
    fun `allowance stopped source preparation should retain its ledger`() {
        val problem = aggregatable()
        val budget = PresolveBudget(1)
        val config = PresolveConfig.parse("off,+subsume")

        val prepared = PresolvePipeline.prepareSource(problem, config, presolveBudget = budget)

        assertSame(problem, prepared.problem)
        assertSame(budget, prepared.budget)
        val effort = assertNotNull(prepared.stats.effort)
        assertEquals(1L, effort.allowance)
        assertEquals(budget.spent(), effort.work)
        assertTrue(budget.spent() >= budget.allowance)
        assertEquals(budget.passCalls, effort.passCalls)
    }

    @Test
    fun `disabled source preparation should consume no pass work`() {
        val budget = PresolveBudget(17)

        val prepared = PresolvePipeline.prepareSource(aggregatable(), PresolveConfig.NONE, presolveBudget = budget)

        assertSame(prepared.source, prepared.problem)
        val effort = assertNotNull(prepared.stats.effort)
        assertEquals("off", effort.emphasis)
        assertEquals(0L, effort.work)
        assertEquals(0, effort.rounds)
        assertTrue(effort.passCalls.isEmpty())
    }

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
    fun `preparation should report the passes that rewrote the model`() {
        val prepared = PresolvePipeline.prepareSource(aggregatable())

        assertTrue(prepared.changed)
        assertContains(prepared.stats.passes, PresolvePass.AGGREGATE_SUB_SUMS.id)
    }

    @Test
    fun `source reduction should run after routing slice exhaustion`() {
        val parent = PresolveBudget(6_000L)
        val routeStop = parent.slice(parent.remaining() / 2)
        routeStop.charge(3_000L)
        assertTrue(routeStop())

        val prepared = PresolvePipeline.prepareSource(
            aggregatable(),
            cancellation = parent.orSpent(Cancellation.Never),
            presolveBudget = parent,
        )

        assertContains(prepared.stats.passes, PresolvePass.AGGREGATE_SUB_SUMS.id)
        assertTrue(parent.remaining() > 0L)
    }

    @Test
    fun `a model that already passed the phase should not be prepared again`() {
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
    fun `the bounded lane should presolve the prepared model rather than the caller model`() {
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
            rebuild = SourceRebuilds.NONE,
            budget = null,
        )

        val outcome = PresolvePipeline.run(prepared, null, PresolveConfig.DEFAULT, solutionSetSensitive = false)

        assertEquals(2, outcome.problem.numIntVars)
    }
}
