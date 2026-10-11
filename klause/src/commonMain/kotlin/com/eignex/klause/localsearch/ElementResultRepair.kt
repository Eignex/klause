package com.eignex.klause.localsearch

internal class ElementResultRepair(
    private val state: LocalSearchState,
    private val sink: MoveSink,
    private val definition: ElementResultDefinition,
) {
    fun propose(value: Long, depth: Int = 0): Int {
        val factor = definition.factor
        if (depth >= MAX_DEPTH || !sink.allowsDefinedInt(factor.result) || value !in state.rootDomains[factor.result]) {
            return 0
        }
        var added = 0
        val index = state.assignment.intValue(factor.idx)
        if (factor.arrIsVars && index >= factor.indexOffset &&
            index < factor.indexOffset.toLong() + factor.arr.size
        ) {
            val selected = factor.arr[(index - factor.indexOffset).toInt()].toInt()
            if (value in state.rootDomains[selected] && value != state.assignment.intValue(selected)) {
                val nested = state.invariants?.elementResultDefinition(selected)
                if (nested != null) {
                    added += ElementResultRepair(state, sink, nested).propose(value, depth + 1)
                } else {
                    val before = sink.size
                    sink.addChannelingIntSet(state, selected, value)
                    added += sink.size - before
                }
            }
        }
        if (added >= MAX_ALTERNATIVES) return added
        val offset = state.rng.nextInt(factor.arr.size)
        for (i in 0 until minOf(factor.arr.size, MAX_POSITIONS)) {
            val position = ((offset.toLong() + i) % factor.arr.size).toInt()
            val entry = factor.arr[position]
            val current = if (factor.arrIsVars) state.assignment.intValue(entry.toInt()) else entry
            val target = factor.indexOffset.toLong() + position
            if (current != value || target == index || target !in state.rootDomains[factor.idx]) continue
            val before = sink.size
            sink.addElementIndexSet(state, factor.idx, target)
            added += sink.size - before
            if (added >= MAX_ALTERNATIVES) break
        }
        return added
    }

    private companion object {
        const val MAX_ALTERNATIVES = 4
        const val MAX_POSITIONS = 32
        const val MAX_DEPTH = 16
    }
}
