package com.eignex.klause.solver

/**
 * Immutable solver-side problem: a count of Boolean variables (including any aux variables
 * introduced by the compiler) and an ordered list of factors. Factors are addressed by their
 * index in [factors].
 *
 * [occurrences] is a precomputed inverse map: variable id to the ids of factors mentioning it.
 */
class Problem(
    val numVars: Int,
    val factors: List<Factor>,
) {
    val occurrences: Array<IntArray> = run {
        val counts = IntArray(numVars)
        for (f in factors) for (v in f.variables) counts[v]++
        val out = Array(numVars) { IntArray(counts[it]) }
        val cursor = IntArray(numVars)
        factors.forEachIndexed { id, f ->
            for (v in f.variables) {
                out[v][cursor[v]++] = id
            }
        }
        out
    }

    val numFactors: Int get() = factors.size
}
