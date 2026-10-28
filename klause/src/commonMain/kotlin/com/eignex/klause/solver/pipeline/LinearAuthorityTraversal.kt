package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Lit
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.optimization.LinearActiveRows
import com.eignex.klause.lp.optimization.LinearApproach
import com.eignex.klause.lp.optimization.LinearAuthority
import com.eignex.klause.lp.optimization.LinearAuthorityCapture
import com.eignex.klause.lp.optimization.LinearAuthorityFeedbackRequest
import com.eignex.klause.lp.optimization.LinearAuthorityResult
import com.eignex.klause.lp.optimization.atScope
import com.eignex.klause.lp.optimization.floorSplit
import com.eignex.klause.lp.optimization.strongerLinearBound
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
import com.eignex.klause.solver.evidence.SourceIntegerAggregateSplit
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.adjacentIntegerAbove
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.evidence.integerPinBounds
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.RegisteredTheoryDecision
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchBranchKind
import com.eignex.klause.solver.search.SearchBranchProposal
import com.eignex.klause.solver.search.SearchBrancher
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchExplanation
import com.eignex.klause.solver.search.SearchIntValue
import com.eignex.klause.solver.search.SearchNodeDisposition
import com.eignex.klause.solver.search.SearchNodePolicy
import com.eignex.klause.solver.search.SearchTheoryAtom
import com.eignex.klause.solver.search.SearchTheoryDecision
import com.eignex.klause.solver.search.SourceBoundAtom
import com.eignex.klause.solver.search.SourceBoundPredicate
import com.eignex.klause.solver.search.SourceBoundTerm
import com.eignex.klause.solver.search.explainAtoms
import com.eignex.klause.util.Cancellation

