package com.eignex.klause.propagation

import com.eignex.klause.ir.Problem
import com.eignex.util.collections.Bits

// Columns a traversal completes at its leaves (Boolean false, integer minimum) instead of deciding them. That
// leaf is a model only while no factor, objective term or distinguishing enumeration reads the columns.
// Presolve keeps every column slot, so a column whose readers were all reduced away is still a decision without
// this. Fixing it in the model instead would be wrong: an assumption, a neighborhood pin or an enumeration may
// still need its other value, and only the search knows which it serves.
internal class LeafColumns(private val bools: Bits, private val ints: Bits) {
    fun bool(v: Int): Boolean = bools.get(v)

    fun int(v: Int): Boolean = ints.get(v)

    fun withoutTerms(boolWeights: LongArray, intCoefficients: LongArray): LeafColumns? {
        val keptBools = bools.copy()
        val keptInts = ints.copy()
        for (v in boolWeights.indices) if (boolWeights[v] != 0L && v < keptBools.size) keptBools.clear(v)
        for (v in intCoefficients.indices) if (intCoefficients[v] != 0L && v < keptInts.size) keptInts.clear(v)
        if (keptBools.cardinality() == 0 && keptInts.cardinality() == 0) return null
        return LeafColumns(keptBools, keptInts)
    }
}

// Inert and implied factors count too: a factor without a propagator is still enforced by a peer or by the
// leaf's original check, which reads its columns.
internal fun unreadColumnsOf(problem: Problem): LeafColumns? {
    val bools = Bits.full(problem.numBoolVars)
    val ints = Bits.full(problem.numIntVars)
    for (factor in problem.factors) {
        for (v in factor.boolVars) bools.clear(v)
        for (v in factor.intVars) ints.clear(v)
    }
    return if (bools.cardinality() == 0 && ints.cardinality() == 0) null else LeafColumns(bools, ints)
}
