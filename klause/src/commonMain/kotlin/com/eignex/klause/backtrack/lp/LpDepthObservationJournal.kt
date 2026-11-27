package com.eignex.klause.backtrack.lp

import com.eignex.klause.solver.search.VarRef

internal class LpDepthObservationJournal(
    private val charge: (Long) -> Unit,
    private val observe: (String) -> Unit,
    private val maxAge: Long,
) {
    private class Observation {
        var variable: VarRef? = null
        var level = 0
        var node = 0L
        var parent = 0.0
        var up = false
        var gain = Double.NaN
        var pruned: Boolean? = null

        fun matches(child: LpDirectionalHistory.Pending): Boolean =
            variable == child.parent.variable && level == child.depth - 1 && parent == child.parent.objective &&
                up == child.up
    }

    private val entries = arrayOfNulls<Observation>(CAPACITY)
    private var next = 0
    var size = 0
        private set

    fun clear() {
        for (index in 0 until size) entries[index]?.variable = null
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
        charge(8L)
        if (!current()) return
        val count = size
        val write = next
        val last = (write - 1) and MASK
        val latest = entries[last]
        val append = count == 0 || latest?.node != node
        if (!append && latest?.matches(child) != true) return
        val index = if (append) write else last
        val retained = entries[index]
        charge(if (retained == null) 64L else 16L)
        if (!current() || count != size || write != next) return
        val evicts = append && count == CAPACITY
        val evictsInAge = evicts && retained != null && node - retained.node <= maxAge
        val observation = retained ?: Observation().also { entries[index] = it }
        if (append) {
            observation.variable = child.parent.variable
            observation.level = child.depth - 1
            observation.node = node
            observation.parent = child.parent.objective
            observation.up = child.up
            observation.gain = Double.NaN
            observation.pruned = null
            next = (write + 1) and MASK
            size = minOf(CAPACITY, count + 1)
        }
        if (pruned == null) observation.gain = gain else observation.pruned = pruned
        if (retained == null) observe("DEPTH_RETENTION_SLOT_ALLOCATED")
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
        val first = (write - count) and MASK
        charge(64L)
        if (!current() || count != size || write != next) return null
        val history = LpDirectionalHistory.Gains(variable)
        for (offset in 0 until count step BLOCK) {
            val end = minOf(count, offset + BLOCK)
            charge((end - offset) * 8L)
            if (!current() || count != size || write != next) return null
            for (index in offset until end) {
                val entry = checkNotNull(entries[(first + index) and MASK])
                if (entry.variable != variable || entry.level != level || node - entry.node !in 1L..maxAge) continue
                val direction = if (entry.up) history.up else history.down
                if (entry.gain.isFinite()) direction.recordNumeric(entry.gain, entry.node, entry.parent, level)
                val pruned = entry.pruned
                if (pruned != null) direction.recordOutcome(pruned, entry.node, entry.parent, level)
            }
        }
        return history
    }

    private companion object {
        const val CAPACITY = 128
        const val MASK = CAPACITY - 1
        const val BLOCK = 16
    }
}
