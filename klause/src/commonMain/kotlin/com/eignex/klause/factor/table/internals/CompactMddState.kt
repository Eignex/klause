package com.eignex.klause.factor.table.internals

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.propagation.ExtensionalSupportCounts
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.RevInt
import com.eignex.klause.propagation.RevLongArray
import com.eignex.klause.propagation.RevRef
import com.eignex.klause.propagation.checkFiniteWork
import com.eignex.klause.propagation.lazyReason

internal class CompactMddLayer(
    val symbols: ExtensionalSupportIndex.Column,
    val tails: ExtensionalSupportIndex.Column,
    val heads: ExtensionalSupportIndex.Column,
)

internal class CompactMddState(
    private val state: PropagationState,
    private val seq: IntArray,
    private val sizes: IntArray,
    private val transitions: LongArray,
    private val initial: Int,
    accepting: IntArray,
    private val pointers: Array<IntArray>,
    private val index: Array<CompactMddLayer>,
) {
    private val accepting = accepting.toSet()
    private val valid = RevInt(state, 0)
    private val baseline = Array(seq.size) { RevRef(state, state.intDomains[seq[it]]) }
    private val arcs = Array(seq.size) { SparseArcs(state, pointers[it].size) }
    private val incomingResidues = Array(seq.size) { IntArray(sizes[it + 1]) { -1 } }
    private val outgoingResidues = Array(seq.size) { IntArray(sizes[it]) { -1 } }

    fun propagate(): Boolean {
        if (seq.isEmpty()) return initial in accepting
        val domains = Array(seq.size) { state.intDomains[seq[it]] }
        if (valid.value == 0) reset(domains) else removeDomainDeltas(domains)
        if (!propagateDown() || !propagateUp()) return false
        for (pos in seq.indices) {
            state.checkFiniteWork()
            if (!prune(pos, domains[pos])) return false
        }
        for (pos in seq.indices) {
            state.checkFiniteWork()
            if (baseline[pos].value !== domains[pos]) baseline[pos].set(domains[pos])
        }
        valid.set(1)
        return true
    }

    private fun reset(domains: Array<IntDomain>) {
        for (pos in seq.indices) {
            val kept = LongArray(arcs[pos].wordCount)
            for (row in pointers[pos].indices) {
                state.checkFiniteWork()
                val p = pointers[pos][row]
                val src = transitions[p].toInt()
                val dst = transitions[p + 2].toInt()
                if (src !in 0 until sizes[pos] || dst !in 0 until sizes[pos + 1]) continue
                if (pos == 0 && src != initial || pos == seq.lastIndex && dst !in accepting) continue
                if (transitions[p + 1] !in domains[pos]) continue
                kept[row ushr 6] = kept[row ushr 6] or (1L shl (row and 63))
            }
            arcs[pos].reset(kept)
        }
    }

    private fun removeDomainDeltas(domains: Array<IntDomain>) {
        for (pos in seq.indices) {
            state.checkFiniteWork()
            val prior = baseline[pos].value
            val current = domains[pos]
            if (prior === current) continue
            val removed = LongArray(arcs[pos].wordCount)
            index[pos].symbols.forEachValue(prior.min, prior.max) { value, bucket ->
                state.checkFiniteWork()
                if (value in prior && value !in current) addMask(index[pos].symbols, bucket, removed)
            }
            arcs[pos].remove(removed)
        }
    }

    private fun propagateDown(): Boolean {
        for (pos in 0 until seq.lastIndex) {
            if (arcs[pos].isEmpty) return false
            val removed = LongArray(arcs[pos + 1].wordCount)
            index[pos + 1].tails.forEachValue(0, sizes[pos + 1].toLong() - 1) { node, bucket ->
                state.checkFiniteWork()
                val id = node.toInt()
                val outgoing = index[pos + 1].tails.firstLiveWord(node, outgoingResidues[pos + 1][id],
                    arcs[pos + 1]::word, state::checkFiniteWork)
                outgoingResidues[pos + 1][id] = outgoing
                if (outgoing >= 0) {
                    val incoming = index[pos].heads.firstLiveWord(node, incomingResidues[pos][id],
                        arcs[pos]::word, state::checkFiniteWork)
                    incomingResidues[pos][id] = incoming
                    if (incoming < 0) addMask(index[pos + 1].tails, bucket, removed)
                }
            }
            arcs[pos + 1].remove(removed)
        }
        return !arcs.last().isEmpty
    }

    private fun propagateUp(): Boolean {
        for (pos in seq.lastIndex downTo 1) {
            if (arcs[pos].isEmpty) return false
            val removed = LongArray(arcs[pos - 1].wordCount)
            index[pos - 1].heads.forEachValue(0, sizes[pos].toLong() - 1) { node, bucket ->
                state.checkFiniteWork()
                val id = node.toInt()
                val incoming = index[pos - 1].heads.firstLiveWord(node, incomingResidues[pos - 1][id],
                    arcs[pos - 1]::word, state::checkFiniteWork)
                incomingResidues[pos - 1][id] = incoming
                if (incoming >= 0) {
                    val outgoing = index[pos].tails.firstLiveWord(node, outgoingResidues[pos][id],
                        arcs[pos]::word, state::checkFiniteWork)
                    outgoingResidues[pos][id] = outgoing
                    if (outgoing < 0) addMask(index[pos - 1].heads, bucket, removed)
                }
            }
            arcs[pos - 1].remove(removed)
        }
        return !arcs.first().isEmpty
    }

    private fun addMask(column: ExtensionalSupportIndex.Column, bucket: Int, mask: LongArray) {
        column.forEachWord(bucket, { word, bits -> mask[word] = mask[word] or bits }, state::checkFiniteWork)
    }

    private fun prune(pos: Int, domain: IntDomain): Boolean {
        val values = requireNotNull(domain.spanOrNull(4096))
        for (i in 0 until values.size) {
            state.checkFiniteWork()
            val value = values.valueAt(i)
            if (index[pos].symbols.firstLiveWord(value, -1, arcs[pos]::word, state::checkFiniteWork) >= 0) continue
            val reason = if (state.currentLevel == 0) null else {
                state.lazyReason(intArrayOf(MddIncrementalState.PRUNE, pos, (value ushr 32).toInt(), value.toInt()))
            }
            if (!state.excludeIntValue(seq[pos], value, reason)) return false
        }
        return true
    }

    fun supportCounts(variable: Int, values: LongArray, visit: () -> Boolean): ExtensionalSupportCounts? {
        if (valid.value == 0) return null
        val pos = seq.indexOf(variable)
        if (pos < 0) return null
        val total = arcs[pos].count(visit) ?: return null
        val counts = IntArray(values.size)
        for (i in values.indices) {
            counts[i] = index[pos].symbols.countLive(values[i], arcs[pos]::word, visit) ?: return null
        }
        return if (total == 0) null else ExtensionalSupportCounts(counts, total)
    }

    private class SparseArcs(private val state: PropagationState, count: Int) {
        val wordCount = ((count.toLong() + 63) ushr 6).toInt()
        private val words = RevLongArray(state, wordCount)
        private val order = IntArray(wordCount) { it }
        private val length = RevInt(state, wordCount)
        val isEmpty: Boolean get() = length.value == 0
        fun word(index: Int): Long = words[index]

        fun reset(kept: LongArray) {
            check(length.value == wordCount)
            for (i in kept.indices) {
                state.checkFiniteWork()
                words[i] = kept[i]
            }
            remove(LongArray(wordCount))
        }

        fun remove(mask: LongArray) {
            var i = 0
            var end = length.value
            while (i < end) {
                state.checkFiniteWork()
                val slot = order[i]
                val kept = words[slot] and mask[slot].inv()
                words[slot] = kept
                if (kept == 0L) {
                    order[i] = order[--end]
                    order[end] = slot
                } else {
                    i++
                }
            }
            length.set(end)
        }

        fun count(visit: () -> Boolean): Int? {
            var count = 0
            for (i in 0 until length.value) {
                if (!visit()) return null
                count += words[order[i]].countOneBits()
            }
            return count
        }
    }
}
