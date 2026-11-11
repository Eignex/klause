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
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
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
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.search.SearchInvocationResources
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.magnitudeBitLength
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
    private val publicationLifetime: Cancellation? = null,
    sourceIntegers: IntArray? = null,
    private val onIncumbentImport: (() -> Unit)? = null,
    private val onIncumbentUse: (() -> Unit)? = null,
    private val observeExternalIncumbents: Boolean = false,
    sharedCompletions: LinearIncumbentSharing? = null,
    private val incumbentCompletionCheck: ((Sample, BigFraction, Cancellation) -> Boolean)? = null,
    private val onComparison: ((JointRepairComparison) -> Unit)? = null,
    private val repairGuidance: LinearRepairGuidance? = null,
    onObservation: ((JointRepairObservation) -> Unit)? = null,
) : ExactNeighborhoodCompletion, AutoCloseable {
    private class CheckedCenter(val sample: Sample, val value: BigFraction?)

    private val sourceIntegers = sourceIntegers?.copyOf()
    private val stop = params.timeout or params.cancellation
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
    private var observationQuery = 0L
    private var observedOperator = 0
    private var observedSchedulingRound = 0L
    private var observedRoots: List<Int> = emptyList()
    private val completion = if (params.sourceIncumbentCompletion)
        LinearIncumbentCompletion(budget, incumbentCompletionCheck, sharedCompletions) { stage, count ->
            val key = "jointRepairExternalCompletion$stage"
            stages[key] = combine(key, stages[key] ?: 0L, count)
        } else null
    private var comparisonCount = 0
    private var conditionSequence = 0L
    private var importedCenter: Sample? = null
    private var preparedCenter: CheckedCenter? = null
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
    val exhausted: Boolean get() = declined || funding.exhausted || budget.operations >= params.sourceLpOperations ||
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
        )).let { counters -> if (observation == null) counters else counters + observation.observations() }))

    private fun observe(stage: String) { stages[stage] = (stages[stage] ?: 0) + 1 }

    private fun observeExternal(stage: String) {
        if (observeExternalIncumbents) observe("jointRepairExternal$stage")
    }

    private fun comparisonValue(sample: Sample): BigFraction? {
        if (onComparison == null || comparisonCount >= 64 || stop()) return null
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

    private fun externalIncumbent(cutoff: BigFraction? = pending?.cutoff): Sample? {
        val offered = external() ?: return null
        val raw = comparisonValue(offered)
        val completed = if (offered.exactReals == null && completion != null)
            completion.complete(source, offered, stop) else offered
        compare("external", if (completed == null) "completion-unavailable" else "available",
            raw = raw, completed = completed?.let(::comparisonValue), cutoff = cutoff)
        return completed
    }

    private fun checkedExternalCenter(cutoff: BigFraction? = null): CheckedCenter? {
        val sample = externalIncumbent(cutoff) ?: return null
        if (completion != null) {
            budget.observe(boundCertification = 1L + model.numBoolVars + model.numIntVars + model.numRealVars)
            if (completion.checking(stop)()) return null
        }
        val value = objective?.evaluateExact(sample)
        if (model.numRealVars != 0 && sample.exactReals?.size != model.numRealVars) {
            observeExternal("MissingExactReals")
            return null
        }
        if (cutoff != null && (value == null || value >= cutoff)) {
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
                else -> "jointRepair" + key.removePrefix("numerical")
            }
        }

    private fun combine(key: String, before: Long, after: Long): Long =
        if ("Max" in key || "Peak" in key) maxOf(before, after) else before + after

    private fun observations(): Map<String, Long> {
        val cuts = authority.cutObservations()
        val current = queryObservations(cuts.keys)
        return (stages.keys + current.keys).associateWith { combine(it, stages[it] ?: 0, current[it] ?: 0) } +
            cuts.mapKeys { (key, _) -> "jointRepair" + key.removePrefix("numerical") }
    }

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
            prepareBooleanCenter = ::prepare, rootCoordinateValue = ::center,
            completionExhausted = { exhausted },
            centerPreparationPending = { rootPreparationPending },
            reviseRefutedCenter = centerProgress?.let { ::reviseRefutedCenter },
            onProposalOperator = observation?.let { { operator, round ->
                observedOperator = operator
                observedSchedulingRound = round
            } },
            proposal = ExactNeighborhoodProposal { centers, bools, count, round, radius, cutoff ->
                val checked = preparedCenter?.takeIf { round == 0L &&
                    (cutoff == null || it.value?.let { value -> value < cutoff } == true)
                } ?: checkedExternalCenter(cutoff)
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
                if (centerProgress != null) proposalHasCheckedCenter = pooled != null
                val from = pooled?.let { sample ->
                    if (sample !== importedCenter) {
                        importedCenter = sample
                        onIncumbentImport?.invoke()
                    }
                    List(model.numIntVars) { BigFraction.of(sample.exactIntValue(it), BIG_ONE) } +
                        sample.exactReals.orEmpty()
                } ?: centers
                val limit = current?.value ?: cutoff
                if (sourceInputPass && !adaptive && limit != null && limit != focusedCutoff) {
                    focusedCutoff = limit
                    focusedRemaining = sourceIntegers?.size ?: model.numIntVars
                    observe("jointRepairSourceInputPasses")
                }
                val checkedStart = pooled != null && completion != null && round == 0L
                if (round == 0L && completion != null) incumbentTrialPending = checkedStart
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
                val proposalRound = if (pooled != null && completion != null)
                    round.coerceAtMost(Long.MAX_VALUE - 1) + 1 else round
                val size = if (enlargedTrialPending && width > checkedTrialWidth)
                    minOf(width, checkedTrialWidth + 1) else width
                val hint = if (pooled != null && completion != null) repairGuidance?.find(source) else null
                val guided = requireNotNull(selection).guide(hint, from, budget, stop)
                requireNotNull(selection).propose(from, pooled?.bools ?: bools, size, proposalRound, radius, limit)
                    .also { condition ->
                        if (observation != null) {
                            proposalSequence++
                            observationQuery = 0L
                            observation.proposal(proposalSequence, observedOperator, observedSchedulingRound,
                                proposalRound, count, size, if (pooled == null) "stored" else "checked-pooled",
                                from, model.numIntVars, sourceIntegers, observedRoots, condition)
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

    private fun prepare(cancellation: Cancellation): Boolean {
        check(!closed)
        if (stop() || exhausted || cancellation()) return false
        if (selection == null) {
            val token = stop or cancellation
            val construction = object : Cancellation {
                override fun isCancelled(): Boolean = token()
                override fun deadline() = token.deadline()
                override fun workMeter(): WorkMeter = WorkMeter { units ->
                    preparationWork += units
                    token.charge(units)
                }
            }
            selection = JointRepairNeighborhood.prepare(source, model.numIntVars, model.numRealVars, construction,
                sourceIntegers)
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
        preparedCenter?.let { return BigFraction.of(it.sample.exactIntValue(index), BIG_ONE) }
        val coordinate = SourceCoordinate(SourceCoordinate.Kind.Integer, index)
        var value = rootPoint?.get(coordinate)?.takeIf { it.isFinite() }?.let { BigFraction.ofDouble(round(it)) }
            ?: BigFraction.ZERO
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

    override fun check(condition: ExactNeighborhoodCondition, cancellation: Cancellation): ExactNeighborhoodResult =
        attempt(condition, cancellation).result

    fun attempt(condition: ExactNeighborhoodCondition, cancellation: Cancellation): JointRepairAttempt {
        readyCenterStep = null
        val result = advanceAttempt(condition, cancellation)
        if (result.result != ExactNeighborhoodResult.Paused) {
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

    private fun advanceAttempt(condition: ExactNeighborhoodCondition, cancellation: Cancellation): JointRepairAttempt {
        check(!closed)
        if (stop() || exhausted) return stoppedAttempt()
        if (cancellation()) return JointRepairAttempt(ExactNeighborhoodResult.Paused)
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
            var bounds = source.scope.condition
            var pinnedIntegers = 0
            for ((id, value) in captured.bools) bounds = bounds.pin(id, value)
            for ((upper, entries) in listOf(false to captured.lower, true to captured.upper)) {
                for ((id, value) in entries) {
                    if (!upper && id < model.numIntVars && captured.upper[id] == value) pinnedIntegers++
                    val coordinate = if (id < model.numIntVars) SourceCoordinate(SourceCoordinate.Kind.Integer, id)
                        else SourceCoordinate(SourceCoordinate.Kind.Real, id - model.numIntVars)
                    bounds = bounds.bound(coordinate, value, upper)
                }
            }
            pending = captured
            if (onComparison != null || observation != null) conditionSequence++
            observationQuery = conditionSequence
            observation?.event(proposalSequence, observationQuery, "query", "opened")
            compare("query", "opened", cutoff = condition.cutoff)
            val querySource = source.atScope(source.scope.copy(condition = bounds))
            queryRefutationKey = key
            queryRefutationScope = querySource.scope
            querySawExternal = false
            queryOriginalCondition = condition
            val refutationGuidance = completion != null && anchor == null && preparedCenter == null &&
                condition.cutoff == null
            query = LinearAuthorityInvocation(querySource,
                params.copy(sourceIncumbentCompletion = false, sourceRefutationCenterStep = false),
                external = {
                    (externalIncumbent() ?: anchor).also { sample ->
                        if (sample != null) {
                            querySawExternal = true
                            refutedConditions.clear()
                        }
                    }
                }, state = state, borrowedAuthority = authority,
                boundOrdered = boundOrdered, guidance = true, funding = funding,
                onNumericalFeedback = if (refutationGuidance) { feedback ->
                    guideRefutation(captured, querySource, feedback)
                } else null,
                feedbackRequest = if (refutationGuidance) LinearAuthorityFeedbackRequest(refutation = true) else null)
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
        val result = active.runSlice(Cancellation.Never, millis, QUERY_SLICE_WORK) { _, _ -> }
        val snapshot = result ?: active.snapshot(TerminationReason.BudgetExhausted)
        val imported = completion != null && active.incumbentIsImported
        if (imported && snapshot.incumbent != null) observe("jointRepairImportedPointWithheld")
        val sample = snapshot.incumbent?.takeUnless { imported }?.takeIf {
            condition.cutoff == null || requireNotNull(objective).evaluateExact(it) < condition.cutoff
        }
        if (sample != null) {
            val evidence = SourceEvidence(source.scope, SourceClaim.Witness(sample), SourceDerivation.SourceWitness)
            // Full-source point verification has no retained cursor across slice boundaries.
            val verification = stop or (publicationLifetime ?: cancellation)
            val mark = TimeSource.Monotonic.markNow()
            observe("jointRepairPublicationChecks")
            val checked = SourceEvidenceVerifier(source.withLinearAuthority()).verify(evidence, verification)
            var originalChecked = true
            if (checked == SourceEvidenceCheck.Checked && incumbentCompletionCheck != null) {
                val checking = publicationChecking(verification)
                observe("jointRepairOriginalPublicationChecks")
                val value = objective?.evaluateExact(sample)
                originalChecked = value != null && incumbentCompletionCheck.invoke(sample, value, checking)
                if (checking()) {
                    originalChecked = false
                    observe("jointRepairOriginalPublicationUnavailable")
                } else observe(if (originalChecked) "jointRepairOriginalPublicationChecked"
                    else "jointRepairOriginalPublicationRejected")
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
                    bound = snapshot.bound)
            }
            if (checked == SourceEvidenceCheck.Checked && originalChecked) {
                compare("discovery", "checked", cutoff = condition.cutoff, candidate = comparisonValue(sample))
            }
            retire()
            if (checked == SourceEvidenceCheck.Checked && originalChecked) {
                anchor = sample
                selection?.clearRefutation()
                refutedConditions.clear()
                observe("jointRepairCheckedIncumbents")
                return JointRepairAttempt(ExactNeighborhoodResult.Witness(sample),
                    feasiblePoint = sample, bound = snapshot.bound)
            }
            return JointRepairAttempt(ExactNeighborhoodResult.Undecided, when {
                params.timeout() -> TerminationReason.Timeout
                stop() -> TerminationReason.Cancelled
                else -> TerminationReason.Unsupported
            },
                bound = snapshot.bound)
        }
        if (condition.cutoff != null) {
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
                bound = snapshot.bound)
        }
        val refuted = result.terminal?.claim == SourceClaim.Refutation
        val completedKey = queryRefutationKey.takeIf { refuted && !querySawExternal &&
            result.terminal?.scope == queryRefutationScope }
        val completedStep = queryCenterStep.takeIf { refuted && !querySawExternal &&
            result.terminal?.scope == queryRefutationScope && anchor == null && preparedCenter == null }
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

    private fun refutationKey(condition: ExactNeighborhoodCondition,
        cancellation: Cancellation): ExactNeighborhoodCondition? {
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
        for (values in listOf(condition.lower, condition.upper)) for (value in values.values) {
            if (stop() || cancellation() || funding.exhausted) return null
            val bits = value.num.magnitudeBitLength().toLong() + value.den.magnitudeBitLength()
            if (bits > MAX_REFUTATION_SCALAR_BITS) return null
            work += MAX_REFUTED_CONDITIONS * (1L + bits * bits / 64L)
            bytes += 64L + (bits + 7L) / 8L
            if (work >= funding.remainingWork || bytes >= funding.remainingAllocation ||
                bytes > MAX_REFUTATION_BYTES) return null
        }
        if (work >= funding.remainingWork || bytes >= funding.remainingAllocation ||
            bytes > MAX_REFUTATION_BYTES) return null
        budget.observe(preparation = work)
        funding.charge(0L, bytes)
        if (stop() || cancellation() || funding.exhausted) return null
        return condition.copy(bools = condition.bools.toMap(), lower = condition.lower.toMap(),
            upper = condition.upper.toMap())
    }

    private fun rememberRefutation(key: ExactNeighborhoodCondition, cancellation: Cancellation) {
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
        condition: ExactNeighborhoodCondition, cancellation: Cancellation) {
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

    private fun retire() {
        val observations = queryObservations(authority.cutObservations().keys)
        for ((key, value) in observations) stages[key] = combine(key, stages[key] ?: 0, value)
        val active = query
        query = null
        pending = null
        queryRefutationKey = null
        queryRefutationScope = null
        queryOriginalCondition = null
        queryCenterStep = null
        querySawExternal = false
        active?.close()
    }

    private fun publicationChecking(cancellation: Cancellation): Cancellation = object : Cancellation {
        private val meter = WorkMeter { units -> budget.observe(boundCertification = units) }
        override fun isCancelled(): Boolean {
            charge(1L)
            // Atomic publication spends the containing funding after the query's slice grant ends.
            return closed || cancellation() || funding.exhausted
        }
        override fun deadline() = cancellation.deadline()
        override fun workMeter(): WorkMeter = meter
    }

    override fun close() {
        if (closed) return
        closed = true
        incumbentTrialPending = false
        enlargedTrialPending = false
        enlargedTrialAfterWitness = false
        enlargedTrialProposal = null
        checkedTrialCenter = null
        guidedProposal = null
        readyCenterStep = null
        selection?.clearRefutation()
        refutedConditions.clear()
        queryFreedIntegers = 0
        val resources = SearchInvocationResources()
        resources.own(authority)
        completion?.let(resources::own)
        resources.own(AutoCloseable { retire() })
        resources.close()
    }
}

private const val QUERY_SLICE_MILLIS = 10L
private const val QUERY_SLICE_WORK = 64L
private const val MAX_REFUTED_CONDITIONS = 4
private const val MAX_REFUTATION_ENTRIES = 4096
private const val MAX_REFUTATION_SCALAR_BITS = 4096
private const val MAX_REFUTATION_BYTES = 1_048_576L
