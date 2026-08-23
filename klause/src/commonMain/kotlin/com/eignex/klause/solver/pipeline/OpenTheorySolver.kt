package com.eignex.klause.solver.pipeline

import com.eignex.klause.solver.ProblemPipeline
import com.eignex.klause.solver.ProblemSpec
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.pipeline
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.theory.TheoryParams
import com.eignex.klause.theory.difference.DifferenceTheorySolver
import com.eignex.klause.theory.lia.GeneralLiaAssignment
import com.eignex.klause.theory.lia.GeneralLiaResult
import com.eignex.klause.theory.lia.GeneralLiaSolver
import com.eignex.klause.theory.qflra.ExactLiraAssignment
import com.eignex.klause.theory.qflra.ExactLiraResult
import com.eignex.klause.theory.qflra.ExactLiraSolver
import com.eignex.klause.theory.qflra.ExactLraAssignment
import com.eignex.klause.theory.qflra.ExactLraResult
import com.eignex.klause.theory.qflra.ExactLraSolver

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
 * Route selection happens once from [ProblemSpec.pipeline]. Frontends consume this uniform result
 * rather than importing or dispatching to individual theory implementations.
 */
class OpenTheorySolver(private val model: ProblemSpec, private val route: ProblemPipeline) {

    init {
        require(route != ProblemPipeline.FINITE_CP && route != ProblemPipeline.UNSUPPORTED_OPEN) {
            "open theory solver requires a supported open source model"
        }
    }

    /** Decide the model with the complete theory selected for its source fragment. */
    fun solve(params: TheoryParams = TheoryParams()): OpenTheoryResult = when (route) {
        ProblemPipeline.DIFFERENCE_THEORY -> DifferenceTheorySolver(model).solve(params).asOpenTheoryResult()
        ProblemPipeline.GENERAL_LIA -> GeneralLiaSolver(model).solve(params).asOpenTheoryResult()
        ProblemPipeline.EXACT_LRA -> ExactLraSolver(model).solve(params).asOpenTheoryResult()
        ProblemPipeline.EXACT_LIRA -> ExactLiraSolver(model).solve(params).asOpenTheoryResult()
        ProblemPipeline.FINITE_CP, ProblemPipeline.UNSUPPORTED_OPEN -> error("validated open theory route changed")
    }
}

private fun SolveResult.asOpenTheoryResult(): OpenTheoryResult = when (this) {
    is SolveResult.Sat -> OpenTheoryResult.Sat(OpenTheoryAssignment.Difference(assignment), stats)
    is SolveResult.Unsat -> OpenTheoryResult.Unsat(stats)
    is SolveResult.Unknown -> OpenTheoryResult.Unknown(reason, stats)
}

private fun GeneralLiaResult.asOpenTheoryResult(): OpenTheoryResult = when (this) {
    is GeneralLiaResult.Sat -> OpenTheoryResult.Sat(OpenTheoryAssignment.GeneralLia(assignment), stats)
    is GeneralLiaResult.Unsat -> OpenTheoryResult.Unsat(stats)
    is GeneralLiaResult.Unknown -> OpenTheoryResult.Unknown(reason, stats)
}

private fun ExactLraResult.asOpenTheoryResult(): OpenTheoryResult = when (this) {
    is ExactLraResult.Sat -> OpenTheoryResult.Sat(OpenTheoryAssignment.ExactLra(assignment), stats)
    is ExactLraResult.Unsat -> OpenTheoryResult.Unsat(stats)
    is ExactLraResult.Unknown -> OpenTheoryResult.Unknown(reason, stats)
}

private fun ExactLiraResult.asOpenTheoryResult(): OpenTheoryResult = when (this) {
    is ExactLiraResult.Sat -> OpenTheoryResult.Sat(OpenTheoryAssignment.ExactLira(assignment), stats)
    is ExactLiraResult.Unsat -> OpenTheoryResult.Unsat(stats)
    is ExactLiraResult.Unknown -> OpenTheoryResult.Unknown(reason, stats)
}
