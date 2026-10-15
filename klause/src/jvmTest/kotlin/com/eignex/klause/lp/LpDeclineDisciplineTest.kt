package com.eignex.klause.lp

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.formats.mps.MpsConstraint
import com.eignex.klause.formats.mps.MpsModel
import com.eignex.klause.formats.mps.MpsObjective
import com.eignex.klause.formats.mps.MpsVar
import com.eignex.klause.formats.mps.toProblem
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.bounding.redundantConstraints
import com.eignex.klause.lp.bounding.rootLpBoundsNoBake
import com.eignex.klause.lp.bounding.rootLpInfeasibleNoBake
import com.eignex.klause.lp.bounding.rootLpRelaxationBound
import com.eignex.klause.lp.bounding.sparseCertifiedPrune
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.CertifiedLpBound
import com.eignex.klause.lp.engine.ComponentLpSolverCapability
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.ExactLpWitness
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpCapture
import com.eignex.klause.lp.engine.LpCertificationObserver
import com.eignex.klause.lp.engine.LpCertificationPolicy
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpEngineFactory
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpFloatAllowance
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpNeighborhood
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpReplay
import com.eignex.klause.lp.engine.LpReplayEvent
import com.eignex.klause.lp.engine.LpReplaySettings
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpSolver
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.PersistentLpSolver
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.RetainedComponentLpSolverCapability
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.TableauCutSolver
import com.eignex.klause.lp.engine.solveAndCertify
import com.eignex.klause.lp.relaxation.leafRealFeasibility
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.util.Cancellation
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private enum class DeclineCall {
    GENERAL,
    COMPONENT,
    TABLEAU,
    PERSISTENT,
    SOLVE,
    SOLVE_PRIMAL,
    ADOPT,
    PREPARE_LOGICALS,
    RESOLVE_BOUNDS,
    RESOLVE_GATED,
    CLOSE,
}

private class DecliningPolicy : LpCertificationPolicy {
    val attempts = ArrayList<Pair<LpCertifier, Boolean>>()

    override fun accepts(certifier: LpCertifier, successful: Boolean): Boolean {
        attempts += certifier to successful
        return false
    }

    fun observedSuccess(certifier: LpCertifier): Boolean = certifier to true in attempts
}

private class ConsumerRecordingFactory : LpEngineFactory {
    val calls = ArrayList<DeclineCall>()
    val cancellations = ArrayList<Cancellation>()

    override fun newGeneralSolver(
        model: LpModel,
        cancellation: Cancellation,
        workLimit: Long,
        pricing: LpPricingOptions,
    ): LpSolver {
        calls += DeclineCall.GENERAL
        cancellations += cancellation
        val delegate = ProductionLpEngineFactory.newGeneralSolver(model, cancellation, workLimit, pricing)
        return RecordingSolver(delegate, calls)
    }

    override fun newComponentSolver(
        model: LpModel,
        parts: List<LpNeighborhood>,
        solvers: List<LpSolver>,
        isolated: IntArray,
    ): ComponentLpSolverCapability {
        calls += DeclineCall.COMPONENT
        val delegate = ProductionLpEngineFactory.newComponentSolver(model, parts, solvers, isolated)
        return RecordingComponentSolver(delegate, calls)
    }

    override fun newRetainedComponentSolver(
        model: LpModel,
        parts: List<LpNeighborhood>,
        solvers: List<LpSolver>,
        isolated: IntArray,
    ): RetainedComponentLpSolverCapability {
        calls += DeclineCall.COMPONENT
        return RecordingComponentSolver(
            ProductionLpEngineFactory.newRetainedComponentSolver(model, parts, solvers, isolated),
            calls,
        )
    }

    override fun newTableauSolver(
        model: LpModel,
        cancellation: Cancellation,
        iterationLimit: Int,
        workLimit: Long,
        trackDegeneracy: Boolean,
        pricing: LpPricingOptions,
    ): TableauCutSolver {
        calls += DeclineCall.TABLEAU
        cancellations += cancellation
        val delegate = ProductionLpEngineFactory.newTableauSolver(
            model,
            cancellation,
            iterationLimit,
            workLimit,
            trackDegeneracy,
            pricing,
        )
        return RecordingTableauSolver(delegate, calls)
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
        calls += DeclineCall.PERSISTENT
        cancellations += cancellation
        val delegate = ProductionLpEngineFactory.newPersistentSolver(
            model,
            cancellation,
            refactorUpdateLimit,
            iterationLimit,
            workLimit,
            trackDegeneracy,
            pricing,
        )
        return RecordingPersistentSolver(delegate, calls)
    }
}

