package com.eignex.klause.lp.engine

import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.simplex.basis.BasisOperationWork
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.magnitudeBitLength

// Engine-local: consumers adapt it into their own statistics rather than making the kernel depend on solver results.
internal enum class LpCertifier { INTEGER, SAFE_OBJECTIVE, EXACT_BASIS, EXACT_FARKAS, EXACT_POINT, RATIONAL }

// Each call site states where its work is accounted, so no certifier's work is left out of a ledger summing both.
internal sealed interface LpCertifierCost {
    // Charged through [LpCertificationObserver.observeBasisVerification] or
    // [LpCertificationObserver.observeContinuation].
    data object Reported : LpCertifierCost

    data class Metered(val work: Long, val allocation: Long = 0L) : LpCertifierCost
}

// Each pass visits every structural entry, column and row once. A pass with Long-wide products costs one unit per
// entry and a wider one the square of its word length, so a wide model or operand is not undercharged.
internal class LpScanCount(private val model: LpModel) {
    private var units = 0L

    fun scan(operandBits: Int = Long.SIZE_BITS) {
        val words = (maxOf(model.coefficientBits, Long.SIZE_BITS) + operandBits - 1L) / Long.SIZE_BITS
        val square = words * words
        units = if (units > Long.MAX_VALUE - square) Long.MAX_VALUE else units + square
    }

    fun cost(): LpCertifierCost.Metered {
        val entries = (model.doubleView?.colPtr ?: model.csc.colPtr)[model.n].toLong() + model.numVars + model.m
        return LpCertifierCost.Metered(
            if (units > 0 && entries > Long.MAX_VALUE / units) Long.MAX_VALUE else entries * units,
        )
    }
}

internal fun exactBits(values: Iterable<BigFraction>): Int =
    values.fold(1) { widest, value -> maxOf(widest, value.num.magnitudeBitLength(), value.den.magnitudeBitLength()) }

internal enum class LpRefactorReason {
    INITIAL,
    WARM_START,
    SINGULAR_RECOVERY,
    UPDATE_LIMIT,
    BACKEND_REQUESTED,
    RECONCILE_RECOVERY,
    NUMERICAL_RECOVERY,
    PRIMAL,
}

// Candidates still require independent exact certification.
internal enum class LpFloatTermination {
    OPTIMAL_CANDIDATE,
    INFEASIBLE_CANDIDATE,
    UNBOUNDED_CANDIDATE,
    WORK,
    PIVOTS,
    CANCELLED,
    NUMERICAL,
    OTHER,
}

internal interface LpCertificationObserver {
    fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost)
    fun observeExactInput(accepted: Boolean)
    fun observeSolve(metrics: LpSolveMetrics, component: Boolean)
    fun observeSolveProgress(metrics: LpSolveMetrics, component: Boolean, finished: Boolean) {
        observeSolve(metrics, component)
    }
    fun observeBasisVerification(metrics: ExactBasisMetrics) {}
    fun observeContinuation(metrics: com.eignex.klause.simplex.exact.ExactContinuationMetrics) {}
    fun observePhase(metrics: LpPhaseMetrics) {}
    fun observeContinuationRejection(key: String, count: Long) {}
}

