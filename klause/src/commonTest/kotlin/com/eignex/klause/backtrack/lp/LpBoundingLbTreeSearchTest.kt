package com.eignex.klause.backtrack.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.engine.LpEngineFactory
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpSolver
import com.eignex.klause.lp.engine.PersistentLpSolver
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeSource

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
            val sample = engine(p, obj).lbTreeSearch(obj, Cancellation.Never)?.sample ?: return@repeat
            produced++
            for (f in p.factors.filterIsInstance<Linear>()) {
                assertTrue(satisfies(f, sample.ints), "subsolver returned infeasible ${sample.ints.toList()}")
            }
        }
        assertTrue(produced > 50, "the subsolver produced only $produced incumbents across 300 instances")
    }

    @Test
    fun `a dive leaf whose reals descend without limit returns its checked ray`() {
        // x in [0,3], r >= 0 with x - r <= 1: minimizing -r grows r without limit from every leaf.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 3)),
            factors = arrayOf<Factor>(
                Linear(longArrayOf(1L), intArrayOf(0), doubleArrayOf(-1.0), intArrayOf(0), LinearOp.LE, 1L),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val obj = LinearObjective(realCoefficients = doubleArrayOf(-1.0))

        val seed = engine(p, obj).use { assertNotNull(it.lbTreeSearch(obj, Cancellation.Never)) }

        assertTrue(assertNotNull(seed.direction).single().signum() > 0)
    }

    @Test
    fun `the dive's LP solves are handed the caller's deadline`() {
        val deadlines = ArrayList<ComparableTimeMark?>()
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newGeneralSolver(
                model: LpModel,
                cancellation: Cancellation,
                workLimit: Long,
                pricing: LpPricingOptions,
            ): LpSolver {
                deadlines += cancellation.deadline()
                return ProductionLpEngineFactory.newGeneralSolver(model, cancellation, workLimit, pricing)
            }

            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver {
                deadlines += cancellation.deadline()
                return ProductionLpEngineFactory.newPersistentSolver(
                    model,
                    cancellation,
                    refactorUpdateLimit,
                    iterationLimit,
                    workLimit,
                    trackDegeneracy,
                    pricing,
                )
            }
        }
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            arrayOf<Factor>(Linear(intArrayOf(2, 3), intArrayOf(0, 1), LinearOp.GE, 5)),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L, 1L))
        val run = Cancellation.until(TimeSource.Monotonic.markNow() + 1.hours)
        val engine = LpEngine(
            problem,
            obj,
            LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "lbtree"),
            LpSolveContext(factory),
        )

        engine.use { assertNotNull(it.lbTreeSearch(obj, run)) }

        assertTrue(deadlines.isNotEmpty())
        assertTrue(deadlines.all { it == run.deadline() })
    }

    @Test
    fun `the dive keeps to the caller's assumptions`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyArray())
        val obj = LinearObjective(intCoefficients = longArrayOf(1L))

        val seed = engine(problem, obj).use {
            assertNotNull(it.lbTreeSearch(obj, Cancellation.Never, Assumptions(ints = mapOf(0 to 2L))))
        }

        assertEquals(2L, seed.sample.ints[0])
    }

    @Test
    fun `tree objective uses its own relaxation after the parent found a different optimum`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyArray())
        val positive = LinearObjective(intCoefficients = longArrayOf(1L))
        val negative = LinearObjective(intCoefficients = longArrayOf(-1L))
        engine(problem, positive).use { parent ->
            assertEquals(0L, assertNotNull(parent.lbTreeSearch(positive, Cancellation.Never)).sample.ints[0])
            assertEquals(3L, assertNotNull(parent.lbTreeSearch(negative, Cancellation.Never)).sample.ints[0])
            assertEquals(0L, assertNotNull(parent.lbTreeSearch(positive, Cancellation.Never)).sample.ints[0])
        }
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
        val sample = lp.lbTreeSearch(obj, Cancellation.Never)?.sample
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

        val sample = engine(problem, objective).use {
            assertNotNull(it.lbTreeSearch(objective, Cancellation.Never)).sample
        }

        assertEquals(1L, sample.ints[0])
        assertEquals(0.5, sample.reals[0])
        assertEquals(2.0, objective.evaluate(sample))
    }
}
