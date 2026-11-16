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

internal class SourcePublicationObserver(callback: ((SourceIncumbentPublication) -> Unit)?) {
    private val observer = IncumbentPublicationObserver(callback)

    val latest: SourceIncumbentPublication? get() = observer.latest

    fun accepted(sample: Sample, value: SourceValue, version: Long = observer.version + 1) {
        observer.accepted(SourceIncumbentPublication(version, sample, value), version)
    }

    fun <T> execute(action: () -> T): T = observer.execute(action)
}
