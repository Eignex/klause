package com.eignex.klause.theory.difference

import com.eignex.klause.arithmetic.difference.differenceFragmentOf
import com.eignex.klause.arithmetic.difference.potentialSample
import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.SearchOutcome
import com.eignex.klause.backtrack.driveSearch
import com.eignex.klause.propagation.difference.DifferenceSystem
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.ProblemPipeline
import com.eignex.klause.solver.ProblemSpec
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.pipeline
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.result.TerminationReason

/** Complete DPLL(T) satisfiability search for an open integer difference-logic [ProblemSpec]. */
class DifferenceTheorySolver(private val model: ProblemSpec) {
    private val fragment = differenceFragmentOf(model.factors, model.numIntVars, model.intBounds)
    private val searchProblem = Problem(
        numBoolVars = model.numBoolVars,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = model.factors.filter { it.intVars.isEmpty() }.toTypedArray() +
            listOfNotNull(fragment?.let { DifferenceSystem(it.edges, theoryOnly = true) }),
        seedDeductions = model.seedDeductions,
        cancellation = model.cancellation,
    )

    init {
        require(model.pipeline() == ProblemPipeline.DIFFERENCE_THEORY) {
            "difference-theory search requires complete difference coverage over an open integer model"
        }
    }

    /** Decide satisfiability without materializing integer search bounds. */
    fun solve(params: BacktrackParams = BacktrackParams()): SolveResult {
        val sink = SolveStatsSink(backend = "difference")
        sink.start()
        val solver = BacktrackSolver(searchProblem.bake())
        for (outcome in solver.driveSearch(params, sink) { snap, _ -> complete(snap) }) {
            sink.stop()
            val stats = sink.snapshot()
            return when (outcome) {
                is SearchOutcome.Found -> SolveResult.Sat(outcome.sample, stats)
                is SearchOutcome.Exhausted -> SolveResult.Unsat(outcome.core, stats)
                SearchOutcome.BudgetCapped -> SolveResult.Unknown(TerminationReason.BudgetExhausted, stats)
            }
        }
        sink.stop()
        return SolveResult.Unsat(stats = sink.snapshot())
    }

    private fun complete(snap: Sample): Sample? {
        val values = if (fragment == null) {
            unconstrainedValues()
        } else {
            fragment.potentialSample(model.numIntVars, snap.bools) ?: return null
        }
        return snap.copy(ints = values)
    }

    private fun unconstrainedValues(): LongArray = LongArray(model.numIntVars) { variable ->
        when {
            model.intBounds.hasLower(variable) -> model.intBounds.lower(variable)
            model.intBounds.hasUpper(variable) -> model.intBounds.upper(variable)
            else -> 0L
        }
    }
}
