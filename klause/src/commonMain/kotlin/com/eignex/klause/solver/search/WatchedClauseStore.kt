package com.eignex.klause.solver.search

import com.eignex.klause.util.IntArrayList

/**
 * Assignment view and consequence sink a [WatchedClauseStore] propagates through.
 *
 * The store owns the watch invariant; the host owns what an implication or a conflict means. That
 * split is what lets one mechanism serve both the model's own clauses and the clauses a session has
 * learned, instead of each keeping its own copy of the same loop.
 */
internal interface ClauseWatchHost {
    /** Truth of [literal] under the current assignment, or null when its variable is unassigned. */
    fun truth(literal: Int): Boolean?

    /** Decision level that assigned [literal]'s variable, or 0 when it is unassigned. */
    fun levelOf(literal: Int): Int

    /** Record that clause [clause] implies [literal], which no other literal of it can now satisfy. */
    fun implied(clause: Int, literal: Int): ComponentResult

    /** Record that every literal of clause [clause] is falsified. */
    fun conflicted(clause: Int): ComponentResult
}

/**
 * Watch-indexed clause database.
 *
 * Each retained clause keeps its watched literals at positions zero and one, so an assignment wakes
 * only the clauses watching the literal it falsified. A clause is immutable as a set of literals while
 * its literal *order* is owned by the watch machinery, which is why callers read literals through
 * [literalsAt] and never retain the array.
 */
internal class WatchedClauseStore {
    private val clauses = ArrayList<IntArray>()
    private val lbds = IntArrayList()
    private val used = ArrayList<Boolean>()
    private val watchers = HashMap<Int, IntArrayList>()
    private val signatures = HashMap<Int, IntArrayList>()
    private val unitClauses = IntArrayList()

    /** Number of retained clauses. */
    val size: Int get() = clauses.size

    /** Indices of the retained clauses that hold a single literal. */
    val units: IntArrayList get() = unitClauses

    /** Literals of clause [index], watched literals first. */
    fun literalsAt(index: Int): IntArray = clauses[index]

    /** Literal block distance recorded for clause [index] when it was learned. */
    fun lbdAt(index: Int): Int = lbds[index]

    /** Whether clause [index] has implied or conflicted since the last reduction. */
    fun usedAt(index: Int): Boolean = used[index]

    /** Record that clause [index] implied a literal or derived a conflict. */
    fun markUsed(index: Int) {
        used[index] = true
    }

    /** Clauses watching [literal], or null when no clause does. */
    fun watchersOf(literal: Int): IntArrayList? = watchers[literal]

    /** Start watching [literal] in clause [index]. */
    fun addWatcher(literal: Int, index: Int) {
        watchers.getOrPut(literal) { IntArrayList() }.add(index)
    }

    /**
     * Retain [literals] unwatched, or return -1 when an equivalent clause is already stored.
     *
     * Watching is a separate step because the two literals a clause must watch depend on the
     * assignment as it stands when the clause is first examined, not when it was learned.
     */
    fun add(literals: IntArray, lbd: Int): Int {
        if (literals.isEmpty()) return -1
        val bucket = signatures.getOrPut(signatureOf(literals)) { IntArrayList(1) }
        for (position in 0 until bucket.size) {
            if (sameLiterals(clauses[bucket[position]], literals)) return -1
        }
        val index = clauses.size
        bucket.add(index)
        clauses.add(literals)
        lbds.add(lbd)
        used.add(false)
        return index
    }

    /** Watch clause [index] on the literals currently held at its first two positions. */
    fun watch(index: Int) {
        val literals = clauses[index]
        addWatcher(literals[0], index)
        if (literals.size == 1) unitClauses.add(index) else addWatcher(literals[1], index)
    }

    /** Clear every use flag after a reduction has selected its survivors. */
    fun clearUsed() {
        for (index in used.indices) used[index] = false
    }

    /**
     * Drop every clause [keep] rejects and rebuild the watch index over the survivors.
     *
     * Watch positions survive the move, so a rebuild neither re-examines assignments nor invalidates
     * the invariant that a clause's watched literals are the last of it to be falsified.
     *
     * [keep] is called while survivors are being compacted into place, so it must decide from data it
     * already holds rather than by reading this store.
     */
    fun retain(keep: (Int) -> Boolean) {
        var target = 0
        for (index in clauses.indices) {
            if (!keep(index)) continue
            clauses[target] = clauses[index]
            lbds[target] = lbds[index]
            used[target] = used[index]
            target++
        }
        if (target == clauses.size) return
        clauses.subList(target, clauses.size).clear()
        used.subList(target, used.size).clear()
        lbds.truncateTo(target)
        watchers.clear()
        unitClauses.clear()
        signatures.clear()
        for (index in clauses.indices) {
            signatures.getOrPut(signatureOf(clauses[index])) { IntArrayList(1) }.add(index)
            watch(index)
        }
    }

    /**
     * Order-insensitive fingerprint of [literals], so a permutation of a stored clause lands in the
     * same bucket. Literals are avalanched before being summed, which keeps sets that share a sum of
     * raw values apart.
     */
    private fun signatureOf(literals: IntArray): Int {
        var accumulated = 0
        for (literal in literals) accumulated += avalanche(literal)
        return accumulated * SIGNATURE_SIZE_FACTOR + literals.size
    }

