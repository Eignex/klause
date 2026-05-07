package com.eignex.klause.solver

/**
 * Immutable solver-side problem. Variables come in two id spaces:
 *  - Boolean vars: ids `[0, numBoolVars)`, packed bits in [Assignment].
 *  - Integer vars: ids `[0, numIntVars)`, raw [Int] values in [Assignment].
 *
 * Each integer variable has an [IntDomain] for bounds. Factors mention either or both.
 * Occurrence lists are split per kind so `flip(boolVar)` and `setInt(intVar)` only walk the
 * factors mentioning that specific variable.
 */
class Problem(
    val numBoolVars: Int,
    val numIntVars: Int,
    val intDomains: Array<IntDomain>,
    val factors: List<Factor>,
) {
    init {
        require(intDomains.size == numIntVars) {
            "intDomains size ${intDomains.size} != numIntVars $numIntVars"
        }
    }

    val boolOccurrences: Array<IntArray> = invert(numBoolVars) { it.boolVars }
    val intOccurrences: Array<IntArray> = invert(numIntVars) { it.intVars }

    /**
     * For each factor, the ids of every other factor sharing at least one variable.
     * Used by clause-weighting strategies (DDFW) to find candidate weight donors.
     */
    val factorNeighbors: Array<IntArray> = Array(factors.size) { fid ->
        val seen = HashSet<Int>()
        val f = factors[fid]
        for (v in f.boolVars) for (o in boolOccurrences[v]) if (o != fid) seen.add(o)
        for (v in f.intVars) for (o in intOccurrences[v]) if (o != fid) seen.add(o)
        seen.toIntArray()
    }

    val numFactors: Int get() = factors.size

    private inline fun invert(slots: Int, vars: (Factor) -> IntArray): Array<IntArray> {
        val counts = IntArray(slots)
        for (f in factors) for (v in vars(f)) counts[v]++
        val out = Array(slots) { IntArray(counts[it]) }
        val cursor = IntArray(slots)
        factors.forEachIndexed { id, f ->
            for (v in vars(f)) out[v][cursor[v]++] = id
        }
        return out
    }
}
