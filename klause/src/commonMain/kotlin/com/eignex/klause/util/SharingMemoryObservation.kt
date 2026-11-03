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
    private var owners: Owner? = null
    private var ownedBytes = 0L
    private var protectedBytes = 0L
    private var ownedSlots = 0L
    private var protectedSlots = 0L
    private var sharedBytes = 0L

    private class Owner(val identity: Any, val capacity: Long, val references: Boolean, var protectedAmount: Long,
        var shared: Boolean) {
        val bytes: Long get() = if (references) 0L else capacity
        val slots: Long get() = if (references) capacity else 0L
        var left: Owner? = null
        var right: Owner? = null
    }

    fun owned(identity: Any, bytes: Long, protected: Boolean = false, shared: Boolean = false) =
        own(identity, bytes, 0L, if (protected) bytes else 0L, shared)

    fun ownedReferences(identity: Any, slots: Long, protected: Boolean = false) =
        own(identity, 0L, slots, if (protected) slots else 0L, false)

    // Pool reports cover an immutable region and its cumulative protected subset.
    fun ownedPool(identity: Any, bytes: Long, cumulativeProtectedBytes: Long) =
        own(identity, bytes, 0L, cumulativeProtectedBytes, false, pool = true)

    fun ownedReferencesPool(identity: Any, slots: Long, cumulativeProtectedSlots: Long) =
        own(identity, 0L, slots, cumulativeProtectedSlots, false, pool = true)

    private fun own(identity: Any, bytes: Long, slots: Long, protectedAmount: Long, shared: Boolean,
        pool: Boolean = false) {
        require(bytes >= 0L && slots >= 0L)
        require(protectedAmount >= 0L && protectedAmount <= if (slots == 0L) bytes else slots)
        if (bytes == 0L && slots == 0L) return
        step()
        val hash = identityHash(identity)
        var owner = owners
        var parent: Owner? = null
        var right = false
        var bit = 0
        while (owner != null) {
            step()
            if (owner.identity === identity && (owner.slots == 0L) == (slots == 0L)) {
                if (pool) require(owner.bytes == bytes && owner.slots == slots)
                if (shared && !owner.shared) { owner.shared = true; sharedBytes += owner.bytes }
                val retained = minOf(protectedAmount, if (slots == 0L) owner.bytes else owner.slots)
                if (retained > owner.protectedAmount) {
                    val added = retained - owner.protectedAmount
                    owner.protectedAmount = retained
                    if (slots == 0L) protectedBytes += added else protectedSlots += added
                }
                return
            }
            parent = owner
            right = bit < Int.SIZE_BITS && hash and (1 shl bit) != 0
            if (bit < Int.SIZE_BITS) bit++
            owner = if (right) owner.right else owner.left
        }
        if (scratchEntries >= maxScratchEntries) throw Stopped()
        step()
        scratchEntries++
        val added = Owner(identity, if (slots == 0L) bytes else slots, slots != 0L, protectedAmount, shared)
        if (parent == null) owners = added else if (right) parent.right = added else parent.left = added
        ownedBytes += bytes
        ownedSlots += slots
        if (slots == 0L) protectedBytes += protectedAmount else protectedSlots += protectedAmount
        if (shared) sharedBytes += bytes
    }

    init {
        require(maxWork >= 0L && maxScratchEntries >= 0)
    }

    fun step() {
        if (work >= maxWork || cancelled()) throw Stopped()
        charge()
        work++
    }

    fun <T : Any> seenIdentities(): Seen<T> = Seen(false)

    fun seenIds(): Seen<Int> = Seen(true)

    fun <T : Any> retain(seen: Seen<T>, value: T): Boolean {
        step()
        val hash = seen.hash(value)
        var node = seen.head
        var parent: Entry<T>? = null
        var right = false
        var bit = 0
        while (node != null) {
            step()
            if (seen.matches(node.value, value)) return false
            parent = node
            right = bit < Int.SIZE_BITS && hash and (1 shl bit) != 0
            if (bit < Int.SIZE_BITS) bit++
            node = if (right) node.right else node.left
        }
        if (scratchEntries >= maxScratchEntries) throw Stopped()
        step()
        scratchEntries++
        val added = Entry(value)
        if (parent == null) seen.head = added else if (right) parent.right = added else parent.left = added
        seen.size++
        return true
    }

    fun <T : Any> contains(seen: Seen<T>, value: T): Boolean {
        step()
        val hash = seen.hash(value)
        var node = seen.head
        var bit = 0
        while (node != null) {
            step()
            if (seen.matches(node.value, value)) return true
            val right = bit < Int.SIZE_BITS && hash and (1 shl bit) != 0
            if (bit < Int.SIZE_BITS) bit++
            node = if (right) node.right else node.left
        }
        return false
    }

    class Seen<T : Any> internal constructor(private val ids: Boolean) {
        internal var head: Entry<T>? = null
        internal var size: Int = 0
        internal fun hash(value: T): Int = if (ids) value as Int else identityHash(value)
        internal fun matches(left: T, right: T): Boolean = if (ids) left == right else left === right
    }

    internal class Entry<T : Any>(val value: T) {
        var left: Entry<T>? = null
        var right: Entry<T>? = null
    }

    fun record(fields: Map<String, Long>) { values.putAll(fields) }

    fun snapshot(scan: () -> Unit): SharingMemorySnapshot {
        val status = try {
            step()
            scan()
            if (cancelled()) SharingMemoryStatus.PARTIAL else SharingMemoryStatus.COMPLETE
        } catch (_: Stopped) {
            SharingMemoryStatus.PARTIAL
        }
        if (status == SharingMemoryStatus.COMPLETE) record(mapOf(
            "ownedPrimitiveBytes" to ownedBytes,
            "sharedPrimitiveBytes" to sharedBytes,
            "privatePrimitiveBytes" to ownedBytes - sharedBytes,
            "protectedPrimitiveBytes" to protectedBytes,
            "ownedReferenceSlots" to ownedSlots,
            "protectedReferenceSlots" to protectedSlots,
        ))
        return SharingMemorySnapshot(status, values.toMap(), work, scratchEntries)
    }

    private class Stopped : RuntimeException()

    companion object {
        const val DEFAULT_WORK = 32_768L
        const val DEFAULT_SCRATCH_ENTRIES = 4_096
    }
}
