package com.eignex.klause.presolve

/**
 * The order affine elimination picks its pivots in. Elimination cost depends on the order as much as on
 * the set of pivots — folding the cheap ones first shrinks the constraint graph, so folds that would have
 * been expensive become cheap or stop existing — and every order yields the same solutions, so this is a
 * cost knob only.
 */
enum class AffinePivotOrder {

    /** Lowest stable id first, preserving model order without considering elimination cost. */
    STABLE_ID,

    /** Cheapest estimated fill first (Markowitz), the default. Prioritizing sparse folds preserves
     *  the cumulative fill-in budget for more eliminations. H. M. Markowitz, "The elimination form of the
     *  inverse and its application to linear programming", Management Science 3(3), 1957,
     *  https://doi.org/10.1287/mnsc.3.3.255. */
    MARKOWITZ,

    /** Markowitz ordering with two-column definitions oriented toward the smaller rewrite payload.
     *  Factor payload sizes estimate construction cost; they do not predict search savings. */
    REWRITE_COST,
}
