package com.eignex.klause.propagation

import com.eignex.klause.factor.table.TablePropagator
import com.eignex.klause.util.IntHashSet
import kotlin.time.TimeSource

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
        val attribution = state.factorAttribution
        val family = state.attributionFamily(marker[1])
        val before = state.work
        val start = if (attribution == null) null else TimeSource.Monotonic.markNow()
        attribution?.note(family, FactorAttribution.Metric.RefinementAttempts)
        replacement = try {
            (state.factorAt(marker[1]) as TablePropagator).historicalAlternative(
                state, marker.copyOfRange(LAZY_PAYLOAD, marker.size), marker[2], preferred,
            )
        } finally {
            attribution?.let {
                it.note(family, FactorAttribution.Metric.RefinementWork, state.work - before)
                it.note(family, FactorAttribution.Metric.RefinementNanos, requireNotNull(start).elapsedNow().inWholeNanoseconds)
            }
        }
        val alternate = replacement ?: return false
        attribution?.note(family, FactorAttribution.Metric.RefinementSupport, alternate.size.toLong())
        state.arithmeticLearningTrace?.clause("table.ordinary-reason", ordinary, state)
        state.arithmeticLearningTrace?.clause("table.alternate-reason", alternate, state)
        return !ordinary.contentEquals(alternate)
    }
}