internal class LinearAuthorityTraversal(
    private val source: SourceProofContext,
    private val budget: SourceLpBudget,
    private val cancellation: Cancellation,
    private val pause: () -> Boolean,
    private val onIncumbent: (Sample) -> Unit,
    private val incumbent: () -> Pair<Sample, BigFraction>?,
    private val useBranchHistory: Boolean = false,
    borrowedAuthority: LinearAuthority? = null,
    private val useGuidance: Boolean = false,
    private val inheritBounds: Boolean = false,
    private val useCuts: Boolean = true,
    useActiveRows: Boolean = false,
    useRefutationReuse: Boolean = false,
    private val useRefutationPropagation: Boolean = false,
    private val useRefutationResolution: Boolean = false,
    useSupportBranching: Boolean = false,
    private val pointCuts: Boolean = false,
    private val onNumericalFeedback: ((SourceNumericalFeedbackResult) -> Unit)? = null,
    aggregateCuts: Boolean = false,
    latestFeedback: Boolean = true,
    feedbackRequest: LinearAuthorityFeedbackRequest? = null,
    composeBooleanBranches: Boolean = false,
    aggregateBranching: Boolean = false,
) : SearchBrancher, SearchBranchProposal, SearchNodePolicy, AutoCloseable {
    override val branchKind: SearchBranchKind get() = SearchBranchKind.Arithmetic

    private val aggregateBranches = if (aggregateBranching && !useActiveRows && !composeBooleanBranches &&
        !useRefutationReuse &&
        !useRefutationPropagation && !useRefutationResolution) LinearAggregateBranches(budget) else null

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
    }, retainLp = true, aggregateCuts = aggregateCuts)
    private val initialCutObservations = authority.cutObservations()
    private val activeRows = if (useActiveRows || composeBooleanBranches)
        LinearActiveRows(source, budget, composeBranches = composeBooleanBranches) else null
    private val propagateRefutations = useRefutationPropagation || useRefutationResolution
    private val refutations = if (useRefutationReuse || propagateRefutations)
        LinearRefutationReuse(source, budget, cancellation, resolve = useRefutationResolution) else null
    // A callback without an explicit request receives the full projection.
    private val feedback = if (useSupportBranching || onNumericalFeedback != null) {
        val callback = feedbackRequest ?: if (onNumericalFeedback != null) LinearAuthorityFeedbackRequest.Full
            else LinearAuthorityFeedbackRequest()
        SourceNumericalFeedback(requireNotNull(budget.funding), cancellation, latestFeedback,
            if (useSupportBranching) callback + LinearSupportBranching.REQUEST else callback)
            .also { it.attach(authority) }
    } else null
    private val supportBranching = feedback?.takeIf { useSupportBranching }?.let {
        LinearSupportBranching(requireNotNull(budget.funding), it)
    }

    fun captureTo(journal: LinearAuthorityCapture) {
        authority.capture = journal
        feedback?.attach(authority)
    }

    override fun close() {
        activeRows?.close()
        feedback?.detach(authority)
        refutations?.clear()
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

    fun observations(): Map<String, Long> = observations + activeRows?.observations.orEmpty() +
        authority.cutObservations().mapValues { (key, value) ->
        if (key.endsWith("Work") || key.startsWith("numericalCutSelection"))
            value - (initialCutObservations[key] ?: 0L) else value
    } + mapOf(
        "numericalPendingLeaves" to pending,
        "numericalPeakPendingLeaves" to peakPending,
        "numericalRetainedNodes" to retained,
        "numericalLpPreparations" to authority.preparations,
        "numericalLpReuses" to authority.reuses,
    ) + (aggregateBranches?.let { mapOf("numericalAggregateBranchWork" to it.work,
        "numericalAggregateBranchAllocation" to it.allocation,
        "numericalAggregateBranchProposals" to it.proposals) } ?: emptyMap()) +
        (feedback?.let { mapOf("numericalFeedbackCollections" to it.collections,
        "numericalFeedbackLateCollections" to it.lateCollections,
        "numericalFeedbackUnavailable" to it.unavailable,
        "numericalFeedbackCutEvaluations" to it.evaluatedCuts,
        "numericalFeedbackArithmeticTerms" to it.evaluatedTerms) } ?: emptyMap()) +
        (supportBranching?.let { mapOf("numericalSupportConsultations" to it.consultations,
            "numericalSupportSelections" to it.selections, "numericalSupportFallbacks" to it.fallbacks) }
            ?: emptyMap()) + refutations?.observations().orEmpty()

    private fun observe(stage: String) {
        observations[stage] = (observations[stage] ?: 0L) + 1L
    }

    private fun linkCapture(scope: SourceScope) {
        val capture = authority.capture ?: return
        val parent = path.getOrNull(path.lastIndex - 1)?.second
        val split = parent?.split
        val sibling = split?.let { if (current === it.below) it.above else it.below }
        capture.link(scope, parent?.let { source.scope.copy(condition = it.condition) },
            sibling?.let { it.coveredBound ?: it.result?.bound }, incumbent()?.second)
    }

    private fun splitCreated() {
        pending++
        retained += 2
        val condition = current.condition
        budget.funding?.charge(0L, 1024L + 256L *
            (condition.bools.size.toLong() + condition.lower.size + condition.upper.size +
                condition.integerRows.sumOf { it.supportSize.toLong() + 1L }))
        peakPending = maxOf(peakPending, pending)
    }
    var ray: SourceEvidence? = null
        private set
    var rootBound: SourceEvidence? = null
        private set

    fun frontierRank(): BigFraction? {
        val minimizes = source.scope.objective?.sense == com.eignex.klause.ir.ObjectiveSense.MINIMIZE
        return path.mapNotNull { (_, node) ->
            val claim = (node.coveredBound ?: node.result?.bound ?: node.partialBound)?.claim as? SourceClaim.Bound
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
                if (propagateRefutations) {
                    if (source.coordinates.none { it.kind == SourceCoordinate.Kind.Boolean && it.id == id } ||
                        current.condition.bools[id] == Lit.isPositive(decision.literal)
                    ) return ComponentResult.Consistent
                }
                val split = current.split ?: CoverageSplit.Boolean(id, current.condition).also {
                    current.split = it
                    splitCreated()
                }
                check(split is CoverageSplit.Boolean && split.id == id)
                if (Lit.isPositive(decision.literal)) split.above else split.below
            }
            is SearchDecision.Theory -> {
                val aggregate = decision.decision as? AggregateAlternative
                if (aggregate != null) {
                    val split = current.split as? CoverageSplit.Aggregate ?: return ComponentResult.Indeterminate
                    if (aggregate.row != split.row) return ComponentResult.Indeterminate
                    val selected = if (aggregate.upper) split.below else split.above
                    selected.assertion = decision
                    path += context.decisionLevel to selected
                    return ComponentResult.Consistent
                }
                val registered = decision.decision as? RegisteredTheoryDecision
                val atom = (registered?.payload as? SourceBoundPredicate)?.sourceBound
                val bound = if (atom != null) {
                    if (context.atomLiteral(decision) == null || atom.strict) return ComponentResult.Indeterminate
                    val term = atom.terms.singleOrNull() ?: return ComponentResult.Indeterminate
                    val integer = term.source as? SearchIntValue ?: return ComponentResult.Indeterminate
                    if (term.coefficient != BigFraction.ONE) return ComponentResult.Indeterminate
                    val coordinate = SourceCoordinate(SourceCoordinate.Kind.Integer, integer.variable)
                    if (coordinate !in source.coordinates) return ComponentResult.Indeterminate
                    if (current.condition.implies(SourceCondition().bound(coordinate, atom.threshold, atom.upper))) {
                        return ComponentResult.Consistent
                    }
                    IntegerAlternative(coordinate,
                        if (atom.upper) atom.threshold else atom.threshold - BigFraction.ONE, atom.upper)
                } else decision.decision as? IntegerAlternative ?: return ComponentResult.Indeterminate
                val split = current.split as? CoverageSplit.Integer ?: return ComponentResult.Indeterminate
                check(split.coordinate == bound.coordinate && split.threshold == bound.threshold)
                if (bound.upper) split.below else split.above
            }
            else -> return ComponentResult.Indeterminate
        }
        child.assertion = decision
        path += context.decisionLevel to child
        return ComponentResult.Consistent
    }

    override fun propagate(context: SearchContext): ComponentResult {
        if (!propagateRefutations) return ComponentResult.Consistent
        while (!cancellation() && !pause()) {
            val node = current
            val replay = node.unit
            if (replay != null) {
                if (!applyUnit(context, replay)) {
                    if (cancellation() || budget.funding?.paused == true) break
                    return ComponentResult.Indeterminate
                }
                observe("numericalRefutationUnitReplays")
                continue
            }
            if (node.pending || node.result != null || node.split != null ||
                source.coordinates.any { it.kind == SourceCoordinate.Kind.Boolean && context.boolValue(it.id) == null }
            ) break
            val exclusion = refutations?.unit(node.condition) ?: break
            val atom = integerAtom(context, exclusion.coordinate, exclusion.threshold)
            if (atom == null) {
                observe("numericalRefutationUnitNameDeclines")
                break
            }
            val survivor = if (exclusion.upper) atom.negative else atom.positive
            if (context.boolValue(survivor.literal ushr 1) != null) break
            val explanation = unitExplanation(context, survivor, exclusion.support)
            if (explanation == null) {
                observe("numericalRefutationUnitExplanationDeclines")
                break
            }
            val split = CoverageSplit.Integer(exclusion.coordinate, exclusion.threshold, node.condition)
            val rejected = if (exclusion.upper) split.below else split.above
            rejected.result = LinearAuthorityResult(evidence = exclusion.evidence)
            node.split = split
            node.unit = survivor
            node.unitSupport = exclusion.support
            splitCreated()
            pending--
            observe("numericalClosedLeaves")
            observe("numericalRefutationUnitDeductions")
            val child = if (exclusion.upper) split.above else split.below
            child.assertion = SearchDecision.Theory(survivor)
            path += context.decisionLevel to child
            val result = context.imply(survivor.literal, explanation)
            refutations?.consumed(exclusion.id)
            if (result !is ComponentResult.Consistent) return result
            if (exclusion.resolved) observe("numericalRefutationResolvedDeductions")
            if (exclusion.unfixedPivot) observe("numericalRefutationUnfixedDeductions")
        }
        return ComponentResult.Consistent
    }

    private fun unitExplanation(
        context: SearchContext, conclusion: RegisteredTheoryDecision, support: SourceCondition?,
    ): SearchExplanation? {
        if (path.size > 4096 || cancellation() || budget.funding?.paused == true) return null
        budget.observe(conflictCertification = 1L + path.size)
        budget.funding?.charge(0L, 256L + 64L * path.size)
        if (cancellation() || budget.funding?.paused == true) return null
        val assertions = path.drop(1).map { it.second.assertion ?: return null }
        val selected = if (useRefutationResolution) {
            linearRefutationSupport(source.scope.condition, assertions, support ?: return null, conclusion) {
                budget.observe(conflictCertification = 1L)
                budget.funding?.charge(0L, 128L)
                !cancellation() && budget.funding?.paused != true
            } ?: return null
        } else assertions
        val premises = selected.map { SearchAtomPremise.Asserted(it) }
        if (selected.size < assertions.size) observe("numericalRefutationSupportReasons")
        return context.explainAtoms(SearchAtomPremise.All(premises), SearchDecision.Theory(conclusion))
    }

    private fun integerAtom(
        context: SearchContext, coordinate: SourceCoordinate, threshold: BigFraction,
    ): SearchTheoryAtom? {
        if (cancellation() || budget.funding?.paused == true) return null
        // Registration is atomic; a spent grant pauses before the next node can query the source.
        budget.observe(conflictCertification = 1L)
        budget.funding?.charge(0L, 384L)
        if (cancellation()) return null
        return SourceBoundAtom.integerSplit(context,
            listOf(SourceBoundTerm(SearchIntValue(coordinate.id), BigFraction.ONE)), threshold)?.also {
            refutations?.name(coordinate, threshold, it.positive.literal)
        }
    }

    private fun applyUnit(context: SearchContext, survivor: RegisteredTheoryDecision): Boolean {
        val explanation = unitExplanation(context, survivor, current.unitSupport) ?: return false
        val split = current.split as? CoverageSplit.Integer ?: return false
        val atom = (survivor.payload as? SourceBoundPredicate)?.sourceBound ?: return false
        val child = if (atom.upper) split.below else split.above
        child.assertion = SearchDecision.Theory(survivor)
        path += context.decisionLevel to child
        return context.imply(survivor.literal, explanation) is ComponentResult.Consistent
    }

    override fun retract(decisionLevel: Int) {
        activeRows?.retract()
        while (path.last().first > decisionLevel) path.removeAt(path.lastIndex)
    }

    override fun beforeBranch(context: SearchContext): SearchNodeDisposition {
        if (cancellation()) return SearchNodeDisposition.Indeterminate
        if (pause() || (propagateRefutations && budget.funding?.paused == true)) return SearchNodeDisposition.Pause
        if (current.unit != null) {
            if (propagate(context) !is ComponentResult.Consistent) return SearchNodeDisposition.Indeterminate
            if (cancellation()) return SearchNodeDisposition.Indeterminate
            if (pause() || budget.funding?.paused == true) return SearchNodeDisposition.Pause
        }
        if (current.result?.evidence?.claim == SourceClaim.Refutation) return SearchNodeDisposition.Prune
        if (source.coordinates.any {
                it.kind == SourceCoordinate.Kind.Boolean && context.boolValue(it.id) == null
            }
        ) {
            authority.capture?.record(source.atScope(source.scope.copy(condition = current.condition)),
                null, null, null, LinearAuthorityResult())
            val partial = activeRows?.check(current.condition, cancellation, context::consumeCheck, authority.capture)
            if (partial == null) {
                feedback?.consume(source.atScope(source.scope.copy(condition = current.condition)))?.let {
                    onNumericalFeedback?.invoke(it)
                }
                return SearchNodeDisposition.Expand
            }
            feedback?.consume(source.atScope(source.scope.copy(condition = current.condition)),
                paused = partial.paused)?.let { onNumericalFeedback?.invoke(it) }
            if (partial.attempted) observe("numericalPartialChecks")
            if (partial.captureDeclined) observe("numericalPartialIncompleteCaptures")
            if (partial.unchanged) observe("numericalPartialUnchangedStates")
            if (partial.paused) {
                observe("numericalPartialPauses")
                return SearchNodeDisposition.Pause
            }
            linkCapture(source.scope.copy(condition = current.condition))
            partial.evidence?.let {
                current.result = LinearAuthorityResult(evidence = it)
                pending--
                observe("numericalPartialConflicts")
                observe("numericalClosedLeaves")
                return SearchNodeDisposition.Prune
            }
            return SearchNodeDisposition.Expand
        }
        activeRows?.release()
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
        if (refutations != null && !current.pending && current.split == null) {
            refutations.find(current.condition, consume = true)?.let { evidence ->
                current.result = LinearAuthorityResult(evidence = evidence)
                pending--
                observe("numericalClosedLeaves")
                observe("numericalAvoidedQueries")
                return SearchNodeDisposition.Prune
            }
            if (cancellation()) return SearchNodeDisposition.Indeterminate
            if (pause()) return SearchNodeDisposition.Pause
        }
        if (!current.pending && !context.consumeCheck()) return SearchNodeDisposition.Indeterminate
        val branch = if (queriedNode === current) checkNotNull(queriedSource) else
            source.atScope(source.scope.copy(condition = current.condition)).also {
                queriedNode = current
                queriedSource = it
            }
        val queried = if (useGuidance && useCuts && pointCuts) authority.advanceWithPointCuts(branch, cancellation)
            else if (useGuidance) authority.advance(branch, cancellation, cuts = useCuts)
            else authority.check(branch, cancellation)
        val result = if (current.partialBound == null) queried else
            queried.copy(bound = strongerLinearBound(current.partialBound, queried.bound))
        for ((stage, count) in result.primalStages) {
            val key = "numerical$stage"
            val previous = observations[key] ?: 0L
            observations[key] = if ("Max" in stage) maxOf(previous, count) else previous + count
        }
        if (result.paused) {
            current.partialBound = strongerLinearBound(current.partialBound, result.bound)
            feedback?.consume(branch, paused = true)?.let { onNumericalFeedback?.invoke(it) }
            current.pending = true
            observe("numericalLpPauses")
            return SearchNodeDisposition.Pause
        }
        current.pending = false
        linkCapture(branch.scope)
        val lateBefore = feedback?.lateCollections
        val numericalFeedback = feedback?.consume(branch)?.also { onNumericalFeedback?.invoke(it) }
        val lateFeedback = feedback != null && feedback.lateCollections != lateBefore
        current.result = if (result.primalStages.isEmpty()) result else result.copy(primalStages = emptyMap())
        observe("numericalChecks")
        result.evidence?.takeIf { it.claim == SourceClaim.Refutation }?.let { refutations?.retain(it) }
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
            aggregateBranches?.propose(branch, cancellation)?.let { row ->
                budget.funding?.charge(0L, 256L + 128L * row.supportSize)
                current.split = CoverageSplit.Aggregate(row, current.condition)
                splitCreated()
                observe("numericalAggregateSplits")
                return SearchNodeDisposition.Expand
            }
            val candidate = if (supportBranching != null && numericalFeedback != null)
                supportBranching.select(numericalFeedback, result.fractional, cancellation)
                else if (useBranchHistory) branchHistory.select(result.fractional, cancellation)
                else result.fractional.firstOrNull()
            val selected = candidate?.floorSplit() ?: (coordinate to threshold)
            if (supportBranching == null && selected != result.split) observe("numericalHistorySelections")
            if (supportBranching != null && lateFeedback && candidate != result.fractional.firstOrNull()) {
                observe("numericalSupportLateSelections")
            }
            current.split = CoverageSplit.Integer(selected.first, selected.second, current.condition, candidate)
            splitCreated()
            observe("numericalFractionalSplits")
            return if (propagateRefutations && budget.funding?.paused == true) SearchNodeDisposition.Pause
                else SearchNodeDisposition.Expand
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
                return if (propagateRefutations && budget.funding?.paused == true) SearchNodeDisposition.Pause
                    else SearchNodeDisposition.Expand
            }
        }
        return SearchNodeDisposition.Indeterminate
    }

    override fun nextBranch(context: SearchContext): List<SearchDecision>? {
        (current.split as? CoverageSplit.Aggregate)?.let { split ->
            return listOf(true, false).map { SearchDecision.Theory(AggregateAlternative(split.row, it)) }
        }
        val split = current.split as? CoverageSplit.Integer ?: return null
        if (propagateRefutations) {
            return integerAtom(context, split.coordinate, split.threshold)?.alternatives()
        }
        return listOf(true, false).map {
            SearchDecision.Theory(IntegerAlternative(split.coordinate, split.threshold, it))
        }
    }

    private fun closeInheritedBound(): Boolean {
        val standing = incumbent()?.second ?: return false
        val minimizes = source.scope.objective?.sense == com.eignex.klause.ir.ObjectiveSense.MINIMIZE
        for ((_, ancestor) in path.dropLast(1).asReversed()) {
            if (cancellation()) return false
            val inherited = ancestor.coveredBound ?: ancestor.result?.bound ?: ancestor.partialBound ?: continue
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
        val verification = if (aggregateBranches == null) cancellation else Cancellation {
            budget.funding?.charge(1L)
            observe("numericalAggregateCoverageWork")
            cancellation() || budget.funding?.exhausted == true
        }
        return evidence.takeIf {
            SourceEvidenceVerifier(proofSource).verify(it, verification) == SourceEvidenceCheck.Checked
        }
    }
}

