package com.eignex.klause.presolve

import com.eignex.klause.ir.Problem

/**
 * The order affine elimination picks its pivots in. Elimination cost depends on the order as much as on
 * the set of pivots — folding the cheap ones first shrinks the constraint graph, so folds that would have
 * been expensive become cheap or stop existing — and every order yields the same solutions, so this is a
 * cost knob only.
 */
enum class AffinePivotOrder {

    /** Lowest stable id first, preserving model order without considering elimination cost. */
    STABLE_ID,

    /** Cheapest estimated fill first (Markowitz). Prioritizing sparse folds preserves
     *  the cumulative fill-in budget for more eliminations. H. M. Markowitz, "The elimination form of the
     *  inverse and its application to linear programming", Management Science 3(3), 1957,
     *  https://doi.org/10.1287/mnsc.3.3.255. */
    MARKOWITZ,

    /** Markowitz ordering with the smallest incident unit-pivot rewrite payload tried first.
     *  Admits folds through word-sized reified linear consumers while retaining their Boolean outputs,
     *  and unit translations or sign reversals through native comparison clauses.
     *  Reuses existing exact binary channels for cost terms within a mixed integer objective.
     *  Factor payload sizes estimate construction cost; they do not predict search savings. */
    REWRITE_COST,

    /** Markowitz ordering with payload scoring when the first admitted pivot needs structured consumers.
     *  Retains declaration order for ordinary consumers and aliases.
     *  Reuses existing exact binary cost channels within mixed integer objectives. */
    STRUCTURED,

    /** The default query policy: structured rewrites for exact discrete optimization, Markowitz otherwise.
     *  Retains binary integer inputs kept by binary substitution. */
    AUTOMATIC;

    internal val allowsStructuredRewrites: Boolean
        get() = this == STRUCTURED || this == REWRITE_COST

    internal fun forQuery(problem: Problem, optimizing: Boolean): AffinePivotOrder = when {
        this != AUTOMATIC -> this
        optimizing && problem.numRealVars == 0 && !problem.semantics.isApproximate -> STRUCTURED
        else -> MARKOWITZ
    }
}
