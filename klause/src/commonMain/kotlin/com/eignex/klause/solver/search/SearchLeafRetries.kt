package com.eignex.klause.solver.search

internal class SearchLeafRetries(
    private val session: SearchSession,
    private val root: () -> Int,
    private val canCommit: () -> Boolean,
    private val committed: (SearchDecision) -> Boolean,
    private val pause: () -> Boolean,
    private val cancelled: () -> SearchRunEvent,
    private val observer: SearchRunObserver,
    private val openRun: () -> SearchRun,
    private val maximumLeaves: Int = 4096,
    private val maximumDecisions: Int = 65_536,
) {
    private val prefixes = LinkedHashSet<List<SearchDecision>>()
    private var retainedDecisions = 0
    private var selected: List<SearchDecision>? = null
    private var position = 0
    private var rootReady = false
    private var run: SearchRun? = null
    private var unresolvedCause: SearchStopCause? = null
    var unresolved = false
        private set
    var unresolvedModels = false
        private set
    val pending: Boolean get() = prefixes.isNotEmpty() || selected != null
    val propagationPending: Boolean get() = run?.learnedPropagationPending == true
    val stopCause: SearchStopCause? get() = unresolvedCause

    fun retain(): Boolean {
        val size = session.decisionLevel - root()
        if (size > maximumDecisions) return false
        val prefix = session.decisionPrefix(root())
        if (prefix in prefixes) return true
        if (prefixes.size >= maximumLeaves || size > maximumDecisions - retainedDecisions) return false
        prefixes.add(prefix)
        retainedDecisions += prefix.size
        return true
    }

    fun next(): SearchRunEvent {
        while (true) {
            if (selected == null) {
                val prefix = prefixes.firstOrNull() ?: return if (unresolved) {
                    session.executionControls.reportStop(unresolvedCause ?: SearchStopCause.RepresentationDecline)
                    SearchRunEvent.Indeterminate.Component
                } else SearchRunEvent.Exhausted
                prefixes.remove(prefix)
                retainedDecisions -= prefix.size
                selected = prefix
                session.popTo(root())
                session.executionControls.beginOperation()
                rootReady = false
                position = 0
            }
            if (session.cancelled()) return cancelled()
            if (pause()) return SearchRunEvent.Paused
            if (!rootReady) {
                when (session.propagate()) {
                    ComponentResult.Consistent -> rootReady = true
                    is ComponentResult.Conflict -> {
                        discard()
                        continue
                    }
                    ComponentResult.Indeterminate -> {
                        if (session.cancelled()) return cancelled()
                        rememberUnresolved()
                        discard()
                        continue
                    }
                }
            }
            val prefix = checkNotNull(selected)
            if (position < prefix.size) {
                if (!canCommit() || !session.executionControls.canCommitDecision()) {
                    session.markDecisionBudgetExhausted()
                    return SearchRunEvent.Indeterminate.Budget
                }
                val decision = prefix[position]
                val level = session.decisionLevel
                (observer as? SearchBranchAttemptObserver)?.onAttempt(decision, session)
                when (session.push(decision)) {
                    ComponentResult.Consistent -> {
                        position++
                        if (session.lastPushCreatedLevel() && !committed(decision)) {
                            session.markDecisionBudgetExhausted()
                            return SearchRunEvent.Indeterminate.Budget
                        }
                    }
                    is ComponentResult.Conflict -> {
                        observer.onConflict(decision)
                        discard()
                    }
                    ComponentResult.Indeterminate -> {
                        session.popTo(level)
                        if (session.cancelled()) return cancelled()
                        rememberUnresolved()
                        discard()
                    }
                }
                continue
            }
            val retry = run ?: openRun().also { run = it }
            when (val event = retry.next()) {
                is SearchRunEvent.Satisfied, SearchRunEvent.Paused -> return event
                SearchRunEvent.Exhausted -> discard()
                SearchRunEvent.Indeterminate.Component -> {
                    rememberUnresolved()
                    unresolvedModels = unresolvedModels || retry.hasUnresolvedModels
                    discard()
                }
                SearchRunEvent.Indeterminate.Budget, SearchRunEvent.Indeterminate.Cancelled -> return event
            }
        }
    }

    private fun discard() {
        run = null
        selected = null
        session.popTo(root())
    }

    fun restartSelected() {
        run = null
        rootReady = false
        position = 0
    }

    private fun rememberUnresolved() {
        unresolved = true
        unresolvedCause = unresolvedCause ?: session.executionControls.stopCause
    }

    fun clear() {
        run?.releasePendingPropagation()
        run = null
        selected = null
        prefixes.clear()
        retainedDecisions = 0
        unresolved = false
        unresolvedModels = false
        unresolvedCause = null
    }
}
