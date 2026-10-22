package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.BigRationalConflict
import com.eignex.klause.simplex.exact.ContinuationStop
import com.eignex.klause.simplex.exact.ExactContinuationLimits
import com.eignex.klause.simplex.exact.ExactSimplexBound
import com.eignex.klause.simplex.exact.SparseFeasibility
import com.eignex.klause.simplex.exact.SparseFeasibilityBounds
import com.eignex.klause.simplex.exact.SparseFeasibilityBudget
import com.eignex.klause.simplex.exact.SparseFeasibilityModel
import com.eignex.klause.util.Cancellation

internal class LpPrimaryFeasibilityAttempt(val admitted: Boolean, val result: CertifiedLpResult?, val metrics: LpSolveMetrics)

private class PrimaryFeasibilityModel(private val source: ExactLpModel) : SparseFeasibilityModel {
    override val n get() = source.n
    override val m get() = source.m
    override fun rhs(row: Int) = source.rhs(row).value
    override fun column(column: Int, visit: (Int, BigFraction) -> Unit) {
        for (entry in source.columnEntries(column)) visit(entry.row, entry.number.value)
    }
}

private class PrimaryFeasibilityBounds(private val source: ExactLpModel) : SparseFeasibilityBounds {
    override val size get() = source.numVars
    override fun lower(column: Int) = source.column(column).bounds.lower?.number?.value
    override fun upper(column: Int) = source.column(column).bounds.upper?.number?.value
}

private class PrimaryFeasibilityEvidence(
    val witness: ExactLpWitness? = null,
    val conflict: BigRationalConflict? = null,
    val support: LpExactSupport? = null,
)

internal class LpPrimaryFeasibilityCache {
    private var base: ExactLpModel? = null
    private var authority: LpExactState? = null
    private var kernel: SparseFeasibility? = null
    private var verified: PrimaryFeasibilityEvidence? = null
    private var usedWork = 0L
    private var usedPivots = 0
    private var ineligible = false
    var lastMetrics = LpSolveMetrics()
        private set

    fun clear() {
        base = null
        authority = null
        kernel = null
        verified = null
        usedWork = 0L
        usedPivots = 0
        ineligible = false
        lastMetrics = LpSolveMetrics()
    }

    fun solve(
        state: LpExactState,
        token: Cancellation,
        policy: LpCertificationPolicy,
        observer: LpCertificationObserver?,
        workLimit: Long,
        iterationLimit: Int,
    ): LpPrimaryFeasibilityAttempt {
        lastMetrics = LpSolveMetrics()
        if (base !== state.baseModel) {
            clear()
            base = state.baseModel
        }
        if (authority !== state) {
            authority = state
            verified = null
            usedWork = 0L
            usedPivots = 0
            ineligible = false
        }
        val timer = LpPhaseTimer(observer, LpSolvePhase.PRIMARY_EXACT_FEASIBILITY)
        if (ineligible) {
            timer.finish("INELIGIBLE_CACHED")
            return LpPrimaryFeasibilityAttempt(false, null, LpSolveMetrics())
        }
        verified?.let {
            timer.finish("CACHED")
            return LpPrimaryFeasibilityAttempt(true, publish(it, token, policy, observer), LpSolveMetrics())
        }
        val defaults = ExactContinuationLimits()
        val maximumWork = if (workLimit > 0L) minOf(workLimit, defaults.maxWork) else defaults.maxWork
        val maximumPivots = if (iterationLimit > 0) minOf(iterationLimit, defaults.maxPivots) else defaults.maxPivots
        val budget = SparseFeasibilityBudget(defaults.copy(maxWork = (maximumWork - usedWork).coerceAtLeast(0L)), token)
        var admitted = true
        var pivots = 0
        var outcome = "INDETERMINATE"
        var evidence: PrimaryFeasibilityEvidence? = null
        try {
            if (!eligible(state, budget)) {
                admitted = false
                ineligible = true
                outcome = "INELIGIBLE"
            } else {
                val current = kernel ?: SparseFeasibility(PrimaryFeasibilityModel(state.model)).also { kernel = it }
                val proposal = current.resume(PrimaryFeasibilityBounds(state.model), budget,
                    (maximumPivots - usedPivots).coerceAtLeast(0))
                pivots = proposal.pivots
                evidence = when {
                    proposal.values != null -> checkedPoint(state, proposal.values, budget)?.let {
                        PrimaryFeasibilityEvidence(witness = it)
                    }
                    proposal.ray != null -> checkedConflict(state, proposal.ray, budget)
                    else -> null
                }
                outcome = when {
                    evidence?.witness != null -> "FEASIBLE"
                    evidence?.conflict != null -> "INFEASIBLE"
                    proposal.decline != null -> "DECLINED_${proposal.decline.name}"
                    else -> "REJECTED_CANDIDATE"
                }
                verified = evidence
            }
        } catch (stop: ContinuationStop) {
            outcome = "DECLINED_${stop.reason.name}"
        } finally {
            usedWork += budget.work
            usedPivots += pivots
            lastMetrics = LpSolveMetrics(pivots = pivots, workOps = budget.work)
            timer.finish(outcome, budget.work, pivots)
        }
        val metrics = lastMetrics
        observer?.observeSolve(metrics, false)
        return LpPrimaryFeasibilityAttempt(admitted,
            if (admitted) publish(evidence, token, policy, observer) else null, metrics)
    }

    private fun eligible(state: LpExactState, budget: SparseFeasibilityBudget): Boolean {
        if (state.conflict != null) return false
        for (column in 0 until state.model.numVars) {
            budget.step()
            val bounds = state.model.column(column).bounds
            if (!state.model.objective.cost(column).value.isZero || bounds.lower?.strict == true || bounds.upper?.strict == true) {
                return false
            }
        }
        for (row in 0 until state.model.m) {
            budget.step()
            if (state.model.row(row).strict) return false
        }
        return true
    }

