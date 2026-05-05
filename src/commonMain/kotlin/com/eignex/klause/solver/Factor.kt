package com.eignex.klause.solver

/**
 * Immutable constraint metadata. Mutable per-factor scratch lives in the [SolverState] payload
 * arrays (e.g. `numSat[factorId]` for clauses). Factors are identified by their position
 * in [Problem.factors].
 *
 * Hard factors must be satisfied; violating them adds 1 to the global hard cost.
 * Soft factors contribute [weight] to the global soft cost when violated.
 */
interface Factor {
    /** Variables this factor reads, in any order. Used to build occurrence lists. */
    val variables: IntArray

    val isHard: Boolean
    val weight: Double

    /** Initialize this factor's payload in [state] from the current assignment. */
    fun initialize(state: SolverState, factorId: Int)

    /** True iff this factor is violated under [state]'s current assignment. */
    fun isViolated(state: SolverState, factorId: Int): Boolean

    /**
     * Δ in this factor's violation status if `variable` flips, computed without mutating state.
     * Returns +1 if a satisfied factor would become violated, -1 if a violated factor would
     * become satisfied, 0 otherwise.
     */
    fun deltaIfFlipped(state: SolverState, factorId: Int, variable: Int): Int

    /**
     * Apply a flip of `variable` to this factor's payload. Called after the assignment has
     * already been updated. Returns the same delta as [deltaIfFlipped] would have returned
     * before the flip.
     */
    fun applyFlip(state: SolverState, factorId: Int, variable: Int): Int
}
