package com.eignex.klause.backtrack

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Where a pausable search's current slice ends: after a number of work units, or, when none is given, at a
 * wall-clock deadline.
 *
 * A work budget makes the pause point a property of the search rather than of machine load, which is what
 * lets two identical invocations report identical counters. A unit is one search node, and LP work is charged
 * against the same budget at [LP_WORK_PER_NODE] per node so an LP-heavy slice pauses about as late in wall time
 * as a CP one.
 */
internal class SliceBudget(private val nodeCount: () -> Long, private val lpWork: () -> Long) {
    /** The armed slice's wall-clock end; null before the first slice. */
    var deadline: TimeSource.Monotonic.ValueTimeMark? = null
        private set

    /** Whether the armed slice is bounded by work rather than by [deadline]. */
    var workBounded: Boolean = false
        private set

    // Node count at which the slice pauses. A flag and not the sign of the end marks a work bound: LP charges can
    // push the end below zero, and a negative end must still read as a spent budget.
    private var nodeEnd = 0L

    // LP work already turned into slice nodes. It runs across slices, so work done between two slices, the root LP
    // included, is charged to the next one.
    private var lpWorkMark = 0L

    // Nodes an earlier slice spent past its budget. One LP solve can cost many slices' worth of nodes, and a slice
    // cannot stop inside it, so the excess is repaid from the slices that follow.
    private var nodeDebt = 0L

    /**
     * Arm the next slice: [sliceNodes] work units when non-negative, else [sliceMillis] of wall time. False when
     * debt from earlier slices consumes the whole allowance, so the caller pauses without searching.
     */
    fun begin(sliceMillis: Long, sliceNodes: Long): Boolean {
        deadline = TimeSource.Monotonic.markNow() + sliceMillis.milliseconds
        workBounded = sliceNodes >= 0L
        nodeEnd = nodeCount() + sliceNodes.coerceAtLeast(0L)
        charge()
        if (!workBounded) return true
        val repaid = minOf(nodeDebt, sliceNodes)
        nodeDebt -= repaid
        nodeEnd -= repaid
        if (nodeEnd > nodeCount()) return true
        nodeDebt += nodeCount() - nodeEnd
        return false
    }

    /** Work spent so far: every node plus the LP work at [LP_WORK_PER_NODE] per node, charged or not. */
    fun spent(): Long = nodeCount() + lpWork() / LP_WORK_PER_NODE

    /** Whether the armed slice has spent its allowance. */
    fun expired(): Boolean = if (workBounded) nodeCount() >= nodeEnd else deadline?.hasPassedNow() ?: false

    /**
     * Spend the LP work done since the last charge from the slice's budget. The charge lands at a node boundary
     * and only moves the slice end, so the slice pauses at its next poll exactly as if it had explored that many
     * nodes; it stays a function of the search, and runs remain reproducible.
     */
    fun charge() {
        if (!workBounded) return
        val nodes = (lpWork() - lpWorkMark) / LP_WORK_PER_NODE
        if (nodes <= 0L) return
        nodeEnd -= nodes
        lpWorkMark += nodes * LP_WORK_PER_NODE
    }

    /** Carry what the slice spent past its budget, the LP work since the last charge included, into the next. */
    fun noteOverspend() {
        if (!workBounded) return
        charge()
        nodeDebt += (nodeCount() - nodeEnd).coerceAtLeast(0L)
    }
}

// LP work (simplex plus the node-LP overhead the engine charges) that costs about as much time as one CP search
// node: the median ratio of LP work per second with the default LP arm to conflictDriven's nodes per second, over
// 13 MIPLIB 2017 models with continuous columns, was 519 (spread 58 to 8145, geometric mean 629).
internal const val LP_WORK_PER_NODE = 600L
