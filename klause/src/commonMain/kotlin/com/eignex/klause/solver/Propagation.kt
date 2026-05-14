package com.eignex.klause.solver

/**
 * Result of [Problem.propagate]. Either a (possibly empty) set of literals/values forced beyond
 * the input assumptions, or a sound (but incomplete) proof of infeasibility.
 */
sealed interface PropagationResult {
    /** [bools] and [ints] are disjoint from the input assumptions: only newly-forced facts. */
    data class Implied(val bools: Map<Int, Boolean>, val ints: Map<Int, Int>) : PropagationResult {
        val isEmpty: Boolean get() = bools.isEmpty() && ints.isEmpty()
    }

    /**
     * Sound, incomplete proof of infeasibility.
     *
     *  - [conflictLevels] is the set of *decision levels* involved in the conflict. For a
     *    [PropagationSession], `session.pinBool(v, value)` lives at the level it was pushed
     *    at; `seed` assigns levels `1..|assumptions|` in iteration order. Level 0 is never
     *    in the set — it represents the problem-constraint phase, not a decision.
     *  - [conflictBools] / [conflictInts] are the decision variables at those levels. They
     *    are derived from [conflictLevels] for convenience; CSP-style DFS samplers typically
     *    read [conflictLevels] directly to compute their backjump target.
     *
     *  The conflict subset is jointly unsatisfiable but not guaranteed minimal — callers must
     *  not assume minimality. An empty result means the contradiction was implied by problem
     *  constraints alone (no input was load-bearing).
     */
    data class Unsat(
        val conflictBools: Set<Int> = emptySet(),
        val conflictInts: Set<Int> = emptySet(),
        val conflictLevels: Set<Int> = emptySet(),
    ) : PropagationResult
}

/**
 * Mutable working state passed to [Factor.propagate]. Tracks the currently-known pinned bool
 * values and the (tightened) int domains, plus a **decision level** per pinned variable for
 * conflict-driven backjumping.
 *
 *  - Decisions (external pins from the driver / session) bump the level monotonically.
 *  - Implied pins (from factor propagation) inherit the maximum level of the variables the
 *    factor reads — i.e. the deepest decision that contributed.
 *  - On contradiction, the set of decision levels touched by the failing factor is what the
 *    driver reports as [PropagationResult.Unsat.conflictLevels].
 *
 *  Factors don't see the level machinery directly — they keep calling `pinBool` /
 *  `tightenIntMin` / `tightenIntMax` / `setInt` as before. The driver sets [currentLevel]
 *  to the inherited level before each factor invocation; mutators read it.
 */
