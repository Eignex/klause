package com.eignex.klause.localsearch.scoring

import com.eignex.klause.localsearch.LocalSearchState

/** The **scoring axis** of a local-search recipe: the basis on which the driver values a candidate
 *  move (lower is better). */
enum class MoveScoring {
    /** Per-factor weighted violation-grade delta (`Σ factorWeights[f]·Δgrade(f)`) — the
     *  CBLS gradient that learns which constraints resist repair. During infeasibility, Cumulative
     *  grades include their bounded fractional overload within the compression bucket. */
    Weighted,

    /** Unweighted violation-grade delta, including fractional Cumulative overload during infeasibility. */
    Raw,

    /** Shaped break score (`breakScore + shapingλ·objectiveΔ`, via [LocalSearchState.shapedBreakScore])
     *  — the focused WalkSAT/probSAT signal: the count of currently-satisfied factors a move would
     *  break, not the net delta. Already folds the shaped objective, so the driver adds no further
     *  objective term for this basis. */
    Break,
}
