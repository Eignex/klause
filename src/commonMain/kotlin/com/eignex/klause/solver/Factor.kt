package com.eignex.klause.solver

/**
 * Immutable constraint metadata. Mutable per-factor scratch lives in the [SolverState]
 * payload arrays. Variables touched by a factor split into two id spaces: Boolean vars in
 * [boolVars] and integer vars in [intVars]. Pure-Boolean factors leave [intVars] empty;
 * pure-integer factors leave [boolVars] empty; reified or mixed factors populate both.
 *
 * A factor's contribution to the global cost: violating a hard factor adds 1 to `hardCost`,
 * violating a soft factor adds [weight] to `softCost`.
 */
interface Factor {
    val boolVars: IntArray
    val intVars: IntArray

    val isHard: Boolean
    val weight: Double

    /** Build this factor's payload from the current assignment. Called once per restart. */
    fun initialize(state: SolverState, factorId: Int)

    fun isViolated(state: SolverState, factorId: Int): Boolean

    /**
     * Δ in this factor's violation status if the given move were applied, computed without
     * mutating state. +1 means a satisfied factor would become violated, -1 the opposite.
     * Default returns 0; factors override the methods relevant to the move kinds they handle.
     */
    fun deltaIfBoolFlipped(state: SolverState, factorId: Int, boolVar: Int): Int = 0
    fun deltaIfIntSet(state: SolverState, factorId: Int, intVar: Int, newValue: Int): Int = 0

    /**
     * Apply a committed move to this factor's payload. The assignment has already been
     * updated, so factors compare current values against the saved [oldValue] (for int sets)
     * or recover the pre-flip value by inversion. Returns the same delta the deltaIf* method
     * would have returned before the move.
     */
    fun applyBoolFlip(state: SolverState, factorId: Int, boolVar: Int): Int = 0
    fun applyIntSet(state: SolverState, factorId: Int, intVar: Int, oldValue: Int): Int = 0

    /**
     * Suggest moves that would (or might) repair this factor when violated. The default lists
     * a Boolean flip per [boolVars] member plus an `IntSet(±1)` per [intVars] member. Factors
     * with structural insight (e.g. a comparator can snap to its bound) override this.
     */
    fun proposeRepairMoves(state: SolverState, factorId: Int, sink: MoveSink) {
        for (b in boolVars) sink.addBoolFlip(b)
        for (i in intVars) {
            val cur = state.assignment.intValue(i)
            val d = state.problem.intDomains[i]
            if (cur < d.max) sink.addIntSet(i, cur + 1)
            if (cur > d.min) sink.addIntSet(i, cur - 1)
        }
    }
}
