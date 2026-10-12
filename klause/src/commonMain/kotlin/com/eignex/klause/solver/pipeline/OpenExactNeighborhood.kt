package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.linearRows
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf

internal class OpenExactNeighborhood(
    private val model: Problem,
    private val params: TheoryParams,
    private val objective: LinearObjective? = null,
) : ExactNeighborhoodCompletion {
    private val state = OpenTheorySolveState(params)
    private var nativeStats = SolveStats.EMPTY
    val exhausted: Boolean get() = !state.work.hasAllowance() ||
        state.work.snapshot().openTheoryChecks >= params.maxLeaves

    override fun check(
        condition: ExactNeighborhoodCondition,
        cancellation: Cancellation,
    ): ExactNeighborhoodResult {
        if (cancellation() || exhausted) return ExactNeighborhoodResult.Undecided
        val additions = ArrayList<Factor>()
        for ((id, value) in condition.bools) additions += Clause(intArrayOf(Lit.make(id, value)))
        fun bounds(values: Map<Int, BigFraction>, relation: LinearOp) {
            for ((coordinate, value) in values) {
                val ref = if (coordinate < model.numIntVars) Term.ofIntVar(coordinate)
                else Term.ofRealVar(coordinate - model.numIntVars)
                additions += ExactSearchRow.of(intArrayOf(ref), listOf(BigFraction.ONE), relation, value)
            }
        }
        bounds(condition.lower, LinearOp.GE)
        bounds(condition.upper, LinearOp.LE)
        if (objective != null && condition.cutoff != null) additions += objective.below(condition.cutoff)
        val constrained = model.withFactors(model.factors + additions,
            model.impliedFactorMask?.let { it + BooleanArray(additions.size) })
        val plan = constrained.componentPlan()
        if (plan.theoryPipeline == ProblemPipeline.UNSUPPORTED_OPEN || plan.theoryPipeline == ProblemPipeline.FINITE_CP) {
            return ExactNeighborhoodResult.Undecided
        }
        val engine = OpenTheoryEngine(constrained, plan.theoryPipeline, PresolveConfig.NONE, true,
            Cancellation.Never, null)
        val result = engine.solve(params.copy(cancellation = params.cancellation or cancellation,
            maxLeaves = (params.maxLeaves - state.work.snapshot().openTheoryChecks).coerceAtLeast(0)), state)
        nativeStats = result.stats.copy(lp = nativeStats.lp.mergedWith(result.stats.lp))
        return when (result) {
            is OpenTheoryResult.Sat -> ExactNeighborhoodResult.Witness(result.assignment.toSample(model))
            is OpenTheoryResult.Unsat -> ExactNeighborhoodResult.Refuted
            is OpenTheoryResult.Unknown -> ExactNeighborhoodResult.Undecided
        }
    }

    fun search(adaptive: Boolean, seed: Long = 0, maximumMoves: Long = Long.MAX_VALUE): ExactNeighborhood =
        ExactNeighborhood(model.numBoolVars, centers(), this, objective,
            coordinateValues = { sample -> coordinates(sample) }, adaptive = adaptive, seed = seed,
            nodeBudget = params.nodeBudget, maximumMoves = maximumMoves, nativeWork = { state.work.spent },
            completionStats = { nativeStats })

    private fun coordinates(sample: Sample): List<BigFraction> =
        List(model.numIntVars) { BigFraction.of(sample.exactIntValue(it), BIG_ONE) } + sample.exactReals.orEmpty()

    private fun centers(): List<BigFraction> {
        val centers = MutableList(model.numIntVars + model.numRealVars) { BigFraction.ZERO }
        for (id in 0 until model.numIntVars) {
            val bounds = model.intBounds
            if (bounds.hasLower(id)) centers[id] = greater(centers[id], BigFraction.ofLong(bounds.lower(id)))
            if (bounds.hasUpper(id)) centers[id] = lesser(centers[id], BigFraction.ofLong(bounds.upper(id)))
        }
        for (id in 0 until model.numRealVars) {
            val coordinate = model.numIntVars + id
            BigFraction.ofDouble(model.realLower[id])?.let { centers[coordinate] = greater(centers[coordinate], it) }
            BigFraction.ofDouble(model.realUpper[id])?.let { centers[coordinate] = lesser(centers[coordinate], it) }
        }
        for (factor in model.factors) for (row in factor.linearRows) {
            if (row.size != 1 || row.activator >= 0 || row.relation != LinearOp.EQ) continue
            val constants = row.constants as? IntegralConstants ?: continue
            val coefficient = constants.exactCoeff(0)
            if (coefficient == bigIntOf(0)) continue
            val value = BigFraction.of(constants.exactBound, coefficient)
            val ref = row.ref(0)
            if (Term.isInt(ref) && value.den == BIG_ONE) centers[Term.intVar(ref)] = value
            if (Term.isReal(ref)) centers[model.numIntVars + Term.realVar(ref)] = value
        }
        return centers
    }
}

internal fun LinearObjective.below(value: BigFraction): ExactSearchRow {
    val refs = ArrayList<Int>()
    val coefficients = ArrayList<BigFraction>()
    for (id in boolWeightValues.indices) if (boolWeightValues[id] != 0L) {
        refs += Term.ofLit(Lit.make(id, true))
        coefficients += BigFraction.ofLong(boolWeightValues[id])
    }
    for (id in intCoefficientValues.indices) if (intCoefficientValues[id] != 0L) {
        refs += Term.ofIntVar(id)
        coefficients += BigFraction.ofLong(intCoefficientValues[id])
    }
    for (id in realCoefficientValues.indices) if (realCoefficientValues[id] != 0.0) {
        refs += Term.ofRealVar(id)
        coefficients += requireNotNull(BigFraction.ofDouble(realCoefficientValues[id]))
    }
    return ExactSearchRow.of(refs.toIntArray(), coefficients, LinearOp.LE,
        value - BigFraction.ofLong(constant), strict = true)
}

private fun greater(left: BigFraction, right: BigFraction): BigFraction = if (left >= right) left else right

private fun lesser(left: BigFraction, right: BigFraction): BigFraction = if (left <= right) left else right
