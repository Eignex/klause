package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Lit
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.optimization.LinearApproach
import com.eignex.klause.lp.optimization.LinearAuthority
import com.eignex.klause.lp.optimization.LinearAuthorityResult
import com.eignex.klause.lp.optimization.atScope
import com.eignex.klause.lp.optimization.withLinearAuthority
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchBrancher
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchNodeDisposition
import com.eignex.klause.solver.search.SearchNodePolicy
import com.eignex.klause.solver.search.SearchTheoryDecision
import com.eignex.klause.util.Cancellation

internal class LinearAuthorityTraversal(
    private val source: SourceProofContext,
    budget: SourceLpBudget,
    private val cancellation: Cancellation,
    private val pause: () -> Boolean,
    private val onIncumbent: (Sample) -> Unit,
) : SearchBrancher, SearchNodePolicy {
    private val authority = LinearAuthority(budget)
    private val root = CoverageNode(source.scope.condition)
    private val path = arrayListOf(0 to root)
    private val current: CoverageNode get() = path.last().second
    var ray: SourceEvidence? = null
        private set
    var rootBound: SourceEvidence? = null
        private set

    override fun initialize(context: SearchContext): ComponentResult {
        for ((id, value) in source.scope.condition.bools) {
            val result = context.publish(Lit.make(id, value))
            if (result !is ComponentResult.Consistent) return result
        }
        return ComponentResult.Consistent
    }

    override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
        val child = when (decision) {
            is SearchDecision.Bool -> {
                val id = Lit.variable(decision.literal)
                val split = current.split ?: CoverageSplit.Boolean(id, current.condition).also { current.split = it }
                check(split is CoverageSplit.Boolean && split.id == id)
                if (Lit.isPositive(decision.literal)) split.above else split.below
            }
            is SearchDecision.Theory -> {
                val bound = decision.decision as? IntegerAlternative ?: return ComponentResult.Indeterminate
                val split = current.split as? CoverageSplit.Integer ?: return ComponentResult.Indeterminate
                check(split.coordinate == bound.coordinate && split.threshold == bound.threshold)
                if (bound.upper) split.below else split.above
            }
            else -> return ComponentResult.Indeterminate
        }
        path += context.decisionLevel to child
        return ComponentResult.Consistent
    }

    override fun retract(decisionLevel: Int) {
        while (path.last().first > decisionLevel) path.removeAt(path.lastIndex)
    }

    override fun beforeBranch(context: SearchContext): SearchNodeDisposition {
        if (cancellation()) return SearchNodeDisposition.Indeterminate
        if (pause()) return SearchNodeDisposition.Pause
        if (source.coordinates.any {
                it.kind == SourceCoordinate.Kind.Boolean && context.boolValue(it.id) == null
            }
        ) return SearchNodeDisposition.Expand
        current.result?.let { return if (it.evidence != null) SearchNodeDisposition.Prune
            else if (it.split != null) SearchNodeDisposition.Expand else SearchNodeDisposition.Indeterminate }
        if (!context.consumeCheck()) return SearchNodeDisposition.Indeterminate
        val branch = source.atScope(source.scope.copy(condition = current.condition))
        val result = authority.check(branch, cancellation)
        current.result = result
        result.incumbent?.let(onIncumbent)
        if (current === root) rootBound = result.bound
        if (result.evidence?.claim is SourceClaim.Ray) {
            ray = checked(result.evidence.claim, SourceDerivation.SourceRay)
            return SearchNodeDisposition.Indeterminate
        }
        result.split?.let { (coordinate, threshold) ->
            current.split = CoverageSplit.Integer(coordinate, threshold, current.condition)
            return SearchNodeDisposition.Expand
        }
        return if (result.evidence != null) SearchNodeDisposition.Prune else SearchNodeDisposition.Indeterminate
    }

    override fun nextBranch(context: SearchContext): List<SearchDecision>? {
        val split = current.split as? CoverageSplit.Integer ?: return null
        return listOf(true, false).map {
            SearchDecision.Theory(IntegerAlternative(split.coordinate, split.threshold, it))
        }
    }

    fun completed(): SourceEvidence? {
        ray?.let { return it }
        val leaves = root.completedLeaves() ?: return null
        val feasible = leaves.mapNotNull { it.evidence }.filter { it.claim != SourceClaim.Refutation }
        if (feasible.isEmpty()) return checked(SourceClaim.Refutation, root.proof(strict = false) ?: return null)
        fun value(evidence: SourceEvidence): BigFraction = when (val claim = evidence.claim) {
            is SourceClaim.Optimal -> (claim.value as SourceValue.Rational).value
            is SourceClaim.Limit -> (claim.value as SourceValue.Rational).value
            else -> error("a covered linear branch must have an optimum or limit")
        }
        val minimizes = source.scope.objective?.sense == com.eignex.klause.ir.ObjectiveSense.MINIMIZE
        val best = feasible.reduce { left, right ->
            if (if (minimizes) value(left) <= value(right) else value(left) >= value(right)) left else right
        }
        val bound = root.proof(strict = false) ?: return null
        checked(SourceClaim.Bound(value(best)), bound)?.let { rootBound = it }
        val optimum = feasible.firstOrNull { it.claim is SourceClaim.Optimal && value(it) == value(best) }
        return if (optimum != null) {
            val claim = optimum.claim as SourceClaim.Optimal
            checked(claim, SourceDerivation.Optimum(SourceDerivation.SourceWitness, bound))
        } else {
            val claim = best.claim as SourceClaim.Limit
            val approach = best.derivation as LinearApproach
            checked(claim, LinearApproach(approach.endpoint, root.proof(strict = true) ?: return null))
        }
    }

    private fun checked(claim: SourceClaim, proof: SourceDerivation): SourceEvidence? {
        val evidence = SourceEvidence(source.scope, claim, proof)
        return evidence.takeIf {
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(it, cancellation) == SourceEvidenceCheck.Checked
        }
    }
}

