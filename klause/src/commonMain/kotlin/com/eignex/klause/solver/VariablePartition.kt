package com.eignex.klause.solver

import com.eignex.klause.solver.pipeline.isTheoryOwnable

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

/**
 * Classify integer variables; see [VariablePartition].
 *
 * A column is search-required exactly when some factor no other lane can hold reads it. That is the same
 * question [componentPlan] answers when it assigns column ownership and when it checks a CP-owned
 * factor's columns, so the three read one rule rather than three that can drift.
 *
 * One pass, not a fixpoint: the demand is a static per-factor declaration, so pulling a column into the
 * search set never changes what another factor declares, and one pass is already the closure.
 */
fun Problem.variablePartition(): VariablePartition = variablePartition(numIntVars, factors, numRealVars != 0)

/** Classify a source [ProblemSpec] before any finite CP domains are materialized. */
fun ProblemSpec.variablePartition(): VariablePartition = variablePartition(numIntVars, factors, numRealVars != 0)

private fun variablePartition(numIntVars: Int, factors: Array<Factor>, hasRealColumns: Boolean): VariablePartition {
    val searchRequired = BooleanArray(numIntVars)
    for (f in factors) {
        if (f.isTheoryOwnable(hasRealColumns)) continue
        for (v in f.variables.ints) if (v < numIntVars) searchRequired[v] = true
    }
    return VariablePartition(searchRequired)
}
