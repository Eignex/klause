package com.eignex.klause.solver

import com.eignex.klause.solver.Lit
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
 * This base contract carries only what every solver backend needs: the var sets and the
 * deductive [propagate] hook (default: no-op). Factors that participate in local search
 * additionally implement [com.eignex.klause.solver.localsearch.LocalSearchFactor], which
 * adds the `initialize` / `isViolated` / `applyBoolFlip` / `applyIntSet` / `deltaIf*` /
 * `proposeRepairMoves` hooks the LS engine drives.
 *
 * Every factor in klause today implements `LocalSearchFactor`. A factor that only
 * propagates (no LS support) is possible but unusual; documenting the split makes
 * propagation-only constraint kinds (e.g. expensive global constraints) safe to add.
 */
interface Factor {
    val boolVars: IntArray
    val intVars: IntArray

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
