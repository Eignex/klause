package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.SourceLpBudget
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
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import kotlin.math.round
import kotlin.time.TimeSource

internal class JointLinearRepair(
    private val model: Problem,
    private val source: SourceProofContext,
    private val objective: LinearObjective?,
    private val params: TheoryParams,
) : ExactNeighborhoodCompletion, AutoCloseable {
    private val stop = params.timeout or params.cancellation
    private val state = OpenTheorySolveState(params)
    private val budget = SourceLpBudget(maxOperations = SOURCE_OPERATION_LIMIT, onWork = state.smt::observeSourceLp)
    private val authority = LinearAuthority(budget, retainLp = true)
    private var selection: JointRepairNeighborhood? = null
    private var rootPoint: Map<SourceCoordinate, Double>? = null
    private var query: LinearAuthorityInvocation? = null
    private var pending: ExactNeighborhoodCondition? = null
    private val stages = linkedMapOf<String, Long>()
    private var closed = false
    val exhausted: Boolean get() = !state.work.hasAllowance() || state.work.acceptedChecks >= params.maxLeaves ||
        budget.operations >= SOURCE_OPERATION_LIMIT
    val work: Long get() = state.work.spent + budget.measuredPreparationWork + budget.measuredFloatWork +
        budget.measuredContinuationWork
    val stats: SolveStats get() = SolveStats(openTheory = state.work.snapshot(), smt = state.smt.snapshot().copy(
        completeSearchStages = observations() + mapOf(
            "jointRepairSelectedIntegers" to (selection?.selectedIntegers ?: 0),
            "jointRepairConnectivityVisits" to (selection?.visitedEntries ?: 0),
            "jointRepairLpPreparations" to authority.preparations,
            "jointRepairLpReuses" to authority.reuses,
        )))

    private fun observe(stage: String) { stages[stage] = (stages[stage] ?: 0) + 1 }

    private fun observations(): Map<String, Long> {
        val current = query?.stats?.smt?.completeSearchStages.orEmpty()
        return (stages.keys + current.keys).associateWith { (stages[it] ?: 0) + (current[it] ?: 0) }
    }

    fun search(adaptive: Boolean, seed: Long = params.randomSeed): ExactNeighborhood = ExactNeighborhood(
        model.numBoolVars, List(model.numIntVars + model.numRealVars) { BigFraction.ZERO }, this, objective,
        coordinateValues = { sample ->
            List(model.numIntVars) { BigFraction.of(sample.exactIntValue(it), BIG_ONE) } + sample.exactReals.orEmpty()
        }, adaptive = adaptive, seed = seed, nodeBudget = params.nodeBudget?.allowance,
        maximumMoves = params.maxFlips, maximumInstructions = params.maxInstructions ?: Long.MAX_VALUE,
        maximumImprovementMoves = if (adaptive) params.alnsIterations.toLong() else null,
        nativeWork = { work }, completionStats = { stats }, onClose = ::close,
        prepareBooleanCenter = ::prepare, rootCoordinateValue = ::center,
        proposal = ExactNeighborhoodProposal { centers, bools, count, round, radius, cutoff ->
            requireNotNull(selection).propose(centers, bools, count, round, radius, cutoff)
        },
    )

    private fun prepare(cancellation: Cancellation): Boolean {
        check(!closed)
        if (stop() || exhausted || cancellation()) return false
        if (selection == null) selection = JointRepairNeighborhood.prepare(source, model.numIntVars,
            model.numRealVars, stop or cancellation) ?: return false
        if (rootPoint == null) {
            if (!state.work.theoryCheck()) return false
            val result = authority.check(source, stop or cancellation)
            if (cancellation()) return false
            rootPoint = result.relaxedPoint
            requireNotNull(selection).guide(result.relaxedPoint)
            observe("jointRepairRootQueries")
        }
        return true
    }

    private fun center(index: Int): BigFraction? {
        if (index >= model.numIntVars) return null
        val coordinate = SourceCoordinate(SourceCoordinate.Kind.Integer, index)
        var value = rootPoint?.get(coordinate)?.takeIf { it.isFinite() }?.let { BigFraction.ofDouble(round(it)) }
            ?: BigFraction.ZERO
        val bounds = model.intBounds
        if (bounds.hasLower(index)) value = maxOf(value, BigFraction.ofLong(bounds.lower(index)))
        if (bounds.hasUpper(index)) value = minOf(value, BigFraction.ofLong(bounds.upper(index)))
        return value
    }

    override fun check(condition: ExactNeighborhoodCondition, cancellation: Cancellation): ExactNeighborhoodResult {
        check(!closed)
        if (stop() || exhausted) return ExactNeighborhoodResult.Undecided
        if (cancellation()) return ExactNeighborhoodResult.Paused
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
            query = LinearAuthorityInvocation(source.atScope(source.scope.copy(condition = bounds)), params,
                state = state, borrowedAuthority = authority)
            observe("jointRepairCandidates")
        }
        val active = requireNotNull(query)
        val millis = cancellation.deadline()?.let { (it - TimeSource.Monotonic.markNow()).inWholeMilliseconds }
            ?.coerceIn(1, QUERY_SLICE_MILLIS) ?: QUERY_SLICE_MILLIS
        val result = active.runSlice(Cancellation.Never, millis, QUERY_SLICE_WORK) { _, _ -> }
        val snapshot = result ?: active.snapshot(TerminationReason.BudgetExhausted)
        val sample = snapshot.incumbent?.takeIf {
            condition.cutoff == null || requireNotNull(objective).evaluateExact(it) < condition.cutoff
        }
        if (sample != null) {
            val evidence = SourceEvidence(source.scope, SourceClaim.Witness(sample), SourceDerivation.SourceWitness)
            val checked = SourceEvidenceVerifier(source.withLinearAuthority()).verify(evidence, stop or cancellation)
            if (cancellation() && !stop()) return ExactNeighborhoodResult.Paused
            retire()
            if (checked == SourceEvidenceCheck.Checked) {
                observe("jointRepairCheckedIncumbents")
                return ExactNeighborhoodResult.Witness(sample)
            }
            return ExactNeighborhoodResult.Undecided
        }
        if (result == null) {
            observe("jointRepairPauses")
            return ExactNeighborhoodResult.Paused
        }
        retire()
        return if (result.terminal?.claim == SourceClaim.Refutation) ExactNeighborhoodResult.Refuted
            else ExactNeighborhoodResult.Undecided
    }

    private fun retire() {
        val observations = query?.stats?.smt?.completeSearchStages.orEmpty()
        for ((key, value) in observations) stages[key] = (stages[key] ?: 0) + value
        query?.close()
        query = null
        pending = null
    }

    override fun close() {
        if (closed) return
        closed = true
        try { retire() } finally { authority.close() }
    }
}

private const val QUERY_SLICE_MILLIS = 10L
private const val QUERY_SLICE_WORK = 64L
private const val SOURCE_OPERATION_LIMIT = 256
