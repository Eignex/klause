package com.eignex.klause.lp

import com.eignex.klause.lp.engine.LpFloatAllowance
import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.ResumableMinimize
import com.eignex.klause.backtrack.StepEvent
import com.eignex.klause.backtrack.selector.IndomainMax
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.CertifiedLpBound
import com.eignex.klause.lp.engine.ComponentLpSolverCapability
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.ExactLpWitness
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpCertificationObserver
import com.eignex.klause.lp.engine.LpCertificationPolicy
import com.eignex.klause.lp.engine.LpEngineFactory
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpNeighborhood
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpSolver
import com.eignex.klause.lp.engine.PersistentLpSolver
import com.eignex.klause.lp.engine.ProductionLpCertificationPolicy
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.TableauCutSolver
import com.eignex.klause.meta.alns.Alns
import com.eignex.klause.meta.alns.DestroyOperator
import com.eignex.klause.meta.alns.FreedVars
import com.eignex.klause.meta.alns.RepairOperator
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.ClauseExchange
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private enum class LifecycleKind { GENERAL, COMPONENT, TABLEAU, PERSISTENT }

private class LifecycleRecord(val kind: LifecycleKind, val index: Int) {
    var closes = 0
    var usesAfterClose = 0
    var operations = 0
    var solves = 0
    var factorizations = 0
    var warmAttempts = 0
    var closed = false

    fun use() {
        if (closed) usesAfterClose++
        operations++
    }

    fun close(delegate: () -> Unit, fail: Boolean = false) {
        closes++
        if (closed) return
        closed = true
        delegate()
        if (fail) error("close failure $index")
    }

    fun <T> solve(solver: LpSolver, action: () -> T): T {
        use()
        val result = action()
        solves++
        factorizations += solver.lastRefactorizations
        warmAttempts += solver.lastMetrics.warmAttempts
        return result
    }
}

private class LifecycleFactory : LpEngineFactory {
    val records = ArrayList<LifecycleRecord>()
    var failPersistentAcquisitionAt: Int? = null
    var failCloseAt: Int? = null
    var failPersistentClose = false
    var gatedSolveDeclines = false
    private var persistentAttempts = 0

    val persistent: List<LifecycleRecord> get() = records.filter { it.kind == LifecycleKind.PERSISTENT }

    override fun newGeneralSolver(model: LpModel, cancellation: Cancellation, pricing: LpPricingOptions): LpSolver {
        val delegate = ProductionLpEngineFactory.newGeneralSolver(model, cancellation, pricing)
        val record = record(LifecycleKind.GENERAL)
        return object : LpSolver by delegate {
            override fun solve(warm: Basis?): FloatLpResult? = record.solve(delegate) { delegate.solve(warm) }

            override fun solvePrimal(warm: Basis?): FloatLpResult? = record.solve(delegate) {
                delegate.solvePrimal(warm)
            }

            override fun close() = record.close(delegate::close, fails(record))
        }
    }

    override fun newComponentSolver(
        model: LpModel,
        parts: List<LpNeighborhood>,
        solvers: List<LpSolver>,
        isolated: IntArray,
    ): ComponentLpSolverCapability {
        val delegate = ProductionLpEngineFactory.newComponentSolver(model, parts, solvers, isolated)
        val record = record(LifecycleKind.COMPONENT)
        return object : ComponentLpSolverCapability, LpSolver by delegate {
            override fun exactBound(
                observer: LpCertificationObserver?,
                policy: LpCertificationPolicy,
            ): CertifiedLpBound? = delegate.exactBound(observer, policy)

            override fun exactWitness(
                observer: LpCertificationObserver?,
                policy: LpCertificationPolicy,
                cancellation: Cancellation,
            ): ExactLpWitness? = delegate.exactWitness(observer, policy, cancellation)

            override fun close() = record.close(delegate::close, fails(record))
        }
    }

