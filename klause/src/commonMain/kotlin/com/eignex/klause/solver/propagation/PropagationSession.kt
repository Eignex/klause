package com.eignex.klause.solver.propagation

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem

/** Variable kind discriminator for [PropagationSession.popUntilUnpinned]. */
enum class VarKind { Bool, Int }

/**
 * Stateful propagator with a decision trail. Push pins via [pinBool] / [pinInt]; each push
 * increments [decisionLevel] and propagates incrementally (only factors whose vars changed
 * are revisited). On [PropagationResult.Unsat], the result carries `conflictLevels` so a
 * CSP-style DFS sampler can backjump directly past the deepest non-conflicting level.
 *
 * One persistent [PropagationState] is reused across pushes. Each successful push records a
 * post-fixpoint snapshot; [popLast] / [popToLevel] restore the snapshot at the target level
 * in O(numVars), no re-propagation. Conflict on push reverts the state to the last good
 * snapshot before returning Unsat — the session's trail is left at the pre-push level.
 *
 * Not thread-safe. One consumer per session.
 */
class PropagationSession(val problem: Problem) {
    private val state: PropagationState = PropagationState(problem, Assumptions.None)
    private data class LevelState(
        val snap: PropagationState.Snapshot,
        val implied: PropagationResult.Implied,
    )
    /** `levelStates[L]` is the state right after level [L]'s fixpoint. Index 0 = post-bake.
     *  Array-backed stack with explicit [levelTop]; grows by doubling. */
    private var levelStates: Array<LevelState?> = arrayOfNulls(8)
    private var levelTop: Int = 0
    /** Pool of [PropagationState.Snapshot] buffers freed by [levelPop] /
     *  [levelTruncateAfterRoot], reused by [makeSnapshot] in place of fresh allocation. Each
     *  buffer's arrays match the state's capacity; the per-push cost shrinks from ~10
     *  `copyOf` allocations to ~10 `copyInto` overwrites of recycled buffers. */
    private val snapshotPool: ArrayDeque<PropagationState.Snapshot> = ArrayDeque()
    private fun makeSnapshot(): PropagationState.Snapshot {
        val buf = snapshotPool.removeLastOrNull() ?: state.allocateSnapshotBuffer()
        return state.snapshotInto(buf)
    }
    private fun recycle(s: PropagationState.Snapshot) {
        snapshotPool.addLast(s)
    }
    private fun levelLast(): LevelState = levelStates[levelTop - 1]!!
    private fun levelPush(s: LevelState) {
        if (levelTop == levelStates.size) levelStates = levelStates.copyOf(levelStates.size * 2)
        levelStates[levelTop++] = s
    }
    private fun levelPop() {
        levelTop--
        val ls = levelStates[levelTop]
        levelStates[levelTop] = null
        if (ls != null) recycle(ls.snap)
    }
    private fun levelTruncateAfterRoot() {
        for (i in 1 until levelTop) {
            val ls = levelStates[i]
            levelStates[i] = null
            if (ls != null) recycle(ls.snap)
        }
        levelTop = 1
    }
    private val pinnedBools: LinkedHashMap<Int, Boolean> = LinkedHashMap()
    private val pinnedInts: LinkedHashMap<Int, Int> = LinkedHashMap()
    private val trail: ArrayDeque<Pair<VarKind, Int>> = ArrayDeque()
    /** Cumulative implied set as of the last successful push — used to diff push returns. */
    private var lastImplied: PropagationResult.Implied =
        PropagationResult.Implied.Empty

    /** Set non-null when bake-time propagation proved Unsat with no caller pins involved.
     *  All session operations short-circuit to this result. */
    private var bakedUnsat: PropagationResult.Unsat? = null

    init {
        val conflict = state.runToFixpoint(allFactors = true)
        if (conflict != null) {
            bakedUnsat = PropagationResult.Unsat(
                state.extractConflictBools(conflict),
                state.extractConflictInts(conflict),
                conflict,
                state.extractConflictFactors(),
            )
        }
        val baseline = computeImplied()
        levelPush(LevelState(makeSnapshot(), baseline))
        lastImplied = baseline
    }

    /** Current decision level — number of pins on the trail. 0 = no decisions (post-bake). */
    val decisionLevel: Int get() = trail.size

    /** Current bool value: pinned by decision OR forced by propagation. `null` = free. */
    fun boolValue(v: Int): Boolean? = state.boolValues[v]

    /** Current int domain after propagation. Always non-empty unless the session is Unsat. */
    fun intDomain(v: Int): IntDomain = state.intDomains[v]

