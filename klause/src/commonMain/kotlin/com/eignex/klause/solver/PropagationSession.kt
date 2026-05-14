package com.eignex.klause.solver

/** Variable kind discriminator for [PropagationSession.popUntilUnpinned]. */
enum class VarKind { Bool, Int }

/**
 * Stateful propagator with a decision trail. Push pins via [pinBool] / [pinInt]; each push
 * increments [decisionLevel] and propagates. On [PropagationResult.Unsat], the result carries
 * `conflictLevels` — the set of decision levels involved in the conflict — so a CSP-style
 * DFS sampler can pop directly to the deepest non-conflicting level.
 *
 * Typical DFS loop:
 * ```
 * val s = PropagationSession(problem)
 * while (!allAssigned()) {
 *   val (v, value) = chooseDecision()
 *   when (val r = s.pinBool(v, value)) {
 *     is Implied -> continue
 *     is Unsat -> {
 *       val target = (r.conflictLevels.maxOrNull() ?: 0) - 1
 *       s.popToLevel(maxOf(0, target))
 *       // try alternate value at the next decision
 *     }
 *   }
 * }
 * ```
 *
 * This first iteration re-propagates on every push/pop (it builds a fresh [PropagationState]
 * each time via [Problem.propagate]). The API is the final shape; incremental fixpoint reuse
 * is documented as a TODO in the README. Not thread-safe.
 */
class PropagationSession(val problem: Problem) {
    private val pinnedBools: LinkedHashMap<Int, Boolean> = LinkedHashMap()
    private val pinnedInts: LinkedHashMap<Int, Int> = LinkedHashMap()
    /** Decision trail: each entry is a (kind, var) pair in push order; index = level - 1. */
    private val trail: ArrayDeque<Pair<VarKind, Int>> = ArrayDeque()
    /** Most recent implied set, kept so push returns only newly-forced facts. */
    private var lastImplied: PropagationResult.Implied =
        PropagationResult.Implied(emptyMap(), emptyMap())

    /** Current decision level — number of pins on the trail. 0 = no decisions. */
    val decisionLevel: Int get() = trail.size

    /**
     * Seed with an initial assumption set. Clears any prior trail. Each assumption is
     * assigned its own decision level in iteration order (bools first, then ints).
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

    /** Push one bool pin at a fresh decision level. */
    fun pinBool(v: Int, value: Boolean): PropagationResult {
        if (pinnedBools[v] == value) {
            return PropagationResult.Implied(emptyMap(), emptyMap())
        }
        pinnedBools[v] = value
        trail.addLast(VarKind.Bool to v)
        return runPropagate()
    }

    /** Push one int pin at a fresh decision level. */
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
        runPropagate()
    }

    /**
     * Pop until [decisionLevel] equals [level]. The pop is unconditional — even if the
     * popped pins were not in any conflict. Used by DFS samplers to backjump.
     *
     * Throws [IllegalArgumentException] if [level] exceeds the current level.
     */
    fun popToLevel(level: Int) {
        require(level >= 0 && level <= trail.size) {
            "popToLevel($level): out of range [0, ${trail.size}]"
        }
        while (trail.size > level) {
            val (kind, v) = trail.removeLast()
            when (kind) {
                VarKind.Bool -> pinnedBools.remove(v)
                VarKind.Int -> pinnedInts.remove(v)
            }
        }
        lastImplied = PropagationResult.Implied(emptyMap(), emptyMap())
        runPropagate()
    }

    /** Pop until [v] of [kind] is no longer pinned. No-op if [v] is already unpinned. */
    fun popUntilUnpinned(kind: VarKind, v: Int) {
        val pinned = when (kind) {
            VarKind.Bool -> pinnedBools.containsKey(v)
            VarKind.Int -> pinnedInts.containsKey(v)
        }
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

    /** The (kind, var) decision at [level] (1-based), or `null` if [level] is out of range. */
    fun decisionAt(level: Int): Pair<VarKind, Int>? =
        if (level in 1..trail.size) trail.elementAt(level - 1) else null

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