// Crosses the engine boundary without model-facing result types; component engines add it even when a later block
// fails.
internal data class LpSolveMetrics(
    val pivots: Int = 0,
    val workOps: Long = 0L,
    val warmAttempts: Int = 0,
    val warmHits: Int = 0,
    val singularRefactorizations: Int = 0,
    val smallPivotBails: Int = 0,
    val initialRefactorizations: Int = 0,
    val warmStartRefactorizations: Int = 0,
    val singularRecoveryRefactorizations: Int = 0,
    val updateLimitRefactorizations: Int = 0,
    val backendRequestedRefactorizations: Int = 0,
    val reconcileRecoveryRefactorizations: Int = 0,
    val numericalRecoveryRefactorizations: Int = 0,
    val primalRefactorizations: Int = 0,
    val objectiveWarmAttempts: Int = 0,
    val objectiveWarmHits: Int = 0,
    val objectiveWarmRepairs: Int = 0,
    // Scaling-view work the solve reported as preparation; it is outside [workOps] and the solve's work limit.
    val preparationOps: Long = 0L,
    val sourceResidualCalls: Long = 0L,
    val sourceResidualNanos: Long = 0L,
    val sourceResidualScaledCalls: Long = 0L,
    val componentSolves: Int = 0,
    val componentReuses: Int = 0,
    val basisBuildWork: Long = 0L,
    val basisBuildDeclines: Long = 0L,
    val basisBuildUnmeasured: Long = 0L,
    val factorEntriesBuilt: Long = 0L,
) {
    operator fun minus(previous: LpSolveMetrics) = LpSolveMetrics(
        pivots = pivots - previous.pivots,
        workOps = workOps - previous.workOps,
        warmAttempts = warmAttempts - previous.warmAttempts,
        warmHits = warmHits - previous.warmHits,
        singularRefactorizations = singularRefactorizations - previous.singularRefactorizations,
        smallPivotBails = smallPivotBails - previous.smallPivotBails,
        initialRefactorizations = initialRefactorizations - previous.initialRefactorizations,
        warmStartRefactorizations = warmStartRefactorizations - previous.warmStartRefactorizations,
        singularRecoveryRefactorizations = singularRecoveryRefactorizations - previous.singularRecoveryRefactorizations,
        updateLimitRefactorizations = updateLimitRefactorizations - previous.updateLimitRefactorizations,
        backendRequestedRefactorizations = backendRequestedRefactorizations - previous.backendRequestedRefactorizations,
        reconcileRecoveryRefactorizations =
        reconcileRecoveryRefactorizations - previous.reconcileRecoveryRefactorizations,
        numericalRecoveryRefactorizations =
        numericalRecoveryRefactorizations - previous.numericalRecoveryRefactorizations,
        primalRefactorizations = primalRefactorizations - previous.primalRefactorizations,
        objectiveWarmAttempts = objectiveWarmAttempts - previous.objectiveWarmAttempts,
        objectiveWarmHits = objectiveWarmHits - previous.objectiveWarmHits,
        objectiveWarmRepairs = objectiveWarmRepairs - previous.objectiveWarmRepairs,
        preparationOps = preparationOps - previous.preparationOps,
        sourceResidualCalls = sourceResidualCalls - previous.sourceResidualCalls,
        sourceResidualNanos = sourceResidualNanos - previous.sourceResidualNanos,
        sourceResidualScaledCalls = sourceResidualScaledCalls - previous.sourceResidualScaledCalls,
        componentSolves = componentSolves - previous.componentSolves,
        componentReuses = componentReuses - previous.componentReuses,
        basisBuildWork = basisBuildWork - previous.basisBuildWork,
        basisBuildDeclines = basisBuildDeclines - previous.basisBuildDeclines,
        basisBuildUnmeasured = basisBuildUnmeasured - previous.basisBuildUnmeasured,
        factorEntriesBuilt = factorEntriesBuilt - previous.factorEntriesBuilt,
    )

    operator fun plus(other: LpSolveMetrics) = LpSolveMetrics(
        pivots + other.pivots, workOps + other.workOps, warmAttempts + other.warmAttempts,
        warmHits + other.warmHits, singularRefactorizations + other.singularRefactorizations,
        smallPivotBails + other.smallPivotBails, initialRefactorizations + other.initialRefactorizations,
        warmStartRefactorizations + other.warmStartRefactorizations,
        singularRecoveryRefactorizations + other.singularRecoveryRefactorizations,
        updateLimitRefactorizations + other.updateLimitRefactorizations,
        backendRequestedRefactorizations + other.backendRequestedRefactorizations,
        reconcileRecoveryRefactorizations + other.reconcileRecoveryRefactorizations,
        numericalRecoveryRefactorizations + other.numericalRecoveryRefactorizations,
        primalRefactorizations + other.primalRefactorizations,
        objectiveWarmAttempts + other.objectiveWarmAttempts,
        objectiveWarmHits + other.objectiveWarmHits,
        objectiveWarmRepairs + other.objectiveWarmRepairs,
        preparationOps + minOf(other.preparationOps, Long.MAX_VALUE - preparationOps),
        sourceResidualCalls + other.sourceResidualCalls,
        sourceResidualNanos + other.sourceResidualNanos,
        sourceResidualScaledCalls + other.sourceResidualScaledCalls,
        componentSolves + other.componentSolves,
        componentReuses + other.componentReuses,
        basisBuildWork + other.basisBuildWork,
        basisBuildDeclines + other.basisBuildDeclines,
        basisBuildUnmeasured + other.basisBuildUnmeasured,
        factorEntriesBuilt + other.factorEntriesBuilt,
    )
}