private data class IntegerAlternative(
    val coordinate: SourceCoordinate, val threshold: BigFraction, val upper: Boolean,
) : SearchTheoryDecision

private data class AggregateAlternative(val row: SourceInequality, val upper: Boolean) : SearchTheoryDecision

private class CoverageNode(val condition: SourceCondition) {
    var assertion: SearchDecision? = null
    var unit: RegisteredTheoryDecision? = null
    var unitSupport: SourceCondition? = null
    var split: CoverageSplit? = null
    var result: LinearAuthorityResult? = null
    var coveredBound: SourceEvidence? = null
    var pending: Boolean = false
    var partialBound: SourceEvidence? = null

    fun boundCoverage(inherited: SourceEvidence?, minimizes: Boolean, cancellation: Cancellation): BoundCoverage? {
        if (cancellation()) return null
        fun value(evidence: SourceEvidence): BigFraction =
            ((evidence.claim as SourceClaim.Bound).value as SourceValue.Rational).value
        val bound = listOfNotNull(inherited, coveredBound, result?.bound, partialBound).reduceOrNull { left, right ->
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
                is CoverageSplit.Aggregate -> SourceIntegerAggregateSplit(children.row, below.proof, above.proof)
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
                is CoverageSplit.Aggregate -> SourceIntegerAggregateSplit(children.row, below, above)
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
    class Aggregate(val row: SourceInequality, condition: SourceCondition) :
        CoverageSplit(condition.integerBound(row), condition.integerBound(row.adjacentIntegerAbove()))
    class Integer(val coordinate: SourceCoordinate, val threshold: BigFraction, condition: SourceCondition,
        val fractional: Pair<SourceCoordinate, BigFraction>? = null) :
        CoverageSplit(condition.bound(coordinate, threshold, true),
            condition.bound(coordinate, threshold + BigFraction.ONE, false))
}
