package com.eignex.klause.theory.difference

import com.eignex.klause.arithmetic.difference.differenceFragmentOf
import com.eignex.klause.arithmetic.difference.potentialSample
import com.eignex.klause.solver.ProblemPipeline
import com.eignex.klause.solver.ProblemSpec
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.pipeline
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.theory.BooleanTheoryResult
import com.eignex.klause.theory.BooleanTheorySearch
import com.eignex.klause.theory.TheoryParams

/** Complete DPLL(T) satisfiability search for an open integer difference-logic [ProblemSpec]. */
class DifferenceTheorySolver(private val model: ProblemSpec) {
    private val fragment = differenceFragmentOf(model.factors, model.numIntVars, model.intBounds)
    private val clauses = model.factors.filterIsInstance<Clause>()

    init {
        require(model.pipeline() == ProblemPipeline.DIFFERENCE_THEORY) {
            "difference-theory search requires complete difference coverage over an open integer model"
        }
    }

    /** Decide satisfiability without materializing integer search bounds. */
    fun solve(params: TheoryParams = TheoryParams()): SolveResult = when (
        val outcome = BooleanTheorySearch(model.numBoolVars, clauses, params.withModelCancellation()).first(::complete)
    ) {
        is BooleanTheoryResult.Found -> SolveResult.Sat(outcome.value)
        BooleanTheoryResult.Exhausted -> SolveResult.Unsat()
        BooleanTheoryResult.Cancelled -> SolveResult.Unknown(TerminationReason.Cancelled)
        BooleanTheoryResult.Unknown -> SolveResult.Unknown(TerminationReason.BudgetExhausted)
    }

    private fun complete(bools: BooleanArray): Sample? {
        val values = if (fragment == null) {
            unconstrainedValues()
        } else {
            fragment.potentialSample(model.numIntVars, bools) ?: return null
        }
        return Sample(bools.copyOf(), values)
    }

    private fun unconstrainedValues(): LongArray = LongArray(model.numIntVars) { variable ->
        when {
            model.intBounds.hasLower(variable) -> model.intBounds.lower(variable)
            model.intBounds.hasUpper(variable) -> model.intBounds.upper(variable)
            else -> 0L
        }
    }

    private fun TheoryParams.withModelCancellation(): TheoryParams = copy(
        cancellation = Cancellation { this@withModelCancellation.cancellation() || model.cancellation() },
    )
}
