package com.eignex.klause.solver.search

internal class SearchInvocationResources : AutoCloseable {
    private val resources = ArrayList<AutoCloseable>()
    private var closed = false

    fun <T : AutoCloseable> own(resource: T): T {
        check(!closed) { "search invocation is closed" }
        if (resources.none { it === resource }) resources += resource
        return resource
    }

    fun ownComponent(component: SearchComponent) {
        if (component is AutoCloseable) own(component)
    }

    override fun close() = closeAfter(null)

    @Suppress("TooGenericExceptionCaught") // Every owner must release even when an earlier cleanup fails.
    fun closeAfter(primary: Throwable?) {
        if (closed) return
        closed = true
        val pending = resources.toList()
        resources.clear()
        var failure = primary
        for (resource in pending.asReversed()) {
            try {
                resource.close()
            } catch (cleanup: Throwable) {
                val previous = failure
                if (previous == null) failure = cleanup
                else if (previous !== cleanup) previous.addSuppressed(cleanup)
            }
        }
        if (primary == null) failure?.let { throw it }
    }
}
