package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.RationalBasisLimits
import com.eignex.klause.simplex.exact.BigRationalConflict
import com.eignex.klause.simplex.exact.ContinuationDecline
import com.eignex.klause.simplex.exact.ExactContinuationLimits
import com.eignex.klause.simplex.exact.ExactContinuationMetrics
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.unaryMinus
import kotlin.time.Duration
import kotlin.time.TimeSource

// Bounds and witnesses remain independently available even when neither proves attainment. ATTAINED_OPTIMUM is exact:
// an exact witness meeting an exact bound. TOLERANCE_OPTIMUM is a float optimum the caller accepted under its own
// tolerance semantics, whose primal and objective are not exact.
internal enum class LpVerdict {
    CERTIFIED_BOUND,
    FEASIBLE,
    ATTAINED_OPTIMUM,
    TOLERANCE_OPTIMUM,
    INFEASIBLE,
    UNBOUNDED,
    INDETERMINATE,
}

internal class ExactLpWitness(val primal: List<BigFraction>, val objective: BigFraction)

internal class ExactLpUnboundedness(val witness: ExactLpWitness, val direction: List<BigFraction>)

internal class CertifiedLpBound(
    val value: BigFraction,
    val certificate: IntegerCertificate? = null,
    val support: LpExactSupport? = null,
    dualMultipliers: List<BigFraction>? = null,
) {
    private val capturedDualMultipliers = dualMultipliers?.toList()
    val dualMultipliers: List<BigFraction>? get() = capturedDualMultipliers?.toList()
}

internal data class LpExactCitedSide(val column: Int, val upper: Boolean, val side: ExactLpSide, val witness: Long?)

internal class LpExactSupport(
    val state: LpExactState,
    val rows: List<Pair<Int, ExactLpRow>>,
    val sides: List<LpExactCitedSide>,
)

// Exact evidence uses original coordinates and minimized objective units.
internal class CertifiedLpResult(
    val float: FloatLpResult?,
    val bound: CertifiedLpBound?,
    val witness: ExactLpWitness?,
    val farkasRay: LongArray?,
    val rationalConflict: BigRationalConflict?,
    private val integralObjective: Boolean,
    private val safeBound: () -> Double?,
    val unboundedness: ExactLpUnboundedness? = null,
    val boundConflict: LpBoundConflict? = null,
    val conflictSupport: LpExactSupport? = null,
    val reconstruction: ReconstructionMetrics? = null,
    val basisVerification: ExactBasisMetrics? = null,
    val continuation: ExactContinuationMetrics? = null,
    val refinement: LpRefinementMetrics? = null,
    val exactPoint: ExactPointRecovery? = null,
    // A float optimum accepted under tolerance semantics in place of exact evidence; its primal is not exact.
    val floatOptimum: FloatLpResult? = null,
    val certificationReused: Boolean = false,
    // Numerical refutation only; an empty ray has no combination a learned clause can cite.
    val floatInfeasibility: LongArray? = null,
) {
    val verdict: LpVerdict = when {
        floatOptimum != null -> LpVerdict.TOLERANCE_OPTIMUM
        floatInfeasibility != null || farkasRay != null || rationalConflict != null || boundConflict != null ->
            LpVerdict.INFEASIBLE
        unboundedness != null -> LpVerdict.UNBOUNDED
        witness != null && bound?.value == witness.objective -> LpVerdict.ATTAINED_OPTIMUM
        witness != null -> LpVerdict.FEASIBLE
        bound != null -> LpVerdict.CERTIFIED_BOUND
        else -> LpVerdict.INDETERMINATE
    }
    val infeasibleRows: IntArray? get() = rationalConflict?.rows
    val certificate: IntegerCertificate? get() = bound?.certificate
    val lowerBound: BigFraction? get() = bound?.value
    val exactPrimal: List<BigFraction>? get() = witness?.primal

    // This ceiling concerns the integer source objective, not attainment of the continuous relaxation.
    val integerObjectiveLowerBound: Long? get() = if (integralObjective) lowerBound?.ceilLong() else null
    val safeLowerBound: Double? by lazy(safeBound)
}

internal fun solveAndCertify(
    model: ExactLpModel,
    warm: ExactLpBasis? = null,
    cancellation: ExecutionControl = ExecutionControl.Never,
    context: LpSolveContext = LpSolveContext.Production,
    counterResults: LpCounterResults? = null,
    pricing: LpPricingOptions = LpPricingOptions(),
    observer: LpCertificationObserver? = null,
    refinementLimits: LpRefinementLimits = LpRefinementLimits(),
    claim: LpClaim = LpClaim.ATTAINED_OPTIMUM,
): CertifiedLpResult {
    val state = LpExactState(model)
    certifyBoundConflict(state, cancellation, context.certificationPolicy, observer, claim)?.let { return it }
    val working = state.toWorkingModel()
    if (working == null || (warm != null && !warm.validFor(model))) {
        counterResults?.declineStorage()
        return CertifiedLpResult(null, null, null, null, null, false, { null })
    }
    val basis = warm?.let {
        Basis(
            IntArray(model.m) { row -> it.heading(row) },
            Array(model.numVars) { column -> VarStatus.valueOf(it.status(column).name) },
            captureEligible = false,
        )
    }
    return solveAndCertify(
        working,
        basis,
        cancellation,
        observer = observer,
        context = context,
        counterResults = counterResults,
        pricing = pricing,
        refinementLimits = refinementLimits,
        claim = claim,
    )
}

