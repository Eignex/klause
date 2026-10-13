package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.solver.result.SmtStatsSink
import com.eignex.klause.solver.search.SearchExactIntValue
import com.eignex.klause.solver.search.SearchExactRealValue
import com.eignex.klause.solver.search.SearchIntValue
import com.eignex.klause.solver.search.SearchRealValue
import com.eignex.klause.solver.search.TheoryComponent
import com.eignex.klause.solver.search.SearchModel
import com.eignex.klause.solver.search.contribute
import com.eignex.klause.theory.difference.DifferenceSearchComponent
import com.eignex.klause.theory.qflra.ExactLiraSearchComponent
import com.eignex.klause.theory.qflra.ExactLiraAssignment
import com.eignex.klause.theory.qflra.PreparedExactLiraComponentFactory
import com.eignex.klause.util.Cancellation

/** Builds the theory component selected by this plan. */
internal fun ComponentPlan.theoryComponent(
    spec: Problem,
    smtStats: SmtStatsSink? = null,
    theorySolveStop: Cancellation? = null,
): TheoryComponent? = openTheoryComponent(theoryFragment(spec), smtStats, theorySolveStop)

internal fun ComponentPlan.openTheoryComponent(
    fragment: Problem,
    smtStats: SmtStatsSink? = null,
    theorySolveStop: Cancellation? = null,
    preparedSource: PreparedExactLiraComponentFactory? = null,
): TheoryComponent? {
    fun exact(contribution: (ExactLiraAssignment, SearchModel) -> Unit): ExactLiraSearchComponent =
        preparedSource?.open(contribution) ?: ExactLiraSearchComponent(fragment, contribution)
    return when (theoryPipeline) {
        ProblemPipeline.DIFFERENCE_THEORY -> DifferenceSearchComponent.withRootBounds(
            fragment,
            theoryIntVars,
            cpIntVars,
        )

        ProblemPipeline.EXACT_LRA -> exact { assignment, model ->
            assignment.reals.forEachIndexed { variable, value ->
                model.put(SearchRealValue(variable), value)
                model.contribute(SearchExactRealValue(SourceRealId(variable)), value)
            }
        }.also { component ->
            component.useSolveStop(theorySolveStop)
            smtStats?.let(component::observeWith)
        }

        ProblemPipeline.EXACT_LIRA -> exact { assignment, model ->
            assignment.ints.forEachIndexed { variable, value ->
                if (intOwner(variable) == IntVariableOwner.THEORY) {
                    model.put(SearchIntValue(variable), value)
                    model.contribute(SearchExactIntValue(SourceIntId(variable)), value)
                }
            }
            assignment.reals.forEachIndexed { variable, value ->
                model.put(SearchRealValue(variable), value)
                model.contribute(SearchExactRealValue(SourceRealId(variable)), value)
            }
        }.also { component ->
            component.useSolveStop(theorySolveStop)
            smtStats?.let(component::observeWith)
        }

        ProblemPipeline.FINITE_CP -> null

        ProblemPipeline.UNSUPPORTED_OPEN -> error("no complete theory component covers the selected fragment")
    }
}
