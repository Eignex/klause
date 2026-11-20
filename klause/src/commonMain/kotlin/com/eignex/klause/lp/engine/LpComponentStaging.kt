package com.eignex.klause.lp.engine

import com.eignex.klause.util.ExecutionControl

internal class LpComponentStaging(private val workLimit: Long, token: ExecutionControl) {
    private val projection = LpProjectionMeter(
        workLimit = workLimit.takeIf { it > 0L } ?: Long.MAX_VALUE, cancellation = token,
    )
    private var reported = LpSolveMetrics()
    private var construction = 0L
    private var workspace = 0L
    private var constructionStarted = false
    private var completedConstruction = 0L

    init {
        require(workLimit >= 0L)
    }

    val work: Long get() = projection.work + reported.workOps
    val metrics: LpSolveMetrics get() = reported + LpSolveMetrics(workOps = projection.work)
    val reservedConstructionWork: Long
        get() = completedConstruction + if (constructionStarted) 0L else construction + workspace

    fun reserve(work: Long, bytes: Long = 0L) {
        if (workLimit > 0L) projection.restrictWork((workLimit - reported.workOps).coerceAtLeast(0L))
        projection.reserve(work, bytes, matrix = false)
    }

    fun reserveConstruction(work: Long, workspaceWork: Long = 0L) {
        check(construction == 0L && workspace == 0L)
        if (workspaceWork < 0L || work < 0L || workspaceWork > Long.MAX_VALUE - work) throw LpProjectionStop()
        reserve(work + workspaceWork)
        construction = work
        workspace = workspaceWork
        constructionStarted = false
    }

    fun beginConstruction(): Long? {
        val remaining = if (workLimit > 0L) (workLimit - work).takeIf { it > 0L } else 0L
        if (remaining != null) constructionStarted = true
        return remaining
    }

    fun startConstruction(): Long = beginConstruction() ?: throw LpProjectionStop()

    fun recordConstruction(
        metrics: LpSolveMetrics?,
        complete: Boolean = metrics != null && metrics.preparationOps > 0L,
    ) {
        if (metrics != null) {
            reported += metrics
            if (complete) completedConstruction += construction
        }
        // Default metrics do not establish that a throwing constructor or preparation reported its work.
        construction = 0L
        workspace = 0L
        constructionStarted = false
    }
}
