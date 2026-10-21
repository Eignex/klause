package com.eignex.klause.presolve

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.SearchOutcome
import com.eignex.klause.backtrack.projectedOutcomes
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.symmetry.SymmetryHandling
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.propagate
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.PresolveInvocationOutcome
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PresolvePipelineTest {
    @Test
    fun `automatic clausal satisfaction harvest should skip before LP construction`() {
        val problem = Problem(
            3, 0, emptyArray(),
            listOf(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(1, true), Lit.make(2, true))),
            ),
        )
        val config = PresolveConfig(
            PresolveEmphasis.AGGRESSIVE,
            overrides = PresolvePass.entries.filter { it != PresolvePass.LP_HARVEST }.associateWith { false },
        )
        val budget = PresolveBudget(Long.MAX_VALUE)

        val outcome = PresolvePipeline.run(problem, null, config, false, presolveBudget = budget)

        val invocation = budget.invocations.single()
        assertEquals("lp-harvest", invocation.pass)
        assertEquals(PresolveInvocationOutcome.ELIGIBILITY_SKIP, invocation.outcome)
        assertEquals(0L, invocation.work)
        assertEquals(null, invocation.input.occurrences)
        assertEquals(0L, budget.entrySizeVisits)
        assertEquals(0.0, outcome.stats.lpStats.rootPasses.sum)
        val solutions = BacktrackSolver(outcome.problem.bake()).enumerate().map {
            outcome.reconstruct(it).bools.toList()
        }.toSet()
        assertEquals(
            setOf(
                listOf(false, true, false),
                listOf(false, true, true),
                listOf(true, false, true),
                listOf(true, true, false),
                listOf(true, true, true),
            ),
            solutions,
        )
    }

    @Test
    fun `explicit clausal harvest should override the automatic gate under every preset`() {
        val problem = Problem(2, 0, emptyArray(), listOf(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))))
        for (emphasis in PresolveEmphasis.entries) {
            val config = PresolveConfig(
                emphasis,
                overrides = PresolvePass.entries.associateWith { it == PresolvePass.LP_HARVEST },
            )
            val budget = PresolveBudget(Long.MAX_VALUE)

            val outcome = PresolvePipeline.run(problem, null, config, false, presolveBudget = budget)

            assertEquals(PresolveInvocationOutcome.COMPLETE_MISS, budget.invocations.single().outcome)
            assertTrue(budget.invocations.single().work > 0L)
            assertTrue(outcome.stats.lpStats.rootPasses.sum > 0.0)
        }
    }

    @Test
    fun `propagator only symmetry should retain the automatic clausal harvest skip`() {
        val problem = Problem(
            3, 0, emptyArray(),
            listOf(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(1, true), Lit.make(2, true))),
            ),
        )
        val config = PresolveConfig(
            PresolveEmphasis.AGGRESSIVE,
            overrides = PresolvePass.entries
                .filter { it != PresolvePass.LP_HARVEST && it != PresolvePass.BREAK_SYMMETRIES }
                .associateWith { false },
        )
        val budget = PresolveBudget(Long.MAX_VALUE)

        val outcome = PresolvePipeline.run(problem, null, config, false, presolveBudget = budget)

        assertTrue(outcome.problem.factors.any { it is SymmetryHandling })
        val invocation = budget.invocations.single { it.pass == "lp-harvest" }
        assertEquals(PresolveInvocationOutcome.ELIGIBILITY_SKIP, invocation.outcome)
        assertEquals(0L, invocation.work)
        assertEquals(0.0, outcome.stats.lpStats.rootPasses.sum)
    }

    @Test
    fun `clause conversion should retain an opportunity for LP refutation`() {
        val problem = Problem(
            3, 0, emptyArray(),
            listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(2, false))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(1, true), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(2, true))),
            ),
        )
        val config = PresolveConfig(
            PresolveEmphasis.AGGRESSIVE,
            overrides = PresolvePass.entries
                .filter { it != PresolvePass.LP_HARVEST && it != PresolvePass.MERGE_AMO_CLIQUES }
                .associateWith { false },
        )
        val budget = PresolveBudget(Long.MAX_VALUE)

        val outcome = PresolvePipeline.run(problem, null, config, false, presolveBudget = budget)

        assertTrue(outcome.stats.infeasible)
        assertTrue(requireNotNull(outcome.stats.lpHarvest).rootInfeasible)
        assertEquals(
            PresolveInvocationOutcome.REFUTED,
            budget.invocations.first { it.pass == "lp-harvest" }.outcome,
        )
        assertTrue(outcome.stats.lpStats.rootPasses.sum > 0.0)
    }

    @Test
    fun `automatic nonclausal harvest should retain certified root refutations`() {
        val problems = listOf(
            Problem(
                0, 3, Array(3) { IntDomain(0, 10) },
                listOf(
                    Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 3),
                    Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 3),
                    Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 3),
                    Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 4),
                ),
            ),
            Problem(
                3, 0, emptyArray(),
                listOf(
                    Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                    Clause(intArrayOf(Lit.make(1, true), Lit.make(2, true))),
                    Clause(intArrayOf(Lit.make(0, true), Lit.make(2, true))),
                    Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true))),
                ),
            ),
        )
        val config = PresolveConfig(
            PresolveEmphasis.AGGRESSIVE,
            overrides = PresolvePass.entries.filter { it != PresolvePass.LP_HARVEST }.associateWith { false },
        )
        for (problem in problems) {
            val budget = PresolveBudget(Long.MAX_VALUE)

            val outcome = PresolvePipeline.run(problem, null, config, false, presolveBudget = budget)

            assertTrue(outcome.stats.infeasible)
            assertTrue(requireNotNull(outcome.stats.lpHarvest).rootInfeasible)
            assertEquals(PresolveInvocationOutcome.REFUTED, budget.invocations.first().outcome)
            assertTrue(outcome.stats.lpStats.rootPasses.sum > 0.0)
        }
    }

    @Test
    fun `automatic clausal optimization harvest should remain eligible`() {
        val problem = Problem(
            3, 0, emptyArray(),
            listOf(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(1, true), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(2, true))),
            ),
        )
        val objective = LinearObjective(boolWeights = longArrayOf(1, 1, 1))
        val config = PresolveConfig(
            PresolveEmphasis.AGGRESSIVE,
            overrides = PresolvePass.entries.filter { it != PresolvePass.LP_HARVEST }.associateWith { false },
        )
        val budget = PresolveBudget(Long.MAX_VALUE)

        val outcome = PresolvePipeline.run(problem, objective, config, false, presolveBudget = budget)

        assertEquals(PresolveInvocationOutcome.COMPLETE_MISS, budget.invocations.single().outcome)
        assertTrue(outcome.stats.lpStats.rootPasses.sum > 0.0)
        assertEquals(2, BacktrackSolver(outcome.problem.bake()).enumerate().minOf {
            outcome.reconstruct(it).bools.count { value -> value }
        })
    }

    @Test
    fun `automatic integer optimization harvest should retain objective bound tightening`() {
        val problem = Problem(
            0, 4,
            arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 3)),
            listOf(
                Linear(intArrayOf(1, -1, -1, -1), intArrayOf(3, 0, 1, 2), LinearOp.GE, 0),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
            ),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1))
        val config = PresolveConfig(
            PresolveEmphasis.AGGRESSIVE,
            overrides = PresolvePass.entries.filter { it != PresolvePass.LP_HARVEST }.associateWith { false },
        )

        val outcome = PresolvePipeline.run(problem, objective, config, false)

        assertEquals(2L, outcome.problem.finiteIntDomain(3).min)
        assertTrue(requireNotNull(outcome.stats.lpHarvest).objectiveLbRaised)
        assertTrue(outcome.stats.lpStats.rootPasses.sum > 0.0)
    }

    @Test
    fun `disabled elective harvest should retain wide integer prebake refutations`() {
        val problem = Problem(
            0, 2, Array(2) { IntDomain(0, 1_000_000_000) },
            listOf(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, -1),
                Linear(intArrayOf(1, -1), intArrayOf(1, 0), LinearOp.LE, -1),
            ),
        )

        val outcome = PresolvePipeline.run(
            problem, null, PresolveConfig.parse("off,-lp-harvest"), solutionSetSensitive = false,
        )

        assertTrue(outcome.stats.infeasible)
        assertEquals(1.0, outcome.stats.lpStats.rootPasses.sum)
    }

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
