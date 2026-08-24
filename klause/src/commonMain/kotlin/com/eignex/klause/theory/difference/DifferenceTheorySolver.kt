package com.eignex.klause.theory.difference

import com.eignex.klause.arithmetic.difference.DifferenceEdge
import com.eignex.klause.arithmetic.difference.differenceFragmentOf
import com.eignex.klause.arithmetic.difference.potentialSample
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.ProblemPipeline
import com.eignex.klause.solver.ProblemSpec
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.pipeline
import com.eignex.klause.solver.search.SearchExplanation
import com.eignex.klause.theory.Theory
import com.eignex.klause.theory.TheoryCheck
import com.eignex.klause.theory.TheoryContext

/** Complete DPLL(T) satisfiability search for an open integer difference-logic [ProblemSpec]. */
class DifferenceTheorySolver(override val model: ProblemSpec) : Theory<Sample> {
    private val fragment = differenceFragmentOf(model.factors, model.numIntVars, model.intBounds)

    init {
        require(model.pipeline() == ProblemPipeline.DIFFERENCE_THEORY) {
            "difference-theory search requires complete difference coverage over an open integer model"
        }
    }

    override fun check(bools: BooleanArray, context: TheoryContext): TheoryCheck<Sample> {
        if (!context.consumeCheck()) return TheoryCheck.Cancelled
        return complete(bools)?.let { TheoryCheck.Sat(it) }
            ?: TheoryCheck.Infeasible(negativeCycleExplanation(bools))
    }

    private fun complete(bools: BooleanArray): Sample? {
        val values = if (fragment == null) {
            unconstrainedValues()
        } else {
            fragment.potentialSample(model.numIntVars, bools) ?: return null
        }
        return Sample(bools.copyOf(), values)
    }

    private fun negativeCycleExplanation(bools: BooleanArray): SearchExplanation? {
        val fragment = fragment ?: return null
        val active = BooleanArray(fragment.edges.size) { edge ->
            val guard = fragment.edges[edge].guard
            guard == DifferenceEdge.ALWAYS || bools[Lit.variable(guard)] == Lit.isPositive(guard)
        }
        val cycle = fragment.graph().negativeCycle(active) ?: return null
        val guards = cycle.map { fragment.edges[it].guard }
        if (guards.any { it == DifferenceEdge.ALWAYS }) return null
        return SearchExplanation(guards.distinct().map(Lit::negate).toIntArray())
    }

    private fun unconstrainedValues(): LongArray = LongArray(model.numIntVars) { variable ->
        when {
            model.intBounds.hasLower(variable) -> model.intBounds.lower(variable)
            model.intBounds.hasUpper(variable) -> model.intBounds.upper(variable)
            else -> 0L
        }
    }
}
