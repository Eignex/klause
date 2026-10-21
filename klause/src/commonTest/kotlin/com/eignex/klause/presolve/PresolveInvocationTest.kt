package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.propagatorProjection
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.result.PresolveInvocationOutcome
import com.eignex.klause.solver.result.PresolveLane
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PresolveInvocationTest {
    @Test
    fun `unsupported clause analyses should skip before entry accounting in either lane`() {
        val problem = Problem(3, 0, emptyArray(), List(8) { Clause(intArrayOf(0, 2, 4)) })
        for (lane in PresolveLane.entries) {
            val budget = PresolveBudget(Long.MAX_VALUE)
            val token = budget.orSpent(Cancellation.Never)
            val config = PresolveConfig.parse("off,+probe-open,+fuse-bounds,+dependent-rows,+sparsify")
            val context = PresolveContext(presolveBudget = budget)

            when (lane) {
                PresolveLane.SOURCE -> Presolver.runSource(problem, config, context, token)
                PresolveLane.FINITE -> Presolver.run(problem.bake(), config, context, token)
            }

            assertEquals(4, budget.invocations.size)
            assertTrue(budget.invocations.all { it.outcome == PresolveInvocationOutcome.ELIGIBILITY_SKIP })
            assertTrue(budget.invocations.all { it.work == 0L && it.input.occurrences == null })
            assertEquals(0L, budget.entrySizeVisits)
        }
    }

    @Test
    fun `closed integer source probing should skip before entry accounting`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)),
            listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 7)))
        val budget = PresolveBudget(Long.MAX_VALUE)

        Presolver.runSource(problem, PresolveConfig.parse("off,+probe-open"),
            PresolveContext(presolveBudget = budget), budget.orSpent(Cancellation.Never))

        assertEquals(PresolveInvocationOutcome.ELIGIBILITY_SKIP, budget.invocations.single().outcome)
        assertEquals(0L, budget.spent())
        assertEquals(0L, budget.entrySizeVisits)
    }

    @Test
    fun `integer row analyses should retain productive calls in either lane`() {
        val equality = Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.EQ, 3)
        val cases = listOf(
            "fuse-bounds" to listOf(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 3),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 3),
            ),
            "dependent-rows" to listOf(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 3),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.EQ, 4),
                Linear(intArrayOf(1, 2, 1), intArrayOf(0, 1, 2), LinearOp.EQ, 7),
            ),
            "sparsify" to listOf(equality,
                Linear(intArrayOf(2, 2, 3), intArrayOf(0, 1, 2), LinearOp.LE, 7)),
        )
        for ((pass, factors) in cases) {
            for (lane in PresolveLane.entries) {
                val problem = Problem(0, 3, Array(3) { IntDomain(0, 10) }, factors)
                val budget = PresolveBudget(Long.MAX_VALUE)
                val context = PresolveContext(presolveBudget = budget)
                val token = budget.orSpent(Cancellation.Never)
                val config = PresolveConfig.parse("off,+$pass")

                when (lane) {
                    PresolveLane.SOURCE -> Presolver.runSource(problem, config, context, token)
                    PresolveLane.FINITE -> Presolver.run(problem.bake(), config, context, token)
                }

                assertEquals(PresolveInvocationOutcome.CHANGED, budget.invocations.first().outcome, "$pass $lane")
                assertTrue(budget.invocations.first().work > 0L)
            }
        }
    }

    @Test
    fun `probing without integer columns should retain constant row refutation`() {
        val problem = Problem(0, 0, emptyArray(), listOf(
            ExactLinear.of(intArrayOf(), emptyList(), LinearOp.LE, BigFraction.ONE.negated()),
        ))
        val budget = PresolveBudget(Long.MAX_VALUE)

        val result = Presolver.runSource(problem, PresolveConfig.parse("off,+probe"),
            PresolveContext(presolveBudget = budget), budget.orSpent(Cancellation.Never))

        assertTrue(result.infeasible)
        assertEquals(PresolveInvocationOutcome.REFUTED, budget.invocations.single().outcome)
    }

    @Test
    fun `clause subsumption eligibility should follow the supplied live factor count at its cap`() {
        val problem = Problem(3, 0, emptyArray(), listOf(Clause(intArrayOf(0, 2)), Clause(intArrayOf(0, 2, 4))))
        val pass = PresolvePass.SUBSUME_CLAUSES

        assertFalse(pass.namespaceEligible(problem, 200_001))
        assertTrue(pass.namespaceEligible(problem, 200_000))
    }

    @Test
    fun `finite clause subsumption should read clauses retained by earlier reductions`() {
        val shorter = Clause(intArrayOf(0, 2))
        val problem = Problem(3, 0, emptyArray(), listOf(shorter, Clause(intArrayOf(0, 2)), Clause(intArrayOf(0, 2, 4))))
        val budget = PresolveBudget(Long.MAX_VALUE)

        val result = Presolver.run(problem.bake(), PresolveConfig.parse("off,+subsume,+clause-subsume"),
            PresolveContext(presolveBudget = budget), budget.orSpent(Cancellation.Never))

        assertEquals(1, result.problem.factors.size)
        val call = budget.invocations.first { it.pass == "clause-subsume" }
        assertEquals(2, call.input.factors)
        assertEquals(PresolveInvocationOutcome.CHANGED, call.outcome)
    }

    @Test
    fun `aggregation without a pivot and two integer partners should skip entry analysis`() {
        for (numInts in 0..2) {
            for (lane in PresolveLane.entries) {
                val problem = Problem(
                    3, numInts, Array(numInts) { IntDomain(0, 10) },
                    listOf(Clause(IntArray(3) { Lit.make(it, true) })),
                )
                val budget = PresolveBudget(Long.MAX_VALUE)
                val context = PresolveContext(presolveBudget = budget)
                val config = PresolveConfig.parse("off,+aggregate")
                val token = budget.orSpent(Cancellation.Never)

                when (lane) {
                    PresolveLane.SOURCE -> Presolver.runSource(problem, config, context, token)
                    PresolveLane.FINITE -> Presolver.run(problem.bake(), config, context, token)
                }

                val invocation = budget.invocations.single()
                assertEquals(PresolveInvocationOutcome.ELIGIBILITY_SKIP, invocation.outcome)
                assertEquals(0L, invocation.work)
                assertEquals(0L, budget.entrySizeVisits)
            }
        }
    }

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
    fun `SAT passes should skip database reads when entry accounting exhausts the allowance`() {
        for (id in listOf("bve", "bce")) {
            for (lane in PresolveLane.entries) {
                var reads = 0
                val clause = Clause(intArrayOf(Lit.make(0, true)))
                val factor = object : Factor by clause, Propagator by clause.propagatorProjection() {
                    override val structuralKeyWeight: Int get() = 1
                    override val boolVars: IntArray
                        get() {
                            reads++
                            return intArrayOf(0)
                        }
                }
                val source = Problem(1, 0, emptyArray(), List(4) { factor })
                val problem = if (lane == PresolveLane.FINITE) source.bake() else source
                reads = 0
                val budget = PresolveBudget(1L)
                val context = PresolveContext(presolveBudget = budget)
                val config = PresolveConfig.parse("off,+$id")
                val token = budget.orSpent(Cancellation.Never)

                when (lane) {
                    PresolveLane.SOURCE -> Presolver.runSource(problem, config, context, token)
                    PresolveLane.FINITE -> Presolver.run(assertIs<BakedProblem>(problem), config, context, token)
                }

                assertEquals(0, reads, "$id $lane")
                val invocation = budget.invocations.single()
                assertEquals(PresolveInvocationOutcome.INTERRUPTED, invocation.outcome)
                assertEquals(84L, budget.spent())
                assertEquals(budget.spent(), invocation.work)
            }
        }
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
        assertEquals(2, substitution.fixedColumns)
        assertTrue(substitution.rebuildWork > 0L)
        assertTrue(budget.invocations.any { it.pass == "probe-failed-literals" && it.rebuildWork > 0L })
        assertTrue(budget.invocations.all { it.rebuildWork <= it.work })
        assertEquals(
            budget.spent(), budget.invocations.sumOf { it.work } + budget.preparationStages.sumOf { it.work },
        )
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

        assertEquals(
            budget.spent(), work + effort.preparationStages.sumOf { it.work } + requireNotNull(effort.unattributedWork),
        )
        assertEquals(0L, effort.unattributedWork)
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

    @Test
    fun `a complete miss that spends the final unit should remain complete`() {
        val problem = Problem(
            0, 1, arrayOf(IntDomain(0, 10)),
            listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 7)),
        )
        val config = PresolveConfig.parse("off,+strengthen")
        val reference = PresolveBudget(Long.MAX_VALUE)
        Presolver.runSource(
            problem, config, PresolveContext(presolveBudget = reference), reference.orSpent(Cancellation.Never),
        )
        val budget = PresolveBudget(reference.spent())

        Presolver.runSource(
            problem, config, PresolveContext(presolveBudget = budget), budget.orSpent(Cancellation.Never),
        )

        assertEquals(0L, budget.remaining())
        assertEquals(PresolveInvocationOutcome.COMPLETE_MISS, budget.invocations.single().outcome)
    }
}
