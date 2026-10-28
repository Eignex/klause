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
        val ordinary = state.lazyReasonMemo[marker] ?: return false
        if (ordinary.size > 64) return false
        val introduced = IntHashSet(ordinary.size)
        for (lit in ordinary) introduced.add(lit)
        val preferred = IntHashSet(frontier.size)
        for (lit in frontier) if (!introduced.contains(lit)) preferred.add(lit)
        replacement = (state.factorAt(marker[1]) as TablePropagator).historicalAlternative(
            state, marker.copyOfRange(LAZY_PAYLOAD, marker.size), marker[2], preferred,
        )
        val alternate = replacement ?: return false
        state.arithmeticLearningTrace?.clause("table.ordinary-reason", ordinary, state)
        state.arithmeticLearningTrace?.clause("table.alternate-reason", alternate, state)
        return !ordinary.contentEquals(alternate)
    }
}
