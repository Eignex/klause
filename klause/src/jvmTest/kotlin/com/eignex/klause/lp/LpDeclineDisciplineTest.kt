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
import com.eignex.klause.lp.engine.LpCertificationObserver
import com.eignex.klause.lp.engine.LpCertificationPolicy
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpEngineFactory
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpFloatAllowance
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpNeighborhood
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpSolver
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.PersistentLpSolver
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.lp.engine.Relation
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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText
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
    REBIND,
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

    override fun newGeneralSolver(model: LpModel, cancellation: Cancellation, pricing: LpPricingOptions): LpSolver {
        calls += DeclineCall.GENERAL
        cancellations += cancellation
        return RecordingSolver(ProductionLpEngineFactory.newGeneralSolver(model, cancellation, pricing), calls)
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
    ComponentLpSolverCapability {
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

    override fun rebind(next: LpModel, token: Cancellation): Boolean {
        calls += DeclineCall.REBIND
        return persistent.rebind(next, token)
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
    fun `standalone Farkas and rational proofs decline to indeterminate`() {
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
    fun `standalone feasible proof ladder cannot surface SAT or optimum after decline`() {
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
    fun `component consumer cannot combine declined block proofs into an optimum`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 3L, cost = 1L)
        val y = builder.addVar(0L, 3L, cost = 1L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        builder.addRow(intArrayOf(y), longArrayOf(1L), Relation.GE, 2L)
        val model = builder.build(Sense.MINIMIZE)
        val harness = harness()

        val declined = solveAndCertify(model, context = harness.context)

        val accepted = solveAndCertify(model)
        assertEquals(LpVerdict.ATTAINED_OPTIMUM, accepted.verdict)
        assertEquals(3L, accepted.integerObjectiveLowerBound)
        assertEquals(LpVerdict.INDETERMINATE, declined.verdict)
        assertNull(declined.integerObjectiveLowerBound)
        assertEquals(2, harness.factory.calls.count { it == DeclineCall.GENERAL })
        assertEquals(1, harness.factory.calls.count { it == DeclineCall.COMPONENT })
        assertEquals(3, harness.factory.calls.count { it == DeclineCall.CLOSE })
        assertTrue(harness.policy.attempts.count { it == LpCertifier.INTEGER to true } >= 2)
    }

    @Test
    fun `standalone and engine leaf consumers preserve an unresolved real leaf`() {
        val problem = continuousEqualityProblem()
        val sample = Sample(booleanArrayOf(), longArrayOf())
        val standaloneHarness = harness()
        val standalone = leafRealFeasibility(
            problem,
            objective = null,
            sample = sample,
            context = standaloneHarness.context,
        )
        val engineHarness = harness()
        val engine = engine(
            problem,
            LinearObjective(intCoefficients = LongArray(0)),
            engineHarness,
            LpPlan(bounding = true, realResidual = true),
        )

        val leaf = engine.leafCertify(PropagationSession(problem))

        assertEquals(LpVerdict.ATTAINED_OPTIMUM, leafRealFeasibility(problem, null, sample).verdict)
        assertEquals(LpVerdict.INDETERMINATE, standalone.verdict)
        assertEquals(LpVerdict.INDETERMINATE, leaf.verdict)
        assertTrue(standalone.reals.isEmpty())
        assertTrue(leaf.reals.isEmpty())
        assertTrue(standaloneHarness.factory.calls.contains(DeclineCall.GENERAL))
        assertTrue(engineHarness.factory.calls.contains(DeclineCall.GENERAL))
        assertEquals(
            standaloneHarness.factory.calls.count { it == DeclineCall.GENERAL },
            standaloneHarness.factory.calls.count { it == DeclineCall.CLOSE },
        )
        assertEquals(
            engineHarness.factory.calls.count { it == DeclineCall.GENERAL },
            engineHarness.factory.calls.count { it == DeclineCall.CLOSE },
        )
    }

    @Test
    fun `MPS real leaf decline reaches the unknown solve terminal`() {
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
                LpVerdict.ATTAINED_OPTIMUM, LpVerdict.FEASIBLE, LpVerdict.UNBOUNDED -> ComponentCheck.Feasible
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
        assertTrue(harness.factory.calls.contains(DeclineCall.GENERAL))
        assertTrue(harness.policy.observedSuccess(LpCertifier.EXACT_BASIS))
        assertTrue(harness.policy.observedSuccess(LpCertifier.EXACT_POINT))
        assertTrue(harness.policy.observedSuccess(LpCertifier.RATIONAL))
        assertEquals(
            harness.factory.calls.count { it == DeclineCall.GENERAL },
            harness.factory.calls.count { it == DeclineCall.CLOSE },
        )
    }

    @Test
    fun `node root and recovery bounds cannot prune or become finite after decline`() {
        val problem = triangleCover()
        val objective = LinearObjective(intCoefficients = longArrayOf(1L, 1L, 1L))
        val nodeHarness = harness()
        val node = engine(problem, objective, nodeHarness)
        val session = PropagationSession(problem)

        try {
            assertFalse(node.pruneNode(session, 1.5, -1, true))
            assertFalse(node.pruneNode(PropagationSession(problem), 1.5, -1, true))
            assertTrue(nodeHarness.factory.calls.contains(DeclineCall.PERSISTENT))
            assertFalse(nodeHarness.factory.calls.contains(DeclineCall.REBIND))
            assertTrue(nodeHarness.factory.calls.contains(DeclineCall.ADOPT))
            assertTrue(nodeHarness.factory.calls.contains(DeclineCall.PREPARE_LOGICALS))
            assertTrue(nodeHarness.factory.calls.contains(DeclineCall.RESOLVE_BOUNDS))
            assertFalse(nodeHarness.factory.calls.contains(DeclineCall.TABLEAU))
            assertTrue(nodeHarness.policy.observedSuccess(LpCertifier.INTEGER))
            assertTrue(nodeHarness.policy.observedSuccess(LpCertifier.SAFE_OBJECTIVE))
        } finally {
            node.close()
        }

        val rootHarness = harness()
        val root = engine(problem, objective, rootHarness)
        assertTrue(root.rootLpRelaxationBound(checkNotNull(root.lpRelaxer), emptyList()).isNaN())
        assertTrue(rootHarness.factory.calls.contains(DeclineCall.TABLEAU))
        assertEquals(
            rootHarness.factory.calls.count { it == DeclineCall.TABLEAU },
            rootHarness.factory.calls.count { it == DeclineCall.CLOSE },
        )

        val recoveryHarness = harness()
        val recovery = engine(problem, objective, recoveryHarness)
        val recoveryOutcome = recovery.sparseCertifiedPrune(
            checkNotNull(recovery.lpRelaxer),
            PropagationSession(problem),
            1.5,
            SolveStatsSink("recovery-consumer"),
            Cancellation.Never,
        )
        assertFalse(recoveryOutcome.prune)
        assertTrue(recoveryHarness.factory.calls.contains(DeclineCall.TABLEAU))
        assertEquals(
            recoveryHarness.factory.calls.count { it == DeclineCall.TABLEAU },
            recoveryHarness.factory.calls.count { it == DeclineCall.CLOSE },
        )

        val positiveFactory = ConsumerRecordingFactory()
        val positive = LpEngine(
            problem,
            objective,
            LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink("positive-control"),
            LpSolveContext(engineFactory = positiveFactory),
        )
        try {
            assertTrue(positive.pruneNode(PropagationSession(problem), 1.5, -1, true))
            assertTrue(positive.rootLpRelaxationBound(checkNotNull(positive.lpRelaxer), emptyList()).isFinite())
        } finally {
            positive.close()
        }
    }

    @Test
    fun `root Farkas consumer cannot report infeasibility after decline`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 1)),
            arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 2)),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(0L))
        val harness = harness()
        val rejecting = engine(problem, objective, harness)

        assertFalse(rejecting.rootLpInfeasibleNoBake(Cancellation.Never))
        assertTrue(productionEngine(problem, objective).rootLpInfeasibleNoBake(Cancellation.Never))
        assertTrue(harness.policy.observedSuccess(LpCertifier.EXACT_FARKAS))
        assertTrue(harness.factory.calls.contains(DeclineCall.TABLEAU))
        assertEquals(1, harness.factory.calls.count { it == DeclineCall.CLOSE })
    }

    @Test
    fun `gated persistent candidate cannot prune when its Farkas proof declines`() {
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
    fun `shaving consumers emit no bound or row fact after decline`() {
        val boundProblem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 10)),
            arrayOf<Factor>(Linear(intArrayOf(2), intArrayOf(0), LinearOp.GE, 5)),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(1L))
        val boundHarness = harness()
        val rejectingBounds = engine(boundProblem, objective, boundHarness)

        assertEquals(emptyList(), rejectingBounds.rootLpBoundsNoBake(Cancellation.Never))
        val positiveBounds = productionEngine(boundProblem, objective).rootLpBoundsNoBake(Cancellation.Never)
        assertTrue(positiveBounds.any { it.varId == 0 && it.lo == 3L })
        assertTrue(boundHarness.factory.calls.contains(DeclineCall.SOLVE_PRIMAL))
        assertTrue(boundHarness.policy.observedSuccess(LpCertifier.SAFE_OBJECTIVE))
        assertEquals(
            boundHarness.factory.calls.count { it == DeclineCall.TABLEAU },
            boundHarness.factory.calls.count { it == DeclineCall.CLOSE },
        )

        val redundantProblem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 4)),
            arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 4)),
        )
        val rowHarness = harness()
        val rejectingRows = engine(redundantProblem, objective, rowHarness)
        assertEquals(emptyList(), rejectingRows.redundantConstraints(Cancellation.Never))
        assertEquals(
            listOf(0),
            productionEngine(redundantProblem, objective).redundantConstraints(Cancellation.Never),
        )
        assertTrue(rowHarness.factory.calls.contains(DeclineCall.TABLEAU))
        assertEquals(
            rowHarness.factory.calls.count { it == DeclineCall.TABLEAU },
            rowHarness.factory.calls.count { it == DeclineCall.CLOSE },
        )
    }

    @Test
    fun `open bound and refutation consumers keep the model open after decline`() {
        val bounds = arrayOf(OpenIntBounds(null, null), OpenIntBounds(null, null))
        val rows = listOf(
            Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 10),
        )
        val boundHarness = harness()

        val declined = tightenOpenIntBounds(bounds, rows, context = boundHarness.context)
        val accepted = tightenOpenIntBounds(bounds, rows)

        assertNull(declined.bounds[0].lo)
        assertNull(declined.bounds[0].hi)
        assertEquals(5L, accepted.bounds[0].hi)
        assertTrue(boundHarness.factory.calls.contains(DeclineCall.SOLVE_PRIMAL))
        assertTrue(boundHarness.policy.observedSuccess(LpCertifier.SAFE_OBJECTIVE))
        assertEquals(
            boundHarness.factory.calls.count { it == DeclineCall.GENERAL },
            boundHarness.factory.calls.count { it == DeclineCall.CLOSE },
        )

        val contradictory = listOf(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 2),
        )
        val refutationHarness = harness()
        assertFalse(
            openLpInfeasible(
                arrayOf(OpenIntBounds(null, null)),
                contradictory,
                context = refutationHarness.context,
            ),
        )
        assertTrue(openLpInfeasible(arrayOf(OpenIntBounds(null, null)), contradictory))
        assertTrue(refutationHarness.policy.observedSuccess(LpCertifier.RATIONAL))
        assertTrue(refutationHarness.factory.calls.contains(DeclineCall.SOLVE))
        assertEquals(1, refutationHarness.factory.calls.count { it == DeclineCall.CLOSE })
    }

    @Test
    fun `cancellation leaves the final rational fallback unknown`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 1.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 2.0)
        val token = Cancellation { true }
        val harness = harness()

        val result = solveAndCertify(
            builder.build(Sense.MINIMIZE),
            cancellation = token,
            componentSplit = false,
            context = harness.context,
        )

        assertEquals(LpVerdict.INDETERMINATE, result.verdict)
        assertTrue(harness.factory.cancellations.all { it === token })
        assertTrue(LpCertifier.RATIONAL to false in harness.policy.attempts)
        assertFalse(harness.policy.observedSuccess(LpCertifier.RATIONAL))
        assertEquals(
            harness.factory.calls.count { it == DeclineCall.GENERAL },
            harness.factory.calls.count { it == DeclineCall.CLOSE },
        )
    }

    @Test
    fun `optimizer terminal mapping preserves indeterminate`() {
        val root = repositoryRoot()
        val search = source(root, "klause/src/commonMain/kotlin/com/eignex/klause/backtrack/Search.kt")
        val optimize = source(root, "klause/src/commonMain/kotlin/com/eignex/klause/backtrack/ResumableMinimize.kt")

        assertTrue("LpVerdict.INDETERMINATE, LpVerdict.CERTIFIED_BOUND -> ComponentCheck.Indeterminate" in search)
        assertTrue(
            (
                "LpVerdict.INDETERMINATE, LpVerdict.CERTIFIED_BOUND -> {\n" +
                    "                        sawIndeterminateLeaf = true"
                ) in optimize,
        )
        assertTrue("sawIndeterminateLeaf -> MinimizeResult.Unknown" in optimize)
    }

    @Test
    fun `open theory uses the shared LP owner while exact arithmetic stays independent`() {
        val root = repositoryRoot()
        val exactRoot = root.resolve("klause/src/commonMain/kotlin/com/eignex/klause")
        val exactSources = kotlinSources(exactRoot.resolve("theory/qflra")) +
            kotlinSources(exactRoot.resolve("simplex/exact")) +
            kotlinSources(exactRoot.resolve("lp/lattice")) +
            listOf(
                exactRoot.resolve("lp/ExactFactorComparison.kt"),
                exactRoot.resolve("lp/ExactMixedEchelonHermite.kt"),
            )
        val exactPaths = exactSources.map { root.relativize(it).toString().replace('\\', '/') }

        for (required in listOf("ExactLraSolver.kt", "QfLraSystem.kt", "QfLiraSolver.kt")) {
            assertTrue(exactPaths.any { it.endsWith(required) }, "exact source discovery missed $required")
        }
        for (path in exactSources) {
            val code = LpBoundaryScanner.codeOnly(path.readText())
            val relative = root.relativize(path).toString()
            val allowed = when (path.fileName.toString()) {
                "QfLiraSolver.kt" -> setOf(
                    "LpSolveContext",
                    "LpVerdict",
                    "LpCertificationObserver",
                    "LpCertifier",
                    "LpSolveMetrics",
                )

                "QfLraSystem.kt" -> setOf(
                    "ExactLpBounds", "ExactLpColumn", "ExactLpEntry", "ExactLpModel", "ExactLpNumber",
                    "ExactLpObjective", "ExactLpRow", "ExactLpSide", "LpScopedRow",
                )

                else -> emptySet()
            }
            for (reference in Regex("com\\.eignex\\.klause\\.lp\\.engine\\.([A-Za-z_*]+)").findAll(code)) {
                assertTrue(reference.groupValues[1] in allowed, "$relative: ${reference.value}")
            }
            assertFalse("solveAndCertify" in code, relative)
            assertFalse("newLpSolver" in code, relative)
            assertFalse("newPersistentSolver" in code, relative)
        }
        assertTrue("bigRationalOutcome" in source(root, exactPaths.single { it.endsWith("ExactLraSolver.kt") }))
        assertTrue("ExactIntegerSearch" in source(root, exactPaths.single { it.endsWith("QfLiraSolver.kt") }))
        assertTrue("LpPropagator(" in source(root, exactPaths.single { it.endsWith("QfLiraSolver.kt") }))
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

    private fun continuousEqualityProblem(): Problem {
        val row = Linear(longArrayOf(), intArrayOf(), doubleArrayOf(2.0), intArrayOf(0), LinearOp.EQ, 3L)
        return Problem(
            0,
            0,
            emptyArray(),
            arrayOf<Factor>(row),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(10.0),
        )
    }

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

    private fun repositoryRoot(): Path {
        var path = Paths.get("").toAbsolutePath()
        while (!Files.isRegularFile(path.resolve("settings.gradle.kts"))) {
            path = checkNotNull(path.parent) { "cannot locate repository root" }
        }
        return path
    }

    private fun source(root: Path, relative: String): String = root.resolve(relative).readText()

    private fun kotlinSources(directory: Path): List<Path> = Files.walk(directory).use { paths ->
        paths.iterator().asSequence().filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.toList()
    }
}
