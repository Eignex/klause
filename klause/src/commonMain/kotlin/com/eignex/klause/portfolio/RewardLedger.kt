package com.eignex.klause.portfolio

import com.eignex.klause.solver.result.ArmFailure
import com.eignex.klause.solver.result.ArmSchedule
import com.eignex.klause.solver.result.PortfolioStats
import com.eignex.klause.solver.result.SharingStats
import com.eignex.klause.solver.result.SolveStats

// Credit is posted per signal, each in its own unit, at any time and for any arm, including one that is not
// running; it waits until that arm's next segment settles it. An arm is scored on the rate it earned each signal
// at, per unit of work, against the rate the rest of the pool earned it at this phase. A rate rather than a total
// keeps a long segment from scoring higher for being long, and comparing each signal only with itself puts
// signals in unrelated units on one scale without a hand-set weight.
//
// The pool's rate counts credit when it is posted, not when it settles: leaving out credit an arm has not yet
// collected would score every other arm as the only one earning nothing on that signal.
//
// An arm is scored only on the signals it earns: a signal its engine cannot produce says nothing about it. The
// pool's rate for a signal is likewise taken over the work of the arms that can earn it.
internal class RewardLedger(
    private val arms: Int,
    private val earns: (arm: Int, signal: Signal) -> Boolean = { _, _ -> true },
) {
    private val pending = Array(Signal.entries.size) { DoubleArray(arms) }
    private val earnedByArm = Array(Signal.entries.size) { DoubleArray(arms) }
    private val earned = DoubleArray(Signal.entries.size)
    private val spentByArm = LongArray(arms)
    private val total = Array(Signal.entries.size) { DoubleArray(arms) }

    fun credit(arm: Int, signal: Signal, amount: Double) {
        if (amount <= 0.0 || !amount.isFinite()) return
        pending[signal.ordinal][arm] += amount
        earnedByArm[signal.ordinal][arm] += amount
        earned[signal.ordinal] += amount
        total[signal.ordinal][arm] += amount
    }

    // Each signal the arm earns and the pool has earned this phase scores `own / (own + others)` on rates; the
    // reward in `[0, 1]` is the mean over those signals.
    fun settle(arm: Int, work: Long): Double {
        val segmentWork = work.coerceAtLeast(1L)
        spentByArm[arm] += segmentWork
        return score(arm, segmentWork)
    }

    fun hasPending(arm: Int): Boolean = pending.any { it[arm] > 0.0 }

    fun pendingCreditOf(arm: Int): Map<String, Double> =
        Signal.entries.filter { pending[it.ordinal][arm] > 0.0 }
            .associate { it.name to pending[it.ordinal][arm] }

    // No work is charged: the credit came from other arms using what this one shared.
    fun settleIdle(arm: Int, overWork: Long): Double = score(arm, overWork.coerceAtLeast(1L))

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

    // Over the whole run, unaffected by resetPhase.
    fun creditOf(arm: Int): Map<String, Double> =
        Signal.entries.filter { total[it.ordinal][arm] > 0.0 }.associate { it.name to total[it.ordinal][arm] }

    fun resetPhase() {
        for (row in pending) row.fill(0.0)
        for (row in earnedByArm) row.fill(0.0)
        earned.fill(0.0)
        spentByArm.fill(0L)
    }
}

internal enum class Signal {
    // Before the run has an incumbent.
    FirstSolution,

    Improvement,

    // Rise of the pool's proven lower bound on the objective.
    Floor,

    RootFixings,

    // Share of the pool's record constraint violation a local-search arm removed.
    Violation,

    // Short learned clauses: the conflicts that teach a complete search most.
    Glue,

    // Uses other arms made of this arm's shared clauses, cuts and root bounds.
    ClauseUses,

    CutUses,

    BoundUses,

    // A source-authorized terminal accepted by the coordinator.
    ProofClosure,
}

internal val COUNTED_SIGNALS: Set<Signal> = setOf(Signal.FirstSolution, Signal.Improvement, Signal.Violation)