internal fun certifyBoundConflict(
    state: LpExactState,
    cancellation: ExecutionControl,
    policy: LpCertificationPolicy,
    observer: LpCertificationObserver?,
    claim: LpClaim,
): CertifiedLpResult? {
    val conflict = state.conflict ?: return null
    val observation = observer as? LpClaimObservation ?: observer?.let { LpClaimObservation(claim, it) }
    fun stopped(): Boolean = cancellation().also { if (it) observation?.refuse("CANCELLED") }
    val accepted = if (stopped())
        null else {
        observation?.observe(LpCertifier.EXACT_FARKAS, true, LpCertifierCost.Reported)
        policy.acceptNullable(LpCertifier.EXACT_FARKAS, conflict)?.takeUnless { stopped() }.also {
            if (it == null && !stopped()) observation?.refuse("POLICY_REFUSED")
        }
    }
    val support = accepted?.let {
        val row = it.column - state.model.n
        LpExactSupport(
            state,
            if (row >= 0) listOf(row to state.model.row(row)) else emptyList(),
            listOf(it.lower, it.upper).map { side ->
                LpExactCitedSide(side.column, side.upper, side.side, side.witness)
            },
        )
    }
    return CertifiedLpResult(
        null, null, null, null, null, false, { null },
        boundConflict = accepted,
        conflictSupport = support,
    ).also { if (observer !is LpClaimObservation) observation?.finish(it, false) }
}

// Float termination hints never determine the proof strength. A float solve stopped by [workLimit] (0: unbounded)
// supplies at most a candidate to certify.
internal fun solveAndCertify(
    model: LpModel,
    warm: Basis? = null,
    cancellation: ExecutionControl = ExecutionControl.Never,
    workLimit: Long = 0L,
    componentSplit: Boolean = true,
    observer: LpCertificationObserver? = null,
    context: LpSolveContext = LpSolveContext.Production,
    counterResults: LpCounterResults? = null,
    pricing: LpPricingOptions = LpPricingOptions(),
    refinementLimits: LpRefinementLimits = LpRefinementLimits(),
    floatAccept: ((FloatLpResult) -> Boolean)? = null,
    floatOffset: Double = 0.0,
    claim: LpClaim = LpClaim.ATTAINED_OPTIMUM,
    acceptFloatInfeasibility: Boolean = false,
): CertifiedLpResult {
    if (claim == LpClaim.ATTAINED_OPTIMUM && floatAccept != null && workLimit == 0L &&
        model.exactState == null && model.floatProjectionAuthoritative()
    ) {
        return solveProjectedAndCertify(
            model, warm, cancellation, workLimit, componentSplit, observer, context, counterResults,
            pricing, refinementLimits, floatAccept, floatOffset, claim, acceptFloatInfeasibility,
        )
    }
    val authoritative = if (model.exactState != null) {
        model
    } else {
        val timer = LpPhaseTimer(observer, LpSolvePhase.AUTHORITATIVE_IMPORT)
        val working = model.authoritativeModel()?.let { LpExactState(it).toWorkingModel() }
        timer.finish(if (working != null) "IMPORTED" else "DECLINED")
        working ?: return CertifiedLpResult(null, null, null, null, null, false, { null })
    }
    val counters = if (authoritative === model) {
        counterResults
    } else {
        counterResults?.let { source ->
            LpCounterResults().also { imported ->
                source.read(model, context.certificationPolicy)?.let {
                    imported.remember(authoritative, it, context.certificationPolicy)
                }
            }
        }
    }
    val result = certifyAuthoritativeSolve(
        authoritative, warm, cancellation, workLimit, componentSplit, observer, context, counters, pricing,
        refinementLimits, floatAccept,
        floatOffset, claim, acceptFloatInfeasibility,
    )
    return sourceCertifiedResult(
        model,
        authoritative,
        result,
        cancellation,
        counterResults,
        context.certificationPolicy,
    )
}

