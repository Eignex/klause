package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.ReifiedLinear
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
        }
        best = LocalSearchResidual(state.cost, byKind)
    }
}