    override fun newTableauSolver(
        model: LpModel,
        cancellation: Cancellation,
        iterationLimit: Int,
        workLimit: Long,
        trackDegeneracy: Boolean,
        pricing: LpPricingOptions,
    ): TableauCutSolver {
        val delegate = ProductionLpEngineFactory.newTableauSolver(
            model,
            cancellation,
            iterationLimit,
            workLimit,
            trackDegeneracy,
            pricing,
        )
        val record = record(LifecycleKind.TABLEAU)
        return object : TableauCutSolver, LpSolver by delegate {
            override fun solve(warm: Basis?): FloatLpResult? = record.solve(delegate) { delegate.solve(warm) }

            override fun solvePrimal(warm: Basis?): FloatLpResult? = record.solve(delegate) {
                delegate.solvePrimal(warm)
            }

            override fun gomoryCuts(maxCuts: Int): List<Cut> = delegate.gomoryCuts(maxCuts)

            override fun mirCuts(maxCuts: Int): List<Cut> = delegate.mirCuts(maxCuts)

            override fun close() = record.close(delegate::close, fails(record))
        }
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
        persistentAttempts++
        if (persistentAttempts == failPersistentAcquisitionAt) error("persistent acquisition failure")
        val delegate = ProductionLpEngineFactory.newPersistentSolver(
            model,
            cancellation,
            refactorUpdateLimit,
            iterationLimit,
            workLimit,
            trackDegeneracy,
            pricing,
        )
        val record = record(LifecycleKind.PERSISTENT)
        return object : PersistentLpSolver by delegate {
            override fun prepareLogicals(token: Cancellation): Basis? {
                record.use()
                return try {
                    delegate.prepareLogicals(token)
                } finally {
                    record.factorizations += delegate.lastRefactorizations
                }
            }

            override fun adopt(state: LpExactState, token: Cancellation): Boolean {
                record.use()
                return delegate.adopt(state, token)
            }

            override fun solve(warm: Basis?): FloatLpResult? = record.solve(delegate) { delegate.solve(warm) }

            override fun rebind(next: LpModel, token: Cancellation): Boolean {
                record.use()
                return delegate.rebind(next, token)
            }

            override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? =
                record.solve(delegate) { delegate.resolveBounds(allowance) }

            override fun resolveGated(enforced: BooleanArray): FloatLpResult? = record.solve(delegate) {
                if (gatedSolveDeclines) null else delegate.resolveGated(enforced)
            }

            override fun close() = record.close(delegate::close, fails(record))
        }
    }

    fun assertAllClosed() {
        assertTrue(records.isNotEmpty())
        assertTrue(records.all { it.closed }, records.toString())
        assertTrue(records.all { it.closes == 1 }, records.toString())
        assertTrue(records.all { it.usesAfterClose == 0 }, records.toString())
    }

    private fun record(kind: LifecycleKind): LifecycleRecord = LifecycleRecord(kind, records.size).also(records::add)

    private fun fails(record: LifecycleRecord): Boolean =
        record.index == failCloseAt || (failPersistentClose && record.kind == LifecycleKind.PERSISTENT)
}

class LpLifecycleTest {
    @Test
    fun `solve closes persistent solvers before returning`() {
        val factory = LifecycleFactory()
        val solver = BacktrackSolver(enumerationProblem().bake(), context(factory))

        solver.solve(params())

        factory.assertAllClosed()
    }

    @Test
    fun `bounded enumeration closes persistent solvers before returning`() {
        val factory = LifecycleFactory()
        val solver = BacktrackSolver(enumerationProblem().bake(), context(factory))

        assertEquals(2, solver.enumerate(params()).take(2).count())

        assertEquals(2, factory.persistent.size)
        factory.assertAllClosed()
    }

