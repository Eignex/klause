package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.solver.selectedFactorProjections
import com.eignex.klause.solver.result.SmtStatsSink
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchExactIntValue
import com.eignex.klause.solver.search.SearchExactRealValue
import com.eignex.klause.solver.search.SearchIntValue
import com.eignex.klause.solver.search.SearchInvocationResources
import com.eignex.klause.solver.search.SearchModel
import com.eignex.klause.solver.search.SearchRealValue
import com.eignex.klause.solver.search.TheoryComponent
import com.eignex.klause.solver.search.contribute
import com.eignex.klause.theory.difference.DifferenceSearchComponent
import com.eignex.klause.theory.qflra.ExactLiraAssignment
import com.eignex.klause.theory.qflra.ExactLiraSearchComponent
import com.eignex.klause.theory.qflra.PreparedExactLiraComponentFactory
import com.eignex.klause.util.Cancellation

/** Builds the theory component selected by this plan. */
internal fun ComponentPlan.theoryComponent(
    spec: Problem,
    smtStats: SmtStatsSink? = null,
    theorySolveStop: Cancellation? = null,
): TheoryComponent? =
    openTheoryComponent(encodedTheoryFragment(spec) ?: theoryFragment(spec), smtStats, theorySolveStop)

@Suppress("TooGenericExceptionCaught") // Invocation cleanup preserves an arbitrary primary failure.
internal fun ComponentPlan.openTheoryComponent(
    fragment: Problem,
    smtStats: SmtStatsSink? = null,
    theorySolveStop: Cancellation? = null,
    preparedSource: PreparedExactLiraComponentFactory? = null,
): TheoryComponent? {
    val resources = SearchInvocationResources()
    val opening = SearchComponentOpening(Cancellation.Never, smtStats, theorySolveStop, resources)
    return try {
        theoryComponentFactory(fragment) { preparedSource }?.invoke(opening)?.single() as? TheoryComponent
    } catch (failure: Throwable) {
        resources.closeAfter(failure)
        throw failure
    }
}

internal fun ComponentPlan.theoryComponentFactory(
    fragment: Problem,
    preparedSource: () -> PreparedExactLiraComponentFactory?,
): ((SearchComponentOpening) -> List<SearchComponent>)? {
    fun exact(
        opening: SearchComponentOpening,
        contribution: (ExactLiraAssignment, SearchModel) -> Unit,
    ): List<SearchComponent> = listOf(
        (preparedSource()?.open(contribution, cpIntVars) ?: ExactLiraSearchComponent(
            fragment, fragment.selectedFactorProjections(), contribution, cpIntVars,
        )).also { component ->
            opening.ownComponent(component)
            component.useSolveStop(opening.theorySolveStop)
            opening.smtStats?.let(component::observeWith)
        },
    )
    return when (theoryPipeline) {
        ProblemPipeline.DIFFERENCE_THEORY -> {
            val owned = theoryIntVars
            val finite = cpIntVars
            val factory: (SearchComponentOpening) -> List<SearchComponent> = { _: SearchComponentOpening ->
                listOf(DifferenceSearchComponent.withRootBounds(fragment, owned, finite))
            }
            factory
        }

        ProblemPipeline.EXACT_LRA -> { opening ->
            exact(opening) { assignment, model ->
                assignment.reals.forEachIndexed { variable, value ->
                    model.put(SearchRealValue(variable), value)
                    model.contribute(SearchExactRealValue(SourceRealId(variable)), value)
                }
            }
        }

        ProblemPipeline.EXACT_LIRA -> { opening ->
            exact(opening) { assignment, model ->
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
            }
        }

        ProblemPipeline.FINITE_CP -> null

        ProblemPipeline.UNSUPPORTED_OPEN -> { _ ->
            error("no complete theory component covers the selected fragment")
        }
    }
}
