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
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.CrashBasisAttempt
import com.eignex.klause.lp.engine.CrashBasisDecline
import com.eignex.klause.lp.engine.CrashBasisMetrics
import com.eignex.klause.lp.engine.LpCertificationPolicy
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpEngineFactory
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.klause.lp.engine.LpSolver
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.PersistentLpSolver
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.lp.relaxation.LeafRealResult
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeSource

class LpTreeSearchTest {
    private fun engine(p: Problem, obj: LinearObjective) =
        LpEngine(p, obj, LpParams(lpPlan = LpPlan(bounding = true)), SolveStatsSink(backend = "lbtree"))

    @Test
    fun `an unbounded leaf should supply a feasible point without a terminal claim`() {
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
    fun `the dive's LP solves should be handed the caller's deadline`() {
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
        val params = LpParams(lpPlan = LpPlan(bounding = true))
        val engine = LpEngine(problem, obj, params, SolveStatsSink(backend = "lbtree"), LpSolveContext(factory))

        engine.use { assertNotNull(it.lbTreeSearch(obj, run)) }

        assertTrue(deadlines.isNotEmpty())
        assertTrue(deadlines.all { it == run.deadline() })
    }

    @Test
    fun `the dive should keep to the caller's assumptions`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyArray())
        val obj = LinearObjective(intCoefficients = longArrayOf(1L))

        val seed = engine(problem, obj).use {
            assertNotNull(it.lbTreeSearch(obj, Cancellation.Never, Assumptions(ints = mapOf(0 to 2L))))
        }

