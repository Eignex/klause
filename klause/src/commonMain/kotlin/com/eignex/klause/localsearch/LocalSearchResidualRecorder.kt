package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.ReifiedLinear
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
                val ints = factor.intVars.distinct()
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

    private companion object {
        const val MAX_FACTORS = 8
        const val MAX_COORDINATES = 16
    }
}
