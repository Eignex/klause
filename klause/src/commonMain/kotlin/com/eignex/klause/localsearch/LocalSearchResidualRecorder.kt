package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.scheduling.internals.CumulativeLsState
import com.eignex.klause.solver.result.LocalSearchResidual
import com.eignex.klause.solver.result.LocalSearchResidualFactor

internal class LocalSearchResidualRecorder {
    var best: LocalSearchResidual? = null
        private set

    fun observe(state: LocalSearchState) {
        if (state.cost >= (best?.cost ?: Long.MAX_VALUE)) return
        val byKind = LinkedHashMap<String, Long>()
        val representatives = LinkedHashMap<String, Int>()
        for (fid in state.problem.factors.indices) {
            val degree = state.factorDegree[fid]
            if (degree == 0) continue
            val factor = state.problem.factors[fid]
            val kind = if (factor is ReifiedLinear) {
                val shape = if (factor.vars.size != 1) {
                    "multi"
                } else {
                    val domain = state.rootDomains[factor.vars[0]]
                    if (domain.min >= 0L && domain.max <= 1L) "binary" else "unary"
                }
                "ReifiedLinear.${factor.op}.$shape"
            } else {
                factor::class.simpleName ?: "Unknown"
            }
            byKind[kind] = (byKind[kind] ?: 0L) + degree
            val previous = representatives[kind]
            if (previous == null || degree > state.factorDegree[previous]) representatives[kind] = fid
        }
        val factors = representatives.entries.sortedByDescending { state.factorDegree[it.value] }
            .take(MAX_FACTORS).map { (kind, fid) ->
                val factor = state.problem.factors[fid]
                val bools = factor.boolVars.distinct()
                val ints = if (factor is Cumulative) {
                    cumulativeCoordinates(state, fid, factor)
                } else {
                    factor.intVars.distinct()
                }
                LocalSearchResidualFactor(
                    fid, kind, state.factorDegree[fid].toLong(),
                    bools.take(MAX_COORDINATES).associateWith(state.assignment::boolValue),
                    ints.take(MAX_COORDINATES).associateWith(state.assignment::intValue),
                    (bools.size - MAX_COORDINATES).coerceAtLeast(0),
                    (ints.size - MAX_COORDINATES).coerceAtLeast(0),
                )
            }
        best = LocalSearchResidual(state.cost, byKind, factors)
    }

    private fun cumulativeCoordinates(state: LocalSearchState, fid: Int, factor: Cumulative): List<Int> {
        val ls = state.refPayload[fid] as? CumulativeLsState ?: return factor.intVars.distinct()
        val peak = ls.usage.indices.maxByOrNull { ls.usage[it] } ?: return factor.intVars.distinct()
        val time = ls.tLow + peak
        val coordinates = ArrayList<Int>()
        if (factor.capacityVar >= 0) coordinates.add(factor.capacityVar)
        for (i in factor.starts.indices) {
            if (!OptionalPresence.isPresentInAssignment(factor.presents, i, state)) continue
            val start = state.assignment.intValue(factor.starts[i])
            val duration = if (factor.durationVars.isEmpty()) {
                factor.durations[i]
            } else {
                state.assignment.intValue(factor.durationVars[i])
            }
            val demand = if (factor.resourceVars.isEmpty()) {
                factor.resources[i]
            } else {
                state.assignment.intValue(factor.resourceVars[i])
            }
            if (duration <= 0L || demand <= 0L || start > time || time >= start + duration) continue
            coordinates.add(factor.starts[i])
            if (factor.durationVars.isNotEmpty()) coordinates.add(factor.durationVars[i])
            if (factor.resourceVars.isNotEmpty()) coordinates.add(factor.resourceVars[i])
        }
        coordinates.addAll(factor.intVars.toList())
        return coordinates.distinct()
    }

    private companion object {
        const val MAX_FACTORS = 8
        const val MAX_COORDINATES = 16
    }
}