    @Test
    fun `continued improvements retain one owner and add no extra solve`() {
        val retainedFactory = LifecycleFactory()
        val retainedSolver = BacktrackSolver(continuedImprovementProblem().bake(), context(retainedFactory))
        val objective = LinearObjective(intCoefficients = longArrayOf(1L, 0L, 0L))
        val params = improvementParams()
        val retained = ResumableMinimize(retainedSolver, objective, params, pausable = false)
        val retainedObjectives = ArrayList<Double?>()
        while (true) {
            when (val event = retained.runUntilEvent()) {
                is StepEvent.Incumbent -> retainedObjectives.add(event.result.objectiveValue)

                is StepEvent.Terminal -> {
                    retainedObjectives.add(event.result.objectiveValue)
                    break
                }

                StepEvent.Paused -> error("non-pausable search paused")
            }
        }

        val releasedFactory = LifecycleFactory()
        val releasedSolver = BacktrackSolver(continuedImprovementProblem().bake(), context(releasedFactory))
        val releasedObjectives = releasedSolver.improvements(objective, params).map { it.objectiveValue }.toList()

        assertEquals(retainedObjectives, releasedObjectives)
        assertTrue(releasedObjectives.size > 2, releasedObjectives.toString())
        assertEquals(1, retainedFactory.persistent.size)
        assertTrue(releasedFactory.persistent.size > retainedFactory.persistent.size)
        val retainedSolves = retainedFactory.persistent.sumOf { it.solves }
        val releasedSolves = releasedFactory.persistent.sumOf { it.solves }
        val retainedFactorizations = retainedFactory.persistent.sumOf { it.factorizations }
        val releasedFactorizations = releasedFactory.persistent.sumOf { it.factorizations }
        assertEquals(0, releasedSolves - retainedSolves)
        val reacquisitions = releasedFactory.persistent.size - retainedFactory.persistent.size
        assertTrue(releasedFactorizations - retainedFactorizations in 0..reacquisitions)
        assertTrue(releasedFactory.persistent.sumOf { it.warmAttempts } > 0)
        retainedFactory.assertAllClosed()
        releasedFactory.assertAllClosed()
    }

    @Test
    fun `LP refuted UNSAT terminal closes its persistent solver`() {
        val factory = LifecycleFactory()
        val solver = BacktrackSolver(lpInfeasibleProblem().bake(), context(factory))

        assertIs<SolveResult.Unsat>(solver.solve(params()))

        factory.assertAllClosed()
    }

    @Test
    fun `first improvement closes persistent solvers`() {
        val factory = LifecycleFactory()
        val solver = BacktrackSolver(wideProblem().bake(), context(factory))

        assertIs<MinimizeResult.WithSample>(
            solver.improvements(objective(), params()).take(1).single(),
        )

        factory.assertAllClosed()
    }

    @Test
    fun `optimal terminal closes persistent solvers`() {
        val factory = LifecycleFactory()
        val solver = BacktrackSolver(wideProblem().bake(), context(factory))

        assertIs<MinimizeResult.Optimal>(solver.minimize(objective(), params()))

        factory.assertAllClosed()
    }

    @Test
    fun `paused resumable search retains factors until explicit close`() {
        val factory = LifecycleFactory()
        val search = BacktrackSolver(wideProblem().bake(), context(factory)).resumable(
            objective(),
            params().copy(lpConfig = null),
        )

        assertNull(search.runSlice(Cancellation.Never, Long.MAX_VALUE, sliceNodes = 1L) {})
        val beforeResume = factory.persistent.single()
        assertFalse(beforeResume.closed)
        val operations = beforeResume.operations

        assertNull(search.runSlice(Cancellation.Never, Long.MAX_VALUE, sliceNodes = 1L) {})

        assertEquals(1, factory.persistent.size)
        assertTrue(beforeResume.operations > operations)
        assertFalse(beforeResume.closed)
        search.close()
        search.close()
        factory.assertAllClosed()
    }

    @Test
    fun `repair reuses factors across terminals and closes its owner once`() {
        val factory = LifecycleFactory()
        val solver = BacktrackSolver(wideProblem().bake(), context(factory))
        val repair = solver.openRepair(objective(), params())

        repair.repair(Assumptions.None, decisionBudget = 2L, cutoff = Double.POSITIVE_INFINITY)
        val kept = factory.persistent.single()
        val operations = kept.operations
        val factorizations = kept.factorizations
        assertFalse(kept.closed)

        repair.repair(Assumptions.None, decisionBudget = 2L, cutoff = Double.POSITIVE_INFINITY)

        assertEquals(1, factory.persistent.size)
        assertTrue(kept.operations > operations)
        assertEquals(factorizations, kept.factorizations)
        assertTrue(kept.warmAttempts > 0)
        assertFalse(kept.closed)
        repair.close()
        repair.close()
        factory.assertAllClosed()
    }

