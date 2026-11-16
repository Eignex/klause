package com.eignex.klause.solver.pipeline

import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.includingStop

internal class ConditionedQueryScope(private val lifetime: ExecutionControl) : AutoCloseable {
    private val conditions = ArrayList<ExactNeighborhoodCondition>()
    private var invocation = lifetime
    private var closed = false

    val stop: ExecutionControl = object : ExecutionControl() {
        override val stopPolling get() = invocation.stopPolling
        override fun isCancelled(): Boolean = closed || invocation()
        override fun deadline() = invocation.deadline()
        override val accounting get() = invocation.accounting
        override val admission get() = admissionFrom(invocation)
    }

    fun <T> withCondition(
        condition: ExactNeighborhoodCondition,
        cancellation: ExecutionControl,
        action: (List<ExactNeighborhoodCondition>) -> T,
    ): T {
        check(!closed) { "conditioned query owner is closed" }
        val previous = invocation
        invocation = previous .withAccounting(cancellation).includingStop( cancellation)
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
