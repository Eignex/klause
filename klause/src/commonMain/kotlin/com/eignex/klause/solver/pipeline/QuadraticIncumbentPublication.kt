package com.eignex.klause.solver.pipeline

import com.eignex.klause.solver.result.QuadraticIncumbent
import com.eignex.klause.solver.result.QuadraticOptimizationResult

/** An accepted exact source incumbent in one invocation's publication order. */
data class QuadraticIncumbentPublication(
    /** Monotonic version within the invocation. */
    val version: Long,
    /** Immutable original-source assignment and its already evaluated source objective. */
    val incumbent: QuadraticIncumbent,
)

internal class QuadraticPublicationObserver(
    private val callback: ((QuadraticIncumbentPublication) -> Unit)?,
) {
    var latest: QuadraticIncumbentPublication? = null
        private set
    private var failure: Throwable? = null

    @Suppress("TooGenericExceptionCaught")
    fun accepted(point: QuadraticIncumbent, version: Long = (latest?.version ?: 0) + 1) {
        val publication = QuadraticIncumbentPublication(version, point)
        latest = publication
        try {
            callback?.invoke(publication)
        } catch (error: Throwable) {
            failure = error
            throw error
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun <T> execute(action: () -> T): T = try {
        val result = action()
        failure?.let { throw it }
        result
    } catch (error: Throwable) {
        val primary = failure ?: error
        if (primary !== error) primary.addSuppressed(error)
        throw primary
    }

    fun matching(result: QuadraticOptimizationResult): QuadraticIncumbentPublication? {
        val point = when (result) {
            is QuadraticOptimizationResult.Optimal -> result.incumbent
            is QuadraticOptimizationResult.UnattainedLimit -> result.incumbent
            is QuadraticOptimizationResult.Unbounded -> result.incumbent
            is QuadraticOptimizationResult.BestFound -> result.incumbent
            is QuadraticOptimizationResult.Infeasible, is QuadraticOptimizationResult.Unknown -> return null
        }
        return latest?.takeIf { it.incumbent.sample === point.sample && it.incumbent.value === point.value }
    }
}