// What a complete search earns on its own; what it shares earns the rest.
internal val SEARCH_SIGNALS: Set<Signal> =
    setOf(Signal.FirstSolution, Signal.Improvement, Signal.RootFixings, Signal.Glue)

// Graded credit a search earns before it has a solution to show. Root fixings are the one progress no later search
// undoes; counting the most the arm has shown keeps a re-seeded handle from earning the same fixings twice. Violation
// credit is the share of the pool's record removed, so matching the best so far earns nothing.
internal class ProgressCredit(arms: Int) {
    private val rootFixedSeen = DoubleArray(arms)
    private val glueSeen = DoubleArray(arms)
    private var recordViolation = Double.POSITIVE_INFINITY

    fun startSearch(arm: Int) {
        glueSeen[arm] = 0.0
    }

    // stats are cumulative for a resumable handle, else one segment's.
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

    private fun creditGrowth(ledger: RewardLedger, arm: Int, signal: Signal, total: Double, seen: DoubleArray) {
        val grown = total - seen[arm]
        if (grown > 0.0) ledger.credit(arm, signal, grown)
        seen[arm] = total
    }
}

internal class ScheduleLog(private val workers: List<PortfolioWorker>) {
    val progress = PortfolioProgressLog(workers)
    private val segments = LongArray(workers.size)
    private val work = LongArray(workers.size)
    private val millis = LongArray(workers.size)
    private val maxMillis = LongArray(workers.size)
    private val initializationMillis = LongArray(workers.size)
    private val initializationWork = LongArray(workers.size)
    private val initializationCancelled = LongArray(workers.size)
    private val rewards = DoubleArray(workers.size)
    private val failures = LongArray(workers.size)
    private val diagnostics = arrayOfNulls<ArmFailure>(workers.size)
    private val faults = LongArray(workers.size)
    private val reseeds = LongArray(workers.size)

    fun record(arm: Int, spent: Long, elapsed: Long, reward: Double, failed: Boolean) {
        segments[arm]++
        work[arm] += spent
        millis[arm] += elapsed
        maxMillis[arm] = maxOf(maxMillis[arm], elapsed)
        rewards[arm] += reward
        if (failed) failures[arm]++
    }

    fun failure(arm: Int, failure: Throwable, phase: String) {
        if (phase == "close") failures[arm]++
        if (diagnostics[arm] != null) return
        diagnostics[arm] = ArmFailure(
            workers[arm].armId,
            (failure::class.simpleName ?: "Throwable").take(128),
            failure.message?.take(1024),
            phase,
            segments[arm],
            work[arm],
            failure.stackTraceToString().take(4096),
        )
    }

    fun initialized(arm: Int, elapsed: Long, work: Long, cancelled: Boolean) {
        initializationMillis[arm] += elapsed
        initializationWork[arm] += work
        if (cancelled) initializationCancelled[arm]++
    }

    fun reseeded(arm: Int) {
        reseeds[arm]++
    }

    // A refuted claim.
    fun fault(arm: Int) {
        faults[arm]++
    }

    // Replicas of one arm share a worker label, so the second and later ones are numbered.
    fun stats(ledger: RewardLedger, proofLedger: RewardLedger = ledger): PortfolioStats {
        val seen = HashMap<String, Int>()
        return PortfolioStats(
            workers.indices.map { arm ->
                val label = workers[arm].label
                val occurrence = (seen[label] ?: 0) + 1
                seen[label] = occurrence
                ArmSchedule(
                    label = if (occurrence == 1) label else "$label#$occurrence",
                    role = workers[arm].role,
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
                    failure = diagnostics[arm],
                    faults = faults[arm],
                    credit = (if (workers[arm].auxiliaryProof) proofLedger else ledger).creditOf(arm),
                    sharing = workers[arm].sharingMeter?.snapshot() ?: SharingStats(),
                    phases = progress.phases(arm),
                    lifecycle = progress.lifecycle(arm),
                )
            },
        )
    }
}
