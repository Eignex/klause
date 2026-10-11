package com.eignex.klause.propagation

import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter

internal interface RootWorkMeter : WorkMeter {
    fun construction(entries: Long)
    fun propagation(units: Long)
}

internal class RootWorkAccounting(
    private val outer: Cancellation,
    private val state: PropagationState,
) : Cancellation {
    private val meter = outer.workMeter() as? RootWorkMeter
    private var reported = 0L

    init {
        meter?.construction(constructionEntries(state.problem))
        flush()
    }

    override fun isCancelled(): Boolean {
        flush()
        return outer()
    }

    override fun deadline() = outer.deadline()
    override fun workMeter() = outer.workMeter()

    fun flush() {
        val current = state.work
        meter?.propagation(current - reported)
        reported = current
    }

    private fun constructionEntries(problem: Problem): Long {
        var entries = problem.numBoolVars.toLong() + problem.numIntVars + problem.numRealVars + problem.numFactors
        for (factor in problem.factors) entries += factor.structuralKeyWeight
        return entries
    }
}
