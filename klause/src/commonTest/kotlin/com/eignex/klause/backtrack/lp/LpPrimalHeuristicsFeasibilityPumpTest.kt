package com.eignex.klause.backtrack.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LpPrimalHeuristicsFeasibilityPumpTest {

    private fun satisfies(f: Linear, x: LongArray): Boolean {
        var s = 0L
        for (i in f.vars.indices) s += checkNotNull(f.integerConstants).coeffs[i] * x[f.vars[i]]
        return when (f.op) {
            LinearOp.LE -> s <= checkNotNull(f.integerConstants).bound
            LinearOp.GE -> s >= checkNotNull(f.integerConstants).bound
            LinearOp.EQ -> s == checkNotNull(f.integerConstants).bound
            else -> true
        }
    }

    @Test
    fun `the pump returns only feasible incumbents`() {
        val rng = Random(20260623)
        var produced = 0
        repeat(60) {
            val n = rng.nextInt(3, 7)
            val domains = Array(n) { IntDomain(0, 1) }
            val factors = ArrayList<Factor>()
            repeat(rng.nextInt(1, 4)) {
                val k = rng.nextInt(2, n + 1)
                val vars = (0 until n).shuffled(rng).take(k).toIntArray()
                val coeffs = IntArray(k) { 1 }
                // Covering (≥1, satisfiable by ones) or packing (≤k-1, satisfiable by zeros).
                if (rng.nextBoolean()) {
                    factors.add(Linear(coeffs, vars, LinearOp.GE, 1))
                } else {
                    factors.add(Linear(coeffs, vars, LinearOp.LE, k - 1))
                }
            }
            val p = Problem(0, n, domains, factors.toTypedArray())
            val obj = LinearObjective(intCoefficients = LongArray(n) { rng.nextLong(-2, 3) })
            val engine = LpEngine(
                p,
                obj,
                LpParams(lpPlan = LpPlan(bounding = true, probe = true)),
                SolveStatsSink(backend = "pump-test"),
            )
            val sample = engine.lpFeasibilityPump(obj, Cancellation.Never) ?: return@repeat // infeasible/failed: skip
            produced++
            for (f in p.factors.filterIsInstance<Linear>()) {
                assertTrue(satisfies(f, sample.ints), "pump returned an infeasible incumbent ${sample.ints.toList()}")
            }
        }
        assertTrue(produced > 10, "the pump produced only $produced incumbents across 60 instances")
    }

    @Test
    fun `the pump changes an integral rounding the consumer declines`() {
        for (coefficient in listOf(1L, -1L)) {
            val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val objective = LinearObjective(intCoefficients = longArrayOf(coefficient))
            val sink = SolveStatsSink(backend = "pump-test")
            var declined: Long? = null

            val sample = LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink).use { engine ->
                assertNotNull(engine.lpFeasibilityPump(objective, Cancellation.Never) { sample ->
                    if (declined == null) declined = sample.ints.single()
                    sample.takeIf { it.ints.single() != declined }
                })
            }

            assertNotEquals(declined, sample.ints.single())
        }
    }

    @Test
    fun `the pump explores from an initial discrete target`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) }, emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1))
        val initial = Sample(BooleanArray(0), longArrayOf(1, 1))
        val proposed = ArrayList<List<Long>>()
        val sink = SolveStatsSink(backend = "pump-test")

        val sample = LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink).use { engine ->
            assertNotNull(engine.lpFeasibilityPump(objective, Cancellation.Never, initial = initial) { sample ->
                proposed += sample.ints.toList()
                sample.takeIf { it.ints.toList() != initial.ints.toList() }
            })
        }

        assertEquals(initial.ints.toList(), proposed.first())
        assertNotEquals(initial.ints.toList(), sample.ints.toList())
    }

    @Test
    fun `directed rounding reuses its LP solve after a declined mixed proposal`() {
        val problem = Problem(
            0, 1, arrayOf(IntDomain(0, 5)),
            arrayOf<Factor>(
                Linear(intArrayOf(0), doubleArrayOf(1.0),
                    intArrayOf(0), doubleArrayOf(-1.0), LinearOp.GE, 0.0),
                Linear(IntArray(0), DoubleArray(0),
                    intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 0.4),
            ),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val sink = SolveStatsSink(backend = "rounding-test")
        val proposed = ArrayList<Long>()

        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink).use { engine ->
            assertNotNull(engine.lpRoundingProbe(objective, Cancellation.Never) { sample ->
                proposed += sample.ints.single()
                sample.takeIf { it.ints.single() == 1L }
            })
        }

        assertEquals(listOf(0L, 1L), proposed)
        assertEquals(1.0, sink.snapshot().lp.rootPasses.sum)
    }

    @Test
    fun `the rounding probe attributes its solve to root work`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 1), IntDomain(0, 1)),
            arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1))
        val sink = SolveStatsSink(backend = "rounding-test")
        val engine = LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink)

        assertNotNull(engine.lpRoundingProbe(objective, Cancellation.Never))

        assertTrue(sink.snapshot().lp.rootPasses.sum > 0.0)
    }
}
