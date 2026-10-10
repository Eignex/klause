package com.eignex.klause.localsearch

import com.eignex.klause.solver.result.LocalSearchResidual

internal class LocalSearchResidualRecorder {
    var best: LocalSearchResidual? = null
        private set

    fun observe(state: LocalSearchState) {
        if (state.cost >= (best?.cost ?: Long.MAX_VALUE)) return
        val byKind = LinkedHashMap<String, Long>()
        for (fid in state.problem.factors.indices) {
            val degree = state.factorDegree[fid]
            if (degree == 0) continue
            val kind = state.problem.factors[fid]::class.simpleName ?: "Unknown"
            byKind[kind] = (byKind[kind] ?: 0L) + degree
        }
        best = LocalSearchResidual(state.cost, byKind)
    }
}
