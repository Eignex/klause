package com.eignex.klause.solver.pipeline

import com.eignex.klause.solver.result.QuadraticIncumbent

/** An accepted exact source incumbent in one invocation's publication order. */
data class QuadraticIncumbentPublication(
    /** Monotonic version within the invocation. */
    val version: Long,
    /** Immutable original-source assignment and its already evaluated source objective. */
    val incumbent: QuadraticIncumbent,
)

internal class QuadraticPublicationObserver(callback: ((QuadraticIncumbentPublication) -> Unit)?) {
    private val observer = IncumbentPublicationObserver(callback)

    val latest: QuadraticIncumbentPublication? get() = observer.latest

    fun accepted(point: QuadraticIncumbent, version: Long = observer.version + 1) {
        observer.accepted(QuadraticIncumbentPublication(version, point), version)
    }

    fun <T> execute(action: () -> T): T = observer.execute(action)
}
