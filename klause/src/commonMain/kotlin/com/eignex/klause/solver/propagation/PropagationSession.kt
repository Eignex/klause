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
 * [PropagationState.LevelMark]; [popLast] / [popToLevel] rewind to the target level's mark
 * by replaying the state's undo log in O(changes), no re-propagation. Conflict on push
 * reverts the state to the last good mark before returning Unsat — the session's trail is
 * left at the pre-push level.
 *
 * Not thread-safe. One consumer per session.
 */
class PropagationSession(val problem: Problem) {
    private val state: PropagationState = PropagationState(problem, Assumptions.None)
    /** `levelStates[L]` is the [PropagationState.LevelMark] right after level [L]'s
     *  fixpoint. Index 0 = post-bake. Array-backed stack with explicit [levelTop]; grows by
     *  doubling. Marks are tiny (four ints + rare payload copies) — no pooling needed. */
    private var levelStates: Array<PropagationState.LevelMark?> = arrayOfNulls(8)
    private var levelTop: Int = 0
    private fun levelLast(): PropagationState.LevelMark = levelStates[levelTop - 1]!!
    private fun levelPush(m: PropagationState.LevelMark) {
        if (levelTop == levelStates.size) levelStates = levelStates.copyOf(levelStates.size * 2)
        levelStates[levelTop++] = m
    }
    private fun levelPop() {
        levelTop--
        levelStates[levelTop] = null
    }
    private fun levelTruncateAfterRoot() {
        for (i in 1 until levelTop) levelStates[i] = null
        levelTop = 1
    }

