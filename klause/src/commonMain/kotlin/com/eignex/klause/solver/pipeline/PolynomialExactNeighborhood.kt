package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.backtrack.composedFixpoint
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.theory.qfnra.PolynomialCondition
import com.eignex.klause.theory.qfnra.PolynomialTheoryInvocation
import com.eignex.klause.theory.qfnra.QfNraResult
import com.eignex.klause.util.Cancellation

internal class PolynomialExactNeighborhood(
    private val model: SharedPolynomialModel,
    private val invocation: PolynomialTheoryInvocation,
    private val finite: BakedProblem,
    private val fixed: Sample,
    private val assumptions: Assumptions,
    private val objective: LinearObjective?,
    private val certify: (Sample) -> Sample,
) : ExactNeighborhoodCompletion {
    private val integerPins = model.integerColumns.map { (id, column) ->
        column to BigFraction.ofLong(fixed.intValue(id))
    }.toMap()
    private val coordinates = (0 until model.service.realCount).filter { it !in integerPins }
    private val objectiveValue = objective?.evaluateExact(fixed)

    override fun check(
        condition: ExactNeighborhoodCondition,
        cancellation: Cancellation,
    ): ExactNeighborhoodResult {
        if (condition.cutoff != null && objectiveValue != null && objectiveValue >= condition.cutoff) {
            return ExactNeighborhoodResult.Refuted
        }
        when (composedFixpoint(finite, Candidate(fixed, Unit), cancellation, assumptions,
            enforcingPeers = model.finiteEnforcingFactors)) {
            is Verification.Rejected -> return ExactNeighborhoodResult.Refuted
            is Verification.Indeterminate -> return ExactNeighborhoodResult.Undecided
            is Verification.Accepted -> Unit
        }
        fun remap(values: Map<Int, BigFraction>) = integerPins + values.mapKeys { (id, _) -> coordinates[id] }
        return when (val result = invocation.check(PolynomialCondition(
            (0 until model.source.numBoolVars).associateWith(fixed::boolValue),
            remap(condition.lower), remap(condition.upper),
        ), cancellation)) {
            is QfNraResult.Satisfiable -> {
                val sample = certify(model.capture(result.assignment, fixed.bools, fixed.ints))
                ExactNeighborhoodResult.Witness(sample)
            }
            is QfNraResult.Unsatisfiable -> ExactNeighborhoodResult.Refuted
            is QfNraResult.Unknown -> ExactNeighborhoodResult.Undecided
        }
    }

    fun search(adaptive: Boolean, seed: Long, maximumMoves: Long, maximumInstructions: Long,
        maximumImprovementMoves: Long?, nodeBudget: NodeBudget?): ExactNeighborhood = ExactNeighborhood(
        0, List(coordinates.size) { BigFraction.ZERO }, this, objective,
        coordinateValues = { sample -> centers(sample) }, adaptive = adaptive, seed = seed, nodeBudget = nodeBudget,
        maximumMoves = maximumMoves, maximumInstructions = maximumInstructions,
        maximumImprovementMoves = maximumImprovementMoves, nativeWork = { invocation.stats.workSpent },
    )

    private fun centers(sample: Sample): List<BigFraction> = coordinates.map { id ->
        sample.algebraicReals?.getOrNull(id)?.let { real ->
            real.rational ?: (real.lowerBound + real.upperBound) * BigFraction.ofLong(2).reciprocal()
        } ?: BigFraction.ZERO
    }
}
