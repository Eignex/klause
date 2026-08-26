package com.eignex.klause.presolve

/**
 * The order affine elimination picks its pivots in. Elimination cost depends on the order as much as on
 * the set of pivots — folding the cheap ones first shrinks the constraint graph, so folds that would have
 * been expensive become cheap or stop existing — and every order yields the same solutions, so this is a
 * cost knob only.
 */
enum class AffinePivotOrder {

    /** Lowest stable id first, i.e. the order the model presents its constraints in. Arbitrary with
     *  respect to elimination cost, and kept only as the baseline to measure against. */
    STABLE_ID,

    /** Cheapest estimated fill first (Markowitz), the default. The pass is bounded by a cumulative
     *  fill-in budget, so the order decides how many variables that budget buys: taking the model's own
     *  order spends it on a handful of runaway dense folds on the models that have any (31 eliminations
     *  for the whole budget on one MIPLIB instance, against 1167 for the same budget here). */
    MARKOWITZ,
}
