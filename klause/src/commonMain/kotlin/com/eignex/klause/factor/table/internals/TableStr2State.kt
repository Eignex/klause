package com.eignex.klause.factor.table.internals

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.RevInt
import com.eignex.klause.propagation.RevLongArray
import com.eignex.klause.propagation.RevRef
import com.eignex.klause.propagation.checkFiniteWork

/** STR2 sparse-set propagation state for the Table constraint. */
internal class TableStr2State(
    val validTuples: IntArray,
    numValidInit: Int,
    private val state: PropagationState,
    private val xs: IntArray,
    val index: ExtensionalSupportIndex?,
) {
    private val startedCell = RevInt(state, 0)
    var started: Boolean
        get() = startedCell.value == 1
        set(value) = startedCell.set(if (value) 1 else 0)
    private val numValidCell = RevInt(state, numValidInit)
    var numValid: Int
        get() = numValidCell.value
        set(value) = numValidCell.set(value)

    val live: RevLongArray? = index?.let { RevLongArray(state, (numValidInit + 63) ushr 6) }
    private val baseline = if (index == null) null else Array(xs.size) { RevRef(state, state.intDomains[xs[it]]) }
    private var sweepDomains: Array<IntDomain>? = null

    fun beginSweep(): Boolean {
        val delta = started && index != null
        if (delta) filterDelta()
        sweepDomains = if (index == null) null else Array(xs.size) {
            state.checkFiniteWork()
            state.intDomains[xs[it]]
        }
        return delta
    }

    private fun filterDelta() {
        val live = live ?: return
        val index = index ?: return
        val baseline = requireNotNull(baseline)
        val killed = LongArray(live.size)
        for (col in xs.indices) {
            state.checkFiniteWork()
            val prior = baseline[col].value
            val current = state.intDomains[xs[col]]
            if (prior === current) continue
            index.columns[col].forEachValue(prior.min, prior.max) { value, bucket ->
                state.checkFiniteWork()
                if (value in prior && value !in current) {
                    index.columns[col].forEachWord(bucket, { word, mask ->
                        killed[word] = killed[word] or mask
                    }) { state.checkFiniteWork() }
                }
            }
        }
        for (word in killed.indices) {
            state.checkFiniteWork()
            if (killed[word] != 0L) live[word] = live[word] and killed[word].inv()
        }
    }

    fun rebuildLive() {
        val live = live ?: return
        val words = LongArray(live.size)
        for (i in 0 until numValid) {
            state.checkFiniteWork()
            val row = validTuples[i]
            words[row ushr 6] = words[row ushr 6] or (1L shl (row and 63))
        }
        for (word in words.indices) {
            state.checkFiniteWork()
            live[word] = words[word]
        }
    }

    fun commit() {
        baseline?.let { domains ->
            val swept = requireNotNull(sweepDomains)
            for (col in xs.indices) {
                state.checkFiniteWork()
                if (domains[col].value !== swept[col]) domains[col].set(swept[col])
            }
        }
        sweepDomains = null
        started = true
    }

    fun baselineMatches(visit: () -> Boolean): Boolean {
        val domains = baseline ?: return false
        for (col in xs.indices) {
            if (!visit() || domains[col].value !== state.intDomains[xs[col]]) return false
        }
        return true
    }
}