private open class RecordingSolver(protected val delegate: LpSolver, private val calls: MutableList<DeclineCall>) :
    LpSolver by delegate {
    override fun solve(warm: Basis?): FloatLpResult? {
        calls += DeclineCall.SOLVE
        return delegate.solve(warm)
    }

    override fun solvePrimal(warm: Basis?): FloatLpResult? {
        calls += DeclineCall.SOLVE_PRIMAL
        return delegate.solvePrimal(warm)
    }

    override fun close() {
        calls += DeclineCall.CLOSE
        delegate.close()
    }
}

private class RecordingComponentSolver(
    private val component: ComponentLpSolverCapability,
    calls: MutableList<DeclineCall>,
) : RecordingSolver(component, calls),
    RetainedComponentLpSolverCapability {
    override fun adopt(state: LpExactState, token: Cancellation): Boolean =
        (component as? RetainedComponentLpSolverCapability)?.adopt(state, token) ?: false

    override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? =
        (component as? RetainedComponentLpSolverCapability)?.resolveBounds(allowance)

    override fun exactBound(observer: LpCertificationObserver?, policy: LpCertificationPolicy): CertifiedLpBound? =
        component.exactBound(observer, policy)

    override fun exactWitness(
        observer: LpCertificationObserver?,
        policy: LpCertificationPolicy,
        cancellation: Cancellation,
    ): ExactLpWitness? = component.exactWitness(observer, policy, cancellation)
}

private class RecordingTableauSolver(private val tableau: TableauCutSolver, calls: MutableList<DeclineCall>) :
    RecordingSolver(tableau, calls),
    TableauCutSolver {
    override fun gomoryCuts(maxCuts: Int): List<Cut> = tableau.gomoryCuts(maxCuts)

    override fun mirCuts(maxCuts: Int): List<Cut> = tableau.mirCuts(maxCuts)
}

private class RecordingPersistentSolver(
    private val persistent: PersistentLpSolver,
    private val calls: MutableList<DeclineCall>,
) : RecordingSolver(persistent, calls),
    PersistentLpSolver {
    override fun prepareLogicals(token: Cancellation): Basis? {
        calls += DeclineCall.PREPARE_LOGICALS
        return persistent.prepareLogicals(token)
    }

    override fun adopt(state: LpExactState, token: Cancellation): Boolean {
        calls += DeclineCall.ADOPT
        return persistent.adopt(state, token)
    }

    override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? {
        calls += DeclineCall.RESOLVE_BOUNDS
        return persistent.resolveBounds(allowance)
    }

    override fun resolveGated(enforced: BooleanArray): FloatLpResult? {
        calls += DeclineCall.RESOLVE_GATED
        return persistent.resolveGated(enforced)
    }
}

class LpDeclineDisciplineTest {

