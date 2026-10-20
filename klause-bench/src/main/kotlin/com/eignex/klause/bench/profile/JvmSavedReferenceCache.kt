package com.eignex.klause.bench.profile

internal class JvmSavedReferenceCache<K : Any, V : Any>(
    private val maximumEntries: Int = 4096,
    private val maximumBytes: Long = 16L * 1024 * 1024,
) {
    private data class Entry<V>(val value: V, val bytes: Long)

    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)
    private var bytes = 0L

    init {
        require(maximumEntries > 0 && maximumBytes > 0)
    }

    fun getOrLoad(key: K, load: () -> Pair<V, Long>): V {
        entries[key]?.let { return it.value }
        val (value, weight) = load()
        require(weight >= 0) { "Reference cache weight is negative" }
        if (weight > maximumBytes) return value
        val iterator = entries.entries.iterator()
        while (entries.size >= maximumEntries || bytes > maximumBytes - weight) {
            bytes -= iterator.next().value.bytes
            iterator.remove()
        }
        entries[key] = Entry(value, weight)
        bytes += weight
        return value
    }

    fun clear() {
        entries.clear()
        bytes = 0
    }
}
