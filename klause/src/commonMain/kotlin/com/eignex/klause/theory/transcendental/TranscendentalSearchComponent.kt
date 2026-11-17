package com.eignex.klause.theory.transcendental

import com.eignex.klause.ir.TranscendentalProblem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchBranchKind
import com.eignex.klause.solver.search.SearchBranchProposal
import com.eignex.klause.solver.search.SearchBrancher
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchModel
import com.eignex.klause.solver.search.SearchModelKey
import com.eignex.klause.solver.search.SearchTheoryDecision
import com.eignex.klause.solver.search.completedSearchSample
import com.eignex.klause.theory.qfnra.PolynomialCondition
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction

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
    rootUnknown: Boolean = false,
    private val finiteIntegers: Map<Int, Int> = emptyMap(),
    private val capture: ((TranscendentalWitness) -> Sample)? = null,
) : SearchBrancher, SearchBranchProposal {
    override val branchKind: SearchBranchKind get() = SearchBranchKind.Arithmetic

    val witnessKey = SearchModelKey(TranscendentalWitness::class)
    private val decisions = ArrayList<Pair<Int, TranscendentalSplit>>()
    private var outcome: TranscendentalCheckResult? = if (rootUnknown) TranscendentalCheckResult.Unknown else null
    private var signature: TranscendentalCondition? = if (rootUnknown) root else null

    override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
        if (decision is SearchDecision.Theory && decision.decision is TranscendentalSplit) {
            decisions.add(context.decisionLevel to decision.decision)
        }
        outcome = null
        signature = null
        return ComponentResult.Consistent
    }

    override fun retract(decisionLevel: Int) {
        while (decisions.lastOrNull()?.first?.let { it > decisionLevel } == true) {
            decisions.removeAt(decisions.lastIndex)
        }
        outcome = null
        signature = null
    }

    override fun initialize(context: SearchContext): ComponentResult = propagate(context)

    override fun propagate(context: SearchContext): ComponentResult = when (evaluate(context)) {
        TranscendentalCheckResult.Refuted -> ComponentResult.Conflict()
        else -> ComponentResult.Consistent
    }

    override fun check(context: SearchContext): ComponentCheck = when (evaluate(context)) {
        is TranscendentalCheckResult.Witness -> if ((0 until source.boolCount).all { context.boolValue(it) != null } &&
            finiteIntegers.keys.all { context.intLowerBound(it) != null &&
                context.intLowerBound(it) == context.intUpperBound(it) }) {
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
            it !in finiteIntegers.values &&
                (condition.rational.lower[it] == null || condition.rational.lower[it] != condition.rational.upper[it])
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
        val witness = (requireNotNull(outcome) as TranscendentalCheckResult.Witness).value
        model.contribute(witnessKey, witness)
        capture?.let { model.contribute(completedSearchSample, it(witness)) }
    }

    private fun evaluate(context: SearchContext): TranscendentalCheckResult {
        if (context.cancelled()) return TranscendentalCheckResult.Unknown
        val current = condition(context)
        if (signature?.let { it.rational == current.rational && it.lowerStrict == current.lowerStrict &&
                it.upperStrict == current.upperStrict } == true) outcome?.let { return it }
        if (root.rational.bools.any { (id, value) -> context.boolValue(id)?.let { it != value } == true }) {
            return TranscendentalCheckResult.Refuted.also { outcome = it }
        }
        if (!context.consumeCheck() || context.cancelled()) return TranscendentalCheckResult.Unknown
        return service.check(current, ExecutionControl { context.cancelled() }).also {
            outcome = it
            signature = current
        }
    }

    private fun condition(context: SearchContext): TranscendentalCondition {
        val lower = root.rational.lower.toMutableMap()
        val upper = root.rational.upper.toMutableMap()
        val strictLower = root.lowerStrict.toMutableSet()
        val strictUpper = root.upperStrict.toMutableSet()
        for ((id, coordinate) in finiteIntegers) {
            context.intLowerBound(id)?.let { value ->
                val bound = BigFraction.ofLong(value)
                if (lower[coordinate]?.let { bound > it } != false) {
                    lower[coordinate] = bound
                    strictLower.remove(coordinate)
                }
            }
            context.intUpperBound(id)?.let { value ->
                val bound = BigFraction.ofLong(value)
                if (upper[coordinate]?.let { bound < it } != false) {
                    upper[coordinate] = bound
                    strictUpper.remove(coordinate)
                }
            }
        }
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