    companion object {
        private lateinit var finiteMpsProblem: Problem
        private lateinit var terminalProblem: BakedProblem

        @BeforeClass
        @JvmStatic
        fun warmLpRuntime() {
            val builder = LpBuilder()
            val x = builder.addVar(0L, 1L)
            builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 2L)
            solveAndCertify(builder.build(Sense.MINIMIZE), componentSplit = false)

            finiteMpsProblem = MpsModel(
                name = "decline",
                sense = ObjectiveSense.MINIMIZE,
                objective = MpsObjective("", IntArray(0), DoubleArray(0), 0.0),
                variables = listOf(MpsVar("x", integer = false, lower = 0.0, upper = 1.0)),
                constraints = listOf(
                    MpsConstraint("fixed", intArrayOf(0), doubleArrayOf(1.0), lower = 0.5, upper = 0.5),
                ),
            ).toProblem().model
            terminalProblem = Problem(0, 0, emptyArray(), emptyArray()).bake()
            BacktrackSolver(terminalProblem).solve(
                BacktrackParams(
                    componentFactory = {
                        listOf(object : SearchComponent {
                            override fun check(context: SearchContext) = ComponentCheck.Indeterminate
                        })
                    },
                ),
            )

            val rootProblem = Problem(
                0,
                1,
                arrayOf(IntDomain(0, 1)),
                arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 2)),
            )
            LpEngine(
                rootProblem,
                LinearObjective(intCoefficients = longArrayOf(0L)),
                LpParams(lpPlan = LpPlan(bounding = true)),
                SolveStatsSink("runtime-warmup"),
            ).rootLpInfeasibleNoBake(Cancellation.Never)

            val gatedProblem = gatedRealProblem()
            val gatedSession = PropagationSession(gatedProblem)
            gatedSession.implyBool(0, true)
            val factory = ConsumerRecordingFactory()
            LpEngine(
                gatedProblem,
                LinearObjective(intCoefficients = LongArray(0)),
                LpParams(lpPlan = LpPlan(bounding = true, realResidual = true)),
                SolveStatsSink("runtime-warmup"),
                LpSolveContext(engineFactory = factory),
            ).use { engine -> engine.pruneNode(gatedSession, Double.POSITIVE_INFINITY, -1, true) }
        }

        private fun gatedRealProblem(): Problem = Problem(
            1,
            0,
            emptyArray(),
            arrayOf<Factor>(
                ReifiedRealLinear(
                    aux = 0,
                    vars = IntArray(0),
                    intCoeffs = DoubleArray(0),
                    realVars = intArrayOf(0),
                    realCoeffs = doubleArrayOf(1.0),
                    op = LinearOp.GE,
                    bound = 2.0,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
    }

    @Test
    fun `standalone Farkas and rational proofs should decline to indeterminate`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 1L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 2L)
        val model = builder.build(Sense.MINIMIZE)
        val harness = harness()

        val declined = solveAndCertify(model, componentSplit = false, context = harness.context)

        assertEquals(LpVerdict.INFEASIBLE, solveAndCertify(model, componentSplit = false).verdict)
        assertEquals(LpVerdict.INDETERMINATE, declined.verdict)
        assertNull(declined.farkasRay)
        assertTrue(harness.policy.observedSuccess(LpCertifier.EXACT_FARKAS))
        assertTrue(harness.policy.observedSuccess(LpCertifier.RATIONAL))
        assertContentEquals(
            listOf(DeclineCall.GENERAL, DeclineCall.SOLVE, DeclineCall.CLOSE),
            harness.factory.calls,
        )
    }

    @Test
    fun `standalone feasible proof ladder should not surface SAT or optimum after decline`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 1.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.EQ, 0.5)
        val model = builder.build(Sense.MINIMIZE)
        val harness = harness()

        val declined = solveAndCertify(model, componentSplit = false, context = harness.context)

        assertEquals(LpVerdict.ATTAINED_OPTIMUM, solveAndCertify(model, componentSplit = false).verdict)
        assertEquals(LpVerdict.INDETERMINATE, declined.verdict)
        assertNull(declined.integerObjectiveLowerBound)
        assertNull(declined.exactPrimal)
        assertNull(declined.safeLowerBound)
        assertTrue(harness.policy.observedSuccess(LpCertifier.EXACT_BASIS))
        assertTrue(harness.policy.observedSuccess(LpCertifier.EXACT_POINT))
        assertTrue(harness.policy.observedSuccess(LpCertifier.RATIONAL))
        assertTrue(harness.policy.observedSuccess(LpCertifier.SAFE_OBJECTIVE))
    }

    @Test
    fun `component consumer should not combine declined block proofs into an optimum`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 3L, cost = 1L)
        val y = builder.addVar(0L, 3L, cost = 1L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        builder.addRow(intArrayOf(y), longArrayOf(1L), Relation.GE, 2L)
        val model = builder.build(Sense.MINIMIZE)
        val harness = harness()

        val capture = LpCapture.capture(
            model,
            LpReplaySettings("component-decline", 570L),
            listOf(LpReplayEvent.Solve()),
        )
        val declined = LpReplay.replay(capture, context = harness.context).steps.single()

        val accepted = LpReplay.replay(capture).steps.single()
        assertEquals(LpVerdict.ATTAINED_OPTIMUM, accepted.productionVerdict)
        assertEquals(3L, accepted.integerObjectiveLowerBound)
        assertEquals(LpVerdict.INDETERMINATE, declined.productionVerdict)
        assertNull(declined.integerObjectiveLowerBound)
        assertEquals(2, harness.factory.calls.count { it == DeclineCall.GENERAL })
        assertEquals(1, harness.factory.calls.count { it == DeclineCall.COMPONENT })
        assertEquals(3, harness.factory.calls.count { it == DeclineCall.CLOSE })
        assertTrue(harness.policy.attempts.count { it == LpCertifier.INTEGER to true } >= 2)
    }

    @Test
    fun `MPS real leaf decline should reach the unknown solve terminal`() {
        val harness = harness()
        val component = object : SearchComponent {
            override fun check(context: SearchContext): ComponentCheck = when (
                leafRealFeasibility(
                    finiteMpsProblem,
                    objective = null,
                    sample = Sample(booleanArrayOf(), longArrayOf()),
                    context = harness.context,
                ).verdict
            ) {
                LpVerdict.ATTAINED_OPTIMUM,
                LpVerdict.TOLERANCE_OPTIMUM,
                LpVerdict.FEASIBLE,
                LpVerdict.UNBOUNDED,
                -> ComponentCheck.Feasible

                LpVerdict.INFEASIBLE -> ComponentCheck.Infeasible()

                LpVerdict.INDETERMINATE, LpVerdict.CERTIFIED_BOUND -> ComponentCheck.Indeterminate
            }
        }

        val declined = BacktrackSolver(terminalProblem).solve(
            BacktrackParams(componentFactory = { listOf(component) }),
        )
        val accepted = leafRealFeasibility(
            finiteMpsProblem,
            objective = null,
            sample = Sample(booleanArrayOf(), longArrayOf()),
        )

        assertEquals(LpVerdict.ATTAINED_OPTIMUM, accepted.verdict)
        assertEquals(TerminationReason.Unsupported, assertIs<SolveResult.Unknown>(declined).reason)
        assertTrue(harness.policy.observedSuccess(LpCertifier.EXACT_BASIS))
        assertEquals(
            harness.factory.calls.count { it == DeclineCall.GENERAL },
            harness.factory.calls.count { it == DeclineCall.CLOSE },
        )
    }

    @Test
    fun `node bound should not prune after decline`() {
        val problem = triangleCover()
        val harness = harness()
        val declining = engine(problem, triangleObjective, harness)
        val accepting = productionEngine(problem, triangleObjective)

        try {
            assertFalse(declining.pruneNode(PropagationSession(problem), 1.5, -1, true))
            assertTrue(accepting.pruneNode(PropagationSession(problem), 1.5, -1, true))
            assertTrue(harness.factory.calls.contains(DeclineCall.RESOLVE_BOUNDS))
            assertTrue(harness.policy.observedSuccess(LpCertifier.SAFE_OBJECTIVE))
        } finally {
            declining.close()
            accepting.close()
        }
    }

    @Test
    fun `root relaxation bound should stay NaN after decline`() {
        val problem = triangleCover()
        val harness = harness()
        val root = engine(problem, triangleObjective, harness)

        val bound = root.rootLpRelaxationBound(checkNotNull(root.lpRelaxer), emptyList())

        assertTrue(bound.isNaN())
        assertTrue(harness.factory.calls.contains(DeclineCall.TABLEAU))
        assertEquals(
            harness.factory.calls.count { it == DeclineCall.TABLEAU },
            harness.factory.calls.count { it == DeclineCall.CLOSE },
        )
    }

    @Test
    fun `sparse recovery should not prune after decline`() {
        val problem = triangleCover()
        val harness = harness()
        val recovery = engine(problem, triangleObjective, harness)

        val outcome = recovery.sparseCertifiedPrune(
            checkNotNull(recovery.lpRelaxer),
            PropagationSession(problem),
            1.5,
            SolveStatsSink("recovery-consumer"),
            Cancellation.Never,
        )

        assertFalse(outcome.prune)
        assertTrue(harness.factory.calls.contains(DeclineCall.TABLEAU))
    }

    @Test
    fun `gated persistent candidate should not prune when its Farkas proof declines`() {
        val problem = gatedRealProblem()
        val harness = harness()
        val rejecting = engine(
            problem,
            LinearObjective(intCoefficients = LongArray(0)),
            harness,
            LpPlan(bounding = true, realResidual = true),
        )
        val session = PropagationSession(problem)
        session.implyBool(0, true)

        try {
            val pruned = rejecting.pruneNode(session, Double.POSITIVE_INFINITY, -1, true)

            assertFalse(pruned)
            assertTrue(harness.factory.calls.contains(DeclineCall.RESOLVE_GATED))
            assertTrue(harness.policy.observedSuccess(LpCertifier.EXACT_FARKAS))
        } finally {
            rejecting.close()
        }
    }

    @Test
    fun `bound shaving should emit no bound after decline`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 10)),
            arrayOf<Factor>(Linear(intArrayOf(2), intArrayOf(0), LinearOp.GE, 5)),
        )
        val harness = harness()

        val declined = engine(problem, unitObjective, harness).rootLpBoundsNoBake(Cancellation.Never)
        val accepted = productionEngine(problem, unitObjective).rootLpBoundsNoBake(Cancellation.Never)

        assertEquals(emptyList(), declined)
        assertTrue(accepted.any { it.varId == 0 && it.lo == 3L })
        assertTrue(harness.policy.observedSuccess(LpCertifier.SAFE_OBJECTIVE))
    }

    @Test
    fun `redundant row detection should emit no row after decline`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 4)),
            arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 4)),
        )
        val harness = harness()

        val declined = engine(problem, unitObjective, harness).redundantConstraints(Cancellation.Never)
        val accepted = productionEngine(problem, unitObjective).redundantConstraints(Cancellation.Never)

        assertEquals(emptyList(), declined)
        assertEquals(listOf(0), accepted)
        assertTrue(harness.factory.calls.contains(DeclineCall.TABLEAU))
    }

    @Test
    fun `open bound tightening should keep bounds open after decline`() {
        val bounds = arrayOf(OpenIntBounds(null, null), OpenIntBounds(null, null))
        val rows = listOf(
            Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 10),
        )
        val harness = harness()

        val declined = tightenOpenIntBounds(bounds, rows, context = harness.context)
        val accepted = tightenOpenIntBounds(bounds, rows)

        assertNull(declined.bounds[0].lo)
        assertNull(declined.bounds[0].hi)
        assertEquals(5L, accepted.bounds[0].hi)
        assertTrue(harness.policy.observedSuccess(LpCertifier.SAFE_OBJECTIVE))
        assertEquals(
            harness.factory.calls.count { it == DeclineCall.PERSISTENT },
            harness.factory.calls.count { it == DeclineCall.CLOSE },
        )
    }

    @Test
    fun `open refutation should not report infeasible after decline`() {
        val bounds = arrayOf(OpenIntBounds(null, null))
        val contradictory = listOf(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 2),
        )
        val harness = harness()

        val declined = openLpInfeasible(bounds, contradictory, context = harness.context)

        assertFalse(declined)
        assertTrue(openLpInfeasible(bounds, contradictory))
        assertTrue(harness.policy.observedSuccess(LpCertifier.RATIONAL))
    }

    private class Harness(val factory: ConsumerRecordingFactory, val policy: DecliningPolicy) {
        val context = LpSolveContext(factory, policy)
    }

    private fun harness(): Harness = Harness(ConsumerRecordingFactory(), DecliningPolicy())

    private fun engine(
        problem: Problem,
        objective: LinearObjective,
        harness: Harness,
        plan: LpPlan = LpPlan(bounding = true),
    ): LpEngine = LpEngine(
        problem,
        objective,
        LpParams(lpPlan = plan),
        SolveStatsSink("decline-consumer"),
        harness.context,
    )

    private fun productionEngine(
        problem: Problem,
        objective: LinearObjective,
        plan: LpPlan = LpPlan(bounding = true),
    ): LpEngine = LpEngine(problem, objective, LpParams(lpPlan = plan), SolveStatsSink("positive-control"))

    private val unitObjective = LinearObjective(intCoefficients = longArrayOf(1L))

    private val triangleObjective = LinearObjective(intCoefficients = longArrayOf(1L, 1L, 1L))

    private fun triangleCover(): Problem = Problem(
        0,
        3,
        Array(3) { IntDomain(0, 1) },
        arrayOf<Factor>(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
        ),
    )
}