private data class IntegerAlternative(
    val coordinate: SourceCoordinate, val threshold: BigFraction, val upper: Boolean,
) : SearchTheoryDecision

private class CoverageNode(val condition: SourceCondition) {
    var split: CoverageSplit? = null
    var result: LinearAuthorityResult? = null

    fun completedLeaves(): List<LinearAuthorityResult>? {
        val children = split
        if (children != null) {
            val below = children.below.completedLeaves() ?: return null
            val above = children.above.completedLeaves() ?: return null
            return below + above
        }
        return result?.takeIf { it.evidence != null }?.let(::listOf)
    }

    fun proof(strict: Boolean): SourceDerivation? {
        val children = split
        if (children != null) {
            val below = children.below.proof(strict) ?: return null
            val above = children.above.proof(strict) ?: return null
            return when (children) {
                is CoverageSplit.Boolean -> SourceDerivation.BooleanSplit(children.id, below, above)
                is CoverageSplit.Integer -> SourceDerivation.IntegerSplit(children.coordinate,
                    children.threshold, below, above)
            }
        }
        val evidence = result?.evidence ?: return null
        return when (val claim = evidence.claim) {
            SourceClaim.Refutation -> evidence.derivation
            is SourceClaim.Optimal -> (evidence.derivation as SourceDerivation.Optimum).bound
            is SourceClaim.Limit -> if (strict) (evidence.derivation as LinearApproach).strictBound
                else result?.bound?.derivation
            else -> null
        }
    }
}

private sealed class CoverageSplit(below: SourceCondition, above: SourceCondition) {
    val below = CoverageNode(below)
    val above = CoverageNode(above)
    class Boolean(val id: Int, condition: SourceCondition) : CoverageSplit(
        condition.pin(id, false), condition.pin(id, true))
    class Integer(val coordinate: SourceCoordinate, val threshold: BigFraction, condition: SourceCondition) :
        CoverageSplit(condition.bound(coordinate, threshold, true),
            condition.bound(coordinate, threshold + BigFraction.ONE, false))
}
