package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.linearRows
import com.eignex.klause.portfolio.verifyRealCoordinates
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.EvidenceCertificate
import com.eignex.klause.solver.incumbent.EvidenceKind
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.search.SearchCandidateHints
import com.eignex.klause.solver.search.SearchLearnedDbParams
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.fitsLong
import kotlin.coroutines.cancellation.CancellationException

internal class OpenExactNeighborhood(
    private val model: Problem,
    private val params: TheoryParams,
    private val objective: LinearObjective? = null,
    preparedSource: PreparedSourcePlan? = null,
) : ExactNeighborhoodCompletion, AutoCloseable {
    private val prepared by lazy {
        (preparedSource ?: model.prepareExecutionPlan(objective)).prepareConditionedLinearQuery()
    }
    private val componentPreparation = lazy {
        val source = prepared ?: return@lazy null
        val working = requireNotNull(source.model)
        val plan = requireNotNull(source.componentPlan)
        PreparedSearchComponents(plan, working, plan.cpSourceDomains(working))
    }
    private val components by componentPreparation
    private val queries = RetainedLinearQueries(model, params, objective, hints = { stop, state ->
        val source = prepared
        val plan = source?.componentPlan
        if (plan == null) SearchCandidateHints.None
        else state.candidateHints(plan, requireNotNull(source?.model), stop)
    }, open = ::open)
    val exhausted: Boolean get() = queries.exhausted
    val work: Long get() = queries.work
    val stats: SolveStats get() = queries.stats
    val preparationWork: Long get() = if (componentPreparation.isInitialized()) components?.preparationWork ?: 0 else 0
    val sourcePrepared: Boolean get() = componentPreparation.isInitialized() && components?.sourcePrepared == true

    init {
        require(preparedSource == null || preparedSource.belongsTo(model)) {
            "completion preparation belongs to a different source"
        }
    }

    private fun open(stop: Cancellation, state: OpenTheorySolveState): PlannedSearch? {
        val source = components ?: return null
        if (!source.prepareSource(stop)) return null
        return source.open(maxChecks = params.maxLeaves, cancellation = stop,
            learnedDb = SearchLearnedDbParams(params.maxLearnedClauses, params.lbdGlue),
            smtStats = state.smt, theorySolveStop = stop)
    }

    fun <T> withCondition(condition: ExactNeighborhoodCondition, cancellation: Cancellation, action: () -> T): T =
        queries.withCondition(condition, cancellation, action)

    override fun check(
        condition: ExactNeighborhoodCondition,
        cancellation: Cancellation,
    ): ExactNeighborhoodResult {
        val result = queries.check(condition, cancellation)
        if (result !is ExactNeighborhoodResult.Witness) return result
        if ((params.timeout or params.cancellation or cancellation)()) return ExactNeighborhoodResult.Undecided
        val sample = result.sample
        val checked = try {
            if (sample.exactInts?.any { !it.fitsLong() } == true || model.factors.any { it is ExactSearchRow }) {
                verifyRealCoordinates(model, sample)
            } else {
                verifyOpenWitness(model, sample)
            }
        } catch (_: CancellationException) {
            return ExactNeighborhoodResult.Undecided
        }
        if (checked !is Verification.Accepted || (params.timeout or params.cancellation or cancellation)()) {
            return ExactNeighborhoodResult.Undecided
        }
        sample.witnessCertificate = EvidenceCertificate.verified(ModelIdentity.of(model), EvidenceKind.Witness)
        return result
    }

    override fun close() = queries.close()

    fun search(
        adaptive: Boolean,
        seed: Long = 0,
        maximumMoves: Long = Long.MAX_VALUE,
        completion: ExactNeighborhoodCompletion = this,
        maximumImprovementMoves: Long? = null,
    ): ExactNeighborhood =
        ExactNeighborhood(model.numBoolVars, centers(), completion, objective,
            coordinateValues = { sample -> coordinates(sample) }, adaptive = adaptive, seed = seed,
            nodeBudget = params.nodeBudget, maximumMoves = minOf(maximumMoves, params.maxFlips),
            maximumInstructions = params.maxInstructions ?: Long.MAX_VALUE,
            maximumImprovementMoves = maximumImprovementMoves, nativeWork = { work },
            completionStats = { stats }, onClose = ::close)

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
