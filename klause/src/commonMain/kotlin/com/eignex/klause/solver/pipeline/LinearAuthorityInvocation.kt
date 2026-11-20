package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.LP_WORK_PER_NODE
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.OptimalityTolerance
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.lp.optimization.LinearAuthority
import com.eignex.klause.lp.optimization.LinearAuthorityCapture
import com.eignex.klause.lp.optimization.LinearAuthorityFeedbackRequest
import com.eignex.klause.lp.optimization.linearCoordinatesSupported
import com.eignex.klause.lp.optimization.withLinearAuthority
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.objective.OptimalityGap
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.search.BooleanBranching
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.HeuristicBooleanBranching
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchComponentSet
import com.eignex.klause.solver.search.SearchExecutionAccounting
import com.eignex.klause.solver.search.SearchExecutionControlAdapter
import com.eignex.klause.solver.search.SearchFrontierRun
import com.eignex.klause.solver.search.SearchInvocationResources
import com.eignex.klause.solver.search.SearchRunEvent
import com.eignex.klause.solver.search.SearchSolveParams
import com.eignex.klause.solver.search.Vsids
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

internal data class LinearInvocationResult(
    val terminal: SourceEvidence?,
    val bound: SourceEvidence?,
    val incumbent: Sample?,
    val value: BigFraction?,
    val reason: TerminationReason,
    val stats: SolveStats,
    val gapClosed: Boolean = false,
)

