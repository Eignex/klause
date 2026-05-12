package com.eignex.klause.solver

/**
 * Per-call constraint on the solver: pin specific variables to specific values for the
 * duration of the call. Compatible with all the entry points on [Sampler] and
 * [Optimizer]; backends that can't enforce assumptions (e.g. pure model-counting paths)
 * will document the limitation.
 *
 * Implementations are expected to:
 *  - initialise (or re-initialise on restart) the assignment with the assumed values,
 *  - skip any move proposal that would change an assumed variable,
 *  - leave the underlying [Problem] untouched — assumptions are call-scoped, not
 *    permanent constraints.
 *
 * If the assumed values are jointly infeasible against the problem's constraints the
 * solver may return `null` / `Unknown` rather than reporting `Unsat` (local-search
 * cannot prove UNSAT).
 */
data class Assumptions(
    val bools: Map<Int, Boolean> = emptyMap(),
    val ints: Map<Int, Int> = emptyMap(),
) {
    val isEmpty: Boolean get() = bools.isEmpty() && ints.isEmpty()

    fun isFrozenBool(id: Int): Boolean = id in bools
    fun isFrozenInt(id: Int): Boolean = id in ints

    companion object {
        val None: Assumptions = Assumptions()
    }
}