        assertEquals(2L, seed.sample.ints[0])
    }

    @Test
    fun `the tree objective should use its own relaxation after the parent found a different optimum`() {
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
    fun `the subsolver should dive to an optimal incumbent on a small problem`() {
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
        assertTrue(sample != null)
        assertEquals(2.0, obj.evaluate(sample))
        assertTrue(sink.snapshot().lp.rootPasses.sum > 0.0)
    }

    @Test
    fun `the shared primal search should certify real values in source units`() {
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
    fun `open integer declarations should decline without using fallback endpoints`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyArray(), openIntHi = booleanArrayOf(true))
        val objective = LinearObjective(intCoefficients = longArrayOf(-1L))
        engine(problem, objective).use { parent ->
            assertNull(parent.lbTreeSearch(objective, Cancellation.Never))
            assertEquals(0L, parent.totalSolveWork())
        }
    }

    @Test
    fun `a cancelled mixed completion should not seed an incumbent`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 1)),
            emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
        val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
        var cancelled = false
        engine(problem, objective).use { parent ->
            assertNull(
                parent.lbTreeSearch(objective, Cancellation { cancelled }, complete = { _, _, _ ->
                    cancelled = true
                    LeafRealResult(
                        LpVerdict.TOLERANCE_OPTIMUM,
                        doubleArrayOf(0.0),
                    )
                }, toleranceCheck = { true }),
            )
            assertTrue(parent.totalSolveWork() > 0L)
            assertTrue(cancelled)
        }
    }

    @Test
    fun `a nonlinear real leaf should decline numerical completion`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 1)),
            arrayOf(ContinuousProduct(0, 1, 2)),
            numRealVars = 3,
            realLower = doubleArrayOf(0.0, 0.0, 1.0),
            realUpper = doubleArrayOf(1.0, 1.0, 1.0),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(1))

        engine(problem, objective).use { parent ->
            assertNull(parent.lbTreeSearch(objective, Cancellation.Never))
        }
    }

    @Test
    fun `fractional alternatives should retain a completed mixed cover under source assumptions`() {
        val problem = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 1) },
            arrayOf(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.5),
            realUpper = doubleArrayOf(1.5),
        )
        val objective = LinearObjective(
            intCoefficients = longArrayOf(1, 1, 1),
            constant = 3L,
            realCoefficients = doubleArrayOf(0.5),
        )

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
    fun `uncertified numerical targets should order mixed choices without accepting a leaf`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 3)),
            arrayOf(
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 2.5),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(-1), realCoefficients = doubleArrayOf(1.0))
        var pointDeclines = 0
        var cancelled = false
        val visited = ArrayList<Long>()
        val policy = LpCertificationPolicy { certifier, successful ->
            if (certifier == LpCertifier.EXACT_POINT) pointDeclines++
            successful && certifier != LpCertifier.EXACT_POINT
        }
        val parent = LpEngine(
            problem,
            objective,
            LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "mixed-ordering"),
            LpSolveContext(certificationPolicy = policy),
        )

        parent.use {
            assertNull(
                it.lbTreeSearch(objective, Cancellation { cancelled }, complete = { candidate, _, _ ->
                    visited += candidate.ints.single()
                    cancelled = true
                    LeafRealResult(LpVerdict.INDETERMINATE, doubleArrayOf())
                }),
            )
        }

        assertEquals(listOf(3L), visited)
        assertTrue(pointDeclines > 0)
    }

    @Test
    fun `a failed mixed completion should release every private numerical owner`() {
        val fixture = UnresolvedRealLeafFixture(true)
        fixture.acceptProof = { _, _ -> true }
        val parent = LpEngine(
            fixture.problem,
            fixture.objective,
            LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "mixed-cleanup"),
            fixture.context,
        )

        assertFailsWith<IllegalStateException> {
            parent.use {
                it.lbTreeSearch(
                    fixture.objective,
                    Cancellation.Never,
                    complete = { _, _, _ -> error("injected completion failure") },
                )
            }
        }

        assertTrue(fixture.persistentOpened > 0)
        assertEquals(fixture.persistentOpened, fixture.persistentClosed)
    }

    @Test
    fun `an integral LP point should complete a mixed incumbent without pinning every domain`() {
        val count = 257
        val problem = Problem(
            0,
            count,
            Array(count) { IntDomain(0, 1) },
            arrayOf(
                Linear(IntArray(count) { 1 }, IntArray(count) { it }, LinearOp.GE, 1),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.5),
            realUpper = doubleArrayOf(1.5),
        )
        val objective = LinearObjective(
            intCoefficients = LongArray(count) { 1 },
            realCoefficients = doubleArrayOf(1.0),
        )

        engine(problem, objective).use { parent ->
            val sample = assertNotNull(parent.lbTreeSearch(objective, Cancellation.Never)).sample

            assertEquals(1L, sample.ints.sum())
            assertEquals(0.5, sample.reals.single())
            assertEquals(1.5, objective.evaluate(sample))
            assertEquals(0L, parent.primalSearchWork)
        }
    }

    @Test
    fun `mixed fractional descent should try the nearer integer side before completing a point`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 1)),
            arrayOf(
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 0.75),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(1), realCoefficients = doubleArrayOf(10.0))

        val sample = engine(problem, objective).use {
            assertNotNull(it.lbTreeSearch(objective, Cancellation.Never)).sample
        }

        assertEquals(1L, sample.ints.single())
        assertEquals(0.0, sample.reals.single())
        assertEquals(1.0, objective.evaluate(sample))
    }

    private val solver = object : LpSolver {
        override val infeasibleRay: DoubleArray? = null
        override fun solve(warm: Basis?) = null
        override fun solvePrimal(warm: Basis?) = null
    }

    @Test
    fun `the root crash budget should be bounded within node work`() {
        assertEquals(100_000L, rootCrashWorkLimit(0L))
        assertEquals(10L, rootCrashWorkLimit(80L))
        assertEquals(1L, rootCrashWorkLimit(1L))
        assertEquals(100_000L, rootCrashWorkLimit(Long.MAX_VALUE))
    }

    @Test
    fun `a declined root solve should still charge crash construction`() {
        val crash = CrashBasisAttempt(
            null,
            CrashBasisMetrics(
                workOps = 37L,
                candidates = 1,
                selected = 0,
                passes = 1,
                decline = CrashBasisDecline.RESOURCE_LIMIT,
            ),
        )
        val observed = ArrayList<LpSolveMetrics>()

        val result = solveRootNodeWithCrash(
            crash,
            solve = { null },
            solveMetrics = { error("a declined solve has no metrics owner") },
            observe = { _, metrics -> observed += metrics },
        )

        assertNull(result)
        assertEquals(listOf(37L), observed.map { it.workOps })
    }

    @Test
    fun `a throwing root solve should still charge crash construction`() {
        val crash = CrashBasisAttempt(
            null,
            CrashBasisMetrics(41L, 1, 0, 1, CrashBasisDecline.CANCELLED),
        )
        val observed = ArrayList<LpSolveMetrics>()

        assertFailsWith<IllegalStateException> {
            solveRootNodeWithCrash(
                crash,
                solve = { error("injected solve failure") },
                solveMetrics = { error("a throwing solve has no metrics owner") },
                observe = { _, metrics -> observed += metrics },
            )
        }

        assertEquals(listOf(41L), observed.map { it.workOps })
    }

    @Test
    fun `a completed root solve should charge crash construction once`() {
        val crash = CrashBasisAttempt(null, CrashBasisMetrics(37L, 1, 1, 1))
        val observed = ArrayList<LpSolveMetrics>()

        solveRootNodeWithCrash(
            crash,
            solve = { solver to null },
            solveMetrics = { LpSolveMetrics(workOps = 11L) },
            observe = { _, metrics -> observed += metrics },
        )

        assertEquals(listOf(48L), observed.map { it.workOps })
    }

    @Test
    fun `a declined root solve should charge parent root work only`() {
        val sink = SolveStatsSink(backend = "crash-parent")
        val engine = LpEngine(
            Problem(0, 0, emptyArray(), emptyArray()),
            LinearObjective(),
            LpParams(),
            sink,
        )
        val crash = CrashBasisAttempt(
            null,
            CrashBasisMetrics(43L, 1, 0, 1, CrashBasisDecline.RESOURCE_LIMIT),
        )

        val result = engine.use {
            it.solveRootNodeWithCrash(
                crash,
                solve = { null },
                solveMetrics = { error("a declined solve has no metrics owner") },
            )
        }

        assertNull(result)
        assertEquals(43L, engine.totalSolveWork())
        assertEquals(0L, engine.pendingNodeSolveWork())
        assertEquals(43.0, sink.snapshot().lp.rootWorkOps.sum)
    }

    @Test
    fun `a failed root should charge completed source preparation once to its parent`() {
        val sink = SolveStatsSink(backend = "source-parent")
        val engine = LpEngine(Problem(0, 0, emptyArray(), emptyArray()), LinearObjective(), LpParams(), sink)

        engine.use {
            assertNull(
                it.solveRootNodeWithCrash(
                    null,
                    { null },
                    { error("no solve owner") },
                    { LpSolveMetrics(workOps = 41L) },
                ),
            )
        }

        assertEquals(41L, engine.totalSolveWork())
        assertEquals(0L, engine.pendingNodeSolveWork())
        assertEquals(41.0, sink.snapshot().lp.rootWorkOps.sum)
    }

    @Test
    fun `throwing source work should be accounted without masking the failure`() {
        val observed = ArrayList<Long>()

        val failure = assertFailsWith<IllegalStateException> {
            solveRootNodeWithCrash(
                null,
                { error("source failure") },
                { error("no owner") },
                { _, metrics -> observed += metrics.workOps },
                { LpSolveMetrics(workOps = 23L) },
            )
        }

        assertEquals("source failure", failure.message)
        assertEquals(listOf(23L), observed)
    }

    @Test
    fun `a successful root should combine preparation and source work once`() {
        val observed = ArrayList<Long>()
        val solver = object : LpSolver {
            override val infeasibleRay: DoubleArray? = null
            override fun solve(warm: Basis?) = null
            override fun solvePrimal(warm: Basis?) = null
        }

        solveRootNodeWithCrash(
            null,
            { solver to null },
            { LpSolveMetrics(workOps = 17L) },
            { _, metrics -> observed += metrics.workOps },
            { LpSolveMetrics(workOps = 23L) },
        )

        assertEquals(listOf(40L), observed)
    }
}
