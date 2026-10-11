package com.eignex.klause.solver.search

/**
 * Resumable traversal of a [SearchSession].
 *
 * Frames contain public shared decisions only. Consequently a CP split, an integer-theory split, and a
 * future array/function/quantifier split all follow the identical push, propagation, retraction, restart,
 * and budget path.
 */
class SearchRun internal constructor(
    private val session: SearchSession,
    private val params: SearchSolveParams,
    private val booleanBranching: BooleanBranching,
    private val decisionBudget: SearchDecisionBudget,
    private val observer: SearchRunObserver,
    private val modelContinuation: SearchModelContinuation,
    private val modelPolicy: SearchModelPolicy,
    private val nodePolicy: SearchNodePolicy,
    private val lifecycle: SearchRunLifecycle,
) {
    private val frames = ArrayList<Frame>()
    private var decisions = 0L
    private var decisionsSinceRestart = 0L
    private var sawIndeterminate = false

    // Whether the last [applyLearnedConflict] call reached its backjump, so the conflict that produced it is spent.
    private var learnedApplied = false
    private var resumeAfterSolution = false
    private var lastModel: AssembledSearchModel? = null
    private var consumedModel = false
    private var terminal: SearchRunEvent? = null
    private var started = false
    private var budgetInterrupted = false
    private val cancellationPoller = SearchCancellationPoller()
    internal var pauseBeforeDecision: () -> Boolean = { false }
    private var pendingAdvance = false

    /**
     * Poll the cancellation token on a fixed cadence rather than a time-adaptive one.
     *
     * A run stops where it polls, so an adaptive cadence makes the stopping point of a *counted* budget
     * depend on machine speed. A caller pausing this run on a node budget sets this so the pause lands
     * on the same node every time. See [SearchCancellationPoller.fixedCadence].
     */
    var fixedCancellationCadence: Boolean
        get() = cancellationPoller.fixedCadence
        set(value) {
            cancellationPoller.fixedCadence = value
        }

    init {
        params.restart.beginRun()
    }

    /** Advance until the next model or terminal verdict. */
    fun next(): SearchRunEvent {
        terminal?.let { return it }
        if (!started) {
            started = true
            lifecycle.onStart(session).toEvent()?.let { return it }
        }
        lifecycle.onResume(session).toEvent()?.let { return it }
        if (pendingAdvance) {
            pendingAdvance = false
            if (!backtrack()) return stopAfterBacktrack()
        }
        if (resumeAfterSolution) {
            resumeAfterSolution = false
            when (modelContinuation) {
                SearchModelContinuation.Chronological -> if (!backtrack()) return stopAfterBacktrack()

                SearchModelContinuation.BlockAtRoot -> when (session.blockModelAtRoot(checkNotNull(lastModel))) {
                    ComponentResult.Consistent -> {
                        frames.clear()
                        consumedModel = true
                        lifecycle.onModelBlocked(session).toEvent()?.let { return it }
                    }

                    is ComponentResult.Conflict -> return finish(SearchRunEvent.Exhausted)

                    ComponentResult.Indeterminate -> return finish(SearchRunEvent.Indeterminate.Component)
                }
            }
        }
        while (true) {
            if (cancellationPoller.due()) {
                if (session.cancelled()) {
                    return lifecycle.onCancellation(session).toEvent()
                        ?: finish(SearchRunEvent.Indeterminate.Cancelled)
                }
                cancellationPoller.rearm()
            }
            when (val node = nodePolicy.beforeBranch(session)) {
                SearchNodeDisposition.Expand -> Unit

                SearchNodeDisposition.Prune -> {
                    observer.onConflict(null)
                    if (!backtrack()) return stopAfterBacktrack()
                    continue
                }

                is SearchNodeDisposition.Backjump -> {
                    observer.onConflict(null)
                    observer.onLearnedNodeBackjump()
                    when (applyLearnedConflict(node.consequence)) {
                        SearchLearnedConflictResult.Resume -> continue

                        SearchLearnedConflictResult.Exhausted -> return finish(SearchRunEvent.Exhausted)

                        SearchLearnedConflictResult.Chronological -> if (!backtrack()) return stopAfterBacktrack()

                        SearchLearnedConflictResult.Indeterminate -> {
                            return finish(SearchRunEvent.Indeterminate.Component)
                        }

                        is SearchLearnedConflictResult.Backjump -> error("learned conflict cascade did not terminate")
                    }
                    continue
                }

                SearchNodeDisposition.Indeterminate -> return finish(SearchRunEvent.Indeterminate.Component)

                // Nothing was decided at this node yet, so the frame stack resumes here unchanged.
                SearchNodeDisposition.Pause -> return SearchRunEvent.Paused
            }
            when (val alternatives = alternatives()) {
                is Alternatives.Leaf -> when (val result = session.check()) {
                    ComponentCheck.Feasible -> {
                        params.restart.onSolution()
                        val model = session.model().also {
                            lastModel = it
                            observer.onModel(it)
                        }
                        resumeAfterSolution = true
                        when (modelPolicy.onModel(model, session)) {
                            SearchModelDisposition.Surface -> return SearchRunEvent.Satisfied(model)

                            SearchModelDisposition.Indeterminate -> {
                                resumeAfterSolution = false
                                lastModel = null
                                return finish(SearchRunEvent.Indeterminate.Component)
                            }

                            SearchModelDisposition.Skip -> {
                                resumeAfterSolution = false
                                lastModel = null
                                // A model is skipped when its check could not decide it, often because the deadline
                                // cut that check short. Such a check costs far more than the nodes the polling cadence
                                // was tuned on, so a skip polls at once rather than after the cadence's next interval.
                                if (session.cancelled()) {
                                    val event = lifecycle.onCancellation(session).toEvent()
                                        ?: finish(SearchRunEvent.Indeterminate.Cancelled)
                                    if (event == SearchRunEvent.Paused) pendingAdvance = true
                                    return event
                                }
                                if (!backtrack()) return stopAfterBacktrack()
                            }

                            SearchModelDisposition.Continue -> {
                                resumeAfterSolution = false
                                when (modelContinuation) {
                                    SearchModelContinuation.Chronological -> if (!backtrack()) {
                                        return stopAfterBacktrack()
                                    }

                                    SearchModelContinuation.BlockAtRoot -> when (
                                        session.blockModelAtRoot(checkNotNull(lastModel))
                                    ) {
                                        ComponentResult.Consistent -> {
                                            frames.clear()
                                            consumedModel = true
                                            lifecycle.onModelBlocked(session).toEvent()?.let { return it }
                                        }

                                        is ComponentResult.Conflict -> return finish(SearchRunEvent.Exhausted)

                                        ComponentResult.Indeterminate -> return finish(
                                            SearchRunEvent.Indeterminate.Component,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    is ComponentCheck.Infeasible -> {
                        observer.onConflict(null)
                        when (resolveExplainedConflict(result.explanation)) {
                            LearnedOutcome.RESUMED -> {
                                if (decisionsSinceRestart > 0 && params.restart.shouldRestart(decisionsSinceRestart)) {
                                    restart()?.let { return it }
                                }
                                continue
                            }

                            LearnedOutcome.ABANDONED -> {
                                if (!backtrack()) return stopAfterBacktrack()
                                continue
                            }

                            LearnedOutcome.UNAPPLIED -> Unit
                        }
                        retainExplanation(result.explanation)
                        if (decisionsSinceRestart > 0 && params.restart.shouldRestart(decisionsSinceRestart)) {
                            restart()?.let { return it }
                            continue
                        }
                        if (!resolveConflict() && !backtrack()) {
                            return stopAfterBacktrack()
                        }
                    }

                    ComponentCheck.Indeterminate -> {
                        sawIndeterminate = true
                        if (session.cancelled()) {
                            return lifecycle.onCancellation(session).toEvent()
                                ?: finish(SearchRunEvent.Indeterminate.Cancelled)
                        }
                        if (!backtrack()) return stopAfterBacktrack(indeterminate = true)
                    }
                }

                is Alternatives.Branch -> {
                    if (session.consumeCancellationPollRequest() && session.cancelled()) {
                        return lifecycle.onCancellation(session).toEvent()
                            ?: finish(SearchRunEvent.Indeterminate.Cancelled)
                    }
                    if (alternatives.decisions.isEmpty()) {
                        if (!backtrack()) return stopAfterBacktrack()
                        continue
                    }
                    if (decisionsSinceRestart > 0 && params.restart.shouldRestart(decisionsSinceRestart)) {
                        restart()?.let { return it }
                        continue
                    }
                    frames += Frame(session.decisionLevel, alternatives.decisions)
                    when (advanceFrame()) {
                        Advance.Expanded -> Unit
                        Advance.Paused -> return SearchRunEvent.Paused
                        Advance.Budget -> return finish(SearchRunEvent.Indeterminate.Budget)
                        Advance.Exhausted -> if (!backtrack()) return stopAfterBacktrack()
                        Advance.Restart -> restart()?.let { return it }
                    }
                }
            }
        }
    }

    private fun alternatives(): Alternatives {
        booleanBranching.alternatives(session)?.let { return Alternatives.Branch(it) }
        val decisions = session.branchAlternatives()
        return if (decisions == null) Alternatives.Leaf else Alternatives.Branch(decisions)
    }

    private fun advanceFrame(): Advance {
        val frame = frames.last()
        while (frame.next < frame.decisions.size) {
            if (pauseBeforeDecision()) {
                pendingAdvance = true
                return Advance.Paused
            }
            if (decisions == params.maxDecisions) {
                session.markDecisionBudgetExhausted()
                return Advance.Budget
            }
            val level = session.decisionLevel
            val decision = frame.decisions[frame.next++]
            if (!decision.tightens(session)) continue
            if (!session.canCommitOpenTheoryDecision()) return Advance.Budget
            when (val result = session.push(decision)) {
                ComponentResult.Consistent -> {
                    if (!session.lastPushCreatedLevel()) continue
                    if (!session.recordOpenTheoryDecision(decision)) return Advance.Budget
                    decisions++
                    decisionsSinceRestart++
                    observer.onCommit(decision, session.decisionLevel)
                    if (!decisionBudget.consume()) return Advance.Budget
                    return Advance.Expanded
                }

                ComponentResult.Indeterminate -> {
                    session.popTo(level)
                    sawIndeterminate = true
                }

                is ComponentResult.Conflict -> {
                    observer.onConflict(decision)
                    val outcome = if (session.hasNativeConflictResolver) {
                        LearnedOutcome.UNAPPLIED
                    } else {
                        resolveExplainedConflict(result.explanation)
                    }
                    if (outcome == LearnedOutcome.RESUMED) return Advance.Expanded
                    if (session.decisionLevel < level) return Advance.Exhausted
                    // An abandoned learned conflict has already run its course; only an unapplied one leaves the
                    // original conflict for the chronological resolvers.
                    val unapplied = outcome == LearnedOutcome.UNAPPLIED
                    if (unapplied) retainExplanation(result.explanation)
                    session.popTo(level)
                    if (unapplied && resolveConflict()) return Advance.Expanded
                    if (frames.isEmpty()) return Advance.Exhausted
                    if (frames.last() !== frame) return Advance.Exhausted
                }
            }
        }
        frames.removeLast()
        return Advance.Exhausted
    }

    private fun SearchDecision.tightens(context: SearchContext): Boolean = when (this) {
        is SearchDecision.Bool -> context.boolValue(literal ushr 1) == null

        is SearchDecision.IntAtMost -> (context.intUpperBound(variable) ?: Long.MAX_VALUE) > upper

        is SearchDecision.IntAtLeast -> (context.intLowerBound(variable) ?: Long.MIN_VALUE) < lower

        is SearchDecision.IntEqual -> context.intLowerBound(
            variable,
        ) != value || context.intUpperBound(variable) != value

        is SearchDecision.Theory -> true
    }

    private fun backtrack(): Boolean {
        while (frames.isNotEmpty()) {
            val frame = frames.last()
            if (session.decisionLevel > frame.level) session.popTo(frame.level)
            when (advanceFrame()) {
                Advance.Expanded -> return true

                Advance.Paused -> return false

                Advance.Budget -> {
                    budgetInterrupted = true
                    return false
                }

                Advance.Exhausted -> Unit

                Advance.Restart -> return restart() == null
            }
        }
        return false
    }

    /*
     * Retain a conflict explanation the shared database does not already own elsewhere.
     *
     * A component with its own conflict analyzer keeps the clause in its own database and propagates it
     * there, so copying it here would give one clause two watch indexes and two reduction policies while
     * deducing nothing new. The shared analyzer still reaches that component's reasoning, through
     * [SearchComponent.reasonFor].
     */
    private fun retainExplanation(explanation: SearchExplanation?) {
        if (session.explanationIsOwnedElsewhere) return
        session.learn(explanation)
    }

    private fun resolveExplainedConflict(explanation: SearchExplanation?): LearnedOutcome {
        if (session.hasNativeConflictResolver) return LearnedOutcome.UNAPPLIED
        val resolution = session.explainedConflict(explanation)
        if (resolution !is SearchConflictResolution.Backjump) return LearnedOutcome.UNAPPLIED
        val result = applyLearnedConflict(resolution.conflict)
        return when {
            resumed(result) -> LearnedOutcome.RESUMED
            learnedApplied -> LearnedOutcome.ABANDONED
            else -> LearnedOutcome.UNAPPLIED
        }
    }

    private fun resolveConflict(): Boolean {
        val (resolver, resolution) = session.conflictResolution() ?: return false
        if (consumedModel && !resolver.resolvesAfterModelBlock) return false
        when (resolution) {
            SearchConflictResolution.Chronological -> return false

            SearchConflictResolution.Exhausted -> return false

            is SearchConflictResolution.Backjump -> {
                return resumed(applyLearnedConflict(resolution.conflict))
            }
        }
    }

    // Whether a learned conflict left the search to resume from its backjump level. By the time it returns, the
    // backjump has already dropped the frames above that level, so a caller that is not resumed backtracks below it.
    // That is sound when the asserted consequence was refuted, but an indeterminate propagation refuted nothing: the
    // region it covered goes unsearched, so the run cannot claim to have exhausted the space.
    private fun resumed(result: SearchLearnedConflictResult): Boolean = when (result) {
        SearchLearnedConflictResult.Resume -> true

        SearchLearnedConflictResult.Exhausted,
        SearchLearnedConflictResult.Chronological,
        -> false

        SearchLearnedConflictResult.Indeterminate -> {
            sawIndeterminate = true
            false
        }

        is SearchLearnedConflictResult.Backjump -> error("learned conflict cascade did not terminate")
    }

    private fun applyLearnedConflict(initial: SearchLearnedConflict): SearchLearnedConflictResult {
        var conflict = initial
        learnedApplied = false
        repeat(MAX_NODE_BACKJUMPS) {
            if (conflict.decisionLevel !in 0..session.decisionLevel) {
                return SearchLearnedConflictResult.Chronological
            }
            learnedApplied = true
            params.restart.recordConflict(conflict.lbd, session.decisionLevel)
            observer.onLearnedConflict(conflict)
            session.popTo(conflict.decisionLevel)
            while (frames.isNotEmpty() && frames.last().level >= conflict.decisionLevel) frames.removeLast()
            when (val result = conflict.apply(session)) {
                SearchLearnedConflictResult.Resume,
                SearchLearnedConflictResult.Exhausted,
                SearchLearnedConflictResult.Chronological,
                SearchLearnedConflictResult.Indeterminate,
                -> return result

                is SearchLearnedConflictResult.Backjump -> conflict = result.conflict
            }
        }
        return SearchLearnedConflictResult.Chronological
    }

    private fun exhausted(): SearchRunEvent = if (sawIndeterminate) {
        SearchRunEvent.Indeterminate.Component
    } else {
        SearchRunEvent.Exhausted
    }

    private fun finish(event: SearchRunEvent): SearchRunEvent {
        terminal = event
        return event
    }

    private fun stopAfterBacktrack(indeterminate: Boolean = false): SearchRunEvent {
        if (pendingAdvance) return SearchRunEvent.Paused
        // An exhausted frame stack has no pending region for a later slice to resume.
        if (frames.isEmpty() && !budgetInterrupted) return finish(exhausted())
        if (session.cancelled()) {
            return lifecycle.onCancellation(session).toEvent()
                ?: finish(SearchRunEvent.Indeterminate.Cancelled)
        }
        return when {
            budgetInterrupted -> finish(SearchRunEvent.Indeterminate.Budget)
            indeterminate -> finish(SearchRunEvent.Indeterminate.Component)
            else -> finish(exhausted())
        }
    }

    private fun restart(): SearchRunEvent? = when (session.restart()) {
        ComponentResult.Consistent -> {
            frames.clear()
            params.restart.onRestart()
            observer.onRestart(decisionsSinceRestart)
            decisionsSinceRestart = 0L
            params.restart.beginRun()
            lifecycle.onRestart(session).toEvent()
        }

        is ComponentResult.Conflict -> finish(SearchRunEvent.Exhausted)

        ComponentResult.Indeterminate -> if (session.cancelled()) {
            lifecycle.onCancellation(session).toEvent() ?: finish(SearchRunEvent.Indeterminate.Cancelled)
        } else {
            finish(SearchRunEvent.Indeterminate.Component)
        }
    }

    private fun SearchRunDisposition.toEvent(): SearchRunEvent? = when (this) {
        SearchRunDisposition.Continue -> null
        SearchRunDisposition.Exhausted -> finish(exhausted())
        SearchRunDisposition.Pause -> SearchRunEvent.Paused
        SearchRunDisposition.Indeterminate -> finish(SearchRunEvent.Indeterminate.Component)
    }

    /** Reset traversal state after its components have been reseeded at root. */
    fun reset() {
        check(session.decisionLevel == 0) { "a search run may only reset at its root" }
        frames.clear()
        decisions = 0L
        decisionsSinceRestart = 0L
        sawIndeterminate = false
        resumeAfterSolution = false
        lastModel = null
        consumedModel = false
        terminal = null
        pendingAdvance = false
        started = false
        cancellationPoller.reset()
        params.restart.beginRun()
    }

    private data class Frame(val level: Int, val decisions: List<SearchDecision>, var next: Int = 0)

    private sealed interface Alternatives {
        data object Leaf : Alternatives
        data class Branch(val decisions: List<SearchDecision>) : Alternatives
    }

    private enum class Advance {
        Expanded,
        Paused,
        Exhausted,
        Budget,
        Restart,
    }

    private companion object {
        const val MAX_NODE_BACKJUMPS = 64
    }
}

/** Charges successful shared decisions to an optional solve-wide allowance. */
fun interface SearchDecisionBudget {
    /** Record one decision and return whether traversal may continue. */
    fun consume(): Boolean

    /** No additional solve-wide decision limit. */
    data object Unlimited : SearchDecisionBudget {
        override fun consume(): Boolean = true
    }
}

/** Observes shared traversal lifecycle events without owning its trail. */
interface SearchRunObserver {
    /** A shared decision was accepted at [decisionLevel]. */
    fun onCommit(decision: SearchDecision, decisionLevel: Int) {}

    /** A branch assertion or candidate leaf was refuted. */
    fun onConflict(decision: SearchDecision?) {}

    /** A node policy supplied an asserting learned consequence. */
    fun onLearnedNodeBackjump() {}

    /**
     * A component produced a learned consequence before the runner retracts to its asserting level.
     *
     * The observer may retain analysis metadata for mode-specific reporting, such as projecting an
     * assumption core. Restart feedback remains owned by [SearchRun].
     */
    fun onLearnedConflict(conflict: SearchLearnedConflict) {}

    /** The runner returned the session to root after [decisions] decisions in the completed run. */
    fun onRestart(decisions: Long) {}

    /** The session assembled a complete model. */
    fun onModel(model: AssembledSearchModel) {}

    /** No-op lifecycle observer. */
    data object None : SearchRunObserver
}

/** Supplies Boolean alternatives before component-owned residual splits. */
fun interface BooleanBranching {
    /** Exhaustive alternatives for one unassigned Boolean, or null when Boolean branching is complete. */
    fun alternatives(context: SearchContext): List<SearchDecision>?

    /** Source-order Boolean variables with false before true. */
    class SourceOrder(private val numBoolVars: Int) : BooleanBranching {
        override fun alternatives(context: SearchContext): List<SearchDecision>? =
            (0 until numBoolVars).firstOrNull { context.boolValue(it) == null }?.let { variable ->
                listOf(SearchDecision.Bool((variable shl 1) or 1), SearchDecision.Bool(variable shl 1))
            }
    }

    /** Leave Boolean selection to a component-owned [SearchBrancher]. */
    data object None : BooleanBranching {
        override fun alternatives(context: SearchContext): List<SearchDecision>? = null
    }
}

/** A model or terminal verdict from a resumable [SearchRun]. */
sealed interface SearchRunEvent {
    /** A complete model; call [SearchRun.next] again to continue enumeration. */
    data class Satisfied(
        /** The complete model assembled by the participating search components. */
        val model: AssembledSearchModel,
    ) : SearchRunEvent

    /** Every branch was refuted. */
    data object Exhausted : SearchRunEvent

    /** The mode yielded cooperatively and may resume by calling [SearchRun.next]. */
    data object Paused : SearchRunEvent

    /** Cancellation, a limit, or a component prevented an exact verdict. */
    sealed interface Indeterminate : SearchRunEvent {
        /** A component could not decide a candidate leaf exactly. */
        data object Component : Indeterminate

        /** The shared decision allowance was spent. */
        data object Budget : Indeterminate

        /** The solve cancellation token fired. */
        data object Cancelled : Indeterminate
    }
}

/** Terminal result of [SearchSession.solveBoolean]. */
sealed interface BooleanSearchResult {
    /**
     * Every component accepted the complete shared assignment.
     *
     * @property model Values contributed by every active component.
     */
    data class Satisfied(val model: AssembledSearchModel) : BooleanSearchResult

    /** Every complete Boolean assignment was refuted. */
    data object Exhausted : BooleanSearchResult

    /** A component or a solve-wide limit prevented an exact verdict. */
    data object Indeterminate : BooleanSearchResult
}

/** Terminal result of [SearchSession.solve]. */
sealed interface SearchResult {
    /**
     * Every component accepted the complete shared assignment.
     *
     * @property model Values contributed by every active component.
     */
    data class Satisfied(val model: AssembledSearchModel) : SearchResult

    /** Every component-provided branch was refuted. */
    data object Exhausted : SearchResult

    /** A component or a solve-wide limit prevented an exact verdict. */
    data object Indeterminate : SearchResult
}

/* What one learned conflict did to the run. */
private enum class LearnedOutcome {
    /* The search resumes from the backjump level. */
    RESUMED,

    /* The conflict was never applied; the original conflict is still live. */
    UNAPPLIED,

    /* The backjump ran but did not resume, so the caller backtracks below it. */
    ABANDONED,
}