private fun sourceCertifiedResult(
    model: LpModel,
    authoritative: LpModel,
    result: CertifiedLpResult,
    cancellation: ExecutionControl,
    counterResults: LpCounterResults?,
    policy: LpCertificationPolicy,
): CertifiedLpResult {
    if (result.floatOptimum != null || result.floatInfeasibility != null) return result
    if (authoritative !== model) {
        if ((result.witness?.let { checkedLpWitness(model, it.primal) } == null && result.witness != null) ||
            result.rationalConflict?.let { !checkedLpConflict(model, it) } == true || cancellation()
        ) {
            return CertifiedLpResult(null, null, null, null, null, false, { null })
        }
        counterResults?.remember(model, result, policy)
    }
    return result
}

// A faithful source projection supplies the same float candidate without allocating a trail or rational matrix.
// A refusal imports authority for a warm solve and the proof ladder. A work-capped source keeps its single-pass
// route: even a second warm factorization would consume part of that invocation's float allowance.
private fun solveProjectedAndCertify(
    model: LpModel,
    warm: Basis?,
    cancellation: ExecutionControl,
    workLimit: Long,
    componentSplit: Boolean,
    observer: LpCertificationObserver?,
    context: LpSolveContext,
    counterResults: LpCounterResults?,
    pricing: LpPricingOptions,
    refinementLimits: LpRefinementLimits,
    floatAccept: (FloatLpResult) -> Boolean,
    floatOffset: Double,
    claim: LpClaim,
    acceptFloatInfeasibility: Boolean,
): CertifiedLpResult = newLpSolver(
    model,
    cancellation,
    componentSplit,
    context.engineFactory,
    pricing,
    workLimit,
).use { solver ->
    solver.deferUnscaledSourceDiagnostics()
    val result = try {
        solver.solve(warm)
    } finally {
        observer?.observeSolve(solver.lastMetrics, solver is ComponentLpSolverCapability)
    }
    if (cancellation()) return@use CertifiedLpResult(null, null, null, null, null, false, { null })
    if (context.numericalDeductions && acceptFloatInfeasibility) {
        numericalLpRefutation(model, solver, result, cancellation, observer)?.let { return@use it }
    }
    var sourceRefused = false
    if (result != null && result.optimal) {
        val timer = LpPhaseTimer(observer, LpSolvePhase.SOURCE_FLOAT_ACCEPTANCE)
        val dualAccepted = floatDualFeasible(model, result, floatOffset)
        val accepted = dualAccepted && floatAccept(result)
        sourceRefused = dualAccepted && !accepted
        timer.finish(if (accepted) "ACCEPTED" else "REFUSED")
        if (accepted && !cancellation()) {
            return@use CertifiedLpResult(result, null, null, null, null, false, { null }, floatOptimum = result)
        }
    }
    if (cancellation()) return@use CertifiedLpResult(null, null, null, null, null, false, { null })
    val timer = LpPhaseTimer(observer, LpSolvePhase.AUTHORITATIVE_IMPORT)
    val authoritative = model.authoritativeModel()?.let { LpExactState(it).toWorkingModel() }
    timer.finish(if (authoritative != null) "IMPORTED" else "DECLINED")
    if (authoritative == null) return@use CertifiedLpResult(null, null, null, null, null, false, { null })
    val counters = counterResults?.let { source ->
        LpCounterResults().also { imported ->
            source.read(model, context.certificationPolicy)?.let {
                imported.remember(authoritative, it, context.certificationPolicy)
            }
        }
    }
    val certified = certifyAuthoritativeSolve(
        authoritative, result?.basis ?: warm, cancellation, workLimit, componentSplit, observer, context, counters,
        pricing, refinementLimits, floatAccept.takeUnless { sourceRefused }, floatOffset, claim,
        acceptFloatInfeasibility,
    )
    sourceCertifiedResult(model, authoritative, certified, cancellation, counterResults, context.certificationPolicy)
}

private fun certifyAuthoritativeSolve(
    model: LpModel,
    warm: Basis?,
    cancellation: ExecutionControl,
    workLimit: Long,
    componentSplit: Boolean,
    observer: LpCertificationObserver?,
    context: LpSolveContext,
    counterResults: LpCounterResults?,
    pricing: LpPricingOptions,
    refinementLimits: LpRefinementLimits,
    floatAccept: ((FloatLpResult) -> Boolean)?,
    floatOffset: Double,
    claim: LpClaim,
    acceptFloatInfeasibility: Boolean,
): CertifiedLpResult = newLpSolver(
    model,
    cancellation,
    componentSplit,
    context.engineFactory,
    pricing,
    workLimit,
).use { solver ->
    solver.deferUnscaledSourceDiagnostics()
    val result = try {
        solver.solve(warm)
    } finally {
        observer?.observeSolve(solver.lastMetrics, solver is ComponentLpSolverCapability)
    }
    LpCertificationSession(context, pricing).use { certification ->
        certification.certify(
            model, solver, result, cancellation, observer, counterResults,
            refinementLimits, floatAccept, floatOffset, claim, acceptFloatInfeasibility,
        )
    }
}

