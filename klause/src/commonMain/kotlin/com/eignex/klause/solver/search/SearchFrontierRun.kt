package com.eignex.klause.solver.search

internal class SearchFrontierRun<K>(
    private val session: SearchSession,
    private val params: SearchSolveParams,
    private val booleanBranching: BooleanBranching,
    private val nodePolicy: SearchNodePolicy,
    private val decisionBudget: SearchDecisionBudget,
    private val rank: () -> K,
    private val compare: Comparator<K>,
    private val pause: () -> Boolean,
    private val revision: () -> Long = { 0L },
    private val maximumPending: Int = 4096,
) {
    private class Path(val parent: Path?, val decision: SearchDecision) {
        val depth: Int = (parent?.depth ?: 0) + 1
        var level: Int = 0
    }
    private class Entry<K>(val path: Path?, val rank: K, val sequence: Long)
    private val queue = ArrayList<Entry<K>>()
    private val unresolved = ArrayList<Entry<K>>()
    private var selected: Entry<K>? = null
    private var current: Path? = null
    private var started = false
    private var sequence = 0L
    private var observedRevision = revision()
    private var committed = 0L
    private var terminal: SearchRunEvent? = null
    var peakPending: Int = 0
        private set
    val pending: Int get() = queue.size + unresolved.size + if (selected == null) 0 else 1

    init {
        require(maximumPending > 0)
        session.executionControls.beginTraversal()
        session.observeUnassignments(booleanBranching as? SearchUnassignListener)
    }

    fun next(): SearchRunEvent {
        terminal?.let { return it }
        if (!started) {
            started = true
            enqueue(Entry(null, rank(), sequence++))
        }
        while (true) {
            if (session.cancelled()) return finish(SearchRunEvent.Indeterminate.Cancelled)
            if (pause()) return SearchRunEvent.Paused
            retryUnresolved()
            val entry = selected ?: take()?.also { selected = it } ?: return finish(
                if (unresolved.isEmpty()) SearchRunEvent.Exhausted else SearchRunEvent.Indeterminate.Component)
            restore(entry.path)?.let { return it }
            when (nodePolicy.beforeBranch(session)) {
                SearchNodeDisposition.Pause -> return SearchRunEvent.Paused
                SearchNodeDisposition.Prune -> selected = null
                SearchNodeDisposition.Indeterminate -> {
                    unresolved += Entry(entry.path, rank(), entry.sequence)
                    selected = null
                }
                is SearchNodeDisposition.Backjump -> return finish(SearchRunEvent.Indeterminate.Component)
                SearchNodeDisposition.Expand -> {
                    val alternatives = booleanBranching.alternatives(session) ?: session.branchAlternatives()
                    if (alternatives == null) {
                        return finish(SearchRunEvent.Indeterminate.Component)
                    }
                    if (alternatives.size > maximumPending - pending + 1) {
                        return finish(SearchRunEvent.Indeterminate.Budget)
                    }
                    val key = rank()
                    for (decision in alternatives) enqueue(Entry(Path(entry.path, decision), key, sequence++))
                    selected = null
                }
            }
        }
    }

    private fun retryUnresolved() {
        val changed = revision()
        if (changed == observedRevision) return
        observedRevision = changed
        while (unresolved.isNotEmpty()) enqueue(unresolved.removeAt(unresolved.lastIndex))
    }

    private fun restore(target: Path?): SearchRunEvent? {
        if (current === target) return null
        var left = current
        var right = target
        while ((left?.depth ?: 0) > (right?.depth ?: 0)) left = left?.parent
        while ((right?.depth ?: 0) > (left?.depth ?: 0)) right = right?.parent
        while (left !== right) {
            left = left?.parent
            right = right?.parent
        }
        session.popTo(left?.level ?: 0)
        current = left
        val suffix = ArrayList<Path>()
        var remaining = target
        while (remaining !== left) {
            suffix += checkNotNull(remaining)
            remaining = remaining.parent
        }
        for (path in suffix.asReversed()) {
            if (session.cancelled()) return finish(SearchRunEvent.Indeterminate.Cancelled)
            if (pause()) return SearchRunEvent.Paused
            if ((params.maxDecisions >= 0L && committed >= params.maxDecisions) ||
                !session.executionControls.canCommitDecision()) {
                session.markDecisionBudgetExhausted()
                return finish(SearchRunEvent.Indeterminate.Budget)
            }
            val level = session.decisionLevel
            if (session.push(path.decision) !is ComponentResult.Consistent) {
                session.popTo(level)
                return finish(SearchRunEvent.Indeterminate.Component)
            }
            path.level = session.decisionLevel
            current = path
            if (session.lastPushCreatedLevel()) {
                committed++
                if (!session.executionControls.recordDecision(path.decision) || !decisionBudget.consume()) {
                    session.markDecisionBudgetExhausted()
                    return finish(SearchRunEvent.Indeterminate.Budget)
                }
            }
        }
        return null
    }

    private fun enqueue(entry: Entry<K>) {
        queue += entry
        var index = queue.lastIndex
        while (index > 0) {
            val parent = (index - 1) / 2
            if (!before(entry, queue[parent])) break
            queue[index] = queue[parent]
            index = parent
        }
        queue[index] = entry
        peakPending = maxOf(peakPending, pending)
    }

    private fun take(): Entry<K>? {
        if (queue.isEmpty()) return null
        val first = queue.first()
        val last = queue.removeAt(queue.lastIndex)
        if (queue.isEmpty()) return first
        var index = 0
        while (index * 2 + 1 < queue.size) {
            var child = index * 2 + 1
            if (child + 1 < queue.size && before(queue[child + 1], queue[child])) child++
            if (!before(queue[child], last)) break
            queue[index] = queue[child]
            index = child
        }
        queue[index] = last
        return first
    }

    private fun before(left: Entry<K>, right: Entry<K>): Boolean {
        val order = compare.compare(left.rank, right.rank)
        return order < 0 || (order == 0 && left.sequence < right.sequence)
    }

    private fun finish(event: SearchRunEvent): SearchRunEvent = event.also { terminal = it }
}
