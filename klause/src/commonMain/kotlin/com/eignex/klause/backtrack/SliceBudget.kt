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

    // Nodes an earlier slice spent past its budget. One LP solve can cost many slices' worth of nodes, and a slice
    // cannot stop inside it, so the excess is repaid from the slices that follow.
    private var nodeDebt: Long = preparation?.nodeDebt ?: 0L

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

    /** Work spent so far: nodes plus LP and propagation work, charged or not. */
    fun spent(): Long = addWork(
        addWork(nodeCount(), lpWork() / LP_WORK_PER_NODE), propagationWork() / PROPAGATION_WORK_PER_NODE,
    )

    /** Whether the armed slice has spent its allowance. */
    fun expired(): Boolean = workExpired() || deadline?.hasPassedNow() == true

    fun workExpired(): Boolean = workBounded && sliceSpent() >= nodeAllowance

    private fun sliceSpent(): Long = addWork((nodeCount() - nodeStart).coerceAtLeast(0L), auxiliaryNodes)

    /**
     * Spend the LP and propagation work done since the last charge from the slice's budget. The charge lands at a
     * node boundary and increases the completed work, so the slice pauses at its next poll as if it had explored
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

// LP work (simplex plus the node-LP overhead the engine charges) that costs about as much time as one CP search
// node: the median ratio of LP work per second with the default LP arm to conflictDriven's nodes per second, over
// 13 MIPLIB 2017 models with continuous columns, was 519 (spread 58 to 8145, geometric mean 629).
internal const val LP_WORK_PER_NODE = 600L

// Propagation visits per cheap search node: median measured rate ratio 3936 over four controls
// (range 2192 to 7135). The rate converts work; it does not change the scheduler's slice sizes.
internal const val PROPAGATION_WORK_PER_NODE = 4_000L

/** The search work a slice is charged, in nodes: every node, plus inprocessing at its measured rates. */
internal val SearchStatsSink.searchWork: Long
    get() = nodeCount + inprocessProbes / INPROCESS_PROBES_PER_NODE + inprocessVisits / INPROCESS_VISITS_PER_NODE

// Vivification probes, and clause literals subsumption scans, in the time one search node of the satOptimized arm
// takes: the median ratios over 42 PB and SAT models were 11.9 (spread 0.5 to 57) and 18658 (spread 127 to 1.7M).
// A probe propagates one literal and undoes it, but a node also analyses conflicts and learns.
internal const val INPROCESS_PROBES_PER_NODE = 12L

internal const val INPROCESS_VISITS_PER_NODE = 18_000L
