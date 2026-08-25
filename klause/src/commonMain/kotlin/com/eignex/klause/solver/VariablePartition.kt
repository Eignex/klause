package com.eignex.klause.solver

/** Partition integer variables by whether finite-domain search must branch on them. */
class VariablePartition(private val searchRequired: BooleanArray) {

    /** Whether finite-domain search must branch on [v]. */
    fun isSearchVariable(v: Int): Boolean = searchRequired[v]

    /** Whether [v] can be decided by a theory. */
    fun isTheoryEligible(v: Int): Boolean = !searchRequired[v]

    /** Number of theory-eligible integer columns. */
    val theoryEligibleCount: Int = searchRequired.count { !it }

    /** Number of integer columns. */
    val size: Int get() = searchRequired.size
}

/** Classify integer variables; see [VariablePartition]. */
fun Problem.variablePartition(): VariablePartition = variablePartition(numIntVars, factors)

/** Classify a source [ProblemSpec] before any finite CP domains are materialized. */
fun ProblemSpec.variablePartition(): VariablePartition = variablePartition(numIntVars, factors)

private fun variablePartition(numIntVars: Int, factors: Array<Factor>): VariablePartition {
    val searchRequired = BooleanArray(numIntVars)
    for (f in factors) {
        for (v in f.variables.spanInts) if (v < numIntVars) searchRequired[v] = true
    }
    return VariablePartition(searchRequired)
}
