package com.eignex.klause.portfolio

import com.eignex.klause.solver.result.ArmSchedule
import com.eignex.klause.solver.result.PortfolioStats
import com.eignex.klause.solver.result.SharingStats
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
 * The pool's rate counts credit as earned when it is posted, not when it settles: an arm owed credit it has not yet
 * been scheduled to collect still earned it, and leaving it out would score every other arm as the only one earning
 * nothing on that signal, a full reward for half the work.
 *
 * An arm is scored only on the signals it [earns]: a signal its engine cannot produce says nothing about it, and
 * scoring it there would count every arm of the other engine against it. The pool's rate for a signal is likewise
 * taken over the work of the arms that can earn it.
 *
 * Rates pool over the current phase only; [resetPhase] starts a new one.
 */
internal class RewardLedger(
    private val arms: Int,
    /** Whether an arm can earn a signal at all; every arm earns every signal by default. */
    private val earns: (arm: Int, signal: Signal) -> Boolean = { _, _ -> true },
) {
    private val pending = Array(Signal.entries.size) { DoubleArray(arms) }
    private val earnedByArm = Array(Signal.entries.size) { DoubleArray(arms) }
    private val earned = DoubleArray(Signal.entries.size)
    private val spentByArm = LongArray(arms)
    private val total = Array(Signal.entries.size) { DoubleArray(arms) }

    /** Post [amount] of [signal] to [arm]'s account. Non-positive and non-finite amounts are ignored. */
    fun credit(arm: Int, signal: Signal, amount: Double) {
        if (amount <= 0.0 || !amount.isFinite()) return
        pending[signal.ordinal][arm] += amount
        earnedByArm[signal.ordinal][arm] += amount
        earned[signal.ordinal] += amount
        total[signal.ordinal][arm] += amount
    }

    /**
     * Settle [arm]'s account over the [work] its segment spent, returning a reward in `[0, 1]`.
     *
     * Each signal the arm [earns] and the pool has earned this phase scores `own / (own + others)` on rates: one
     * for an arm the only one earning it, a half for one earning it as fast as the rest of the pool does, zero for
     * one earning nothing. The reward is the mean over those signals.
     */
    fun settle(arm: Int, work: Long): Double {
        val segmentWork = work.coerceAtLeast(1L)
        spentByArm[arm] += segmentWork
        return score(arm, segmentWork)
    }

    /** Whether [arm] holds credit no settle has paid out yet. */
    fun hasPending(arm: Int): Boolean = pending.any { it[arm] > 0.0 }

    /**
     * Settle the credit [arm] earned while it was not running, scored as if over [overWork] of its own work. No work
     * is charged: the credit came from other arms using what this one shared.
     */
    fun settleIdle(arm: Int, overWork: Long): Double = score(arm, overWork.coerceAtLeast(1L))

    // Clear [arm]'s pending credit and score it over [segmentWork].
    private fun score(arm: Int, segmentWork: Long): Double {
        var share = 0.0
        var signals = 0
        for (s in pending.indices) {
            val own = pending[s][arm]
            pending[s][arm] = 0.0
            val signal = Signal.entries[s]
            if (earned[s] <= 0.0 || !earns(arm, signal)) continue
            signals++
            val othersWork = (0 until arms).sumOf { if (it != arm && earns(it, signal)) spentByArm[it] else 0L }
            val ownRate = own / segmentWork
            val othersRate = if (othersWork > 0L) (earned[s] - earnedByArm[s][arm]) / othersWork else 0.0
            if (ownRate > 0.0) share += ownRate / (ownRate + othersRate)
        }
        return if (signals == 0) 0.0 else share / signals
    }

    /** Everything credited to [arm] over the whole run, by signal name, leaving out signals it never earned. */
    fun creditOf(arm: Int): Map<String, Double> =
        Signal.entries.filter { total[it.ordinal][arm] > 0.0 }.associate { it.name to total[it.ordinal][arm] }

    /** Start a new phase: pooled rates and unsettled credit both clear. */
    fun resetPhase() {
        for (row in pending) row.fill(0.0)
        for (row in earnedByArm) row.fill(0.0)
        earned.fill(0.0)
        spentByArm.fill(0L)
    }
}

/** One kind of progress an arm is credited for in a [RewardLedger]. */
internal enum class Signal {
    /** A first feasible solution, before the run has an incumbent. */
    FirstSolution,

    /** Objective improvement of the shared incumbent. */
    Improvement,

    /** Rise of the pool's proven lower bound on the objective: the dual side of the gap. */
    Floor,

    /** Variables a complete search, backtrack or open theory, newly fixed at its root. */
    RootFixings,

    /** Share of the pool's record constraint violation a local-search arm removed. */
    Violation,

    /** Short learned clauses a complete search derived: the conflicts that teach it most. */
    Glue,

    /** Uses other arms made of this arm's shared clauses. */
    ClauseUses,

    /** Uses other arms made of this arm's shared cuts. */
    CutUses,

    /** Uses other arms made of this arm's shared root bounds. */
    BoundUses,
}

/** What a counted arm, local search or ALNS, earns: it finds and improves solutions and lowers violation. */
internal val COUNTED_SIGNALS: Set<Signal> = setOf(Signal.FirstSolution, Signal.Improvement, Signal.Violation)

