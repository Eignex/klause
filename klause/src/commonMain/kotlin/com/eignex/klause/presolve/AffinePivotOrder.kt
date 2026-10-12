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
     *  the cumulative fill-in budget for more eliminations. */
    MARKOWITZ,
}
