package com.eignex.klause.backtrack

import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.result.SearchStatsSink
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.PollStride
import com.eignex.klause.util.pollStop
import com.eignex.util.numbers.saturatedAdd
import kotlin.time.TimeSource

internal enum class InprocessingKind { Root, Probe, Subsumption, Vivification }

internal class InprocessingSpending(private val kind: InprocessingKind) {
    private var debt = 0L

    fun run(
        session: PropagationSession,
        params: BacktrackParams,
        stats: SearchStatsSink?,
        resumingRoot: Boolean = false,
        containingWork: Long = Long.MAX_VALUE,
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
        if (repaid > 0L) stats?.noteInprocessing("inprocess${kind.name}DebtRepaid", repaid)
        val budget = InprocessingBudget(session, params, stats, kind,
            if (resumingRoot) 0L else limit - repaid, containingWork)
        if (!resumingRoot && budget.checkpoint()) return
        budget.note("Runs")
        val start = TimeSource.Monotonic.markNow()
        try {
            session.withInprocessingCancellation(budget.cancellation) { operation(budget) }
        } finally {
            val spent = budget.spent
            val added = (spent - budget.limit).coerceAtLeast(0L)
            debt = saturatedAdd(debt, added)
            if (added > 0L) budget.note("DebtAdded", added)
            budget.note("Work", spent)
            stats.noteInprocessingDuration("inprocess${kind.name}", start.elapsedNow().inWholeNanoseconds)
            if (budget.checkpoint()) budget.note("Stopped")
        }
    }
}

internal class InprocessingBudget(
    private val session: PropagationSession,
    private val params: BacktrackParams,
    private val stats: SearchStatsSink?,
    private val kind: InprocessingKind,
    val limit: Long,
    private val containingLimit: Long = Long.MAX_VALUE,
) {
    private val start = session.work
    val spent: Long get() = (session.work - start).coerceAtLeast(0L)
    val remaining: Long get() = (minOf(limit, containingLimit) - spent).coerceAtLeast(0L)
    val cancellation = object : ExecutionControl() {
        override fun isCancelled(): Boolean = params.cancellation() || remaining == 0L
        override val stopPolling get() = params.cancellation.stopPolling
        override fun deadline() = params.cancellation.deadline() }
    private val stride = PollStride(calls = 256)

    fun checkpoint(): Boolean = remaining == 0L || params.cancellation() || session.cancelRequested ||
        session.fixpointCancelled || session.isUnsatAtRoot

    fun stopped(): Boolean = remaining == 0L || params.cancellation.pollStop() || session.cancelRequested ||
        session.fixpointCancelled || session.isUnsatAtRoot

    fun visit(count: Int = 1): Boolean {
        if (pollStopped(count)) return false
        charge(count)
        return true
    }

    fun pollStopped(count: Int = 1): Boolean = remaining == 0L || session.fixpointCancelled || session.isUnsatAtRoot ||
        if (params.cancellation.stopPolling != null) {
            params.cancellation.pollStop(count)
        } else {
            (stride.due(count.toLong()) && stopped())
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

internal fun SearchStatsSink?.noteInprocessingDuration(prefix: String, nanos: Long) {
    this ?: return
    noteInprocessing("${prefix}Nanos", nanos)
    val bucket = when {
        nanos < 1_000_000L -> "Under1ms"
        nanos < 10_000_000L -> "Under10ms"
        nanos < 100_000_000L -> "Under100ms"
        nanos < 1_000_000_000L -> "Under1s"
        else -> "AtLeast1s"
    }
    noteInprocessing("${prefix}Latency$bucket", 1L)
}
