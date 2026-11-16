package com.eignex.klause.solver.pipeline

internal class IncumbentPublicationObserver<P>(
    private val callback: ((P) -> Unit)?,
) {
    var latest: P? = null
        private set
    var version: Long = 0
        private set
    private var failure: Throwable? = null

    @Suppress("TooGenericExceptionCaught")
    fun accepted(publication: P, version: Long) {
        latest = publication
        this.version = version
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
