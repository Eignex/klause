package com.eignex.klause.solver.pipeline

import com.eignex.klause.util.Cancellation

internal class ConditionedQueryScope(private val lifetime: Cancellation) : AutoCloseable {
    private val conditions = ArrayList<ExactNeighborhoodCondition>()
    private var invocation = lifetime
    private var closed = false

    val stop: Cancellation = object : Cancellation {
        override fun isCancelled(): Boolean = closed || invocation()
        override fun deadline() = invocation.deadline()
        override fun workMeter() = invocation.workMeter()
    }

    fun <T> withCondition(
        condition: ExactNeighborhoodCondition,
        cancellation: Cancellation,
        action: (List<ExactNeighborhoodCondition>) -> T,
    ): T {
        check(!closed) { "conditioned query owner is closed" }
        val previous = invocation
        invocation = previous or cancellation
        conditions += ExactNeighborhoodCondition(condition.bools.toMap(), condition.lower.toMap(),
            condition.upper.toMap(), condition.cutoff)
        return try {
            action(conditions.toList())
        } finally {
            conditions.removeAt(conditions.lastIndex)
            invocation = previous
        }
    }

    override fun close() {
        closed = true
    }
}
