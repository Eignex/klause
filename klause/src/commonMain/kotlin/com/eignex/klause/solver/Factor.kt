package com.eignex.klause.solver

import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState

/** Shared singleton for the empty-int-var-set case. Factors with no variables in one of
 *  the two var spaces (purely-Boolean ones leave [Factor.intVars] empty; purely-integer
 *  ones leave [Factor.boolVars] empty) wire this in instead of allocating their own
 *  per-class empty array. */
internal val EmptyIntArray: IntArray = IntArray(0)

/**
 * Constraint metadata for [Problem]. Variables touched by a factor split into two id
 * spaces: Boolean vars in [boolVars] and integer vars in [intVars]. Pure-Boolean factors
 * leave [intVars] empty; pure-integer factors leave [boolVars] empty; reified or mixed
 * factors populate both.
 *
 * A factor carries two contracts. The **deductive** half — the var sets plus [propagate] —
 * is what every solver backend needs. The **local-search** half — `initialize` /
 * `isViolated` / `applyBoolFlip` / `applyIntSet` / `deltaIf*` / `proposeRepairMoves` — is
 * what the LS engine ([com.eignex.klause.solver.localsearch.LocalSearchSolver]) drives. Both
 * halves default to a sound no-op, so a factor that only propagates (no LS support) just
 * inherits the LS defaults — it reports always-satisfied with zero deltas — and a pure-LS
 * factor leaves [propagate] at its no-op.
 */
interface Factor {
    val boolVars: IntArray
    val intVars: IntArray

    /**
     * A copy of this factor with every Boolean variable id rewritten through [boolMap] and every
     * integer variable id through [intMap] (`newId = map[oldId]`). Non-variable data — coefficients,
     * bounds, constant arrays, domain offsets, DFA tables — is carried over unchanged. Used by
     * presolve passes that renumber or substitute variables (#332).
     *
     * Every factor must implement this (no default): a variable being renumbered or substituted can
     * appear in any factor, so a silent miss would leave stale ids in the rewritten problem. A
     * factor that genuinely touches no variables returns `this`.
     */
    fun remap(boolMap: IntArray, intMap: IntArray): Factor

    /**
     * A canonical string identifying this constraint up to variable identity: same factor type,
     * same constants (coefficients, bounds, polarities), and the same multiset of variables — in a
     * representation that does not depend on internal ordering — produce the same key. Used by
     * symmetry detection (#334) to check whether permuting variables maps the factor set to itself
     * (an automorphism). `null` (the default) means "not keyed"; verification falls back to the
     * conservative same-factor-set heuristic when any factor in the problem is unkeyed.
     */
    fun structuralKey(): String? = null

    /**
     * Whether this factor's meaning is invariant under *any* relabeling of domain values — i.e. it
     * treats values as interchangeable symbols (AllDifferent: distinctness ignores which values).
     * Used by value-symmetry detection (#366): a value permutation is a symmetry only if every
     * factor is value-anonymous (and every variable's domain is invariant under it). Arithmetic and
     * value-meaningful constraints return `false` (the default), conservatively blocking value
     * symmetry for the whole problem.
     */
    fun isValueAnonymous(): Boolean = false

    /**
     * A copy of this factor with every *value-dependent constant* relabeled through [valueMap]
     * (`newValue = valueMap(oldValue)`) — the value analog of [remap] (#374). Relabels things that
     * name domain values: an [com.eignex.klause.solver.factor.GlobalCardinality] cover, a
     * [com.eignex.klause.solver.factor.Table]'s tuples, an Element constant array, Regular/Mdd
     * symbols. Variable ids, coefficients, and structural positions are unchanged.
     *
     * Used by value-symmetry detection to *verify* that a value permutation maps the factor set to
     * itself: applying it to every factor and comparing the [structuralKey] multiset proves the
     * permutation is a symmetry, the value analog of the [remap]-based automorphism check (#334).
     *
     * `null` (the default) means "not value-relabelable" — arithmetic / value-meaningful factors
     * ([com.eignex.klause.solver.factor.Linear], Product) where a value carries magnitude, not just
     * identity, and a factor whose values live in more than one universe (e.g. a GCC with count
     * *variables*) which can't be relabeled by a single map. A `null` anywhere conservatively blocks
     * value symmetry for the whole problem. A [isValueAnonymous] factor returns `this` (no constant
     * names a value).
     */
    fun remapValues(valueMap: (Int) -> Int): Factor? = null