    // Decision pins, primitive-encoded to avoid the boxing the old LinkedHashMap<Int,*> and
    // ArrayDeque<Pair> paid on every push. [boolPinned] holds -1 (free) / 0 (false) / 1
    // (true) per bool var; [intPinnedSet] + [intPinnedVal] hold the int decisions. Only
    // *decision* pins live here — propagation-implied facts are read from [state]. [trail]
    // is the decision stack used as a LIFO, each entry encoded as `v` (bool) or
    // `numBoolVars + v` (int), matching PropagationState's level encoding.
    private val boolPinned: IntArray = IntArray(problem.numBoolVars) { -1 }
    private val intPinnedSet: BooleanArray = BooleanArray(problem.numIntVars)
    private val intPinnedVal: IntArray = IntArray(problem.numIntVars)
    private val trail: com.eignex.klause.util.IntArrayList = com.eignex.klause.util.IntArrayList()
    private fun encBool(v: Int): Int = v
    private fun encInt(v: Int): Int = problem.numBoolVars + v
    private fun trailIsBool(enc: Int): Boolean = enc < problem.numBoolVars

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
        // Bake-time fixpoint above ran with logging off (it never backtracks). Enable undo
        // logging now, before the first push; the level-0 mark therefore has undoSize 0,
        // and undoing to it rewinds every search mutation back to this post-bake baseline.
        state.undoLogging = true
        levelPush(state.mark())
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
        state.undoTo(levelStates[0]!!)
        if (levelTop > 1) levelTruncateAfterRoot()
        clearPins()

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
        return computeImplied()
    }

    /** Drop every decision pin, restoring the primitive pin arrays to "free". Iterates the
     *  trail (the decided vars) rather than clearing the whole arrays. */
    private fun clearPins() {
        for (i in 0 until trail.size) {
            val e = trail[i]
            if (trailIsBool(e)) boolPinned[e] = -1 else intPinnedSet[e - problem.numBoolVars] = false
        }
        trail.clear()
    }

    /** Push one bool pin at a fresh decision level. Returns newly-implied facts (diff). */
    fun pinBool(v: Int, value: Boolean): PropagationResult {
        bakedUnsat?.let { return it }
        return pushBool(v, value)
    }

    /** Push one int pin at a fresh decision level. */
    fun pinInt(v: Int, value: Int): PropagationResult {
        bakedUnsat?.let { return it }
        return pushInt(v, value)
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
        val base = state.undoTop
        val newFid = state.addLearnedClause(clause, lbd)
        val conflict = state.runToFixpoint(allFactors = false, initialFactor = newFid)
        if (conflict != null) return revertAndUnsat(conflict)
        return impliedSince(base)
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
        val want = if (value) 1 else 0
        if (boolPinned[v] == want) return PropagationResult.Implied.Empty
        val base = state.undoTop
        if (!state.pinBoolAsDecision(v, value)) return revertAndUnsat(state.conflictLevels ?: emptySet())
        val conflict = state.runToFixpoint(allFactors = false)
        if (conflict != null) return revertAndUnsat(conflict)
        boolPinned[v] = want
        trail.add(encBool(v))
        levelPush(state.mark())
        return impliedSince(base)
    }

    private fun pushInt(v: Int, value: Int): PropagationResult {
        if (intPinnedSet[v] && intPinnedVal[v] == value) return PropagationResult.Implied.Empty
        val base = state.undoTop
        if (!state.setIntAsDecision(v, value)) return revertAndUnsat(state.conflictLevels ?: emptySet())
        val conflict = state.runToFixpoint(allFactors = false)
        if (conflict != null) return revertAndUnsat(conflict)
        intPinnedSet[v] = true; intPinnedVal[v] = value
        trail.add(encInt(v))
        levelPush(state.mark())
        return impliedSince(base)
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
        state.undoTo(levelLast())
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
            val e = trail[trail.size - 1]
            trail.removeAt(trail.size - 1)
            if (trailIsBool(e)) boolPinned[e] = -1 else intPinnedSet[e - problem.numBoolVars] = false
            levelPop()
        }
        state.undoTo(levelLast())
    }

    /** Pop until [v] of [kind] is no longer pinned. No-op if [v] is already unpinned. */
    fun popUntilUnpinned(kind: VarKind, v: Int) {
        val pinned = when (kind) {
            VarKind.Bool -> boolPinned[v] != -1
            VarKind.Int -> intPinnedSet[v]
        }
        if (!pinned) return
        val target = if (kind == VarKind.Bool) encBool(v) else encInt(v)
        while (trail.size > 0) {
            val top = trail[trail.size - 1]
            popLast()
            if (top == target) break
        }
    }

    /** Snapshot the current pin set as an [Assumptions]. Maps are fresh copies, in trail
     *  (decision) order. */
    fun currentAssumptions(): Assumptions {
        val bools = LinkedHashMap<Int, Boolean>()
        val ints = LinkedHashMap<Int, Int>()
        for (i in 0 until trail.size) {
            val e = trail[i]
            if (trailIsBool(e)) bools[e] = boolPinned[e] == 1
            else { val iv = e - problem.numBoolVars; ints[iv] = intPinnedVal[iv] }
        }
        return Assumptions(bools = bools, ints = ints)
    }

    /** The (kind, var) decision at [level] (1-based), or `null` if [level] is out of range. */
    fun decisionAt(level: Int): Pair<VarKind, Int>? {
        if (level !in 1..trail.size) return null
        val e = trail[level - 1]
        return if (trailIsBool(e)) VarKind.Bool to e else VarKind.Int to (e - problem.numBoolVars)
    }

    /**
     * Build a fresh [PropagationResult.Implied] from the propagation state, excluding
     * already-pinned vars. Iterates the var spaces in ascending id order so the resulting
     * primitive arrays are naturally sorted — no separate sort step required. Used only for
     * [seed]'s full-implied return (the hot push path uses the incremental [impliedSince]).
     */
    private fun computeImplied(): PropagationResult.Implied {
        val bKeys = com.eignex.klause.util.IntArrayList(initialCapacity = 8)
        val bVals = ArrayList<Boolean>()
        for (v in 0 until problem.numBoolVars) {
            val b = state.boolValues[v] ?: continue
            if (boolPinned[v] != -1) continue
            bKeys.add(v); bVals.add(b)
        }
        val iKeys = com.eignex.klause.util.IntArrayList(initialCapacity = 8)
        val iVals = com.eignex.klause.util.IntArrayList(initialCapacity = 8)
        for (v in 0 until problem.numIntVars) {
            val d = state.intDomains[v]
            if (d.min == d.max) {
                if (intPinnedSet[v]) continue
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
     * Newly-implied facts of a push (its "diff"): the variables mutated since undo position
     * [base] that are now *determined* (bool assigned / int domain singleton) and aren't
     * themselves decision pins. This is exactly the set the old `computeImplied` +
     * `diffAgainst` produced, but read straight off the state's undo log in O(changes)
     * rather than scanning every variable. Keys come out sorted ascending (matching the old
     * ascending-id build), as [PropagationResult.Implied]'s binary-search lookups require.
     */
    private fun impliedSince(base: Int): PropagationResult.Implied {
        val top = state.undoTop
        if (top <= base) return PropagationResult.Implied.Empty
        val bRaw = com.eignex.klause.util.IntArrayList()
        val iRaw = com.eignex.klause.util.IntArrayList()
        for (i in base until top) {
            val v = state.undoVarAt(i)
            if (state.undoIsBoolAt(i)) bRaw.add(v) else iRaw.add(v)
        }
        val bKeys = com.eignex.klause.util.IntArrayList()
        val bVals = ArrayList<Boolean>()
        if (bRaw.size > 0) {
            val sorted = bRaw.toIntArray(); sorted.sort()
            var prev = -1
            for (v in sorted) {
                if (v == prev) continue
                prev = v
                if (boolPinned[v] != -1) continue        // decision var — excluded from implied
                val b = state.boolValues[v] ?: continue   // must be determined
                bKeys.add(v); bVals.add(b)
            }
        }
        val iKeys = com.eignex.klause.util.IntArrayList()
        val iVals = com.eignex.klause.util.IntArrayList()
        if (iRaw.size > 0) {
            val sorted = iRaw.toIntArray(); sorted.sort()
            var prev = -1
            for (v in sorted) {
                if (v == prev) continue
                prev = v
                if (intPinnedSet[v]) continue             // decision var — excluded
                val d = state.intDomains[v]
                if (d.min != d.max) continue               // not yet determined
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
}
