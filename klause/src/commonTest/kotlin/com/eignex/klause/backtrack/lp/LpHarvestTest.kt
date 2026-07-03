package com.eignex.klause.backtrack.lp

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * LP-relaxation harvest ([lpHarvest]) — folding the LP's proven variable-bound tightenings into the
 * problem's domains permanently. Asserts the gate (no shaving ⇒ no change) and the soundness invariant
 * (the harvested domains never exclude a feasible assignment), exercised over randomized linear systems
 * so the shave actually engages.
 */
class LpHarvestTest {

    private val shavingParams = BacktrackParams(lpPlan = LpPlan(bounding = true, variableShaving = true))
    private val objShavingParams = BacktrackParams(lpPlan = LpPlan(bounding = true, objectiveShaving = true))

    @Test
    fun `harvest is a no-op when variable shaving is off`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 4), IntDomain(0, 4)),
            arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 5)),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L, 1L))
        assertSame(
            problem,
            lpHarvest(problem, obj, BacktrackParams(lpPlan = LpPlan(bounding = true))),
            "with variable shaving disabled the harvest must return the problem unchanged",
        )
    }

    @Test
    fun `harvest tightens domains without excluding any feasible assignment`() {
        val rng = Random(20260701)
        var engaged = 0
        repeat(300) { _ ->
            val n = rng.nextInt(2, 4)
            val hi = rng.nextInt(2, 5)
            val domains = Array(n) { IntDomain(0, hi) }
            val factors = ArrayList<Factor>()
            repeat(rng.nextInt(1, 4)) { _ ->
                val coeffs = IntArray(n) { rng.nextInt(-2, 3) }
                if (coeffs.all { it == 0 }) return@repeat
                val rel = if (rng.nextBoolean()) LinearOp.LE else LinearOp.GE
                factors.add(Linear(coeffs, IntArray(n) { it }, rel, rng.nextInt(-hi, hi * n + 1)))
            }
            val problem = Problem(0, n, domains, factors.toTypedArray())
            val obj = LinearObjective(intCoefficients = LongArray(n) { 1L })
            val harvested = lpHarvest(problem, obj, shavingParams, Cancellation.Never)
            if (harvested === problem) return@repeat
            engaged++
            // Every assignment feasible under the original constraints must still lie inside the
            // harvested domains — the harvest may only remove proven-infeasible values.
            val point = IntArray(n)
            fun feasible(): Boolean = factors.filterIsInstance<Linear>().all { f ->
                var s = 0L
                for (i in f.vars.indices) s += f.coeffs[i].toLong() * point[f.vars[i]]
                when (f.op) {
                    LinearOp.LE -> s <= f.bound
                    LinearOp.GE -> s >= f.bound
                    else -> true
                }
            }
            fun rec(idx: Int) {
                if (idx == n) {
                    if (feasible()) {
                        for (v in 0 until n) {
                            assertTrue(
                                point[v] in harvested.intDomains[v],
                                "harvest excluded feasible x$v=${point[v]} from ${harvested.intDomains[v].min}..${
                                    harvested.intDomains[v].max
                                }",
                            )
                        }
                    }
                    return
                }
                for (value in 0..hi) {
                    point[idx] = value
                    rec(idx + 1)
                }
            }
            rec(0)
        }
        assertTrue(engaged > 0, "variable-shaving harvest never engaged across 300 instances")
    }

    @Test
    fun `objective-LB harvest raises the objective variable's lower bound`() {
        // minimise z = x0 + x1 + x2 with the three pairwise covers x_i + x_j >= 1 over binaries. No
        // single bound rises at the root, so z's declared min stays 0; shaving proves z >= 2 (any two
        // covers force a second one). Objective shaving alone (variable shaving off) must fold that in.
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
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(0L, 0L, 0L, 1L))
        val harvested = lpHarvest(problem, obj, objShavingParams, Cancellation.Never)
        assertTrue(harvested !== problem, "objective shaving proved a floor; the harvest must apply it")
        assertTrue(harvested.intDomains[3].min >= 2, "objective floor z>=2 not folded into the domain")
        assertTrue(2 in harvested.intDomains[3], "harvest excluded the attainable optimum z=2")
    }

    @Test
    fun `objective-LB harvest is a no-op for a maximised objective`() {
        // shaveObjectiveLb binds only an ascending objective, so a maximise (descending) objective
        // harvests no objective floor.
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 4), IntDomain(0, 4)),
            arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 5)),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(0L, -1L))
        assertSame(
            problem,
            lpHarvest(problem, obj, objShavingParams, Cancellation.Never),
            "a maximised objective yields no ascending floor, so the harvest is unchanged",
        )
    }

    @Test
    fun `harvested domains are no wider than the original`() {
        // A direct narrowing check on a small system: harvested bounds are always within the originals.
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 4), IntDomain(0, 4)),
            arrayOf<Factor>(
                Linear(intArrayOf(2, 1), intArrayOf(0, 1), LinearOp.GE, 3),
                Linear(intArrayOf(1, 2), intArrayOf(0, 1), LinearOp.GE, 3),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L, 1L))
        val harvested = lpHarvest(problem, obj, shavingParams, Cancellation.Never)
        for (v in 0 until problem.numIntVars) {
            assertTrue(harvested.intDomains[v].min >= problem.intDomains[v].min, "lower bound widened for x$v")
            assertTrue(harvested.intDomains[v].max <= problem.intDomains[v].max, "upper bound widened for x$v")
        }
    }

    @Test
    fun `harvest proves a propagation-feasible but LP-infeasible problem UNSAT`() {
        // x+y >= 3, y+z >= 3, x+z >= 3, x+y+z <= 4 over [0,10]: summing the three covers gives
        // 2(x+y+z) >= 9, i.e. x+y+z >= 4.5 > 4 — an LP/Farkas infeasibility. Bounds propagation tightens
        // no single variable past [0,4], so it does not catch it; the LP harvest must.
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
        )
        assertTrue(problem.baked !is PropagationResult.Unsat, "propagation alone must not catch this infeasibility")
        val harvested = lpHarvest(problem, LinearObjective(), shavingParams, Cancellation.Never)
        assertTrue(harvested.baked is PropagationResult.Unsat, "the LP-certified infeasibility must bake to Unsat")
    }

    @Test
    fun `harvest drops a constraint the LP proves implied by a combination of the others`() {
        // x+y<=3, y+z<=3, x+z<=3 sum to 2(x+y+z)<=9, i.e. x+y+z<=4.5, so x+y+z<=5 is redundant — implied
        // by a *combination* of the others (LP max 4.5), which bound propagation (each var only <=3 ⇒
        // sum <=9) and single-constraint subsumption both miss. Needs the simplex over the kept rows.
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 10), IntDomain(0, 10), IntDomain(0, 10)),
            arrayOf<Factor>(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 3),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.LE, 3),
                Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.LE, 3),
                Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 5), // implied by the three above
            ),
        )
        val harvested = lpHarvest(problem, LinearObjective(), shavingParams, Cancellation.Never)
        assertTrue(harvested.factors.none { it is Linear && it.vars.size == 3 }, "the implied x+y+z<=5 must be dropped")
        assertEquals(3, harvested.factors.count { it is Linear }, "the three irredundant pairwise covers stay")
        assertSameFeasibleSet(problem, harvested, hi = 10)
    }

    @Test
    fun `harvest drops a lower-bound constraint the LP proves implied by a combination of the others`() {
        // x+y>=3, y+z>=3, x+z>=3 sum to 2(x+y+z)>=9, i.e. x+y+z>=4.5, so x+y+z>=4 is redundant — implied by
        // the *min* over the others (LP min 4.5), the >= mirror of the <= combination case. Each var is only
        // forced >=0 by propagation, so neither it nor single-constraint subsumption catches the GE row.
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 10), IntDomain(0, 10), IntDomain(0, 10)),
            arrayOf<Factor>(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 3),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 3),
                Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 3),
                Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.GE, 4), // implied by the three above
            ),
        )
        val harvested = lpHarvest(problem, LinearObjective(), shavingParams, Cancellation.Never)
        assertTrue(harvested.factors.none { it is Linear && it.vars.size == 3 }, "the implied x+y+z>=4 must be dropped")
        assertEquals(3, harvested.factors.count { it is Linear }, "the three irredundant pairwise covers stay")
        assertSameFeasibleSet(problem, harvested, hi = 10)
    }

    @Test
    fun `harvest adds an equality the LP proves pins a difference to a constant`() {
        // x<=y, y<=z, z<=x chain to x=y=z, so x-y is pinned to 0 — provable only by *combining* the rows
        // (transitivity), which bound propagation does not derive as a relation. The harvest emits the
        // proven `=` so the next round's affine elimination can substitute it out.
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 5), IntDomain(0, 5), IntDomain(0, 5)),
            arrayOf<Factor>(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0), // x - y <= 0
                Linear(intArrayOf(1, -1), intArrayOf(1, 2), LinearOp.LE, 0), // y - z <= 0
                Linear(intArrayOf(1, -1), intArrayOf(2, 0), LinearOp.LE, 0), // z - x <= 0
            ),
        )
        val harvested = lpHarvest(problem, LinearObjective(), shavingParams, Cancellation.Never)
        assertTrue(
            harvested.factors.any { it is Linear && it.op == LinearOp.EQ && it.vars.size == 2 },
            "the LP-pinned difference must be added as a two-term equality",
        )
        assertSameFeasibleSet(problem, harvested, hi = 5)
    }

    @Test
    fun `harvest report breaks out the LP's own contribution`() {
        // The same combination-redundant system as above: the report must attribute the dropped row to the
        // LP harvest specifically (one redundant constraint), isolating it from the combinatorial passes.
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
        )
        val report = lpHarvestReporting(problem, LinearObjective(), shavingParams, Cancellation.Never).report
        assertEquals(1, report.constraintsRemoved, "the LP-redundant row must be counted")
        assertTrue(!report.rootInfeasible && report.equalitiesAdded == 0, "no other LP action fired here")
        assertTrue(!report.skipped && report.relaxationNnz > 0, "the built relaxation's size must be reported")
    }

    @Test
    fun `harvest report flags a root-infeasible relaxation`() {
        // The LP-infeasible cover system: the report must record that the LP certified root infeasibility.
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
        )
        val report = lpHarvestReporting(problem, LinearObjective(), shavingParams, Cancellation.Never).report
        assertTrue(report.rootInfeasible, "root-LP infeasibility must be recorded in the report")
    }

    @Test
    fun `redundant-constraint removal preserves the feasible set`() {
        val rng = Random(20260702)
        var dropped = 0
        repeat(300) { _ ->
            val n = rng.nextInt(2, 4)
            val hi = rng.nextInt(2, 5)
            val domains = Array(n) { IntDomain(0, hi) }
            val factors = ArrayList<Factor>()
            repeat(rng.nextInt(2, 5)) { _ ->
                val coeffs = IntArray(n) { rng.nextInt(0, 3) }
                if (coeffs.all { it == 0 }) return@repeat
                factors.add(Linear(coeffs, IntArray(n) { it }, LinearOp.LE, rng.nextInt(0, hi * n + 1)))
            }
            val problem = Problem(0, n, domains, factors.toTypedArray())
            val harvested = lpHarvest(problem, LinearObjective(), shavingParams, Cancellation.Never)
            if (harvested.factors.size < problem.factors.size) dropped++
            assertSameFeasibleSet(problem, harvested, hi)
        }
        assertTrue(dropped > 0, "redundant-constraint removal never engaged across 300 instances")
    }

    /** Assert [original] and [harvested] admit exactly the same points of the declared box `[0, hi]^n` —
     *  membership is domains AND the [Linear] factors, so it catches a row dropped (or an equality added)
     *  without its effect surviving in the bounds. */
    private fun assertSameFeasibleSet(original: Problem, harvested: Problem, hi: Int) {
        val n = original.numIntVars
        val point = IntArray(n)
        fun feasible(p: Problem): Boolean {
            for (v in 0 until n) if (point[v] !in p.intDomains[v]) return false
            return p.factors.filterIsInstance<Linear>().all { f ->
                var s = 0L
                for (i in f.vars.indices) s += f.coeffs[i].toLong() * point[f.vars[i]]
                when (f.op) {
                    LinearOp.LE -> s <= f.bound
                    LinearOp.GE -> s >= f.bound
                    LinearOp.EQ -> s == f.bound.toLong()
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
