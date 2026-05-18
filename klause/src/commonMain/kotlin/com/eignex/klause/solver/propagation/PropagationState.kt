package com.eignex.klause.solver.propagation

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem

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

    /** Vars whose pin/domain changed since the driver last drained them. Primitive int
     *  ring buffers to avoid the autoboxing tax `ArrayDeque<Int>` pays on every push/poll. */
    private val dirtyBools: com.eignex.klause.util.IntArrayDeque =
        com.eignex.klause.util.IntArrayDeque(initialCapacity = problem.numBoolVars.coerceAtLeast(8))
    private val dirtyInts: com.eignex.klause.util.IntArrayDeque =
        com.eignex.klause.util.IntArrayDeque(initialCapacity = problem.numIntVars.coerceAtLeast(8))

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
     * or a shifted int var id (numBoolVars + intVar). Grows as decisions are pushed. Primitive
     * int list (no boxing on push or indexed read).
     */
    private val levelToDecisionVar: com.eignex.klause.util.IntArrayList =
        com.eignex.klause.util.IntArrayList()

    /** Number of decisions pushed so far. Equals the maximum level. */
    val numDecisions: Int get() = levelToDecisionVar.size

    /** Level any pin created during the current factor invocation inherits. Set by the driver. */
    internal var currentLevel: Int = 0

    /** Populated on contradiction; the driver reads it to form [PropagationResult.Unsat]. */
    internal var conflictLevels: MutableSet<Int>? = null

    init {
        seeded = seedAssumptions(assumptions)
    }

    /** Push every pin in [a] as a fresh decision; return `false` (so [seeded] becomes
     *  `false`) on the first contradiction. Direct primitive-array iteration so the early
     *  exit is a clean `return`. */
    private fun seedAssumptions(a: Assumptions): Boolean {
        val bk = a.boolKeys; val bv = a.boolValues
        for (i in bk.indices) {
            if (!pinBoolAsDecision(bk[i], bv[i])) return false
        }
        val ik = a.intKeys; val iv = a.intValues
        for (i in ik.indices) {
            if (!setIntAsDecision(ik[i], iv[i])) return false
        }
        return true
    }

    /**
     * Push a bool var as a new decision: bumps the level and pins it. Used by the driver to
     * seed input assumptions and by [PropagationSession] to push branches.
     */
    fun pinBoolAsDecision(v: Int, value: Boolean): Boolean {
        levelToDecisionVar.add(v)
        currentLevel = levelToDecisionVar.size
        return pinBoolImpl(v, value)
    }

    /** Push an int var as a new decision. */
    fun setIntAsDecision(v: Int, value: Int): Boolean {
        levelToDecisionVar.add(problem.numBoolVars + v)
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

    // Snapshot / restore for [PropagationSession]. Captures every mutable field so a pop
    // can rewind the state to a prior fixpoint without re-propagating. Dirty queues are not
    // snapshotted — the caller is expected to snapshot only between propagation cycles
    // (i.e. when dirty queues are empty).
    class Snapshot internal constructor(
        internal val boolValues: Array<Boolean?>,
        internal val intDomains: Array<IntDomain>,
        internal val boolLevel: IntArray,
        internal val intLevel: IntArray,
        internal val decisionVars: IntArray,
    )

    fun snapshot(): Snapshot = Snapshot(
        boolValues = boolValues.copyOf(),
        intDomains = intDomains.copyOf(),
        boolLevel = boolLevel.copyOf(),
        intLevel = intLevel.copyOf(),
        decisionVars = levelToDecisionVar.toIntArray(),
    )

    fun restore(s: Snapshot) {
        for (i in s.boolValues.indices) boolValues[i] = s.boolValues[i]
        for (i in s.intDomains.indices) intDomains[i] = s.intDomains[i]
        for (i in s.boolLevel.indices) boolLevel[i] = s.boolLevel[i]
        for (i in s.intLevel.indices) intLevel[i] = s.intLevel[i]
        levelToDecisionVar.clear()
        for (v in s.decisionVars) levelToDecisionVar.add(v)
        // Aborted pushes may have left dirty queue entries behind; drop them.
        dirtyBools.clear()
        dirtyInts.clear()
        conflictLevels = null
        currentLevel = 0
    }

    /**
     * Run propagation until no factor can derive more. When [allFactors] is true, every
     * factor is enqueued initially (the usual one-shot path). When false, only factors
     * touching variables currently in the dirty queues are enqueued — for incremental use
     * by a session that just applied a pin and wants to extend the fixpoint.
     *
     * Returns `null` on success (state is at fixpoint); otherwise the conflict-levels set.
     */
    internal fun runToFixpoint(allFactors: Boolean): Set<Int>? {
        val pending = BooleanArray(problem.numFactors)
        val queue = com.eignex.klause.util.IntArrayDeque(initialCapacity = problem.numFactors.coerceAtLeast(8))
        if (allFactors) {
            for (fid in 0 until problem.numFactors) { pending[fid] = true; queue.addLast(fid) }
        } else {
            while (true) {
                val v = pollDirtyBool(); if (v < 0) break
                for (fid in problem.boolOccurrences[v]) {
                    if (!pending[fid]) { pending[fid] = true; queue.addLast(fid) }
                }
            }
            while (true) {
                val v = pollDirtyInt(); if (v < 0) break
                for (fid in problem.intOccurrences[v]) {
                    if (!pending[fid]) { pending[fid] = true; queue.addLast(fid) }
                }
            }
        }
        while (queue.isNotEmpty()) {
            val fid = queue.removeFirst()
            pending[fid] = false
            val f = problem.factors[fid]
            currentLevel = maxLevelForVars(f.boolVars, f.intVars)
            conflictLevels = null
            if (!f.propagate(this, fid)) {
                return conflictLevels ?: collectLevelsForVars(f.boolVars, f.intVars)
            }
            while (true) {
                val v = pollDirtyBool(); if (v < 0) break
                for (other in problem.boolOccurrences[v]) {
                    if (!pending[other]) { pending[other] = true; queue.addLast(other) }
                }
            }
            while (true) {
                val v = pollDirtyInt(); if (v < 0) break
                for (other in problem.intOccurrences[v]) {
                    if (!pending[other]) { pending[other] = true; queue.addLast(other) }
                }
            }
        }
        return null
    }
}
