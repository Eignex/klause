package com.eignex.klause.backtrack

import com.eignex.klause.solver.result.SearchStatsSink
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Where a pausable search's current slice ends: after a number of work units or at a wall-clock deadline,
 * whichever comes first.
 *
 * A work budget makes the pause point a property of the search rather than of machine load, which is what
 * lets two identical invocations report identical counters. A unit is one search node, and LP work is charged
 * against the same budget at [LP_WORK_PER_NODE] per node. Propagation dispatches and visits are charged at
 * [PROPAGATION_WORK_PER_NODE] per node. [nodeCount] is the search's own work in nodes, inprocessing included;
 * see [searchWork].
 */
internal class SliceBudget(
    private val nodeCount: () -> Long,
    private val lpWork: () -> Long,
    private val propagationWork: () -> Long = { 0L },
    preparation: SliceBudget? = null,
    // A containing budget already charges cumulative work and repays overspend for private searches.
    private val ownsWorkDebt: Boolean = true,
) {
    /** The armed slice's wall-clock end; null before the first slice. */
    var deadline: TimeSource.Monotonic.ValueTimeMark? = null
        private set

    /** Whether the armed slice is bounded by work rather than by [deadline]. */
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

    fun inheritPreparation(preparation: SliceBudget) {
        lpWorkMark = preparation.lpWorkMark
        propagationWorkMark = preparation.propagationWorkMark
        nodeDebt = preparation.nodeDebt
    }

    /**
     * Arm the next slice: [sliceNodes] work units when non-negative, and at most [sliceMillis] of wall time. False
     * when debt from earlier slices consumes the whole allowance, so the caller pauses without searching.
     */
    fun begin(sliceMillis: Long, sliceNodes: Long): Boolean {
        deadline = TimeSource.Monotonic.markNow() + sliceMillis.milliseconds
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

    /** Work spent so far: nodes plus LP and propagation work, charged or not. */
    fun spent(): Long = addWork(
        addWork(nodeCount(), lpWork() / LP_WORK_PER_NODE), propagationWork() / PROPAGATION_WORK_PER_NODE,
    )

    /** Whether the armed slice has spent its allowance. */
    fun expired(): Boolean {
        charge()
        return workExpired() || deadline?.hasPassedNow() == true
    }

    fun workExpired(): Boolean = workBounded && sliceSpent() >= nodeAllowance

    private fun sliceSpent(): Long = addWork((nodeCount() - nodeStart).coerceAtLeast(0L), auxiliaryNodes)

    /**
     * Spend the LP and propagation work done since the last charge from the slice's budget. The charge lands at a
     * cancellation poll or node boundary and increases the completed work, so the slice pauses as if it had explored
     * that many nodes; it stays a function of the search, and runs remain reproducible.
     */
    fun charge() {
        if (!workBounded) return
        val nodes = (lpWork() - lpWorkMark) / LP_WORK_PER_NODE
        auxiliaryNodes = addWork(auxiliaryNodes, nodes)
        lpWorkMark += nodes * LP_WORK_PER_NODE
        val propagationNodes = (propagationWork() - propagationWorkMark) / PROPAGATION_WORK_PER_NODE
        auxiliaryNodes = addWork(auxiliaryNodes, propagationNodes)
        propagationWorkMark += propagationNodes * PROPAGATION_WORK_PER_NODE
    }

    /** Carry what the slice spent past its budget, including uncharged work, into the next. */
    fun noteOverspend() {
        if (!workBounded) return
        charge()
        if (ownsWorkDebt) {
            val overspend = (sliceSpent() - nodeAllowance).coerceAtLeast(0L)
            nodeDebt = addWork(nodeDebt, (overspend - settledOverspend).coerceAtLeast(0L))
            settledOverspend = overspend
        }
    }
}

private fun addWork(left: Long, right: Long): Long =
    if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

// Converts simplex and node-LP work to approximate CP-node cost for slice accounting.
internal const val LP_WORK_PER_NODE = 600L

// Converts propagation visits to approximate CP-node cost without changing slice sizes.
internal const val PROPAGATION_WORK_PER_NODE = 4_000L

/** Decision work; inprocessing scans and probes enter the propagation-work ledger. */
internal val SearchStatsSink.searchWork: Long
    get() = nodeCount
