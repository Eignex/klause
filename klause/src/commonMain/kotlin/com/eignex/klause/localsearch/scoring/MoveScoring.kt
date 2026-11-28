package com.eignex.klause.localsearch.scoring

import com.eignex.klause.localsearch.LocalSearchState

/** The **scoring axis** of a local-search recipe: the basis on which the driver values a candidate
 *  move (lower is better). */
enum class MoveScoring {
    /**
     * Weighted violation-grade delta `Σ weight(f)·Δgrade(f)`, the gradient that learns which constraints
     * resist repair. During infeasibility, Cumulative grades include their bounded fractional overload.
     */
    Weighted,

    /** Unweighted violation-grade delta, including fractional Cumulative overload during infeasibility. */
    Raw,

    /**
     * Shaped break score ([LocalSearchState.shapedBreakScore]): the count of satisfied factors a move would
     * break plus the shaped objective term, so the driver adds no further objective term.
     */
    Break,
}