    /**
     * Seed with an initial assumption set. Resets any prior trail to level 0 first, then
     * pushes each assumption in iteration order (bools first, then ints). Each gets its own
     * decision level. Returns the cumulative implied set beyond the seed pins (or Unsat).
     */
    fun seed(assumptions: Assumptions): PropagationResult {
        bakedUnsat?.let { return it }
        state.restore(levelStates[0]!!.snap)
        if (levelTop > 1) levelTruncateAfterRoot()
        pinnedBools.clear()
        pinnedInts.clear()
        trail.clear()
        lastImplied = levelStates[0]!!.implied

        // Seed bool then int pins from the primitive sorted arrays. Iterating directly
        // (vs. forEachBool / forEachInt) lets us `return` the first Unsat without a
        // captured-flag dance.
        val bk = assumptions.boolKeys; val bv = assumptions.boolValues
        for (i in bk.indices) {
            val r = pushBool(bk[i], bv[i])
            if (r is PropagationResult.Unsat) return r
        }
        val ik = assumptions.intKeys; val iv = assumptions.intValues
        for (i in ik.indices) {
            val r = pushInt(ik[i], iv[i])
            if (r is PropagationResult.Unsat) return r
        }
        lastImplied = levelLast().implied
        return computeImplied()
    }

    /** Push one bool pin at a fresh decision level. Returns newly-implied facts (diff). */
    fun pinBool(v: Int, value: Boolean): PropagationResult {
        bakedUnsat?.let { return it }
        val r = pushBool(v, value)
        if (r !is PropagationResult.Implied) return r
        return diffAgainst(r, lastImplied).also { lastImplied = r }
    }

    /** Push one int pin at a fresh decision level. */
    fun pinInt(v: Int, value: Int): PropagationResult {
        bakedUnsat?.let { return it }
        val r = pushInt(v, value)
        if (r !is PropagationResult.Implied) return r
        return diffAgainst(r, lastImplied).also { lastImplied = r }
    }

    /**
     * Register a learned [clause] and immediately propagate it. Used by the
     * BacktrackSolver after a CDB backjump to make the analyzer's 1UIP clause stick:
     * the clause stays alive for the rest of the session and participates in every
     * future propagation cycle through [PropagationState.boolWatchersByLit]. Returns
     * the propagation result of asserting it — typically [PropagationResult.Implied]
     * with the UIP literal now forced, or [PropagationResult.Unsat] if the assertion
     * cascades into another conflict (the engine handles that as a fresh CDB round).
     *
     * Unlike [pinBool] / [pinInt], this does *not* open a new trail level — the
     * learned clause is a constraint over existing variables, not a decision. So no
     * snapshot is pushed and no decision counter is bumped.
     */
    fun addLearnedClause(
        clause: com.eignex.klause.solver.factor.Clause,
        lbd: Int,
    ): PropagationResult {
        bakedUnsat?.let { return it }
        val newFid = state.addLearnedClause(clause, lbd)
        val conflict = state.runToFixpoint(allFactors = false, initialFactor = newFid)
        if (conflict != null) return revertAndUnsat(conflict)
        val implied = computeImplied()
        return diffAgainst(implied, lastImplied).also { lastImplied = implied }
    }

    /** Forward to [PropagationState.forgetLearnedClauses]. Called by the engine's
     *  restart hook to bound the learned-clause database. */
    fun forgetLearnedClauses(keep: (learnedIndex: Int, lbd: Int) -> Boolean) {
        state.forgetLearnedClauses(keep)
    }

    /** Current learned-clause count. Used by the engine to decide whether to invoke
     *  [forgetLearnedClauses] based on `BacktrackParams.maxLearnedClauses`. */
    val learnedClauseCount: Int get() = state.learnedClauses.size

    /** LBD of the learned clause at [learnedIndex]. */
    fun learnedClauseLbd(learnedIndex: Int): Int = state.learnedClauseLbd(learnedIndex)

    private fun pushBool(v: Int, value: Boolean): PropagationResult {
        if (pinnedBools[v] == value) return levelLast().implied
        if (!state.pinBoolAsDecision(v, value)) return revertAndUnsat(state.conflictLevels ?: emptySet())
        val conflict = state.runToFixpoint(allFactors = false)
        if (conflict != null) return revertAndUnsat(conflict)
        pinnedBools[v] = value
        trail.addLast(VarKind.Bool to v)
        val implied = computeImplied()
        levelPush(LevelState(makeSnapshot(), implied))
        return implied
    }

    private fun pushInt(v: Int, value: Int): PropagationResult {
        if (pinnedInts[v] == value) return levelLast().implied
        if (!state.setIntAsDecision(v, value)) return revertAndUnsat(state.conflictLevels ?: emptySet())
        val conflict = state.runToFixpoint(allFactors = false)
        if (conflict != null) return revertAndUnsat(conflict)
        pinnedInts[v] = value
        trail.addLast(VarKind.Int to v)
        val implied = computeImplied()
        levelPush(LevelState(makeSnapshot(), implied))
        return implied
    }