    /**
     * Deductive propagation given [state]'s current pins / domains. Pin or tighten anything
     * this factor implies; return `false` iff a contradiction is derived. Default is a no-op
     * — sound but trivial. Factors override to participate in [Problem.propagate].
     */
    fun propagate(state: PropagationState, factorId: Int): Boolean = true

    /**
     * Boolean literals this factor wants per-literal wakeup on, or `null` for the default
     * occurrence-list wakeup (fire on *any* change to a variable in [boolVars]). When
     * non-null, the propagation engine routes bool wakeups through a per-literal index
     * (`boolWatchersByLit[lit]`) instead of through [boolVars]: the factor fires only when
     * the literal that just became *false* is in this set. The factor is responsible for
     * keeping the index in sync as watches drift, via
     * [PropagationState.moveBoolWatcher].
     *
     * Used by [com.eignex.klause.solver.factor.Clause] to implement two-watched-literal
     * propagation (Zhang–Stickel / MiniSAT): only the two watched literals trigger
     * wakeups, so a 50-literal clause fires on 2/50 var changes instead of 50/50. The
     * same scheme generalises to Cardinality with k+1 watched literals — a future
     * factor can adopt this contract without engine changes.
     *
     * Default is `null` — preserves the current "wake on any boolVars change" semantics
     * for every factor that hasn't opted in.
     */
    val initialBoolWatchers: IntArray? get() = null

    /**
     * Optional blocking literals paired index-for-index with [initialBoolWatchers]. Entry
     * `i` is a literal that, if currently true, *proves this factor already satisfied*, so
     * the propagation engine can skip waking the factor when watcher `i`'s literal goes
     * false (see `PropagationState.boolBlockersByLit`). The standard two-watched-literal
     * BCP speedup (MiniSAT): the blocker is typically the other watched literal of the same
     * clause, and a stale blocker only ever costs a missed skip — never correctness.
     *
     * Only meaningful for factors satisfied by *any* single true literal (disjunctions /
     * [com.eignex.klause.solver.factor.Clause]). Must stay `null` for factors where one true
     * literal does not imply satisfaction — e.g. cardinality, where a blocker would be
     * unsound. `null` (default) means "no blocking literals": every watcher always fires,
     * preserving the prior behaviour exactly.
     */
    val initialBoolWatcherBlockers: IntArray? get() = null

    /**
     * If this factor just returned `false` from [propagate], the clause-form explanation
     * of why — i.e. an array of literals, all currently *false* in [state], whose
     * disjunction is unsatisfied. The propagation-graph conflict analyzer seeds its
     * resolution loop with this set when computing a learned clause (lazy clause
     * generation). Returns `null` for factors that can't produce a clause-form reason;
     * the analyzer falls back to chronological backtrack in that case.
     *
     * Bool-pinning factors (Clause, Cardinality, PseudoBoolean, ReifiedCardinality,
     * ReifiedPseudoBoolean, Xor) override this with a sharp factor-specific clause.
     * The default implementation handles int-domain factors (Linear, AllDifferent,
     * GlobalCardinality, Element, Cumulative, etc.) by returning a sound but coarse
     * "negate the current bool partial assignment" clause via
     * [defaultBoolPinsConflictReason] — suppressed when int decisions are on the
     * trail (full LCG int-bound literals would be needed for that case).
     */
    fun conflictReason(state: PropagationState, factorId: Int): IntArray? = defaultBoolPinsConflictReason(state)

