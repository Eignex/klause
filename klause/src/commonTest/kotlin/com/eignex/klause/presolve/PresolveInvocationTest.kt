package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.result.PresolveInvocationOutcome
import com.eignex.klause.solver.result.PresolveLane
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PresolveInvocationTest {
    @Test
    fun `Boolean namespace skips should avoid whole model entry work`() {
        val problem = Problem(
            0, 1, arrayOf(IntDomain(0, 10)),
            List(2_048) { Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 7) },
        )
        val budget = PresolveBudget(1L)
        val token = budget.orSpent(Cancellation.Never)

        val result = Presolver.runSource(
            problem, PresolveConfig.parse("off,+bve,+bce,+impl-graph"),
            PresolveContext(presolveBudget = budget), token,
        )

        assertSame(problem, result.problem)
        assertEquals(3, budget.invocations.size)
        assertTrue(budget.invocations.all { it.outcome == PresolveInvocationOutcome.ELIGIBILITY_SKIP })
        assertTrue(budget.invocations.all { it.input.occurrences == null })
        assertEquals(0L, budget.entrySizeVisits)
        assertEquals(0L, budget.spent())
    }

    @Test
    fun `unchanged source calls should reuse aggregate size and retain complete misses`() {
        val problem = Problem(
            0, 1, arrayOf(IntDomain(0, 10)),
            List(8) { Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 7) },
        )
        val budget = PresolveBudget(Long.MAX_VALUE)
        val context = PresolveContext(presolveBudget = budget)
        val config = PresolveConfig.parse("off,+strengthen")
        val token = budget.orSpent(Cancellation.Never)

        repeat(2) { Presolver.runSource(problem, config, context, token) }

        assertEquals(8L, budget.entrySizeVisits)
        assertEquals(2, budget.invocations.size)
        assertTrue(budget.invocations.all { it.outcome == PresolveInvocationOutcome.COMPLETE_MISS })
        assertTrue(budget.invocations.all { it.lane == PresolveLane.SOURCE && it.work > 0L })
        assertEquals(budget.spent(), budget.invocations.sumOf { it.work })
        assertEquals(budget.invocations[0].work - 8L, budget.invocations[1].work)
    }

    @Test
    fun `budget interruption should retain an empty analysis and its charge`() {
        val problem = Problem(
            0, 1, arrayOf(IntDomain(0, 10)),
            listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 7)),
        )
        val budget = PresolveBudget(1L)

        Presolver.runSource(
            problem, PresolveConfig.parse("off,+strengthen"), PresolveContext(presolveBudget = budget),
            budget.orSpent(Cancellation.Never),
        )

        val invocation = budget.invocations.single()
        assertEquals(PresolveInvocationOutcome.INTERRUPTED, invocation.outcome)
        assertEquals(invocation.input.factors, invocation.output.factors)
        assertEquals(budget.spent(), invocation.work)
    }

    @Test
    fun `source refutations should retain their invocation outcome`() {
        val problem = Problem(
            0, 1, arrayOf(IntDomain(0, 2)),
            listOf(Linear(intArrayOf(2), intArrayOf(0), LinearOp.EQ, 1)),
        )
        val budget = PresolveBudget(Long.MAX_VALUE)

        val result = Presolver.runSource(
            problem, PresolveConfig.parse("off,+strengthen"), PresolveContext(presolveBudget = budget),
            budget.orSpent(Cancellation.Never),
        )

        assertTrue(result.infeasible)
        assertEquals(PresolveInvocationOutcome.REFUTED, budget.invocations.single().outcome)
    }

    @Test
    fun `construction tiers should retain namespace skips and probe cap interruptions`() {
        val budget = PresolveBudget(Long.MAX_VALUE)
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 2)), emptyList())
            .bake(budget.orSpent(Cancellation.Never))

        RootBaker.bake(problem, BakeConfig(
            probeFailedLiterals = true, probeIntBounds = true, probeIntHoles = true, probeTotalBudget = 0,
        ))

        assertEquals(listOf(
            PresolveInvocationOutcome.ELIGIBILITY_SKIP,
            PresolveInvocationOutcome.INTERRUPTED,
            PresolveInvocationOutcome.INTERRUPTED,
        ), budget.invocations.map { it.outcome })
        assertEquals(0L, budget.probeCalls)
    }

    @Test
    fun `empty external stages should survive the pipeline fixpoint exit`() {
        val budget = PresolveBudget(Long.MAX_VALUE)
        val problem = Problem(0, 0, emptyArray(), emptyList())

        PresolvePipeline.run(
            problem, null, PresolveConfig.parse("off,+binary-columns,+lp-harvest,+difference-system"),
            false, presolveBudget = budget,
        )

        assertEquals(listOf("binary-columns", "lp-harvest", "difference-system"), budget.invocations.map { it.pass })
        assertEquals(PresolveInvocationOutcome.ELIGIBILITY_SKIP, budget.invocations.first().outcome)
        assertEquals(PresolveInvocationOutcome.ELIGIBILITY_SKIP, budget.invocations.last().outcome)
        assertTrue(budget.invocations.all { it.input.factors == it.output.factors })
    }
    @Test
    fun `nested construction charges should reconcile without billing the parent twice`() {
        val budget = PresolveBudget(Long.MAX_VALUE)
        val problem = Problem(
            0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 1)),
        )

        val result = PresolvePipeline.run(
            problem, null, PresolveConfig.parse("off,+binary-columns,+probe-failed-literals"),
            false, presolveBudget = budget,
        )

        val substitution = budget.invocations.single { it.pass == "binary-columns" }
        assertEquals(PresolveInvocationOutcome.CHANGED, substitution.outcome)
        assertEquals(2, substitution.output.booleans - substitution.input.booleans)
        assertTrue(substitution.rebuildWork > 0L)
        assertTrue(budget.invocations.any { it.pass == "probe-failed-literals" && it.rebuildWork > 0L })
        assertTrue(budget.invocations.all { it.rebuildWork <= it.work })
        assertTrue(budget.invocations.sumOf { it.work } < budget.spent())
        assertTrue(result.changed)
    }

    @Test
    fun `unchanged finite schedules should reuse the source factor aggregate`() {
        val budget = PresolveBudget(Long.MAX_VALUE)
        val problem = Problem(
            0, 2, Array(2) { IntDomain(0, 2) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 3)),
        ).bake()
        val context = PresolveContext(objectiveIntCoeffs = mapOf(0 to 1L, 1 to 1L), presolveBudget = budget)
        val config = PresolveConfig.parse("off,+dup-columns")
        val token = budget.orSpent(Cancellation.Never)

        repeat(2) { Presolver.run(problem, config, context, token) }

        assertEquals(1L, budget.entrySizeVisits)
        assertTrue(budget.invocations.all { it.outcome == PresolveInvocationOutcome.COMPLETE_MISS })
        assertEquals(2, budget.invocations.size)
    }
    @Test
    fun `source reductions should report factor and occurrence yield from their delta`() {
        val budget = PresolveBudget(Long.MAX_VALUE)
        val problem = Problem(
            0, 1, arrayOf(IntDomain(0, 10)),
            List(2) { Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 7) },
        )

        Presolver.runSource(
            problem, PresolveConfig.parse("off,+subsume"), PresolveContext(presolveBudget = budget),
            budget.orSpent(Cancellation.Never),
        )

        val changed = budget.invocations.first()
        assertEquals(PresolveInvocationOutcome.CHANGED, changed.outcome)
        assertEquals(2, changed.input.factors)
        assertEquals(1, changed.output.factors)
        assertEquals(2L, changed.input.occurrences)
        assertEquals(1L, changed.output.occurrences)
    }
    @Test
    fun `an affine rerun without touched equality shapes should charge only candidate visits`() {
        val budget = PresolveBudget(Long.MAX_VALUE)
        val problem = Problem(
            0, 2, Array(2) { IntDomain(0, 2) },
            List(2) { Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 3) },
        ).bake()

        Presolver.run(
            problem, PresolveConfig.parse("off,+affine,+subsume"), PresolveContext(presolveBudget = budget),
            budget.orSpent(Cancellation.Never),
        )

        val calls = budget.invocations.filter { it.pass == "affine" }
        assertEquals(listOf(
            PresolveInvocationOutcome.COMPLETE_MISS,
            PresolveInvocationOutcome.ELIGIBILITY_SKIP,
        ), calls.map { it.outcome })
        assertEquals(2L, calls.last().work)
        assertEquals(null, calls.last().input.occurrences)
        assertEquals(2L, budget.entrySizeVisits)
    }
    @Test
    fun `effort snapshots should reconcile invocation work with shared construction`() {
        val budget = PresolveBudget(Long.MAX_VALUE)
        val config = PresolveConfig.parse("off,+binary-columns,+probe-failed-literals")
        val problem = Problem(
            0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 1)),
        )

        PresolvePipeline.run(problem, null, config, false, presolveBudget = budget)
        val effort = config.effortStats(budget, kotlin.time.Duration.ZERO)
        val work = effort.invocations.sumOf { it.work }

        assertEquals(budget.spent(), work + requireNotNull(effort.unattributedWork))
        assertTrue(requireNotNull(effort.unattributedWork) > 0L)
        assertEquals(budget.entrySizeVisits, effort.entrySizeVisits)
        assertEquals(budget.invocations.size, effort.invocations.size)
    }

    @Test
    fun `materializing a certified refutation should retain the phase construction meter`() {
        val budget = PresolveBudget(Long.MAX_VALUE)
        val problem = Problem(1, 0, emptyArray(), emptyList()).bake(budget.orSpent(Cancellation.Never))
        val construction = budget.rootConstructionEntries

        RootBaker.provenInfeasible(problem, BakeConfig.NONE)

        assertTrue(budget.rootConstructionEntries > construction)
    }
}
