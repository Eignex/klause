package com.eignex.klause.backtrack.lp

import com.eignex.klause.solver.search.VarRef

internal class LpDepthObservationJournal(
    private val charge: (Long) -> Unit,
    private val observe: (String) -> Unit,
    private val maxAge: Long,
) {
    private class Block {
        val variables = IntArray(BLOCK_SIZE)
        val nodes = LongArray(BLOCK_SIZE)
        val parents = DoubleArray(BLOCK_SIZE)
        val gains = DoubleArray(BLOCK_SIZE)
        val flags = ShortArray(BLOCK_SIZE)
    }

    private val capacity = capacity(maxAge)
    private val blocks = arrayOfNulls<Block>(blockCount(capacity))
    private val evidence = LongArray(BUCKETS)
    private val wordsPerBucket = wordCount(capacity)
    private val positions = LongArray(BUCKETS * wordsPerBucket)
    private var next = 0
    var size = 0
        private set

    val clearWork: Long get() = evidence.size + positions.size + 1L

    fun clear() {
        size = 0
        next = 0
        evidence.fill(0L)
        positions.fill(0L)
    }

    fun mayHavePairs(variable: VarRef, level: Int): Boolean {
        val packed = evidence[bucket(key(variable), level)]
        return field(packed, 0) >= 2L && field(packed, 1) >= 2L &&
            field(packed, 2) >= 2L && field(packed, 3) >= 2L
    }

    fun record(
        child: LpDirectionalHistory.Pending,
        node: Long,
        gain: Double,
        pruned: Boolean?,
        current: () -> Boolean,
    ) {
        if (pruned == null && !gain.isFinite()) return
        val count = size
        val write = next
        charge(8L)
        if (!current() || count != size || write != next) return
        val last = if (write == 0) capacity - 1 else write - 1
        val previous = blocks[last / BLOCK_SIZE]
        val append = count == 0 || previous?.nodes?.get(last % BLOCK_SIZE) != node
        val index = if (append) write else last
        val page = index / BLOCK_SIZE
        val slot = index % BLOCK_SIZE
        val key = key(child.parent.variable)
        val flag = ((child.depth - 1) shl LEVEL_SHIFT) or if (child.up) UP else 0
        val retained = blocks[page]
        if (!append && (retained == null || retained.variables[slot] != key ||
                retained.parents[slot] != child.parent.objective ||
                retained.flags[slot].toInt() and OUTCOMES.inv() != flag)
        ) return
        val evicts = append && count == capacity
        val positionWork = if (evicts) 12L else if (append) 6L else 0L
        charge(24L + positionWork + if (retained == null) BLOCK_BYTES else 0L)
        if (!current() || count != size || write != next) return
        val block = retained ?: Block().also { blocks[page] = it }
        val evictsInAge = evicts && node - block.nodes[slot] <= maxAge
        if (evicts) {
            val oldFlag = block.flags[slot].toInt()
            val oldKey = block.variables[slot]
            adjust(oldKey, oldFlag, block.gains[slot].isFinite(), oldFlag and OUTCOMES != 0, add = false)
            updatePosition(oldKey, oldFlag, index, add = false)
        }
        if (append) {
            updatePosition(key, flag, index, add = true)
            block.variables[slot] = key
            block.nodes[slot] = node
            block.parents[slot] = child.parent.objective
            block.gains[slot] = Double.NaN
            block.flags[slot] = flag.toShort()
            next = if (write + 1 == capacity) 0 else write + 1
            size = minOf(capacity, count + 1)
        }
        if (pruned == null) {
            if (!block.gains[slot].isFinite()) adjust(key, flag, numeric = true, outcome = false, add = true)
            block.gains[slot] = gain
        } else {
            if (block.flags[slot].toInt() and OUTCOMES == 0) {
                adjust(key, flag, numeric = false, outcome = true, add = true)
            }
            block.flags[slot] = (flag or if (pruned) PRUNED else OPEN).toShort()
        }
        if (retained == null) observe("DEPTH_RETENTION_JOURNAL_BLOCK_ALLOCATED")
        if (evicts) observe("DEPTH_RETENTION_JOURNAL_EVICTED")
        if (evictsInAge) observe("DEPTH_RETENTION_JOURNAL_EVICTED_IN_AGE")
        observe(when (pruned) {
            null -> "DEPTH_RETENTION_NUMERIC"
            true -> "DEPTH_RETENTION_OUTCOME_PRUNED"
            false -> "DEPTH_RETENTION_OUTCOME_OPEN"
        })
    }

    private fun updatePosition(key: Int, flag: Int, position: Int, add: Boolean) {
        val index = bucket(key, flag ushr LEVEL_SHIFT) * wordsPerBucket + position / WORD_BITS
        val bit = 1L shl (position % WORD_BITS)
        positions[index] = if (add) positions[index] or bit else positions[index] and bit.inv()
    }

    private fun adjust(key: Int, flag: Int, numeric: Boolean, outcome: Boolean, add: Boolean) {
        val index = bucket(key, flag ushr LEVEL_SHIFT)
        val direction = if (flag and UP == 0) 0 else 1
        val numericCount = if (numeric) 1L shl (direction * FIELD_BITS) else 0L
        val outcomeCount = if (outcome) 1L shl ((direction + 2) * FIELD_BITS) else 0L
        val delta = numericCount or outcomeCount
        evidence[index] += if (add) delta else -delta
    }

    fun restore(
        variable: VarRef,
        level: Int,
        node: Long,
        current: () -> Boolean,
    ): LpDirectionalHistory.Gains? {
        val count = size
        val write = next
        val first = (write - count + capacity) % capacity
        charge(64L)
        if (!current() || count != size || write != next) return null
        val history = LpDirectionalHistory.Gains(variable)
        val key = key(variable)
        val bucketStart = bucket(key, level) * wordsPerBucket
        fun replay(start: Int, end: Int): Boolean {
            var offset = start
            while (offset < end) {
                val word = offset / WORD_BITS
                val base = word * WORD_BITS
                val stop = minOf(end, base + WORD_BITS)
                val lowMask = -1L shl (offset - base)
                val highMask = -1L ushr (WORD_BITS - (stop - base))
                var candidates = positions[bucketStart + word] and lowMask and highMask
                charge(8L + candidates.countOneBits() * 8L)
                if (!current() || count != size || write != next) return false
                while (candidates != 0L) {
                    val position = base + candidates.countTrailingZeroBits()
                    candidates = candidates and (candidates - 1L)
                    val block = checkNotNull(blocks[position / BLOCK_SIZE])
                    val slot = position % BLOCK_SIZE
                    val flag = block.flags[slot].toInt()
                    val sample = block.nodes[slot]
                    if (block.variables[slot] != key || flag ushr LEVEL_SHIFT != level ||
                        node - sample !in 1L..maxAge
                    ) continue
                    val direction = if (flag and UP != 0) history.up else history.down
                    val parent = block.parents[slot]
                    val gain = block.gains[slot]
                    if (gain.isFinite()) direction.recordNumeric(gain, sample, parent, level)
                    if (flag and OUTCOMES != 0) {
                        direction.recordOutcome(flag and PRUNED != 0, sample, parent, level)
                    }
                }
                offset = stop
            }
            return true
        }
        val end = first + count
        if (!replay(first, minOf(end, capacity)) || (end > capacity && !replay(0, end - capacity))) return null
        return history
    }

    internal companion object {
        private const val BLOCK_SIZE = 16
        private const val BLOCK_BYTES = BLOCK_SIZE * 30L
        private const val MAX_AGE = 256L
        private const val UP = 1
        private const val OPEN = 2
        private const val PRUNED = 4
        private const val OUTCOMES = OPEN or PRUNED
        private const val LEVEL_SHIFT = 3
        private const val BUCKETS = 128
        private const val WORD_BITS = 64
        private const val FIELD_BITS = 9
        private const val FIELD_MASK = (1L shl FIELD_BITS) - 1L

        fun indexWork(maxAge: Long): Long {
            val capacity = capacity(maxAge)
            return blockCount(capacity) + BUCKETS * (1L + wordCount(capacity)) * 8L
        }

        private fun capacity(maxAge: Long): Int = maxAge.coerceIn(0L, MAX_AGE).toInt() + 1

        private fun blockCount(capacity: Int): Int = (capacity + BLOCK_SIZE - 1) / BLOCK_SIZE

        private fun wordCount(capacity: Int): Int = (capacity + WORD_BITS - 1) / WORD_BITS

        private fun key(variable: VarRef): Int = if (variable is VarRef.Bool) variable.varId.inv() else variable.varId

        private fun field(value: Long, index: Int): Long = value ushr (index * FIELD_BITS) and FIELD_MASK

        private fun bucket(key: Int, level: Int): Int {
            val mixed = (key * -1640531527) xor (level * 40503)
            return (mixed xor (mixed ushr 16)) and (BUCKETS - 1)
        }
    }
}
