package com.eignex.klause.solver.backtrack.selector

/**
 * Which variable [com.eignex.klause.solver.backtrack.BacktrackSolver] is branching on. Independent of
 * value selection so var and value strategies can be combined freely (mirroring MiniZinc's
 * `int_search(vars, var_strategy, value_strategy, complete)`).
 */
sealed interface VarRef {
    /** The referenced variable id. */
    val varId: Int

    /** A Boolean variable reference. */
    data class Bool(override val varId: Int) : VarRef

    /** An integer variable reference. */
    data class IntVar(override val varId: Int) : VarRef
}
