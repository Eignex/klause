package com.eignex.klause.presolve

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.SearchOutcome
import com.eignex.klause.backtrack.projectedOutcomes
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.propagate
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PresolvePipelineTest {
    @Test
    fun `the pipeline should not charge another construction for an already baked input`() {
        val budget = PresolveBudget(Long.MAX_VALUE)
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyList())
            .bake(budget.orSpent(Cancellation.Never))
        val spent = budget.spent()

        PresolvePipeline.run(problem, null, PresolveConfig.NONE, false, presolveBudget = budget)

        assertEquals(spent, budget.spent())
        assertEquals(1L, budget.rootConstructionEntries)
    }

    @Test
    fun `isolated wide output should use root propagation without prebake LP`() {
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 1_000_000_000), IntDomain(0, 10), IntDomain(0, 10)),
            listOf(Linear(longArrayOf(1, -1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0)),
        )

        val outcome = PresolvePipeline.run(problem, null, PresolveConfig.NONE, solutionSetSensitive = false)

        assertEquals(20L, outcome.problem.bake().rootIntDomain(0).max)
        assertEquals(0.0, outcome.stats.lpStats.rootPasses.sum)
    }

    @Test
    fun `coupled wide columns should retain prebake LP bounds`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 1_000_000_000), IntDomain(0, 1_000_000_000)),
            listOf(
                Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 10),
                Linear(longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0),
            ),
        )

        val outcome = PresolvePipeline.run(problem, null, PresolveConfig.NONE, solutionSetSensitive = false)

        assertEquals(5L, outcome.problem.finiteIntDomain(0).max)
        assertEquals(5.0, outcome.stats.lpStats.rootPasses.sum)
    }

    private fun isFeasible(problem: Problem, ints: LongArray): Boolean {
        var a = Assumptions.None
        for (v in 0 until problem.numIntVars) a = a.withInt(v, ints[v])
        return problem.propagate(a) !is PropagationResult.Unsat
    }

    private fun boxPoints(problem: Problem): List<LongArray> {
        val n = problem.numIntVars
        val out = ArrayList<LongArray>()
        val ints = LongArray(n) { problem.finiteIntDomain(it).min }
        while (true) {
            out.add(ints.copyOf())
            var i = 0
            while (i < n) {
                ints[i]++
                if (ints[i] <= problem.finiteIntDomain(i).max) break
                ints[i] = problem.finiteIntDomain(i).min
                i++
            }
            if (i == n) break
        }
        return out
    }

    private val model = Problem(
        numBoolVars = 0,
        numIntVars = 3,
        intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
        factors = listOf(
            Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 3),
            Linear(longArrayOf(1, -1), intArrayOf(1, 2), LinearOp.LE, 1),
            Linear(longArrayOf(1), intArrayOf(2), LinearOp.GE, 1),
        ),
    )

    @Test
    fun `reconstruct should lift every solution of the transformed problem back to the original`() {
        val outcome = PresolvePipeline.run(model, null, PresolveConfig.AUTO, solutionSetSensitive = false)
        assertTrue(outcome.changed)

        var lifted = 0
        for (p in boxPoints(outcome.problem)) {
            if (!isFeasible(outcome.problem, p)) continue
            val recon = outcome.reconstruct(Sample(bools = BooleanArray(0), ints = p))
            assertTrue(
                isFeasible(model, recon.ints),
                "reconstruct produced ${recon.ints.toList()} which does not satisfy the original",
            )
            lifted++
        }
        assertEquals(
            boxPoints(model).any { isFeasible(model, it) },
            lifted > 0,
            "presolve changed satisfiability",
        )
    }

    @Test
    fun `a pass-proven infeasibility should be reported in the stats`() {
        val unsat = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 4), IntDomain(0, 4)),
            factors = listOf(Linear(longArrayOf(2, 4), intArrayOf(0, 1), LinearOp.EQ, 3)),
        )
        val outcome = PresolvePipeline.run(unsat, null, PresolveConfig.AUTO, solutionSetSensitive = false)
        assertTrue(outcome.stats.infeasible)
        assertTrue(boxPoints(unsat).none { isFeasible(unsat, it) })
    }

    @Test
    fun `affine reconstruction should cover exactly the declared source solutions`() {
        val objective = LinearObjective(intCoefficients = longArrayOf(0, 2, 0), constant = 5)
        val outcome = PresolvePipeline.run(model, objective, PresolveConfig.parse("affine"), false)
        val expected = boxPoints(model).filter { values ->
            values[0] + values[1] == 3L && values[1] - values[2] <= 1L && values[2] >= 1L
        }.map { it.toList() }.toSet()

        val actual = BacktrackSolver(outcome.problem.bake()).enumerate().map { reduced ->
            outcome.mapping.requireObjectivePreserved(objective, outcome.objective ?: objective, reduced)
            outcome.mapping.reconstructFrom(outcome.problem, reduced).ints.toList()
        }.toSet()

        assertTrue(outcome.mapping.rebuild.steps.any { it is RebuildStep.AffineValue })
        assertEquals(expected, actual)
    }

    @Test
    fun `projected enumeration after affine elimination should emit each source solution once`() {
        val outcome = PresolvePipeline.run(model, null, PresolveConfig.parse("affine"), false)
        val expected = boxPoints(model).filter { values ->
            values[0] + values[1] == 3L && values[1] - values[2] <= 1L && values[2] >= 1L
        }.map { it.toList() }.toSet()

        val outcomes = BacktrackSolver(outcome.problem.bake()).projectedOutcomes(
            BacktrackParams(),
            intArrayOf(),
            intArrayOf(0, 1, 2),
            outcome.mapping,
        ).toList()
        val actual = outcomes.filterIsInstance<SearchOutcome.Found>().map { it.sample.ints.toList() }

        assertEquals(expected, actual.toSet())
        assertEquals(expected.size, actual.size)
        assertTrue(outcomes.last() is SearchOutcome.Exhausted)
    }
}
