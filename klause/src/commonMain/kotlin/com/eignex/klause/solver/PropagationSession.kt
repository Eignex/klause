package com.eignex.klause.solver

/** Variable kind discriminator for [PropagationSession.popUntilUnpinned]. */
enum class VarKind { Bool, Int }

/**
 * Stateful propagator: builds a fixpoint incrementally as the caller pins/unpins variables.
 * Mirrors the SAT-solver "trail" abstraction — push assumptions, propagate, pop on
 * backtrack. The caller never sees the trail directly; it interacts through pin/unpin and
 * gets back [PropagationResult] for each push.
 *
 * This first iteration uses a *full re-propagation* on each push/pop — simpler and obviously
 * sound, slower than the reason-graph invalidation described in the design doc. The
 * consumer-visible API matches the eventual incremental version; only the engine underneath
 * is naive. Optimising to incremental fixpoint reuse (only revisit factors whose vars
 * changed; pop via reason-graph) is a follow-up.
 *
 * Not thread-safe. One session per search.
 */
class PropagationSession(val problem: Problem) {
    private val pinnedBools: LinkedHashMap<Int, Boolean> = LinkedHashMap()
    private val pinnedInts: LinkedHashMap<Int, Int> = LinkedHashMap()
    /** Push order — used by [popLast] to identify the most recent pin. */
    private val trail: ArrayDeque<Pair<VarKind, Int>> = ArrayDeque()
    /** Most recent implied set, kept so push returns only newly-forced facts. */
    private var lastImplied: PropagationResult.Implied =
        PropagationResult.Implied(emptyMap(), emptyMap())

    /**
     * Seed with an initial assumption set. Clears any prior trail. Returns the implied set
     * for the seed (or [PropagationResult.Unsat] if the seed itself is infeasible).
     */
    fun seed(assumptions: Assumptions): PropagationResult {
        pinnedBools.clear()
        pinnedInts.clear()
        trail.clear()
        lastImplied = PropagationResult.Implied(emptyMap(), emptyMap())
        for ((v, b) in assumptions.bools) {
            pinnedBools[v] = b; trail.addLast(VarKind.Bool to v)
        }
        for ((v, i) in assumptions.ints) {
            pinnedInts[v] = i; trail.addLast(VarKind.Int to v)
        }
        return runPropagate()
    }

    /** Push one bool pin and propagate. Returns only the newly-forced facts. */
    fun pinBool(v: Int, value: Boolean): PropagationResult {
        if (pinnedBools[v] == value) {
            // Idempotent push; no new state, no new facts.
            return PropagationResult.Implied(emptyMap(), emptyMap())
        }
        pinnedBools[v] = value
        trail.addLast(VarKind.Bool to v)
        return runPropagate()
    }

    /** Push one int pin and propagate. Returns only the newly-forced facts. */
    fun pinInt(v: Int, value: Int): PropagationResult {
        if (pinnedInts[v] == value) {
            return PropagationResult.Implied(emptyMap(), emptyMap())
        }
        pinnedInts[v] = value
        trail.addLast(VarKind.Int to v)
        return runPropagate()
    }

    /**
     * Pop the most-recently-pushed pin and re-propagate. No-op if the trail is empty.
     * Resets `lastImplied` so the next push reports its implied facts in full.
     */
    fun popLast() {
        if (trail.isEmpty()) return
        val (kind, v) = trail.removeLast()
        when (kind) {
            VarKind.Bool -> pinnedBools.remove(v)
            VarKind.Int -> pinnedInts.remove(v)
        }
        lastImplied = PropagationResult.Implied(emptyMap(), emptyMap())
        // Re-propagate to refresh internal state. Result intentionally discarded.
        runPropagate()
    }

    /** Pop until [v] of [kind] is no longer pinned. No-op if [v] is already unpinned. */
    fun popUntilUnpinned(kind: VarKind, v: Int) {
        val pinned = when (kind) { VarKind.Bool -> pinnedBools.containsKey(v); VarKind.Int -> pinnedInts.containsKey(v) }
        if (!pinned) return
        while (trail.isNotEmpty()) {
            val top = trail.last()
            popLast()
            if (top.first == kind && top.second == v) break
        }
    }

    /** Snapshot the current pin set as an [Assumptions]. Maps are fresh copies. */
    fun currentAssumptions(): Assumptions =
        Assumptions(bools = pinnedBools.toMap(), ints = pinnedInts.toMap())

    private fun runPropagate(): PropagationResult {
        val asm = Assumptions(bools = pinnedBools.toMap(), ints = pinnedInts.toMap())
        val r = problem.propagate(asm)
        return when (r) {
            is PropagationResult.Unsat -> r
            is PropagationResult.Implied -> {
                val newBools = r.bools.filter { (k, vNew) -> lastImplied.bools[k] != vNew }
                val newInts = r.ints.filter { (k, vNew) -> lastImplied.ints[k] != vNew }
                lastImplied = r
                PropagationResult.Implied(newBools, newInts)
            }
        }
    }
}
