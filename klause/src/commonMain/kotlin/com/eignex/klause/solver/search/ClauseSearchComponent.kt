package com.eignex.klause.solver.search

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList

internal interface SharedClauseSelection {
    fun contains(index: Int): Boolean
    fun encoded(index: Int): Boolean
}

/**
 * Boolean clauses hosted by the shared session rather than by a frontend-specific search loop.
 *
 * The clauses live in the same [WatchedClauseStore] the session uses for what it learns, so a
 * falsified literal wakes only the clauses watching it. CP's watched-clause implementation remains
 * its specialised finite-domain mechanism; open and hybrid component sets use this one. Its clause is
 * both the propagation and the conflict explanation.
 */
class ClauseSearchComponent(clauses: Iterable<Clause>) :
    SearchComponent,
    ClauseWatchHost {
    private val store = WatchedClauseStore()
    private val sourceClauses = clauses.map(Clause::literals)
    internal var sharedSource: Problem? = null
        private set
    private var sourceSelection: SharedClauseSelection? = null

    internal fun retainSource(
        source: Problem,
        selection: SharedClauseSelection,
    ) {
        check(sharedSource == null)
        sharedSource = source
        sourceSelection = selection
    }

    internal fun releaseSource() {
        sharedSource = null
        sourceSelection = null
    }

    internal fun matchesSource(stop: Cancellation): Boolean {
        val source = sharedSource ?: return false
        val selected = sourceSelection ?: return false
        var matched = 0
        for (index in source.factors.indices) {
            stop.charge(1)
            if (stop()) return false
            if (!selected.contains(index)) continue
            val clause = source.factors[index] as? Clause ?: return false
            stop.charge(4)
            if (stop() || !selected.encoded(index)) {
                return false
            }
            if (matched >= sourceClauses.size || sourceClauses[matched] !== clause.literals) return false
            for (literal in clause.literals) {
                stop.charge(1)
                if (stop() || Lit.variable(literal) !in 0 until source.numBoolVars) return false
            }
            matched++
        }
        return matched == sourceClauses.size
    }

    internal fun ownsSourceClause(index: Int, clause: Clause, stop: Cancellation): Boolean {
        stop.charge(1)
        if (stop()) return false
        val source = sharedSource ?: return false
        if (index !in source.factors.indices || source.factors[index] !== clause ||
            sourceSelection?.contains(index) != true
        ) return false
        return true
    }

    /** Literals whose falsification has not been propagated through the watch index yet. */
    private val pending = IntArrayList()
    private var active: SearchContext? = null
    private var unitsPending = false

    override fun initialize(context: SearchContext): ComponentResult = withContext(context) {
        if (sourceClauses.any { clause -> clause.any { context.atomLiteral(SearchDecision.Bool(it)) == null } }) {
            return@withContext ComponentResult.Indeterminate
        }
        for (literals in sourceClauses) {
            val index = store.add(literals.copyOf(), lbd = literals.size)
            if (index < 0) continue
            val result = store.attach(index, this)
            if (result !is ComponentResult.Consistent) return@withContext result
        }
        drain()
    }

    override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
        if (decision !is SearchDecision.Bool) return ComponentResult.Consistent
        return withContext(context) {
            pending.add(decision.literal xor 1)
            drain()
        }
    }

    override fun propagate(context: SearchContext): ComponentResult = withContext(context) {
        // A unit clause is woken by no assignment, so it is re-examined after a retraction and at the
        // root, where a rebuilt seed can unassign its literal without any retraction at all.
        if (unitsPending || context.decisionLevel == 0) {
            unitsPending = false
            val units = store.propagateUnits(this)
            if (units !is ComponentResult.Consistent) {
                unitsPending = true
                return@withContext units
            }
        }
        drain()
    }

    override fun retract(decisionLevel: Int) {
        // Draining is synchronous, so anything still queued was interrupted by a conflict at the level
        // being left, and the retraction unassigns exactly those literals.
        pending.clear()
        unitsPending = true
    }

    override fun truth(literal: Int): Boolean? {
        val value = session().boolValue(literal ushr 1) ?: return null
        return value == (literal and 1 == 0)
    }

    /**
     * Watch preference among falsified literals is a level order, which this component cannot see.
     * A constant is sound: it only costs a watch move that a level-aware choice would have avoided.
     */
    override fun levelOf(literal: Int): Int = 0

    override fun implied(clause: Int, literal: Int): ComponentResult {
        val result = session().imply(literal, store.explanationOf(clause))
        // The session delivers an implication to every component except the one that made it, so this
        // component queues its own consequences.
        if (result is ComponentResult.Consistent) pending.add(literal xor 1)
        return result
    }

    override fun conflicted(clause: Int): ComponentResult = ComponentResult.Conflict(store.explanationOf(clause))

    private fun session(): SearchContext =
        requireNotNull(active) { "clause propagation ran outside a session callback" }

    private fun drain(): ComponentResult {
        while (!pending.isEmpty()) {
            val falsified = pending[pending.size - 1]
            pending.truncateTo(pending.size - 1)
            val result = store.propagate(falsified, this)
            if (result !is ComponentResult.Consistent) return result
        }
        return ComponentResult.Consistent
    }

    private inline fun withContext(context: SearchContext, body: () -> ComponentResult): ComponentResult {
        val previous = active
        active = context
        val result = body()
        active = previous
        return result
    }
}
