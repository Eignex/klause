package com.eignex.klause.util

internal enum class SharingMemoryStatus { COMPLETE, PARTIAL, STALE }

internal data class SharingMemorySnapshot(
    val status: SharingMemoryStatus,
    val values: Map<String, Long>,
    val work: Long,
    val scratchEntries: Int,
) {
    val completeValues: Map<String, Long>? get() = values.takeIf { status == SharingMemoryStatus.COMPLETE }

    fun telemetry(): Map<String, Long> = values + mapOf(
        "memorySampleComplete" to if (status == SharingMemoryStatus.COMPLETE) 1L else 0L,
        "memorySamplePartial" to if (status == SharingMemoryStatus.PARTIAL) 1L else 0L,
        "memorySampleStale" to if (status == SharingMemoryStatus.STALE) 1L else 0L,
        "memorySampleWork" to work,
        "memorySampleScratchEntries" to scratchEntries.toLong(),
    )
}

internal class SharingMemoryObservation(
    private val maxWork: Long,
    private val maxScratchEntries: Int,
    private val cancelled: () -> Boolean,
    private val charge: () -> Unit,
) {
    var work: Long = 0L
        private set
    private var scratchEntries = 0
    private val values = HashMap<String, Long>()

    init {
        require(maxWork >= 0L && maxScratchEntries >= 0)
    }

    fun step() {
        if (work >= maxWork || cancelled()) throw Stopped()
        charge()
        work++
    }

    fun <T : Any> seen(): Seen<T> = Seen()

    fun <T : Any> retain(seen: Seen<T>, value: T): Boolean {
        step()
        var node = seen.head
        while (node != null) {
            step()
            if (node.value == value) return false
            node = node.next
        }
        if (scratchEntries >= maxScratchEntries) throw Stopped()
        scratchEntries++
        seen.head = Entry(value, seen.head)
        seen.size++
        return true
    }

    class Seen<T : Any> {
        internal var head: Entry<T>? = null
        internal var size: Int = 0
    }

    internal class Entry<T : Any>(val value: T, val next: Entry<T>?)

    fun record(fields: Map<String, Long>) { values.putAll(fields) }

    fun snapshot(scan: () -> Unit): SharingMemorySnapshot {
        val status = try {
            step()
            scan()
            if (cancelled()) SharingMemoryStatus.PARTIAL else SharingMemoryStatus.COMPLETE
        } catch (_: Stopped) {
            SharingMemoryStatus.PARTIAL
        }
        return SharingMemorySnapshot(status, values.toMap(), work, scratchEntries)
    }

    private class Stopped : RuntimeException()

    companion object {
        const val DEFAULT_WORK = 32_768L
        const val DEFAULT_SCRATCH_ENTRIES = 4_096
    }
}
