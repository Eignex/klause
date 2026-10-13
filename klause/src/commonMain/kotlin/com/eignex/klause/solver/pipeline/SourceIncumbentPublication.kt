package com.eignex.klause.solver.pipeline

import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceValue

/** A verified source incumbent installed by one invocation, independent of scalar telemetry. */
data class SourceIncumbentPublication(
    /** Monotonic installation version within the invocation. */
    val version: Long,
    /** Owned original-source assignment. */
    val sample: Sample,
    /** Already evaluated exact value in the original objective direction. */
    val value: SourceValue,
)

internal class SourcePublicationObserver(
    private val callback: ((SourceIncumbentPublication) -> Unit)?,
) {
    var latest: SourceIncumbentPublication? = null
        private set
    private var failure: Throwable? = null

    @Suppress("TooGenericExceptionCaught")
    fun accepted(sample: Sample, value: SourceValue, version: Long = (latest?.version ?: 0) + 1) {
        val publication = SourceIncumbentPublication(version, sample, value)
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
}
