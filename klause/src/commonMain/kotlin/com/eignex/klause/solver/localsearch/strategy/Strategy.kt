package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.schedule.RoundAccumulator

/** Picks the next move to commit. Returns `null` to signal the solver should restart. */
interface Strategy {
    /** Pick the next move to apply, or null when none is available. */
    fun pickMove(state: LocalSearchState): Move?

    /** Whether this strategy retunes from per-round feedback ([observeRound]); gates the engine's
     *  per-step round accumulation so the common (non-adaptive) strategies carry no overhead.
     *  Default `false`. */
    val wantsRoundFeedback: Boolean get() = false

    /** Feed a completed round of move statistics ([acc]) to this strategy's adaptive policies, the
     *  round ending at engine [step]. Only called when [wantsRoundFeedback]; the strategy snapshots
     *  the accumulator with its own temperature and drives its adaptive schedules. Default no-op. */
    fun observeRound(acc: RoundAccumulator, step: Long) = Unit
}

/** Basis on which a [Strategy] scores candidate moves. */
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
