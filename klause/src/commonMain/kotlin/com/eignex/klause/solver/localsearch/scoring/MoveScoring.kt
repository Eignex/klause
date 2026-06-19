package com.eignex.klause.solver.localsearch.scoring

import com.eignex.klause.solver.localsearch.LocalSearchState

/** The **scoring axis** of a local-search recipe: the basis on which the driver values a candidate
 *  move (lower is better). */
enum class MoveScoring {
    /** Per-factor weighted violation-count delta (`Σ factorWeights[f]·Δviolated[f]`) — the
     *  CBLS gradient that learns which constraints resist repair. */
    Weighted,

    /** Plain, unweighted violation-count delta — the classical VND / WalkSAT signal. */
    Raw,

    /** Shaped break score (`breakScore + shapingλ·objectiveΔ`, via [LocalSearchState.shapedBreakScore])
     *  — the focused WalkSAT/probSAT signal: the count of currently-satisfied factors a move would
     *  break, not the net delta. Already folds the shaped objective, so the driver adds no further
     *  objective term for this basis. */
    Break,
}
