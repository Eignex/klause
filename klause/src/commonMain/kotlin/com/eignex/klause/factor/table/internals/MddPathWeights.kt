package com.eignex.klause.factor.table.internals

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.propagation.ExtensionalSupportCounts
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.util.binarySearchLong

internal class MddPathWeights private constructor(
    private val seq: IntArray,
    private val sizes: IntArray,
    private val transitions: LongArray,
    private val index: MddTransitionIndex,
    private val initial: Int,
    private val accepting: IntArray,
) {
    private val prefix = Array(sizes.size) { IntArray(sizes[it]) }
    private val suffix = Array(sizes.size) { IntArray(sizes[it]) }
    private var domains: Array<IntDomain>? = null
    private var complete = false

    fun supportCounts(
        state: PropagationState,
        position: Int,
        values: LongArray,
        visit: () -> Boolean,
    ): ExtensionalSupportCounts? {
        var unchanged = domains != null
        for (i in seq.indices) {
            if (!visit()) return null
            if (domains?.get(i) !== state.intDomains[seq[i]]) unchanged = false
        }
        if (!unchanged) {
            complete = false
            domains = null
            if (!rebuild(state, visit)) return null
        }
        if (!complete) return null
        val counts = IntArray(values.size)
        val head = index.fwdHead[position]
        val pointers = index.fwdPtr[position]
        val domain = state.intDomains[seq[position]]
        for (src in prefix[position].indices) {
            if (!visit()) return null
            if (prefix[position][src] == 0) continue
            for (edge in head[src] until head[src + 1]) {
                if (!visit()) return null
                val p = pointers[edge]
                val sym = transitions[p + 1]
                val dst = transitions[p + 2].toInt()
                if (sym !in domain || dst !in suffix[position + 1].indices) continue
                val candidate = values.binarySearchLong(sym)
                if (candidate >= 0) {
                    // Each term and each marginal is bounded by the admitted root path count.
                    counts[candidate] += (prefix[position][src].toLong() * suffix[position + 1][dst]).toInt()
                }
            }
        }
        val total = suffix[0][initial]
        return if (total == 0) null else ExtensionalSupportCounts(counts, total)
    }

    private fun rebuild(state: PropagationState, visit: () -> Boolean): Boolean {
        for (layer in sizes.indices) {
            for (s in 0 until sizes[layer]) {
                if (!visit()) return false
                prefix[layer][s] = 0
                suffix[layer][s] = 0
            }
        }
        for (s in accepting) {
            if (!visit()) return false
            if (s in suffix.last().indices) suffix.last()[s] = 1
        }
        for (layer in seq.lastIndex downTo 0) {
            val domain = state.intDomains[seq[layer]]
            for (p in index.fwdPtr[layer]) {
                if (!visit()) return false
                val src = transitions[p].toInt()
                val dst = transitions[p + 2].toInt()
                if (transitions[p + 1] !in domain || dst !in suffix[layer + 1].indices) continue
                val sum = suffix[layer][src].toLong() + suffix[layer + 1][dst]
                if (sum > Int.MAX_VALUE) return decline(state)
                suffix[layer][src] = sum.toInt()
            }
        }
        prefix[0][initial] = 1
        for (layer in seq.indices) {
            val domain = state.intDomains[seq[layer]]
            for (p in index.fwdPtr[layer]) {
                if (!visit()) return false
                val src = transitions[p].toInt()
                val dst = transitions[p + 2].toInt()
                if (transitions[p + 1] !in domain || dst !in suffix[layer + 1].indices ||
                    suffix[layer + 1][dst] == 0
                ) continue
                val sum = prefix[layer + 1][dst].toLong() + prefix[layer][src]
                if (sum > Int.MAX_VALUE) return decline(state)
                prefix[layer + 1][dst] = sum.toInt()
            }
        }
        domains = Array(seq.size) { state.intDomains[seq[it]] }
        complete = true
        return true
    }

    private fun decline(state: PropagationState): Boolean {
        domains = Array(seq.size) { state.intDomains[seq[it]] }
        return false
    }

    companion object {
        private const val MAX_STATES = 8192
        private const val MAX_EDGES = 8192
        private const val MAX_CELLS = 1_048_576

        fun build(
            state: PropagationState,
            seq: IntArray,
            sizes: IntArray,
            transitions: LongArray,
            index: MddTransitionIndex,
            initial: Int,
            accepting: IntArray,
            visit: () -> Boolean,
        ): MddPathWeights? {
            if (seq.size > MAX_STATES || sizes.sumOf { it.toLong() } > MAX_STATES ||
                index.fwdPtr.sumOf { it.size.toLong() } > MAX_EDGES || initial !in 0 until sizes[0]
            ) return null
            val cells = sizes.sum() * 2 + seq.size * 2
            if (cells > MAX_CELLS - state.mddPathWeightCells) return null
            val variables = HashSet<Int>()
            for (v in seq) {
                if (!visit() || !variables.add(v)) return null
            }
            for (layer in seq.indices) {
                val head = index.fwdHead[layer]
                val pointers = index.fwdPtr[layer]
                for (src in 0 until sizes[layer]) {
                    if (!visit()) return null
                    val symbols = HashSet<Long>()
                    for (edge in head[src] until head[src + 1]) {
                        if (!visit()) return null
                        val p = pointers[edge]
                        if (!symbols.add(transitions[p + 1])) return null
                    }
                }
            }
            state.mddPathWeightCells += cells
            return MddPathWeights(seq, sizes, transitions, index, initial, accepting)
        }
    }
}
