package com.eignex.klause.propagation

import com.eignex.klause.factor.table.TablePropagator
import com.eignex.klause.util.IntHashSet

internal class HistoricalTableReason(private val state: PropagationState) {
    private var selected: IntArray? = null
    private var replacement: IntArray? = null

    fun read(marker: IntArray): IntArray? {
        if (marker === selected) return replacement
        if (selected == null && marker.size == LAZY_PAYLOAD + 4 && marker[1] >= 0 &&
            state.factorAt(marker[1]) is TablePropagator
        ) {
            selected = marker
            state.arithmeticLearningTrace?.record("table.selected", marker[1].toLong(), marker[2].toLong())
        }
        return null
    }

    fun prepare(frontier: IntArray): Boolean {
        val marker = selected ?: return false
        val preferred = IntHashSet(frontier.size)
        for (lit in frontier) preferred.add(lit)
        replacement = (state.factorAt(marker[1]) as TablePropagator).historicalAlternative(
            state, marker.copyOfRange(LAZY_PAYLOAD, marker.size), marker[2], preferred,
        )
        val alternate = replacement ?: return false
        state.lazyReasonMemo[marker]?.let { state.arithmeticLearningTrace?.clause("table.ordinary-reason", it, state) }
        state.arithmeticLearningTrace?.clause("table.alternate-reason", alternate, state)
        return state.lazyReasonMemo[marker]?.contentEquals(alternate) == false
    }
}
