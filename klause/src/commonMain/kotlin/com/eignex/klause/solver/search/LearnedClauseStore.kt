package com.eignex.klause.solver.search

import com.eignex.klause.util.IntArrayList

/**
 * Watch-indexed store for the clauses a [SearchSession] has learned.
 *
 * Each retained clause keeps its watched literals at positions zero and one, so an assignment wakes
 * only the clauses watching the literal it falsified. A clause is immutable as a set of literals while
 * its literal *order* is owned by the watch machinery, which is why callers read literals through
 * [literalsAt] and never retain the array.
 */
internal class LearnedClauseStore {
    private val clauses = ArrayList<IntArray>()
    private val sources = ArrayList<SearchComponent?>()
    private val lbds = IntArrayList()
    private val used = ArrayList<Boolean>()
    private val watchers = HashMap<Int, IntArrayList>()
    private val keys = HashSet<List<Int>>()
    private val unitClauses = IntArrayList()

    /** Number of retained clauses. */
    val size: Int get() = clauses.size

    /** Indices of the retained clauses that hold a single literal. */
    val units: IntArrayList get() = unitClauses

    /** Literals of clause [index], watched literals first. */
    fun literalsAt(index: Int): IntArray = clauses[index]

    /** Component that owns an equivalent native constraint for clause [index], if any. */
    fun sourceAt(index: Int): SearchComponent? = sources[index]

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
    fun add(literals: IntArray, source: SearchComponent?, lbd: Int): Int {
        if (literals.isEmpty() || !keys.add(literals.sorted())) return -1
        val index = clauses.size
        clauses.add(literals)
        sources.add(source)
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
     */
    fun retain(keep: (Int) -> Boolean) {
        var target = 0
        for (index in clauses.indices) {
            if (!keep(index)) continue
            clauses[target] = clauses[index]
            sources[target] = sources[index]
            lbds[target] = lbds[index]
            used[target] = used[index]
            target++
        }
        if (target == clauses.size) return
        clauses.subList(target, clauses.size).clear()
        sources.subList(target, sources.size).clear()
        used.subList(target, used.size).clear()
        lbds.truncateTo(target)
        watchers.clear()
        unitClauses.clear()
        keys.clear()
        for (index in clauses.indices) {
            keys.add(clauses[index].sorted())
            watch(index)
        }
    }
}