    private fun publish(
        evidence: PrimaryFeasibilityEvidence?,
        token: Cancellation,
        policy: LpCertificationPolicy,
        observer: LpCertificationObserver?,
    ): CertifiedLpResult? {
        if (token()) {
            verified = null
            return null
        }
        observer?.observe(LpCertifier.RATIONAL, evidence != null, LpCertifierCost.Reported)
        val accepted = policy.acceptNullable(LpCertifier.RATIONAL, evidence)
        val witness = evidence?.witness?.let {
            observer?.observe(LpCertifier.EXACT_POINT, true, LpCertifierCost.Reported)
            policy.acceptNullable(LpCertifier.EXACT_POINT, it).takeIf { accepted != null }
        }
        val conflict = evidence?.conflict?.let {
            observer?.observe(LpCertifier.EXACT_FARKAS, true, LpCertifierCost.Reported)
            policy.acceptNullable(LpCertifier.EXACT_FARKAS, it).takeIf { accepted != null }
        }
        if (token()) {
            verified = null
            return null
        }
        return CertifiedLpResult(null, null, witness, null, conflict, false, { null },
            conflictSupport = evidence?.support.takeIf { conflict != null })
    }
}

private fun withinPrimaryBounds(model: ExactLpModel, column: Int, value: BigFraction, budget: SparseFeasibilityBudget): Boolean {
    budget.step()
    val bounds = model.column(column).bounds
    val lower = bounds.lower
    if (lower != null) {
        val comparison = budget.compare(value, lower.number.value)
        if (comparison < 0 || (comparison == 0 && lower.strict)) return false
    }
    val upper = bounds.upper
    if (upper != null) {
        val comparison = budget.compare(value, upper.number.value)
        if (comparison > 0 || (comparison == 0 && upper.strict)) return false
    }
    return true
}

private fun checkedPoint(state: LpExactState, point: List<BigFraction>, budget: SparseFeasibilityBudget): ExactLpWitness? {
    val model = state.model
    if (point.size != model.numVars) return null
    budget.step(model.m.toLong() + model.n, model.m.toLong() * 8L + model.n.toLong() * 16L)
    val activity = Array(model.m) { BigFraction.ZERO }
    val primal = ArrayList<BigFraction>()
    for (column in 0 until model.n) {
        val value = budget.number(point[column])
        if (!withinPrimaryBounds(model, column, value, budget)) return null
        for (entry in model.columnEntries(column)) {
            activity[entry.row] = budget.add(activity[entry.row], budget.multiply(entry.number.value, value))
        }
        primal += budget.add(value, model.column(column).origin.value)
    }
    for (row in 0 until model.m) {
        val slack = budget.subtract(model.rhs(row).value, activity[row])
        if (budget.compare(slack, point[model.n + row]) != 0 ||
            !withinPrimaryBounds(model, model.n + row, slack, budget) ||
            (model.row(row).strict && slack.isZero)
        ) return null
    }
    val objective = budget.add(
        budget.multiply(model.objective.constant.value, budget.reciprocal(model.objective.scale.value)),
        model.objective.externalConstant.value,
    )
    budget.step(primal.size.toLong(), primal.size.toLong() * 8L)
    return ExactLpWitness(primal.toList(), objective)
}

private fun checkedConflict(
    state: LpExactState,
    ray: List<BigFraction>,
    budget: SparseFeasibilityBudget,
): PrimaryFeasibilityEvidence? {
    val model = state.model
    if (ray.size != model.m) return null
    budget.step(model.m.toLong() + model.numVars, model.m.toLong() * 32L + model.numVars.toLong() * 128L)
    val rows = ArrayList<Int>()
    val multipliers = ArrayList<BigFraction>()
    var rhs = BigFraction.ZERO
    for (row in 0 until model.m) {
        val multiplier = budget.number(ray[row])
        rhs = budget.add(rhs, budget.multiply(multiplier, model.rhs(row).value))
        if (!multiplier.isZero) {
            if (!state.rows.row(row).active) return null
            rows += row
            multipliers += multiplier
        }
    }
    var minimum = BigFraction.ZERO
    val bounds = ArrayList<ExactSimplexBound>()
    val sides = ArrayList<LpExactCitedSide>()
    for (column in 0 until model.numVars) {
        var coefficient = if (column >= model.n) ray[column - model.n] else BigFraction.ZERO
        if (column < model.n) {
            for (entry in model.columnEntries(column)) {
                coefficient = budget.add(coefficient, budget.multiply(ray[entry.row], entry.number.value))
            }
        }
        if (coefficient.isZero) continue
        val upper = coefficient.signum() < 0
        val side = if (upper) model.column(column).bounds.upper else model.column(column).bounds.lower
        side ?: return null
        val assertion = state.activeSide(column, upper)?.takeIf { it.side == side } ?: return null
        minimum = budget.add(minimum, budget.multiply(coefficient, side.number.value))
        budget.step(bytes = 128L)
        bounds += ExactSimplexBound(column, upper)
        sides += LpExactCitedSide(column, upper, side, assertion.witness)
    }
    if (budget.compare(rhs, minimum) >= 0) return null
    budget.step(rows.size.toLong(), rows.size.toLong() * 48L)
    val conflict = BigRationalConflict(rows.toIntArray(), multipliers.toList(), bounds.toList())
    return PrimaryFeasibilityEvidence(conflict = conflict,
        support = LpExactSupport(state, rows.map { it to model.row(it) }, sides))
}
