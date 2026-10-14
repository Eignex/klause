package com.eignex.klause.theory

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.pipeline.ExactSearchRow
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchComponentSet
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.theory.qflra.prepareExactLiraSource
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation

internal fun privateLinearCompletion(encoded: Problem, sample: Sample, cancellation: Cancellation): Boolean? {
    if (cancellation()) return null
    val values = sample.exactReals ?: return null
    if (values.size != encoded.encodingSourceRealCount || sample.numIntVars != encoded.numIntVars ||
        sample.numBoolVars != encoded.numBoolVars
    ) return null
    val factors = ArrayList<Factor>()
    factors.addAll(encoded.factors)
    for (id in 0 until encoded.numBoolVars) {
        if (cancellation()) return null
        cancellation.charge(1)
        factors += Clause(intArrayOf(Lit.make(id, sample.boolValue(id))))
    }
    for (id in 0 until encoded.numIntVars) {
        if (cancellation()) return null
        cancellation.charge(1)
        factors += ExactSearchRow.of(intArrayOf(Term.ofIntVar(id)), listOf(BigFraction.ONE), LinearOp.EQ,
            BigFraction.of(sample.exactIntValue(id), BIG_ONE))
    }
    for (id in values.indices) {
        if (cancellation()) return null
        cancellation.charge(1)
        factors += ExactSearchRow.of(intArrayOf(Term.ofRealVar(id)), listOf(BigFraction.ONE), LinearOp.EQ, values[id])
    }
    val model = encoded.rebindEncoding(encoded, factors.toTypedArray(), null)
    val factory = prepareExactLiraSource(model, cancellation) ?: return null
    factory.open().use { component ->
        val session = SearchComponentSet(listOf(component)).session(cancellation = cancellation,
            atoms = SearchAtomRegistry(encoded.numBoolVars))
        return when (session.initialize()) {
            is ComponentResult.Conflict -> false
            ComponentResult.Indeterminate -> null
            ComponentResult.Consistent -> when (session.solve(encoded.numBoolVars)) {
                SearchResult.Exhausted -> false
                SearchResult.Indeterminate -> null
                is SearchResult.Satisfied -> true
            }
        }
    }
}
