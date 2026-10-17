package com.eignex.klause.solver.result

import com.eignex.kumulant.core.Concurrency

/**
 * Bounded observations of aggregation MIR in one sequential finite portfolio invocation.
 * [encoded] contains the versioned primitive snapshot described in the benchmark record contract.
 * IDs identify this process and invocation; they do not identify original MPS text rows.
 * Collection is optional, retains no solver objects and supplies no witness, proof or ordinary
 * performance authority. Resource refusal and discarded foreign observations make [complete] false.
 */
class MirUseDiagnostics internal constructor(
    /** Process-local monotonic invocation identity. */
    val invocationId: Long,
    /** Immutable ASCII array of exact word metadata; at most 65,536 encoded bytes including metadata. */
    val encoded: String,
    /** Whether the collected first snapshots have no resource or identity omissions. */
    val complete: Boolean,
    /** Conservative scalar reservation, including collector bookkeeping. */
    val retainedScalars: Int,
    /** Whether merging refused observations from another invocation or an inconsistent snapshot. */
    val foreignObservationsDiscarded: Boolean = false,
)

internal fun mergeMirUse(first: MirUseDiagnostics?, second: MirUseDiagnostics?): MirUseDiagnostics? = when {
    first == null -> second
    second == null || first === second -> first
    first.invocationId == second.invocationId && first.encoded == second.encoded &&
        !second.foreignObservationsDiscarded -> first
    else -> MirUseDiagnostics(first.invocationId, first.encoded, false, first.retainedScalars, true)
}

private object MirInvocationIds {
    private val lock = Concurrency.Strict.lock()
    private var next = 0L

    fun allocate(): Long? = lock.withLock {
        if (next == Long.MAX_VALUE) null else ++next
    }
}

internal class MirUseBudget private constructor(val invocationId: Long) {
    private var words: LongArray? = null
    private var used = GLOBAL_WORDS
    private var owners = 0
    private var refusedOwners = 0L
    private var incomplete = false
    private var finished: MirUseDiagnostics? = null

    fun owner(seed: Long?, emphasis: Int, bools: Int, ints: Int, reals: Int, factors: Int): MirUseOwner? {
        if (finished != null) return null
        if (owners == MAX_OWNERS || !fits(OWNER_WORDS)) {
            refusedOwners = increment(refusedOwners)
            incomplete = true
            return null
        }
        val buffer = words ?: LongArray(MAX_WORDS).also { words = it }
        val offset = used
        used += OWNER_WORDS
        val id = ++owners
        buffer[offset] = OWNER_TAG
        buffer[offset + 1] = OWNER_WORDS.toLong()
        buffer[offset + 2] = id.toLong()
        buffer[offset + 3] = id.toLong()
        buffer[offset + 4] = if (seed == null) 0L else 1L
        buffer[offset + 5] = seed ?: 0L
        buffer[offset + 6] = emphasis.toLong()
        buffer[offset + 8] = bools.toLong()
        buffer[offset + 9] = ints.toLong()
        buffer[offset + 10] = reals.toLong()
        buffer[offset + 11] = factors.toLong()
        buffer[offset + 28] = -1L
        buffer[offset + 29] = -1L
        buffer[offset + 31] = -1L
        return MirUseOwner(this, offset, id)
    }

    private fun fits(count: Int): Boolean = count >= 0 && count <= MAX_WORDS - used &&
        2L * (21L * (used + count) + ENCODING_OVERHEAD) + ENCODING_OVERHEAD <= MAX_BYTES

    fun reserve(owner: MirUseOwner, size: Long, cut: Boolean): Int? {
        if (!owner.snapshotActive || finished != null) return null
        val index = if (cut) 21 else 20
        val limit = if (cut) MAX_CUTS else MAX_ROWS
        if (read(owner, index) >= limit || size > Int.MAX_VALUE || size < 0L || !fits(size.toInt())) {
            note(owner, 22)
            incomplete = true
            return null
        }
        val offset = used
        used += size.toInt()
        note(owner, index)
        return offset
    }

    fun put(offset: Int, value: Long) {
        words?.set(offset, value)
    }

    fun note(owner: MirUseOwner, field: Int) {
        val buffer = words ?: return
        val index = owner.offset + field
        buffer[index] = increment(buffer[index])
    }

    fun set(owner: MirUseOwner, field: Int, value: Long) {
        words?.set(owner.offset + field, value)
    }

    fun read(owner: MirUseOwner, field: Int): Long = words?.get(owner.offset + field) ?: 0L

    fun omitted(owner: MirUseOwner, field: Int) {
        note(owner, field)
        incomplete = true
    }

    fun finish(): MirUseDiagnostics {
        finished?.let { return it }
        val buffer = words ?: LongArray(GLOBAL_WORDS)
        buffer[0] = 1L
        buffer[1] = invocationId
        buffer[2] = 1L
        buffer[3] = owners.toLong()
        buffer[4] = refusedOwners
        buffer[5] = if (incomplete) 1L else 0L
        buffer[6] = used.toLong()
        buffer[7] = -1L
        val text = buildString(21 * used + 2) {
            append('[')
            for (index in 0 until used) {
                if (index > 0) append(',')
                append(buffer[index])
            }
            append(']')
        }
        return MirUseDiagnostics(invocationId, text, !incomplete, MAX_WORDS + BOOKKEEPING_SCALARS).also {
            finished = it
            words = null
        }
    }

    companion object {
        const val MAX_OWNERS = 8
        const val MAX_ROWS = 48
        const val MAX_CUTS = 16
        const val MAX_SCALARS = 2048
        const val MAX_BYTES = 65536
        const val MAX_WORDS = 1536
        const val BOOKKEEPING_SCALARS = 128
        const val GLOBAL_WORDS = 16
        const val OWNER_WORDS = 40
        const val OWNER_TAG = 1L
        const val POTENTIAL_TAG = 2L
        const val ROW_TAG = 3L
        const val CUT_TAG = 4L
        private const val ENCODING_OVERHEAD = 256

        fun create(): MirUseBudget? = MirInvocationIds.allocate()?.let(::MirUseBudget)

        private fun increment(value: Long): Long = if (value == Long.MAX_VALUE) value else value + 1L
    }
}

internal class MirUseOwner(
    private val budget: MirUseBudget,
    val offset: Int,
    val id: Int,
) {
    var snapshotActive: Boolean = false
        private set

    fun configure(flags: Long) {
        budget.set(this, 7, flags)
        budget.set(this, 39, 1L)
    }

    fun begin(root: Boolean, sourceMap: Boolean) {
        budget.note(this, if (root) 12 else 13)
        snapshotActive = budget.read(this, 19) == 0L
        if (snapshotActive) {
            budget.set(this, 19, 1L)
            budget.set(this, 28, if (sourceMap) 1L else 0L)
            budget.set(this, 29, if (root) 0L else 1L)
        }
    }

    fun sourceEpoch(epoch: Long) { if (snapshotActive) budget.set(this, 31, epoch) }

    fun end() { snapshotActive = false }
    fun count(field: Int) = budget.note(this, field)
    fun omit(field: Int) = budget.omitted(this, field)
    fun reserve(size: Long, cut: Boolean = false): Int? = budget.reserve(this, size, cut)
    fun put(offset: Int, value: Long) = budget.put(offset, value)
}