    @Test
    fun `repair rebind failure closes retained factors and preserves the primary failure`() {
        var starts = 0
        val exchange = object : ClauseExchange {
            override fun onRestart(session: PropagationSession) = Unit

            override fun onSearchStart(session: PropagationSession) {
                if (++starts == 3) error("rebind failure")
            }
        }
        val factory = LifecycleFactory().also { it.failPersistentClose = true }
        val solver = BacktrackSolver(wideProblem().bake(), context(factory))
        val repair = solver.openRepair(objective(), params().copy(clauseExchange = exchange))
        repair.repair(Assumptions.None, decisionBudget = 2L, cutoff = Double.POSITIVE_INFINITY)

        val failure = assertFailsWith<IllegalStateException> {
            repair.repair(Assumptions.None, decisionBudget = 2L, cutoff = Double.POSITIVE_INFINITY)
        }

        assertEquals("rebind failure", failure.message)
        assertTrue(failure.suppressed.any { it.message?.startsWith("close failure") == true })
        factory.assertAllClosed()
    }

    @Test
    fun `ALNS owner failure closes retained repair factors and preserves the primary failure`() {
        val factory = LifecycleFactory()
        val problem = wideProblem().bake()
        val objective = objective()
        val repair = RepairOperator { context ->
            context.repairSearch?.repair(context.pinAssumptions, 2L, context.bestObjective)
            factory.failPersistentClose = true
            error("owner failure")
        }
        val alns = Alns(
            inner = LocalSearchSolver(problem),
            destroyOperators = listOf(
                DestroyOperator { _, _, _, _, _ ->
                    FreedVars(IntArray(0), intArrayOf(0, 1, 2))
                },
            ),
            repairOperators = listOf(repair),
            minDestroyFraction = 0.5,
            maxDestroyFraction = 0.5,
            maxIterations = 1,
            flipsPerIteration = 1L,
            backtrack = BacktrackSolver(problem, context(factory)),
            backtrackParams = params(),
        )
        val initial = Sample(BooleanArray(0), LongArray(6) { 2L })

        val failure = assertFailsWith<IllegalStateException> {
            alns.minimize(
                objective,
                LocalSearchParams(maxFlips = 1L, randomSeed = 0L, initialAssignment = initial),
            )
        }

        assertEquals("owner failure", failure.message)
        assertTrue(failure.suppressed.any { it.message?.startsWith("close failure") == true })
        factory.assertAllClosed()
    }

    @Test
    fun `callback failure closes the resumable owner and preserves the primary failure`() {
        val factory = LifecycleFactory().also { it.failPersistentClose = true }
        val search = BacktrackSolver(wideProblem().bake(), context(factory)).resumable(
            objective(),
            params().copy(lpConfig = null),
        )

        val failure = assertFailsWith<IllegalStateException> {
            search.runSlice(Cancellation.Never, 60_000) { error("callback failure") }
        }

        assertEquals("callback failure", failure.message)
        assertTrue(failure.suppressed.any { it.message?.startsWith("close failure") == true })
        factory.assertAllClosed()
    }

    @Test
    fun `replacement closes the displaced solver`() {
        val factory = LifecycleFactory()
        val engine = reifiedEngine(factory)
        engine.pruneNode(PropagationSession(reifiedProblem()), Double.POSITIVE_INFINITY, -1, true)
        val pinned = PropagationSession(reifiedProblem()).also { it.implyBool(0, true) }

        engine.pruneNode(pinned, Double.POSITIVE_INFINITY, -1, true)

        assertTrue(factory.persistent.first().closed)
        assertFalse(factory.persistent.last().closed)
        engine.close()
        factory.assertAllClosed()
    }

