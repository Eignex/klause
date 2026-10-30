package com.eignex.klause.lp.bounding

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
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpCertificationPolicy
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpEngineFactory
import com.eignex.klause.lp.engine.LpFloatAllowance
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.klause.lp.engine.PersistentLpSolver
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.VarStatus
import com.eignex.klause.lp.engine.continueExactLp
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ContinuationDecline
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.RelaxationCapability
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SparseLpAdmissionTest {
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
                    cancellation: Cancellation,
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
                                    workOps = engine.sparseTrialRemainingWork() - checkingWork,
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
        val policy = LpCertificationPolicy { certifier, successful ->
            if (certifier == LpCertifier.RATIONAL && successful) {
                refusals++
                false
            } else successful
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
    fun `sparse trials preserve the ordinary root probe intent`() {
        for (ceiling in listOf(1L, 1_000_000L)) {
            val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
                listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)),
                settings = ProblemSettings(lpCeilingTableauCells = ceiling))
            val ordinary = LpAutoConfig.resolve(problem, LpConfig.AUTO)

            val trial = LpAutoConfig.resolve(problem, LpConfig.parse("default,+sparse-trial"))

            assertEquals(ordinary.probe, trial.probe)
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
    fun `cancelled sparse root continuation publishes no partial bound`() {
        val problem = Problem(0, 96, Array(96) { IntDomain(0, 1) },
            List(95) { Linear(intArrayOf(1, 1), intArrayOf(it, it + 1), LinearOp.GE, 1) },
            settings = ProblemSettings(lpCeilingTableauCells = 1L))
        val sink = SolveStatsSink("cancelled-sparse-root")
        val stop = Cancellation { (sink.lp.sparseTrial["RootPauses"] ?: 0L) > 0L }

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
        assertTrue(sink.snapshot().lp.phases.filterKeys { it.startsWith("TEMPLATE_ASSEMBLY_") }.values
            .sumOf { it.outcomes["ROWS"] ?: 0L } > 0L)
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
            admission = prepareSparseLpAdmission(problem, null, Cancellation { true })
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