    // ---------------------------------------------------------------------------------------
    // Local-search contract. Every method below defaults to a sound no-op (always-satisfied,
    // zero deltas, naive ±1 repair moves), so a propagation-only factor needs to implement
    // nothing here. The LS engine drives these; the deductive half above is unused by it.
    // ---------------------------------------------------------------------------------------

    /** Build this factor's payload from the current assignment. Called once per restart.
     *  Default no-op for stateless factors that maintain no payload. */
    fun initialize(state: LocalSearchState, factorId: Int) {}

    /** True iff this factor is violated under the current state. Default: never violated —
     *  the correct answer for a propagation-only factor that carries no LS semantics. */
    fun isViolated(state: LocalSearchState, factorId: Int): Boolean = false

    /**
     * **Graded violation degree**: `0` when satisfied, a positive magnitude that grows with
     * "how far" the constraint is from being satisfied. The LS hard cost is
     * `Σ violationDegree` over all factors — *not* a count of violated factors — so CBLS
     * sees a descent gradient on tight arithmetic and global constraints (a move that shrinks
     * an `int_lin_eq` residual from 1000 → 1 scores a real improvement instead of 0).
     *
     * The default delegates to the binary [isViolated] (degree `1` when violated, else `0`),
     * which is the *correct* degree for inherently-binary factors — a violated clause, a
     * single comparator, a table membership all genuinely have degree 1. Gradable factors
     * (linear, count/cardinality, packing, scheduling, all-different, …) override this with
     * a real magnitude.
     *
     * **Contract (must hold for cost/gradient consistency):** for every move kind,
     * `deltaIf*` and `apply*` must return exactly `violationDegree(after) - violationDegree(before)`.
     * The engine maintains `cost` and the per-factor degree incrementally from those deltas
     * and only calls [violationDegree] at [LocalSearchState.recompute]; a delta that disagrees
     * with this method silently desyncs the cost. Degrees should be clamped to a sane range
     * to avoid `Int` overflow when summed across the factor set.
     */
    fun violationDegree(state: LocalSearchState, factorId: Int): Int = if (isViolated(state, factorId)) 1 else 0

    /**
     * Δ in this factor's [violationDegree] if the given move were applied, computed without
     * mutating state. For a binary factor this is `+1` (satisfied → violated), `-1` (the
     * opposite), or `0`; for a graded factor it is the signed change in magnitude (any
     * integer). Default returns 0; factors override the methods relevant to the move kinds
     * they handle.
     */
    fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int = 0

    /** Δ violation-degree if [intVar] were set to [newValue], without mutating state. */
    fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int = 0

    /**
     * Apply a committed move to this factor's payload. The assignment has already been
     * updated, so factors compare current values against the saved `oldValue` (for int sets)
     * or recover the pre-flip value by inversion. Returns the same Δ[violationDegree] the
     * deltaIf* method would have returned before the move.
     */
    fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int = 0

    /** Apply a committed int-set of [intVar] from [oldValue]; returns the Δ violation-degree. */
    fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int = 0

