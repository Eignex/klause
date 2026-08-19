package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.ClausePropagator
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList

/** Variable kind discriminator for [PropagationSession.popUntilUnpinned]. */
enum class VarKind {
    /** A Boolean variable. */
    Bool,

    /** An integer variable. */
    Int,
}

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
class PropagationSession(
    /** The problem being propagated. */
    val problem: Problem,
    /** Deadline token polled by every fixpoint (bake, seed, and per-node pushes) so a slow
     *  propagator over wide domains can't wedge past the solve budget. Defaults to never. */
    private val cancellation: Cancellation = Cancellation.Never,
    /** Per-call fire floor before the deadline poll engages; see [PROPAGATION_CANCEL_FLOOR]. */
    propagationCancelFloor: Int = PROPAGATION_CANCEL_FLOOR,
    /** Opt into the native-SAT BCP lane for an eligible pure-Boolean problem;
     *  ignored when the problem has integer variables or non-clause factors. */
    nativeSat: Boolean = false,
    /** Opt into pseudo-Boolean cutting-planes conflict learning; ignored on problems
     *  with integer variables. */
    pbLearning: Boolean = false,
    /** Keep a Boolean premise that recurs after being resolved out; see [PropagationState.keepRecurringPremises]. */
    keepRecurringPremises: Boolean = false,
) {
    private val state: PropagationState =
        PropagationState(
            problem,
            Assumptions.None,
            nativeSat = nativeSat,
            pbLearning = pbLearning,
            keepRecurringPremises = keepRecurringPremises,
        ).also {
            it.cancelFloor = propagationCancelFloor
        }

    /** Subscribe to per-variable unassign events fired by every backtrack/undo; see
     *  [PropagationState.undoTo]. Combined-index encoding: bool id `v`, int id `numBoolVars + v`. */
    var unassignListener: ((Int) -> Unit)?
        get() = state.unassignListener
        set(value) {
            state.unassignListener = value
        }

    /** `levelStates[L]` is the [PropagationState.LevelMark] right after level `L`'s
     *  fixpoint. Index 0 = post-bake. Array-backed stack with explicit [levelTop]; grows by
     *  doubling. Marks are tiny (four ints + rare payload copies) — no pooling needed. */
    private var levelStates: Array<PropagationState.LevelMark?> = arrayOfNulls(8)
    private var levelTop: Int = 0
    private fun levelLast(): PropagationState.LevelMark = requireNotNull(levelStates[levelTop - 1])
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

    // Decision pins, primitive-encoded to keep a push allocation- and boxing-free.
    // [boolPinned] holds -1 (free) / 0 (false) / 1
    // (true) per bool var; [intPinnedSet] + [intPinnedVal] hold the int decisions. Only
    // *decision* pins live here — propagation-implied facts are read from [state]. [trail]
    // is the decision stack used as a LIFO, each entry encoded as `v` (bool) or
    // `numBoolVars + v` (int), matching PropagationState's level encoding.
    private val boolPinned: IntArray = IntArray(problem.numBoolVars) { -1 }
    private val intPinnedSet: BooleanArray = BooleanArray(problem.numIntVars)
    private val intPinnedVal: LongArray = LongArray(problem.numIntVars)
    private val trail: IntArrayList = IntArrayList()
    private fun encBool(v: Int): Int = v
    private fun encInt(v: Int): Int = problem.numBoolVars + v
    private fun trailIsBool(enc: Int): Boolean = enc < problem.numBoolVars

    /** The clause forbidding the exact total assignment `(bools, ints)`: one literal per
     *  variable demanding it differ. Blocking a found solution's full assignment (rather than
     *  the decisions that led to it) is order-independent — the same assignment reached later
     *  through a different decision sequence is still excluded. */
    fun assignmentNogood(bools: BooleanArray, ints: LongArray): IntArray {
        val out = IntArray(bools.size + ints.size)
        var j = 0
        for (v in bools.indices) out[j++] = Lit.make(v, !bools[v])
        for (v in ints.indices) out[j++] = Lit.make(state.atomVarEq(v, ints[v]), false)
        return out
    }

    /** Literal `x_v ≥ threshold` ([positive]) or its negation — for LP/energetic explanation
     *  clauses, whose reason atoms are absolute variable bounds. */
    fun boundGeLit(v: Int, threshold: Long, positive: Boolean): Int = Lit.make(state.atomVarGe(v, threshold), positive)

    /** Literal `x_v ≤ threshold` ([positive]) or its negation. See [boundGeLit]. */
    fun boundLeLit(v: Int, threshold: Long, positive: Boolean): Int = Lit.make(state.atomVarLe(v, threshold), positive)

    /** Whether the deadline token has fired. Read by the heuristics that run propagation *inside* one
     *  decision — see [com.eignex.klause.backtrack.selector.probeAndOrder], which spends a fixpoint per
     *  candidate value — since the engine only polls between nodes and cannot see past them. */
    internal val cancelRequested: Boolean get() = cancellation()

    /** True once a fixpoint was cut short by the [cancellation] deadline (sticky). The engine must
     *  then abort to `BudgetCapped` rather than read the partial state as a solved leaf. */
    val fixpointCancelled: Boolean get() = state.runCancelled

    /** Set when a value probe saw the deadline fire (see [probeFixpoint] and
     *  [com.eignex.klause.backtrack.selector.probeAndOrder]). The engine reads and clears it per node and
     *  stops there: a decision made now would spend its own multi-second fixpoint past the deadline.
     *  Not sticky — a probe leaves no partial state behind, so a paused arm resumes on this session. */
    internal var probeCancelled: Boolean = false

    /** Set non-null when bake-time propagation proved Unsat with no caller pins involved.
     *  All session operations short-circuit to this result. */
    private var bakedUnsat: PropagationResult.Unsat? = null

    init {
        val conflict = state.runToFixpoint(allFactors = true, cancellation = cancellation)
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

    /**
     * True when bake-time propagation already proved the problem Unsat. The bake fixpoint stops at
     * the first conflict, so the post-bake domains are then a *partial* propagation state — callers
     * that read domains without pinning (e.g. the LP rounding probe snapshotting an assignment)
     * must treat the session as conflicted rather than trust them.
     */
    val isUnsatAtRoot: Boolean get() = bakedUnsat != null

    /** Cumulative count of factor-forced assignments across this session — backs the
     *  `propagations` solve stat. Monotonic; the engine reads deltas around each pin. */
    val propagationCount: Long get() = state.propagations

    /** Current bool value: pinned by decision OR forced by propagation. `null` = free. */
    fun boolValue(v: Int): Boolean? = state.boolValues[v]

    /** Current int domain after propagation. Always non-empty unless the session is Unsat. */
    fun intDomain(v: Int): IntDomain = state.intDomains[v]

    /** Install (once, at the root) the incremental objective bool lower bound over [weights]; returns
     *  true while it is live. See [PropagationState.installObjectiveBoolBound]. */
    fun installObjectiveBoolBound(weights: LongArray): Boolean = state.installObjectiveBoolBound(weights)

    /** O(1) incremental bool part of the trivial objective lower bound; valid only once
     *  [installObjectiveBoolBound] has returned true. */
    fun objectiveBoolLowerBound(): Long = state.objectiveBoolLowerBound()

    /**
     * Seed with an initial assumption set. Resets any prior trail to level 0 first, then
     * pushes each assumption in iteration order (bools first, then ints). Each gets its own
     * decision level. Returns the cumulative implied set beyond the seed pins (or Unsat).
     */
    fun seed(assumptions: Assumptions): PropagationResult {
        bakedUnsat?.let { return it }
        state.undoTo(requireNotNull(levelStates[0]))
        if (levelTop > 1) levelTruncateAfterRoot()
        clearPins()

        // Seed bool then int pins from the primitive sorted arrays. Iterating directly
        // (vs. forEachBool / forEachInt) lets us `return` the first Unsat without a
        // captured-flag dance.
        val bk = assumptions.boolKeys
        val bv = assumptions.boolValues
        for (i in bk.indices) {
            val r = pushBool(bk[i], bv[i])
            if (r is PropagationResult.Unsat) return r
        }
        val ik = assumptions.intKeys
        val iv = assumptions.intValues
        for (i in ik.indices) {
            val r = pushInt(ik[i], iv[i])
            if (r is PropagationResult.Unsat) return r
        }
        return computeImplied()
    }

    /**
     * Re-seed to [desired] by diffing against the live pin trail instead of clearing to root and
     * re-pushing every pin (as [seed] does). The longest bottom prefix of the trail whose pins already
     * agree with [desired] is kept — its propagation stands untouched — and only the divergent suffix is
     * popped, then the still-missing pins are pushed. When successive fragments free a small fraction of
     * the variables, the shared complement stays pinned across calls, so the work scales with the changed
     * pins rather than the total variable count.
     *
     * The outcome is identical to [seed] with the same [desired]: on return the pinned set is exactly
     * [desired], one decision level per pin. Any leftover search decisions sit above the seed prefix and,
     * differing from [desired], are popped like any other mismatch. Returns the cumulative implied set
     * beyond the pins, or the first [PropagationResult.Unsat] a push hits.
     */
    fun reseedFrom(desired: Assumptions): PropagationResult {
        bakedUnsat?.let { return it }
        var keep = 0
        while (keep < trail.size) {
            val e = trail[keep]
            val agrees = if (trailIsBool(e)) {
                desired.boolValueOrNull(e) == (boolPinned[e] == 1)
            } else {
                val iv = e - problem.numBoolVars
                intPinnedSet[iv] && desired.intValueOrNull(iv) == intPinnedVal[iv]
            }
            if (!agrees) break
            keep++
        }
        popToLevel(keep)

        // Push every desired pin the kept prefix does not already stand — a var still pinned here is one
        // that survived the diff at its agreed value, so skipping it keeps its level intact.
        val bk = desired.boolKeys
        val bv = desired.boolValues
        for (i in bk.indices) {
            if (boolPinned[bk[i]] != -1) continue
            val r = pushBool(bk[i], bv[i])
            if (r is PropagationResult.Unsat) return r
        }
        val ik = desired.intKeys
        val iv = desired.intValues
        for (i in ik.indices) {
            if (intPinnedSet[ik[i]]) continue
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
    fun pinInt(v: Int, value: Long): PropagationResult {
        bakedUnsat?.let { return it }
        return pushInt(v, value)
    }

    /** [pinBool] as a probe — see [probeFixpoint]. */
    internal fun probeBool(v: Int, value: Boolean): PropagationResult? = probeFixpoint { pushBool(v, value) }

    /** [pinInt] as a probe — see [probeFixpoint]. */
    internal fun probeInt(v: Int, value: Long): PropagationResult? = probeFixpoint { pushInt(v, value) }

    /**
     * One probe pin: the fixpoint polls the deadline from its first fire (no [PROPAGATION_CANCEL_FLOOR]
     * wait, since a probe over a wide domain is exactly the runaway that floor hides), and a cut fixpoint
     * reverts the pin and yields `null`.
     *
     * The revert lands on the previous level's *complete* fixpoint, so — unlike a cut search pin — no
     * under-propagated state survives the call and [fixpointCancelled] stays clear: the session is left
     * usable and a paused arm can resume on it. [probeCancelled] carries the deadline sighting instead.
     */
    private inline fun probeFixpoint(push: () -> PropagationResult): PropagationResult? {
        bakedUnsat?.let { return it }
        if (state.runCancelled) return null
        val floor = state.cancelFloor
        state.cancelFloor = 0
        val r = try {
            push()
        } finally {
            state.cancelFloor = floor
        }
        if (!state.runCancelled) return r
        state.runCancelled = false
        probeCancelled = true
        // A cut fixpoint reports no conflict, so the probe's pin is standing on a partial state; an Unsat
        // one already reverted itself.
        if (r !is PropagationResult.Unsat) popLast()
        return null
    }

    /**
     * Decide `v ≤ hi` at a fresh decision level (a single-bound decision). Unlike [pinInt]
     * this narrows only the upper bound, so the decision contributes a single 1UIP literal
     * to any conflict it seeds — letting CDCL learn an asserting clause where an equality
     * pin (two same-level bound atoms) could not. The complementary branch is [pinIntAtLeast].
     */
    fun pinIntAtMost(v: Int, hi: Long): PropagationResult {
        bakedUnsat?.let { return it }
        val base = state.undoTop
        if (!state.setIntMaxAsDecision(v, hi)) return revertAndUnsat(state.conflictLevels ?: EmptyIntArray)
        val conflict = state.runToFixpoint(allFactors = false, cancellation = cancellation)
        if (conflict != null) return revertAndUnsat(conflict)
        trail.add(encInt(v))
        levelPush(state.mark())
        return impliedSince(base)
    }

    /** Decide `v ≥ lo` at a fresh decision level. See [pinIntAtMost]. */
    fun pinIntAtLeast(v: Int, lo: Long): PropagationResult {
        bakedUnsat?.let { return it }
        val base = state.undoTop
        if (!state.setIntMinAsDecision(v, lo)) return revertAndUnsat(state.conflictLevels ?: EmptyIntArray)
        val conflict = state.runToFixpoint(allFactors = false, cancellation = cancellation)
        if (conflict != null) return revertAndUnsat(conflict)
        trail.add(encInt(v))
        levelPush(state.mark())
        return impliedSince(base)
    }

    /**
     * Register a learned [clause] and immediately propagate it. Used by the
     * BacktrackSolver after a CDB backjump to make the analyzer's 1UIP clause stick:
     * the clause stays alive for the rest of the session and participates in every
     * future propagation cycle through [BoolWatcherIndex.byLit]. Returns
     * the propagation result of asserting it — typically [PropagationResult.Implied]
     * with the UIP literal now forced, or [PropagationResult.Unsat] if the assertion
     * cascades into another conflict (the engine handles that as a fresh CDB round).
     *
     * Unlike [pinBool] / [pinInt], this does *not* open a new trail level — the
     * learned clause is a constraint over existing variables, not a decision. So no
     * snapshot is pushed and no decision counter is bumped.
     */
    fun addLearnedClause(clause: Clause, lbd: Int, permanent: Boolean = false): PropagationResult =
        registerAndPropagate(base = state.undoTop) { state.addLearnedClause(clause, lbd, permanent) }

    /**
     * Register a learned pseudo-Boolean constraint `Σ weightsᵢ·literalsᵢ ≥ degree` and
     * immediately propagate it, exactly as [addLearnedClause] does for a clause. Used by the engine's
     * cutting-planes backjump to make the learned PB constraint stick and force its asserting literal.
     */
    fun addLearnedPb(
        weights: LongArray,
        literals: IntArray,
        degree: Long,
        lbd: Int,
        permanent: Boolean = false,
    ): PropagationResult =
        registerAndPropagate(base = state.undoTop) { state.addLearnedPb(weights, literals, degree, lbd, permanent) }

    /** Shared body of [addLearnedClause] / [addLearnedPb]: register the constraint via [register], fire it
     *  once, and either surface the cascaded conflict or re-baseline the level with the asserted facts. */
    private inline fun registerAndPropagate(base: Int, register: () -> Int): PropagationResult {
        bakedUnsat?.let { return it }
        val newFid = register()
        val conflict = state.runToFixpoint(allFactors = false, initialFactor = newFid, cancellation = cancellation)
        if (conflict != null) return revertAndUnsat(conflict)
        // The asserted facts are implied by the decisions up to the current level, so they join the
        // level's baseline: re-snapshot the top mark. Otherwise the next failed pin's revert — which
        // restores to the top mark — silently rewinds them while the constraint stays registered, and the
        // search can re-derive the same conflict forever.
        levelStates[levelTop - 1] = state.mark()
        return impliedSince(base)
    }

    /**
     * Assert a permanent objective bound on an integer variable as a propagating unit
     * clause: `[v <= hi]` ([atMost]) or `[v >= lo]` (`atMost = false`). Branch-and-bound
     * calls this on each new incumbent so the bound is pushed into the objective variable
     * and the constraint defining it (linear, product, element, a global's output — any
     * structure) propagates the tightening back into the decision variables. This is
     * objective propagation, strictly stronger than a passive lower-bound
     * check, and it gives objective pruning even for non-linear-defined objectives that the
     * predicate path cannot bound. Permanent: survives forgetting and persists for the rest
     * of the session; later incumbents assert progressively tighter (subsuming) units.
     * Returns the propagation result, [PropagationResult.Unsat] if the bound is already
     * infeasible at the root (the remaining objective space is empty).
     */
    fun assertObjectiveBound(v: Int, bound: Long, atMost: Boolean): PropagationResult {
        val atom = if (atMost) state.atomVarLe(v, bound) else state.atomVarGe(v, bound)
        return addLearnedClause(Clause(intArrayOf(Lit.make(atom, true))), lbd = 1, permanent = true)
    }

    /**
     * Apply a domain reduction inferred at the **current** decision level — for LP reduced-cost
     * fixing. Like [addLearnedClause] and unlike the `pin*` decisions, this opens **no** new
     * level: the tightening is folded into the current level's baseline (re-snapshotted mark), so
     * [popLast] of that level undoes it. That is exactly right for a subtree-local inference, which
     * is valid only under this node's bounds and the current incumbent and must vanish on backtrack.
     *
     * The reduction is recorded with no antecedents, so conflict analysis treats its bound atom as a
     * level leaf (like a decision). That is sound: a conflict it triggers is a real infeasibility of
     * the problem under the path bounds plus this tightening, so any learned nogood is globally
     * valid; the reduction itself never escapes its level.
     */
    fun implyIntAtMost(v: Int, hi: Long): PropagationResult = implyAtCurrentLevel { state.tightenIntMax(v, hi) }

    /** Lower-bound reduced-cost reduction at the current level. See [implyIntAtMost]. */
    fun implyIntAtLeast(v: Int, lo: Long): PropagationResult = implyAtCurrentLevel { state.tightenIntMin(v, lo) }

    /** Boolean reduced-cost fixing at the current level. See [implyIntAtMost]. */
    fun implyBool(v: Int, value: Boolean): PropagationResult = implyAtCurrentLevel { state.pinBool(v, value) }

    /**
     * Like [implyIntAtLeast]/[implyIntAtMost], but records [reason] as the antecedent of the new bound
     * atom so conflict analysis can resolve *through* the tightening instead of treating it as a leaf.
     * [reason] is a set of currently-false literals — the negated seated bounds whose
     * conjunction (with the always-valid constraints) implies the bound — so the implicit reason clause
     * `(new bound atom) ∨ reason` is globally valid. The antecedent is journaled and undone on
     * backtrack like any other; the tightening itself still folds into the current level.
     */
    fun implyIntAtLeastWithReason(v: Int, lo: Long, reason: IntArray): PropagationResult =
        implyAtCurrentLevel { state.tightenIntMin(v, lo, reason) }

    /** Upper-bound counterpart of [implyIntAtLeastWithReason]. */
    fun implyIntAtMostWithReason(v: Int, hi: Long, reason: IntArray): PropagationResult =
        implyAtCurrentLevel { state.tightenIntMax(v, hi, reason) }

    private fun implyAtCurrentLevel(apply: () -> Boolean): PropagationResult {
        bakedUnsat?.let { return it }
        val base = state.undoTop
        if (!apply()) return revertAndUnsat(state.conflictLevels ?: EmptyIntArray)
        val conflict = state.runToFixpoint(allFactors = false, cancellation = cancellation)
        if (conflict != null) return revertAndUnsat(conflict)
        // Fold into the current level's baseline so a later same-level revert keeps it, and so
        // popLast of this level — and only this level — discards it. Mirrors addLearnedClause.
        levelStates[levelTop - 1] = state.mark()
        return impliedSince(base)
    }

    /** Forward to [PropagationState.forgetLearnedClauses]. Called by the engine's
     *  restart hook to bound the learned-clause database. */
    fun forgetLearnedClauses(keep: (learnedIndex: Int, lbd: Int) -> Boolean) {
        state.forgetLearnedClauses(keep)
    }

    /** Unified truth of a bool or atom literal — null when undetermined. */
    fun litTruth(lit: Int): Boolean? = state.litTruth(lit)

    /** Run 1UIP conflict analysis from an externally supplied all-false conflict clause (e.g. an LP
     *  Farkas certificate) to obtain a learned clause and backjump level. See
     *  [ConflictAnalyzer.analyzeConflictClause]. */
    internal fun analyzeConflictClause(conflictClause: IntArray): ConflictAnalyzer.AnalysisResult =
        state.conflictAnalyzer.analyzeConflictClause(conflictClause)

    /** True when this session runs the native-SAT lane; its learned clauses live in the arena store,
     *  not the general database, so clause-database introspection routes differently. */
    val usesNativeSat: Boolean get() = state.nativeEngine != null

    /** Current learned-clause count. Used by the engine to decide whether to invoke
     *  [forgetLearnedClauses] based on `BacktrackParams.maxLearnedClauses`. */
    val learnedClauseCount: Int get() = state.nativeEngine?.count ?: state.learnedClauses.size

    /** LBD of the learned clause at [learnedIndex]. */
    fun learnedClauseLbd(learnedIndex: Int): Int = state.learnedClauseLbd(learnedIndex)

    /** True iff learned clause [learnedIndex] survives every forgetting pass. */
    fun learnedClausePermanent(learnedIndex: Int): Boolean = state.learnedClausePermanent(learnedIndex)

    /** The learned clause at [learnedIndex]. Read by the engine's vivification pass and the
     *  glue-clause export, both clause-only. Fails loudly on a non-clause learned constraint rather than
     *  through a bare cast, so a pseudo-Boolean nogood reaching a clause-only pass names the site that
     *  needs a clause filter instead of surfacing an opaque ClassCastException. */
    internal fun learnedClauseAt(learnedIndex: Int): ClausePropagator =
        state.learnedClauses[learnedIndex] as? ClausePropagator
            ?: error(
                "learned constraint at $learnedIndex is not a clause; vivification and glue export are clause-only",
            )

    /** Literals of learned clause [learnedIndex]. Routes to the native-SAT store when active (its
     *  clauses aren't in the general database), else through [learnedClauseAt]. */
    internal fun learnedClauseLiterals(learnedIndex: Int): IntArray =
        state.nativeEngine?.literalsOf(learnedIndex) ?: learnedClauseAt(learnedIndex).literals

    /** True iff the learned constraint at [learnedIndex] is a clause (not a pseudo-Boolean constraint) —
     *  the clause-only passes (vivification, glue export) skip the rest. Native-lane entries are all
     *  clauses. */
    internal fun isLearnedClause(learnedIndex: Int): Boolean =
        state.nativeEngine != null || state.learnedClauses[learnedIndex] is ClausePropagator

    /** Three-tier DB tier of learned clause [learnedIndex]. */
    internal fun learnedClauseTier(learnedIndex: Int): ClauseTier = state.learnedClauseTier(learnedIndex)

    /** Set the three-tier DB tier of learned clause [learnedIndex]. */
    internal fun setLearnedClauseTier(learnedIndex: Int, tier: ClauseTier) =
        state.setLearnedClauseTier(learnedIndex, tier)

    /** True iff learned clause [learnedIndex] was used (conflict or unit) since the last reduction. */
    fun learnedClauseUsedSinceReduction(learnedIndex: Int): Boolean =
        state.learnedClauseUsedSinceReduction(learnedIndex)

    /** Clear the reuse flag of learned clause [learnedIndex] (called for survivors after a reduction). */
    fun clearLearnedClauseUsed(learnedIndex: Int) = state.clearLearnedClauseUsed(learnedIndex)

    /**
     * Export this session's **glue** learned clauses — those with LBD ≤ [maxLbd] and length ≤
     * [maxLen] — in session-portable [SharedClause] form, for cross-arm sharing. Int-bound atom
     * literals are decoded to `(intVar, kind, threshold, sign)` via the atom registry; boolean
     * literals travel as-is. Cheap: the glue set is small by construction. (See [ClauseExchange].)
     */
    fun exportGlueClauses(maxLbd: Int, maxLen: Int, skipPermanent: Boolean = false): List<SharedClause> {
        val out = ArrayList<SharedClause>()
        for (i in 0 until learnedClauseCount) {
            // Permanent clauses are the search-conditioned assertions — the incumbent objective bound
            // (assertObjectiveBound) and blocking nogoods — not globally-valid resolvents. A caller
            // learning under assumptions/incumbent (LNS repair) must skip them or it poisons peers.
            if (skipPermanent && learnedClausePermanent(i)) continue
            if (!isLearnedClause(i)) continue // pseudo-Boolean nogoods aren't clause-portable
            val lbd = learnedClauseLbd(i)
            if (lbd > maxLbd) continue
            val lits = learnedClauseLiterals(i)
            if (lits.size > maxLen) continue
            out.add(asSharedClause(lits, lbd))
        }
        return out
    }

    /**
     * One clause's [lits] (this session's literals) in session-portable [SharedClause] form: boolean
     * literals travel as-is, int-bound atom literals decode to `(intVar, kind, threshold, sign)` via the
     * atom registry. The decode shared by [exportGlueClauses] and the direct publish of globally-valid
     * nogoods (LP Farkas certificates), which bypass the LBD glue filter.
     */
    internal fun asSharedClause(lits: IntArray, lbd: Int): SharedClause {
        val numBool = problem.numBoolVars
        var boolCount = 0
        for (lit in lits) if (Lit.variable(lit) < numBool) boolCount++
        val bools = IntArray(boolCount)
        val quads = LongArray((lits.size - boolCount) * SharedClause.QUAD)
        var bi = 0
        var qi = 0
        for (lit in lits) {
            val v = Lit.variable(lit)
            if (v < numBool) {
                bools[bi++] = lit
            } else {
                val atomId = v - numBool
                quads[qi++] = state.atoms.intVar[atomId].toLong()
                quads[qi++] = state.atoms.kind[atomId].ordinal.toLong()
                quads[qi++] = state.atoms.threshold[atomId]
                quads[qi++] = if (Lit.isPositive(lit)) 0L else 1L
            }
        }
        return SharedClause(bools, quads, lbd)
    }

    /**
     * Register an imported [shared] nogood (learned by another arm of the same problem) into this
     * session's learned DB, translating its int-atom literals into this session's lazily-allocated
     * atom space. Call only at decision level 0 (a restart): the literals are then unassigned, so the
     * clause registers without an immediate unit/conflict and participates from the next fixpoint.
     */
    fun importClause(shared: SharedClause) {
        val lits = IntArray(shared.boolLits.size + shared.atomQuads.size / SharedClause.QUAD)
        var j = 0
        for (l in shared.boolLits) lits[j++] = l
        var i = 0
        while (i < shared.atomQuads.size) {
            val intVar = shared.atomQuads[i].toInt()
            val kind = shared.atomQuads[i + 1].toInt()
            val threshold = shared.atomQuads[i + 2]
            val positive = shared.atomQuads[i + 3] == 0L
            val virtualVar = when (kind) {
                0 -> state.atomVarGe(intVar, threshold)
                1 -> state.atomVarLe(intVar, threshold)
                else -> state.atomVarEq(intVar, threshold)
            }
            lits[j++] = Lit.make(virtualVar, positive)
            i += SharedClause.QUAD
        }
        state.addLearnedClause(Clause(lits), shared.lbd, permanent = false)
    }

    private fun pushBool(v: Int, value: Boolean): PropagationResult {
        val want = if (value) 1 else 0
        if (boolPinned[v] == want) return PropagationResult.Implied.EMPTY
        val base = state.undoTop
        if (!state.pinBoolAsDecision(v, value)) return revertAndUnsat(state.conflictLevels ?: EmptyIntArray)
        val conflict = state.runToFixpoint(allFactors = false, cancellation = cancellation)
        if (conflict != null) return revertAndUnsat(conflict)
        boolPinned[v] = want
        trail.add(encBool(v))
        levelPush(state.mark())
        return impliedSince(base)
    }

    private fun pushInt(v: Int, value: Long): PropagationResult {
        if (intPinnedSet[v] && intPinnedVal[v] == value) return PropagationResult.Implied.EMPTY
        val base = state.undoTop
        if (!state.setIntAsDecision(v, value)) return revertAndUnsat(state.conflictLevels ?: EmptyIntArray)
        val conflict = state.runToFixpoint(allFactors = false, cancellation = cancellation)
        if (conflict != null) return revertAndUnsat(conflict)
        intPinnedSet[v] = true
        intPinnedVal[v] = value
        trail.add(encInt(v))
        levelPush(state.mark())
        return impliedSince(base)
    }

    /**
     * Build the Unsat result from [levels] (which references the *failed-push* level
     * encoding still on the state), then restore the pre-push snapshot. Extraction must
     * happen before restore, since restore wipes the level-to-var mapping.
     */
    private fun revertAndUnsat(levels: IntArray): PropagationResult.Unsat {
        // Must extract factors *before* restoring — restore wipes the seed + reason arrays.
        val factors = state.extractConflictFactors()
        // Run 1UIP analysis BEFORE restore — the analyzer walks `state.boolAntecedents` /
        // `state.boolPinOrder` / `state.boolLevel`, all of which restore would rewind.
        // Only applicable when a factor's `propagate` triggered the conflict (so
        // `currentFactor >= 0`); seed-assumption conflicts don't have a clause-form
        // antecedent to seed analysis with.
        val learned: ConflictAnalyzer.AnalysisResult? = run {
            val nativeReason = state.nativeConflictReason
            val failingFid = state.currentFactor
            when {
                // Native lane: the conflicting clause's literals are stashed directly (its factor id
                // does not index the general learned store), so seed 1UIP from them.
                nativeReason != null -> state.conflictAnalyzer.analyzeConflictClause(nativeReason)

                failingFid >= 0 -> state.conflictAnalyzer.analyze(failingFid)

                state.lastDecisionConflictVar >= 0 ->
                    state.conflictAnalyzer.analyzeDecisionConflict(state.lastDecisionConflictVar)

                else -> null
            }
        }
        // Conflict-reason variable sets for activity heuristics (VSIDS / dom-wdeg). When
        // 1UIP analysis produced a clause, prefer the canonical CDCL bump set — every
        // variable seen while walking the implication graph — over the coarse "decision
        // vars at the conflict levels" extraction. Sharper conflict focus ⇒ fewer conflicts.
        val bools: IntArray
        val ints: IntArray
        if (learned is ConflictAnalyzer.AnalysisResult.LearnedConstraint) {
            bools = state.conflictAnalyzer.lastBumpBoolVars().toIntArray()
            ints = state.conflictAnalyzer.lastBumpIntVars().toIntArray()
        } else {
            bools = state.extractConflictBools(levels)
            ints = state.extractConflictInts(levels)
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

    /** Pop until `v` of [kind] is no longer pinned. No-op if `v` is already unpinned. */
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
        val ints = LinkedHashMap<Int, Long>()
        for (i in 0 until trail.size) {
            val e = trail[i]
            if (trailIsBool(e)) {
                bools[e] = boolPinned[e] == 1
            } else {
                val iv = e - problem.numBoolVars
                ints[iv] = intPinnedVal[iv]
            }
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
        val bKeys = IntArrayList(initialCapacity = 8)
        val bVals = ArrayList<Boolean>()
        for (v in 0 until problem.numBoolVars) {
            val b = state.boolValues[v] ?: continue
            if (boolPinned[v] != -1) continue
            bKeys.add(v)
            bVals.add(b)
        }
        val iKeys = IntArrayList(initialCapacity = 8)
        val iVals = LongArrayList(initialCapacity = 8)
        for (v in 0 until problem.numIntVars) {
            val d = state.intDomains[v]
            if (d.min == d.max) {
                if (intPinnedSet[v]) continue
                iKeys.add(v)
                iVals.add(d.min)
            }
        }
        return PropagationResult.Implied(
            boolKeys = bKeys.toIntArray(),
            boolValues = BooleanArray(bVals.size) { bVals[it] },
            intKeys = iKeys.toIntArray(),
            intValues = iVals.toLongArray(),
        )
    }

    /**
     * Newly-implied facts of a push (its "diff"): the variables mutated since undo position
     * [base] that are now *determined* (bool assigned / int domain singleton) and aren't
     * themselves decision pins. Read straight off the state's undo log in O(changes) rather
     * than scanning every variable. Keys come out sorted ascending, as
     * [PropagationResult.Implied]'s binary-search lookups require.
     */
    private fun impliedSince(base: Int): PropagationResult.Implied {
        val top = state.undoTop
        if (top <= base) return PropagationResult.Implied.EMPTY
        val bRaw = IntArrayList()
        val iRaw = IntArrayList()
        for (i in base until top) {
            val v = state.undoVarAt(i)
            if (state.undoIsBoolAt(i)) bRaw.add(v) else iRaw.add(v)
        }
        val bKeys = IntArrayList()
        val bVals = ArrayList<Boolean>()
        if (bRaw.size > 0) {
            val sorted = bRaw.toIntArray()
            sorted.sort()
            var prev = -1
            for (v in sorted) {
                if (v == prev) continue
                prev = v
                if (boolPinned[v] != -1) continue // decision var — excluded from implied
                val b = state.boolValues[v] ?: continue // must be determined
                bKeys.add(v)
                bVals.add(b)
            }
        }
        val iKeys = IntArrayList()
        val iVals = LongArrayList()
        if (iRaw.size > 0) {
            val sorted = iRaw.toIntArray()
            sorted.sort()
            var prev = -1
            for (v in sorted) {
                if (v == prev) continue
                prev = v
                if (intPinnedSet[v]) continue // decision var — excluded
                val d = state.intDomains[v]
                if (d.min != d.max) continue // not yet determined
                iKeys.add(v)
                iVals.add(d.min)
            }
        }
        return PropagationResult.Implied(
            boolKeys = bKeys.toIntArray(),
            boolValues = BooleanArray(bVals.size) { bVals[it] },
            intKeys = iKeys.toIntArray(),
            intValues = iVals.toLongArray(),
        )
    }
}
