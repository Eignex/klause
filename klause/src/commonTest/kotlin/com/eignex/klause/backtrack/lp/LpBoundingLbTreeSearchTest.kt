package com.eignex.klause.backtrack.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The shared tree-search heuristic [lbTreeSearch] proposes propagation-feasible assignments.
 * The randomized corpus is exercised by the explicit JVM integration task.
 */
class LpBoundingLbTreeSearchTest {

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

    private fun engine(p: Problem, obj: LinearObjective) =
        LpEngine(p, obj, LpParams(lpPlan = LpPlan(bounding = true)), SolveStatsSink(backend = "lbtree"))

    fun `the subsolver returns only feasible incumbents`() {
        val rng = Random(20260625)
        var produced = 0
        repeat(300) { _ ->
            val n = rng.nextInt(3, 7)
            val domains = Array(n) { IntDomain(0, 1) }
            val factors = ArrayList<Factor>()
            repeat(rng.nextInt(1, 4)) { _ ->
                val k = rng.nextInt(2, n + 1)
                val vars = (0 until n).shuffled(rng).take(k).toIntArray()
                val coeffs = IntArray(k) { 1 }
                if (rng.nextBoolean()) {
                    factors.add(Linear(coeffs, vars, LinearOp.GE, 1)) // covering (ones feasible)
                } else {
                    factors.add(Linear(coeffs, vars, LinearOp.LE, k - 1)) // packing (zeros feasible)
                }
            }
            val p = Problem(0, n, domains, factors.toTypedArray())
            val obj = LinearObjective(intCoefficients = LongArray(n) { rng.nextLong(-2, 3) })
            val sample = engine(p, obj).lbTreeSearch(obj, Cancellation.Never) ?: return@repeat
            produced++
            for (f in p.factors.filterIsInstance<Linear>()) {
                assertTrue(satisfies(f, sample.ints), "subsolver returned infeasible ${sample.ints.toList()}")
            }
        }
        assertTrue(produced > 50, "the subsolver produced only $produced incumbents across 300 instances")
    }

    @Test
    fun `the subsolver dives to an optimal incumbent on a small problem`() {
        // Triangle vertex cover: cost = x0+x1+x2 over {0,1}³, pair-covering rows ⇒ optimum cost 2.
        val p = Problem(
            0,
            4,
            arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 3)),
            arrayOf<Factor>(
                Linear(intArrayOf(1, 1, 1, -1), intArrayOf(0, 1, 2, 3), LinearOp.EQ, 0),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1))
        val sink = SolveStatsSink(backend = "lbtree")
        val lp = LpEngine(p, obj, LpParams(lpPlan = LpPlan(bounding = true)), sink)
        val sample = lp.lbTreeSearch(obj, Cancellation.Never)
        assertTrue(sample != null, "shared search should find a feasible incumbent")
        assertEquals(2.0, obj.evaluate(sample), "shared search should dive to the optimal cost 2")
        assertTrue(sink.snapshot().lp.rootPasses.sum > 0.0, "every tree-search LP must be attributed to root work")
    }

    @Test
    fun `the shared primal search certifies real values in source units`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 1)),
            factors = arrayOf<Factor>(
                Linear(
                    intVars = intArrayOf(0),
                    intCoeffs = doubleArrayOf(1.0),
                    realVars = intArrayOf(0),
                    realCoeffs = doubleArrayOf(1.0),
                    op = LinearOp.GE,
                    bound = 1.5,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(2.0),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(1), realCoefficients = doubleArrayOf(2.0))

        val sample = engine(problem, objective).use { assertNotNull(it.lbTreeSearch(objective, Cancellation.Never)) }

        assertEquals(1L, sample.ints[0])
        assertEquals(0.5, sample.reals[0])
        assertEquals(2.0, objective.evaluate(sample))
    }
}