    private fun avalanche(value: Int): Int {
        var mixed = value xor (value ushr SHIFT_HIGH)
        mixed *= MIX_FIRST
        mixed = mixed xor (mixed ushr SHIFT_MID)
        mixed *= MIX_SECOND
        return mixed xor (mixed ushr SHIFT_HIGH)
    }

    private fun sameLiterals(stored: IntArray, candidate: IntArray): Boolean {
        if (stored.size != candidate.size) return false
        val left = stored.copyOf().also(IntArray::sort)
        val right = candidate.copyOf().also(IntArray::sort)
        return left.contentEquals(right)
    }

    /**
     * Choose watches for clause [index] against the current assignment and evaluate it once.
     *
     * Watching is deferred to this point because the two literals a clause must watch depend on the
     * assignment as it stands when the clause is first examined, not when it was stored.
     */
    fun attach(index: Int, host: ClauseWatchHost): ComponentResult {
        val literals = clauses[index]
        orderWatches(literals, host)
        watch(index)
        val watched = literals[0]
        val watchedTruth = host.truth(watched)
        if (watchedTruth == true) return ComponentResult.Consistent
        if (watchedTruth == false) return conflictOn(index, host)
        if (literals.size > 1 && host.truth(literals[1]) != false) return ComponentResult.Consistent
        return implyFrom(index, watched, host)
    }

    /**
     * Re-examine every single-literal clause.
     *
     * A unit clause is woken by no assignment, so a caller must run this whenever a retraction may
     * have unassigned one of their literals. Returns the index that stopped the pass, or -1 when all
     * units were examined, so the caller can keep the remainder pending.
     */
    fun propagateUnits(host: ClauseWatchHost): ComponentResult {
        unitClauses.forEach { index ->
            val literal = clauses[index][0]
            when (host.truth(literal)) {
                true -> Unit

                false -> return conflictOn(index, host)

                null -> {
                    val result = implyFrom(index, literal, host)
                    if (result !is ComponentResult.Consistent) return result
                }
            }
        }
        return ComponentResult.Consistent
    }

    /**
     * Visit every clause watching [falsified], restoring the two-watch invariant.
     *
     * A clause that finds a non-falsified replacement literal moves its watch and leaves this list; a
     * clause with no replacement either implies its remaining watch or is in conflict.
     */
    fun propagate(falsified: Int, host: ClauseWatchHost): ComponentResult {
        val watching = watchers[falsified] ?: return ComponentResult.Consistent
        var source = 0
        var target = 0
        var outcome: ComponentResult = ComponentResult.Consistent
        while (source < watching.size) {
            val index = watching[source++]
            val literals = clauses[index]
            if (literals[0] == falsified && literals.size > 1) {
                literals[0] = literals[1]
                literals[1] = falsified
            }
            val other = literals[0]
            if (host.truth(other) == true) {
                watching[target++] = index
                continue
            }
            val replacement = replacementWatch(literals, host)
            if (replacement > 0) {
                literals[1] = literals[replacement]
                literals[replacement] = falsified
                addWatcher(literals[1], index)
                continue
            }
            watching[target++] = index
            outcome = if (host.truth(other) == false) {
                conflictOn(index, host)
            } else {
                implyFrom(index, other, host)
            }
            if (outcome !is ComponentResult.Consistent) break
        }
        while (source < watching.size) watching[target++] = watching[source++]
        watching.truncateTo(target)
        return outcome
    }

    /** The clause-form explanation of clause [index], safe for the caller to retain. */
    fun explanationOf(index: Int): SearchExplanation = SearchExplanation(clauses[index].copyOf())

    /**
     * Move the two literals that must be watched to the front of [literals].
     *
     * An unassigned or satisfied literal is always preferable, and among falsified literals the one
     * assigned deepest is preferable: it is the first to be unassigned, which is what keeps a watch
     * from going stale after a backjump.
     */
    private fun orderWatches(literals: IntArray, host: ClauseWatchHost) {
        for (slot in 0 until minOf(2, literals.size)) {
            var best = slot
            for (candidate in slot + 1 until literals.size) {
                if (watchRank(literals[candidate], host) > watchRank(literals[best], host)) best = candidate
            }
            val swapped = literals[slot]
            literals[slot] = literals[best]
            literals[best] = swapped
        }
    }

    private fun watchRank(literal: Int, host: ClauseWatchHost): Int = when (host.truth(literal)) {
        true -> SATISFIED_WATCH_RANK
        null -> UNASSIGNED_WATCH_RANK
        false -> host.levelOf(literal)
    }

    private fun replacementWatch(literals: IntArray, host: ClauseWatchHost): Int {
        for (position in 2 until literals.size) {
            if (host.truth(literals[position]) != false) return position
        }
        return -1
    }

    private fun conflictOn(index: Int, host: ClauseWatchHost): ComponentResult {
        markUsed(index)
        return host.conflicted(index)
    }

    private fun implyFrom(index: Int, literal: Int, host: ClauseWatchHost): ComponentResult {
        markUsed(index)
        return host.implied(index, literal)
    }

    private companion object {
        /** Watch preference for an unassigned literal; below a satisfied one, above any level. */
        const val UNASSIGNED_WATCH_RANK = Int.MAX_VALUE - 1

        /** Watch preference for a satisfied literal, which never needs replacing. */
        const val SATISFIED_WATCH_RANK = Int.MAX_VALUE
        const val SIGNATURE_SIZE_FACTOR = 31
        const val SHIFT_HIGH = 16
        const val SHIFT_MID = 13
        const val MIX_FIRST = -2048144789
        const val MIX_SECOND = -1028477387
    }
}
