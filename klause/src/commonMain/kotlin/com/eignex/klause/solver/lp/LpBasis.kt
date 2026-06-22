package com.eignex.klause.solver.lp

/** Outcome of an LP solve. */
internal enum class LpStatus {
    /** An optimal vertex was found. */
    OPTIMAL,

    /** The LP has no feasible point. For branch-and-bound this means the subtree is infeasible. */
    INFEASIBLE,

    /** The objective is unbounded below (minimization). Cannot happen when every variable is bounded. */
    UNBOUNDED,
}

/** Where a variable sits. Nonbasic variables are pinned to a finite bound; basic ones float. */
internal enum class VarStatus {
    /** Basic: the variable floats; its value is read off the basis. */
    BASIC,

    /** Nonbasic, pinned to its lower bound. */
    AT_LOWER,

    /** Nonbasic, pinned to its upper bound. */
    AT_UPPER,
}

/**
 * A basis: the `m` basic variable columns plus the bound each nonbasic variable is pinned to. The
 * float [RevisedSimplex] returns one for exact certification ([integerCertify]); because
 * branch-and-bound only tightens bounds, a parent basis stays dual-feasible so a child re-optimizes
 * with a few pivots instead of a cold solve.
 */
internal class Basis(
    /** The `m` variable columns that are basic. Order is irrelevant; the loader assigns rows. */
    val basicVars: IntArray,
    /** Per-variable status (length `numVars`): [VarStatus.BASIC], [VarStatus.AT_LOWER] or `AT_UPPER`. */
    val status: Array<VarStatus>,
)
