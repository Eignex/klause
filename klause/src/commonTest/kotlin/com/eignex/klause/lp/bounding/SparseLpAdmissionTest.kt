package com.eignex.klause.lp.bounding

import com.eignex.klause.backtrack.lp.lpRoundingProbe
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.Term
import com.eignex.klause.lp.RelaxationProjection
import com.eignex.klause.lp.RelaxationProjectionProvider
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.EngineConstruction
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpCertificationPolicy
import com.eignex.klause.lp.engine.LpEngineFactory
import com.eignex.klause.lp.engine.LpFloatAllowance
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpSolveCursor
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.klause.lp.engine.PersistentLpSolver
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.lp.engine.RecordingLpEngineFactory
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.ResumableLpSolver
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.VarStatus
import com.eignex.klause.lp.engine.continueExactLp
import com.eignex.klause.lp.relaxation.LpSourcePreparation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.ContinuationDecline
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.RelaxationCapability
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.CONSTRUCTION_BATCH_SIZE
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SparseLpAdmissionTest {
    @Test
    fun `automatic sparse root work checks a funded pause before resuming its cursor`() {
        val problem = boundedLpEngineProblem().withSettings(
            ProblemSettings(lpMaxTableauCells = 1L, lpCeilingTableauCells = 1L)).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1L, 2L, 3L))
        var proposals = 0
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel, cancellation: ExecutionControl, refactorUpdateLimit: Int, iterationLimit: Int,
                workLimit: Long, trackDegeneracy: Boolean, pricing: LpPricingOptions,
            ): PersistentLpSolver {
                val delegate = ProductionLpEngineFactory.newPersistentSolver(model, cancellation,
                    refactorUpdateLimit, iterationLimit, workLimit, trackDegeneracy, pricing)
                return object : PersistentLpSolver by delegate, ResumableLpSolver {
                    override var currentWork: Long = 0L
                        private set

                    override fun pausedDualProposal(cancellation: ExecutionControl): DoubleArray? {
                        if (cancellation()) return null
                        proposals++
                        return DoubleArray(model.m)
                    }

                    override fun openSolve(
                        allowance: LpFloatAllowance?, retainFundingStop: Boolean,
                    ): LpSolveCursor<FloatLpResult?> = LpSolveCursor {
                        currentWork = 1L
                        pauseAt(1L)
                        null
                    }
                }
            }
        }
        val params = LpParams(lpConfig = LpConfig.parse("default,+automatic-sparse"))
        LpEngine(problem, objective, params, SolveStatsSink("paid-pause"), LpSolveContext(factory)).use { engine ->
            assertTrue(engine.prepareRootLayout(ExecutionControl.Never, ExecutionControl.Never))

            engine.lpRoundingProbe(objective, ExecutionControl.Never)
            assertEquals(0.0, engine.continuedRootLowerBound)
            assertEquals(1, proposals)
            engine.lpRoundingProbe(objective, ExecutionControl.Never)

            assertEquals(1, proposals)
        }
    }

    @Test
    fun `a reused sparse root layout has private numerical state and preparation charges`() {
        val source = boundedLpEngineProblem().withSettings(
            ProblemSettings(lpMaxTableauCells = 1L, lpCeilingTableauCells = 1L))
        val problem = source.bake(FactorProjectionProviders.Default.prepare(source))
        val objective = LinearObjective(intCoefficients = longArrayOf(1L, 2L, 3L))
        val params = LpParams(lpConfig = LpConfig.parse("default,+automatic-sparse"))
        val firstFactory = RecordingLpEngineFactory()
        val secondFactory = RecordingLpEngineFactory()
        val secondSink = SolveStatsSink("reused-sparse-root")

        LpEngine(problem, objective, params, SolveStatsSink("first-sparse-root"), LpSolveContext(firstFactory))
            .use { first ->
                assertTrue(first.prepareRootLayout(ExecutionControl.Never, ExecutionControl.Never))
                val firstWork = first.totalSolveWork()
                LpEngine(problem, objective, params, secondSink, LpSolveContext(secondFactory)).use { second ->
                    val secondBeforeLayout = second.totalSolveWork()
                    assertTrue(second.prepareRootLayout(ExecutionControl.Never, ExecutionControl.Never))

                    val firstState = requireNotNull(first.propagator.state)
                    val secondState = requireNotNull(second.propagator.state)
                    assertNotSame(firstState, secondState)
                    assertNotSame(firstState.model, secondState.model)
                    assertTrue(firstFactory.calls.any { it.kind == EngineConstruction.PERSISTENT })
                    assertTrue(secondFactory.calls.any { it.kind == EngineConstruction.PERSISTENT })
                    assertTrue(second.totalSolveWork() > secondBeforeLayout)
                    assertEquals(firstWork, first.totalSolveWork())
                }
            }
        assertTrue(requireNotNull(secondSink.snapshot().lp.sparseTrial["ReusedPreparationWork"]) > 0L)
    }

    @Test
    fun `automatic sparse preparation shares the paid layout for the same source and objective`() {
        val source = boundedLpEngineProblem()
        val problem = source.bake(FactorProjectionProviders.Default.prepare(source))
        val objective = LinearObjective(intCoefficients = longArrayOf(1L, 2L, 3L))
        val admissions = ArrayList<SparseLpAdmission>()

        sequence<Unit> {
            repeat(2) { admissions += prepareSparseLpAdmission(problem, objective, automatic = true) }
        }.forEach { }

        assertSame(requireNotNull(admissions[0].sourceLayout), admissions[1].sourceLayout)
        assertEquals(admissions[0].work, admissions[1].metrics["ReusedPreparationWork"])
        assertEquals(1L, admissions[1].work)
    }

    @Test
    fun `automatic sparse reuse can decline a funded source with an unaffordable compact forecast`() {
        val source = boundedLpEngineProblem()
        val problem = source.bake(FactorProjectionProviders.Default.prepare(source))
        val objective = LinearObjective(intCoefficients = longArrayOf(1L, 2L, 3L))
        val admissions = ArrayList<SparseLpAdmission>()

        sequence<Unit> {
            val first = prepareSparseLpAdmission(problem, objective, automatic = true)
            admissions += first
            admissions += prepareSparseLpAdmission(problem, objective, automatic = true,
                queryWorkAllowance = first.metrics.getValue("EstimatedProbeTotalWork") - 1L)
        }.forEach { }

        val (first, declined) = admissions
        assertEquals(1L, declined.work)
        assertEquals(first.work, declined.metrics["ReusedPreparationWork"])
        assertEquals(1L, declined.metrics["DeclineQueryWork"])
        assertFalse(declined.admitted)
        assertNull(declined.sourceLayout)
    }

    @Test
    fun `automatic sparse reuse preserves a source pass funding decline`() {
        val source = boundedLpEngineProblem()
        val problem = source.bake(FactorProjectionProviders.Default.prepare(source))
        val objective = LinearObjective(intCoefficients = longArrayOf(1L, 2L, 3L))
        var declined: SparseLpAdmission? = null

        sequence<Unit> {
            prepareSparseLpAdmission(problem, objective, automatic = true)
            declined = prepareSparseLpAdmission(problem, objective, automatic = true, queryWorkAllowance = 0L)
        }.forEach { }

        val metrics = requireNotNull(declined).metrics
        assertEquals(1L, metrics["DeclineQueryWork"])
        assertNull(metrics["ReusedPreparationWork"])
    }

    @Test
    fun `automatic sparse preparation keeps layouts for different objectives distinct`() {
        val source = boundedLpEngineProblem()
        val problem = source.bake(FactorProjectionProviders.Default.prepare(source))
        val objectives = listOf(LinearObjective(intCoefficients = longArrayOf(1L, 2L, 3L)),
            LinearObjective(intCoefficients = longArrayOf(3L, 2L, 1L)))
        val admissions = ArrayList<SparseLpAdmission>()

        sequence<Unit> {
            for (objective in objectives) admissions += prepareSparseLpAdmission(problem, objective, automatic = true)
        }.forEach { }

        assertNotSame(requireNotNull(admissions[0].sourceLayout), requireNotNull(admissions[1].sourceLayout))
    }

    @Test
    fun `unaffordable automatic forecast should stop before inspecting every source row`() {
        val width = 32
        val rowCount = CONSTRUCTION_BATCH_SIZE * 3
        val columns = IntArray(width) { it }
        val rows = Array(rowCount) { Linear(IntArray(width) { 1 }, columns, LinearOp.GE, 1) }
        val problem = Problem(0, width, Array(width) { IntDomain(0, 1) }, rows.toList())
        val optimisticPass = (rowCount.toLong() + width) * rowCount
        var admission: SparseLpAdmission? = null

        sequence<Unit> {
            admission = prepareSparseLpAdmission(problem, LinearObjective(intCoefficients = LongArray(width)),
                automatic = true, queryWorkAllowance = optimisticPass + rowCount)
        }.forEach { }

        val declined = requireNotNull(admission)
        assertEquals(1L, declined.metrics["DeclineQueryWork"])
        assertTrue(declined.work in 1L until rowCount.toLong())
    }

    @Test
    fun `disabled source preparation should remain disabled during sparse admission`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)),
            settings = ProblemSettings(lpCeilingTableauCells = 1L)).bake()
        var admission: SparseLpAdmission? = null

        sequence<Unit> {
            admission = prepareSparseLpAdmission(problem, LinearObjective(intCoefficients = longArrayOf(1, 2)),
                sourcePreparation = LpSourcePreparation.DISABLED)
        }.forEach { }

        assertNull(requireNotNull(requireNotNull(admission).sourceLayout).preparationTemplates)
    }

    @Test
    fun `paid sparse source layout starts numerical preparation without another assembly slice`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)),
            settings = ProblemSettings(lpCeilingTableauCells = 1L)).bake()
        val factory = RecordingLpEngineFactory()
        LpEngine(problem, LinearObjective(intCoefficients = longArrayOf(1, 2), constant = 7L),
            LpParams(lpConfig = LpConfig.parse("default,+sparse-trial")),
            SolveStatsSink("paid-sparse-source-layout"), LpSolveContext(factory),
            projections = FactorProjectionProviders.Default.prepare(problem)).use { engine ->
            val paid = engine.totalSolveWork()

            val readyInFirstSlice = engine.prepareRootLayout(ExecutionControl.Never,
                ExecutionControl { engine.totalSolveWork() > paid })

            assertFalse(readyInFirstSlice)
            assertTrue(paid > 0L)
            assertEquals(EngineConstruction.PERSISTENT, factory.calls.single().kind)
            val bound = engine.rootLpRelaxationBound(requireNotNull(engine.lpRelaxer), emptyList())
            assertEquals(8.0, bound)
            assertEquals(1, factory.calls.size)
            assertEquals(1, factory.floatAllowances.size)
        }
    }

    @Test
    fun `cancelled sparse source adoption retains its paid charge without constructing an owner`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)),
            settings = ProblemSettings(lpCeilingTableauCells = 1L)).bake()
        val factory = RecordingLpEngineFactory()
        LpEngine(problem, LinearObjective(intCoefficients = longArrayOf(1, 2)),
            LpParams(lpConfig = LpConfig.parse("default,+sparse-trial")),
            SolveStatsSink("cancelled-sparse-source-adoption"), LpSolveContext(factory)).use { engine ->
            val paid = engine.totalSolveWork()
            val cancelled = ExecutionControl { true }

            engine.prepareRootLayout(cancelled, ExecutionControl.Never)
            val bound = engine.rootLpRelaxationBound(requireNotNull(engine.lpRelaxer), emptyList(), cancelled)

            assertTrue(paid > 0L)
            assertEquals(paid, engine.totalSolveWork())
            assertTrue(factory.calls.isEmpty())
            assertTrue(bound.isNaN())
        }
    }

    @Test
    fun `sparse admission measures source bounds before root propagation`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 64)),
            listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1)),
            settings = ProblemSettings(lpCeilingTableauCells = 1L))

        val admission = LpAutoConfig.admission(problem, LpConfig.parse("default,+sparse-trial"), LpPlan())

        assertTrue(admission.plan.bounding)
        assertEquals(7L, requireNotNull(admission.sparseTrial).metrics["ScalarBits"])
    }

    @Test
    fun `cancelled sparse assembly retains its reserved construction charge`() {
        val row = Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            List(2 * CONSTRUCTION_BATCH_SIZE) { row },
            settings = ProblemSettings(lpCeilingTableauCells = 1L))
        val complete = requireNotNull(LpAutoConfig.admission(problem,
            LpConfig.parse("default,+sparse-trial"), LpPlan()).sparseTrial)
        assertTrue(complete.admitted)
        for (chunks in 1..2) {
            var cancelled = false
            var interrupted: SparseLpAdmission? = null
            val preparation = sequence<Unit> {
                interrupted = prepareSparseLpAdmission(problem, null, ExecutionControl { cancelled })
            }.iterator()
            repeat(problem.factors.size / CONSTRUCTION_BATCH_SIZE + chunks) {
                assertTrue(preparation.hasNext())
                preparation.next()
            }
            cancelled = true

            while (preparation.hasNext()) preparation.next()

            val result = requireNotNull(interrupted)
            assertFalse(result.admitted)
            assertEquals(1L, result.metrics["DeclineCancelled"])
            assertEquals(complete.work, result.work)
        }
    }

    @Test
    fun `a sparse root bound does not admit the same model to dense exact continuation`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 2)),
            List(256) { Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1) },
            settings = ProblemSettings(lpCeilingTableauCells = 1L))
        LpEngine(problem, LinearObjective(intCoefficients = longArrayOf(1)),
            LpParams(lpConfig = LpConfig.parse("default,+sparse-trial")),
            SolveStatsSink("separate-exact-envelope")).use { engine ->
            val relaxer = requireNotNull(engine.lpRelaxer)
            val model = relaxer.build(PropagationSession(problem)).model
            val basis = Basis(IntArray(model.m) { model.n + it },
                Array(model.numVars) { if (it >= model.n) VarStatus.BASIC else VarStatus.AT_LOWER })

            val declined = continueExactLp(model, basis)
            val bound = engine.rootLpRelaxationBound(relaxer, emptyList())

            assertEquals(ContinuationDecline.DIMENSION, declined.metrics.decline)
            assertEquals(0, declined.metrics.builds)
            assertEquals(1.0, bound)
        }
    }

    @Test
    fun `completed sparse root work leaves checking inside the same grant`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)),
            settings = ProblemSettings(lpCeilingTableauCells = 1L))
        for (checkingWork in listOf(0L, 1L)) {
            lateinit var engine: LpEngine
            val factory = object : LpEngineFactory by ProductionLpEngineFactory {
                override fun newPersistentSolver(
                    model: LpModel,
                    cancellation: ExecutionControl,
                    refactorUpdateLimit: Int,
                    iterationLimit: Int,
                    workLimit: Long,
                    trackDegeneracy: Boolean,
                    pricing: LpPricingOptions,
                ): PersistentLpSolver {
                    val delegate = ProductionLpEngineFactory.newPersistentSolver(
                        model, cancellation, refactorUpdateLimit, iterationLimit, workLimit, trackDegeneracy, pricing,
                    )
                    return object : PersistentLpSolver by delegate {
                        private var completed: LpSolveMetrics? = null
                        override val lastMetrics get() = completed ?: delegate.lastMetrics
                        override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? =
                            delegate.resolveBounds(allowance).also {
                                completed = delegate.lastMetrics.copy(
                                    workOps = engine.retainedRootRemainingWork() - checkingWork,
                                )
                            }
                    }
                }
            }
            engine = LpEngine(problem, LinearObjective(intCoefficients = longArrayOf(1, 1)),
                LpParams(lpConfig = LpConfig.parse("default,+sparse-trial")),
                SolveStatsSink("funded-sparse-root"), LpSolveContext(factory))

            val (bound, work) = engine.use {
                it.rootLpRelaxationBound(requireNotNull(it.lpRelaxer), emptyList()) to it.totalSolveWork()
            }

            assertTrue(bound.isNaN())
            assertTrue(work <= SPARSE_LP_MAX_WORK)
            assertTrue(work >= SPARSE_LP_MAX_WORK - checkingWork)
        }
    }

    @Test
    fun `sparse root bounds honor bounded certificate refusal`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)),
            settings = ProblemSettings(lpCeilingTableauCells = 1L))
        var refusals = 0
        val policy = LpCertificationPolicy { _, successful ->
            if (successful) refusals++
            false
        }

        val bound = LpEngine(problem, LinearObjective(intCoefficients = longArrayOf(1, 1)),
            LpParams(lpConfig = LpConfig.parse("default,+sparse-trial")),
            SolveStatsSink("refused-sparse-root"), LpSolveContext(certificationPolicy = policy)).use {
            it.rootLpRelaxationBound(requireNotNull(it.lpRelaxer), emptyList())
        }

        assertTrue(bound.isNaN())
        assertTrue(refusals > 0)
    }

    @Test
    fun `admitted sparse roots retain primal consumers when the ordinary size proxy declines`() {
        for ((ceiling, ordinaryLp) in listOf(1L to false, 1_000_000L to true)) {
            val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
                listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)),
                settings = ProblemSettings(lpCeilingTableauCells = ceiling))
            val ordinary = LpAutoConfig.resolve(problem, LpConfig.AUTO)

            val trial = LpAutoConfig.resolve(problem, LpConfig.parse("default,+sparse-trial"))

            assertEquals(ordinaryLp, ordinary.bounding)
            assertEquals(ordinaryLp, ordinary.probe)
            assertTrue(trial.bounding)
            assertTrue(trial.probe)
        }
    }

    @Test
    fun `sparse root consultations retain progress until a checked bound completes`() {
        val problem = Problem(0, 96, Array(96) { IntDomain(0, 1) },
            List(95) { Linear(intArrayOf(1, 1), intArrayOf(it, it + 1), LinearOp.GE, 1) },
            settings = ProblemSettings(lpCeilingTableauCells = 1L))
        val sink = SolveStatsSink("continued-sparse-root")

        val (lower, work) = LpEngine(problem, LinearObjective(intCoefficients = LongArray(96) { 1L }),
            LpParams(lpConfig = LpConfig.parse("default,+sparse-trial")), sink).use { engine ->
            repeat(3) { engine.observeNodeWork(false, 96, 96, 1) }
            val bound = engine.rootLpRelaxationBound(requireNotNull(engine.lpRelaxer), emptyList())
            bound to engine.totalSolveWork()
        }

        assertEquals(48.0, lower)
        assertTrue((sink.snapshot().lp.sparseTrial["RootPauses"] ?: 0L) > 0L)
        assertEquals(1.0, sink.snapshot().lp.rootPasses.sum)
        assertTrue(sink.snapshot().lp.rootWorkOps.sum > 0.0)
        assertTrue(work <= SPARSE_LP_MAX_WORK)
    }

    @Test
    fun `sparse root slices share one numerical continuation grant`() {
        val problem = Problem(0, 32, Array(32) { IntDomain(0, 1) },
            List(31) { Linear(intArrayOf(1, 1), intArrayOf(it, it + 1), LinearOp.GE, 1) },
            settings = ProblemSettings(lpCeilingTableauCells = 1L))
        for (numerical in listOf(false, true)) {
            var queries = 0
            var sliceEnd = Long.MAX_VALUE
            lateinit var engine: LpEngine
            val context = LpSolveContext(numericalDeductions = numerical, onQuery = { queries++ })
            val objective = LinearObjective(intCoefficients = LongArray(32) { 1L })
            engine = LpEngine(problem, objective,
                LpParams(lpConfig = LpConfig.parse("default,+sparse-trial")),
                SolveStatsSink("sparse-sliced-continuation"), context,
                nodeWorkAllowance = {
                    if (sliceEnd == Long.MAX_VALUE) null else (sliceEnd - engine.totalSolveWork()).coerceAtLeast(0L)
                })
            engine.use {
                assertTrue(it.prepareRootLayout(ExecutionControl.Never, ExecutionControl.Never))
                val relaxer = requireNotNull(it.lpRelaxer)
                val token = ExecutionControl { it.totalSolveWork() >= sliceEnd }

                sliceEnd = engine.totalSolveWork() + 500L
                engine.rootLpRelaxationBound(relaxer, emptyList(), token)
                sliceEnd = engine.totalSolveWork() + 500L
                engine.lpRoundingProbe(objective, token)
                sliceEnd = engine.totalSolveWork() + 1_000_000L
                val sample = engine.lpRoundingProbe(objective, token)

                assertEquals(16L, requireNotNull(sample).ints.sum())
                assertEquals(16.0, engine.continuedRootLowerBound)
                assertEquals(1, queries)
                assertTrue(engine.totalSolveWork() <= SPARSE_LP_MAX_WORK)
            }
        }
    }

    @Test
    fun `continued root bounds reject a changed assertion scope`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)),
            settings = ProblemSettings(lpCeilingTableauCells = 1L))
        for (depth in listOf(0, 1)) {
            LpEngine(problem, LinearObjective(intCoefficients = longArrayOf(1, 3)),
                LpParams(lpConfig = LpConfig.parse("default,+sparse-trial")),
                SolveStatsSink("changed-root-bound-scope")).use { engine ->
                assertEquals(1.0, engine.rootLpRelaxationBound(requireNotNull(engine.lpRelaxer), emptyList()))
                val original = requireNotNull(engine.sourceRootBoundLayout).model
                assertTrue(engine.propagator.atLevel(depth))
                assertTrue(engine.propagator.assertBound(1, false, ExactLpSide(ExactLpNumber.of(1L))))
                val result = requireNotNull(engine.propagator.solveFloat()?.second)

                engine.checkContinuedRootBound(original, result, ExecutionControl.Never)

                assertEquals(3.0, result.objective)
                assertNull(engine.continuedRootLowerBound)
            }
        }
    }

    @Test
    fun `continued root bounds honor certification refusal`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)),
            settings = ProblemSettings(lpCeilingTableauCells = 1L))
        val context = LpSolveContext(certificationPolicy = LpCertificationPolicy { _, _ -> false })
        LpEngine(problem, LinearObjective(intCoefficients = longArrayOf(1, 3)),
            LpParams(lpConfig = LpConfig.parse("default,+sparse-trial")),
            SolveStatsSink("refused-continued-root-bound"), context).use { engine ->
            assertTrue(engine.rootLpRelaxationBound(requireNotNull(engine.lpRelaxer), emptyList()).isNaN())
            val model = requireNotNull(engine.sourceRootBoundLayout).model
            val result = requireNotNull(engine.propagator.solveFloat()?.second)

            engine.checkContinuedRootBound(model, result, ExecutionControl.Never)

            assertNull(engine.continuedRootLowerBound)
        }
    }

    @Test
    fun `spent sparse root grant prevents another root node query`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)),
            settings = ProblemSettings(lpCeilingTableauCells = 1L))
        var queries = 0
        LpEngine(problem, LinearObjective(intCoefficients = longArrayOf(1, 3)),
            LpParams(lpConfig = LpConfig.parse("default,+sparse-trial"),
                lpPlan = LpPlan(rootMaxWork = 20_000L)), SolveStatsSink("spent-sparse-root"),
            LpSolveContext(onQuery = { queries++ })).use { engine ->
            assertEquals(1.0, engine.rootLpRelaxationBound(requireNotNull(engine.lpRelaxer), emptyList()))
            engine.noteNodeOverhead(engine.retainedRootCumulativeWork())
            val previousQueries = queries

            val pruned = engine.pruneNode(PropagationSession(problem), 2.0, -1, true)

            assertFalse(pruned)
            assertEquals(previousQueries, queries)
            assertEquals(0L, engine.retainedRootCumulativeWork())
        }
    }

    @Test
    fun `checked paused bounds survive a later root consultation stop`() {
        val problem = Problem(0, 96, Array(96) { IntDomain(0, 1) },
            List(95) { Linear(intArrayOf(1, 1), intArrayOf(it, it + 1), LinearOp.GE, 1) },
            settings = ProblemSettings(lpCeilingTableauCells = 1L))
        val sink = SolveStatsSink("checked-paused-sparse-root")
        val stop = ExecutionControl { (sink.lp.sparseTrial["PausedCertifiedBounds"] ?: 0L) > 0L }

        val (lower, work) = LpEngine(problem, LinearObjective(intCoefficients = LongArray(96) { 1L }),
            LpParams(lpConfig = LpConfig.parse("default,+sparse-trial")), sink).use { engine ->
            repeat(3) { engine.observeNodeWork(false, 96, 96, 1) }
            val bound = engine.rootLpRelaxationBound(requireNotNull(engine.lpRelaxer), emptyList(), stop)
            assertTrue(engine.propagator.floatPaused)
            bound to engine.totalSolveWork()
        }

        assertTrue(lower > 0.0 && lower <= 48.0)
        assertEquals(1L, sink.snapshot().lp.sparseTrial["PausedCertifiedBounds"])
        assertEquals(0.0, sink.snapshot().lp.rootPasses.sum)
        assertTrue(work <= SPARSE_LP_MAX_WORK)
    }

    @Test
    fun `cancelled sparse root continuation publishes no partial bound`() {
        val problem = Problem(0, 96, Array(96) { IntDomain(0, 1) },
            List(95) { Linear(intArrayOf(1, 1), intArrayOf(it, it + 1), LinearOp.GE, 1) },
            settings = ProblemSettings(lpCeilingTableauCells = 1L))
        val sink = SolveStatsSink("cancelled-sparse-root")
        val stop = ExecutionControl { (sink.lp.sparseTrial["RootPauses"] ?: 0L) > 0L }

        val (lower, work) = LpEngine(problem, LinearObjective(intCoefficients = LongArray(96) { 1L }),
            LpParams(lpConfig = LpConfig.parse("default,+sparse-trial")), sink).use { engine ->
            repeat(3) { engine.observeNodeWork(false, 96, 96, 1) }
            val bound = engine.rootLpRelaxationBound(requireNotNull(engine.lpRelaxer), emptyList(), stop)
            bound to engine.totalSolveWork()
        }

        assertTrue(lower.isNaN())
        assertEquals(1L, sink.snapshot().lp.sparseTrial["RootPauses"])
        assertEquals(0.0, sink.snapshot().lp.rootPasses.sum)
        assertTrue(sink.snapshot().lp.rootWorkOps.sum > 0.0)
        assertTrue(work <= SPARSE_LP_MAX_WORK)
    }

    @Test
    fun `strict source completion stays outside bounded root trial admission`() {
        val problem = Problem(
            0, 0, emptyArray(),
            listOf(ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                LinearOp.GE, BigFraction.ZERO, strict = true)),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
            settings = ProblemSettings(lpCeilingTableauCells = 1L),
        )

        val admission = LpAutoConfig.admission(problem, LpConfig.parse("default,+sparse-trial"), LpPlan())

        assertFalse(admission.plan.bounding)
        assertEquals(1L, requireNotNull(admission.sparseTrial).metrics["DeclineStrictRow"])
    }

    @Test
    fun `mixed source root bounds preserve original objective units`() {
        val problem = Problem(
            0, 1, arrayOf(IntDomain(0, 1)),
            listOf(ExactLinear.of(intArrayOf(Term.ofIntVar(0), Term.ofRealVar(0)),
                listOf(BigFraction.ONE, BigFraction.ONE), LinearOp.GE, BigFraction.ONE)),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
            settings = ProblemSettings(lpCeilingTableauCells = 1L),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(1),
            realCoefficients = doubleArrayOf(1.0), constant = 7L)
        val sink = SolveStatsSink("mixed-root-units")

        val lower = LpEngine(problem, objective,
            LpParams(lpConfig = LpConfig.parse("default,+sparse-trial")), sink).use { engine ->
            engine.rootLpRelaxationBound(requireNotNull(engine.lpRelaxer), emptyList())
        }

        assertEquals(8.0, lower)
    }

    @Test
    fun `source preparation respects a smaller caller root allowance`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)),
            settings = ProblemSettings(lpCeilingTableauCells = 1L))
        val sink = SolveStatsSink("small-root-allowance")
        val plan = LpAutoConfig.resolve(problem, LpConfig.parse("default,+sparse-trial"), LpPlan(rootMaxWork = 1L))
        assertFalse(plan.bounding)

        LpEngine(problem, LinearObjective(intCoefficients = longArrayOf(1, 1)),
            LpParams(lpConfig = LpConfig.parse("default,+sparse-trial"), lpPlan = LpPlan(rootMaxWork = 1L)),
            sink).use { engine -> assertFalse(engine.params.lpPlan.bounding) }

        assertEquals(1L, sink.snapshot().lp.sparseTrial["DeclinePreparationWork"])
        assertEquals(0.0, sink.snapshot().lp.rootPasses.sum)
    }

    @Test
    fun `unreserved custom projections decline before emission`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 2)),
            listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1)),
            settings = ProblemSettings(lpCeilingTableauCells = 1L))
        var emitted = false
        val projections = FactorProjectionProviders(relaxation = RelaxationProjectionProvider {
            RelaxationProjection(RelaxationCapability.SOUND) { _, builder ->
                emitted = true
                builder.linearRow(LinearOp.LE, intArrayOf(0), longArrayOf(1), 1)
            }
        }).prepare(problem)
        val sink = SolveStatsSink("custom-projection")

        LpEngine(problem, LinearObjective(intCoefficients = longArrayOf(1)),
            LpParams(lpConfig = LpConfig.parse("default,+sparse-trial")), sink,
            projections = projections).use { }

        assertFalse(emitted)
        assertEquals(1L, sink.snapshot().lp.sparseTrial["DeclineProjection"])
    }

    @Test
    fun `compact estimates measure the full signed objective magnitude`() {
        val model = LpBuilder().apply { addVar(0L, 1L, cost = Long.MIN_VALUE) }.build(Sense.MINIMIZE)

        val estimate = estimateSparseLpAdmission(model)

        assertEquals(64L, estimate.metrics["ScalarBits"])
    }

    @Test
    fun `compact estimates exclude an artificial upper side`() {
        val model = LpBuilder().apply { addFreeVar(0L, null, cost = 7L) }.build(Sense.MINIMIZE)

        val estimate = estimateSparseLpAdmission(model)

        assertEquals(3L, estimate.metrics["ScalarBits"])
    }

    @Test
    fun `wide exact scalars decline numerical admission`() {
        for (originOnly in listOf(false, true)) {
            val model = LpBuilder().apply {
                if (originOnly) {
                    addRealVar(Double.MIN_VALUE, Double.MIN_VALUE)
                } else {
                    addRealVar(0.0, 1.0)
                    addRealRow(intArrayOf(0), doubleArrayOf(Double.MIN_VALUE), Relation.LE, 1.0)
                }
            }.build(Sense.MINIMIZE)

            val estimate = estimateSparseLpAdmission(model)

            assertFalse(estimate.admitted)
            assertEquals(1L, estimate.metrics["DeclineScalarWidth"])
            assertTrue(requireNotNull(estimate.metrics["ScalarBits"]) > 128L)
        }
    }

    @Test
    fun `estimates retain disconnected dimensions and row intersection risk`() {
        val model = LpBuilder().apply {
            repeat(5) { addVar(0L, 10L) }
            addRow(intArrayOf(0, 1), longArrayOf(1L, 1L), Relation.LE, 3L)
            addRow(intArrayOf(2, 3), longArrayOf(2L, 1L), Relation.LE, 4L)
        }.build(Sense.MINIMIZE)

        val estimate = estimateSparseLpAdmission(model)

        assertTrue(estimate.admitted)
        assertEquals(4L, estimate.metrics["Nonzeros"])
        assertEquals(3L, estimate.metrics["Components"])
        assertEquals(1L, estimate.metrics["LargestComponentRows"])
        assertEquals(2L, estimate.metrics["LargestComponentColumns"])
        assertEquals(4L, estimate.metrics["RowIntersectionRisk"])
    }

    @Test
    fun `ordinary root bounding consumes a sparse trial certificate beyond the size proxy`() {
        val problem = Problem(
            0, 3, Array(3) { IntDomain(0, 1) },
            listOf(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
            ), settings = ProblemSettings(lpCeilingTableauCells = 1L),
        )
        val session = PropagationSession(problem)
        val sink = SolveStatsSink("sparse-trial")
        val projections = FactorProjectionProviders.Default.prepare(problem)

        LpEngine(problem, LinearObjective(intCoefficients = longArrayOf(1, 1, 1)),
            LpParams(lpConfig = LpConfig.parse("default,+sparse-trial")), sink,
            projections = projections).use { engine ->
            assertTrue(engine.sparseTrialActive)
            assertEquals(0, engine.params.lpPlan.boundMaxDepth)
            engine.rootLpRelaxationBound(requireNotNull(engine.lpRelaxer), emptyList())

            assertTrue(engine.pruneNode(session, 2.0, -1, true))
        }

        assertEquals(1L, sink.snapshot().lp.sparseTrial["Admitted"])
        assertTrue(sink.snapshot().lp.pruned.sum > 0.0)
        assertTrue(sink.snapshot().lp.workOps.sum > 0.0)
        assertTrue((sink.snapshot().lp.sparseTrial["PreparationWork"] ?: 0L) > 0L)
    }

    @Test
    fun `default admission preserves the size refusal`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)),
            settings = ProblemSettings(lpCeilingTableauCells = 1L))

        val plan = LpAutoConfig.resolve(problem, LpConfig.AUTO)

        assertFalse(plan.bounding)
    }

    @Test
    fun `cancelled sparse admission supplies no bounding route`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)))
        var admission: SparseLpAdmission? = null
        sequence<Unit> {
            admission = prepareSparseLpAdmission(problem, null, ExecutionControl { true })
        }.forEach { }

        assertFalse(requireNotNull(admission).admitted)
        assertEquals(1L, requireNotNull(admission).metrics["DeclineCancelled"])
    }

    @Test
    fun `factor storage is bounded before source assembly`() {
        val row = Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 1)
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) }, List(66_000) { row },
            settings = ProblemSettings(lpCeilingTableauCells = 1L))

        val admission = LpAutoConfig.admission(problem, LpConfig.parse("default,+sparse-trial"), LpPlan())

        assertFalse(admission.plan.bounding)
        assertEquals(1L, requireNotNull(admission.sparseTrial).metrics["DeclineShape"])
    }
}
