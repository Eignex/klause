package com.eignex.klause.backtrack

import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.result.SearchStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.time.TimeSource

internal enum class InprocessingKind { Root, Probe, Subsumption, Vivification }

internal class InprocessingSpending(private val kind: InprocessingKind) {
    private var debt = 0L

    fun run(
        session: PropagationSession,
        params: BacktrackParams,
        stats: SearchStatsSink?,
        resumingRoot: Boolean = false,
        operation: (InprocessingBudget) -> Unit,
    ) {
        val limit = when (kind) {
            InprocessingKind.Root -> params.inprocessingRootWork
            InprocessingKind.Probe -> params.inprocessingProbeWork
            InprocessingKind.Subsumption -> params.inprocessingSubsumeWork
            InprocessingKind.Vivification -> params.inprocessingVivifyWork
        }
        val repaid = minOf(debt, limit)
        debt -= repaid
        val budget = InprocessingBudget(session, params, stats, kind, if (resumingRoot) 0L else limit - repaid)
        if (!resumingRoot && budget.stopped()) return
        budget.note("Runs")
        val start = TimeSource.Monotonic.markNow()
        try {
            session.withInprocessingCancellation(budget.cancellation) { operation(budget) }
        } finally {
            val spent = budget.spent
            debt = saturatedWork(debt, (spent - budget.limit).coerceAtLeast(0L))
            budget.note("Work", spent)
            budget.note("Nanos", start.elapsedNow().inWholeNanoseconds)
            if (budget.stopped()) budget.note("Stopped")
        }
    }
}

internal class InprocessingBudget(
    private val session: PropagationSession,
    private val params: BacktrackParams,
    private val stats: SearchStatsSink?,
    private val kind: InprocessingKind,
    val limit: Long,
) {
    private val start = session.work
    val spent: Long get() = (session.work - start).coerceAtLeast(0L)
    val cancellation = Cancellation { params.cancellation() || spent >= limit }

    fun stopped(): Boolean = spent >= limit || params.cancellation() || session.cancelRequested ||
        session.fixpointCancelled || session.isUnsatAtRoot

    fun visit(count: Int = 1): Boolean {
        if (stopped()) return false
        charge(count)
        return true
    }

    fun charge(count: Int) {
        session.chargeInprocessingWork(count.toLong())
        stats?.observeInprocessing(probes = 0L, visits = count.toLong())
    }

    fun truth(literal: Int): Boolean? {
        stats?.observeInprocessing(probes = 0L, visits = 1L)
        return session.litTruth(literal)
    }

    fun probe() {
        stats?.observeInprocessing(probes = 1L, visits = 0L)
        note("Probes")
    }

    fun note(metric: String, count: Long = 1L) {
        stats?.noteInprocessing("inprocess${kind.name}$metric", count)
    }
}

internal fun saturatedWork(left: Long, right: Long): Long =
    if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
