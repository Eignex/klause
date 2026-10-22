package com.eignex.klause.propagation

import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Bits

/**
 * Columns a traversal completes at its leaves instead of deciding them.
 *
 * Selectors pass over these columns, and a leaf reads `false` for each such Boolean and the domain minimum for
 * each such integer. That leaf is a model only while nothing reads the columns: no factor, no objective term and
 * no enumeration that tells solutions apart by them. Deriving them is [unreadColumnsOf]'s job; installing them is
 * the job of a search that streams no solution set, through [PropagationSession.completeAtLeaves].
 *
 * Presolve keeps every column slot, so a column whose readers were all reduced away is still a decision without
 * this. Fixing such a column in the model would be wrong instead: an assumption, a neighborhood pin or an
 * enumeration may still need its other value, and only the search knows which of them it serves.
 */
internal class LeafColumns(private val bools: Bits, private val ints: Bits) {
    /** Whether Boolean column [v] is completed at leaves. */
    fun bool(v: Int): Boolean = bools.get(v)

    /** Whether integer column [v] is completed at leaves. */
    fun int(v: Int): Boolean = ints.get(v)

    /** These columns less every one [boolWeights] or [intCoefficients] gives a nonzero term, or null if none remain. */
    fun withoutTerms(boolWeights: LongArray, intCoefficients: LongArray): LeafColumns? {
        val keptBools = bools.copy()
        val keptInts = ints.copy()
        for (v in boolWeights.indices) if (boolWeights[v] != 0L && v < keptBools.size) keptBools.clear(v)
        for (v in intCoefficients.indices) if (intCoefficients[v] != 0L && v < keptInts.size) keptInts.clear(v)
        if (keptBools.cardinality() == 0 && keptInts.cardinality() == 0) return null
        return LeafColumns(keptBools, keptInts)
    }
}

/**
 * The columns of [problem] that no factor declares, or null when every column has one.
 *
 * Every factor counts, inert and implied ones included: a factor without a propagator is still enforced by a peer
 * or by the leaf's original check, and it reads its columns there.
 */
internal fun unreadColumnsOf(problem: Problem): LeafColumns? {
    val bools = Bits.full(problem.numBoolVars)
    val ints = Bits.full(problem.numIntVars)
    for (factor in problem.factors) {
        for (v in factor.boolVars) bools.clear(v)
        for (v in factor.intVars) ints.clear(v)
    }
    return if (bools.cardinality() == 0 && ints.cardinality() == 0) null else LeafColumns(bools, ints)
}
