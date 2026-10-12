package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.search.AssembledSearchModel
import com.eignex.klause.solver.search.BooleanBranching
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.HeuristicBooleanBranching
import com.eignex.klause.solver.search.SearchBoolValue
import com.eignex.klause.solver.search.SearchCandidateHints
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchExactIntValue
import com.eignex.klause.solver.search.SearchExactRealValue
import com.eignex.klause.solver.search.SearchIntValue
import com.eignex.klause.solver.search.SearchNodeDisposition
import com.eignex.klause.solver.search.SearchNodePolicy
import com.eignex.klause.solver.search.SearchRealValue
import com.eignex.klause.solver.search.SearchRestart
import com.eignex.klause.solver.search.SearchRunEvent
import com.eignex.klause.solver.search.SearchRunObserver
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SearchSolveParams
import com.eignex.klause.solver.search.SearchTheoryDecision
import com.eignex.klause.solver.search.Vsids
import com.eignex.klause.theory.qflra.SourceBoundAtom
import com.eignex.klause.theory.qflra.SourceBoundTerm
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.toLongExact
import kotlin.coroutines.cancellation.CancellationException

internal class RetainedLinearQueries(
    private val model: Problem,
    private val params: TheoryParams,
    private val objective: LinearObjective?,
    private val hints: (Cancellation, OpenTheorySolveState) -> SearchCandidateHints = { _, _ -> SearchCandidateHints.None },
    private val open: (Cancellation, OpenTheorySolveState) -> PlannedSearch?,
) : ExactNeighborhoodCompletion, AutoCloseable {
    private val state = OpenTheorySolveState(params)
    private val scope = ConditionedQueryScope(params.timeout or params.cancellation)
    private var planned: PlannedSearch? = null
    private var sourceRefuted = false
    private var checking = false
    private var closed = false
    private val marker = object : SearchTheoryDecision {}
    val work: Long get() = state.work.spent
    val stats: SolveStats get() = SolveStats(openTheory = state.work.snapshot(), smt = state.smt.snapshot(),
        openTheoryClauses = planned?.session?.learnedClauseStats() ?: state.clauses, openHints = state.hints)
    val exhausted: Boolean get() = !state.work.hasAllowance() ||
        state.work.snapshot().openTheoryChecks >= params.maxLeaves

    fun <T> withCondition(condition: ExactNeighborhoodCondition, cancellation: Cancellation, action: () -> T): T {
        validate(condition)
        return scope.withCondition(condition, cancellation) { action() }
    }

    override fun check(condition: ExactNeighborhoodCondition, cancellation: Cancellation): ExactNeighborhoodResult {
        check(!closed) { "conditioned query owner is closed" }
        check(!checking) { "a worker may execute one conditioned query at a time" }
        validate(condition)
        return scope.withCondition(condition, cancellation) { conditions ->
            checking = true
            try {
                decide(conditions)
            } catch (_: CancellationException) {
                ExactNeighborhoodResult.Undecided
            } finally {
                checking = false
            }
        }
    }

    private fun decide(conditions: List<ExactNeighborhoodCondition>): ExactNeighborhoodResult {
        if (scope.stop() || exhausted) return ExactNeighborhoodResult.Undecided
        if (sourceRefuted) return ExactNeighborhoodResult.Refuted
        val search = prepared() ?: return if (sourceRefuted) ExactNeighborhoodResult.Refuted
            else ExactNeighborhoodResult.Undecided
        val session = search.session
        if (model.numIntVars + model.numRealVars == 0 && !session.consumeCheck()) {
            return ExactNeighborhoodResult.Undecided
        }
        val enclosingLevel = session.decisionLevel
        return try {
            val boundary = session.push(SearchDecision.Theory(marker))
            if (boundary !is ComponentResult.Consistent) return boundary.result()
            for (condition in conditions) {
                val applied = apply(session, condition)
                if (applied !is ComponentResult.Consistent) return applied.result()
            }
            val policy = cutoffPolicy(session, conditions.mapNotNull { it.cutoff }.minOrNull())
            val branching = when (params.openBranching) {
                OpenBranching.SourceOrder -> BooleanBranching.SourceOrder(model.numBoolVars)
                OpenBranching.Activity -> HeuristicBooleanBranching(Vsids(), model.numBoolVars)
            }
            val run = session.openConditionedRun(model.numBoolVars,
                SearchSolveParams(maxDecisions = state.remainingDecisions().coerceAtLeast(0),
                    restart = params.sharedRestart?.let(SearchRestart::Every) ?: SearchRestart.Never),
                nodePolicy = policy, booleanBranching = branching, candidateHints = hints(scope.stop, state),
                observer = branching as? SearchRunObserver ?: SearchRunObserver.None)
            when (val event = run.next()) {
                is SearchRunEvent.Satisfied -> {
                    val sample = event.model.sample()
                    if (conditions.all { matches(it, sample) }) ExactNeighborhoodResult.Witness(sample)
                    else ExactNeighborhoodResult.Undecided
                }
                SearchRunEvent.Exhausted -> ExactNeighborhoodResult.Refuted
                SearchRunEvent.Paused, is SearchRunEvent.Indeterminate -> ExactNeighborhoodResult.Undecided
            }
        } finally {
            session.popTo(enclosingLevel)
        }
    }

    private fun prepared(): PlannedSearch? {
        planned?.let { return it }
        val created = open(scope.stop, state) ?: return null
        var retained = false
        try {
            created.session.attachOpenTheoryWork(state.work)
            when (created.session.initialize()) {
                ComponentResult.Consistent -> if (!scope.stop()) {
                    planned = created
                    retained = true
                    return created
                }
                is ComponentResult.Conflict -> sourceRefuted = true
                ComponentResult.Indeterminate -> Unit
            }
            return null
        } finally {
            if (!retained) created.close()
        }
    }

    private fun apply(session: SearchSession, condition: ExactNeighborhoodCondition): ComponentResult {
        for ((id, value) in condition.bools) {
            if (scope.stop() || exhausted) return ComponentResult.Indeterminate
            val result = session.push(SearchDecision.Bool(Lit.make(id, value)))
            if (result !is ComponentResult.Consistent) return result
        }
        for ((upper, bounds) in listOf(false to condition.lower, true to condition.upper)) {
            for ((coordinate, threshold) in bounds) {
                if (scope.stop() || exhausted) return ComponentResult.Indeterminate
                val term = SourceBoundTerm(if (coordinate < model.numIntVars) SearchIntValue(coordinate)
                    else SearchRealValue(coordinate - model.numIntVars), BigFraction.ONE)
                val result = assertBound(session, listOf(term), threshold, upper)
                if (result !is ComponentResult.Consistent) return result
            }
        }
        return ComponentResult.Consistent
    }

    private fun assertBound(
        session: SearchSession,
        terms: List<SourceBoundTerm>,
        threshold: BigFraction,
        upper: Boolean,
        strict: Boolean = false,
    ): ComponentResult {
        val atom = SourceBoundAtom.rationalSplit(session, terms, threshold, strict = if (upper) strict else !strict)
            ?: return ComponentResult.Indeterminate
        return session.push(SearchDecision.Theory(if (upper) atom.positive else atom.negative))
    }

    private fun cutoffPolicy(session: SearchSession, cutoff: BigFraction?): SearchNodePolicy {
        if (objective == null || cutoff == null) return SearchNodePolicy.ExpandAll
        val terms = ArrayList<SourceBoundTerm>()
        for (id in objective.intCoefficientValues.indices) {
            val coefficient = objective.intCoefficientValues[id]
            if (coefficient != 0L) terms += SourceBoundTerm(SearchIntValue(id), BigFraction.ofLong(coefficient))
        }
        for (id in objective.realCoefficientValues.indices) {
            val coefficient = objective.realCoefficientValues[id]
            if (coefficient != 0.0) terms += SourceBoundTerm(SearchRealValue(id),
                requireNotNull(BigFraction.ofDouble(coefficient)))
        }
        return object : SearchNodePolicy {
            override fun beforeBranch(context: com.eignex.klause.solver.search.SearchContext): SearchNodeDisposition {
                if ((0 until model.numBoolVars).any { context.boolValue(it) == null }) {
                    return SearchNodeDisposition.Expand
                }
                if (scope.stop() || exhausted) return SearchNodeDisposition.Indeterminate
                var threshold = cutoff - BigFraction.ofLong(objective.constant)
                for (id in objective.boolWeightValues.indices) {
                    if (context.boolValue(id) == true) threshold -= BigFraction.ofLong(objective.boolWeightValues[id])
                }
                if (terms.isEmpty()) return if (BigFraction.ZERO < threshold) SearchNodeDisposition.Expand
                    else SearchNodeDisposition.Prune
                return when (assertBound(session, terms, threshold, upper = true, strict = true)) {
                    ComponentResult.Consistent -> SearchNodeDisposition.Expand
                    is ComponentResult.Conflict -> SearchNodeDisposition.Prune
                    ComponentResult.Indeterminate -> SearchNodeDisposition.Indeterminate
                }
            }
        }
    }

    private fun AssembledSearchModel.sample(): Sample {
        val integers = List(model.numIntVars) { requireNotNull(valueOf(SearchExactIntValue(SourceIntId(it)))) }
        val reals = List(model.numRealVars) { requireNotNull(valueOf(SearchExactRealValue(SourceRealId(it)))) }
        return Sample(BooleanArray(model.numBoolVars) { requireNotNull(valueOf(SearchBoolValue(it))) },
            if (integers.all { it.fitsLong() }) LongArray(integers.size) { integers[it].toLongExact() } else LongArray(0),
            DoubleArray(reals.size) { reals[it].toDouble() }, exactReals = reals, exactInts = integers)
    }

    private fun validate(condition: ExactNeighborhoodCondition) {
        require(condition.bools.keys.all { it in 0 until model.numBoolVars })
        require((condition.lower.keys + condition.upper.keys).all { it in 0 until model.numIntVars + model.numRealVars })
        require(condition.cutoff == null || objective != null) { "a cutoff requires the source objective" }
    }

    private fun matches(condition: ExactNeighborhoodCondition, sample: Sample): Boolean {
        fun coordinate(id: Int): BigFraction = if (id < model.numIntVars) {
            BigFraction.of(sample.exactIntValue(id), BIG_ONE)
        } else {
            requireNotNull(sample.exactReals)[id - model.numIntVars]
        }
        return condition.bools.all { (id, value) -> sample.boolValue(id) == value } &&
            condition.lower.all { (id, value) -> coordinate(id) >= value } &&
            condition.upper.all { (id, value) -> coordinate(id) <= value } &&
            (condition.cutoff == null || requireNotNull(objective).evaluateExact(sample) < condition.cutoff)
    }

    override fun close() {
        if (closed) return
        closed = true
        scope.close()
        state.capture(planned?.session)
        planned?.close()
        planned = null
    }
}

private fun ComponentResult.result(): ExactNeighborhoodResult = when (this) {
    is ComponentResult.Conflict -> ExactNeighborhoodResult.Refuted
    ComponentResult.Consistent -> error("a consistent assertion has no query verdict")
    ComponentResult.Indeterminate -> ExactNeighborhoodResult.Undecided
}