class PropagationState(
    val problem: Problem,
    assumptions: Assumptions,
) {
    /** Per-bool current pin; `null` means unassigned. */
    val boolValues: Array<Boolean?> = arrayOfNulls(problem.numBoolVars)

    /** Per-int current domain (copy of [Problem.intDomains], narrowed as propagation proceeds). */
    val intDomains: Array<IntDomain> = Array(problem.numIntVars) { problem.intDomains[it] }

    /** Vars whose pin/domain changed since the driver last drained them. */
    private val dirtyBools: ArrayDeque<Int> = ArrayDeque()
    private val dirtyInts: ArrayDeque<Int> = ArrayDeque()

    /** False iff seeding the assumptions themselves already produced a contradiction. */
    var seeded: Boolean = true
        private set

    // Decision-level plumbing. ---------------------------------------------------------------

    /** Decision level when each bool was first pinned (-1 = unpinned). */
    val boolLevel: IntArray = IntArray(problem.numBoolVars) { -1 }
    /** Deepest decision level contributing to this int var's current domain (-1 = untouched). */
    val intLevel: IntArray = IntArray(problem.numIntVars) { -1 }

    /**
     * Decision-var encoded per level: index `lvl-1` holds either a bool var id (0..numBoolVars-1)
     * or a shifted int var id (numBoolVars + intVar). Grows as decisions are pushed.
     */
    private val levelToDecisionVar: ArrayDeque<Int> = ArrayDeque()

    /** Number of decisions pushed so far. Equals the maximum level. */
    val numDecisions: Int get() = levelToDecisionVar.size

    /** Level any pin created during the current factor invocation inherits. Set by the driver. */
    internal var currentLevel: Int = 0

    /** Populated on contradiction; the driver reads it to form [PropagationResult.Unsat]. */
    internal var conflictLevels: MutableSet<Int>? = null

    init {
        seedLoop@ for ((v, b) in assumptions.bools) {
            if (!pinBoolAsDecision(v, b)) { seeded = false; break@seedLoop }
        }
        if (seeded) {
            for ((v, i) in assumptions.ints) {
                if (!setIntAsDecision(v, i)) { seeded = false; break }
            }
        }
    }

    /**
     * Push a bool var as a new decision: bumps the level and pins it. Used by the driver to
     * seed input assumptions and by [PropagationSession] to push branches.
     */
    fun pinBoolAsDecision(v: Int, value: Boolean): Boolean {
        levelToDecisionVar.addLast(v)
        currentLevel = levelToDecisionVar.size
        return pinBoolImpl(v, value)
    }

    /** Push an int var as a new decision. */
    fun setIntAsDecision(v: Int, value: Int): Boolean {
        levelToDecisionVar.addLast(problem.numBoolVars + v)
        currentLevel = levelToDecisionVar.size
        return setIntImpl(v, value)
    }

    fun pinBool(v: Int, value: Boolean): Boolean = pinBoolImpl(v, value)
    fun tightenIntMin(v: Int, lo: Int): Boolean = tightenIntMinImpl(v, lo)
    fun tightenIntMax(v: Int, hi: Int): Boolean = tightenIntMaxImpl(v, hi)
    fun setInt(v: Int, value: Int): Boolean = setIntImpl(v, value)

    private fun pinBoolImpl(v: Int, value: Boolean): Boolean {
        val cur = boolValues[v]
        if (cur != null) {
            if (cur == value) return true
            // Conflict — record levels of both contributors.
            recordConflictLevels(boolLevel[v], currentLevel)
            return false
        }
        boolValues[v] = value
        boolLevel[v] = currentLevel
        dirtyBools.addLast(v)
        return true
    }

    private fun tightenIntMinImpl(v: Int, lo: Int): Boolean {
        val d = intDomains[v]
        if (lo <= d.min) return true
        if (lo > d.max) {
            recordConflictLevels(intLevel[v], currentLevel)
            return false
        }
        intDomains[v] = IntDomain(lo, d.max)
        intLevel[v] = maxOf(intLevel[v], currentLevel)
        dirtyInts.addLast(v)
        return true
    }

    private fun tightenIntMaxImpl(v: Int, hi: Int): Boolean {
        val d = intDomains[v]
        if (hi >= d.max) return true
        if (hi < d.min) {
            recordConflictLevels(intLevel[v], currentLevel)
            return false
        }
        intDomains[v] = IntDomain(d.min, hi)
        intLevel[v] = maxOf(intLevel[v], currentLevel)
        dirtyInts.addLast(v)
        return true
    }

    private fun setIntImpl(v: Int, value: Int): Boolean =
        tightenIntMinImpl(v, value) && tightenIntMaxImpl(v, value)

    private fun recordConflictLevels(a: Int, b: Int) {
        val s = HashSet<Int>()
        if (a > 0) s.add(a)
        if (b > 0) s.add(b)
        conflictLevels = s
    }

    /** Pop one bool var that's been dirtied since the last call, or `-1` if none. */
    fun pollDirtyBool(): Int = if (dirtyBools.isEmpty()) -1 else dirtyBools.removeFirst()

    /** Pop one int var that's been dirtied since the last call, or `-1` if none. */
    fun pollDirtyInt(): Int = if (dirtyInts.isEmpty()) -1 else dirtyInts.removeFirst()

    /** Max decision level of any variable in [boolVars] / [intVars]. Used by the driver to
     *  set [currentLevel] before each factor invocation. */
    fun maxLevelForVars(boolVars: IntArray, intVars: IntArray): Int {
        var max = 0
        for (v in boolVars) { val l = boolLevel[v]; if (l > max) max = l }
        for (v in intVars) { val l = intLevel[v]; if (l > max) max = l }
        return max
    }

    /** Collect every decision level touched by [boolVars] / [intVars] — the factor's view of
     *  who's responsible. Used when a factor returns `false` without explicitly setting
     *  [conflictLevels]. */
    fun collectLevelsForVars(boolVars: IntArray, intVars: IntArray): Set<Int> {
        val out = HashSet<Int>()
        for (v in boolVars) { val l = boolLevel[v]; if (l > 0) out.add(l) }
        for (v in intVars) { val l = intLevel[v]; if (l > 0) out.add(l) }
        return out
    }

    /** Decode [levels] (a subset of pushed decision levels) into the bool decision vars at
     *  those levels. */
    internal fun extractConflictBools(levels: Set<Int>): Set<Int> {
        if (levels.isEmpty()) return emptySet()
        val out = HashSet<Int>()
        for (lvl in levels) {
            if (lvl <= 0 || lvl > levelToDecisionVar.size) continue
            val encoded = levelToDecisionVar[lvl - 1]
            if (encoded < problem.numBoolVars) out.add(encoded)
        }
        return out
    }

    /** Decode [levels] into the int decision vars at those levels. */
    internal fun extractConflictInts(levels: Set<Int>): Set<Int> {
        if (levels.isEmpty()) return emptySet()
        val out = HashSet<Int>()
        for (lvl in levels) {
            if (lvl <= 0 || lvl > levelToDecisionVar.size) continue
            val encoded = levelToDecisionVar[lvl - 1]
            if (encoded >= problem.numBoolVars) out.add(encoded - problem.numBoolVars)
        }
        return out
    }
}

/** floor(a / b) with correct handling of negative operands. */
internal fun floorDivLong(a: Long, b: Long): Long {
    val q = a / b
    return if (a % b != 0L && (a xor b) < 0L) q - 1 else q
}

/** ceil(a / b) with correct handling of negative operands. */
internal fun ceilDivLong(a: Long, b: Long): Long {
    val q = a / b
    return if (a % b != 0L && (a xor b) >= 0L) q + 1 else q
}
