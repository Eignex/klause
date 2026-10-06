package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.RationalBasisLimits
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.BigRationalConflict
import com.eignex.klause.simplex.exact.ContinuationDecline
import com.eignex.klause.simplex.exact.ExactContinuationLimits
import com.eignex.klause.simplex.exact.ExactContinuationMetrics
import com.eignex.klause.simplex.exact.ExactSimplexBound
import com.eignex.klause.util.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger
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
)

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
) {
    val verdict: LpVerdict = when {
        floatOptimum != null -> LpVerdict.TOLERANCE_OPTIMUM
        farkasRay != null || rationalConflict != null || boundConflict != null -> LpVerdict.INFEASIBLE
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
    cancellation: Cancellation = Cancellation.Never,
    context: LpSolveContext = LpSolveContext.Production,
    counterResults: LpCounterResults? = null,
    pricing: LpPricingOptions = LpPricingOptions(),
    observer: LpCertificationObserver? = null,
    refinementLimits: LpRefinementLimits = LpRefinementLimits(),
): CertifiedLpResult {
    val state = LpExactState(model)
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
    )
}

// Float termination hints never determine the proof strength. A float solve stopped by [workLimit] (0: unbounded)
// supplies at most a candidate to certify.
internal fun solveAndCertify(
    model: LpModel,
    warm: Basis? = null,
    cancellation: Cancellation = Cancellation.Never,
    workLimit: Long = 0L,
    componentSplit: Boolean = true,
    observer: LpCertificationObserver? = null,
    context: LpSolveContext = LpSolveContext.Production,
    counterResults: LpCounterResults? = null,
    pricing: LpPricingOptions = LpPricingOptions(),
    refinementLimits: LpRefinementLimits = LpRefinementLimits(),
    floatAccept: ((FloatLpResult) -> Boolean)? = null,
    floatOffset: Double = 0.0,
): CertifiedLpResult {
    val authoritative = if (model.exactState != null) {
        model
    } else {
        val exact = model.authoritativeModel()
            ?: return CertifiedLpResult(null, null, null, null, null, false, { null })
        LpExactState(exact).toWorkingModel()
            ?: return CertifiedLpResult(null, null, null, null, null, false, { null })
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
        floatOffset,
    )
    if (result.floatOptimum != null) return result
    if (authoritative !== model) {
        if ((result.witness?.let { checkedLpWitness(model, it.primal) } == null && result.witness != null) ||
            result.rationalConflict?.let { !checkedLpConflict(model, it) } == true || cancellation()
        ) {
            return CertifiedLpResult(null, null, null, null, null, false, { null })
        }
        counterResults?.remember(model, result, context.certificationPolicy)
    }
    return result
}

private fun certifyAuthoritativeSolve(
    model: LpModel,
    warm: Basis?,
    cancellation: Cancellation,
    workLimit: Long,
    componentSplit: Boolean,
    observer: LpCertificationObserver?,
    context: LpSolveContext,
    counterResults: LpCounterResults?,
    pricing: LpPricingOptions,
    refinementLimits: LpRefinementLimits,
    floatAccept: ((FloatLpResult) -> Boolean)?,
    floatOffset: Double,
): CertifiedLpResult = newLpSolver(
    model,
    cancellation,
    componentSplit,
    context.engineFactory,
    pricing,
    workLimit,
).use { solver ->
    val result = try {
        solver.solve(warm)
    } finally {
        observer?.observeSolve(solver.lastMetrics, solver is ComponentLpSolverCapability)
    }
    // Tolerance semantics accept a float optimum before any exact work; a rejected one falls through to it.
    if (floatAccept != null && result != null && result.optimal && !cancellation()) {
        floatOptimum(model, result, floatAccept, floatOffset, cancellation)?.let { float ->
            return@use CertifiedLpResult(float, null, null, null, null, false, { null }, floatOptimum = float)
        }
    }
    val state = model.exactState
    if (state == null || solver is ComponentLpSolverCapability) {
        certifyLpResult(model, solver, result, cancellation, observer, context.certificationPolicy, counterResults)
    } else {
        LpScopedSolver(state, cancellation, context, pricing = pricing).use { anchor ->
            certifyLpResult(
                model,
                solver,
                result,
                cancellation,
                observer,
                context.certificationPolicy,
                counterResults,
                refinement = LpRefinementRequest(anchor, anchor.refinementCache, refinementLimits),
            )
        }
    }
}

