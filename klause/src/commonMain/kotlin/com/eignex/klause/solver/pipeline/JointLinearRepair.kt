package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.LP_WORK_PER_NODE
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.lp.SparseSourceLpPolicy
import com.eignex.klause.lp.optimization.LinearAuthority
import com.eignex.klause.lp.optimization.LinearAuthorityFeedbackRequest
import com.eignex.klause.lp.optimization.atScope
import com.eignex.klause.lp.optimization.withLinearAuthority
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.SourceLpWorkStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.search.SearchInvocationResources
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.WorkMeter
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.fitsLong
import com.eignex.util.numbers.magnitudeBitLength
import com.eignex.util.numbers.toLongExact
import kotlin.math.round
import kotlin.time.TimeSource

internal data class JointRepairAttempt(
    val result: ExactNeighborhoodResult,
    val reason: TerminationReason? = null,
    val feasiblePoint: Sample? = null,
    val bound: SourceEvidence? = null,
)

internal data class JointRepairComparison(
    val condition: Long,
    val phase: String,
    val rawValue: BigFraction? = null,
    val completedValue: BigFraction? = null,
    val cutoff: BigFraction? = null,
    val candidateValue: BigFraction? = null,
    val disposition: String,
)

internal class JointLinearRepair(
    private val model: Problem,
    private val source: SourceProofContext,
    private val objective: LinearObjective?,
    private val params: TheoryParams,
    guidancePolicy: SparseSourceLpPolicy = SparseSourceLpPolicy(),
    private val boundOrdered: Boolean = false,
    private val external: () -> Sample? = { null },
    private val publicationLifetime: ExecutionControl? = null,
    sourceIntegers: IntArray? = null,
    private val onIncumbentImport: (() -> Unit)? = null,
    private val onIncumbentUse: (() -> Unit)? = null,
    private val onAdvisoryUse: (() -> Unit)? = null,
    private val observeExternalIncumbents: Boolean = false,
    sharedCompletions: LinearIncumbentSharing? = null,
    private val incumbentCompletionCheck: ((Sample, BigFraction, ExecutionControl) -> Boolean)? = null,
    private val onComparison: ((JointRepairComparison) -> Unit)? = null,
    private val repairGuidance: LinearRepairGuidance? = null,
    private val incumbentProximity: Boolean = false,
    onObservation: ((JointRepairObservation) -> Unit)? = null,
) : ExactNeighborhoodCompletion, AutoCloseable {
    private class CheckedCenter(val sample: Sample, val value: BigFraction?)
    private class CheckedProposal(val condition: ExactNeighborhoodCondition, val sample: Sample)
    private class BinaryPins(val width: Int, val fixed: Long, val ones: Long)
    private class PrivateQueryReceipt(val proposal: Long, val query: Long, val pins: BinaryPins?) {
        var consultations = 0L
        var firstSearchWork = 0L
        var firstLpWork = SourceLpWorkStats()
    }
    private class ProximityPass(val value: BigFraction, val coordinates: List<BigFraction>, val observationId: Long,
        var next: Int = 0) {
        var pair: ExactNeighborhoodCondition? = null
    }

    private val sourceIntegers = sourceIntegers?.copyOf()
    private val stop = params.timeout.withAccounting(params.cancellation) or params.cancellation
    private val state = OpenTheorySolveState(params)
    private val funding = SourceLpFunding(params.sourceLpWorkLimit, params.sourceLpAllocationLimit)
    private val budget = SourceLpBudget(maxOperations = params.sourceLpOperations,
        onWork = state.smt::observeSourceLp, funding = funding)
    private val stages = linkedMapOf<String, Long>()
    private val centerProgress = if (params.sourceRefutationCenterStep)
        JointRepairCenter(model, budget, stop) { selection?.eligibleInteger(it) == true } else null
    private var queryOriginalCondition: ExactNeighborhoodCondition? = null
    private var queryCenterStep: JointRepairCenter.Step? = null
    private var readyCenterStep: JointRepairCenter.Step? = null
    private var proposalHasCheckedCenter = false
    private val observation = onObservation?.let { JointRepairObservationCapture(budget, stop, it) }
    private var proposalSequence = 0L
    private var observedPasses = 0L
    private var observationQuery = 0L
    private var observedPrivateQueries = 0
    private var privateQueryReceipt: PrivateQueryReceipt? = null
    private var privatePublication: String? = null
    private var observedOperator = 0
    private var observedSchedulingRound = 0L
    private var observedRoots: List<Int> = emptyList()
    private val completion = if (params.sourceIncumbentCompletion)
        LinearIncumbentCompletion(budget, incumbentCompletionCheck, sharedCompletions) { stage, count ->
            val key = "jointRepairExternalCompletion$stage"
            stages[key] = combine(key, stages[key] ?: 0L, count)
        } else null
    private val proximityChecking = completion ?: if (incumbentProximity)
        LinearIncumbentCompletion(budget, incumbentCompletionCheck, sharedCompletions) { stage, count ->
            val key = "jointRepairProximityCompletion$stage"
            stages[key] = combine(key, stages[key] ?: 0L, count)
        } else null
    private var comparisonCount = 0
    private var conditionSequence = 0L
    private var importedCenter: Sample? = null
    private var preparedCenter: CheckedCenter? = null
    private var checkedProposal: CheckedProposal? = null
    private var relaxationProposal: ExactNeighborhoodCondition? = null
    private var proximityProposal: ExactNeighborhoodCondition? = null
    private var advisorySeen: Sample? = null
    private var advisoryCenter: IncumbentProximity.AdvisoryCenter? = null
    private var proximityAdvisory: IncumbentProximity.AdvisoryCenter? = null
    private var centerPass: ProximityPass? = null
    private var proximityTurn = false
    private var proximityStarted = false
    private var proximityProposalPass: ProximityPass? = null
    private var queryProximityPass: ProximityPass? = null
    private var proximityCompletedCutoff: BigFraction? = null
    private var proximityDeclined = false
    private var proximityProposalClosed = false
    private var proximityProposalKind = JointRepairNeighborhood.AdvisorySelection.Kind.Pair
    private var queryProximityKind = JointRepairNeighborhood.AdvisorySelection.Kind.Pair
    private var queryProximityClosed = false
    private var queryProximity = false
    private var queryProximityCutoff: BigFraction? = null
    private var proximityDiscovery: Sample? = null
    fun isProximityDiscovery(sample: Sample): Boolean = sample === proximityDiscovery
    private var retainedProposal: ExactNeighborhoodCondition? = null
    private var publishedQueryIncumbent: Sample? = null
    private var bootstrapHint: LinearRepairGuidance.Hint? = null
    private var bootstrapPending = false
    private var bootstrapTried = false
    private var relaxationAttempted = false
    private val authority = LinearAuthority(budget, onLpShape = { shape ->
        if (shape.sparse) observe(shape.phase?.let { "jointRepairLpPhase_$it" } ?: "jointRepairLpNoPhase")
        if (!shape.admitted) observe("jointRepairLpDeclinedAttempts")
        stages["jointRepairLpMaxRows"] = maxOf(stages["jointRepairLpMaxRows"] ?: 0, shape.rows.toLong())
        stages["jointRepairLpMaxColumns"] = maxOf(stages["jointRepairLpMaxColumns"] ?: 0, shape.columns.toLong())
    }, retainLp = true, guidancePolicy = guidancePolicy,
        aggregateCuts = boundOrdered && params.sourceAggregateCuts)
    private var selection: JointRepairNeighborhood? = null
    private var rootPoint: Map<SourceCoordinate, Double>? = null
    private var rootStarted = false
    private var rootPreparationPending = false
    private var query: LinearAuthorityInvocation? = null
    private var anchor: Sample? = null
    private var pending: ExactNeighborhoodCondition? = null
    private val refutedConditions = ArrayDeque<ExactNeighborhoodCondition>()
    private var queryRefutationKey: ExactNeighborhoodCondition? = null
    private var queryRefutationScope: SourceScope? = null
    private var querySawExternal = false
    private var guidedProposal: ExactNeighborhoodCondition? = null
    private var queryFreedIntegers = 0
    private var checkedTrialWidth = 0
    private var checkedTrialCenter: Sample? = null
    private var enlargedTrialPending = false
    private var enlargedTrialAfterWitness = false
    private var enlargedTrialProposal: ExactNeighborhoodCondition? = null
    var incumbentTrialPending = false
        private set
    private var closed = false
    private var declined = false
    private var preparationWork = 0L
    val exhausted: Boolean get() = declined || funding.exhausted ||
        (!rootPreparationPending && budget.operations >= params.sourceLpOperations &&
            !(queryProximity && query?.hasPendingProximityCompletion == true)) ||
        (!rootPreparationPending && pending == null && (!state.work.hasAllowance() ||
            state.work.acceptedChecks >= params.maxLeaves))
    val work: Long get() = state.work.spent + funding.spent / LP_WORK_PER_NODE + preparationWork +
        (selection?.visitedEntries ?: 0)
    val stats: SolveStats get() = SolveStats(openTheory = state.work.snapshot(), smt = state.smt.snapshot().copy(
        completeSearchStages = (observations() + mapOf(
            "jointRepairSelectedIntegers" to (selection?.selectedIntegers ?: 0),
            "jointRepairConnectivityVisits" to (selection?.visitedEntries ?: 0),
            "jointRepairRefutationSeeds" to (selection?.refutationSeeds ?: 0),
            "jointRepairAdditionalRefutationSeeds" to (selection?.additionalRefutationSeeds ?: 0),
            "jointRepairPreparationEntries" to preparationWork,
            "jointRepairLpPreparations" to authority.preparations,
            "jointRepairLpReuses" to authority.reuses,
            "jointRepairFundedWork" to funding.spent,
            "jointRepairFundedAllocation" to funding.allocated,
            "jointRepairPrivateQueryObservationsDropped" to
                (observedPrivateQueries - MAX_PRIVATE_QUERY_OBSERVATIONS).coerceAtLeast(0).toLong(),
        )).let { counters -> if (observation == null) counters else counters + observation.observations() }))

    private fun observe(stage: String) {
        stages[stage] = (stages[stage] ?: 0) + 1
    }

    private fun observeExternal(stage: String) {
        if (observeExternalIncumbents) observe("jointRepairExternal$stage")
    }

    private fun comparisonValue(sample: Sample): BigFraction? {
        if (onComparison == null || comparisonCount >= 64 || stop()) return null
        return objectiveValue(sample)
    }

    private fun objectiveValue(sample: Sample): BigFraction? {
        if (stop()) return null
        if (sample.numBoolVars != model.numBoolVars || sample.numIntVars != model.numIntVars ||
            sample.numRealVars != model.numRealVars || sample.algebraicReals != null || sample.symbolicReals != null ||
            sample.exactReals?.let { it.size != model.numRealVars } == true ||
            (sample.exactReals == null &&
                (0 until model.numRealVars).any { !sample.approximateRealValue(it).isFinite() })
        ) return null
        budget.observe(boundCertification = 1L + model.numBoolVars + model.numIntVars + model.numRealVars)
        if (funding.exhausted || completion?.checking(stop)?.invoke() == true) return null
        return objective?.evaluateExact(sample)
    }

    private fun compare(phase: String, disposition: String, raw: BigFraction? = null,
        completed: BigFraction? = null, cutoff: BigFraction? = null, candidate: BigFraction? = null) {
        if (onComparison == null || comparisonCount >= 64 || stop() || funding.exhausted) return
        comparisonCount++
        onComparison.invoke(JointRepairComparison(if (pending == null) 0L else conditionSequence,
            phase, raw, completed, cutoff, candidate, disposition))
    }

    private fun externalIncumbent(cutoff: BigFraction? = pending?.cutoff, allowEqual: Boolean = false): Sample? {
        val offered = external() ?: return null
        val raw = if (completion != null && offered.exactReals != null && cutoff != null)
            objectiveValue(offered) else comparisonValue(offered)
        val declined = cutoff != null && raw != null && (if (allowEqual) raw > cutoff else raw >= cutoff)
        if (completion != null && offered.exactReals != null && declined) {
            compare("external", "not-improving", raw = raw, cutoff = cutoff)
            return null
        }
        val completed = if (completion != null)
            completion.complete(source, offered, stop) else offered
        compare("external", if (completed == null) "completion-unavailable" else "available",
            raw = raw, completed = completed?.let(::comparisonValue), cutoff = cutoff)
        return completed
    }

    private fun advisoryExternalCenter(cutoff: BigFraction? = null,
        allowEqual: Boolean = false): IncumbentProximity.AdvisoryCenter? {
        if (!incumbentProximity) return null
        val sample = external() ?: return null
        if (sample !== advisorySeen) {
            advisorySeen = sample
            advisoryCenter = IncumbentProximity.advisoryCenter(source, sample, budget, stop)
            if (advisoryCenter != null) observe("jointRepairProximityAdvisoryCenters")
        }
        return advisoryCenter?.takeIf {
            cutoff == null || if (allowEqual) it.value <= cutoff else it.value < cutoff
        }
    }

    private fun checkedExternalCenter(cutoff: BigFraction? = null, allowEqual: Boolean = false): CheckedCenter? {
        val sample = externalIncumbent(cutoff, allowEqual) ?: return null
        if (completion != null) {
            budget.observe(boundCertification = 1L + model.numBoolVars + model.numIntVars + model.numRealVars)
            if (completion.checking(stop)()) return null
        }
        val value = objective?.evaluateExact(sample)
        if (model.numRealVars != 0 && sample.exactReals?.size != model.numRealVars) {
            observeExternal("MissingExactReals")
            return null
        }
        if (cutoff != null && (value == null || if (allowEqual) value > cutoff else value >= cutoff)) {
            observeExternal("NotImproving")
            compare("center", "not-improving", completed = value, cutoff = cutoff)
            return null
        }
        val checking = completion?.checking(stop) ?: stop
        val checked = SourceEvidenceVerifier(source.withLinearAuthority()).verify(
            SourceEvidence(source.scope, SourceClaim.Witness(sample), SourceDerivation.SourceWitness), checking)
        if (checked != SourceEvidenceCheck.Checked) {
            observeExternal(if (checked is SourceEvidenceCheck.Rejected) "PointRejected" else "PointUnavailable")
            return null
        }
        compare("center", "checked", completed = value, cutoff = cutoff)
        selection?.clearRefutation()
        refutedConditions.clear()
        return CheckedCenter(sample, value)
    }

    private fun centerPass(sample: Sample, value: BigFraction): ProximityPass? {
        val work = 16L * (model.numIntVars + 1L)
        if (stop() || funding.availableWork <= work) return null
        budget.observe(preparation = work)
        val previous = centerPass
        if (previous != null && previous.value == value &&
            previous.coordinates.indices.all {
                previous.coordinates[it] == BigFraction.of(sample.exactIntValue(it), BIG_ONE)
            }) return previous
        val bytes = 256L + 128L * model.numIntVars
        if (funding.remainingAllocation <= bytes) return null
        funding.charge(0L, bytes)
        return ProximityPass(value,
            List(model.numIntVars) { BigFraction.of(sample.exactIntValue(it), BIG_ONE) },
            if (observation == null) 0L else ++observedPasses)
            .takeUnless { stop() || funding.exhausted }?.also { centerPass = it }
    }

    private fun finishProximity(cutoff: BigFraction?, finishCenter: Boolean = false) {
        val pass = queryProximityPass
        if (pass == null) proximityCompletedCutoff = cutoff
        else {
            if (finishCenter || queryProximityClosed ||
                queryProximityKind != JointRepairNeighborhood.AdvisorySelection.Kind.Pair) {
                pass.next = if (finishCenter) requireNotNull(selection).advisoryRoots else pass.next + 1
                pass.pair = null
            } else {
                pass.pair = queryOriginalCondition
                observe("jointRepairProximityExpansionRequests")
            }
            observe("jointRepairProximityNeighborhoodCompletions")
            if (proximityAdvisory != null) observe("jointRepairAdvisoryNeighborhoodCompletions")
        }
    }

    private fun refreshedCenter(center: CheckedCenter): CheckedCenter {
        val cutoff = center.value ?: return center
        if (completion == null || stop()) return center
        val sample = external() ?: return center
        if (sample.algebraicReals != null || sample.symbolicReals != null ||
            (model.numRealVars != 0 && sample.exactReals?.size != model.numRealVars)
        ) return center
        budget.observe(boundCertification = 1L + model.numBoolVars + model.numIntVars + model.numRealVars)
        val checking = completion.checking(stop)
        if (checking()) return center
        val evidence = SourceEvidence(source.scope, SourceClaim.Witness(sample), SourceDerivation.SourceWitness)
        if (SourceEvidenceVerifier(source.withLinearAuthority()).verify(evidence, checking) !=
            SourceEvidenceCheck.Checked
        ) return center
        val value = objective?.evaluateExact(sample) ?: return center
        if (value >= cutoff) return center
        if (incumbentCompletionCheck?.invoke(sample, value, checking) == false || checking()) return center
        observe("jointRepairCheckedCutoffRefreshes")
        compare("cutoff", "refreshed", completed = value, cutoff = cutoff)
        observe("jointRepairCheckedCenterRefreshes")
        compare("center", "refreshed", completed = value, cutoff = cutoff)
        return CheckedCenter(sample, value)
    }

    private fun queryObservations(authorityCuts: Set<String>): Map<String, Long> =
        query?.stats?.smt?.completeSearchStages.orEmpty().filterKeys { it !in authorityCuts }.mapKeys { (key, _) ->
            when (key) {
                "numericalRetainedNodes" -> "jointRepairPeakRetainedNodes"
                "numericalPendingLeaves" -> "jointRepairUnresolvedLeaves"
                "numericalFrontierPending" -> "jointRepairFrontierUnresolved"
                else -> if (queryProximity && key.startsWith("numericalPrimalGuidance"))
                    "jointRepairProximity" + key.removePrefix("numerical")
                else if (key.startsWith("numericalExternal"))
                    "jointRepairQueryExternal" + key.removePrefix("numericalExternal")
                else "jointRepair" + key.removePrefix("numerical")
            }
        }

    private fun combine(key: String, before: Long, after: Long): Long =
        if ("Max" in key || "Peak" in key) maxOf(before, after) else before + after

    private fun observations(): Map<String, Long> {
        val cuts = authority.cutObservations()
        val current = queryObservations(cuts.keys)
        return (stages.keys + current.keys).associateWith { combine(it, stages[it] ?: 0, current[it] ?: 0) } +
            cuts.mapKeys { (key, _) -> "jointRepair" + key.removePrefix("numerical") } +
            privateQueryObservation(current, "Live")
    }

    private fun privateQueryObservation(counters: Map<String, Long>, disposition: String): Map<String, Long> {
        val receipt = privateQueryReceipt ?: return emptyMap()
        val prefix = "jointRepairPrivateQuery${observedPrivateQueries}_"
        return buildMap {
            for ((key, value) in counters) {
                val stage = key.removePrefix("jointRepairProximity")
                if (stage.startsWith("Completion") || stage.startsWith("Offer") || stage.startsWith("Pause")) {
                    put(prefix + stage, value)
                }
            }
            put(prefix + "Proposal", receipt.proposal)
            put(prefix + "Query", receipt.query)
            put(prefix + "Consultations", receipt.consultations)
            receipt.pins?.let { pins ->
                put(prefix + "PinWidth", pins.width.toLong())
                put(prefix + "PinCount", pins.fixed.countOneBits().toLong())
                put(prefix + "FixedIntegerMask", pins.fixed)
                put(prefix + "OneIntegerMask", pins.ones)
            }
            if (receipt.consultations > 0L) {
                val work = receipt.firstLpWork
                put(prefix + "FirstSearchWork", receipt.firstSearchWork)
                put(prefix + "FirstLpPreparationWork", work.preparationWork)
                put(prefix + "FirstLpFloatWork", work.floatWork)
                put(prefix + "FirstLpContinuationWork", work.continuationWork)
                put(prefix + "FirstLpRefinementWork", work.refinementWork)
                put(prefix + "FirstLpBoundCertificationWork", work.boundCertificationWork)
                put(prefix + "FirstLpConflictCertificationWork", work.conflictCertificationWork)
            }
            put(prefix + "Pending", if (query?.hasPendingProximityCompletion == true) 1L else 0L)
            put(prefix + "RemainingWork", funding.remainingWork)
            put(prefix + "RemainingAllocation", funding.remainingAllocation)
            put(prefix + "LpOperations", budget.operations.toLong())
            put(prefix + disposition, 1L)
            privatePublication?.let { put(prefix + it, 1L) }
        }
    }

    private fun binaryPins(condition: SourceCondition): BinaryPins? {
        val width = model.numIntVars
        val count = condition.coordinateRestrictionCount
        if (width !in 1..Long.SIZE_BITS || condition.hasIntegerRows || count > 2L * width) return null
        val work = 1L + 4L * width
        val allocation = 64L + 32L * width
        if (funding.availableWork <= work || funding.remainingAllocation <= allocation) return null
        budget.observe(preparation = work)
        funding.charge(0L, allocation)
        var fixed = 0L
        var ones = 0L
        for (id in 0 until width) {
            val coordinate = SourceCoordinate(SourceCoordinate.Kind.Integer, id)
            val lower = condition.boundValue(coordinate, upper = false)
            val upper = condition.boundValue(coordinate, upper = true)
            if (lower == null && upper == null) continue
            if (lower == null || lower != upper || (!lower.isZero && lower != BigFraction.ONE)) return null
            val bit = 1L shl id
            fixed = fixed or bit
            if (!lower.isZero) ones = ones or bit
        }
        return if (2L * fixed.countOneBits() == count) BinaryPins(width, fixed, ones) else null
    }

    private fun lpPhaseWork(before: SourceLpWorkStats? = null): SourceLpWorkStats = SourceLpWorkStats(
        preparationWork = budget.measuredPreparationWork - (before?.preparationWork ?: 0L),
        floatWork = budget.measuredFloatWork - (before?.floatWork ?: 0L),
        continuationWork = budget.measuredContinuationWork - (before?.continuationWork ?: 0L),
        refinementWork = budget.measuredRefinementWork - (before?.refinementWork ?: 0L),
        boundCertificationWork = budget.measuredBoundCertificationWork - (before?.boundCertificationWork ?: 0L),
        conflictCertificationWork = budget.measuredConflictCertificationWork - (before?.conflictCertificationWork ?: 0L),
    )

    fun search(
        adaptive: Boolean, seed: Long = params.randomSeed, sourceInputPass: Boolean = sourceIntegers != null,
    ): ExactNeighborhood {
        var focusedCutoff: BigFraction? = null
        var focusedRemaining = 0
        return ExactNeighborhood(
            model.numBoolVars, List(model.numIntVars + model.numRealVars) { BigFraction.ZERO }, this, objective,
            coordinateValues = { sample ->
                List(model.numIntVars) { BigFraction.of(sample.exactIntValue(it), BIG_ONE) } +
                    sample.exactReals.orEmpty()
            }, adaptive = adaptive, seed = seed, nodeBudget = params.nodeBudget?.allowance,
            maximumMoves = params.maxFlips, maximumInstructions = params.maxInstructions ?: Long.MAX_VALUE,
            maximumImprovementMoves = if (adaptive) params.alnsIterations.toLong() else null,
            nativeWork = { work }, completionStats = { stats }, onClose = ::close,
            prepareBooleanCenter = { prepare(it, bootstrap = !adaptive) }, rootCoordinateValue = ::center,
            completionExhausted = { exhausted },
            centerPreparationPending = { rootPreparationPending },
            continuePausedCompletion = { condition ->
                val proposal = checkedProposal
                val checked = proposal != null && proposal.condition === queryOriginalCondition &&
                    (queryProximity || query?.hasIncumbent == true)
                val advisory = queryProximity && proximityAdvisory != null &&
                    proximityProposal === queryOriginalCondition
                val retained = incumbentProximity && query != null && queryOriginalCondition === condition
                val carries = checked || advisory || retained
                if (carries) observe("jointRepairGrantedQueryResumeRequests")
                carries
            },
            reviseRefutedCenter = centerProgress?.let { ::reviseRefutedCenter },
            onProposalOperator = observation?.let {
                { operator, round ->
                    observedOperator = operator
                    observedSchedulingRound = round
                }
            },
            proposal = ExactNeighborhoodProposal { centers, bools, count, round, radius, cutoff ->
                retainedProposal?.let { return@ExactNeighborhoodProposal it }
                var bootstrap = bootstrapPending
                val advisory = advisoryExternalCenter(cutoff, allowEqual = true)
                val checked = preparedCenter?.takeIf {
                    round == 0L &&
                        (cutoff == null || it.value?.let { value -> value < cutoff } == true)
                } ?: if (advisory == null) checkedExternalCenter(cutoff, allowEqual = bootstrapTried) else null
                checked?.sample?.let { sample ->
                    observe("jointRepairImportedCenters")
                    if (sample !== importedCenter) {
                        importedCenter = sample
                        onIncumbentImport?.invoke()
                    }
                }
                val current = if (round == 0L && checked != null && checked === preparedCenter)
                    refreshedCenter(checked) else checked
                val pooled = current?.sample
                if (bootstrap && current != null && dominatedBootstrap(current)) {
                    bootstrap = false
                    bootstrapPending = false
                    bootstrapTried = true
                    bootstrapHint = null
                    observe("jointRepairDominatedBootstrapsSkipped")
                }
                if (centerProgress != null) proposalHasCheckedCenter = pooled != null
                pooled?.let { sample ->
                    if (sample !== importedCenter) {
                        importedCenter = sample
                        onIncumbentImport?.invoke()
                    }
                }
                val from = pooled?.takeUnless { bootstrap }?.let { sample ->
                    List(model.numIntVars) { BigFraction.of(sample.exactIntValue(it), BIG_ONE) } +
                        sample.exactReals.orEmpty()
                } ?: centers
                val limit = current?.value ?: cutoff
                if (sourceInputPass && !adaptive && limit != null && limit != focusedCutoff) {
                    focusedCutoff = limit
                    focusedRemaining = sourceIntegers?.size ?: model.numIntVars
                    observe("jointRepairSourceInputPasses")
                }
                val checkedStart = pooled != null && completion != null && !bootstrap &&
                    (round == 0L || (bootstrapTried && !relaxationAttempted))
                if (round == 0L && completion != null) incumbentTrialPending = pooled != null
                val width = if (enlargedTrialAfterWitness) checkedTrialWidth + 1 else if (checkedStart) {
                    observe("jointRepairCheckedCenterStarts")
                    minOf(count, 2)
                } else if (sourceInputPass && !adaptive && round > 0L && focusedRemaining > 0) {
                    focusedRemaining--
                    observe("jointRepairSourceInputProposals")
                    minOf(count, 2)
                } else count
                if (pooled != null) {
                    selection?.clearRefutation()
                    onIncumbentUse?.invoke()
                }
                // A checked center needs no all-pinned rounded-root bootstrap.
                val proposalRound = if (bootstrap) 0L else if (pooled != null && completion != null)
                    round.coerceAtMost(Long.MAX_VALUE - 1) + 1 else round
                val size = if (enlargedTrialPending && width > checkedTrialWidth)
                    minOf(width, checkedTrialWidth + 1) else width
                val hint = if (pooled != null && completion != null) repairGuidance?.find(source) else null
                val privateLimit = advisory?.value ?: limit
                val privateChecked = pooled?.takeIf {
                    incumbentProximity && completion == null && !adaptive && !bootstrap && privateLimit != null &&
                        model.numBoolVars == 0 && IncumbentProximity.accepts(model.intBounds, model.numIntVars)
                }?.let { proximityChecking?.complete(source, it, stop) }
                val pass = if (advisory != null) centerPass(advisory.sample, advisory.value)
                else privateChecked?.let { centerPass(it, requireNotNull(privateLimit)) }
                val rotating = advisory != null || privateChecked != null
                if (advisory != null && !proximityTurn) observe("jointRepairAdvisoryOrdinaryTurns")
                val beforeSelection = requireNotNull(selection).visitedEntries
                var supportClosed = false
                var supportKind = JointRepairNeighborhood.AdvisorySelection.Kind.Pair
                var observedSelection: JointRepairObservedSelection? = null
                val proximity = if (incumbentProximity && !proximityDeclined && !adaptive &&
                    (!rotating || proximityTurn || (advisory == null && !proximityStarted)) &&
                    ((pooled != null && completion != null) || pass != null) &&
                    !bootstrap && privateLimit != null &&
                    (if (rotating) pass != null && pass.next < requireNotNull(selection).advisoryRoots
                    else privateLimit != proximityCompletedCutoff) && model.numBoolVars == 0 &&
                    IncumbentProximity.accepts(model.intBounds, model.numIntVars))
                    if (pass != null) {
                        val selector = requireNotNull(selection)
                        val selected = selector.advisoryNeighborhood(pass.coordinates, pass.next, privateLimit,
                            budget, stop, expand = pass.pair != null, previous = pass.pair)
                        observedSelection = observation?.advisorySelection(pass.observationId, pass.next,
                            selector.advisoryRoot(pass.next), pass.pair != null, pass.coordinates,
                            advisory?.sample ?: privateChecked, model.numRealVars, privateLimit, source.scope.revision,
                            selected, selector.visitedEntries - beforeSelection,
                            if (advisory != null) "advisory-source" else "checked-private")
                        when (selected) {
                            is JointRepairNeighborhood.AdvisorySelection.Ready -> {
                                val pair = pass.pair
                                if (pair != null && selected.condition.lower.keys.containsAll(pair.lower.keys)) {
                                    pass.next++
                                    pass.pair = null
                                    observe("jointRepairProximityRepeatedSupportSkipped")
                                    null
                                } else {
                                    supportClosed = selected.closed
                                    supportKind = selected.kind
                                    if (selected.kind ==
                                        JointRepairNeighborhood.AdvisorySelection.Kind.ContinuousSupport)
                                        observe("jointRepairProximityContinuousSupportSelections")
                                    selected.condition
                                }
                            }
                            is JointRepairNeighborhood.AdvisorySelection.Declined -> {
                                pass.next++
                                pass.pair = null
                                observe("jointRepairProximityNeighborhood${selected.limit}Declines")
                                null
                            }
                            JointRepairNeighborhood.AdvisorySelection.Unavailable -> null
                        }
                    } else ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap(), privateLimit) else null
                val guided = requireNotNull(selection).guide(hint, from, budget, stop)
                val binary = if (proximity == null && !adaptive && pooled != null && completion != null && !bootstrap)
                    requireNotNull(selection).binaryNeighborhood(hint, from, model.intBounds,
                        pooled.bools, limit, budget, stop) else null
                val relaxation = if (proximity == null && checkedStart && binary == null)
                    requireNotNull(selection).relaxationNeighborhood(
                        hint, from, requireNotNull(pooled).bools, limit, budget, stop) else null
                if (checkedStart && hint != null) relaxationAttempted = true
                if (relaxation != null) observe("jointRepairRelaxationNeighborhoods")
                if (binary != null) observe("jointRepairBinarySupportNeighborhoods")
                (proximity ?: binary ?: relaxation ?: requireNotNull(selection).proposeWithinSelectionBudget(
                    from, pooled?.bools ?: bools, size, proposalRound, limit,
                    requireNotNull(selection).visitedEntries - beforeSelection))
                    .also { condition ->
                        if (bootstrap) {
                            bootstrapPending = false
                            bootstrapTried = true
                            bootstrapHint = null
                            observe("jointRepairPaidRootBootstraps")
                        }
                        relaxationProposal = condition.takeIf { relaxation != null || binary != null }
                        proximityProposal = condition.takeIf { proximity != null }
                        proximityProposalClosed = proximity != null && supportClosed
                        proximityProposalKind = supportKind
                        proximityAdvisory = advisory.takeIf { proximity != null }
                        proximityProposalPass = pass.takeIf { proximity != null }
                        if (proximity != null && rotating) proximityStarted = true
                        if (proximityAdvisory != null) {
                            observe("jointRepairProximityAdvisoryConsumed")
                            onAdvisoryUse?.invoke()
                        }
                        checkedProposal = (privateChecked ?: pooled)?.takeIf {
                            (completion != null || privateChecked != null) && !bootstrap
                        }?.let { CheckedProposal(condition, it) }
                        if (incumbentProximity || observation != null) proposalSequence++
                        if (observation != null) {
                            observationQuery = 0L
                            observation.proposal(proposalSequence, observedOperator, observedSchedulingRound,
                                proposalRound, count, size, when {
                                    proximity != null && pass != null ->
                                        if (advisory != null) "advisory-source" else "checked-private"
                                    bootstrap -> "rounded-paid-root"
                                    pooled == null -> "stored"
                                    else -> "checked-pooled"
                                },
                                if (proximity != null && pass != null) pass.coordinates else from,
                                model.numIntVars, sourceIntegers, observedRoots, condition, reals = model.numRealVars,
                                selection = observedSelection.takeIf { proximity != null })
                        }
                        guidedProposal = condition.takeIf { guided }
                        if (checkedStart) {
                            checkedTrialCenter = pooled
                            checkedTrialWidth = (sourceIntegers?.size ?: model.numIntVars) - condition.lower.size
                            enlargedTrialPending = adaptive &&
                                (sourceIntegers?.size ?: model.numIntVars) > checkedTrialWidth
                        }
                        if (enlargedTrialPending && width > checkedTrialWidth) enlargedTrialProposal = condition
                    }
            },
        )
    }

    private fun requestIncumbentPreparation() {
        if (completion == null || incumbentTrialPending) return
        budget.observe(preparation = 1L)
        val seed = external() ?: return
        if (seed.numBoolVars == model.numBoolVars && seed.numIntVars == model.numIntVars &&
            seed.numRealVars == model.numRealVars && seed.algebraicReals == null && seed.symbolicReals == null
        ) {
            incumbentTrialPending = true
            observe("jointRepairIncumbentPreparationStarts")
        }
    }

    private fun prepare(cancellation: ExecutionControl, bootstrap: Boolean): Boolean {
        check(!closed)
        if (stop() || exhausted || cancellation()) return false
        if (selection == null) {
            val token = stop or cancellation
            val construction = object : ExecutionControl() {
                override fun isCancelled(): Boolean = token()
                override fun deadline() = token.deadline()
                override val accounting: WorkMeter = WorkMeter { units ->
                    preparationWork += units
                    token.charge(units)
                }
            }
            selection = JointRepairNeighborhood.prepare(source, model.numIntVars, model.numRealVars, construction,
                sourceIntegers, privateObjective = incumbentProximity)
            selection?.onRefutationRoots = observation?.let { { roots -> observedRoots = roots } }
            if (selection == null) {
                declined = !stop() && !cancellation()
                return false
            }
        }
        if (rootPoint == null) {
            funding.grant(20_000_000L)
            requestIncumbentPreparation()
            if (!rootStarted && completion != null) {
                preparedCenter = preparedCenter ?: checkedExternalCenter()
                if (stop() || exhausted || cancellation()) return false
                if (preparedCenter != null) {
                    bootstrapHint = repairGuidance?.find(source)?.takeIf { bootstrap }
                    bootstrapPending = bootstrapHint != null
                    rootPoint = emptyMap()
                    observe("jointRepairCheckedCenterPreparations")
                    return true
                }
            }
            if (!rootStarted) {
                if (!state.work.theoryCheck()) return false
                rootStarted = true
                observe("jointRepairRootQueries")
            }
            val result = if (boundOrdered && params.sourceCuts && params.sourcePointCuts)
                authority.advanceWithPointCuts(source, stop, retainBasePoint = true)
            else authority.advance(source, stop, cuts = boundOrdered && params.sourceCuts)
            for ((stage, count) in result.primalStages) {
                val key = "jointRepairRoot$stage"
                stages[key] = combine(key, stages[key] ?: 0L, count)
            }
            observe("jointRepairRootConsultations")
            rootPreparationPending = result.paused
            if (rootPreparationPending) {
                observe("jointRepairRootPauses")
                return false
            }
            if (stop()) return false
            val guidance = result.advisoryPoint ?: result.relaxedPoint
            rootPoint = guidance
            requireNotNull(selection).guide(guidance)
            stages["jointRepairGuidedIntegers"] = guidance.keys.count {
                it.kind == SourceCoordinate.Kind.Integer
            }.toLong()
            if (guidance.isEmpty()) observe("jointRepairRootGuidanceUnavailable")
        }
        return true
    }

    private fun center(index: Int): BigFraction? {
        if (index >= model.numIntVars) return null
        val prepared = preparedCenter?.let { BigFraction.of(it.sample.exactIntValue(index), BIG_ONE) }
        if (!bootstrapPending && prepared != null) return prepared
        val coordinate = SourceCoordinate(SourceCoordinate.Kind.Integer, index)
        val hint = bootstrapHint?.integer(index) ?: rootPoint?.get(coordinate)
        var value = hint?.takeIf { it.isFinite() }?.let { BigFraction.ofDouble(round(it)) }
            ?: prepared ?: BigFraction.ZERO
        val bounds = model.intBounds
        if (bounds.hasLower(index)) {
            val lower = BigFraction.ofLong(bounds.lower(index))
            if (value < lower) value = lower
        }
        if (bounds.hasUpper(index)) {
            val upper = BigFraction.ofLong(bounds.upper(index))
            if (value > upper) value = upper
        }
        return value
    }

    private fun dominatedBootstrap(checked: CheckedCenter): Boolean {
        val objective = objective ?: return false
        val cutoff = checked.value ?: return false
        val entries = model.numBoolVars.toLong() + model.numIntVars + model.numRealVars
        val work = 32L * (entries + 1L)
        val bytes = 256L * (entries + 1L)
        if (sourceIntegers != null || stop() || funding.availableWork <= work ||
            funding.remainingAllocation <= bytes) return false
        budget.observe(preparation = work)
        funding.charge(0L, bytes)
        if (objective.realCoefficientValues.any { it != 0.0 }) return false
        val integers = LongArray(model.numIntVars)
        for (id in integers.indices) {
            if (stop()) return false
            val value = center(id) ?: return false
            if (value.den != BIG_ONE || !value.num.fitsLong()) return false
            integers[id] = value.num.toLongExact()
        }
        val rounded = Sample(checked.sample.bools, integers, DoubleArray(model.numRealVars))
        // With every discrete coordinate fixed, a zero continuous objective cannot repair this cutoff.
        return objective.evaluateExact(rounded) >= cutoff && !stop() && !funding.exhausted
    }

    override fun check(condition: ExactNeighborhoodCondition, cancellation: ExecutionControl): ExactNeighborhoodResult =
        attempt(condition, cancellation).result

    fun attempt(condition: ExactNeighborhoodCondition, cancellation: ExecutionControl): JointRepairAttempt {
        readyCenterStep = null
        val rotating = condition === proximityProposal && proximityProposalPass != null
        val result = advanceAttempt(condition, cancellation)
        if (result.result != ExactNeighborhoodResult.Paused) {
            if (incumbentProximity) proximityTurn = !rotating
            observation?.event(proposalSequence, observationQuery, "result", when (result.result) {
                is ExactNeighborhoodResult.Witness -> "checked-source-witness"
                ExactNeighborhoodResult.Refuted -> "regional-refutation"
                else -> "undecided-${result.reason}"
            })
            if (queryFreedIntegers > 2 && (result.result == ExactNeighborhoodResult.Refuted ||
                    result.result is ExactNeighborhoodResult.Witness)) {
                observe("jointRepairLargerNeighborhoodCompletions")
                if (result.result is ExactNeighborhoodResult.Witness) {
                    observe("jointRepairLargerNeighborhoodCheckedIncumbents")
                }
            }
            val enlarged = condition === enlargedTrialProposal
            if (enlarged && (result.result == ExactNeighborhoodResult.Refuted ||
                    result.result is ExactNeighborhoodResult.Witness)) observe("jointRepairEnlargedTrialCompletions")
            val continuing = enlargedTrialPending && !enlarged && !exhausted && !stop() &&
                when (val outcome = result.result) {
                    ExactNeighborhoodResult.Refuted -> true
                    is ExactNeighborhoodResult.Witness -> sameDiscreteCenter(outcome.sample).also {
                        enlargedTrialAfterWitness = it
                    }
                    else -> false
                }
            if (continuing) observe("jointRepairEnlargedTrialContinuations")
            else {
                enlargedTrialPending = false
                enlargedTrialAfterWitness = false
                enlargedTrialProposal = null
                checkedTrialCenter = null
            }
            incumbentTrialPending = continuing
        }
        return result
    }

    private fun sameDiscreteCenter(sample: Sample): Boolean {
        val center = checkedTrialCenter ?: return false
        budget.observe(boundCertification = 1L + model.numBoolVars + model.numIntVars)
        return !stop() && !funding.exhausted && sample.bools.contentEquals(center.bools) &&
            (0 until model.numIntVars).all { sample.exactIntValue(it) == center.exactIntValue(it) }
    }

    private fun advanceAttempt(condition: ExactNeighborhoodCondition,
        cancellation: ExecutionControl): JointRepairAttempt {
        check(!closed)
        if (stop() || exhausted) return stoppedAttempt()
        if (cancellation()) return JointRepairAttempt(ExactNeighborhoodResult.Paused)
        if (queryProximity && pending == condition &&
            (advisoryExternalCenter(queryProximityCutoff) != null ||
                checkedExternalCenter(queryProximityCutoff) != null)) {
            finishProximity(queryProximityCutoff, finishCenter = true)
            retire("Superseded")
            observe("jointRepairProximitySuperseded")
            return JointRepairAttempt(ExactNeighborhoodResult.Undecided)
        }
        if (pending != condition) {
            retire()
            val key = refutationKey(condition, cancellation)
            val reused = key != null && refutedConditions.any { it == key }
            if (stop() || exhausted) return stoppedAttempt()
            if (cancellation()) return JointRepairAttempt(ExactNeighborhoodResult.Paused)
            if (reused) {
                queryFreedIntegers = 0
                selection?.clearRefutation()
                observe("jointRepairRepeatedQueriesSkipped")
                observation?.event(proposalSequence, 0L, "reuse", "checked-regional-refutation")
                compare("reuse", "checked-refutation")
                return JointRepairAttempt(ExactNeighborhoodResult.Refuted, TerminationReason.SearchExhausted)
            }
            val captured = key ?: condition
            val bounds = queryCondition(captured, cancellation) ?: return if (cancellation() && !stop())
                JointRepairAttempt(ExactNeighborhoodResult.Paused) else stoppedAttempt()
            val pinnedIntegers = captured.lower.count { (id, value) ->
                id < model.numIntVars && captured.upper[id] == value
            }
            pending = captured
            if (onComparison != null || observation != null || incumbentProximity) conditionSequence++
            observationQuery = conditionSequence
            observation?.event(proposalSequence, observationQuery, "query", "opened")
            compare("query", "opened", cutoff = condition.cutoff)
            val receiving = source.atScope(source.scope.copy(condition = bounds))
            var seed = checkedProposal?.takeIf { it.condition === condition }?.sample
                ?: proximityAdvisory?.takeIf { condition === proximityProposal }?.sample
            var privateCutoff = condition.cutoff
            if (condition === proximityProposal && proximityAdvisory == null && completion != null) {
                checkedExternalCenter(condition.cutoff)?.let { newer ->
                    seed = newer.sample
                    privateCutoff = newer.value
                    observe("jointRepairProximityRecentered")
                }
            }
            val querySource = if (condition === proximityProposal && seed != null) {
                IncumbentProximity.prepare(receiving, requireNotNull(seed), requireNotNull(privateCutoff), budget,
                    stop or cancellation, relativeImprovement = false)?.also {
                    queryProximity = true
                    observedPrivateQueries++
                    if (observedPrivateQueries <= MAX_PRIVATE_QUERY_OBSERVATIONS) {
                        privateQueryReceipt = PrivateQueryReceipt(proposalSequence, observationQuery,
                            binaryPins(it.scope.condition))
                    }
                    queryProximityCutoff = privateCutoff
                    queryProximityPass = proximityProposalPass
                    queryProximityClosed = proximityProposalClosed
                    queryProximityKind = proximityProposalKind
                    if (queryProximityPass?.pair != null) observe("jointRepairProximityExpansionQueries")
                    observe("jointRepairProximityCenters")
                } ?: run {
                    proximityDeclined = true
                    observe("jointRepairProximityDeclines")
                    retire()
                    return JointRepairAttempt(ExactNeighborhoodResult.Undecided)
                }
            } else receiving
            queryRefutationKey = key
            queryRefutationScope = querySource.scope
            querySawExternal = false
            queryOriginalCondition = condition
            val refutationGuidance = completion != null && anchor == null && preparedCenter == null &&
                condition.cutoff == null
            query = LinearAuthorityInvocation(querySource,
                params.copy(sourceIncumbentCompletion = false, sourceRefutationCenterStep = false),
                external = {
                    (if (queryProximity) null else if (seed != null && query?.hasIncumbent != true) seed
                    else externalIncumbent() ?: seed ?: anchor).also { sample ->
                        if (sample != null) {
                            querySawExternal = true
                            refutedConditions.clear()
                        }
                    }
                }, state = state, borrowedAuthority = authority, borrowedBudget = budget,
                boundOrdered = boundOrdered, guidance = true, funding = funding,
                cuts = boundOrdered && params.sourceCuts && !queryProximity,
                onNumericalFeedback = if (refutationGuidance) { feedback ->
                    guideRefutation(captured, querySource, feedback)
                } else null,
                feedbackRequest = if (refutationGuidance) LinearAuthorityFeedbackRequest(refutation = true) else null,
                useSourceGap = false, guideIncumbent = seed != null && !queryProximity,
                proximityCenter = seed.takeIf { queryProximity }, proximityRounding = queryProximity,
                boundProximityFloatToOffer = queryProximity,
                observeExternalIncumbents = observeExternalIncumbents)
            val freed = (sourceIntegers?.size ?: model.numIntVars) - pinnedIntegers
            queryFreedIntegers = freed
            if (condition === guidedProposal) observe("jointRepairSharedGuidanceQueries")
            stages["jointRepairMaxFreedIntegers"] = maxOf(stages["jointRepairMaxFreedIntegers"] ?: 0L, freed.toLong())
            if (condition === enlargedTrialProposal) observe("jointRepairEnlargedTrialStarts")
            observe("jointRepairCandidates")
        }
        val active = requireNotNull(query)
        val millis = cancellation.deadline()?.let { (it - TimeSource.Monotonic.markNow()).inWholeMilliseconds }
            ?.coerceIn(1, QUERY_SLICE_MILLIS) ?: QUERY_SLICE_MILLIS
        val before = active.work
        val delivered = if (queryProximity) ExecutionControl { active.hasIncumbent } else ExecutionControl.Never
        val receipt = privateQueryReceipt
        val lpBefore = receipt?.takeIf { it.consultations == 0L }?.let { lpPhaseWork() }
        val result = active.runSlice(delivered, millis, QUERY_SLICE_WORK) { _, _ -> }
        if (receipt != null) {
            receipt.consultations++
            if (lpBefore != null) {
                receipt.firstSearchWork = active.work - before
                receipt.firstLpWork = lpPhaseWork(lpBefore)
            }
        }
        if (queryProximity) {
            observe("jointRepairProximityConsultations")
            stages["jointRepairProximityWork"] = (stages["jointRepairProximityWork"] ?: 0L) + active.work - before
        }
        val snapshot = result ?: active.snapshot(TerminationReason.BudgetExhausted)
        val privateProximity = queryProximity
        val bound = snapshot.bound.takeUnless { privateProximity }
        val imported = completion != null && active.incumbentIsImported
        if (imported && snapshot.incumbent != null) observe("jointRepairImportedPointWithheld")
        val discovered = snapshot.incumbent?.takeUnless { imported || it === publishedQueryIncumbent }
        val sample = discovered?.takeIf {
            condition.cutoff == null || requireNotNull(objective).evaluateExact(it) < condition.cutoff
        }
        if (privateProximity && discovered != null && sample == null) privatePublication = "PublicationCutoffMiss"
        if (sample != null) {
            val evidence = SourceEvidence(source.scope, SourceClaim.Witness(sample), SourceDerivation.SourceWitness)
            // Full-source point verification has no retained cursor across slice boundaries.
            val verification = stop or (publicationLifetime ?: cancellation)
            val mark = TimeSource.Monotonic.markNow()
            observe("jointRepairPublicationChecks")
            val checked = SourceEvidenceVerifier(source.withLinearAuthority()).verify(evidence, verification)
            var originalChecked = true
            var originalUnavailable = false
            if (checked == SourceEvidenceCheck.Checked && incumbentCompletionCheck != null) {
                val checking = publicationChecking(verification)
                observe("jointRepairOriginalPublicationChecks")
                val value = objective?.evaluateExact(sample)
                originalChecked = value != null && incumbentCompletionCheck.invoke(sample, value, checking)
                if (checking()) {
                    originalChecked = false
                    originalUnavailable = true
                    observe("jointRepairOriginalPublicationUnavailable")
                } else observe(if (originalChecked) "jointRepairOriginalPublicationChecked"
                else "jointRepairOriginalPublicationRejected")
            }
            if (privateProximity) privatePublication = when {
                checked is SourceEvidenceCheck.Rejected -> "PublicationWorkingRejected"
                checked is SourceEvidenceCheck.Unavailable -> "PublicationWorkingUnavailable"
                originalUnavailable -> "PublicationOriginalUnavailable"
                !originalChecked -> "PublicationOriginalRejected"
                incumbentCompletionCheck == null -> "PublicationWorkingCheckedOnly"
                else -> "PublicationChecked"
            }
            observation?.event(proposalSequence, observationQuery, "publication", when {
                checked != SourceEvidenceCheck.Checked -> "source-unchecked"
                !originalChecked -> "original-unchecked"
                else -> "checked"
            })
            stages["jointRepairPublicationNs"] = (stages["jointRepairPublicationNs"] ?: 0L) +
                mark.elapsedNow().inWholeNanoseconds
            if (publicationLifetime == null && cancellation() && !stop()) {
                observe("jointRepairPublicationPauses")
                return JointRepairAttempt(ExactNeighborhoodResult.Paused, feasiblePoint = sample,
                    bound = bound)
            }
            val accepted = checked == SourceEvidenceCheck.Checked && originalChecked
            if (privateProximity) finishProximity(if (accepted)
                requireNotNull(objective).evaluateExact(sample) else queryProximityCutoff, finishCenter = accepted)
            if (accepted) {
                compare("discovery", "checked", cutoff = condition.cutoff, candidate = comparisonValue(sample))
            }
            val liveRelaxation = result == null && condition === relaxationProposal && !privateProximity
            if (accepted && liveRelaxation && !stop() && !exhausted) {
                retainedProposal = condition
                publishedQueryIncumbent = sample
                observe("jointRepairRetainedQueryIncumbents")
            } else retire(if (accepted) "SourceChecked" else "SourcePublicationDeclined")
            if (accepted) {
                proximityDiscovery = sample.takeIf { privateProximity }
                anchor = sample
                selection?.clearRefutation()
                refutedConditions.clear()
                observe("jointRepairCheckedIncumbents")
                if (privateProximity) observe("jointRepairProximityImprovements")
                return JointRepairAttempt(ExactNeighborhoodResult.Witness(sample),
                    feasiblePoint = sample, bound = bound)
            }
            return JointRepairAttempt(ExactNeighborhoodResult.Undecided, when {
                params.timeout() -> TerminationReason.Timeout
                stop() -> TerminationReason.Cancelled
                else -> TerminationReason.Unsupported
            },
                bound = bound)
        }
        if (!privateProximity && condition.cutoff != null) {
            val bound = ((snapshot.bound?.claim as? SourceClaim.Bound)?.value as? SourceValue.Rational)?.value
            val minimized = if (source.scope.objective?.sense == ObjectiveSense.MAXIMIZE) bound?.negated() else bound
            if (minimized != null && minimized >= condition.cutoff) {
                observe("jointRepairCutoffClosures")
                retire()
                return JointRepairAttempt(ExactNeighborhoodResult.Refuted, TerminationReason.SearchExhausted,
                    snapshot.incumbent, snapshot.bound)
            }
        }
        if (result == null) {
            observe("jointRepairPauses")
            return JointRepairAttempt(ExactNeighborhoodResult.Paused, feasiblePoint = snapshot.incumbent,
                bound = bound)
        }
        if (privateProximity) {
            finishProximity(queryProximityCutoff)
            observe("jointRepairProximityCompleted")
            val terminal = result.terminal
            val refuted = terminal?.claim == SourceClaim.Refutation && terminal.scope == queryRefutationScope
            retire(if (refuted) "CheckedRefutation" else result.reason.name)
            return JointRepairAttempt(ExactNeighborhoodResult.Undecided, result.reason)
        }
        val refuted = result.terminal?.claim == SourceClaim.Refutation
        val completedKey = queryRefutationKey.takeIf {
            refuted && !querySawExternal &&
                result.terminal?.scope == queryRefutationScope
        }
        val completedStep = queryCenterStep.takeIf {
            refuted && !querySawExternal &&
                result.terminal?.scope == queryRefutationScope && anchor == null && preparedCenter == null
        }
        retire()
        if (completedKey != null) rememberRefutation(completedKey, cancellation)
        readyCenterStep = completedStep
        return JointRepairAttempt(
            if (refuted) ExactNeighborhoodResult.Refuted
            else ExactNeighborhoodResult.Undecided,
            if (result.terminal?.claim is SourceClaim.Ray) TerminationReason.Unsupported else result.reason,
            result.incumbent, result.bound)
    }

    private fun stoppedAttempt() = JointRepairAttempt(ExactNeighborhoodResult.Undecided, when {
        params.timeout() -> TerminationReason.Timeout
        stop() -> TerminationReason.Cancelled
        else -> TerminationReason.BudgetExhausted
    })

    private fun queryCondition(condition: ExactNeighborhoodCondition,
        cancellation: ExecutionControl): SourceCondition? {
        val original = source.scope.condition
        val entries = 1L + original.coordinateRestrictionCount + condition.bools.size +
            condition.lower.size + condition.upper.size
        val work = 16L * entries
        val bytes = 256L * entries
        if (stop() || cancellation()) return null
        if (funding.remainingWork <= work || funding.remainingAllocation <= bytes) {
            declined = true
            return null
        }
        budget.observe(preparation = work)
        funding.charge(0L, bytes)
        val bools = original.bools.toMutableMap()
        val lower = original.lower.toMutableMap()
        val upper = original.upper.toMutableMap()
        for ((id, value) in condition.bools) {
            if (stop() || cancellation()) return null
            bools[id] = value
        }
        for ((isUpper, entries) in listOf(false to condition.lower, true to condition.upper)) {
            val target = if (isUpper) upper else lower
            for ((id, value) in entries) {
                if (stop() || cancellation()) return null
                val coordinate = if (id < model.numIntVars) SourceCoordinate(SourceCoordinate.Kind.Integer, id)
                else SourceCoordinate(SourceCoordinate.Kind.Real, id - model.numIntVars)
                val before = target[coordinate]
                target[coordinate] = if (before == null || (if (isUpper) value < before else value > before))
                    value else before
            }
        }
        val result = original.copy(bools, lower, upper)
        return result.takeUnless { stop() || cancellation() || funding.exhausted }
    }

    private fun refutationKey(condition: ExactNeighborhoodCondition,
        cancellation: ExecutionControl): ExactNeighborhoodCondition? {
        if (anchor != null || preparedCenter != null || condition.cutoff != null) return null
        val entries = condition.bools.size.toLong() + condition.lower.size + condition.upper.size
        if (entries > MAX_REFUTATION_ENTRIES || stop() || cancellation()) return null
        // Metadata work spends the owner ledger without renewing a numerical query grant.
        val scan = 1L + entries
        if (funding.remainingWork <= scan || funding.remainingAllocation <= 64L) return null
        budget.observe(preparation = scan)
        funding.charge(0L, 64L)
        var work = 1L + 16L * entries
        var bytes = 256L + 64L * entries
        for (values in listOf(condition.lower, condition.upper)) {
            for (value in values.values) {
                if (stop() || cancellation() || funding.exhausted) return null
                val bits = value.num.magnitudeBitLength().toLong() + value.den.magnitudeBitLength()
                if (bits > MAX_REFUTATION_SCALAR_BITS) return null
                work += MAX_REFUTED_CONDITIONS * (1L + bits * bits / 64L)
                bytes += 64L + (bits + 7L) / 8L
                if (work >= funding.remainingWork || bytes >= funding.remainingAllocation ||
                    bytes > MAX_REFUTATION_BYTES) return null
            }
        }
        if (work >= funding.remainingWork || bytes >= funding.remainingAllocation ||
            bytes > MAX_REFUTATION_BYTES) return null
        budget.observe(preparation = work)
        funding.charge(0L, bytes)
        if (stop() || cancellation() || funding.exhausted) return null
        return condition.copy(bools = condition.bools.toMap(), lower = condition.lower.toMap(),
            upper = condition.upper.toMap())
    }

    private fun rememberRefutation(key: ExactNeighborhoodCondition, cancellation: ExecutionControl) {
        if (stop() || cancellation() || anchor != null || preparedCenter != null ||
            funding.remainingWork <= 1L || funding.remainingAllocation <= 64L) return
        budget.observe(preparation = 1L)
        funding.charge(0L, 64L)
        if (stop() || cancellation() || funding.exhausted) return
        if (refutedConditions.size == MAX_REFUTED_CONDITIONS) refutedConditions.removeFirst()
        refutedConditions.addLast(key)
    }

    private fun guideRefutation(condition: ExactNeighborhoodCondition, querySource: SourceProofContext,
        feedback: SourceNumericalFeedbackResult) {
        observe("jointRepairRefutationFeedbackObserved")
        if (feedback !is SourceNumericalFeedbackResult.Available) {
            observe("jointRepairRefutationFeedbackUnavailable")
            observation?.event(proposalSequence, observationQuery, "feedback", "unavailable")
            return
        }
        if (!feedback.compatible(querySource, null)) {
            observe("jointRepairRefutationScopeIncompatible")
            observation?.event(proposalSequence, observationQuery, "feedback", "incompatible-scope")
            return
        }
        if (feedback.query.refutation == null) {
            observe("jointRepairRefutationClaimMissing")
            observation?.event(proposalSequence, observationQuery, "feedback", "missing-refutation")
            return
        }
        when (val support = sourceNumericalRefutationSupport(feedback, funding, stop)) {
            is SourceNumericalSupportResult.Available -> {
                observe("jointRepairRefutationSupportAvailable")
                val selector = selection
                val disposition = when {
                    selector == null -> "jointRepairRefutationSelectorMissing"
                    selector.guideRefutation(support.assumptions, condition, budget, stop) ->
                        "jointRepairRefutationGuidance"
                    else -> "jointRepairRefutationSeedDeclined"
                }
                observe(disposition)
                if (queryCenterStep == null && centerProgress != null && !proposalHasCheckedCenter) {
                    queryOriginalCondition?.let { original ->
                        queryCenterStep = centerProgress.suggest(support.assumptions, condition, original)
                        if (queryCenterStep != null) observe("jointRepairAdvisoryCenterProposals")
                    }
                }
                observation?.feedback(proposalSequence, observationQuery, disposition, support.assumptions,
                    condition, model.numIntVars)
            }
            is SourceNumericalSupportResult.Unavailable -> {
                observe("jointRepairRefutationSupport${support.reason}")
                observation?.event(proposalSequence, observationQuery, "feedback",
                    "support-${support.reason}-${support.limitStage}")
                support.limitStage?.let { observe("jointRepairRefutationSupportLimit$it") }
                if (support.reason == SourceNumericalSupportResult.Reason.Funding) {
                    observe(if (funding.exhausted) "jointRepairRefutationOwnerExhausted"
                    else "jointRepairRefutationGrantPaused")
                }
            }
        }
    }

    private fun reviseRefutedCenter(centers: MutableList<BigFraction>, bools: BooleanArray,
        condition: ExactNeighborhoodCondition, cancellation: ExecutionControl) {
        val step = readyCenterStep
        readyCenterStep = null
        if (step == null || anchor != null || preparedCenter != null || completion == null ||
            proposalHasCheckedCenter) return
        if (centerProgress?.apply(step, centers, bools, condition, cancellation) != true) {
            observe("jointRepairAdvisoryCenterDeclined")
            return
        }
        selection?.clearRefutation()
        observe("jointRepairAdvisoryCenterMoves")
        observation?.event(proposalSequence, observationQuery, "center", "advisory-step")
    }

    private fun retire(disposition: String = "Replaced") {
        val observations = queryObservations(authority.cutObservations().keys)
        for ((key, value) in observations) stages[key] = combine(key, stages[key] ?: 0, value)
        stages.putAll(privateQueryObservation(observations, disposition))
        val active = query
        query = null
        pending = null
        queryRefutationKey = null
        queryRefutationScope = null
        queryOriginalCondition = null
        queryCenterStep = null
        querySawExternal = false
        queryProximity = false
        queryProximityClosed = false
        queryProximityKind = JointRepairNeighborhood.AdvisorySelection.Kind.Pair
        queryProximityCutoff = null
        queryProximityPass = null
        retainedProposal = null
        publishedQueryIncumbent = null
        privateQueryReceipt = null
        privatePublication = null
        active?.close()
    }

    private fun publicationChecking(cancellation: ExecutionControl): ExecutionControl = object : ExecutionControl() {
        private val meter = WorkMeter { units -> budget.observe(boundCertification = units) }
        override fun isCancelled(): Boolean {
            charge(1L)
            // Atomic publication spends the containing funding after the query's slice grant ends.
            return closed || cancellation() || funding.exhausted
        }
        override fun deadline() = cancellation.deadline()
        override val accounting: WorkMeter get() = meter
    }

    override fun close() {
        if (closed) return
        closed = true
        incumbentTrialPending = false
        enlargedTrialPending = false
        enlargedTrialAfterWitness = false
        enlargedTrialProposal = null
        checkedTrialCenter = null
        checkedProposal = null
        relaxationProposal = null
        proximityProposal = null
        proximityAdvisory = null
        advisoryCenter = null
        advisorySeen = null
        centerPass = null
        proximityProposalPass = null
        proximityDiscovery = null
        bootstrapHint = null
        guidedProposal = null
        readyCenterStep = null
        selection?.clearRefutation()
        refutedConditions.clear()
        queryFreedIntegers = 0
        val resources = SearchInvocationResources()
        resources.own(authority)
        completion?.let(resources::own)
        proximityChecking?.takeUnless { it === completion }?.let(resources::own)
        resources.own(AutoCloseable { retire("WorkerClose") })
        resources.close()
    }
}

private const val QUERY_SLICE_MILLIS = 10L
private const val QUERY_SLICE_WORK = 64L
private const val MAX_PRIVATE_QUERY_OBSERVATIONS = 4
private const val MAX_REFUTED_CONDITIONS = 4
private const val MAX_REFUTATION_ENTRIES = 4096
private const val MAX_REFUTATION_SCALAR_BITS = 4096
private const val MAX_REFUTATION_BYTES = 1_048_576L
