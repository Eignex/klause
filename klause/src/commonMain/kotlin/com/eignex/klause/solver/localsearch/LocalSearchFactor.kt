package com.eignex.klause.solver.localsearch

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.localsearch.MoveSink

/**
 * A [Factor] that participates in local search. Adds the LS-side hooks the engine drives:
 * `initialize` / `isViolated` / `applyBoolFlip` / `applyIntSet` / `deltaIf*` /
 * `proposeRepairMoves`. The base [Factor] interface carries only the propagation contract,
 * so factors that only contribute to deductive propagation (no LS support) can implement
 * just [Factor] without paying for the LS hooks.
 *
 * Every factor in klause today implements [LocalSearchFactor] — [LocalSearchSolver] casts
 * unchecked under the assumption that any problem given to it has only LS-capable factors.
 */
interface LocalSearchFactor : Factor {
    /** Build this factor's payload from the current assignment. Called once per restart. */
    fun initialize(state: LocalSearchState, factorId: Int)

    fun isViolated(state: LocalSearchState, factorId: Int): Boolean

    /**
     * Δ in this factor's violation status if the given move were applied, computed without
     * mutating state. +1 means a satisfied factor would become violated, -1 the opposite.
     * Default returns 0; factors override the methods relevant to the move kinds they handle.
     */
    fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int = 0
    fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int = 0

    /**
     * Apply a committed move to this factor's payload. The assignment has already been
     * updated, so factors compare current values against the saved [oldValue] (for int sets)
     * or recover the pre-flip value by inversion. Returns the same delta the deltaIf* method
     * would have returned before the move.
     */
    fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int = 0
    fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int = 0

    /**
     * Suggest moves that would (or might) repair this factor when violated. The default lists
     * a Boolean flip per [Factor.boolVars] member plus an `IntSet(±1)` per [Factor.intVars]
     * member. Factors with structural insight (e.g. a comparator can snap to its bound)
     * override this.
     */
    fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        for (b in boolVars) sink.addBoolFlip(b)
        for (i in intVars) {
            val cur = state.assignment.intValue(i)
            val d = state.problem.intDomains[i]
            if (cur < d.max) sink.addIntSet(i, cur + 1)
            if (cur > d.min) sink.addIntSet(i, cur - 1)
        }
    }
}
