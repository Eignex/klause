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
    private var next = 0
    var size = 0
        private set

    fun clear() {
        size = 0
        next = 0
    }

    fun record(
        child: LpDirectionalHistory.Pending,
        node: Long,
        gain: Double,
        pruned: Boolean?,
        current: () -> Boolean,
    ) {
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
        charge(16L + if (retained == null) BLOCK_BYTES else 0L)
        if (!current() || count != size || write != next) return
        val evicts = append && count == capacity
        val block = retained ?: Block().also { blocks[page] = it }
        val evictsInAge = evicts && node - block.nodes[slot] <= maxAge
        if (append) {
            block.variables[slot] = key
            block.nodes[slot] = node
            block.parents[slot] = child.parent.objective
            block.gains[slot] = Double.NaN
            block.flags[slot] = flag.toShort()
            next = if (write + 1 == capacity) 0 else write + 1
            size = minOf(capacity, count + 1)
        }
        if (pruned == null) {
            block.gains[slot] = gain
        } else {
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
        for (offset in 0 until count step BLOCK_SIZE) {
            val end = minOf(count, offset + BLOCK_SIZE)
            charge((end - offset) * 8L)
            if (!current() || count != size || write != next) return null
            for (index in offset until end) {
                val position = (first + index) % capacity
                val block = checkNotNull(blocks[position / BLOCK_SIZE])
                val slot = position % BLOCK_SIZE
                val flag = block.flags[slot].toInt()
                val sample = block.nodes[slot]
                if (block.variables[slot] != key || flag ushr LEVEL_SHIFT != level || node - sample !in 1L..maxAge) {
                    continue
                }
                val direction = if (flag and UP != 0) history.up else history.down
                val parent = block.parents[slot]
                val gain = block.gains[slot]
                if (gain.isFinite()) direction.recordNumeric(gain, sample, parent, level)
                if (flag and OUTCOMES != 0) {
                    direction.recordOutcome(flag and PRUNED != 0, sample, parent, level)
                }
            }
        }
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

        fun indexWork(maxAge: Long): Long = blockCount(capacity(maxAge)).toLong()

        private fun capacity(maxAge: Long): Int = maxAge.coerceIn(0L, MAX_AGE).toInt() + 1

        private fun blockCount(capacity: Int): Int = (capacity + BLOCK_SIZE - 1) / BLOCK_SIZE

        private fun key(variable: VarRef): Int = if (variable is VarRef.Bool) variable.varId.inv() else variable.varId
    }
}
