package com.eignex.klause.propagation

import com.eignex.klause.ir.Problem
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.WorkMeter

internal interface RootWorkMeter : WorkMeter {
    fun construction(entries: Long)
    fun propagation(units: Long)
}

internal class RootWorkAccounting(private val outer: ExecutionControl, private val state: PropagationState) :
    ExecutionControl() {
    private val meter = outer.accounting as? RootWorkMeter
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
    override val accounting get() = outer.accounting
    override val admission get() = admissionFrom(outer)

    fun construction(entries: Long) {
        meter?.construction(entries)
    }

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