    /**
     * Build the Unsat result from [levels] (which references the *failed-push* level
     * encoding still on the state), then restore the pre-push snapshot. Extraction must
     * happen before restore, since restore wipes the level-to-var mapping.
     */
    private fun revertAndUnsat(levels: Set<Int>): PropagationResult.Unsat {
        val bools = state.extractConflictBools(levels)
        val ints = state.extractConflictInts(levels)
        // Must extract factors *before* restoring — restore wipes the seed + reason arrays.
        val factors = state.extractConflictFactors()
        // Run 1UIP analysis BEFORE restore — the analyzer walks `state.boolAntecedents` /
        // `state.boolPinOrder` / `state.boolLevel`, all of which restore would rewind.
        // Only applicable when a factor's `propagate` triggered the conflict (so
        // `currentFactor >= 0`); seed-assumption conflicts don't have a clause-form
        // antecedent to seed analysis with.
        val learned: ConflictAnalyzer.AnalysisResult? = run {
            val failingFid = state.currentFactor
            when {
                failingFid >= 0 -> ConflictAnalyzer(state).analyze(failingFid)
                state.lastDecisionConflictVar >= 0 ->
                    ConflictAnalyzer(state).analyzeDecisionConflict(state.lastDecisionConflictVar)
                else -> null
            }
        }
        state.restore(levelLast().snap)
        return PropagationResult.Unsat(bools, ints, levels, factors, learned)
    }

    /** Pop the most-recently-pushed pin. No-op if the trail is empty. */
    fun popLast() {
        if (trail.isEmpty()) return
        popToLevel(trail.size - 1)
    }

    /**
     * Pop until [decisionLevel] equals [level]. O(decisions popped × numVars) for the
     * snapshot restore. Used by DFS samplers to backjump.
     */
    fun popToLevel(level: Int) {
        require(level in 0..trail.size) {
            "popToLevel($level): out of range [0, ${trail.size}]"
        }
        while (trail.size > level) {
            val (kind, v) = trail.removeLast()
            when (kind) {
                VarKind.Bool -> pinnedBools.remove(v)
                VarKind.Int -> pinnedInts.remove(v)
            }
            levelPop()
        }
        state.restore(levelLast().snap)
        lastImplied = levelLast().implied
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

    /**
     * Build a fresh [PropagationResult.Implied] from the propagation state, excluding
     * already-pinned vars. Iterates the var spaces in ascending id order so the resulting
     * primitive arrays are naturally sorted — no separate sort step required.
     */
    private fun computeImplied(): PropagationResult.Implied {
        val bKeys = com.eignex.klause.util.IntArrayList(initialCapacity = 8)
        val bVals = ArrayList<Boolean>()
        for (v in 0 until problem.numBoolVars) {
            val b = state.boolValues[v] ?: continue
            if (pinnedBools[v] == b) continue
            bKeys.add(v); bVals.add(b)
        }
        val iKeys = com.eignex.klause.util.IntArrayList(initialCapacity = 8)
        val iVals = com.eignex.klause.util.IntArrayList(initialCapacity = 8)
        for (v in 0 until problem.numIntVars) {
            val d = state.intDomains[v]
            if (d.min == d.max) {
                if (pinnedInts[v] == d.min) continue
                iKeys.add(v); iVals.add(d.min)
            }
        }
        return PropagationResult.Implied(
            boolKeys = bKeys.toIntArray(),
            boolValues = BooleanArray(bVals.size) { bVals[it] },
            intKeys = iKeys.toIntArray(),
            intValues = iVals.toIntArray(),
        )
    }

    /**
     * Filter [current] to entries that differ from [previous]. Both sides are key-sorted,
     * so we walk in lockstep with binary-search misses replacing the old Map filtering.
     */
    private fun diffAgainst(
        current: PropagationResult.Implied,
        previous: PropagationResult.Implied,
    ): PropagationResult.Implied {
        val bKeys = com.eignex.klause.util.IntArrayList(initialCapacity = current.numBools)
        val bVals = ArrayList<Boolean>(current.numBools)
        current.forEachBool { k, v ->
            if (previous.boolValueOrNull(k) != v) { bKeys.add(k); bVals.add(v) }
        }
        val iKeys = com.eignex.klause.util.IntArrayList(initialCapacity = current.numInts)
        val iVals = com.eignex.klause.util.IntArrayList(initialCapacity = current.numInts)
        current.forEachInt { k, v ->
            if (previous.intValueOrNull(k) != v) { iKeys.add(k); iVals.add(v) }
        }
        return PropagationResult.Implied(
            boolKeys = bKeys.toIntArray(),
            boolValues = BooleanArray(bVals.size) { bVals[it] },
            intKeys = iKeys.toIntArray(),
            intValues = iVals.toIntArray(),
        )
    }
}