// Observer failures cannot replace an exception from the numerical operation.
@Suppress("TooGenericExceptionCaught")
// Certifies only the supplied solve and its current basis; source solves import authority before allocation.
internal fun certifyLpResult(
    model: LpModel,
    solver: LpSolver,
    result: FloatLpResult?,
    cancellation: ExecutionControl = ExecutionControl.Never,
    observer: LpCertificationObserver? = null,
    policy: LpCertificationPolicy = ProductionLpCertificationPolicy,
    counterResults: LpCounterResults? = null,
    continuationCache: LpExactContinuationCache = LpExactContinuationCache(),
    continuationLimits: ExactContinuationLimits = ExactContinuationLimits(),
    fullContinuation: Boolean = true,
    refinement: LpRefinementRequest? = null,
    sparsePointRecovery: Boolean = false,
    claim: LpClaim = LpClaim.ATTAINED_OPTIMUM,
): CertifiedLpResult {
    val observation = observer as? LpClaimObservation ?: observer?.let { LpClaimObservation(claim, it) }
    val pairing = observer?.let { LpRejectionPairing() }
    var completed = false
    var failure: Throwable? = null
    try {
        val certified = certifyLpResultWithin(
            model, solver, result, cancellation, observation, policy, counterResults, continuationCache,
            continuationLimits, fullContinuation, refinement, sparsePointRecovery, claim, pairing,
        )
        completed = true
        if (observer !is LpClaimObservation) observation?.finish(certified, certified.certificationReused)
        return certified
    } catch (primary: Throwable) {
        failure = primary
        throw primary
    } finally {
        val primary = failure
        if (primary == null) {
            pairing?.report(requireNotNull(observer), completed)
        } else {
            try {
                pairing?.report(requireNotNull(observer), completed)
            } catch (observation: Throwable) {
                primary.addSuppressed(observation)
            }
        }
    }
}

