package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Lit
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.optimization.LinearApproach
import com.eignex.klause.lp.optimization.LinearAuthority
import com.eignex.klause.lp.optimization.LinearAuthorityResult
import com.eignex.klause.lp.optimization.atScope
import com.eignex.klause.lp.optimization.floorSplit
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
import com.eignex.klause.solver.evidence.integerPinBounds
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchBranchKind
import com.eignex.klause.solver.search.SearchBranchProposal
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
    private val incumbent: () -> Pair<Sample, BigFraction>?,
    private val useBranchHistory: Boolean = false,
    borrowedAuthority: LinearAuthority? = null,
    private val useGuidance: Boolean = false,
    private val inheritBounds: Boolean = false,
) : SearchBrancher, SearchBranchProposal, SearchNodePolicy, AutoCloseable {
    override val branchKind: SearchBranchKind get() = SearchBranchKind.Arithmetic

    private val ownsAuthority = borrowedAuthority == null
    private val authority = borrowedAuthority ?: LinearAuthority(budget, { shape ->
        observations["numericalLpInputMaxRows"] =
            maxOf(observations["numericalLpInputMaxRows"] ?: 0L, shape.inputRows.toLong())
        observations["numericalLpMaxRows"] = maxOf(observations["numericalLpMaxRows"] ?: 0L, shape.rows.toLong())
        observations["numericalLpMaxColumns"] =
            maxOf(observations["numericalLpMaxColumns"] ?: 0L, shape.columns.toLong())
        observations["numericalLpExtractedBoundRows"] =
            (observations["numericalLpExtractedBoundRows"] ?: 0L) + shape.inputRows - shape.rows
        if (shape.sparse) {
            shape.phase?.let { observe("numericalSparseLpPhase$it") }
            if (!shape.admitted) observe("numericalSparseLpDeclines")
        } else if (!shape.admitted) observe("numericalLpShapesOutsideDimensionGuard")
    }, retainLp = true)

    override fun close() {
        if (ownsAuthority) authority.close()
    }
    private val root = CoverageNode(source.scope.condition)
    private val branchHistory = LinearBranchHistory()
    private val path = arrayListOf(0 to root)
    private val current: CoverageNode get() = path.last().second
    private var queriedNode: CoverageNode? = null
    private var queriedSource: SourceProofContext? = null
    private val proofSource by lazy { source.withLinearAuthority() }
    private val observations = linkedMapOf(
        "numericalIncumbentDominatedSplits" to 0L,
        "numericalIncumbentBoundClosures" to 0L,
    )
    private var pending = 1L
    private var peakPending = 1L
    private var retained = 1L

    fun observations(): Map<String, Long> = observations + authority.cutObservations() + mapOf(
        "numericalPendingLeaves" to pending,
        "numericalPeakPendingLeaves" to peakPending,
        "numericalRetainedNodes" to retained,
        "numericalLpPreparations" to authority.preparations,
        "numericalLpReuses" to authority.reuses,
    )

    private fun observe(stage: String) {
        observations[stage] = (observations[stage] ?: 0L) + 1L
    }

    private fun splitCreated() {
        pending++
        retained += 2
        peakPending = maxOf(peakPending, pending)
    }
    var ray: SourceEvidence? = null
        private set
    var rootBound: SourceEvidence? = null
        private set

    fun frontierRank(): BigFraction? {
        val minimizes = source.scope.objective?.sense == com.eignex.klause.ir.ObjectiveSense.MINIMIZE
        return path.mapNotNull { (_, node) ->
            val claim = (node.coveredBound ?: node.result?.bound)?.claim as? SourceClaim.Bound
            val value = (claim?.value as? SourceValue.Rational)?.value
            if (minimizes) value else value?.negated()
        }.reduceOrNull { left, right -> if (left >= right) left else right }
    }

    fun updateGlobalBound() {
        val minimizes = source.scope.objective?.sense == com.eignex.klause.ir.ObjectiveSense.MINIMIZE
        val coverage = root.boundCoverage(null, minimizes, cancellation) ?: return
        val value = coverage.value ?: return
        val checkedValue = ((rootBound?.claim as? SourceClaim.Bound)?.value as? SourceValue.Rational)?.value
        if (checkedValue != null && if (minimizes) value <= checkedValue else value >= checkedValue) return
        checked(SourceClaim.Bound(value), coverage.proof)?.let { rootBound = it }
    }

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
                val split = current.split ?: CoverageSplit.Boolean(id, current.condition).also {
                    current.split = it
                    splitCreated()
                }
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
        if (inheritBounds && current.result == null && current.split == null && closeInheritedBound()) {
            return SearchNodeDisposition.Prune
        }
        current.result?.let {
            if (it.evidence == null && current.coveredBound == null && current.split == null) {
                val bound = it.bound
                val value = ((bound?.claim as? SourceClaim.Bound)?.value as? SourceValue.Rational)?.value
                val standing = incumbent()?.second
                val minimizes = source.scope.objective?.sense == com.eignex.klause.ir.ObjectiveSense.MINIMIZE
                if (value != null && standing != null && if (minimizes) value >= standing else value <= standing) {
                    current.coveredBound = bound
                    pending--
                    observe("numericalIncumbentBoundClosures")
                    observe("numericalClosedLeaves")
                }
            }
            return when {
                it.evidence != null || current.coveredBound != null -> SearchNodeDisposition.Prune
                current.split != null -> SearchNodeDisposition.Expand
                else -> SearchNodeDisposition.Indeterminate
            }
        }
        if (!current.pending && !context.consumeCheck()) return SearchNodeDisposition.Indeterminate
        val branch = if (queriedNode === current) checkNotNull(queriedSource) else
            source.atScope(source.scope.copy(condition = current.condition)).also {
                queriedNode = current
                queriedSource = it
            }
        val result = if (useGuidance) authority.advance(branch, cancellation, cuts = true)
            else authority.check(branch, cancellation)
        if (result.paused) {
            current.pending = true
            observe("numericalLpPauses")
            return SearchNodeDisposition.Pause
        }
        current.pending = false
        current.result = result
        observe("numericalChecks")
        if (result.coordinateConflict) observe("numericalCoordinateConflicts")
        if (result.lpConflict) observe("numericalLpConflicts")
        if (result.bound != null) {
            observe("numericalCertifiedBounds")
            val parent = path.getOrNull(path.lastIndex - 1)?.second?.result?.bound?.claim as? SourceClaim.Bound
            val bound = result.bound.claim as SourceClaim.Bound
            val after = (bound.value as SourceValue.Rational).value
            val minimizes = source.scope.objective?.sense == com.eignex.klause.ir.ObjectiveSense.MINIMIZE
            val standing = incumbent()?.second
            if (result.evidence == null && standing != null &&
                if (minimizes) after >= standing else after <= standing
            ) {
                if (result.split != null) observe("numericalIncumbentDominatedSplits")
                current.coveredBound = result.bound
                observe("numericalIncumbentBoundClosures")
            }
            if (parent != null) {
                val before = (parent.value as SourceValue.Rational).value
                if (if (minimizes) after > before else after < before) observe("numericalChildBoundGains")
                val parentNode = path.getOrNull(path.lastIndex - 1)?.second
                val split = parentNode?.split as? CoverageSplit.Integer
                split?.fractional?.takeIf { useBranchHistory }?.let { candidate ->
                    branchHistory.observe(candidate, current === split.below,
                        if (minimizes) after - before else before - after)
                }
            }
        }
        if (result.evidence != null || current.coveredBound != null) {
            observe("numericalClosedLeaves")
            pending--
        } else if (result.split == null) {
            observe("numericalUnavailableGuidance")
        }
        result.incumbent?.let(onIncumbent)
        if (current === root) rootBound = result.bound
        if (result.evidence?.claim is SourceClaim.Ray) {
            ray = checked(result.evidence.claim, SourceDerivation.SourceRay)
            return SearchNodeDisposition.Indeterminate
        }
        if (current.coveredBound != null) return SearchNodeDisposition.Prune
        result.split?.let { (coordinate, threshold) ->
            val candidate = if (useBranchHistory) branchHistory.select(result.fractional, cancellation)
                else result.fractional.firstOrNull()
            val selected = candidate?.floorSplit() ?: (coordinate to threshold)
            if (selected != result.split) observe("numericalHistorySelections")
            current.split = CoverageSplit.Integer(selected.first, selected.second, current.condition, candidate)
            splitCreated()
            observe("numericalFractionalSplits")
            return SearchNodeDisposition.Expand
        }
        if (result.evidence != null) return SearchNodeDisposition.Prune
        val guarded = branch.premises.values.filter { it.integerPins.isNotEmpty() }
        if (guarded.isEmpty()) return SearchNodeDisposition.Indeterminate
        val integerBounds = branch.integerPinBounds(current.condition, cancellation)
            ?: return SearchNodeDisposition.Indeterminate
        for (premise in guarded) {
            if (cancellation()) return SearchNodeDisposition.Indeterminate
            if (premise.guards.any { (id, value) -> current.condition.bools[id] != value }) continue
            integerBounds.split(premise.integerPins)?.let { (coordinate, threshold) ->
                current.split = CoverageSplit.Integer(coordinate, threshold, current.condition)
                splitCreated()
                observe("numericalIndicatorSplits")
                return SearchNodeDisposition.Expand
            }
        }
        return SearchNodeDisposition.Indeterminate
    }

    override fun nextBranch(context: SearchContext): List<SearchDecision>? {
        val split = current.split as? CoverageSplit.Integer ?: return null
        return listOf(true, false).map {
            SearchDecision.Theory(IntegerAlternative(split.coordinate, split.threshold, it))
        }
    }

    private fun closeInheritedBound(): Boolean {
        val standing = incumbent()?.second ?: return false
        val minimizes = source.scope.objective?.sense == com.eignex.klause.ir.ObjectiveSense.MINIMIZE
        for ((_, ancestor) in path.dropLast(1).asReversed()) {
            if (cancellation()) return false
            val inherited = ancestor.coveredBound ?: ancestor.result?.bound ?: continue
            val claim = inherited.claim as? SourceClaim.Bound ?: continue
            val value = (claim.value as? SourceValue.Rational)?.value ?: continue
            if (if (minimizes) value < standing else value > standing) continue
            val branch = proofSource.atScope(source.scope.copy(condition = current.condition))
            val evidence = SourceEvidence(branch.scope, claim, inherited.derivation)
            if (SourceEvidenceVerifier(branch).verify(evidence, cancellation) != SourceEvidenceCheck.Checked) continue
            authority.invalidateGuidance()
            current.pending = false
            current.result = LinearAuthorityResult(bound = evidence)
            current.coveredBound = evidence
            pending--
            observe("numericalInheritedBoundClosures")
            observe("numericalClosedLeaves")
            return true
        }
        return false
    }

    fun completed(): SourceEvidence? {
        ray?.let { return it }
        val leaves = root.completedLeaves() ?: return null
        val bound = root.proof(strict = false) ?: return null
        incumbent()?.let { (sample, value) ->
            val optimum = checked(SourceClaim.Optimal(sample, SourceValue.Rational(value)),
                SourceDerivation.Optimum(SourceDerivation.SourceWitness, bound))
            if (optimum != null) {
                rootBound = checked(SourceClaim.Bound(value), bound)
                return optimum
            }
        }
        val feasible = leaves.mapNotNull { it.evidence }.filter { it.claim != SourceClaim.Refutation }
        if (feasible.isEmpty()) return if (leaves.all { it.evidence?.claim == SourceClaim.Refutation }) {
            checked(SourceClaim.Refutation, bound)
        } else null
        fun value(evidence: SourceEvidence): BigFraction = when (val claim = evidence.claim) {
            is SourceClaim.Optimal -> (claim.value as SourceValue.Rational).value
            is SourceClaim.Limit -> (claim.value as SourceValue.Rational).value
            else -> error("a covered linear branch must have an optimum or limit")
        }
        val minimizes = source.scope.objective?.sense == com.eignex.klause.ir.ObjectiveSense.MINIMIZE
        val best = feasible.reduce { left, right ->
            if (if (minimizes) value(left) <= value(right) else value(left) >= value(right)) left else right
        }
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
            SourceEvidenceVerifier(proofSource).verify(it, cancellation) == SourceEvidenceCheck.Checked
        }
    }
}

