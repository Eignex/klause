package com.eignex.klause.theory.transcendental

import com.eignex.klause.ir.TranscendentalProblem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchBrancher
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchModel
import com.eignex.klause.solver.search.SearchModelKey
import com.eignex.klause.solver.search.SearchTheoryDecision
import com.eignex.klause.solver.search.contribute
import com.eignex.klause.theory.qfnra.PolynomialCondition

internal data class TranscendentalSplit(
    val coordinate: Int,
    val boundary: BigFraction,
    val atMost: Boolean,
) : SearchTheoryDecision

internal class TranscendentalSearchComponent(
    private val source: TranscendentalProblem,
    private val service: TranscendentalTheoryService,
    private val root: TranscendentalCondition = TranscendentalCondition(),
    private val maximumDepth: Int = 12,
) : SearchBrancher {
    val witnessKey = SearchModelKey(TranscendentalWitness::class)
    private val decisions = ArrayList<Pair<Int, TranscendentalSplit>>()
    private var outcome: TranscendentalCheckResult? = null

    override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
        if (decision is SearchDecision.Theory && decision.decision is TranscendentalSplit) {
            decisions.add(context.decisionLevel to decision.decision)
        }
        outcome = null
        return ComponentResult.Consistent
    }

    override fun retract(decisionLevel: Int) {
        while (decisions.lastOrNull()?.first?.let { it > decisionLevel } == true) {
            decisions.removeAt(decisions.lastIndex)
        }
        outcome = null
    }

    override fun initialize(context: SearchContext): ComponentResult = propagate(context)

    override fun propagate(context: SearchContext): ComponentResult = when (evaluate(context)) {
        TranscendentalCheckResult.Refuted -> ComponentResult.Conflict()
        else -> ComponentResult.Consistent
    }

    override fun check(context: SearchContext): ComponentCheck = when (evaluate(context)) {
        is TranscendentalCheckResult.Witness -> if ((0 until source.boolCount).all { context.boolValue(it) != null }) {
            ComponentCheck.Feasible
        } else ComponentCheck.Indeterminate
        TranscendentalCheckResult.Refuted -> ComponentCheck.Infeasible()
        else -> ComponentCheck.Indeterminate
    }

    override fun nextBranch(context: SearchContext): List<SearchDecision>? {
        if (evaluate(context) is TranscendentalCheckResult.Witness || decisions.size >= maximumDepth ||
            source.numericCount == 0 || service.exhausted || context.cancelled()
        ) return null
        val condition = condition(context)
        val candidates = (0 until source.numericCount).filter {
            condition.rational.lower[it] == null || condition.rational.lower[it] != condition.rational.upper[it]
        }
        if (candidates.isEmpty()) return null
        val coordinate = candidates[decisions.size % candidates.size]
        val lower = condition.rational.lower[coordinate]
        val upper = condition.rational.upper[coordinate]
        val middle = when {
            lower != null && upper != null -> (lower + upper) * BigFraction.ofLong(2).reciprocal()
            lower != null -> lower + BigFraction.ONE
            upper != null -> upper - BigFraction.ONE
            else -> BigFraction.ZERO
        }
        return listOf(
            SearchDecision.Theory(TranscendentalSplit(coordinate, middle, true)),
            SearchDecision.Theory(TranscendentalSplit(coordinate, middle, false)),
        )
    }

    override fun contributeModel(model: SearchModel, context: SearchContext) {
        model.contribute(witnessKey, (requireNotNull(outcome) as TranscendentalCheckResult.Witness).value)
    }

    private fun evaluate(context: SearchContext): TranscendentalCheckResult {
        outcome?.let { return it }
        if (root.rational.bools.any { (id, value) -> context.boolValue(id)?.let { it != value } == true }) {
            return TranscendentalCheckResult.Refuted.also { outcome = it }
        }
        if (!context.consumeCheck() || context.cancelled()) return TranscendentalCheckResult.Unknown
        return service.check(condition(context)).also { outcome = it }
    }

    private fun condition(context: SearchContext): TranscendentalCondition {
        val lower = root.rational.lower.toMutableMap()
        val upper = root.rational.upper.toMutableMap()
        val strictLower = root.lowerStrict.toMutableSet()
        val strictUpper = root.upperStrict.toMutableSet()
        for ((_, split) in decisions) {
            if (split.atMost) {
                val old = upper[split.coordinate]
                if (old == null || split.boundary < old) {
                    upper[split.coordinate] = split.boundary
                    strictUpper.remove(split.coordinate)
                }
            } else {
                val old = lower[split.coordinate]
                if (old == null || split.boundary >= old) {
                    lower[split.coordinate] = split.boundary
                    strictLower.add(split.coordinate)
                }
            }
        }
        val bools = (0 until source.boolCount).mapNotNull { id -> context.boolValue(id)?.let { id to it } }.toMap()
        return TranscendentalCondition(
            PolynomialCondition(root.rational.bools + bools, lower, upper), strictLower, strictUpper,
        )
    }
}