    @Test
    fun `failed source epoch acquisition releases the displaced solver`() {
        val factory = LifecycleFactory().also { it.failPersistentAcquisitionAt = 2 }
        val engine = reifiedEngine(factory)
        engine.pruneNode(PropagationSession(reifiedProblem()), Double.POSITIVE_INFINITY, -1, true)
        val pinned = PropagationSession(reifiedProblem()).also { it.implyBool(0, true) }

        assertFailsWith<IllegalStateException> {
            engine.pruneNode(pinned, Double.POSITIVE_INFINITY, -1, true)
        }

        assertTrue(factory.persistent.single().closed)
        engine.close()
        factory.assertAllClosed()
    }

    @Test
    fun `close failure does not prevent releasing the other owned solver`() {
        val factory = LifecycleFactory().also {
            it.failCloseAt = 0
            it.gatedSolveDeclines = true
        }
        val problem = gatedProblem()
        val engine = LpEngine(
            problem,
            LinearObjective(intCoefficients = LongArray(0)),
            LpParams(lpPlan = LpPlan(bounding = true, realResidual = true)),
            SolveStatsSink("lifecycle"),
            context(factory),
        )
        val model = LpBuilder().apply { addVar(0L, 1L) }.build(Sense.MINIMIZE)
        engine.nodeSimplex = factory.newPersistentSolver(
            model,
            Cancellation.Never,
            50,
            0,
            0L,
            false,
            LpPricingOptions(),
        )
        engine.gatedResidual(PropagationSession(problem))

        assertFailsWith<IllegalStateException> { engine.close() }

        assertEquals(2, factory.persistent.size)
        assertTrue(factory.persistent.all { it.closed })
        assertTrue(factory.persistent.all { it.closes == 1 })
    }

    private fun context(factory: LifecycleFactory) = LpSolveContext(factory, ProductionLpCertificationPolicy)

    private fun params() = BacktrackParams(
        randomSeed = 0L,
        lpConfig = LpConfig(),
        lpPlan = LpPlan(bounding = true),
    )

    private fun objective() = LinearObjective(intCoefficients = LongArray(6) { (it % 4 + 1).toLong() })

    private fun wideProblem(): Problem {
        val n = 6
        val vars = IntArray(n) { it }
        return Problem(
            0,
            n,
            Array(n) { IntDomain(0, 3) },
            arrayOf<Factor>(
                Linear(LongArray(n) { 1L }, vars, LinearOp.GE, 11L),
                Linear(LongArray(n) { if (it % 2 == 0) 2L else 1L }, vars, LinearOp.LE, 19L),
                Linear(LongArray(n) { if (it % 3 == 0) 3L else 1L }, vars, LinearOp.LE, 20L),
            ),
        )
    }

    private fun enumerationProblem(): Problem = Problem(
        0,
        3,
        Array(3) { IntDomain(0, 1) },
        arrayOf<Factor>(Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.GE, 1)),
    )

    private fun continuedImprovementProblem(): Problem = Problem(
        0,
        3,
        Array(3) { IntDomain(0, 5) },
        arrayOf<Factor>(Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.GE, 5)),
    )

    private fun improvementParams() = params().copy(
        variableSelector = InputOrder,
        valueSelector = IndomainMax,
        objectiveGuidedValues = false,
        lpConfig = null,
        lpPlan = LpPlan(bounding = true, branching = false),
    )

    private fun lpInfeasibleProblem(): Problem = Problem(
        0,
        3,
        Array(3) { IntDomain(0, 1) },
        arrayOf<Factor>(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 1),
        ),
    )

    private fun reifiedProblem(): Problem = Problem(
        1,
        1,
        arrayOf(IntDomain(0, 2)),
        arrayOf<Factor>(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.GE, 1)),
    )

    private fun reifiedEngine(factory: LifecycleFactory): LpEngine {
        val problem = reifiedProblem()
        return LpEngine(
            problem,
            LinearObjective(intCoefficients = longArrayOf(1L)),
            LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink("lifecycle"),
            context(factory),
        )
    }

    private fun gatedProblem(): Problem = Problem(
        1,
        0,
        emptyArray(),
        arrayOf<Factor>(
            com.eignex.klause.factor.arithmetic.ReifiedRealLinear(
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
