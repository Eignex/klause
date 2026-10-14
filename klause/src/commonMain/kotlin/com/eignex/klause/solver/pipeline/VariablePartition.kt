package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.theory.EncodingTarget
import com.eignex.klause.theory.linearTheoryModel

/** Factor representation eligibility; [ComponentPlan] independently owns finite choices. */
class VariablePartition(private val searchRequired: BooleanArray) {

    /** Whether some factor needs a finite enforcement path for [v]. */
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
 * A column is search-required by a factor when no selected arithmetic encoder can hold that factor.
 * Eligibility describes factor representation; it does not transfer a finite declaration's choices
 * away from finite state. [componentPlan] selects coordinate ownership independently.
 *
 * One pass, not a fixpoint: the demand is a static per-factor declaration, so pulling a column into the
 * search set never changes what another factor declares, and one pass is already the closure.
 */
fun Problem.variablePartition(): VariablePartition = variablePartition(FactorProjectionProviders.Default.prepare(this))

internal fun Problem.variablePartition(projections: PreparedFactorProjections): VariablePartition {
    val searchRequired = BooleanArray(numIntVars)
    for ((index, factor) in factors.withIndex()) {
        if (projections.theoryOwnable(index, numRealVars != 0)) continue
        for (variable in factor.variables.ints) if (variable < numIntVars) searchRequired[variable] = true
    }
    return VariablePartition(searchRequired)
}

internal fun PreparedFactorProjections.theoryOwnable(index: Int, hasRealColumns: Boolean): Boolean {
    if (!encoder(index, EncodingTarget.LINEAR).enforcesSource) return false
    if (hasRealColumns) return true
    val factors = linearTheoryModel(EncodingTarget.LINEAR, listOf(index))?.factors ?: return false
    return factors.any { it.intVars.isNotEmpty() } &&
        factors.all { factor -> factor.linearForm!!.rows.all { it.constants is IntegralConstants } }
}
