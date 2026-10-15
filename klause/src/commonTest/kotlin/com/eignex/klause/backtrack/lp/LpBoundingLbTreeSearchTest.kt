package com.eignex.klause.backtrack.lp

import com.eignex.klause.backtrack.UnresolvedRealLeafFixture
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.engine.LpCertificationPolicy
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpEngineFactory
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpSolver
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.PersistentLpSolver
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.lp.relaxation.LeafRealResult
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeSource

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
    fun `an unbounded leaf supplies a feasible point without a terminal claim`() {
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

        assertTrue(seed.sample.reals.single() >= 0.0)
        assertTrue(seed.sample.ints.single() - seed.sample.reals.single() <= 1.0)
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
    @Test
    fun `open integer declarations decline without using fallback endpoints`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyArray(), openIntHi = booleanArrayOf(true))
        val objective = LinearObjective(intCoefficients = longArrayOf(-1L))
        engine(problem, objective).use { parent ->
            assertNull(parent.lbTreeSearch(objective, Cancellation.Never))
            assertEquals(0L, parent.totalSolveWork())
        }
    }

    @Test
    fun `a cancelled mixed completion cannot seed an incumbent`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray(),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
        var cancelled = false
        engine(problem, objective).use { parent ->
            assertNull(parent.lbTreeSearch(objective, Cancellation { cancelled }, complete = { _, _, _ ->
                cancelled = true
                LeafRealResult(
                    LpVerdict.TOLERANCE_OPTIMUM, doubleArrayOf(0.0))
            }, toleranceCheck = { true }))
            assertTrue(parent.totalSolveWork() > 0L)
            assertEquals(1L, parent.primalSearchWork)
        }
    }

    @Test
    fun `a nonlinear real leaf declines numerical completion`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), arrayOf(ContinuousProduct(0, 1, 2)),
            numRealVars = 3, realLower = doubleArrayOf(0.0, 0.0, 1.0), realUpper = doubleArrayOf(1.0, 1.0, 1.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(1))

        engine(problem, objective).use { parent ->
            assertNull(parent.lbTreeSearch(objective, Cancellation.Never))
        }
    }

    @Test
    fun `fractional alternatives retain a completed mixed cover under source assumptions`() {
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 1) }, arrayOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1)),
            numRealVars = 1, realLower = doubleArrayOf(0.5), realUpper = doubleArrayOf(1.5))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1, 1),
            constant = 3L, realCoefficients = doubleArrayOf(0.5))

        for (assumptions in listOf(Assumptions.None, Assumptions.None.withInt(0, 0L))) {
            val sample = engine(problem, objective).use {
                assertNotNull(it.lbTreeSearch(objective, Cancellation.Never, assumptions)).sample
            }

            assertEquals(5.25, objective.evaluate(sample))
            assertEquals(0.5, sample.reals.single())
            assertTrue(sample.ints[0] + sample.ints[1] >= 1L)
            assertTrue(sample.ints[1] + sample.ints[2] >= 1L)
            assertTrue(sample.ints[0] + sample.ints[2] >= 1L)
            if (assumptions != Assumptions.None) assertEquals(0L, sample.ints[0])
        }
    }

    @Test
    fun `uncertified numerical targets order mixed choices without accepting a leaf`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 3)), arrayOf(
            Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 2.5)),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(-1), realCoefficients = doubleArrayOf(1.0))
        var pointDeclines = 0
        var cancelled = false
        val visited = ArrayList<Long>()
        val policy = LpCertificationPolicy { certifier, successful ->
            if (certifier == LpCertifier.EXACT_POINT) pointDeclines++
            successful && certifier != LpCertifier.EXACT_POINT
        }
        val parent = LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "mixed-ordering"), LpSolveContext(certificationPolicy = policy))

        parent.use {
            assertNull(it.lbTreeSearch(objective, Cancellation { cancelled }, complete = { candidate, _, _ ->
                visited += candidate.ints.single()
                cancelled = true
                LeafRealResult(LpVerdict.INDETERMINATE, doubleArrayOf())
            }))
        }

        assertEquals(listOf(3L), visited)
        assertTrue(pointDeclines > 0)
    }

    @Test
    fun `a failed mixed completion releases every private numerical owner`() {
        val fixture = UnresolvedRealLeafFixture(true)
        fixture.acceptProof = { _, _ -> true }
        val parent = LpEngine(fixture.problem, fixture.objective, LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "mixed-cleanup"), fixture.context)

        assertFailsWith<IllegalStateException> {
            parent.use {
                it.lbTreeSearch(fixture.objective, Cancellation.Never,
                    complete = { _, _, _ -> error("injected completion failure") })
            }
        }

        assertTrue(fixture.persistentOpened > 0)
        assertEquals(fixture.persistentOpened, fixture.persistentClosed)
    }

    @Test
    fun `an integral LP point completes a mixed incumbent without pinning every domain`() {
        val count = 257
        val problem = Problem(0, count, Array(count) { IntDomain(0, 1) }, arrayOf(
            Linear(IntArray(count) { 1 }, IntArray(count) { it }, LinearOp.GE, 1)),
            numRealVars = 1, realLower = doubleArrayOf(0.5), realUpper = doubleArrayOf(1.5))
        val objective = LinearObjective(intCoefficients = LongArray(count) { 1 },
            realCoefficients = doubleArrayOf(1.0))

        engine(problem, objective).use { parent ->
            val sample = assertNotNull(parent.lbTreeSearch(objective, Cancellation.Never)).sample

            assertEquals(1L, sample.ints.sum())
            assertEquals(0.5, sample.reals.single())
            assertEquals(1.5, objective.evaluate(sample))
            assertEquals(0L, parent.primalSearchWork)
        }
    }

}
