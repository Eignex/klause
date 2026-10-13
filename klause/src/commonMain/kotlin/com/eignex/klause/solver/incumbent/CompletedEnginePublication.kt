package com.eignex.klause.solver.incumbent

internal sealed interface CompletedEnginePublication {
    val invocation: EngineInvocation
    val model: ModelIdentity

    class ObjectiveBound(
        override val invocation: EngineInvocation,
        val value: Double,
    ) : CompletedEnginePublication {
        override val model: ModelIdentity get() = invocation.model

        init {
            require(invocation.executionObjective != null) { "objective bound requires an execution objective" }
            require(value.isFinite()) { "objective bound must be finite" }
        }
    }

    class IntegerWindow(
        override val invocation: EngineInvocation,
        val coordinate: Int,
        val lower: Long,
        val upper: Long,
    ) : CompletedEnginePublication {
        override val model: ModelIdentity get() = invocation.model.forObjective(null)

        init {
            require(coordinate >= 0) { "integer coordinate must be nonnegative" }
        }
    }
}