/**
 * What a complete search, backtrack or open theory, earns on its own: it finds and improves solutions, fixes
 * variables at its root and learns clauses. What it shares earns the rest; see [PortfolioWorker.sharing].
 */
internal val SEARCH_SIGNALS: Set<Signal> =
    setOf(Signal.FirstSolution, Signal.Improvement, Signal.RootFixings, Signal.Glue)

/**
 * Turns the counters a segment reports into progress credit, the graded signal a search earns before it has
 * anything to show for itself.
 *
 * A complete-search arm is credited for variables newly fixed at its root, the one kind of progress no later search
 * undoes; its count is the most the arm has shown, so a re-seeded handle does not earn the same fixings twice. It
 * is credited too for the short clauses it learns, the conflicts that teach it most, though it has fixed nothing
 * yet. A local-search arm is credited for lowering the pool's record violation, by the share of the record it removed,
 * so getting close to a solution pays and merely matching the best so far does not.
 */
internal class ProgressCredit(arms: Int) {
    private val rootFixedSeen = DoubleArray(arms)
    private val glueSeen = DoubleArray(arms)
    private var recordViolation = Double.POSITIVE_INFINITY

    /** Credit [arm] in [ledger] for the progress in [stats], cumulative for a resumable handle or one segment's. */
    fun observe(ledger: RewardLedger, arm: Int, stats: SolveStats) {
        val fixed = stats.search.rootFixed.max
        if (fixed > rootFixedSeen[arm]) {
            ledger.credit(arm, Signal.RootFixings, fixed - rootFixedSeen[arm])
            rootFixedSeen[arm] = fixed
        }
        creditGrowth(ledger, arm, Signal.Glue, stats.search.glueClauses.sum, glueSeen)
        val violation = stats.ls.incumbentViolation
        if (violation.isNaN() || violation >= recordViolation) return
        if (recordViolation.isFinite()) {
            ledger.credit(arm, Signal.Violation, (recordViolation - violation) / recordViolation)
        }
        recordViolation = violation
    }

    // Credit what [total] grew by since [arm] last showed it. A re-seeded handle starts its counters over, so a
    // total below the last one seen is a new search: it is credited whole and becomes the new mark.
    private fun creditGrowth(ledger: RewardLedger, arm: Int, signal: Signal, total: Double, seen: DoubleArray) {
        val grown = if (total < seen[arm]) total else total - seen[arm]
        if (grown > 0.0) ledger.credit(arm, signal, grown)
        seen[arm] = total
    }
}

/** What the scheduler did with each arm over a run, reported as [SolveStats.portfolio]. */
internal class ScheduleLog(private val workers: List<PortfolioWorker>) {
    private val segments = LongArray(workers.size)
    private val work = LongArray(workers.size)
    private val millis = LongArray(workers.size)
    private val maxMillis = LongArray(workers.size)
    private val initializationMillis = LongArray(workers.size)
    private val initializationWork = LongArray(workers.size)
    private val initializationCancelled = LongArray(workers.size)
    private val rewards = DoubleArray(workers.size)
    private val failures = LongArray(workers.size)
    private val faults = LongArray(workers.size)
    private val reseeds = LongArray(workers.size)

    /** One segment of [arm]: the [spent] work, the [elapsed] milliseconds, the [reward] it settled for, and whether
     *  it [failed]. */
    fun record(arm: Int, spent: Long, elapsed: Long, reward: Double, failed: Boolean) {
        segments[arm]++
        work[arm] += spent
        millis[arm] += elapsed
        maxMillis[arm] = maxOf(maxMillis[arm], elapsed)
        rewards[arm] += reward
        if (failed) failures[arm]++
    }

    fun initialized(arm: Int, elapsed: Long, work: Long, cancelled: Boolean) {
        initializationMillis[arm] += elapsed
        initializationWork[arm] += work
        if (cancelled) initializationCancelled[arm]++
    }

    fun reseeded(arm: Int) {
        reseeds[arm]++
    }

    /** Count a refuted claim against [arm]. */
    fun fault(arm: Int) {
        faults[arm]++
    }

    /** The schedule so far, with each arm's credit read from [ledger]. Replicas of one arm share a
     *  worker label, so the second and later ones are numbered to keep each arm's report its own. */
    fun stats(ledger: RewardLedger): PortfolioStats {
        val seen = HashMap<String, Int>()
        return PortfolioStats(
            workers.indices.map { arm ->
                val label = workers[arm].label
                val occurrence = (seen[label] ?: 0) + 1
                seen[label] = occurrence
                ArmSchedule(
                    label = if (occurrence == 1) label else "$label#$occurrence",
                    segments = segments[arm],
                    work = work[arm],
                    millis = millis[arm],
                    maxMillis = maxMillis[arm],
                    initializationMillis = initializationMillis[arm],
                    reseeds = reseeds[arm],
                    initializationWork = initializationWork[arm],
                    initializationCancelled = initializationCancelled[arm],
                    meanReward = if (segments[arm] > 0L) rewards[arm] / segments[arm] else 0.0,
                    failures = failures[arm],
                    faults = faults[arm],
                    credit = ledger.creditOf(arm),
                    sharing = workers[arm].sharingMeter?.snapshot() ?: SharingStats(),
                )
            },
        )
    }
}
