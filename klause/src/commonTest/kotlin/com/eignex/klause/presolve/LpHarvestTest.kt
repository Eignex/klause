package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.RootDomains
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.baked
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpHarvestTest {
    private val shavingParams = LpPlan(bounding = true, variableShaving = true)
    private val objShavingParams = LpPlan(bounding = true, objectiveShaving = true)

    @Test
    fun `a no op harvest should retain finite templates for the engine bake`() {
        val source = Problem(0, 4, Array(4) { IntDomain(0, 10) }, arrayOf<Factor>(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 3),
            Linear(intArrayOf(1, 1), intArrayOf(2, 3), LinearOp.GE, 3),
        ))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1, 1, 1))
        val selection = FactorProjectionProviders.Default.prepare(source)
        val config = PresolveConfig.parse("off,+lp-harvest")
        val prepared = PresolvePipeline.prepareSource(source, config, objective, finiteLane = true)

        val outcome = PresolvePipeline.run(prepared, objective, config, false, projections = selection)
        val engineProblem = source.bake(selection)
        val consumer = CpToLpRelaxation(engineProblem, objective)
        consumer.build(RootDomains(engineProblem))

        assertSame(source, outcome.problem)
        assertFalse(outcome.changed)
        assertTrue(assertNotNull(consumer.preparationTemplates).reboundSource)
        assertTrue(assertNotNull(consumer.preparationTemplates).reusedFactors > 0)
        assertTrue(assertNotNull(consumer.preparationTemplates).reusedBounds > 0)
    }

    @Test
    fun `a consuming LP should reuse templates across incremental presolve preparation`() {
        val source = Problem(0, 4, Array(4) { IntDomain(0, 10) }, arrayOf<Factor>(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 3),
            Linear(intArrayOf(1, 1), intArrayOf(2, 3), LinearOp.GE, 3),
        ))
        val problem = source.bake(FactorProjectionProviders.Default.prepare(source))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1, 1, 1))
        val plan = LpPlan(bounding = true, componentOwnership = true)
        val first = lpHarvestReporting(problem, objective, plan)
        val session = PresolveSession(first.problem)
        session.apply(PresolveDelta(domains = arrayOf(IntDomain(1, 10), IntDomain(0, 10),
            IntDomain(0, 10), IntDomain(0, 10))))
        val prepared = session.materialize()
        val second = lpHarvestReporting(prepared, objective, plan)
        val sink = SolveStatsSink("template-consumer")

        val pruned = LpEngine(second.problem, objective, LpParams(lpPlan = plan), sink).use { engine ->
            engine.pruneNode(PropagationSession(second.problem), 5.0, -1, true)
        }

        assertTrue(pruned)
        assertTrue(second.stats.phases.filterKeys { it.startsWith("TEMPLATE_PREPARATION_") }.values
            .sumOf { it.outcomes["FACTORS_REUSED"] ?: 0L } > 0L)
        assertTrue(sink.snapshot().lp.phases.filterKeys { it.startsWith("TEMPLATE_PREPARATION_") }.values
            .sumOf { it.outcomes["FACTORS_REUSED"] ?: 0L } > 0L)
        assertTrue(sink.snapshot().lp.phases.filterKeys { it.startsWith("TEMPLATE_ASSEMBLY_") }.values
            .sumOf { it.outcomes["ROWS"] ?: 0L } > 0L)
        assertTrue(sink.snapshot().lp.phases.filterKeys { it.startsWith("TEMPLATE_ASSEMBLY_") }.values
            .sumOf { it.outcomes["EXACT_BOUND_OBJECTS"] ?: 0L } > 0L)
    }

    @Test
    fun `objective shaving should raise the objective variable lower bound`() {
        // No single bound rises at the root, but shaving proves z >= 2.
        val problem = Problem(
            0,
            4,
            arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 3)),
            arrayOf<Factor>(
                Linear(intArrayOf(1, -1, -1, -1), intArrayOf(3, 0, 1, 2), LinearOp.GE, 0),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
            ),
        ).bake()
        val obj = LinearObjective(intCoefficients = longArrayOf(0L, 0L, 0L, 1L))
        val harvested = lpHarvest(problem, obj, objShavingParams, cancellation = Cancellation.Never)
        assertTrue(harvested !== problem)
        assertTrue(harvested.finiteIntDomain(3).min >= 2)
        assertTrue(2 in harvested.finiteIntDomain(3))
    }

    @Test
    fun `objective shaving should leave a maximised objective unchanged`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 4), IntDomain(0, 4)),
            arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 5)),
        ).bake()
        val obj = LinearObjective(intCoefficients = longArrayOf(0L, -1L))
        assertSame(
            problem,
            lpHarvest(problem, obj, objShavingParams, cancellation = Cancellation.Never),
        )
    }

    @Test
    fun `harvest should prove a propagation-feasible but LP-infeasible problem UNSAT`() {
        // Summing the covers gives x+y+z >= 4.5 > 4, while no single bound contradicts.
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 10), IntDomain(0, 10), IntDomain(0, 10)),
            arrayOf<Factor>(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 3),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 3),
                Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 3),
                Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 4),
            ),
        ).bake()
        assertTrue(problem.baked !is PropagationResult.Unsat)
        val harvested = lpHarvest(problem, LinearObjective(), shavingParams, cancellation = Cancellation.Never)
        assertTrue(harvested.baked is PropagationResult.Unsat)
    }

    @Test
    fun `harvest should drop a row implied by a combination of the others`() {
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 10), IntDomain(0, 10), IntDomain(0, 10)),
            arrayOf<Factor>(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 3),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.LE, 3),
                Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.LE, 3),
                Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 5),
            ),
        ).bake()
        val harvested = lpHarvest(problem, LinearObjective(), shavingParams, cancellation = Cancellation.Never)
        assertTrue(harvested.factors.none { it is Linear && it.vars.size == 3 })
        assertEquals(3, harvested.factors.count { it is Linear })
        assertSameFeasibleSet(problem, harvested, hi = 10)
    }

    @Test
    fun `harvest should add an equality for a difference the LP pins to a constant`() {
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 5), IntDomain(0, 5), IntDomain(0, 5)),
            arrayOf<Factor>(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0),
                Linear(intArrayOf(1, -1), intArrayOf(1, 2), LinearOp.LE, 0),
                Linear(intArrayOf(1, -1), intArrayOf(2, 0), LinearOp.LE, 0),
            ),
        ).bake()
        val harvested = lpHarvest(problem, LinearObjective(), shavingParams, cancellation = Cancellation.Never)
        assertTrue(
            harvested.factors.any { it is Linear && it.op == LinearOp.EQ && it.vars.size == 2 },
        )
        assertSameFeasibleSet(problem, harvested, hi = 5)
    }

    private fun coverSystem(): Problem = Problem(
        0,
        3,
        arrayOf(IntDomain(0, 10), IntDomain(0, 10), IntDomain(0, 10)),
        arrayOf<Factor>(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 3),
            Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 3),
            Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 3),
            Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 4),
        ),
    )

    @Test
    fun `harvest should stop when the budget expires inside the simplex`() {
        val problem = coverSystem().bake()
        var polls = 0
        val spent = Cancellation { polls++ > 0 }

        val result = lpHarvestReporting(problem, LinearObjective(), shavingParams, cancellation = spent)

        assertFalse(result.report.rootInfeasible)
        assertSame(problem, result.problem)
    }

    @Test
    fun `the root feasibility LP should stop once its charged work spends the budget`() {
        val budget = PresolveBudget(1L)

        val result = lpRootInfeasibleReporting(
            coverSystem(),
            LinearObjective(),
            LpPlan(bounding = true),
            budget.orSpent(Cancellation.Never),
        )

        assertFalse(result.infeasible)
    }

    @Test
    fun `lpRootInfeasible should certify a wide difference cycle without baking`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 1_000_000_000), IntDomain(0, 1_000_000_000)),
            arrayOf<Factor>(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, -1),
                Linear(intArrayOf(1, -1), intArrayOf(1, 0), LinearOp.LE, -1),
            ),
        )
        val result = lpRootInfeasibleReporting(problem, LinearObjective(), LpPlan(bounding = true))

        assertTrue(result.infeasible)
        assertTrue(result.stats.rootPasses.sum > 0.0)
    }

    @Test
    fun `lpRootBounds should tighten a coupled domain beyond propagation`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 1_000_000_000), IntDomain(0, 1_000_000_000)),
            arrayOf<Factor>(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 10),
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0),
            ),
        ).bake()
        val result = lpRootBoundsReporting(problem, LinearObjective(), LpPlan(bounding = true))

        assertTrue(result.problem !== problem)
        assertEquals(5L, result.problem.finiteIntDomain(0).max)
        assertTrue(result.stats.rootPasses.sum > 0.0)
    }

    @Test
    fun `lpRootBounds should tighten a wide domain past its solve allowance in index order`() {
        val padding = 80
        val domains = Array(padding + 2) { if (it < padding) IntDomain(0, 1) else IntDomain(0, 1_000_000_000) }
        val x = padding
        val y = padding + 1
        val problem = Problem(
            0,
            padding + 2,
            domains,
            arrayOf<Factor>(
                Linear(intArrayOf(1, 1), intArrayOf(x, y), LinearOp.LE, 10),
                Linear(intArrayOf(1, -1), intArrayOf(x, y), LinearOp.LE, 0),
            ),
        ).bake()
        val tightened = lpRootBounds(problem, LinearObjective(), LpPlan(bounding = true))
        assertEquals(5L, tightened.finiteIntDomain(x).max)
    }

    @Test
    fun `prebake bounds should only probe domains wider than the span gate`() {
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 1_000_000_000), IntDomain(0, 1_000_000_000), IntDomain(0, 10)),
            arrayOf<Factor>(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 10),
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0),
                Linear(intArrayOf(1), intArrayOf(2), LinearOp.LE, 5),
            ),
        )

        val result = lpRootBoundsReporting(
            problem,
            LinearObjective(),
            LpPlan(bounding = true),
            minimumSpan = 1_000_000,
        )

        assertEquals(5L, result.problem.finiteIntDomain(0).max)
        assertEquals(10L, result.problem.finiteIntDomain(2).max)
        assertEquals(4.0, result.stats.rootPasses.sum)
    }

    private fun assertSameFeasibleSet(original: Problem, harvested: Problem, hi: Int) {
        val n = original.numIntVars
        val point = IntArray(n)
        fun feasible(p: Problem): Boolean {
            for (v in 0 until n) if (point[v].toLong() !in p.finiteIntDomain(v)) return false
            return p.factors.filterIsInstance<Linear>().all { f ->
                var s = 0L
                for (i in f.vars.indices) s += checkNotNull(f.integerConstants).coeffs[i] * point[f.vars[i]]
                when (f.op) {
                    LinearOp.LE -> s <= checkNotNull(f.integerConstants).bound
                    LinearOp.GE -> s >= checkNotNull(f.integerConstants).bound
                    LinearOp.EQ -> s == checkNotNull(f.integerConstants).bound
                    else -> true
                }
            }
        }
        fun rec(idx: Int) {
            if (idx == n) {
                assertEquals(feasible(original), feasible(harvested), "feasibility differs at ${point.toList()}")
                return
            }
            for (value in 0..hi) {
                point[idx] = value
                rec(idx + 1)
            }
        }
        rec(0)
    }
}
