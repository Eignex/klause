package com.eignex.klause.lp.engine

import com.eignex.klause.util.ExecutionControl

internal class LpComponentStaging(private val workLimit: Long, token: ExecutionControl) {
    private val projection = LpProjectionMeter(
        workLimit = workLimit.takeIf { it > 0L } ?: Long.MAX_VALUE, cancellation = token,
    )
    private var reported = LpSolveMetrics()
    private var construction = 0L
    private var constructionStarted = false
    private var completedConstruction = 0L

    init {
        require(workLimit >= 0L)
    }

    val work: Long get() = projection.work + reported.workOps
    val metrics: LpSolveMetrics get() = reported + LpSolveMetrics(workOps = projection.work)
    val reservedConstructionWork: Long
        get() = completedConstruction + if (constructionStarted) 0L else construction

    fun reserve(work: Long, bytes: Long = 0L) {
        if (workLimit > 0L) projection.restrictWork((workLimit - reported.workOps).coerceAtLeast(0L))
        projection.reserve(work, bytes, matrix = false)
    }

    fun reserveConstruction(work: Long) {
        check(construction == 0L)
        reserve(work)
        construction = work
        constructionStarted = false
    }

    fun beginConstruction(): Long? {
        val remaining = if (workLimit > 0L) (workLimit - work).takeIf { it > 0L } else 0L
        if (remaining != null) constructionStarted = true
        return remaining
    }

    fun recordConstruction(metrics: LpSolveMetrics?) {
        if (metrics != null) {
            reported += metrics
            if (metrics.preparationOps > 0L) completedConstruction += construction
        }
        // Default metrics do not establish that a throwing constructor or preparation reported its work.
        construction = 0L
        constructionStarted = false
    }
}