private data class IntegerAlternative(
    val coordinate: SourceCoordinate, val threshold: BigFraction, val upper: Boolean,
) : SearchTheoryDecision

private class CoverageNode(val condition: SourceCondition) {
    var split: CoverageSplit? = null
    var result: LinearAuthorityResult? = null
    var coveredBound: SourceEvidence? = null
    var pending: Boolean = false

    fun boundCoverage(inherited: SourceEvidence?, minimizes: Boolean, cancellation: Cancellation): BoundCoverage? {
        if (cancellation()) return null
        fun value(evidence: SourceEvidence): BigFraction =
            ((evidence.claim as SourceClaim.Bound).value as SourceValue.Rational).value
        val bound = listOfNotNull(inherited, coveredBound, result?.bound).reduceOrNull { left, right ->
            if (if (minimizes) value(left) >= value(right) else value(left) <= value(right)) left else right
        }
        val children = split
        if (children != null) {
            val below = children.below.boundCoverage(bound, minimizes, cancellation) ?: return null
            val above = children.above.boundCoverage(bound, minimizes, cancellation) ?: return null
            val selected = listOfNotNull(below.value, above.value).reduceOrNull { left, right ->
                if (if (minimizes) left <= right else left >= right) left else right
            }
            val proof = when (children) {
                is CoverageSplit.Boolean -> SourceDerivation.BooleanSplit(children.id, below.proof, above.proof)
                is CoverageSplit.Integer -> SourceDerivation.IntegerSplit(children.coordinate,
                    children.threshold, below.proof, above.proof)
            }
            return BoundCoverage(selected, proof)
        }
        val terminal = result?.evidence
        if (terminal?.claim == SourceClaim.Refutation) return BoundCoverage(null, terminal.derivation)
        return bound?.let { BoundCoverage(value(it), it.derivation) }
    }

    fun completedLeaves(): List<LinearAuthorityResult>? {
        val children = split
        if (children != null) {
            val below = children.below.completedLeaves() ?: return null
            val above = children.above.completedLeaves() ?: return null
            return below + above
        }
        return result?.takeIf { it.evidence != null || coveredBound != null }?.let(::listOf)
    }

    fun proof(strict: Boolean): SourceDerivation? {
        coveredBound?.let { return it.derivation }
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

private class BoundCoverage(val value: BigFraction?, val proof: SourceDerivation)

private sealed class CoverageSplit(below: SourceCondition, above: SourceCondition) {
    val below = CoverageNode(below)
    val above = CoverageNode(above)
    class Boolean(val id: Int, condition: SourceCondition) : CoverageSplit(
        condition.pin(id, false), condition.pin(id, true))
    class Integer(val coordinate: SourceCoordinate, val threshold: BigFraction, condition: SourceCondition,
        val fractional: Pair<SourceCoordinate, BigFraction>? = null) :
        CoverageSplit(condition.bound(coordinate, threshold, true),
            condition.bound(coordinate, threshold + BigFraction.ONE, false))
}