internal class LinearAuthorityInvocation(
    private val context: SourceProofContext,
    private val params: TheoryParams,
    private val external: () -> Sample? = { null },
    private val state: OpenTheorySolveState = OpenTheorySolveState(params),
    useBranchHistory: Boolean = false,
    borrowedAuthority: LinearAuthority? = null,
    private val boundOrdered: Boolean = false,
    guidance: Boolean = false,
    private val funding: SourceLpFunding = borrowedAuthority?.funding
        ?: SourceLpFunding(params.sourceLpWorkLimit, params.sourceLpAllocationLimit),
    cuts: Boolean = boundOrdered && params.sourceCuts,
    activeRows: Boolean = false,
    supportBranching: Boolean = boundOrdered && params.sourceCutBranching,
    onNumericalFeedback: ((SourceNumericalFeedbackResult) -> Unit)? = null,
    latestFeedback: Boolean = true,
    feedbackRequest: LinearAuthorityFeedbackRequest? = null,
    aggregateBranching: Boolean = false,
    private val onIncumbentImport: (() -> Unit)? = null,
    private val onIncumbentUse: (() -> Unit)? = null,
    private val observeExternalIncumbents: Boolean = false,
    sharedCompletions: LinearIncumbentSharing? = null,
    incumbentCompletionCheck: ((Sample, BigFraction, ExecutionControl) -> Boolean)? = null,
    private val repairGuidance: LinearRepairGuidance? = null,
    gap: OptimalityGap? = null,
    useSourceGap: Boolean = true,
    gapTolerance: OptimalityTolerance? = context.semantics?.effective?.optimalityTolerance,
    private val guideIncumbent: Boolean = params.sourceIncumbentCompletion,
    private val incumbentProximity: Boolean = false,
    borrowedBudget: SourceLpBudget? = null,
    private val recordProgress: Boolean = true,
    private val proximityCenter: Sample? = null,
) : AutoCloseable {
    init {
        require(borrowedAuthority?.funding == null || borrowedAuthority.funding === funding) {
            "borrowed linear authority has another work allowance"
        }
        require(borrowedBudget == null || borrowedBudget.funding === funding)
    }
    private val started = TimeSource.Monotonic.markNow()
    private var closed = false
    private var currentStop = params.timeout.withAccounting(params.cancellation) or params.cancellation
    private val stop = object : ExecutionControl() {
        override fun isCancelled(): Boolean = closed || currentStop()
        override fun deadline() = currentStop.deadline()
        override val accounting get() = currentStop.accounting
        override val admission get() = admissionFrom(currentStop)
    }
    private var sliceEnd: ComparableTimeMark? = null
    private var endWork = Long.MAX_VALUE
    private var startWork = 0L
    private var callback: (Sample, BigFraction) -> Unit = { _, _ -> }
    private var incumbent: Sample? = null
    private var value: BigFraction? = null
    private var incumbentRevision = 0L
    private var importedIncumbents = 0L
    private var guidedFrontierRanks = 0L
    private val externalOffers = mutableMapOf<String, Long>()
    private var importedIncumbent: Sample? = null
    private var consumedIncumbent: Sample? = null
    private var proximityIncumbent: Sample? = null
    private var result: LinearInvocationResult? = null
    private var proximity: LinearAuthorityInvocation? = null
    private var proximityTurn = false
    private var proximityLatest: Map<String, Long> = emptyMap()
    private var exchangePrepared = false
    private var exchange: LinearIncumbentExchange? = null
    private val budget = borrowedBudget ?: SourceLpBudget(maxOperations = params.sourceLpOperations,
        onWork = state.smt::observeSourceLp, funding = funding)
    private val completion = if (params.sourceIncumbentCompletion)
        LinearIncumbentCompletion(budget, incumbentCompletionCheck, sharedCompletions) { stage, count ->
            val key = "numericalExternalCompletion$stage"
            externalOffers[key] = (externalOffers[key] ?: 0L) + count
        } else null
    val isDone: Boolean get() = result != null
    val hasIncumbent: Boolean get() = incumbent != null
    val incumbentIsImported: Boolean get() = incumbent != null && incumbent === importedIncumbent
    val incumbentIsProximity: Boolean get() = incumbent != null && incumbent === proximityIncumbent
    fun captureTo(journal: LinearAuthorityCapture) = component.captureTo(journal)
    val work: Long get() = state.work.spent + funding.spent / LP_WORK_PER_NODE
    val stats: SolveStats get() = SolveStats(
        run = SolveStats.EMPTY.run.copy(backend = "linear-authority",
            wallMs = started.elapsedNow().inWholeMilliseconds, timedOut = params.timeout()),
        smt = state.smt.snapshot().let { snapshot ->
            snapshot.copy(
                completeSearchStages = snapshot.completeSearchStages.orEmpty() + component.observations() +
                    externalOffers + proximityLatest +
                    mapOf("numericalFundedWork" to funding.spent, "numericalFundedAllocation" to funding.allocated,
                        "numericalImportedIncumbents" to importedIncumbents,
                        "numericalIncumbentFrontierRanks" to guidedFrontierRanks) +
                    (frontier?.let {
                        mapOf("numericalFrontierPending" to it.pending.toLong(),
                            "numericalFrontierPeakPending" to it.peakPending.toLong())
                    } ?: emptyMap()))
        },
        openTheory = state.work.snapshot(),
        search = state.progress(if (closed) null else session))
    private val component = LinearAuthorityTraversal(context,
        budget, stop, ::paused, ::offer,
        {
            incumbent?.let { sample ->
                value?.let {
                    if (sample === importedIncumbent && sample !== consumedIncumbent) {
                        consumedIncumbent = sample
                        onIncumbentUse?.invoke()
                    }
                    sample to it
                }
            }
        }, useBranchHistory, borrowedAuthority,
        useGuidance = boundOrdered || guidance || supportBranching || onNumericalFeedback != null,
        inheritBounds = boundOrdered, useCuts = cuts,
        useActiveRows = activeRows, useRefutationReuse = params.sourceRefutationReuse,
        useRefutationPropagation = params.sourceRefutationPropagation,
        useRefutationResolution = params.sourceRefutationResolution, useSupportBranching = supportBranching,
        pointCuts = boundOrdered && params.sourcePointCuts, onNumericalFeedback = onNumericalFeedback,
        aggregateCuts = boundOrdered && params.sourceAggregateCuts, latestFeedback = latestFeedback,
        feedbackRequest = feedbackRequest, aggregateBranching = aggregateBranching, repairGuidance = repairGuidance,
        gap = gap, useSourceGap = useSourceGap, gapTolerance = gapTolerance,
        guideIncumbent = guideIncumbent && !incumbentProximity, proximityCenter = proximityCenter)
    private val session = SearchComponentSet(listOf(component)).session(params.maxLeaves, stop,
        atoms = if (params.sourceRefutationPropagation || params.sourceRefutationResolution) SearchAtomRegistry(
            context.coordinates.filter {
                it.kind == com.eignex.klause.solver.evidence.SourceCoordinate.Kind.Boolean
            }.maxOfOrNull { it.id + 1 } ?: 0) else null).also {
        val accounting = OpenTheoryExecutionAccounting(state.work)
        it.attachExecutionControls(
            SearchExecutionControlAdapter(stop, object : SearchExecutionAccounting by accounting {
                override fun canCommitDecision(): Boolean =
                    state.remainingDecisions() > 0 && accounting.canCommitDecision()
                override fun consumeCheck(): Boolean = state.work.acceptedChecks < params.maxLeaves &&
                    accounting.consumeCheck()
            }),
        )
    }
    private var initialized = false
    private val branching = if (params.openBranching != OpenBranching.Activity)
        BooleanBranching.SourceOrder(context.coordinates.count {
            it.kind == com.eignex.klause.solver.evidence.SourceCoordinate.Kind.Boolean
        }) else HeuristicBooleanBranching(Vsids(), context.coordinates.count {
        it.kind == com.eignex.klause.solver.evidence.SourceCoordinate.Kind.Boolean
    })
    private val run = if (boundOrdered) null else session.openRun(context.coordinates.count {
        it.kind == com.eignex.klause.solver.evidence.SourceCoordinate.Kind.Boolean
    }, SearchSolveParams(state.remainingDecisions()), decisionBudget = state.decisionBudget,
        booleanBranching = branching, nodePolicy = component)
    private val frontier = if (!boundOrdered) null else SearchFrontierRun(
        session, SearchSolveParams(state.remainingDecisions()), branching, component, state.decisionBudget,
        ::frontierRank, Comparator { left, right ->
            val depth = left.first.compareTo(right.first)
            when {
                depth != 0 -> depth
                left.second == null && right.second == null -> 0
                left.second == null -> -1
                right.second == null -> 1
                else -> requireNotNull(left.second).compareTo(requireNotNull(right.second))
            }
        }, ::paused, { incumbentRevision },
    )

    private fun frontierRank(): Pair<Int, BigFraction?> {
        val depth = if ((guideIncumbent && !incumbentProximity && incumbent != null) || proximityCenter != null)
            session.decisionLevel else 0
        if (depth > 0) guidedFrontierRanks++
        return -depth to component.frontierRank()
    }

    fun run(onIncumbent: (Sample, BigFraction) -> Unit): LinearInvocationResult {
        while (true) {
            runSlice(ExecutionControl.Never, Long.MAX_VALUE, -1, onIncumbent)?.let { return it }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Callback failure retires the invocation and preserves cleanup failures.
    fun runSlice(
        cancellation: ExecutionControl,
        millis: Long,
        work: Long,
        onIncumbent: (Sample, BigFraction) -> Unit,
    ): LinearInvocationResult? {
        result?.let { return it }
        check(!closed)
        currentStop = params.timeout.withAccounting(params.cancellation) or params.cancellation or cancellation
        sliceEnd = if (millis == Long.MAX_VALUE) null else TimeSource.Monotonic.markNow() + millis.milliseconds
        startWork = this.work
        endWork = if (work < 0) Long.MAX_VALUE else this.work + minOf(work, Long.MAX_VALUE - this.work)
        callback = onIncumbent
        try {
            if (!context.affineObjectiveAvailable || !context.linearCoordinatesSupported()) {
                return finish(null, TerminationReason.Unsupported)
            }
            funding.grant(if (work < 0) 20_000_000L else maxOf(20_000_000L,
                work.coerceIn(1L, 150_000L) * LP_WORK_PER_NODE))
            external()?.takeUnless { it === incumbent }?.let { sample ->
                val before = incumbentRevision
                val completed = if (completion != null)
                    completion.complete(context, sample, stop) else sample
                if (completed != null && completed !== incumbent) offer(completed, external = true)
                if (incumbentRevision != before) {
                    importedIncumbents++
                    importedIncumbent = incumbent
                    onIncumbentImport?.invoke()
                    if (incumbentProximity && completion != null) prepareProximity()
                }
            }
            if (stop()) return finish(null, if (params.timeout()) TerminationReason.Timeout
            else TerminationReason.Cancelled)
            if (funding.exhausted) return finish(null, TerminationReason.BudgetExhausted)
            if (!exchangePrepared && proximityCenter != null) {
                exchangePrepared = true
                exchange = LinearIncumbentExchange.prepare(context, proximityCenter, budget, stop,
                    claim = {
                        state.remainingDecisions() > 0 && state.work.acceptedChecks < params.maxLeaves &&
                            state.work.hasAllowance() && state.decisionBudget.consume() &&
                            state.work.theoryDecision() && state.work.theoryCheck()
                    }, observe = { stage, count ->
                        val key = "numericalExchange$stage"
                        externalOffers[key] = (externalOffers[key] ?: 0L) + count
                    })
            }
            exchange?.takeUnless { it.finished }?.advance(stop or ExecutionControl { paused() })?.let(::offer)
            if (stop()) return finish(null, if (params.timeout()) TerminationReason.Timeout
            else TerminationReason.Cancelled)
            if (paused()) return null
            if (!initialized) {
                initialized = true
                if (session.initialize() !is ComponentResult.Consistent) {
                    return finish(null, TerminationReason.Unsupported)
                }
            }
            if (proximityTurn && proximity != null) {
                proximityTurn = false
                advanceProximity()
                if (stop()) return finish(null, if (params.timeout()) TerminationReason.Timeout
                else TerminationReason.Cancelled)
                if (funding.exhausted) return finish(null, TerminationReason.BudgetExhausted)
                if (paused()) return null
            }
            val revision = incumbentRevision
            val event = frontier?.next() ?: checkNotNull(run).next()
            if (incumbentProximity && completion != null && importedIncumbents > 0 &&
                incumbentRevision != revision) {
                if (incumbent?.numRealVars == 0) prepareProximity() else {
                    proximity?.close()
                    proximity = null
                }
            }
            proximityTurn = proximity != null
            if (boundOrdered) component.updateGlobalBound()
            component.ray?.let { return finish(it, TerminationReason.SearchExhausted) }
            if (component.gapBound() != null) return finish(null, TerminationReason.SearchExhausted, gapClosed = true)
            return when (event) {
                SearchRunEvent.Paused -> null
                SearchRunEvent.Exhausted -> {
                    val terminal = component.completed()
                    finish(terminal, if (terminal == null) TerminationReason.Unsupported
                    else TerminationReason.SearchExhausted)
                }
                is SearchRunEvent.Indeterminate -> finish(null, when {
                    params.timeout() -> TerminationReason.Timeout
                    stop() -> TerminationReason.Cancelled
                    event == SearchRunEvent.Indeterminate.Budget -> TerminationReason.BudgetExhausted
                    session.workExhausted() || session.checkBudgetExhausted() ||
                        session.decisionBudgetExhausted() -> TerminationReason.BudgetExhausted
                    else -> TerminationReason.Unsupported
                })
                is SearchRunEvent.Satisfied ->
                    error("linear authority resolves an optimized node before model assembly")
            }
        } catch (failure: Throwable) {
            try {
                close()
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        } finally {
            callback = { _, _ -> }
            currentStop = params.timeout.withAccounting(params.cancellation) or params.cancellation
        }
    }

    private fun paused(): Boolean = work > startWork && (work >= endWork || sliceEnd?.hasPassedNow() == true)

    private fun observeProximity(stage: String, count: Long = 1L) {
        val key = "numericalProximity$stage"
        externalOffers[key] = (externalOffers[key] ?: 0L) + count
    }

    private fun prepareProximity() {
        val sample = completion?.complete(context, incumbent ?: return, stop) ?: return
        proximity?.close()
        proximity = null
        val exact = value ?: return
        val cutoff = if (context.scope.objective?.sense == ObjectiveSense.MAXIMIZE) exact.negated() else exact
        val source = IncumbentProximity.prepare(context, sample, cutoff, budget, stop,
            repairGuidance?.find(context)) ?: return
        if (sample === importedIncumbent && sample !== consumedIncumbent) {
            consumedIncumbent = sample
            onIncumbentUse?.invoke()
        }
        proximity = LinearAuthorityInvocation(source, params.copy(sourceIncumbentCompletion = false), state = state,
            boundOrdered = true, funding = funding, useSourceGap = false, guideIncumbent = false,
            borrowedBudget = budget, recordProgress = false, proximityCenter = sample)
        proximityTurn = true
        observeProximity("Centers")
    }

    private fun advanceProximity() {
        val active = proximity ?: return
        val remaining = sliceEnd?.let { (it - TimeSource.Monotonic.markNow()).inWholeMilliseconds }
            ?.coerceAtLeast(0L) ?: Long.MAX_VALUE
        if (remaining == 0L || work >= endWork) return
        val before = work
        val preparation = budget.measuredPreparationWork
        val floating = budget.measuredFloatWork
        val certification = budget.measuredBoundCertificationWork
        val revision = incumbentRevision
        val delivered = ExecutionControl { incumbentRevision != revision }
        val millis = if (remaining == Long.MAX_VALUE) 1_000L else maxOf(1L, remaining / 2L)
        val instructions = minOf(5_000L, maxOf(1L, (endWork - work) / 2L))
        val completed = active.runSlice(stop or delivered, millis, instructions) { sample, _ ->
            val checked = completion?.complete(context, sample, stop)
            if (checked != null) offer(checked, external = false, privatePoint = true) else observeProximity("Rejected")
        }
        observeProximity("Consultations")
        observeProximity("Work", (work - before).coerceAtLeast(0L))
        observeProximity("PreparationWork", budget.measuredPreparationWork - preparation)
        observeProximity("FloatWork", budget.measuredFloatWork - floating)
        observeProximity("CertificationWork", budget.measuredBoundCertificationWork - certification)
        proximityLatest = (active.component.observations() + active.externalOffers).mapKeys { (key, _) ->
            "numericalProximityLatest" + key.removePrefix("numerical")
        }
        if (completed != null) {
            observeProximity("Finished${completed.reason.name}")
            observeProximity(when (completed.terminal?.claim) {
                SourceClaim.Refutation -> "RefutedTargets"
                is SourceClaim.Optimal -> "OptimalTargets"
                null -> "UnresolvedTargets"
                else -> "OtherTargets"
            })
        }
        if (incumbentRevision != revision) observeProximity("Improvements")
        if (completed != null || incumbentRevision != revision) {
            proximity = null
            active.close()
            if (incumbentRevision != revision && incumbent?.numRealVars == 0) prepareProximity()
            proximityTurn = false
        }
    }

    private fun offer(sample: Sample) = offer(sample, external = false)

    private fun observeExternalOffer(external: Boolean, stage: String) {
        if (external && observeExternalIncumbents) {
            val key = "numericalExternal$stage"
            externalOffers[key] = (externalOffers[key] ?: 0L) + 1L
        }
    }

    private fun offer(sample: Sample, external: Boolean, privatePoint: Boolean = false) {
        val checking = if (external) completion?.checking(stop) ?: stop else stop
        if (checking()) {
            observeExternalOffer(external, "Stopped")
            return
        }
        if (sample.exactReals == null && context.objective.keys.any {
                it.kind == com.eignex.klause.solver.evidence.SourceCoordinate.Kind.Real
            }
        ) {
            observeExternalOffer(external, "MissingExactReals")
            return
        }
        val checked = SourceEvidenceVerifier(context.withLinearAuthority()).verify(SourceEvidence(context.scope,
            SourceClaim.Witness(sample), SourceDerivation.SourceWitness), checking)
        if (checked != SourceEvidenceCheck.Checked) {
            observeExternalOffer(external, if (checked is SourceEvidenceCheck.Rejected) "PointRejected"
            else "PointUnavailable")
            return
        }
        val exact = context.linearValue(sample, checking)
        if (exact == null) {
            observeExternalOffer(external, "ObjectiveUnavailable")
            return
        }
        val standing = value
        val minimizes = context.scope.objective?.sense == ObjectiveSense.MINIMIZE
        if (standing != null && if (minimizes) exact >= standing else exact <= standing) {
            observeExternalOffer(external, "NotImproving")
            return
        }
        incumbent = sample
        importedIncumbent = null
        proximityIncumbent = sample.takeIf { privatePoint }
        value = exact
        incumbentRevision++
        observeExternalOffer(external, "Accepted")
        callback(sample, exact)
    }

    val objectiveBound: SourceEvidence? get() = component.rootBound

    fun snapshot(reason: TerminationReason): LinearInvocationResult = result
        ?: LinearInvocationResult(null, component.rootBound, incumbent, value, reason, stats)

    private fun finish(terminal: SourceEvidence?, reason: TerminationReason,
        gapClosed: Boolean = false): LinearInvocationResult {
        val cancelled = stop()
        val accepted = terminal?.takeUnless { cancelled }
        val stopped = if (cancelled) {
            if (params.timeout()) TerminationReason.Timeout else TerminationReason.Cancelled
        } else reason
        result = LinearInvocationResult(accepted, component.rootBound, incumbent, value, stopped, stats,
            gapClosed && !cancelled)
        return requireNotNull(result)
    }

    override fun close() {
        if (closed) return
        closed = true
        val active = proximity
        proximity = null
        callback = { _, _ -> }
        val resources = SearchInvocationResources()
        exchange?.let(resources::own)
        completion?.let(resources::own)
        resources.own(component)
        resources.own(AutoCloseable {
            if (recordProgress) state.capture(session)
            session.popTo(0)
        })
        active?.let(resources::own)
        resources.close()
    }
}