private fun certifyLpResultWithin(
    model: LpModel,
    solver: LpSolver,
    result: FloatLpResult?,
    cancellation: ExecutionControl = ExecutionControl.Never,
    observer: LpCertificationObserver? = null,
    policy: LpCertificationPolicy = ProductionLpCertificationPolicy,
    counterResults: LpCounterResults? = null,
    continuationCache: LpExactContinuationCache = LpExactContinuationCache(),
    continuationLimits: ExactContinuationLimits = ExactContinuationLimits(),
    fullContinuation: Boolean = true,
    refinement: LpRefinementRequest? = null,
    sparsePointRecovery: Boolean = false,
    claim: LpClaim = LpClaim.ATTAINED_OPTIMUM,
    pairing: LpRejectionPairing? = null,
): CertifiedLpResult {
    fun stopped(): Boolean = cancellation().also {
        if (it) {
            pairing?.stopObserved = true
            (observer as? LpClaimObservation)?.refuse("CANCELLED")
        }
    }
    val certificationStarted = refinement?.let { TimeSource.Monotonic.markNow() }
    if (!model.finiteExactInput()) {
        (observer as? LpClaimObservation)?.refuse("UNSUPPORTED_INPUT")
        return CertifiedLpResult(null, null, null, null, null, false, { null })
    }
    val state = model.exactState
    var capturedTarget: LpContinuationTarget? = null
    var earlyContinuation: LpContinuationVerification? = null
    var earlyWitness: ExactLpWitness? = null
    fun continuationTarget(): LpContinuationTarget = capturedTarget ?: captureContinuationTarget(
        model,
        solver,
        result,
        continuationLimits,
        cancellation,
    ).also { capturedTarget = it }
    if (state != null) {
        if (stopped()) return CertifiedLpResult(null, null, null, null, null, false, { null })
        certifyBoundConflict(state, cancellation, policy, observer, claim)?.let { return it }
        if (result != null && (solver.solvedExactState !== state || result.exactState !== state)) {
            (observer as? LpClaimObservation)?.refuse("UNSUPPORTED_SCOPE")
            return CertifiedLpResult(null, null, null, null, null, false, { null })
        }
        val remembered = counterResults?.read(model, policy)
        if (remembered != null && claim.satisfiedBy(remembered) && !stopped()) {
            return CertifiedLpResult(result, remembered.bound, remembered.witness, null, null,
                model.hasIntegralObjective(), { null }, certificationReused = true)
        }
        if (result != null && claim == LpClaim.BOUND) {
            val direct = certifyLpBound(model, result.duals, observer, policy)
                ?: (solver as? ComponentLpSolverCapability)?.exactBound(observer, policy)
            val checked = if (direct == null && !stopped()) {
                certifyLpBoundOnly(model, result.duals, ReconstructionLimits(), cancellation,
                    observer, policy, result.basis)
            } else
                null
            val certified = CertifiedLpResult(result, direct ?: checked?.bound, null, null, null,
                model.hasIntegralObjective(), { null }, reconstruction = checked?.metrics)
            if (!stopped()) counterResults?.remember(model, certified, policy)
            return if (stopped()) CertifiedLpResult(null, null, null, null, null, false, { null }) else certified
        }
        if (solver.solvedExactState !== state &&
            (
                refinement?.source?.state !== state ||
                    (solver.recessionDirection == null && continuationTarget().basis != null)
                )
        ) {
            val continued = continueExactLp(
                model,
                continuationTarget().basis,
                continuationCache,
                cancellation,
                continuationLimits,
                fullEffort = fullContinuation,
                targetMetrics = continuationTarget().metrics,
            )
            pairing?.observe(continued, LpContinuationSite.EARLY, result != null)
            observer?.observeContinuation(continued.metrics)
            val accepted = policy.acceptNullable(LpCertifier.RATIONAL, continued.takeIf { it.metrics.success })
            val point = accepted?.witness?.let { policy.acceptNullable(LpCertifier.EXACT_BASIS, it) }
            val refutation = accepted?.conflict?.let { policy.acceptNullable(LpCertifier.EXACT_FARKAS, it) }
            val boxedObjective = (0 until state.model.numVars).all { j ->
                val column = state.model.column(j)
                when (state.model.objective.cost(j).value.signum()) {
                    1 -> column.bounds.lower != null
                    -1 -> column.bounds.upper != null
                    else -> true
                }
            }
            if ((continued.metrics.success && (point == null || boxedObjective)) ||
                refinement?.source?.state !== state
            ) {
                val constantBound = if (continued.conflict == null && !stopped() &&
                    (0 until model.numVars).all { model.exactCost(it).isZero }
                ) {
                    certifyLpBound(model, DoubleArray(model.m), observer, policy).takeUnless { stopped() }
                } else {
                    null
                }
                return CertifiedLpResult(
                    null, constantBound, point, null, refutation, model.hasIntegralObjective(), { null },
                    conflictSupport = continued.support.takeIf { refutation != null }, continuation = continued.metrics,
                )
            }
            earlyContinuation = continued
            earlyWitness = point
        }
    }
    val remembered = counterResults?.read(model, policy)
    val component = solver as? ComponentLpSolverCapability
    var bound = result?.takeUnless { claim == LpClaim.FEASIBLE_COMPLETION || claim == LpClaim.CONTRADICTION }
        ?.let { certifyLpBound(model, it.duals, observer, policy) }
        ?: result?.takeUnless { claim == LpClaim.FEASIBLE_COMPLETION || claim == LpClaim.CONTRADICTION }
            ?.let { component?.exactBound(observer, policy) }
        ?: remembered?.bound
    var witness = remembered?.witness ?: earlyWitness
    var ray: LongArray? = null
    var conflict: BigRationalConflict? = null
    var reconstruction: ReconstructedCertificate? = null
    var exactBasis: ExactBasisVerification? = null
    var continued: LpContinuationVerification? = earlyContinuation
    var refined: LpRefinementResult? = null
    // Both refinement calls a node can make, so the reported work is what certification spent.
    var refinementMetrics: LpRefinementMetrics? = null
    var pointRecovery: ExactPointRecovery? = null
    var numericalWitness = earlyContinuation?.witness ?: witness
    var numericalBound = bound
    var numericalConflict: BigRationalConflict? = null
    var withheldWitness = false
    var withheldBound = false
    var withheldConflict = false
    var pointAttempted = false
    // A second, seeded refinement is charged only the direct work done since the first.
    var chargedWork = 0L
    var chargedAllocation = 0L
    var chargedElapsed = Duration.ZERO
    fun directElapsed(): Duration =
        (certificationStarted?.elapsedNow() ?: Duration.ZERO) - (pointRecovery?.elapsed ?: Duration.ZERO)
    fun refine() {
        if (refinement == null || state == null || stopped()) return
        if (result == null) {
            val decline = continuationTarget().metrics.decline
            if (decline != null && decline != ContinuationDecline.NO_BASIS) return
        }
        val directWork = (reconstruction?.metrics?.work ?: 0L) + (exactBasis?.metrics?.work ?: 0L) +
            (capturedTarget?.metrics?.work ?: 0L)
        val directAllocation = (reconstruction?.metrics?.allocation ?: 0L) +
            (exactBasis?.metrics?.allocation ?: 0L) + (capturedTarget?.metrics?.allocation ?: 0L)
        val recovered = refineLp(
            model, refinement, result?.primal, result?.duals,
            result?.basis ?: continuationTarget().basis, numericalWitness, cancellation,
            directWork = (directWork - chargedWork).coerceAtLeast(0L),
            directAllocation = (directAllocation - chargedAllocation).coerceAtLeast(0L),
            needPoint = numericalWitness == null, direction = solver.recessionDirection,
            directElapsed = (directElapsed() - chargedElapsed).coerceAtLeast(Duration.ZERO),
            reconstructInitial = reconstruction == null,
            preferBasis = result != null && policy === ProductionLpCertificationPolicy,
            preferredBasisCache = solver.exactBasisCache.takeIf {
                result != null && policy === ProductionLpCertificationPolicy
            },
            additionalSourceWork = pointRecovery?.work ?: 0L,
            diagnostic = pairing?.newRefinement(result != null && policy === ProductionLpCertificationPolicy),
            claim = claim,
        )
        refined = recovered
        refinementMetrics = refinementMetrics?.plus(recovered.metrics) ?: recovered.metrics
        pairing?.refinement = refinementMetrics
        chargedWork = directWork
        chargedAllocation = directAllocation
        chargedElapsed = directElapsed()
        recovered.sourceSingularBasis?.let { rejected ->
            (observer as? LpClaimObservation)?.refuse("SINGULAR")
            continuationTarget()
            solver.rejectSingularBasis(model, rejected)
        }
        observer?.observe(
            LpCertifier.RATIONAL,
            recovered.witness != null || recovered.bound != null ||
                recovered.conflict != null || recovered.unboundedness != null,
            LpCertifierCost.Metered(recovered.metrics.work),
        )
        numericalWitness = numericalWitness ?: recovered.witness
        numericalBound = recovered.bound ?: numericalBound
        numericalConflict = numericalConflict ?: recovered.conflict
        if (!withheldWitness && recovered.witness != null) {
            val point = policy.acceptNullable(LpCertifier.RATIONAL, recovered.witness)?.let {
                if (recovered.witnessUsesBasis) policy.acceptNullable(LpCertifier.EXACT_BASIS, it) else it
            }
            withheldWitness = point == null
            if (point != null && (
                    witness == null || point.objective < requireNotNull(
                        witness,
                    ).objective
                    )
            ) {
                witness = point
            }
        }
        if (!withheldBound && recovered.bound != null) {
            val stronger = policy.acceptNullable(LpCertifier.RATIONAL, recovered.bound)?.let {
                if (recovered.boundUsesBasis) policy.acceptNullable(LpCertifier.EXACT_BASIS, it) else it
            }
            withheldBound = stronger == null
            if (stronger != null && (bound == null || stronger.value > requireNotNull(bound).value)) bound = stronger
        }
        if (!withheldConflict && recovered.conflict != null && numericalWitness == null) {
            conflict = policy.acceptNullable(LpCertifier.RATIONAL, recovered.conflict)?.let {
                policy.acceptNullable(LpCertifier.EXACT_FARKAS, it)
            }?.let {
                if (recovered.conflictUsesBasis) policy.acceptNullable(LpCertifier.EXACT_BASIS, it) else it
            }
            withheldConflict = conflict == null
        }
    }
    if (result != null && claim.needsMore(bound, witness) && !stopped()) {
        reconstruction = reconstructCertificate(
            model,
            result.primal,
            result.duals.takeUnless { claim == LpClaim.FEASIBLE_COMPLETION || claim == LpClaim.CONTRADICTION },
            result.basis,
            cancellation = cancellation,
            claim = claim,
        )
        observer?.observe(
            LpCertifier.RATIONAL,
            reconstruction.witness != null || reconstruction.bound != null,
            LpCertifierCost.Metered(reconstruction.metrics.work),
        )
        numericalWitness = reconstruction.witness ?: numericalWitness
        numericalBound = reconstruction.bound ?: numericalBound
        val point = policy.acceptNullable(LpCertifier.RATIONAL, reconstruction.witness)
        if (point != null && (witness == null || point.objective < requireNotNull(witness).objective)) witness = point
        val stronger = policy.acceptNullable(LpCertifier.RATIONAL, reconstruction.bound)
        if (stronger != null && (bound == null || stronger.value > requireNotNull(bound).value)) bound = stronger
    }
    if (result != null && witness == null && !stopped()) {
        witness = component?.exactWitness(observer, policy, cancellation)
        numericalWitness = witness ?: numericalWitness
    }
    if (result != null && numericalWitness == null && refinement != null &&
        model.m > RationalBasisLimits().dimension && policy === ProductionLpCertificationPolicy && !stopped()
    ) {
        pointAttempted = true
        val ordinaryScans = LpScanCount(model)
        val ordinary = exactPointWitness(
            model,
            result.primal,
            observer.takeUnless { sparsePointRecovery },
            ordinaryScans,
        )
        val point = if (ordinary != null || !sparsePointRecovery) {
            if (sparsePointRecovery) observer?.observe(LpCertifier.EXACT_POINT, true, ordinaryScans.cost())
            ordinary
        } else {
            val recovered = recoverExactPointWitness(
                model,
                result.primal,
                refinement,
                cancellation,
                observer,
            )
            observer?.observePhase(LpPhaseMetrics(
                LpSolvePhase.EXACT_POINT_RECOVERY, recovered.decline?.name ?: "WITNESS",
                recovered.elapsed.inWholeNanoseconds, work = recovered.work, steps = recovered.repairs,
            ))
            pointRecovery = recovered
            recovered.witness
        }
        numericalWitness = point
        witness = policy.acceptNullable(LpCertifier.EXACT_POINT, point)
    }
    if (result != null && claim.needsMore(numericalBound, numericalWitness) &&
        !stopped()
    ) {
        refine()
    }
    val refinementUnavailable = refined == null || refined.metrics.decline == LpRefinementDecline.DISABLED ||
        refined.metrics.decline == LpRefinementDecline.AUTHORITY
    if (result != null && refinementUnavailable &&
        claim.needsMore(bound, witness) && !stopped()
    ) {
        val checked = verifyExactBasis(
            model,
            result.basis,
            cache = solver.exactBasisCache ?: ExactBasisCache(),
            cancellation = cancellation,
            observer = observer,
            primalOnly = claim == LpClaim.FEASIBLE_COMPLETION || claim == LpClaim.CONTRADICTION,
        )
        exactBasis = checked
        if (checked.singularRank != null) {
            continuationTarget()
            solver.rejectSingularBasis(model, result.basis)
        }
        numericalWitness = checked.witness ?: numericalWitness
        numericalBound = checked.bound ?: numericalBound
        val point = if (withheldWitness) null else policy.acceptNullable(LpCertifier.EXACT_BASIS, checked.witness)
        if (point != null && (witness == null || point.objective < requireNotNull(witness).objective)) witness = point
        val basisBound = if (withheldBound) null else policy.acceptNullable(LpCertifier.EXACT_BASIS, checked.bound)
        val stronger = policy.acceptNullable(LpCertifier.RATIONAL, basisBound)
        if (stronger != null && (bound == null || stronger.value > requireNotNull(bound).value)) bound = stronger
    }
    if (result != null && witness == null && !pointAttempted && !withheldWitness && !stopped()) {
        val point = exactPointWitness(model, result.primal, observer)
        numericalWitness = point ?: numericalWitness
        witness = policy.acceptNullable(LpCertifier.EXACT_POINT, point)
    }
    // An independently checked point refutes infeasibility; rejecting a ray candidate does not.
    if (result == null && numericalWitness == null) {
        if (state != null) {
            val scans = LpScanCount(model)
            conflict = solver.infeasibleRay?.let { exactStateConflict(model, it, scans) }
            observer?.observe(LpCertifier.EXACT_FARKAS, conflict != null, scans.cost())
            numericalConflict = conflict
            conflict = policy.acceptNullable(LpCertifier.EXACT_FARKAS, conflict)
        } else {
            ray = solver.infeasibleRay?.let {
                policy.acceptNullable(
                    LpCertifier.EXACT_FARKAS,
                    certifyLpFarkas(
                        model,
                        it,
                        observer = observer,
                    ),
                )
            }
        }
    }
    if (result == null && numericalWitness == null && ray == null && conflict == null &&
        !stopped()
    ) {
        solver.infeasibleRay?.let { candidate ->
            reconstruction = reconstructCertificate(model, ray = candidate, cancellation = cancellation)
            observer?.observe(
                LpCertifier.RATIONAL,
                reconstruction.conflict != null,
                LpCertifierCost.Metered(reconstruction.metrics.work),
            )
            numericalConflict = reconstruction.conflict
            conflict = policy.acceptNullable(LpCertifier.RATIONAL, reconstruction.conflict)
        }
    }
    if (result == null && numericalWitness == null && ray == null && conflict == null &&
        !stopped()
    ) {
        solver.infeasibleBasis?.let { basis ->
            val checked = verifyExactBasis(
                model,
                basis,
                rayRow = solver.infeasibleRow,
                cache = solver.exactBasisCache ?: ExactBasisCache(),
                cancellation = cancellation,
                observer = observer,
            )
            exactBasis = checked
            if (checked.singularRank != null) {
                continuationTarget()
                solver.rejectSingularBasis(model, basis)
            }
            numericalConflict = checked.conflict
            conflict = if (withheldConflict) null else policy.acceptNullable(LpCertifier.EXACT_FARKAS, checked.conflict)
            if (conflict != null && state == null) ray = checked.integerRay
        }
    }
    if (result == null &&
        (if (claim == LpClaim.ATTAINED_OPTIMUM)
            numericalWitness == null || numericalBound == null
        else
            claim.needsMore(numericalBound, numericalWitness)) &&
        ray == null && numericalConflict == null && conflict == null && !stopped()
    ) {
        refine()
    }
    if (continued == null && witness == null && !withheldWitness && !withheldConflict &&
        ray == null && conflict == null && (state != null || result == null || model.hasContinuous)
    ) {
        continued = continueExactLp(
            model,
            continuationTarget().basis,
            continuationCache,
            cancellation,
            continuationLimits,
            fullEffort = fullContinuation,
            targetMetrics = continuationTarget().metrics,
        )
        pairing?.observe(continued, LpContinuationSite.LATE, result != null)
        observer?.observeContinuation(continued.metrics)
        observer?.observe(LpCertifier.RATIONAL, continued.metrics.success, LpCertifierCost.Reported)
        numericalWitness = continued.witness
        numericalConflict = continued.conflict
        val accepted = policy.acceptNullable(LpCertifier.RATIONAL, continued.takeIf { it.metrics.success })
        witness = accepted?.witness?.let { policy.acceptNullable(LpCertifier.EXACT_BASIS, it) }
        conflict = accepted?.conflict?.let { policy.acceptNullable(LpCertifier.EXACT_FARKAS, it) }
        // Continuation proves feasibility only. Its point, found after refinement ran without one, seeds the
        // refinement that can prove the optimum.
        if (claim == LpClaim.ATTAINED_OPTIMUM && witness != null && bound?.value != witness.objective &&
            !stopped()
        )
            refine()
    }
    if (continued == null) {
        capturedTarget?.let {
            observer?.observeContinuation(it.metrics)
        }
    }
    if ((claim == LpClaim.FEASIBLE_COMPLETION || claim == LpClaim.CONTRADICTION) &&
        witness != null && bound == null && ray == null && conflict == null &&
        (0 until model.numVars).all { model.exactCost(it).isZero } && !stopped()
    ) {
        bound = certifyLpBound(model, DoubleArray(model.m), observer, policy).takeUnless { stopped() }
    }
    if ((numericalWitness != null && witness == null) || (numericalBound != null && bound == null) ||
        (numericalConflict != null && conflict == null)
    ) {
        (observer as? LpClaimObservation)?.refuse("POLICY_REFUSED")
    }
    if (ray != null || conflict != null) bound = null
    val unboundedness = if (claim == LpClaim.ATTAINED_OPTIMUM && bound == null && witness != null &&
        !withheldWitness
    ) {
        if (refined?.unboundedness != null) {
            policy.acceptNullable(LpCertifier.RATIONAL, refined.unboundedness)?.let {
                if (refined.unboundednessUsesBasis) policy.acceptNullable(LpCertifier.EXACT_BASIS, it) else it
            }
        } else {
            solver.recessionDirection?.let { direction ->
                policy.acceptNullable(
                    LpCertifier.EXACT_POINT,
                    checkedLpUnboundedness(model, requireNotNull(witness), direction),
                )
            }
        }
    } else {
        null
    }
    if (refined != null && stopped()) {
        return CertifiedLpResult(
            null,
            null,
            null,
            null,
            null,
            false,
            { null },
            refinement = refinementMetrics,
        )
    }
    val certified = CertifiedLpResult(
        result,
        bound,
        witness,
        ray,
        conflict,
        model.hasIntegralObjective(),
        // The remembered copy carries no safe bound; the returned result evaluates it lazily from a frozen snapshot.
        safeBound = { null },
    )
    counterResults?.remember(model, certified, policy)
    return CertifiedLpResult(
        result,
        bound,
        witness,
        ray,
        conflict,
        model.hasIntegralObjective(),
        safeBound = result?.let {
            val snapshot = if (state != null) {
                state.toWorkingModel()
            } else {
                LpCapturedModel.captureOrNull(
                    model,
                )?.toModel()
            }
            val duals = it.duals.copyOf()
            val compute: () -> Double? = {
                snapshot?.let { frozen ->
                    policy.acceptNullable(LpCertifier.SAFE_OBJECTIVE, safeObjectiveLowerBound(frozen, duals))
                }
            }
            compute
        } ?: { null },
        unboundedness = unboundedness,
        refinement = refinementMetrics,
        reconstruction = reconstruction?.metrics,
        basisVerification = exactBasis?.metrics,
        continuation = continued?.metrics ?: capturedTarget?.metrics,
        exactPoint = pointRecovery,
        conflictSupport =
        refined?.support?.takeIf { conflict === refined.conflict }
            ?: continued?.support?.takeIf { conflict === continued.conflict }
            ?: exactBasis?.conflictSupport?.takeIf { conflict === exactBasis.conflict }
            ?: reconstruction?.conflictSupport?.takeIf { conflict === reconstruction.conflict }
            ?: conflict?.let(model::exactConflictSupport),
    )
}
