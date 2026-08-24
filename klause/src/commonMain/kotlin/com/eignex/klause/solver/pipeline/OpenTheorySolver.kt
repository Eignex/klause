package com.eignex.klause.solver.pipeline

import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.ProblemPipeline
import com.eignex.klause.solver.ProblemSpec
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.componentPlan
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.theory.TheoryParams
import com.eignex.klause.theory.lia.GeneralLiaAssignment
import com.eignex.klause.theory.qflra.ExactLiraAssignment
import com.eignex.klause.theory.qflra.ExactLraAssignment

/** A complete witness emitted by an open-model theory route. */
sealed interface OpenTheoryAssignment {
    /** A Long-backed integer witness from the difference theory. */
    data class Difference(
        /** The Long-backed difference-theory witness. */
        val sample: Sample,
    ) : OpenTheoryAssignment

    /** An arbitrary-precision integer witness from General LIA. */
    data class GeneralLia(
        /** The arbitrary-precision integer witness. */
        val assignment: GeneralLiaAssignment,
    ) : OpenTheoryAssignment

    /** A rational real witness from exact QF_LRA. */
    data class ExactLra(
        /** The rational real witness. */
        val assignment: ExactLraAssignment,
    ) : OpenTheoryAssignment

    /** A mixed arbitrary-precision integer and rational witness from exact QF_LIRA. */
    data class ExactLira(
        /** The mixed exact witness. */
        val assignment: ExactLiraAssignment,
    ) : OpenTheoryAssignment
}

/** The common verdict surface of the complete open-model theory routes. */
sealed interface OpenTheoryResult {
    /** Statistics collected by the selected theory. */
    val stats: SolveStats

    /** A satisfying witness. */
    data class Sat(
        /** The complete witness. */
        val assignment: OpenTheoryAssignment,
        override val stats: SolveStats,
    ) : OpenTheoryResult

    /** A proof that the source model is infeasible. */
    data class Unsat(override val stats: SolveStats) : OpenTheoryResult

    /** An explicit budget or cancellation interrupted exact search. */
    data class Unknown(
        /** The explicit interruption cause. */
        val reason: TerminationReason,
        override val stats: SolveStats,
    ) : OpenTheoryResult
}

/**
 * Selects and executes the complete theory route for an open source model.
 *
 * Route selection happens once from `ProblemSpec.pipeline()`. Frontends consume this uniform result
 * rather than importing or dispatching to individual theory implementations.
 */
class OpenTheoryEngine(model: ProblemSpec, route: ProblemPipeline) {
    private val model = model
    private val route = route

    init {
        require(route != ProblemPipeline.FINITE_CP && route != ProblemPipeline.UNSUPPORTED_OPEN) {
            "open theory solver requires a supported open source model"
        }
        require(model.componentPlan().theoryPipeline == route) { "open theory route disagrees with component plan" }
    }

    /** Decide the model through its immutable component plan and one shared search session. */
    fun solve(params: TheoryParams = TheoryParams()): OpenTheoryResult {
        val cancellation = Cancellation { params.cancellation() || model.cancellation() }
        val stats = SolveStatsSink(backend = route.backendName())
        stats.start()
        val planned = model.componentPlan().search(
            model,
            emptyMap(),
            maxChecks = params.maxLeaves,
            cancellation = cancellation,
        )
        when (planned.session.initialize()) {
            ComponentResult.Consistent -> Unit
            is ComponentResult.Conflict -> return OpenTheoryResult.Unsat(stats.finish())
            ComponentResult.Indeterminate -> return unknown(planned.session.cancelled(), stats)
        }
        return when (val result = planned.session.solve(model.numBoolVars)) {
            is SearchResult.Satisfied -> OpenTheoryResult.Sat(
                assignment(result.model, checkNotNull(planned.theory)),
                stats.finish(),
            )

            SearchResult.Exhausted -> OpenTheoryResult.Unsat(stats.finish())

            SearchResult.Indeterminate -> unknown(planned.session.cancelled(), stats)
        }
    }

    /**
     * An unknown from a complete theory always means a spent allowance — the cancellation token or the
     * leaf budget — so the run is reported as timed out. Without it a caller cannot tell an exhausted
     * budget from a structural stop, and reports the wrong reason for every open run.
     */
    private fun unknown(cancelled: Boolean, stats: SolveStatsSink): OpenTheoryResult.Unknown {
        stats.timedOut = true
        return OpenTheoryResult.Unknown(
            if (cancelled) TerminationReason.Cancelled else TerminationReason.BudgetExhausted,
            stats.finish(),
        )
    }

    private fun SolveStatsSink.finish(): SolveStats {
        stop()
        return snapshot()
    }

    private fun ProblemPipeline.backendName(): String = when (this) {
        ProblemPipeline.DIFFERENCE_THEORY -> "difference-theory"
        ProblemPipeline.GENERAL_LIA -> "general-lia"
        ProblemPipeline.EXACT_LRA -> "exact-lra"
        ProblemPipeline.EXACT_LIRA -> "exact-lira"
        ProblemPipeline.FINITE_CP, ProblemPipeline.UNSUPPORTED_OPEN -> error("not an open theory route")
    }

    private fun assignment(
        model: com.eignex.klause.solver.search.AssembledSearchModel,
        component: Any,
    ): OpenTheoryAssignment = when (route) {
        ProblemPipeline.DIFFERENCE_THEORY -> OpenTheoryAssignment.Difference(
            checkNotNull(model.valueOf<Sample>(component)),
        )

        ProblemPipeline.GENERAL_LIA -> OpenTheoryAssignment.GeneralLia(
            checkNotNull(model.valueOf<GeneralLiaAssignment>(component)),
        )

        ProblemPipeline.EXACT_LRA -> OpenTheoryAssignment.ExactLra(
            checkNotNull(model.valueOf<ExactLraAssignment>(component)),
        )

        ProblemPipeline.EXACT_LIRA -> OpenTheoryAssignment.ExactLira(
            checkNotNull(model.valueOf<ExactLiraAssignment>(component)),
        )

        ProblemPipeline.FINITE_CP, ProblemPipeline.UNSUPPORTED_OPEN -> error("validated open theory route changed")
    }
}