// Engine-agnostic: any LP method can produce the primal and duals for the safe bound of Neumaier and Shcherbina
// ("Safe bounds in linear and mixed-integer linear programming", Math. Program. 99, 2004,
// https://doi.org/10.1007/s10107-003-0433-3) and a candidate Farkas ray. Cut generation is simplex-specific and lives
// on [TableauCutSolver]. Returned values are double-precision guides; authoritative bounds come only from exact
// certification downstream.
internal interface LpSolver : AutoCloseable {
    val trialProgress: Map<String, Long> get() = emptyMap()

    fun pausedDualProposal(cancellation: ExecutionControl = ExecutionControl.Never): DoubleArray? = null

    // Certified callers validate source feasibility independently. Scaled arithmetic guards remain required.
    fun deferUnscaledSourceDiagnostics() = Unit

    // Null when the engine does not report a reason or has not solved.
    val lastTermination: LpFloatTermination? get() = null

    // Null on non-convergence, dual unboundedness or a singular basis. [warm] changes only the pivot path, never the
    // result, and engines may decline it.
    fun solve(warm: Basis? = null): FloatLpResult?

    // For a feasible point even when the dual pass would not converge.
    fun solvePrimal(warm: Basis? = null): FloatLpResult?

    // Null unless the last [solve] returned null on infeasibility.
    val infeasibleRay: DoubleArray?

    // In original coordinates. Unboundedness also requires an independently checked feasible point.
    val recessionDirection: DoubleArray? get() = null

    val scalingMetrics: LpScalingMetrics get() = LpScalingMetrics(decline = LpScalingDecline.DISABLED)

    // For the exact ray solve a model with an unbounded column needs. Null on an engine that keeps no basis;
    // certification then falls back to rounding [infeasibleRay].
    val infeasibleBasis: Basis? get() = null

    // The leaving row of [infeasibleBasis].
    val infeasibleRow: Int get() = -1

    val solvedExactState: LpExactState? get() = null

    val exactBasisCache: ExactBasisCache? get() = null

    fun rejectSingularBasis(model: LpModel, basis: Basis): Boolean = false

    fun continuationBasis(model: LpModel): Basis? = null

    // Also counts solves that returned no [FloatLpResult]: reading it off the result would drop exactly the solves
    // that prune.
    val lastPivots: Int get() = 0

    val lastRefactorizations: Int get() = 0

    val lastWarmStarted: Boolean get() = false

    // Deterministic cost in [LpWork] operations: pivots alone miss that a dense pivot outweighs many sparse ones.
    val lastWorkOps: Long get() = 0L

    // The engine falls back to the slack cold start on a singular factorization, so this is the only trace that a
    // warm start was thrown away.
    val lastSingularRefactorizations: Int get() = 0

    // Each is a solve lost outright, with no [FloatLpResult] to record it in.
    val lastSmallPivotBails: Int get() = 0

    val lastMetrics: LpSolveMetrics get() = LpSolveMetrics(
        pivots = lastPivots,
        workOps = lastWorkOps,
        singularRefactorizations = lastSingularRefactorizations,
        smallPivotBails = lastSmallPivotBails,
    )

    // Nonbasic columns with zero reduced cost at the last termination.
    val lastDegenerateColumns: Int get() = 0

    val lastColumns: Int get() = 0

    // Closing twice is harmless.
    override fun close() {}
}

internal class LpPrimalGuidance(val coordinates: DoubleArray, val known: BooleanArray)

// Component results certify without one monolithic exact problem. Factories return this capability explicitly so a
// decorator around a component engine cannot hide the certification routes.
internal interface ComponentLpSolverCapability : LpSolver {
    fun partialBoundDuals(cancellation: ExecutionControl = ExecutionControl.Never): DoubleArray? = null

