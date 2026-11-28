package com.eignex.klause.backtrack

import com.eignex.klause.solver.result.SearchStatsSink
import com.eignex.klause.util.Cancellation
import com.eignex.util.numbers.saturatedAdd
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

// Where a pausable search's current slice ends: after a number of work units or at a wall-clock deadline,
// whichever comes first. A work budget makes the pause point a property of the search rather than of machine load.
// A unit is one search node; LP work costs LP_WORK_PER_NODE and propagation work PROPAGATION_WORK_PER_NODE per unit.
internal class SliceBudget(
    private val nodeCount: () -> Long,
    private val lpWork: () -> Long,
    private val propagationWork: () -> Long = { 0L },
    preparation: SliceBudget? = null,
    // A containing budget already charges cumulative work and repays overspend for private searches.
    private val ownsWorkDebt: Boolean = true,
) {
    var deadline: TimeSource.Monotonic.ValueTimeMark? = null
        private set

    private var timeExpired: Cancellation = Cancellation.Never

    var workBounded: Boolean = false
        private set

    // Relative counters avoid overflowing an absolute node end for a large offered allowance.
    private var nodeStart = 0L
    private var nodeAllowance = 0L
    private var auxiliaryNodes = 0L
    private var settledOverspend = 0L

    // LP work already turned into slice nodes. It runs across slices, so work done between two slices, the root LP
    // included, is charged to the next one.
    private var lpWorkMark: Long = preparation?.lpWorkMark ?: 0L
    private var propagationWorkMark: Long = preparation?.propagationWorkMark ?: 0L

    // Atomic LP setup, factorization and certification can overrun a slice even when numerical iterations yield.
    // The excess is repaid from the slices that follow.
    private var nodeDebt: Long = preparation?.nodeDebt ?: 0L

    var repaidWork: Long = 0L
        private set

    fun inheritPreparation(preparation: SliceBudget) {
        lpWorkMark = preparation.lpWorkMark
        propagationWorkMark = preparation.propagationWorkMark
        nodeDebt = preparation.nodeDebt
    }

    // False when debt from earlier slices consumes the whole allowance, so the caller pauses without searching.
    fun begin(sliceMillis: Long, sliceNodes: Long): Boolean {
        repaidWork = 0L
        val end = TimeSource.Monotonic.markNow() + sliceMillis.milliseconds
        deadline = end
        timeExpired = Cancellation.until(end)
        workBounded = sliceNodes >= 0L
        nodeStart = nodeCount()
        nodeAllowance = sliceNodes.coerceAtLeast(0L)
        auxiliaryNodes = 0L
        settledOverspend = 0L
        if (!ownsWorkDebt) {
            lpWorkMark = lpWork()
            propagationWorkMark = propagationWork()
        }
        charge()
        if (!workBounded) return true
        if (!ownsWorkDebt) return remainingNodes() > 0L
        val repaid = minOf(nodeDebt, sliceNodes)
        repaidWork = repaid
        nodeDebt -= repaid
        nodeAllowance -= repaid
        if (remainingNodes() > 0L) return true
        noteOverspend()
        return false
    }

    fun remainingNodes(): Long {
        charge()
        return if (workBounded) (nodeAllowance - sliceSpent()).coerceAtLeast(0L) else -1L
    }

    fun remainingLpWork(): Long? {
        val nodes = remainingNodes()
        if (nodes < 0L) return null
        val fractional = (lpWork() - lpWorkMark).coerceAtLeast(0L)
        val allowance = nodes.coerceAtMost(Long.MAX_VALUE / LP_WORK_PER_NODE) * LP_WORK_PER_NODE
        return (allowance - fractional).coerceAtLeast(0L)
    }

    // Nodes plus LP and propagation work, charged or not.
    fun spent(): Long = saturatedAdd(
        saturatedAdd(nodeCount(), lpWork() / LP_WORK_PER_NODE), propagationWork() / PROPAGATION_WORK_PER_NODE,
    )

    fun expired(): Boolean {
        charge()
        return workExpired() || timeExpired()
    }

    fun workExpired(): Boolean = workBounded && sliceSpent() >= nodeAllowance

    private fun sliceSpent(): Long = saturatedAdd((nodeCount() - nodeStart).coerceAtLeast(0L), auxiliaryNodes)

    // Charges LP and propagation work done since the last charge at a poll or node boundary, so the slice pauses as
    // if it had explored that many nodes and runs stay reproducible.
    fun charge() {
        if (!workBounded) return
        val nodes = (lpWork() - lpWorkMark) / LP_WORK_PER_NODE
        auxiliaryNodes = saturatedAdd(auxiliaryNodes, nodes)
        lpWorkMark += nodes * LP_WORK_PER_NODE
        val propagationNodes = (propagationWork() - propagationWorkMark) / PROPAGATION_WORK_PER_NODE
        auxiliaryNodes = saturatedAdd(auxiliaryNodes, propagationNodes)
        propagationWorkMark += propagationNodes * PROPAGATION_WORK_PER_NODE
    }

    // Carries what the slice spent past its budget, uncharged work included, into the next.
    fun noteOverspend() {
        if (!workBounded) return
        charge()
        if (ownsWorkDebt) {
            val overspend = (sliceSpent() - nodeAllowance).coerceAtLeast(0L)
            nodeDebt = saturatedAdd(nodeDebt, (overspend - settledOverspend).coerceAtLeast(0L))
            settledOverspend = overspend
        }
    }
}

// Converts simplex and node-LP work to approximate CP-node cost for slice accounting.
internal const val LP_WORK_PER_NODE = 600L

// Converts propagation visits to approximate CP-node cost without changing slice sizes.
internal const val PROPAGATION_WORK_PER_NODE = 4_000L

// Decision work; inprocessing scans and probes enter the propagation-work ledger.
internal val SearchStatsSink.searchWork: Long
    get() = nodeCount