    /**
     * Suggest moves that would (or might) repair this factor when violated. The default lists
     * a Boolean flip per [boolVars] member plus an `IntSet(±1)` per [intVars] member. Factors
     * with structural insight (e.g. a comparator can snap to its bound) override this.
     */
    fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        for (b in boolVars) sink.addBoolFlip(b)
        for (i in intVars) {
            val cur = state.assignment.intValue(i)
            val d = state.problem.intDomains[i]
            if (cur < d.max) sink.addChannelingIntSet(state, i, cur + 1)
            if (cur > d.min) sink.addChannelingIntSet(state, i, cur - 1)
        }
    }

    /**
     * Suggest moves that the factor *currently* knows would preserve its own satisfaction
     * (`isViolated → false` after the move). Called by the minimize engine during the
     * objective-descent phase to collect candidate moves that don't break the constraint
     * but might improve the LS objective. Pre-condition: `!isViolated(state, factorId)`
     * — engines only consult this on already-feasible states.
     *
     * Default: no proposals. Factors with structural insight (e.g. `Linear EQ`'s "shift
     * between two summed vars" or `Cardinality.exactlyOne`'s "swap a true literal with a
     * false one") override to push self-preserving moves. The engine combines proposals
     * from all factors and scores each against the objective; the constraint-aware
     * "structured" set typically dwarfs random pair-swap in hit rate on decomposed CP
     * models.
     */
    fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
    }

    /** True iff this factor maintains its contribution to [LocalSearchState.boolBreakCount]
     *  and [LocalSearchState.boolMakeCount] incrementally via [updateBoolBreakMakeForFlip],
     *  skipping the engine's brute-force O(arity²) per-flip subtract-add cycle.
     *
     *  Factors that override should: (a) set this to true, (b) implement
     *  [updateBoolBreakMakeForFlip] so the post-flip counts match what brute-force would
     *  produce, (c) maintain whatever internal state the update needs (e.g. `numTrueLits`
     *  in [LocalSearchState.intPayload]). Default `false` keeps the brute-force fallback. */
    val maintainsBreakMakeIncrementally: Boolean get() = false

    /** Adjust [LocalSearchState.boolBreakCount] / [LocalSearchState.boolMakeCount] after
     *  [flippedVar] has been flipped. Called *after* the assignment is updated and after
     *  this factor's own [applyBoolFlip] has run, so any internal payload is current.
     *
     *  Only invoked when [maintainsBreakMakeIncrementally] is true. Net adjustment must
     *  equal the brute-force "subtract pre-flip per-var deltas, add post-flip" pattern. */
    fun updateBoolBreakMakeForFlip(state: LocalSearchState, factorId: Int, flippedVar: Int) {}

    /** Mirror of [maintainsBreakMakeIncrementally] for the int-set path. When `true`, the
     *  LS engine skips its brute-force [boolVars] walk after an `intVar` is set and calls
     *  [updateIntBreakMakeForIntSet] instead. Factors whose [deltaIfBoolFlipped] doesn't
     *  depend on int values (e.g. pure Boolean constraints with no [intVars]) get no
     *  benefit from setting this flag — the engine already short-circuits when [intVars]
     *  is empty via [Problem.intOccurrences]. */
    val maintainsIntBreakMakeIncrementallyForIntSet: Boolean get() = false

    /** Adjust [LocalSearchState.boolBreakCount] / [LocalSearchState.boolMakeCount] after
     *  [intVar] has been set from [oldValue] to its new value (read via
     *  `state.assignment.intValue(intVar)`). Called *after* the assignment is updated and
     *  after this factor's own [applyIntSet] has run. Only invoked when
     *  [maintainsIntBreakMakeIncrementallyForIntSet] is true. Net adjustment must equal
     *  the brute-force "subtract pre-set per-bool deltas, add post-set" pattern. */
    fun updateIntBreakMakeForIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int) {}
}

/**
 * Sound but coarse clause-form nogood used as the default [Factor.conflictReason] for
 * int-domain factors: the disjunction of every currently-pinned bool literal's
 * false-form. Says "the current bool partial assignment forced this dead-end". Returns
 * `null` (forcing analyzer fallback to chronological backtrack) when:
 *   - no bool vars are currently pinned, or
 *   - any int decision is on the trail — including it as antecedent would be unsound
 *     since the int decision is part of the cause but not captured in the clause.
 *
 * The analyzer's minimization step (self-subsuming resolution) typically shrinks this
 * coarse seed substantially: implied pins resolve against their antecedents and only
 * the actual UIP plus relevant decisions remain. So the practical learned clause is
 * usually much shorter than [Factor.conflictReason]'s return value.
 */
internal fun defaultBoolPinsConflictReason(state: PropagationState): IntArray? {
    if (!state.allDecisionsAreBool()) return null
    val numBool = state.problem.numBoolVars
    var count = 0
    for (i in 0 until numBool) {
        if (state.boolValues[i] != null) count++
    }
    if (count == 0) return null
    val out = IntArray(count)
    var w = 0
    for (i in 0 until numBool) {
        val b = state.boolValues[i] ?: continue
        out[w++] = Lit.make(i, !b)
    }
    return out
}