    fun partialPrimalGuidance(cancellation: ExecutionControl = ExecutionControl.Never): LpPrimalGuidance? = null

    fun nodeDeductions(
        duals: DoubleArray,
        observer: LpCertificationObserver? = null,
        policy: LpCertificationPolicy = ProductionLpCertificationPolicy,
        cancellation: ExecutionControl = ExecutionControl.Never,
        withAlternatives: Boolean = false,
        sharedMeter: MixedCertificateMeter? = null,
    ): ComponentLpDeductions? = null

    fun exactBound(
        observer: LpCertificationObserver? = null,
        policy: LpCertificationPolicy = ProductionLpCertificationPolicy,
    ): CertifiedLpBound?

    fun exactWitness(
        observer: LpCertificationObserver? = null,
        policy: LpCertificationPolicy = ProductionLpCertificationPolicy,
        cancellation: ExecutionControl = ExecutionControl.Never,
    ): ExactLpWitness?
}

// Gomory and MIR cuts are read off an optimal simplex basis, so only a basis-carrying engine supplies them.
internal interface TableauCutSolver : LpSolver {
    // Empty if the last solve was not optimal.
    fun gomoryCuts(maxCuts: Int): List<Cut>

    fun mirCuts(maxCuts: Int): List<Cut>
}

internal data class LpFloatAllowance(val work: Long, val iterations: Int) {
    init {
        require(work >= 0L && iterations >= 0)
    }
}

internal interface RetainedLpSolver : LpSolver {
    // A declined adoption requires the caller to discard this owner; a composite may retire partially edited children.
    fun adopt(state: LpExactState, token: ExecutionControl = ExecutionControl.Never): Boolean = false

    fun resolveBounds(allowance: LpFloatAllowance? = null): FloatLpResult?
}

internal interface ResumableLpSolver : RetainedLpSolver {
    val currentWork: Long
    fun openSolve(
        allowance: LpFloatAllowance? = null,
        retainFundingStop: Boolean = false,
    ): LpSolveCursor<FloatLpResult?>
    fun promoteSolve(allowance: LpFloatAllowance): Boolean = false
}

internal interface RetainedComponentLpSolverCapability :
    ComponentLpSolverCapability,
    RetainedLpSolver

// Retains its basis and factors across exact bound and objective adoption, so it must be monolithic.
internal interface PersistentLpSolver : RetainedLpSolver {
    // Builds an all-logical basis without claiming feasibility; null means unsupported or declined.
    fun prepareLogicals(token: ExecutionControl = ExecutionControl.Never): Basis? = null

    fun prepareBasis(basis: Basis, token: ExecutionControl = ExecutionControl.Never): Basis? = prepareLogicals(token)

    fun retainedBasis(): Basis? = null

    val basisLifecycleWork: BasisOperationWork? get() = null

    // Null [allowance] uses construction limits; zero work is unbounded and zero iterations uses the size-derived
    // limit. Neither zero means exhausted.
    override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult?

    // An unenforced row only defines a free slack. Sound only for an all-zero objective, under which every basis is
    // dual-feasible.
    fun resolveGated(enforced: BooleanArray): FloatLpResult?
}

// A separable model first decomposes into its column components ([ComponentLpSolver]) unless [componentSplit] is
// off. [workLimit] bounds each solve in [LpWork] operations, 0 leaving it unbounded; a stopped solve yields at most
// a bound to certify.
internal fun newLpSolver(
    model: LpModel,
    cancellation: ExecutionControl = ExecutionControl.Never,
    componentSplit: Boolean = true,
    factory: LpEngineFactory = ProductionLpEngineFactory,
    pricing: LpPricingOptions = LpPricingOptions(),
    workLimit: Long = 0L,
): LpSolver {
    if (componentSplit) {
        componentLpSolverOrNull(
            model,
            cancellation,
            null,
            { part, token, rowOnlyRows ->
                factory.newGeneralSolver(
                    part, token, workLimit, pricing.componentShare(part.n, model.n, rowOnlyRows),
                )
            },
            factory::newComponentSolver,
        )?.let { return it }
    }
    return factory.newGeneralSolver(model, cancellation, workLimit, pricing)
}

