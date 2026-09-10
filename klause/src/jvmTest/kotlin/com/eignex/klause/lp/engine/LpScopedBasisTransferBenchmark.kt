package com.eignex.klause.lp.engine

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory

private var b5bSink = 0L

// Explicit bounded evidence entry point; ordinary test discovery never runs measurements.
fun main() {
    val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
    check(bean.isThreadAllocatedMemorySupported)
    bean.isThreadAllocatedMemoryEnabled = true
    for (dimension in DIMENSIONS) for (arm in ARMS) measure(dimension, arm, bean)
    repeat(REPETITIONS) { repetition ->
        for (dimension in DIMENSIONS) {
            for (arm in ARMS) println("B5bbench repetition=$repetition ${measure(dimension, arm, bean)}")
        }
    }
    check(b5bSink != Long.MIN_VALUE)
}

private fun measure(dimension: Int, arm: LpAppendSelection, bean: ThreadMXBean): String {
    val state = LpExactState(denseAppendModel(dimension))
    val solver = LpScopedSolver(state, refactorUpdateLimit = 100, maxRetainedRows = 64, appendSelection = arm)
    val initial = checkNotNull(solver.solve())
    check(initial.exactPrimal != null)
    val row = denseAppendedRow(dimension)
    val thread = Thread.currentThread().threadId()
    val beforeBytes = bean.getThreadAllocatedBytes(thread)
    val beforeNanos = System.nanoTime()
    check(solver.append(row, scoped = true))
    val result = checkNotNull(solver.solve())
    val elapsedNanos = System.nanoTime() - beforeNanos
    val allocatedBytes = bean.getThreadAllocatedBytes(thread) - beforeBytes
    check(result.exactPrimal != null && result.verdict == LpVerdict.ATTAINED_OPTIMUM)
    val metrics = solver.metrics
    b5bSink = b5bSink xor checkNotNull(result.lowerBound).hashCode().toLong()
    solver.close()
    check(metrics.currentOwners == 1L)
    return "dimension=$dimension arm=$arm elapsedNanos=$elapsedNanos allocatedBytes=$allocatedBytes " +
        "basisWork=${metrics.appendBasisWork} unknownWork=${metrics.appendUnknownWork} " +
        "engineWork=${solver.lastMetrics.workOps} attempts=${metrics.appendReplacementAttempts} " +
        "transfers=${metrics.appendTransfers} intendedFresh=${metrics.appendIntendedFreshBuilds} " +
        "fallbacks=${metrics.appendFallbacks} decline=${metrics.lastAppendDecline ?: "NONE"} " +
        "created=${metrics.createdOwners} closed=${metrics.closedOwners + 1} peak=${metrics.peakOwners}"
}

private fun denseAppendModel(dimension: Int): ExactLpModel {
    val zero = ExactLpNumber.of(0L)
    val one = ExactLpNumber.of(1L)
    val ten = ExactLpNumber.of(10L)
    val structural = List(dimension) {
        ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ten)), integral = false)
    }
    val logical = List(dimension) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero))) }
    return ExactLpModel(
        List(dimension) { column ->
            List(dimension) { row -> ExactLpEntry(row, ExactLpNumber.of(if (column == row) -10L else -1L)) }
        },
        List(dimension) { ExactLpNumber.of(-10L) },
        structural + logical,
        List(dimension) { ExactLpRow() },
        ExactLpObjective(List(dimension) { one } + List(dimension) { zero }),
    )
}

private fun denseAppendedRow(dimension: Int): LpScopedRow = LpScopedRow(
    dimension.toLong(),
    List((dimension + 3) / 4) { it to ExactLpNumber.of(-1L) },
    ExactLpNumber.of(-1L),
    ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(0L)))),
)

private const val REPETITIONS = 3
private val DIMENSIONS = listOf(16, 20)
private val ARMS = listOf(
    LpAppendSelection.FORCE_TRANSFER,
    LpAppendSelection.FRESH_INTENDED,
    LpAppendSelection.CANDIDATE,
    LpAppendSelection.PRODUCTION_FRESH,
)