// Certifies only the supplied solve and its current basis; source solves import authority before allocation.
internal fun certifyLpResult(
    model: LpModel,
    solver: LpSolver,
    result: FloatLpResult?,
    cancellation: Cancellation = Cancellation.Never,
    observer: LpCertificationObserver? = null,
    policy: LpCertificationPolicy = ProductionLpCertificationPolicy,
    counterResults: LpCounterResults? = null,
    continuationCache: LpExactContinuationCache = LpExactContinuationCache(),
    continuationLimits: ExactContinuationLimits = ExactContinuationLimits(),
    fullContinuation: Boolean = true,
    refinement: LpRefinementRequest? = null,
    sparsePointRecovery: Boolean = false,
): CertifiedLpResult {
    val certificationStarted = refinement?.let { TimeSource.Monotonic.markNow() }
    if (!model.finiteExactInput()) return CertifiedLpResult(null, null, null, null, null, false, { null })
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
        if (cancellation()) return CertifiedLpResult(null, null, null, null, null, false, { null })
        state.conflict?.let {
            observer?.observe(LpCertifier.EXACT_FARKAS, true, LpCertifierCost.Reported)
            val accepted = policy.acceptNullable(LpCertifier.EXACT_FARKAS, it)?.takeUnless { cancellation() }
            val support = accepted?.let { conflict ->
                LpExactSupport(
                    state,
                    if (conflict.column < model.n) {
                        emptyList()
                    } else {
                        val row = conflict.column - model.n
                        listOf(row to state.model.row(row))
                    },
                    listOf(
                        LpExactCitedSide(conflict.column, false, conflict.lower.side, conflict.lower.witness),
                        LpExactCitedSide(conflict.column, true, conflict.upper.side, conflict.upper.witness),
                    ),
                )
            }
            return CertifiedLpResult(
                null,
                null,
                null,
                null,
                null,
                false,
                { null },
                boundConflict = accepted,
                conflictSupport = support,
            )
        }
        if (result != null && (solver.solvedExactState !== state || result.exactState !== state)) {
            return CertifiedLpResult(null, null, null, null, null, false, { null })
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
                val constantBound = if (continued.conflict == null && !cancellation() &&
                    (0 until model.numVars).all { model.exactCost(it).isZero }
                ) {
                    certifyLpBound(model, DoubleArray(model.m), observer, policy).takeUnless { cancellation() }
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
    var bound = result?.let { certifyLpBound(model, it.duals, observer, policy) }
        ?: result?.let { component?.exactBound(observer, policy) }
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
        if (refinement == null || state == null || cancellation()) return
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
        )
        refined = recovered
        refinementMetrics = refinementMetrics?.plus(recovered.metrics) ?: recovered.metrics
        chargedWork = directWork
        chargedAllocation = directAllocation
        chargedElapsed = directElapsed()
        recovered.sourceSingularBasis?.let { rejected ->
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
    if (result != null && (bound?.value != witness?.objective || witness == null) && !cancellation()) {
        reconstruction = reconstructCertificate(
            model,
            result.primal,
            result.duals,
            result.basis,
            cancellation = cancellation,
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
    if (result != null && witness == null && !cancellation()) {
        witness = component?.exactWitness(observer, policy, cancellation)
        numericalWitness = witness ?: numericalWitness
    }
    if (result != null && numericalWitness == null && refinement != null &&
        model.m > RationalBasisLimits().dimension && policy === ProductionLpCertificationPolicy && !cancellation()
    ) {
        pointAttempted = true
        val ordinaryScans = LpScanCount()
        val ordinary = exactPointWitness(
            model,
            result.primal,
            observer.takeUnless { sparsePointRecovery },
            ordinaryScans,
        )
        val point = if (ordinary != null || !sparsePointRecovery) {
            if (sparsePointRecovery) observer?.observe(LpCertifier.EXACT_POINT, true, ordinaryScans.cost(model))
            ordinary
        } else {
            val recovered = recoverExactPointWitness(
                model,
                result.primal,
                refinement,
                cancellation,
                observer,
            )
            pointRecovery = recovered
            recovered.witness
        }
        numericalWitness = point
        witness = policy.acceptNullable(LpCertifier.EXACT_POINT, point)
    }
    if (result != null && (numericalWitness == null || numericalBound?.value != numericalWitness?.objective) &&
        !cancellation()
    ) {
        refine()
    }
    val legacyBasis = refined == null || refined.metrics.decline == LpRefinementDecline.DISABLED ||
        refined.metrics.decline == LpRefinementDecline.AUTHORITY
    if (result != null && legacyBasis &&
        (witness == null || bound?.value != witness.objective) && !cancellation()
    ) {
        val checked = verifyExactBasis(
            model,
            result.basis,
            cache = solver.exactBasisCache ?: ExactBasisCache(),
            cancellation = cancellation,
            observer = observer,
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
    if (result != null && witness == null && !pointAttempted && !withheldWitness && !cancellation()) {
        val point = exactPointWitness(model, result.primal, observer)
        numericalWitness = point ?: numericalWitness
        witness = policy.acceptNullable(LpCertifier.EXACT_POINT, point)
    }
    // An independently checked point refutes infeasibility; rejecting a ray candidate does not.
    if (result == null && numericalWitness == null) {
        if (state != null) {
            val scans = LpScanCount()
            conflict = solver.infeasibleRay?.let { exactStateConflict(model, it, scans) }
            observer?.observe(LpCertifier.EXACT_FARKAS, conflict != null, scans.cost(model))
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
        !cancellation()
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
        !cancellation()
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
    if (result == null && (numericalWitness == null || numericalBound == null) &&
        ray == null && numericalConflict == null && conflict == null && !cancellation()
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
        observer?.observeContinuation(continued.metrics)
        observer?.observe(LpCertifier.RATIONAL, continued.metrics.success, LpCertifierCost.Reported)
        numericalWitness = continued.witness
        numericalConflict = continued.conflict
        val accepted = policy.acceptNullable(LpCertifier.RATIONAL, continued.takeIf { it.metrics.success })
        witness = accepted?.witness?.let { policy.acceptNullable(LpCertifier.EXACT_BASIS, it) }
        conflict = accepted?.conflict?.let { policy.acceptNullable(LpCertifier.EXACT_FARKAS, it) }
        // Continuation proves feasibility only. Its point, found after refinement ran without one, seeds the
        // refinement that can prove the optimum.
        if (witness != null && bound?.value != witness?.objective && !cancellation()) refine()
    }
    if (continued == null) {
        capturedTarget?.let {
            observer?.observeContinuation(it.metrics)
        }
    }
    if (ray != null || conflict != null) bound = null
    val unboundedness = if (bound == null && witness != null && !withheldWitness) {
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
    if (refined != null && cancellation()) {
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
        // Freeze the value before mutable objective/bound arrays can change; evaluation is opt-in.
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

internal fun certifyLpBound(
    model: LpModel,
    duals: DoubleArray,
    observer: LpCertificationObserver? = null,
    policy: LpCertificationPolicy = ProductionLpCertificationPolicy,
): CertifiedLpBound? {
    if (duals.size != model.m || !model.finiteExactInput()) return null
    if (!model.hasContinuous && model.probeClampedLo.none { it } && model.probeClampedHi.none { it }) {
        val certificate = policy.acceptNullable(LpCertifier.INTEGER, integerCertify(model, duals, observer = observer))
            ?: return null
        val numerator = certificate.objectiveNumerator()
        return CertifiedLpBound(
            BigFraction.of(
                (BigInteger.fromLong(numerator.hi) shl 64) + BigInteger.fromULong(numerator.lo.toULong()),
                BigInteger.ONE shl certificate.objectiveScaleBits,
            ),
            certificate,
        )
    }
    // The same integer-multiplier Lagrangian, evaluated against exact IEEE input without decimal guessing.
    val bound = rationalLpBound(model, duals)
    observer?.observe(LpCertifier.INTEGER, bound != null, LpScanCount().apply { scan() }.cost(model))
    return policy.acceptNullable(LpCertifier.INTEGER, bound)
}

private fun rationalLpBound(model: LpModel, duals: DoubleArray): CertifiedLpBound? {
    if (duals.size != model.m) return null
    val rounded = roundDuals(model, duals) ?: return null
    val scale = BigFraction.ofLong(rounded.scale).reciprocal()
    val y = List(model.m) { row -> BigFraction.ofLong(rounded.mult[row]) * scale }
    val value = exactLagrangian(model, y) ?: return null
    return CertifiedLpBound(value, support = model.exactSupport(y, objective = true))
}

internal fun LpModel.exactConflictSupport(conflict: BigRationalConflict): LpExactSupport? {
    val y = MutableList(m) { BigFraction.ZERO }
    for (i in conflict.rows.indices) y[conflict.rows[i]] -= conflict.multipliers[i]
    return exactSupport(y, objective = false)
}

private fun LpModel.exactSupport(multipliers: List<BigFraction>, objective: Boolean): LpExactSupport? {
    val state = exactState ?: return null
    val y = multipliers.mapIndexed { row, value ->
        val slack = slackCol(row)
        if (objective && hasFiniteLower(slack) && !hasFiniteUpper(slack) && value > exactCost(slack)) {
            exactCost(slack)
        } else {
            value
        }
    }
    val sides = ArrayList<LpExactCitedSide>()
    for (j in 0 until numVars) {
        var coefficient = if (objective) exactCost(j).negated() else BigFraction.ZERO
        forEachRationalColumn(j) { row, a -> coefficient += y[row] * a }
        if (coefficient.isZero) continue
        val upper = coefficient.signum() > 0
        val side = if (upper) exactBounds(j).upper else exactBounds(j).lower
        if (side != null) {
            val witness = state.activeSide(j, upper)?.takeIf { it.side == side }?.witness
            sides += LpExactCitedSide(j, upper, side, witness)
        }
    }
    val rows = (
        y.indices.filter {
            !y[it].isZero
        } + sides.filter { it.column >= n }.map { it.column - n }
        ).distinct().sorted()
    return LpExactSupport(state, rows.map { it to state.model.row(it) }, sides)
}

internal fun exactLagrangian(
    model: LpModel,
    multipliers: List<BigFraction>,
    cancellation: Cancellation = Cancellation.Never,
): BigFraction? {
    if (multipliers.size != model.m || !model.finiteExactInput()) return null
    val y = List(model.m) { row ->
        val candidate = multipliers[row]
        val slackCost = model.exactCost(model.slackCol(row))
        val slack = model.slackCol(row)
        if (model.hasFiniteLower(
                slack,
            ) && !model.hasFiniteUpper(slack) && candidate > slackCost
        ) {
            slackCost
        } else {
            candidate
        }
    }
    var value = model.exactConstant()
    for (i in 0 until model.m) value += y[i] * model.exactRhs(i)
    for (j in 0 until model.numVars) {
        if (cancellation()) return null
        var reduced = model.exactCost(j)
        model.forEachRationalColumn(j) { row, a -> reduced -= y[row] * a }
        if (reduced.signum() > 0) {
            val side = model.exactBounds(j).lower ?: return null
            value += reduced * side.number.value
        }
        if (reduced.signum() < 0) {
            val side = model.exactBounds(j).upper ?: return null
            value += reduced * side.number.value
        }
    }
    return model.sourceObjective(value)
}

internal fun certifyLpFarkas(
    model: LpModel,
    candidate: DoubleArray,
    basis: Basis? = null,
    basisRow: Int = -1,
    onRoute: ((FarkasRoute) -> Unit)? = null,
    observer: LpCertificationObserver? = null,
): LongArray? {
    var route = FarkasRoute.NONE
    val scans = LpScanCount()
    val mechanismObserver = observer?.let { target ->
        object : LpCertificationObserver by target {
            override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) {
                if (certifier != LpCertifier.EXACT_FARKAS) target.observe(certifier, success, cost)
            }
        }
    }
    val ray = integerFarkasRay(
        model,
        candidate,
        basis = basis,
        basisRow = basisRow,
        onRoute = { route = it },
        observer = mechanismObserver,
        scans = scans,
    )?.takeIf {
        scans.scan()
        sourceFarkasValid(model, it)
    }
    observer?.observe(LpCertifier.EXACT_FARKAS, ray != null, scans.cost(model))
    onRoute?.invoke(if (ray != null) route else FarkasRoute.NONE)
    return ray
}

private fun exactStateConflict(model: LpModel, candidate: DoubleArray, scans: LpScanCount): BigRationalConflict? {
    if (candidate.size != model.m || candidate.any { !it.isFinite() }) return null
    val candidates = listOfNotNull(reconstructIntegerVector(candidate), roundDuals(model, candidate)?.mult)
    for (integers in candidates) {
        for (sign in listOf(BigFraction.ONE, BigFraction.MINUS_ONE)) {
            val y = integers.map { BigFraction.ofLong(it) * sign }
            scans.scan()
            val sides = ArrayList<ExactSimplexBound>()
            for (j in 0 until model.numVars) {
                var coefficient = BigFraction.ZERO
                model.forEachRationalColumn(j) { i, a -> coefficient += y[i] * a }
                if (!coefficient.isZero) sides += ExactSimplexBound(j, coefficient.signum() < 0)
            }
            val rows = y.indices.filter { !y[it].isZero }
            val conflict = BigRationalConflict(rows.toIntArray(), rows.map { y[it] }, sides)
            scans.scan()
            if (checkedLpConflict(model, conflict)) return conflict
        }
    }
    return null
}

internal fun sourceFarkasValid(model: LpModel, ray: LongArray): Boolean {
    if (ray.size != model.m || !model.finiteExactInput()) return false
    var surplus = BigFraction.ZERO
    var strict = false
    for (i in 0 until model.m) surplus += BigFraction.ofLong(ray[i]) * model.exactRhs(i)
    for (j in 0 until model.numVars) {
        var coefficient = BigFraction.ZERO
        model.forEachRationalColumn(j) { row, value -> coefficient += BigFraction.ofLong(ray[row]) * value }
        if (coefficient.signum() < 0) {
            val side = model.exactBounds(j).lower ?: return false
            surplus -= coefficient * side.number.value
            strict = strict || side.strict
        }
        if (coefficient.signum() > 0) {
            val side = model.exactBounds(j).upper ?: return false
            surplus -= coefficient * side.number.value
            strict = strict || side.strict
        }
    }
    return surplus.signum() > 0 || (surplus.isZero && strict)
}

internal fun checkedLpConflict(model: LpModel, conflict: BigRationalConflict): Boolean {
    if (!model.finiteExactInput() || conflict.rows.size != conflict.multipliers.size) return false
    val multipliers = MutableList(model.m) { BigFraction.ZERO }
    var rhs = BigFraction.ZERO
    var strict = false
    for (entry in conflict.rows.indices) {
        val row = conflict.rows[entry]
        if (row !in 0 until model.m) return false
        val multiplier = conflict.multipliers[entry]
        multipliers[row] += multiplier
        rhs += multiplier * model.exactRhs(row)
    }
    val lowerCited = BooleanArray(model.numVars)
    val upperCited = BooleanArray(model.numVars)
    for (bound in conflict.bounds) {
        if (bound.column !in 0 until model.numVars) return false
        if (bound.upper) upperCited[bound.column] = true else lowerCited[bound.column] = true
    }
    var minimum = BigFraction.ZERO
    for (j in 0 until model.numVars) {
        var coefficient = BigFraction.ZERO
        model.forEachRationalColumn(j) { row, a -> coefficient += multipliers[row] * a }
        if (coefficient.signum() < 0) {
            val side = model.exactBounds(j).upper ?: return false
            if (!upperCited[j]) return false
            minimum += coefficient * side.number.value
            strict = strict || side.strict
        } else if (coefficient.signum() > 0) {
            val side = model.exactBounds(j).lower ?: return false
            if (!lowerCited[j]) return false
            minimum += coefficient * side.number.value
            strict = strict || side.strict
        }
    }
    return rhs < minimum || (rhs == minimum && strict)
}

internal fun checkedLpWitness(
    model: LpModel,
    primal: List<BigFraction>,
    meter: RefinementMeter? = null,
): ExactLpWitness? {
    if (primal.size != model.n) return null
    meter?.charge(model.n.toLong() + model.m, 16L * (model.n.toLong() + model.m))
    if (!model.finiteExactInput()) return null
    fun add(a: BigFraction, b: BigFraction): BigFraction = meter?.pointAdd(a, b) ?: a + b
    fun multiply(a: BigFraction, b: BigFraction): BigFraction = meter?.pointMultiply(a, b) ?: a * b
    fun subtract(a: BigFraction, b: BigFraction): BigFraction =
        if (b.isZero) a else add(a, meter?.number(b.negated()) ?: b.negated())
    val shifted = primal.mapIndexed { j, value -> subtract(value, model.exactShift(j)) }
    for (j in shifted.indices) {
        meter?.charge()
        if (!model.withinExactBounds(j, shifted[j], meter)) return null
    }
    val activity = MutableList(model.m) { BigFraction.ZERO }
    for (j in 0 until model.n) {
        model.forEachRationalColumn(j) { row, a ->
            meter?.charge()
            activity[row] = add(activity[row], multiply(a, shifted[j]))
        }
    }
    var objective = model.exactConstant()
    for (j in 0 until model.n) {
        meter?.charge()
        objective = add(objective, multiply(model.exactCost(j), shifted[j]))
    }
    for (row in 0 until model.m) {
        meter?.charge()
        val slack = subtract(model.exactRhs(row), activity[row])
        val column = model.slackCol(row)
        if (!model.withinExactBounds(column, slack, meter) ||
            (model.exactRowStrict(row) && slack.isZero)
        ) {
            return null
        }
        objective = add(objective, multiply(model.exactCost(column), slack))
    }
    val source = model.exactState?.model?.objective
    val sourceObjective = if (meter != null && source != null) {
        add(
            multiply(objective, meter.number(meter.number(source.scale.value).reciprocal())),
            source.externalConstant.value,
        )
    } else {
        model.sourceObjective(objective)
    }
    meter?.charge(model.n.toLong(), model.n.toLong() * 8L)
    return ExactLpWitness(primal.toList(), sourceObjective)
}

internal fun checkedLpUnboundedness(
    model: LpModel,
    witness: ExactLpWitness,
    direction: DoubleArray,
): ExactLpUnboundedness? {
    if (direction.size != model.n || direction.any { !it.isFinite() }) return null
    val point = checkedLpWitness(model, witness.primal) ?: return null
    val state = model.exactState
    if (state != null && point.primal.indices.any {
            state.model.column(it).integral && point.primal[it].den != BigInteger.ONE
        }
    ) {
        return null
    }
    val ray = direction.map { checkNotNull(BigFraction.ofDouble(it)) }
    if (state != null && ray.indices.any {
            state.model.column(
                it,
            ).integral && ray[it].den != BigInteger.ONE
        }
    ) {
        return null
    }
    val slackRay = MutableList(model.m) { BigFraction.ZERO }
    var improvement = BigFraction.ZERO
    for (j in 0 until model.n) {
        if (model.exactBounds(j).lower != null && ray[j].signum() < 0) return null
        if (model.hasFiniteUpper(j) && !model.probeClampedHi[j] && ray[j].signum() > 0) return null
        model.forEachRationalColumn(j) { row, a -> slackRay[row] -= a * ray[j] }
        improvement += model.exactCost(j) * ray[j]
    }
    for (row in 0 until model.m) {
        if (model.hasFiniteLower(model.slackCol(row)) && slackRay[row].signum() < 0) return null
        if (model.hasFiniteUpper(model.slackCol(row)) && slackRay[row].signum() > 0) return null
        improvement += model.exactCost(model.slackCol(row)) * slackRay[row]
    }
    if (state != null) {
        for (row in slackRay.indices) {
            if (!state.model.column(model.n + row).integral) continue
            if (slackRay[row].den != BigInteger.ONE) return null
            var slackPoint = model.exactRhs(row)
            for (j in point.primal.indices) {
                model.forEachRationalColumn(j) { i, a ->
                    if (i == row) slackPoint -= a * (point.primal[j] - model.exactShift(j))
                }
            }
            if (slackPoint.den != BigInteger.ONE) return null
        }
    }
    return if (improvement.signum() < 0) ExactLpUnboundedness(point, ray) else null
}

internal fun LpModel.exactShift(j: Int): BigFraction = exactState?.model?.column(j)?.origin?.value
    ?: doubleView?.let { exactDouble(it.loShift[j]) }
    ?: BigFraction.ofLong(loShift[j])

internal fun LpModel.exactCost(j: Int): BigFraction = exactState?.model?.objective?.cost(j)?.value
    ?: doubleView?.let { exactDouble(it.cost[j]) }
    ?: BigFraction.ofLong(cost[j])

internal fun LpModel.exactUpper(j: Int): BigFraction = exactState?.model?.column(j)?.bounds?.upper?.number?.value
    ?: doubleView?.exactUpper(j)
    ?: BigFraction.ofLong(upper[j])

internal fun LpModel.exactRhs(i: Int): BigFraction = exactState?.model?.rhs(i)?.value
    ?: doubleView?.exactRhs(i)
    ?: BigFraction.ofLong(rhs[i])

internal fun LpModel.exactConstant(): BigFraction = exactState?.model?.objective?.constant?.value
    ?: doubleView?.exactObjConstant()
    ?: BigFraction.ofLong(objConstant)

internal fun LpModel.exactLower(j: Int): BigFraction = exactBounds(j).lower?.number?.value ?: BigFraction.ZERO

internal fun LpModel.exactBounds(j: Int): ExactLpBounds {
    val source = exactState?.model?.column(j)?.bounds
    val bounds = source ?: ExactLpBounds(
        if (j >= n || !probeClampedLo[j]) ExactLpSide(ExactLpNumber.of(0L)) else null,
        if (hasFiniteUpper(j) && (j >= n || !probeClampedHi[j])) ExactLpSide(ExactLpNumber.of(exactUpper(j))) else null,
    )
    return if (j >= n && exactRowStrict(j - n) && bounds.lower?.number?.value?.isZero == true) {
        bounds.copy(lower = bounds.lower.copy(strict = true))
    } else {
        bounds
    }
}

private fun LpModel.exactRowStrict(row: Int): Boolean = exactState?.model?.row(row)?.strict ?: rowStrict[row]

internal fun LpModel.withinExactBounds(j: Int, value: BigFraction, meter: RefinementMeter? = null): Boolean {
    val bounds = exactBounds(j)
    meter?.pointVisit(value)
    bounds.lower?.let {
        if ((meter?.pointCompare(value, it.number.value) ?: value.compareTo(it.number.value)) < 0 ||
            (it.strict && value == it.number.value)
        ) {
            return false
        }
    }
    bounds.upper?.let {
        if ((meter?.pointCompare(value, it.number.value) ?: value.compareTo(it.number.value)) > 0 ||
            (it.strict && value == it.number.value)
        ) {
            return false
        }
    }
    return true
}

internal fun LpModel.sourceObjective(value: BigFraction): BigFraction = exactState?.model?.objective?.let {
    value * it.scale.value.reciprocal() + it.externalConstant.value
} ?: value

internal fun LpModel.finiteExactInput(): Boolean {
    if (exactState != null) return exactState.model.n == n && exactState.model.m == m
    if (n < 0 || m < 0 || n > Int.MAX_VALUE - m) return false
    if (colContinuous.size != n || probeClampedLo.size != n || probeClampedHi.size != n ||
        rowStrict.size != m || rowGlobal.size != m || rowPremises.size != m
    ) {
        return false
    }
    val dv = doubleView
    val pointers = dv?.colPtr ?: csc.colPtr
    val rows = dv?.rowIdx ?: csc.rowIdx
    val count = dv?.colVal?.size ?: csc.colVal.size
    if (pointers.size != n + 1 || pointers[0] != 0 || pointers[n] != count || rows.size != count) return false
    for (j in 0 until n) {
        if (pointers[j] > pointers[j + 1] || pointers[j] < 0 || pointers[j + 1] > count) return false
        var previous = -1
        for (entry in pointers[j] until pointers[j + 1]) {
            if (rows[entry] !in 0 until m || rows[entry] <= previous) return false
            previous = rows[entry]
        }
    }
    if (dv == null) {
        return rhs.size == m && cost.size == numVars && upper.size == numVars &&
            hasUpper.size == numVars && loShift.size == n && upper.indices.all { !hasUpper[it] || upper[it] >= 0L }
    }
    if (dv.inexactCoefficients) return false
    return dv.rhs.size == m && dv.cost.size == numVars && dv.upper.size == numVars &&
        dv.hasUpper.size == numVars && dv.loShift.size == n &&
        dv.colVal.all { it.isFinite() } && dv.rhs.all { it.isFinite() } && dv.cost.all { it.isFinite() } &&
        dv.loShift.all { it.isFinite() } && dv.objConstant.isFinite() &&
        dv.upper.indices.all { !dv.hasUpper[it] || (dv.upper[it].isFinite() && dv.upper[it] >= 0.0) }
}

internal fun exactDouble(value: Double): BigFraction = checkNotNull(BigFraction.ofDouble(value))

internal inline fun LpModel.forEachRationalColumn(j: Int, action: (Int, BigFraction) -> Unit) {
    if (j >= n) {
        action(j - n, BigFraction.ONE)
    } else {
        val source = exactState?.model
        if (source != null) {
            for (entry in source.entries(j)) action(entry.row, entry.number.value)
            return
        }
        val dv = doubleView
        if (dv == null) {
            forEachInColumn(j) { row, a -> action(row, BigFraction.ofLong(a)) }
        } else {
            for (p in dv.colPtr[j] until dv.colPtr[j + 1]) action(dv.rowIdx[p], exactDouble(dv.colVal[p]))
        }
    }
}

internal fun LpModel.hasIntegralObjective(): Boolean {
    if (!finiteExactInput()) return false
    exactState?.model?.let { source ->
        val scale = source.objective.scale.value.reciprocal()
        var constant = source.objective.constant.value * scale + source.objective.externalConstant.value
        for (j in 0 until numVars) {
            val c = source.objective.cost(j).value * scale
            if (c.isZero) continue
            if (j >= n || !source.column(j).integral || c.den != BigInteger.ONE) return false
            constant -= c * source.column(j).origin.value
        }
        return constant.den == BigInteger.ONE
    }
    if (doubleView == null) return cost.indices.all { cost[it] == 0L || (it < n && !colContinuous[it]) }
    var sourceConstant = exactConstant()
    for (j in 0 until numVars) {
        val c = exactCost(j)
        if (c.isZero) continue
        if (j >= n || colContinuous[j] || c.den != BigInteger.ONE) return false
        sourceConstant -= c * exactShift(j)
    }
    return sourceConstant.den == BigInteger.ONE
}

internal fun BigFraction.ceilLong(): Long? {
    var ceiling = num / den
    if (num.signum() > 0 && !(num % den).isZero()) ceiling += BigInteger.ONE
    if (ceiling < BigInteger.fromLong(Long.MIN_VALUE) || ceiling > BigInteger.fromLong(Long.MAX_VALUE)) return null
    return ceiling.longValue(exactRequired = true)
}

// A bounded value snapshot prevents sibling, objective and premise changes from reusing counters.
internal class LpCounterResults {
    private var state: LpExactState? = null
    private var key: ByteArray? = null
    private var evidence: CertifiedLpResult? = null
    private var acceptedBy: LpCertificationPolicy? = null
    var storageDeclined: Boolean = false
        private set

    fun declineStorage() {
        storageDeclined = true
        state = null
        key = null
        evidence = null
        acceptedBy = null
    }

    fun read(model: LpModel, policy: LpCertificationPolicy): CertifiedLpResult? {
        val current = keyOf(model)
        if (policy !== ProductionLpCertificationPolicy || current == null ||
            key?.contentEquals(current) != true || acceptedBy !== policy || state !== model.exactState
        ) {
            state = null
            key = null
            evidence = null
            acceptedBy = null
            return null
        }
        return evidence
    }

    fun remember(model: LpModel, result: CertifiedLpResult, policy: LpCertificationPolicy) {
        val current = keyOf(model) ?: run {
            declineStorage()
            return
        }
        if (policy !== ProductionLpCertificationPolicy || (result.witness == null && result.bound == null)) return
        state = model.exactState
        key = current
        // No float vectors, lazy model views, candidate rejections or infeasibility claims are cached.
        evidence = CertifiedLpResult(null, result.bound, result.witness, null, null, false, { null })
        acceptedBy = policy
    }

    private fun keyOf(model: LpModel): ByteArray? = exactLpStateKey(model).also { storageDeclined = it == null }
}

internal fun exactLpStateKey(model: LpModel): ByteArray? {
    model.exactState?.let { return LpExactCapture.stateKey(it) }
    var size = model.n.toLong() * 16 + model.m.toLong() * 16 + model.csc.colVal.size.toLong() * 3
    model.doubleView?.let { size += it.colVal.size.toLong() * 3 }
    for (premises in model.rowPremises) {
        if (premises != null) {
            size += premises.vars.size.toLong() * 3 + premises.boolLits.size
        }
    }
    if (size > MAX_COUNTER_KEY_VALUES) return null
    val captured = LpCapturedModel.captureOrNull(model) ?: return null
    return LpCapture(LP_CAPTURE_VERSION, LpReplaySettings("exact-counter", 0L), captured, emptyList()).encode()
}

internal fun exactLpStateKey(model: ExactLpModel): ByteArray? {
    if (model.keySize > MAX_COUNTER_KEY_VALUES) return null
    val state = LpExactState(model)
    if (state.toWorkingModel() == null) return null
    return LpExactCapture.stateKey(state)
}

private const val MAX_COUNTER_KEY_VALUES = 4096L

// A float optimum the dual check cannot prove under the engine's duals is checked again under the exact duals of
// its own basis: at a dual-degenerate optimum a reduced cost that is exactly zero takes a random sign from any
// inexact duals, and only the exact zero clears a column nothing bounds. A basis whose exact duals leave some
// reduced cost truly wrong-signed, or whose exact duals decline, gets one cleanup: primal pivots from its own basis
// at a pricing tolerance well below the engine's, as a solver re-optimizes when unscaled infeasibilities exceed its
// tolerance. A stop the engine made early moves on to the true optimum in a few pivots; a basis already optimal but
// priced with noisy duals finds nothing to pivot on and is left to the exact ladder, so the pivots are capped.
private fun floatOptimum(
    model: LpModel,
    result: FloatLpResult,
    floatAccept: (FloatLpResult) -> Boolean,
    offset: Double,
    cancellation: Cancellation,
): FloatLpResult? {
    val verdict = provenFloatOptimum(model, result, offset, cancellation)
    if (verdict == FloatDualVerdict.ACCEPTED) return result.takeIf(floatAccept)
    if (verdict == FloatDualVerdict.REFUSED || cancellation()) return null
    val cleanup = RevisedSimplex(
        model,
        cancellation,
        iterationLimit = CLEANUP_PIVOTS,
        primalPricingTolerance = CLEANUP_PRICING_TOLERANCE,
    ).use { it.solvePrimal(result.basis) }
        ?.takeIf { it.optimal && !cancellation() }
        ?: return null
    return cleanup.takeIf {
        provenFloatOptimum(model, it, offset, cancellation) == FloatDualVerdict.ACCEPTED && floatAccept(it)
    }
}

// ACCEPTED when the engine's duals or the basis's exact duals prove the optimum; WRONG_SIGNED when that is left
// undecided by a declined exact solve or refuted by a truly wrong-signed exact reduced cost; REFUSED otherwise.
private fun provenFloatOptimum(
    model: LpModel,
    result: FloatLpResult,
    offset: Double,
    cancellation: Cancellation,
): FloatDualVerdict {
    if (floatDualFeasible(model, result, offset)) return FloatDualVerdict.ACCEPTED
    val duals = exactBasisDuals(
        model,
        result.basis,
        result.duals,
        cancellation.shorten(EXACT_DUAL_BUDGET_FRACTION),
    ).duals ?: return FloatDualVerdict.WRONG_SIGNED
    return floatDualVerdict(model, result, offset, exactDuals = duals)
}

private const val EXACT_DUAL_BUDGET_FRACTION: Double = 0.5
private const val CLEANUP_PRICING_TOLERANCE: Double = 1e-12
private const val CLEANUP_PIVOTS: Int = 100