// Never decomposes. [iterationLimit] and [workLimit] bound the dual solve, 0 leaving it to the engine; a truncated
// dual iterate supplies only a candidate to certify.
internal fun newTableauCutSolver(
    model: LpModel,
    cancellation: ExecutionControl = ExecutionControl.Never,
    iterationLimit: Int = 0,
    workLimit: Long = 0L,
    trackDegeneracy: Boolean = false,
    factory: LpEngineFactory = ProductionLpEngineFactory,
    pricing: LpPricingOptions = LpPricingOptions(),
): TableauCutSolver = factory.newTableauSolver(model, cancellation, iterationLimit, workLimit, trackDegeneracy, pricing)

internal fun newComponentTableauCutSolver(
    model: LpModel,
    cancellation: ExecutionControl = ExecutionControl.Never,
    iterationLimit: Int = 0,
    workLimit: Long = 0L,
    trackDegeneracy: Boolean = false,
    factory: LpEngineFactory = ProductionLpEngineFactory,
    pricing: LpPricingOptions = LpPricingOptions(),
): TableauCutSolver = componentLpSolverOrNull(
    model, cancellation, null,
    { part, token, rowOnlyRows ->
        factory.newTableauSolver(
            part, token, iterationLimit, workLimit, trackDegeneracy,
            pricing.componentShare(part.n, model.n, rowOnlyRows),
        )
    },
    { parent, parts, children, isolated ->
        ComponentLpSolver(parent, parts, children, isolated).apply {
            queryAllowance = LpFloatAllowance(workLimit, iterationLimit)
        }
    },
) ?: newTableauCutSolver(model, cancellation, iterationLimit, workLimit, trackDegeneracy, factory, pricing)

internal fun LpPricingOptions.componentShare(columns: Int, totalColumns: Int, rowOnlyRows: Int = 0): LpPricingOptions {
    val limit = basisEntryLimit
    if (limit == Long.MAX_VALUE) return this
    // Row-only children retain identity factors and have no structural entering column that can create fill.
    val logicalShare = minOf(limit, 4L * rowOnlyRows)
    if (columns == 0) return copy(basisEntryLimit = logicalShare)
    val structuralShare = limit - logicalShare
    // Column ownership keeps the allocation stable across component-local row additions.
    val share = structuralShare / totalColumns * columns +
        (structuralShare % totalColumns) * columns / totalColumns
    return copy(basisEntryLimit = share)
}

// Monolithic: a decomposed model has no single basis to carry. A caller whose pivots accumulate across solves
// raises [refactorUpdateLimit], since the default is sized for one solve's chain.
internal fun newPersistentLpSolver(
    model: LpModel,
    cancellation: ExecutionControl = ExecutionControl.Never,
    refactorUpdateLimit: Int = DEFAULT_REFACTOR_UPDATE_LIMIT,
    iterationLimit: Int = 0,
    workLimit: Long = 0L,
    trackDegeneracy: Boolean = false,
    factory: LpEngineFactory = ProductionLpEngineFactory,
    pricing: LpPricingOptions = LpPricingOptions(),
): PersistentLpSolver = factory.newPersistentSolver(
    model,
    cancellation,
    refactorUpdateLimit,
    iterationLimit,
    workLimit,
    trackDegeneracy,
    pricing,
)

internal fun newRetainedLpSolver(
    model: LpModel,
    cancellation: ExecutionControl = ExecutionControl.Never,
    componentSplit: Boolean = true,
    factory: LpEngineFactory = ProductionLpEngineFactory,
    pricing: LpPricingOptions = LpPricingOptions(),
    workLimit: Long = 0L,
): RetainedLpSolver {
    require(model.exactState != null)
    if (componentSplit) {
        componentLpSolverOrNull(
            model,
            cancellation,
            null,
            { part, token, rowOnlyRows ->
                newPersistentLpSolver(
                    part,
                    token,
                    factory = factory,
                    pricing = pricing.componentShare(part.n, model.n, rowOnlyRows),
                    workLimit = workLimit,
                )
            },
            factory::newRetainedComponentSolver,
        )?.let { return it }
    }
    return newPersistentLpSolver(model, cancellation, factory = factory, pricing = pricing, workLimit = workLimit)
}
