package com.eignex.klause.portfolio

import com.eignex.klause.solver.result.SolveStats

/**
 * Per-arm reward accounts for the sequential portfolio: what each arm earned, settled against what it spent.
 *
 * Credit is posted per [Signal], each in its own unit, at any time and for any arm, including one that is not
 * running; it waits in the arm's account until that arm's next segment settles it. An arm is scored on the rate
 * it earned each signal at, per unit of work, against the rate the rest of the pool earned it at this phase. A
 * rate rather than a total keeps a long segment from scoring higher for being long, and comparing each signal
 * only with itself puts signals in unrelated units on one scale without a hand-set weight between them.
 *
 * Rates pool over the current phase only; [resetPhase] starts a new one.
 */
internal class RewardLedger(private val arms: Int) {
    private val pending = Array(Signal.entries.size) { DoubleArray(arms) }
    private val earnedByArm = Array(Signal.entries.size) { DoubleArray(arms) }
    private val earned = DoubleArray(Signal.entries.size)
    private val spentByArm = LongArray(arms)
    private var spent = 0L

    /** Post [amount] of [signal] to [arm]'s account. Non-positive and non-finite amounts are ignored. */
    fun credit(arm: Int, signal: Signal, amount: Double) {
        if (amount > 0.0 && amount.isFinite()) pending[signal.ordinal][arm] += amount
    }

    /**
     * Settle [arm]'s account over the [work] its segment spent, returning a reward in `[0, 1]`.
     *
     * Each signal the pool has earned this phase scores `own / (own + others)` on rates: one for an arm the only
     * one earning it, a half for one earning it as fast as the rest of the pool does, zero for one earning
     * nothing. The reward is the mean over those signals.
     */
    fun settle(arm: Int, work: Long): Double {
        val segmentWork = work.coerceAtLeast(1L)
        spentByArm[arm] += segmentWork
        spent += segmentWork
        val othersWork = spent - spentByArm[arm]
        var share = 0.0
        var signals = 0
        for (s in pending.indices) {
            val own = pending[s][arm]
            pending[s][arm] = 0.0
            earnedByArm[s][arm] += own
            earned[s] += own
            if (earned[s] <= 0.0) continue
            signals++
            val ownRate = own / segmentWork
            val othersRate = if (othersWork > 0L) (earned[s] - earnedByArm[s][arm]) / othersWork else 0.0
            if (ownRate > 0.0) share += ownRate / (ownRate + othersRate)
        }
        return if (signals == 0) 0.0 else share / signals
    }

    /** Start a new phase: pooled rates and unsettled credit both clear. */
    fun resetPhase() {
        for (row in pending) row.fill(0.0)
        for (row in earnedByArm) row.fill(0.0)
        earned.fill(0.0)
        spentByArm.fill(0L)
        spent = 0L
    }
}

/** One kind of progress an arm is credited for in a [RewardLedger]. */
internal enum class Signal {
    /** A first feasible solution, before the run has an incumbent. */
    FirstSolution,

    /** Objective improvement of the shared incumbent. */
    Improvement,

    /** Variables a backtrack arm newly fixed at its root. */
    RootFixings,

    /** Share of the pool's record constraint violation a local-search arm removed. */
    Violation,
}

/**
 * Turns the counters a segment reports into progress credit, the graded signal a search earns before it has
 * anything to show for itself.
 *
 * A backtrack arm is credited for variables newly fixed at its root, the one kind of progress no later search
 * undoes; its count is the most the arm has shown, so a re-seeded handle does not earn the same fixings twice. A
 * local-search arm is credited for lowering the pool's record violation, by the share of the record it removed,
 * so getting close to a solution pays and merely matching the best so far does not.
 */
internal class ProgressCredit(arms: Int) {
    private val rootFixedSeen = DoubleArray(arms)
    private var recordViolation = Double.POSITIVE_INFINITY

    /** Credit [arm] in [ledger] for the progress in [stats], cumulative for a resumable handle or one segment's. */
    fun observe(ledger: RewardLedger, arm: Int, stats: SolveStats) {
        val fixed = stats.search.rootFixed.max
        if (fixed > rootFixedSeen[arm]) {
            ledger.credit(arm, Signal.RootFixings, fixed - rootFixedSeen[arm])
            rootFixedSeen[arm] = fixed
        }
        val violation = stats.ls.incumbentViolation
        if (violation.isNaN() || violation >= recordViolation) return
        if (recordViolation.isFinite()) {
            ledger.credit(arm, Signal.Violation, (recordViolation - violation) / recordViolation)
        }
        recordViolation = violation
    }
}
