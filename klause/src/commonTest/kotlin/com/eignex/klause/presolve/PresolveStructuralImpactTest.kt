package com.eignex.klause.presolve

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.linear.AffineSingletons
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.propagate
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.PresolveLane
import com.eignex.klause.solver.result.PresolveSizeStats
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PresolveStructuralImpactTest {
    @Test
    fun `structure control should retain table coordinates through every host and reconstruct source solutions`() {
        for (lane in listOf("source", "incremental", "rebuilt")) {
            for (preserve in listOf(false, true)) {
                val source = Problem(0, 4,
                    arrayOf(IntDomain(1, 3), IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
                    listOf(
                        Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 1),
                        Table(intArrayOf(0, 2), longArrayOf(1, 0, 2, 1, 3, 2)),
                        Table(intArrayOf(1, 3), longArrayOf(0, 0, 1, 1, 2, 2)),
                    ))
                val budget = PresolveBudget(1_000_000)
                val token = budget.orSpent(Cancellation.Never)
                val config = PresolveConfig.parse(if (lane == "rebuilt") "off,+affine,+bce" else "off,+affine")
                    .withEffort(preserveStructure = preserve, structuralImpactLimit = 1024)
                val context = PresolveContext(presolveBudget = budget)
                val reduced: Problem
                val reconstruct: (Sample) -> Sample
                if (lane == "source") {
                    val result = Presolver.runSource(source, config, context, token)
                    reduced = result.problem
                    reconstruct = result.rebuild.asSampleLift() ?: { it }
                } else {
                    val result = Presolver.run(source.bake(), config, context, token)
                    reduced = result.problem
                    reconstruct = result.reconstruct
                }
                assertEquals(preserve, reduced.factors.any { it === source.factors[1] }, "$lane $preserve")
                val solved = assertIs<SolveResult.Sat>(BacktrackSolver(reduced.bake()).solve(BacktrackParams()))
                val point = reconstruct(solved.assignment)
                var pins = Assumptions.None
                for (v in point.ints.indices) pins = pins.withInt(v, point.ints[v])
                assertFalse(source.propagate(pins) is PropagationResult.Unsat, "$lane $preserve")
                if (!preserve) {
                    val impact = budget.invocations.first { it.pass == "affine" && it.structuralImpact.isNotEmpty() }
                        .structuralImpact
                    assertEquals(1L, impact["Complete"])
                    assertTrue((impact["RemovedTableCells"] ?: 0L) > 0L)
                    assertTrue((impact["ReconstructionDefinitions"] ?: 0L) > 0L)
                }
            }
        }
    }

    @Test
    fun `structure control should preserve binary partitions without suppressing unrelated affine rows`() {
        val source = Problem(0, 4,
            arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(2, 4), IntDomain(1, 3)),
            listOf(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 1),
                Linear(intArrayOf(1, -1), intArrayOf(2, 3), LinearOp.EQ, 1),
            ))
        val config = PresolveConfig.parse("off,+affine").withEffort(preserveStructure = true)

        val result = Presolver.runSource(source, config)

        assertTrue(result.problem.factors.any { it === source.factors[0] })
        assertFalse(result.problem.factors.any { it === source.factors[1] })
    }

    @Test
    fun `structural control should retain a table that ordinary reduction rewrites`() {
        val source = Problem(0, 2, arrayOf(IntDomain(0, 0), IntDomain(0, 2)),
            listOf(Table(intArrayOf(0, 1), longArrayOf(0, 1, 1, 2))))
        for (lane in PresolveLane.entries) for (preserve in listOf(false, true)) {
            val config = PresolveConfig.parse("off,+structural").withEffort(preserveStructure = preserve)

            val result = if (lane == PresolveLane.SOURCE) Presolver.runSource(source, config).problem
            else Presolver.run(source.bake(), config).problem

            assertEquals(preserve, result.factors.any { it is Table }, "$lane $preserve")
        }
    }

    @Test
    fun `impact visits should be capped and retain an explicitly incomplete observation`() {
        val row = Linear(IntArray(32) { 1 }, IntArray(32) { it }, LinearOp.EQ, 1)
        val source = Problem(0, 32, Array(32) { IntDomain(0, 1) }, listOf(row))
        val invocation = PresolveInvocation("affine", PresolveLane.SOURCE, PresolveSizeStats(1, 0, 32))
        val budget = PresolveBudget(1000)

        invocation.recordStructuralImpact(source, intArrayOf(0), emptyList(), SourceRebuilds.NONE,
            5, budget.orSpent(Cancellation.Never))

        assertEquals(5L, budget.spent())
        assertEquals(5L, invocation.structuralImpact["Visits"])
        assertEquals(0L, invocation.structuralImpact["Complete"])
    }

    @Test
    fun `structure protection should include exactly substitutable objective coordinates`() {
        val source = Problem(0, 3, arrayOf(IntDomain(1, 3), IntDomain(0, 2), IntDomain(0, 2)),
            listOf(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 1),
                Table(intArrayOf(0, 2), longArrayOf(1, 0, 2, 1, 3, 2)),
                Table(intArrayOf(1, 2), longArrayOf(0, 0, 1, 1, 2, 2)),
            ))
        val objective = LinearObjective(
            intCoefficients = longArrayOf(1, 0, 0),
        )
        val context = PresolveContext.of(objective, transformObjective = true)
        val config = PresolveConfig.parse("off,+affine").withEffort(preserveStructure = true)

        val result = Presolver.runSource(source, config, context)

        assertTrue(result.rebuild.isEmpty)
        assertTrue(result.problem === source)
    }

    @Test
    fun `interrupted protection discovery should publish no affine elimination`() {
        val source = Problem(0, 2, arrayOf(IntDomain(1, 3), IntDomain(0, 2)),
            listOf(Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 1)))
        val budget = PresolveBudget(1)

        val delta = AffineSingletons.eliminateSourceAffineSingletons(
            source, cancellation = budget.orSpent(Cancellation.Never), preserveStructure = true,
        )

        assertTrue(delta.isEmpty)
        assertTrue(budget.spent() >= 1L)
    }

    @Test
    fun `impact scan should honor the containing phase stop before its own cap`() {
        val row = Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 1)
        val source = Problem(0, 2, Array(2) { IntDomain(0, 1) }, listOf(row))
        val invocation = PresolveInvocation("affine", PresolveLane.SOURCE, PresolveSizeStats(1, 0, 2))
        val budget = PresolveBudget(2)

        invocation.recordStructuralImpact(source, intArrayOf(0), emptyList(), SourceRebuilds.NONE,
            100, budget.orSpent(Cancellation.Never))

        assertEquals(2L, budget.spent())
        assertEquals(0L, invocation.structuralImpact["Complete"])
    }

    @Test
    fun `disabled impact should perform no diagnostic work`() {
        val source = Problem(0, 0, emptyArray(), emptyList())
        val invocation = PresolveInvocation("structural", PresolveLane.SOURCE, PresolveSizeStats(0, 0, 0))
        val budget = PresolveBudget(1000)

        invocation.recordStructuralImpact(source, intArrayOf(), emptyList(), SourceRebuilds.NONE,
            0, budget.orSpent(Cancellation.Never))

        assertEquals(0L, budget.spent())
        assertTrue(invocation.structuralImpact.isEmpty())
    }
}
