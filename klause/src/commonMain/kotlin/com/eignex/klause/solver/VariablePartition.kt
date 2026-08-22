package com.eignex.klause.solver

/**
 * Which integer variables the search has to branch on, and which one could hand to a theory instead.
 *
 * A *search variable* is filtered by propagators and enumerated by the search, so it needs a finite
 * domain supplied by the backend that chooses to enumerate it.
 * A *theory-eligible* variable is mentioned only by factors that reason over intervals or structure, so
 * nothing about it has to be enumerated and a decision procedure could settle it over the whole of ℤ.
 *
 * The partition says who *branches*, not who *reasons*: a theory reads the trail, so it sees search
 * variables like any others.
 *
 * Reading it changes nothing on its own. It names the distinction that the clamp, the branching
 * interface and the propagator/theory split all rest on, so those can be moved one at a time.
 */
class VariablePartition(private val searchRequired: BooleanArray) {

    /** The search must branch on [v], so [v] needs a finite domain. */
    fun isSearchVariable(v: Int): Boolean = searchRequired[v]

    /** No factor needs a finite domain for [v], so a theory could decide it unbounded. */
    fun isTheoryEligible(v: Int): Boolean = !searchRequired[v]

    /** How many integer columns no factor needs a finite domain for. */
    val theoryEligibleCount: Int = searchRequired.count { !it }

    /** Integer columns in total, whichever side they fall. */
    val size: Int get() = searchRequired.size
}

/**
 * Classify this problem's integer variables; see [VariablePartition].
 *
 * One pass rather than a fixpoint, because the rule reads only the factors as they stand: a variable is
 * search-required exactly when some factor that [Factor.needsFiniteDomains] mentions it. A fixpoint
 * becomes necessary only once a *lowering* decision depends on the classification — splitting a global
 * by boundedness would reclassify the variables it stops mentioning — and no such decision exists here.
 *
 * Continuous columns are outside this: they are already theory-only and are never search variables.
 */
fun Problem.variablePartition(): VariablePartition = variablePartition(numIntVars, factors)

/** Classify a source [ProblemSpec] before any finite CP domains are materialized. */
fun ProblemSpec.variablePartition(): VariablePartition = variablePartition(numIntVars, factors)

private fun variablePartition(numIntVars: Int, factors: Array<Factor>): VariablePartition {
    val searchRequired = BooleanArray(numIntVars)
    for (f in factors) {
        if (!f.needsFiniteDomains) continue
        for (v in f.intVars) if (v < numIntVars) searchRequired[v] = true
    }
    return VariablePartition(searchRequired)
}
