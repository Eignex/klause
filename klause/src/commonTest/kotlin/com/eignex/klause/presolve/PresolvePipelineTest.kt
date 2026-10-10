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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PresolvePipelineTest {

    private fun isFeasible(problem: Problem, ints: LongArray): Boolean {
        var a = Assumptions.None
        for (v in 0 until problem.numIntVars) a = a.withInt(v, ints[v])
        return problem.propagate(a) !is PropagationResult.Unsat
    }

    /** Every int tuple inside [problem]'s declared domains. */
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
    fun `reconstruct lifts every solution of the transformed problem back to the original`() {
        val outcome = PresolvePipeline.run(model, null, PresolveConfig.AUTO, solutionSetSensitive = false)
        assertTrue(outcome.changed, "the fixture must actually be transformed for this to test anything")

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
        // `lifted > 0` plus the lift check covers reduced ⇒ original; this covers the other direction.
        assertEquals(
            boxPoints(model).any { isFeasible(model, it) },
            lifted > 0,
            "presolve changed satisfiability",
        )
    }

    @Test
    fun `a pass-proven infeasibility is reported in the stats`() {
        // 2x + 4y = 3: the coefficient gcd does not divide the bound, so a pass proves infeasibility
        // outright rather than leaving it to the bake.
        val unsat = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 4), IntDomain(0, 4)),
            factors = listOf(Linear(longArrayOf(2, 4), intArrayOf(0, 1), LinearOp.EQ, 3)),
        )
        val outcome = PresolvePipeline.run(unsat, null, PresolveConfig.AUTO, solutionSetSensitive = false)
        assertTrue(outcome.stats.infeasible, "the gcd rule proves the model infeasible")
        assertTrue(boxPoints(unsat).none { isFeasible(unsat, it) }, "the fixture really has no solution")
    }

    @Test
    fun `affine reconstruction covers exactly the independently declared source solutions`() {
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
    fun `projected enumeration after affine elimination emits each source solution once`() {
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
