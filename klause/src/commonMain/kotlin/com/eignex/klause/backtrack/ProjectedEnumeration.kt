package com.eignex.klause.backtrack

import com.eignex.klause.presolve.SourceMapping

internal fun BacktrackSolver.projectedOutcomes(
    params: BacktrackParams,
    boolVars: IntArray,
    intVars: IntArray,
    mapping: SourceMapping? = null,
): Sequence<SearchOutcome> {
    val source = mapping?.source ?: problem
    require(mapping == null || mapping.target === problem && mapping.guarantees.projectedSolutions) {
        "projected enumeration requires complete source coverage for this reduced model"
    }
    require(boolVars.all { it in 0 until source.numBoolVars })
    require(intVars.all { it in 0 until source.numIntVars })
    require(params.minHammingDistance == 0) { "projected enumeration requires unfiltered witnesses" }
    return sequence {
        val seen = HashSet<List<Long>>()
        for (outcome in enumerationOutcomes(params)) {
            when (outcome) {
                is SearchOutcome.Found -> {
                    val sample = mapping?.reconstructFrom(problem, outcome.sample) ?: outcome.sample
                    val key = ArrayList<Long>(boolVars.size + intVars.size)
                    for (v in boolVars) key.add(if (sample.bools[v]) 1L else 0L)
                    for (v in intVars) key.add(sample.ints[v])
                    if (seen.add(key)) yield(SearchOutcome.Found(sample))
                    if (boolVars.isEmpty() && intVars.isEmpty()) {
                        yield(SearchOutcome.Exhausted())
                        return@sequence
                    }
                }
                is SearchOutcome.Exhausted, SearchOutcome.BudgetCapped -> {
                    yield(outcome)
                    return@sequence
                }
            }
        }
    }
}
