package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.LP_WORK_PER_NODE
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.lp.SparseSourceLpPolicy
import com.eignex.klause.lp.optimization.LinearAuthority
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
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.search.SearchInvocationResources
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.math.round
import kotlin.time.TimeSource

internal data class JointRepairAttempt(
    val result: ExactNeighborhoodResult,
    val reason: TerminationReason? = null,
    val feasiblePoint: Sample? = null,
    val bound: SourceEvidence? = null,
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
) : ExactNeighborhoodCompletion, AutoCloseable {
    private class CheckedCenter(val sample: Sample, val value: BigFraction?)

    private val sourceIntegers = sourceIntegers?.copyOf()
    private val stop = params.timeout or params.cancellation
    private val state = OpenTheorySolveState(params)
    private val funding = SourceLpFunding(params.sourceLpWorkLimit, params.sourceLpAllocationLimit)
    private val budget = SourceLpBudget(maxOperations = params.sourceLpOperations,
        onWork = state.smt::observeSourceLp, funding = funding)
    private val stages = linkedMapOf<String, Long>()
    private val completion = if (params.sourceIncumbentCompletion)
        LinearIncumbentCompletion(budget, incumbentCompletionCheck, sharedCompletions) { stage, count ->
            val key = "jointRepairExternalCompletion$stage"
            stages[key] = combine(key, stages[key] ?: 0L, count)
        } else null
    private var importedCenter: Sample? = null
    private var preparedCenter: CheckedCenter? = null
    private val authority = LinearAuthority(budget, onLpShape = { shape ->
        if (shape.sparse) observe(shape.phase?.let { "jointRepairLpPhase_$it" } ?: "jointRepairLpNoPhase")
        if (!shape.admitted) observe("jointRepairLpDeclinedAttempts")
        stages["jointRepairLpMaxRows"] = maxOf(stages["jointRepairLpMaxRows"] ?: 0, shape.rows.toLong())
        stages["jointRepairLpMaxColumns"] = maxOf(stages["jointRepairLpMaxColumns"] ?: 0, shape.columns.toLong())
    }, retainLp = true, guidancePolicy = guidancePolicy)
    private var selection: JointRepairNeighborhood? = null
    private var rootPoint: Map<SourceCoordinate, Double>? = null
    private var rootStarted = false
    private var rootPreparationPending = false
    private var query: LinearAuthorityInvocation? = null
    private var anchor: Sample? = null
    private var pending: ExactNeighborhoodCondition? = null
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
        completeSearchStages = observations() + mapOf(
            "jointRepairSelectedIntegers" to (selection?.selectedIntegers ?: 0),
            "jointRepairConnectivityVisits" to (selection?.visitedEntries ?: 0),
            "jointRepairPreparationEntries" to preparationWork,
            "jointRepairLpPreparations" to authority.preparations,
            "jointRepairLpReuses" to authority.reuses,
            "jointRepairFundedWork" to funding.spent,
            "jointRepairFundedAllocation" to funding.allocated,
        )))

    private fun observe(stage: String) { stages[stage] = (stages[stage] ?: 0) + 1 }

    private fun observeExternal(stage: String) {
        if (observeExternalIncumbents) observe("jointRepairExternal$stage")
    }

    private fun externalIncumbent(): Sample? = external()?.let { sample ->
        if (sample.exactReals == null && completion != null) completion.complete(source, sample, stop) else sample
    }

    private fun checkedExternalCenter(cutoff: BigFraction? = null): CheckedCenter? {
        val sample = externalIncumbent() ?: return null
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
            return null
        }
        val checking = completion?.checking(stop) ?: stop
        val checked = SourceEvidenceVerifier(source.withLinearAuthority()).verify(
            SourceEvidence(source.scope, SourceClaim.Witness(sample), SourceDerivation.SourceWitness), checking)
        if (checked != SourceEvidenceCheck.Checked) {
            observeExternal(if (checked is SourceEvidenceCheck.Rejected) "PointRejected" else "PointUnavailable")
            return null
        }
        return CheckedCenter(sample, value)
    }

    private fun queryObservations(): Map<String, Long> =
        query?.stats?.smt?.completeSearchStages.orEmpty().mapKeys { (key, _) ->
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
        val current = queryObservations()
        return (stages.keys + current.keys).associateWith { combine(it, stages[it] ?: 0, current[it] ?: 0) }
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
            proposal = ExactNeighborhoodProposal { centers, bools, count, round, radius, cutoff ->
                val checked = preparedCenter?.takeIf { round == 0L &&
                    (cutoff == null || it.value?.let { value -> value < cutoff } == true)
                } ?: checkedExternalCenter(cutoff)
                val pooled = checked?.sample
                val from = pooled?.let { sample ->
                    observe("jointRepairImportedCenters")
                    if (sample !== importedCenter) {
                        importedCenter = sample
                        onIncumbentImport?.invoke()
                    }
                    List(model.numIntVars) { BigFraction.of(sample.exactIntValue(it), BIG_ONE) } +
                        sample.exactReals.orEmpty()
                } ?: centers
                val limit = checked?.value ?: cutoff
                if (sourceInputPass && !adaptive && limit != null && limit != focusedCutoff) {
                    focusedCutoff = limit
                    focusedRemaining = sourceIntegers?.size ?: model.numIntVars
                    observe("jointRepairSourceInputPasses")
                }
                val checkedStart = pooled != null && completion != null && round == 0L
                if (round == 0L && completion != null) incumbentTrialPending = checkedStart
                val width = if (checkedStart) {
                    observe("jointRepairCheckedCenterStarts")
                    minOf(count, 2)
                } else if (sourceInputPass && !adaptive && round > 0L && focusedRemaining > 0) {
                    focusedRemaining--
                    observe("jointRepairSourceInputProposals")
                    minOf(count, 2)
                } else count
                if (pooled != null) onIncumbentUse?.invoke()
                // A checked center needs no all-pinned rounded-root bootstrap.
                val proposalRound = if (pooled != null && completion != null)
                    round.coerceAtMost(Long.MAX_VALUE - 1) + 1 else round
                requireNotNull(selection).propose(from, pooled?.bools ?: bools, width, proposalRound, radius, limit)
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
            val result = authority.advance(source, stop, cuts = boundOrdered)
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
            rootPoint = result.relaxedPoint
            requireNotNull(selection).guide(result.relaxedPoint)
            stages["jointRepairGuidedIntegers"] = result.relaxedPoint.keys.count {
                it.kind == SourceCoordinate.Kind.Integer
            }.toLong()
            if (result.relaxedPoint.isEmpty()) observe("jointRepairRootGuidanceUnavailable")
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
        val result = advanceAttempt(condition, cancellation)
        if (result.result != ExactNeighborhoodResult.Paused) incumbentTrialPending = false
        return result
    }

    private fun advanceAttempt(condition: ExactNeighborhoodCondition, cancellation: Cancellation): JointRepairAttempt {
        check(!closed)
        if (stop() || exhausted) return JointRepairAttempt(ExactNeighborhoodResult.Undecided, when {
            params.timeout() -> TerminationReason.Timeout
            stop() -> TerminationReason.Cancelled
            else -> TerminationReason.BudgetExhausted
        })
        if (cancellation()) return JointRepairAttempt(ExactNeighborhoodResult.Paused)
        if (pending != condition) {
            retire()
            var bounds = source.scope.condition
            for ((id, value) in condition.bools) bounds = bounds.pin(id, value)
            for ((upper, entries) in listOf(false to condition.lower, true to condition.upper)) {
                for ((id, value) in entries) {
                    val coordinate = if (id < model.numIntVars) SourceCoordinate(SourceCoordinate.Kind.Integer, id)
                        else SourceCoordinate(SourceCoordinate.Kind.Real, id - model.numIntVars)
                    bounds = bounds.bound(coordinate, value, upper)
                }
            }
            pending = condition
            query = LinearAuthorityInvocation(source.atScope(source.scope.copy(condition = bounds)),
                params.copy(sourceIncumbentCompletion = false),
                external = { externalIncumbent() ?: anchor }, state = state, borrowedAuthority = authority,
                boundOrdered = boundOrdered, guidance = true, funding = funding)
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
            stages["jointRepairPublicationNs"] = (stages["jointRepairPublicationNs"] ?: 0L) +
                mark.elapsedNow().inWholeNanoseconds
            if (publicationLifetime == null && cancellation() && !stop()) {
                observe("jointRepairPublicationPauses")
                return JointRepairAttempt(ExactNeighborhoodResult.Paused, feasiblePoint = sample,
                    bound = snapshot.bound)
            }
            retire()
            if (checked == SourceEvidenceCheck.Checked && originalChecked) {
                anchor = sample
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
        retire()
        return JointRepairAttempt(
            if (result.terminal?.claim == SourceClaim.Refutation) ExactNeighborhoodResult.Refuted
            else ExactNeighborhoodResult.Undecided,
            if (result.terminal?.claim is SourceClaim.Ray) TerminationReason.Unsupported else result.reason,
            result.incumbent, result.bound)
    }

    private fun retire() {
        val observations = queryObservations()
        for ((key, value) in observations) stages[key] = combine(key, stages[key] ?: 0, value)
        val active = query
        query = null
        pending = null
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
        val resources = SearchInvocationResources()
        resources.own(authority)
        completion?.let(resources::own)
        resources.own(AutoCloseable { retire() })
        resources.close()
    }
}

private const val QUERY_SLICE_MILLIS = 10L
private const val QUERY_SLICE_WORK = 64L
