package com.eignex.klause.solver

import com.eignex.klause.solver.propagation.PropagationState

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
    /** Float-typed variables this factor touches. Defaults to empty so existing
     *  int-/bool-only factors don't need to opt in. Factors over real arithmetic
     *  ([FloatLinear], [FloatComparison]) populate this. */
    val floatVars: IntArray get() = EMPTY_INT_ARRAY

    /**
     * Deductive propagation given [state]'s current pins / domains. Pin or tighten anything
     * this factor implies; return `false` iff a contradiction is derived. Default is a no-op
     * — sound but trivial. Factors override to participate in [Problem.propagate].
     */
    fun propagate(state: PropagationState, factorId: Int): Boolean = true
}

private val EMPTY_INT_ARRAY = IntArray(0)
