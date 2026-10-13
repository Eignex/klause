package com.eignex.klause.theory.qfnia

import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.QuadraticEngineDerivation
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.RegisteredTheoryDecision
import com.eignex.klause.solver.search.SearchBranchKind
import com.eignex.klause.solver.search.SearchBranchProposal
import com.eignex.klause.solver.search.SearchBrancher
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchIntegerBound
import com.eignex.klause.solver.search.SearchModel
import com.eignex.klause.solver.search.SearchModelKey
import com.eignex.klause.solver.search.SearchNodeDisposition
import com.eignex.klause.solver.search.SearchNodePolicy
import com.eignex.klause.solver.search.SearchTheoryDecision
import com.eignex.klause.solver.search.TheoryComponent
import com.eignex.klause.solver.search.contribute
import com.eignex.klause.theory.qfnra.PolynomialCondition
import com.eignex.klause.theory.qfnra.QfNraAssignment
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.plus

internal sealed interface QuadraticIntegerNode {
    data class Closed(
        val assignment: QfNraAssignment,
        val value: AlgebraicReal,
        val attained: Boolean,
        val proof: SourceDerivation,
    ) : QuadraticIntegerNode
    data class Empty(val proof: SourceDerivation) : QuadraticIntegerNode
    data class Pruned(val proof: SourceDerivation) : QuadraticIntegerNode
    data class Split(val coordinate: Int, val threshold: BigInt) : QuadraticIntegerNode
    data class Unbounded(val assignment: QfNraAssignment, val proof: SourceDerivation) : QuadraticIntegerNode
    data object BranchBooleans : QuadraticIntegerNode
    data object Unknown : QuadraticIntegerNode
}

private data class QuadraticIntegerBound(
    val owner: Any,
    val coordinate: Int,
    val threshold: BigInt,
    val upper: Boolean,
) : SearchTheoryDecision {
    fun complement() = if (upper) copy(threshold = threshold + BIG_ONE, upper = false)
        else error("integer split registration starts with an upper bound")
}

internal class QuadraticIntegerComponent(
    private val source: NonlinearIntegerProblem,
    private val root: PolynomialCondition,
    private val step: () -> Unit,
    private val complete: (PolynomialCondition, SearchContext) -> QuadraticIntegerNode,
) : TheoryComponent, SearchBrancher, SearchBranchProposal, SearchNodePolicy {
    override val branchKind: SearchBranchKind get() = SearchBranchKind.Arithmetic

    private val owner = Any()
    private val assertions = ArrayList<Pair<Int, QuadraticIntegerBound>>()
    private var current: QuadraticIntegerNode? = null
    val modelKey = SearchModelKey(QuadraticIntegerNode.Closed::class)
    val outcome: QuadraticIntegerNode? get() = current

    override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
        current = null
        if (decision is SearchDecision.Theory) {
            val registered = decision.decision as? RegisteredTheoryDecision
            val payload = registered?.payload ?: decision.decision
            val bound = payload as? QuadraticIntegerBound
            if (bound != null && bound.owner === owner) assertions += context.decisionLevel to bound
            val integer = payload as? SearchIntegerBound
            if (integer != null) addBound(integer.variable, integer.exactThreshold(), integer.upper, context)
        } else when (decision) {
            is SearchDecision.IntAtMost -> addBound(decision.variable, bigIntOf(decision.upper), true, context)
            is SearchDecision.IntAtLeast -> addBound(decision.variable, bigIntOf(decision.lower), false, context)
            is SearchDecision.IntEqual -> {
                addBound(decision.variable, bigIntOf(decision.value), true, context)
                addBound(decision.variable, bigIntOf(decision.value), false, context)
            }
            else -> Unit
        }
        return ComponentResult.Consistent
    }

    private fun addBound(id: Int, threshold: BigInt, upper: Boolean, context: SearchContext) {
        require(id in source.integerCoordinates.indices) { "quadratic integer decision id out of range" }
        assertions += context.decisionLevel to QuadraticIntegerBound(
            owner, source.integerCoordinates[id], threshold, upper,
        )
    }

    override fun retract(decisionLevel: Int) {
        while (assertions.lastOrNull()?.first?.let { it > decisionLevel } == true) {
            assertions.removeAt(assertions.lastIndex)
        }
        current = null
    }

    override fun propagate(context: SearchContext): ComponentResult {
        if (context.cancelled()) return ComponentResult.Indeterminate
        val node = current ?: run {
            val lower = root.lower.toMutableMap()
            val upper = root.upper.toMutableMap()
            for ((_, bound) in assertions) {
                step()
                val value = BigFraction.of(bound.threshold, BIG_ONE)
                val values = if (bound.upper) upper else lower
                val old = values[bound.coordinate]
                if (old == null || if (bound.upper) value < old else value > old) values[bound.coordinate] = value
            }
            val bools = root.bools.toMutableMap()
            var boolConflict = false
            for (id in 0 until source.boolCount) {
                step()
                context.boolValue(id)?.let { value ->
                    if (root.bools[id]?.let { it != value } == true) boolConflict = true
                    bools[id] = value
                }
            }
            val condition = PolynomialCondition(bools, lower, upper)
            val contradictory = lower.any { (id, value) ->
                step()
                upper[id]?.let { value > it } == true
            }
            (if (contradictory || boolConflict) QuadraticIntegerNode.Empty(QuadraticEngineDerivation.Both(
                QuadraticEngineDerivation.Condition(root), QuadraticEngineDerivation.Condition(condition),
            ))
            else complete(condition, context)).also {
                current = it
            }
        }
        return when (node) {
            is QuadraticIntegerNode.Empty -> ComponentResult.Conflict()
            QuadraticIntegerNode.Unknown -> ComponentResult.Indeterminate
            else -> ComponentResult.Consistent
        }
    }

    override fun nextBranch(context: SearchContext): List<SearchDecision>? {
        val split = current as? QuadraticIntegerNode.Split ?: return null
        require(split.coordinate in source.integerCoordinates) { "quadratic split requires a source integer" }
        val positive = QuadraticIntegerBound(owner, split.coordinate, split.threshold, upper = true)
        val negative = positive.complement()
        return context.registerAtom(positive, negative)?.alternatives()
            ?: listOf(SearchDecision.Theory(positive), SearchDecision.Theory(negative))
    }

    override fun beforeBranch(context: SearchContext): SearchNodeDisposition = when (current) {
        is QuadraticIntegerNode.Pruned -> SearchNodeDisposition.Prune
        is QuadraticIntegerNode.Unbounded -> SearchNodeDisposition.Pause
        QuadraticIntegerNode.Unknown, null -> SearchNodeDisposition.Indeterminate
        else -> SearchNodeDisposition.Expand
    }

    override fun check(context: SearchContext): ComponentCheck = when (current) {
        is QuadraticIntegerNode.Closed -> ComponentCheck.Feasible
        is QuadraticIntegerNode.Empty -> ComponentCheck.Infeasible()
        else -> ComponentCheck.Indeterminate
    }

    override fun contributeModel(model: SearchModel, context: SearchContext) {
        (current as? QuadraticIntegerNode.Closed)?.let { model.contribute(modelKey, it) }
    }
}
