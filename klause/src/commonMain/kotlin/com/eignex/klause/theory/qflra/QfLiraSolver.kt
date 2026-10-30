package com.eignex.klause.theory.qflra

import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.RealConstants
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.complemented
import com.eignex.klause.ir.indices
import com.eignex.klause.ir.linearRows
import com.eignex.klause.lp.ExactComparison
import com.eignex.klause.lp.ExactMixedBoundedRow
import com.eignex.klause.lp.ExactMixedEchelonHermite
import com.eignex.klause.lp.ExactMixedTriangularBounds
import com.eignex.klause.lp.ExactRowForm
import com.eignex.klause.lp.SourceLp
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.admittedSourcePoint
import com.eignex.klause.lp.asFraction
import com.eignex.klause.lp.bounding.LpEffortProfile
import com.eignex.klause.lp.bounding.LpPremisedBound
import com.eignex.klause.lp.bounding.LpPropagator
import com.eignex.klause.lp.bounding.LpSearchPolicy
import com.eignex.klause.lp.closeSourceLpOwners
import com.eignex.klause.lp.engine.ExactBasisMetrics
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.LpBoundBatchResult
import com.eignex.klause.lp.engine.LpCertificationObserver
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpCertifierCost
import com.eignex.klause.lp.engine.LpClaim
import com.eignex.klause.lp.engine.LpPhaseMetrics
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.ProductionLpCertificationPolicy
import com.eignex.klause.lp.exactColumnLower
import com.eignex.klause.lp.exactColumnUpper
import com.eignex.klause.lp.exactComparison
import com.eignex.klause.lp.exactForm
import com.eignex.klause.lp.exactMixedEchelonHermite
import com.eignex.klause.lp.exactMixedTriangularBounds
import com.eignex.klause.lp.satisfiesSourceRows
import com.eignex.klause.lp.sourceDoubleBoundedSplit
import com.eignex.klause.lp.sourceEqualityRows
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactContinuationLimits
import com.eignex.klause.simplex.exact.ExactContinuationMetrics
import com.eignex.klause.simplex.exact.ExactDoubleBoundedSplit
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.result.SmtStatsSink
import com.eignex.klause.solver.result.SourceLpWorkStats
import com.eignex.klause.solver.search.AssembledSearchModel
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.RegisteredTheoryDecision
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchBranchKind
import com.eignex.klause.solver.search.SearchBranchProposal
import com.eignex.klause.solver.search.SearchBrancher
import com.eignex.klause.solver.search.SearchCandidateHints
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchExactAlgebraicRealValue
import com.eignex.klause.solver.search.SearchExactIntValue
import com.eignex.klause.solver.search.SearchExactRealExpressionValue
import com.eignex.klause.solver.search.SearchExactRealValue
import com.eignex.klause.solver.search.SearchExplanation
import com.eignex.klause.solver.search.SearchIntValue
import com.eignex.klause.solver.search.SearchIntegerBound
import com.eignex.klause.solver.search.SearchModel
import com.eignex.klause.solver.search.SearchModelKey
import com.eignex.klause.solver.search.SearchRealValue
import com.eignex.klause.solver.search.SearchStopCause
import com.eignex.klause.solver.search.SearchTheoryDecision
import com.eignex.klause.solver.search.SourceBoundAtom
import com.eignex.klause.solver.search.SourceBoundPredicate
import com.eignex.klause.solver.search.SourceBoundTerm
import com.eignex.klause.solver.search.SourceCoordinateAgreement
import com.eignex.klause.solver.search.explainAtoms
import com.eignex.klause.solver.selectedFactorProjections
import com.eignex.klause.solver.snapshotValues
import com.eignex.klause.theory.EncodingTarget
import com.eignex.klause.theory.encodingSourceRealCount
import com.eignex.klause.theory.linearTheoryModel
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_TWO
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.MutableIntIntMap
import com.eignex.klause.util.MutableIntObjectMap
import com.eignex.klause.util.abs
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.div
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.magnitudeBitLength
import com.eignex.klause.util.maxOf
import com.eignex.klause.util.minOf
import com.eignex.klause.util.minus
import com.eignex.klause.util.negate
import com.eignex.klause.util.rem
import com.eignex.klause.util.toLongExact
import kotlin.time.TimeSource

/** An exact integer/rational witness for an open QF_LIRA or QF_LIA model. */
class ExactLiraAssignment(
    /** Boolean values indexed by model Boolean variable id. */
    bools: BooleanArray,
    /** Arbitrary-precision integer values indexed by model integer variable id. */
    ints: Array<BigInt>,
    /** Rational real values indexed by model real variable id. */
    reals: List<BigFraction>,
) {
    private val booleanValues: BooleanArray = bools.copyOf()

    /** Independent copy of the Boolean coordinates. */
    val bools: BooleanArray get() = booleanValues.copyOf()

    private val integerValues: Array<BigInt> = ints.copyOf()

    /** Independent copy of the integer coordinates. */
    val ints: Array<BigInt> get() = integerValues.copyOf()

    private val realValues: List<BigFraction> = snapshotValues(reals)

    /** Owned read-only rational coordinates. */
    val reals: List<BigFraction> get() = realValues

    /** Boolean coordinate without exporting its buffer. */
    fun boolValue(id: Int): Boolean = booleanValues[id]

    /** Authoritative arbitrary-precision integer coordinate. */
    fun intValue(id: Int): BigInt = integerValues[id]

    /** Capture replacement coordinates in an owned witness. */
    fun copy(
        bools: BooleanArray = booleanValues,
        ints: Array<BigInt> = integerValues,
        reals: List<BigFraction> = realValues,
    ): ExactLiraAssignment = ExactLiraAssignment(bools, ints, reals)

    /** Coordinate view for destructuring. */
    operator fun component1(): BooleanArray = bools

    /** Coordinate view for destructuring. */
    operator fun component2(): Array<BigInt> = ints

    /** Coordinate view for destructuring. */
    operator fun component3(): List<BigFraction> = reals

    override fun equals(other: Any?): Boolean = other is ExactLiraAssignment &&
        booleanValues.contentEquals(other.booleanValues) &&
        integerValues.contentEquals(other.integerValues) &&
        realValues == other.realValues

    override fun hashCode(): Int =
        31 * (31 * booleanValues.contentHashCode() + integerValues.contentHashCode()) + realValues.hashCode()

    override fun toString(): String = "ExactLiraAssignment(bools=${booleanValues.contentToString()}, " +
        "ints=${integerValues.contentToString()}, reals=$realValues)"
}

/** Incremental source arithmetic on the shared LP and search lifecycles. */
class ExactLiraSearchComponent private constructor(
    private val preparedSource: PreparedExactLiraSource,
    private val modelContribution: ((ExactLiraAssignment, SearchModel) -> Unit)?,
    private val finiteIntVars: IntArray = intArrayOf(),
) : SearchComponent,
    SearchBrancher,
    SearchBranchProposal,
    SourceCoordinateAgreement,
    AutoCloseable {
    override val branchKind: SearchBranchKind get() = SearchBranchKind.Arithmetic

    constructor(
        model: Problem,
        modelContribution: ((ExactLiraAssignment, SearchModel) -> Unit)? = null,
    ) : this(model, model.selectedFactorProjections(), modelContribution = modelContribution)

    /** Open private exact state from the selected whole source relations. */
    constructor(
        model: Problem,
        projections: PreparedFactorProjections,
        finiteIntegerVariables: IntArray = intArrayOf(),
        modelContribution: ((ExactLiraAssignment, SearchModel) -> Unit)? = null,
    ) : this(legacyExactLiraSource(requireNotNull(projections.linearTheoryModel(EncodingTarget.LINEAR)) {
        "unsupported selected exact linear encoding"
    }.also { projections.requireSource(model) }), modelContribution, finiteIntegerVariables.copyOf())

    private val model = preparedSource.model
    private val sourceRealCount = model.encodingSourceRealCount
    private val hasPrivateReals = sourceRealCount != model.numRealVars
    private var smtStats: SmtStatsSink? = null
    private var primaryLpObserver: LpCertificationObserver? = null
    private val bools = IntArray(model.numBoolVars) { UNASSIGNED }
    private var unassignedBooleans = model.numBoolVars
    private var completeTraversalObserved = false
    private var mixedPointCompletion = false
    private var defaultDisjunctionHints = false
    private val boolLevels = IntArray(model.numBoolVars) { -1 }
    private val root = SearchNode()
    private val disjunctionAtoms = HashMap<Int, List<DisjunctAtom>>()
    private val disjunctOwner = Any()
    private val hasSourceDisjunctions = model.factors.any { it.linearForm is LinearForm.Disjunction }
    private val exactForms = preparedSource.exactForms
    private val preparedRows = preparedSource.preparedRows
    private val sourceAssertionLevels = IntArray(preparedRows.size) { -1 }
    private val scopedSourceAssertions = IntArrayList()
    private var incrementalSourceRows = false
    private var sourceRowsByBoolean: Map<Int, List<Int>>? = null
    private val changedSourceBooleans = LinkedHashSet<Int>()
    private val pendingSourceRows = LinkedHashSet<Int>()
    private val recurringSourceRows = ArrayList<Int>()
    private var assertedRootFixings: List<BigFraction?>? = null
    private var sourceRowVisits = 0L
    private var sourceRowTouches = 0L
    private var sourceRowInvalidations = 0L
    private val realDifference by lazy { RealDifferenceSystem.prepare(model, exactForms, operationStop) }
    private var impliedDisjunct = false
    private val reduction = ExactLiraReductionCache(model, exactForms, disjunctionAtoms, { solveContext }) {
        smtStats?.observeSourceLp(it)
    }
    private val nodesByLevel = MutableIntObjectMap<SearchNode>()
    private var node = root
    private var assignment: ExactLiraAssignment? = null
    private var outcome: ComponentCheck? = null
    private var candidate: List<BigFraction>? = null
    private var floatCandidate: DoubleArray? = null
    private var pointHintRows: List<PreparedSourceRow>? = null
    private var pointHintValues: IntArray? = null
    private var pointHintLevel = -1
    private var pointHintStop = Cancellation.Never
    private var dirty = true
    private var partialLinearPropagation = false
    private var incrementalLinearPropagation = false
    private var partialLinearCandidates: List<PreparedSourceRow>? = null
    private var partialLinearInvocations = 0L
    private var partialLinearVisited = 0L
    private var partialLinearEligible = 0L
    private var partialLinearDeductions = 0L
    private var partialLinearDeferrals = 0L
    private var partialLinearCache = false
    private val partialLinearDeclines = HashMap<PreparedSourceRow, PartialLinearInputs>()
    private var partialLinearEvaluations = 0L
    private var partialLinearUnchanged = 0L
    private var partialLinearCost = false
    private var partialLinearProducerNs = 0L
    private var partialLinearAssertionNs = 0L
    private var partialLinearNumericalNs = 0L
    private var linearOperationStops = 0L
    private var sourceInstalled = false
    private var graphFirst = false
    private var solveContext = LpSolveContext.Production
    private var solveStop: Cancellation? = null
    private var operationAllowance: ((Cancellation) -> Cancellation)? = null
    private var operationStop = Cancellation.Never
    private var parentStop = Cancellation.Never
    private var pollContextStop = true
    private val lpStop = LinearOperationCancellation({ operationStop }) {
        pollContextStop && context?.cancelled() == true
    }
    internal var operationBudgetExhausted = false
        private set
    private val arithmeticRows = preparedSource.arithmeticRows
    private val arithmeticVariables = preparedSource.arithmeticVariables
    private val branchNames = HashMap<SourceBoundAtom, SearchDecision>()
    private var context: SearchContext? = null
    private val lpDelegate: Lazy<LpPropagator> = lazy {
        LpPropagator(
            object : LpSearchPolicy {
                override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult =
                    accept(decision, context)
                override fun propagate(context: SearchContext): ComponentResult = relax(context)
                override fun check(context: SearchContext): ComponentCheck = outcome ?: ComponentCheck.Indeterminate
                override fun nextBranch(context: SearchContext): List<SearchDecision>? = branch(context)
                override fun retract(decisionLevel: Int) = retractSource(decisionLevel)
            },
            effort = {
                LpEffortProfile(
                    continuation = ExactContinuationLimits(
                        maxRows = if (completeTraversalObserved && unassignedBooleans == 0) Int.MAX_VALUE else 128,
                    ),
                )
            },
            solveContext = solveContext,
            cancellation = lpStop,
            certificationObserver = object : LpCertificationObserver {
                override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) {
                    primaryLpObserver?.observe(certifier, success, cost)
                }
                override fun observeExactInput(accepted: Boolean) {
                    primaryLpObserver?.observeExactInput(accepted)
                }
                override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) {
                    primaryLpObserver?.observeSolve(metrics, component)
                }
                override fun observePhase(metrics: LpPhaseMetrics) {
                    primaryLpObserver?.observePhase(metrics)
                }
                override fun observeBasisVerification(metrics: ExactBasisMetrics) {
                    primaryLpObserver?.observeBasisVerification(metrics)
                }
                override fun observeContinuation(metrics: ExactContinuationMetrics) {
                    primaryLpObserver?.observeContinuation(metrics)
                    smtStats?.observeContinuation(metrics)
                }
                override fun observeContinuationRejection(key: String, count: Long) {
                    primaryLpObserver?.observeContinuationRejection(key, count)
                    smtStats?.observeContinuationRejection(key, count)
                }
            },
        )
    }
    private val lp: LpPropagator by lpDelegate
    private val system by lazy { LiveQfLraSystem(model, lp, exactForms) }

    internal fun solveWith(context: LpSolveContext) {
        check(this.context == null)
        solveContext = context
    }

    internal fun useSolveStop(stop: Cancellation?) {
        check(context == null)
        solveStop = stop
    }

    internal fun useOperationAllowance(allowance: (Cancellation) -> Cancellation) {
        check(context == null)
        operationAllowance = allowance
    }

    internal val lpMetrics get() = lp.metrics

    internal fun observeWith(stats: SmtStatsSink) {
        smtStats = stats
    }

    internal fun observePrimaryLpWith(observer: LpCertificationObserver) {
        check(context == null && primaryLpObserver == null)
        primaryLpObserver = observer
    }

    internal val defaultMixedPointOwner: Boolean
        get() = completeTraversalObserved && mixedPointCompletion && defaultDisjunctionHints

    internal fun useMixedPointCompletion() {
        check(context == null)
        mixedPointCompletion = true
    }

    internal fun useDefaultDisjunctionHints() {
        check(context == null)
        defaultDisjunctionHints = model.numIntVars > 0 && model.numRealVars > 0
    }

    internal fun observeCompleteTraversal() {
        check(context == null && !completeTraversalObserved)
        completeTraversalObserved = true
        smtStats?.observeCompleteSearchBooleans(bools.size, unassignedBooleans)
        observeCompleteStage("OPENING")
    }

    internal fun partialPointHints(
        stop: Cancellation,
        defaultOpening: Boolean = false,
        defaultGraphSelections: Boolean = false,
    ): Pair<IntArray, SearchCandidateHints>? {
        check(context == null)
        if (model.numRealVars == 0 && (!defaultOpening || model.numIntVars == 0)) return null
        if (model.numBoolVars == 0 || model.numBoolVars > MAX_POINT_HINT_ENTRIES ||
            preparedRows.size > MAX_POINT_HINT_ENTRIES || stop()
        ) {
            return null
        }
        var entries = model.numRealVars.toLong() + model.numIntVars + model.numBoolVars
        val rows = ArrayList<PreparedSourceRow>()
        val variables = LinkedHashSet<Int>()
        for (prepared in preparedRows) {
            stop.charge(1)
            if (stop()) return null
            if (prepared.row.activator == LinearRow.ALWAYS) continue
            if (prepared.row.activator !in bools.indices) return null
            entries += prepared.row.size.toLong() + 1L
            if (entries > MAX_POINT_HINT_ENTRIES) return null
            rows += prepared
            variables += prepared.row.activator
        }
        stop.charge(rows.size.toLong() + variables.size + 1L)
        if (stop() || rows.isEmpty()) return null
        pointHintRows = rows.toList()
        pointHintStop = stop
        return variables.toIntArray() to SearchCandidateHints { variable ->
            observeCompleteStage("POINT_HINT_CONSULTED")
            if (!partialPointHintAvailable()) {
                observeCompleteStage("POINT_HINT_UNAVAILABLE")
                null
            } else if (defaultOpening && graphFirst && !defaultGraphSelections) {
                smtStats?.observeDefaultGraphHintRelease()
                releasePartialPointHints()
                null
            } else {
                val preference = when (pointHintValues?.getOrNull(variable)) {
                    TRUE -> true
                    FALSE -> false
                    else -> null
                }
                observeCompleteStage(if (preference == null) "POINT_HINT_NO_PREFERENCE" else "POINT_HINT_PREFERRED")
                preference
            }
        }
    }

    internal fun partialPointHintAvailable(): Boolean {
        val current = context ?: return false
        return pointHintValues != null && !dirty && current.decisionLevel == pointHintLevel &&
            !current.cancelled() && !pointHintStop() && !parentStop()
    }

    internal fun partialPointHintBool(variable: Int): Boolean? =
        if (partialPointHintAvailable()) context?.boolValue(variable) else null

    internal fun releasePartialPointHints() {
        defaultDisjunctionHints = false
        pointHintRows = null
        pointHintValues = null
        pointHintLevel = -1
        pointHintStop = Cancellation.Never
    }

    internal fun probePartialLinearImplications(): PartialLinearProbe {
        val current = checkNotNull(context)
        beginOperation()
        return try {
            derivePartialLinearImplications(current, applyLocally = false)
        } finally {
            endOperation(current)
        }
    }

    internal fun usePartialLinearPropagation() {
        val current = context
        check(current == null || current.decisionLevel == 0)
        partialLinearPropagation = true
    }

    internal fun useIncrementalLinearPropagation() {
        useIncrementalSourceRows()
        incrementalLinearPropagation = true
    }

    internal fun useIncrementalSourceRows() {
        usePartialLinearPropagation()
        reusePartialLinearDeclines()
        incrementalSourceRows = true
    }

    internal val partialLinearPropagationStats: PartialLinearPropagationStats
        get() = PartialLinearPropagationStats(partialLinearCandidates?.size ?: 0,
            partialLinearInvocations, partialLinearVisited, partialLinearEligible,
            partialLinearDeductions, partialLinearDeferrals, partialLinearEvaluations, partialLinearUnchanged,
            partialLinearProducerNs, partialLinearAssertionNs, partialLinearNumericalNs,
            system.assertionCost.definitionAppends, system.assertionCost.definitionNs,
            system.assertionCost.boundCalls, system.assertionCost.boundSides, system.assertionCost.boundNs,
            sourceRowVisits, sourceRowTouches, sourceRowInvalidations, linearOperationStops)

    internal fun observePartialLinearCost() {
        check(context == null || context?.decisionLevel == 0)
        partialLinearCost = true
        system.observeAssertionCost = true
    }

    internal fun useIndividualSourceBounds() {
        check(context == null || context?.decisionLevel == 0)
        system.batchSourceBounds = false
    }

    private inline fun <T> measurePartialLinearPhase(record: (Long) -> Unit, action: () -> T): T {
        if (!partialLinearCost) return action()
        val started = TimeSource.Monotonic.markNow()
        return try {
            action()
        } finally {
            record(started.elapsedNow().inWholeNanoseconds)
        }
    }

    internal fun reusePartialLinearDeclines() {
        check(context == null || context?.decisionLevel == 0)
        partialLinearCache = true
    }

    private fun partialLinearCandidateRows(): List<PreparedSourceRow>? {
        partialLinearCandidates?.let { return it }
        operationStop.charge(bools.size.toLong() + 1L)
        if (operationStop()) return null
        val heads = IntArray(bools.size)
        for (prepared in preparedRows) {
            operationStop.charge(1)
            if (operationStop()) return null
            if (prepared.row.activator != LinearRow.ALWAYS) heads[prepared.row.activator]++
        }
        val candidates = ArrayList<PreparedSourceRow>()
        for (prepared in preparedRows) {
            operationStop.charge(1)
            if (operationStop()) return null
            val row = prepared.row
            if (prepared.disjunction || row.activator == LinearRow.ALWAYS || heads[row.activator] != 1) continue
            operationStop.charge(row.size.toLong())
            if ((0 until row.size).none {
                    Term.isBool(row.ref(it)) && Lit.variable(Term.lit(row.ref(it))) == row.activator
                }
            ) candidates += prepared
        }
        operationStop.charge(candidates.size.toLong() + 1L)
        if (operationStop()) return null
        return candidates.toList().also { partialLinearCandidates = it }
    }

    private fun derivePartialLinearImplications(current: SearchContext, applyLocally: Boolean): PartialLinearProbe =
        measurePartialLinearPhase({ partialLinearProducerNs += it }) {
            derivePartialLinearImplicationsWithin(current, applyLocally)
        }

    private fun derivePartialLinearImplicationsWithin(
        current: SearchContext, applyLocally: Boolean,
    ): PartialLinearProbe {
        var visited = 0
        var eligible = 0
        var unexplained = 0
        var affineProbes = 0
        var affineDeductions = 0
        val previousUnchanged = partialLinearUnchanged
        val deductions = ArrayList<PartialLinearDeduction>()
        partialLinearInvocations++
        try {
            val state = lp.state ?: return PartialLinearProbe(0, 0, 0, false, emptyList())
            val candidates = partialLinearCandidateRows()
                ?: return PartialLinearProbe(0, 0, 0, true, emptyList())
            for (prepared in candidates) {
                operationStop.charge(1)
                if (operationStop()) break
                visited++
                val variable = prepared.row.activator
                if (current.boolValue(variable) != null) continue
                val row = prepared.row
                operationStop.charge(row.size.toLong() + prepared.booleans.size)
                if (prepared.booleans.any { it != variable && bools[it] == UNASSIGNED }) continue
                eligible++
                val comparison = prepared.form.comparison(true) { bools[it] == TRUE }
                val affine = if (incrementalLinearPropagation && comparison.terms.size > 1) {
                    affineProbes++
                    system.affineImplication(comparison, operationStop)
                } else null
                val inputs = if (affine == null && partialLinearCache) {
                    partialLinearInputs(comparison) ?: continue
                } else null
                if (inputs != null && partialLinearDeclines[prepared] == inputs) {
                    partialLinearUnchanged++
                    continue
                }
                var unavailable = false
                partialLinearEvaluations++
                val implication = affine ?: partialLinearImplication(comparison, { column, upper ->
                    val metadata = state.model.column(column)
                    if (metadata.tag != column || !metadata.origin.value.isZero) null
                    else state.activeSide(column, upper)?.let { active ->
                        val premise = lp.activeBoundPremise(column, upper) ?: SearchAtomPremise.Unavailable
                        if (premise === SearchAtomPremise.Unavailable) unavailable = true
                        ExplainedLinearBound(active.side.number.value, active.side.strict, premise)
                    }
                }, operationStop)
                if (implication == null) {
                    if (inputs != null && !unavailable && !operationStop()) partialLinearDeclines[prepared] = inputs
                    continue
                }
                partialLinearDeclines.remove(prepared)
                val rhs = prepared.booleans.filter { it != variable }.map { input ->
                    SearchAtomPremise.Asserted(SearchDecision.Bool(Lit.make(input, bools[input] == TRUE)))
                }
                val decision = SearchDecision.Bool(Lit.make(variable, implication.truth))
                val reason = current.explainAtoms(SearchAtomPremise.All(rhs + implication.premise), decision)
                if (reason == null) {
                    unexplained++
                    continue
                }
                operationStop.charge(reason.literals.size.toLong() + rhs.size + 1L)
                if (operationStop()) break
                if (current.imply(decision.literal, reason) !is ComponentResult.Consistent) break
                if (applyLocally && accept(decision, current) !is ComponentResult.Consistent) break
                deductions += PartialLinearDeduction(prepared.address.factor, prepared.address.row,
                    variable, implication.truth, reason.literals.copyOf())
                if (affine != null) affineDeductions++
            }
            return PartialLinearProbe(visited, eligible, unexplained, operationStop(), deductions)
        } finally {
            partialLinearVisited += visited
            partialLinearEligible += eligible
            partialLinearDeductions += deductions.size
            if (completeTraversalObserved && partialLinearPropagation) {
                smtStats?.observeCompleteSearchStage("PARTIAL_LINEAR_VISITED", visited.toLong())
                smtStats?.observeCompleteSearchStage("PARTIAL_LINEAR_DECLINE_REUSE",
                    partialLinearUnchanged - previousUnchanged)
                smtStats?.observeCompleteSearchStage("PARTIAL_LINEAR_DEDUCTION", deductions.size.toLong())
                smtStats?.observeCompleteSearchStage("PARTIAL_LINEAR_AFFINE_PROBE", affineProbes.toLong())
                smtStats?.observeCompleteSearchStage("PARTIAL_LINEAR_AFFINE_DEDUCTION", affineDeductions.toLong())
            }
        }
    }

    private fun partialLinearInputs(comparison: ExactComparison): PartialLinearInputs? {
        val state = lp.state ?: return null
        val sides = ArrayList<PartialLinearSide?>(comparison.terms.size * 2)
        for (column in comparison.terms.keys) {
            operationStop.charge(3)
            if (operationStop()) return null
            val metadata = state.model.column(column)
            if (metadata.tag != column || !metadata.origin.value.isZero) return null
            for (upper in listOf(false, true)) {
                sides += state.activeSide(column, upper)?.side?.let { PartialLinearSide(it.number.value, it.strict) }
            }
        }
        return PartialLinearInputs(comparison.bound, sides)
    }

    private fun capturePointHints(point: List<BigFraction>, context: SearchContext, graph: Boolean = false) {
        pointHintValues = null
        val rows = pointHintRows ?: return
        if (point.size != model.numRealVars + model.numIntVars || operationStop() || pointHintStop()) return
        val poll = if (incrementalLinearPropagation) {
            val stop = object : Cancellation {
                override fun isCancelled() = operationStop() || pointHintStop()
                override fun workMeter() = operationStop.workMeter() ?: pointHintStop.workMeter()
            }
            LinearOperationCancellation({ stop }) { false }.apply { begin() }
        } else null
        operationStop.charge(model.numBoolVars.toLong() + point.size + 1L)
        if (operationStop() || point.any { !hintScalar(it, poll) }) return
        if (poll != null && (operationStop() || pointHintStop())) return
        val values = IntArray(model.numBoolVars) { UNASSIGNED }
        for (prepared in rows) {
            operationStop.charge(1)
            if (hintStopped(poll)) return
            val variable = prepared.row.activator
            if (values[variable] == DECLINED_HINT) continue
            val comparison = if (prepared.disjunction) null else prepared.form.hintComparison(
                { variable -> bools[variable].takeIf { it != UNASSIGNED }?.let { it == TRUE } },
                { hintScalar(it, poll) }, { left, right -> hintPreflight(left, right, poll) },
            )
            val preference = comparison?.let { hintTruth(it, point, poll) }
            if (preference == null) {
                values[variable] = DECLINED_HINT
                continue
            }
            val value = if (preference) TRUE else FALSE
            if (values[variable] != UNASSIGNED && values[variable] != value) {
                values[variable] = DECLINED_HINT
            } else {
                values[variable] = value
            }
        }
        if ((poll != null && poll()) || operationStop() || pointHintStop()) return
        pointHintLevel = context.decisionLevel
        pointHintValues = values
        smtStats?.observePointHintCapture(graph)
        observeCompleteStage(if (graph) "POINT_HINT_GRAPH_CAPTURE" else "POINT_HINT_LP_CAPTURE")
    }

    private fun hintTruth(
        comparison: ExactComparison, point: List<BigFraction>, poll: Cancellation? = null,
    ): Boolean? = hintTruth(comparison, poll) { point.getOrNull(it) }

    private inline fun hintTruth(
        comparison: ExactComparison, poll: Cancellation? = null, value: (Int) -> BigFraction?,
    ): Boolean? {
        var activity = BigFraction.ZERO
        for ((column, coefficient) in comparison.terms) {
            val coordinate = value(column) ?: return null
            if (!hintPreflight(coefficient, coordinate, poll)) return null
            val product = coefficient * coordinate
            if (!hintScalar(product, poll) || !hintPreflight(activity, product, poll)) return null
            activity += product
            if (!hintScalar(activity, poll)) return null
        }
        if (!hintPreflight(activity, comparison.bound, poll)) return null
        return when (comparison.op) {
            LinearOp.LE -> if (comparison.strict) activity < comparison.bound else activity <= comparison.bound
            LinearOp.GE -> if (comparison.strict) activity > comparison.bound else activity >= comparison.bound
            LinearOp.EQ -> activity == comparison.bound
            LinearOp.NE -> activity != comparison.bound
        }
    }

    private fun hintScalar(value: BigFraction): Boolean = hintScalar(value, null)

    private fun hintScalar(value: BigFraction, poll: Cancellation?): Boolean {
        val bits = maxOf(value.num.magnitudeBitLength(), value.den.magnitudeBitLength())
        operationStop.charge(1L + bits / Long.SIZE_BITS)
        return bits <= MAX_POINT_HINT_BITS && !hintStopped(poll)
    }

    private fun hintPreflight(left: BigFraction, right: BigFraction): Boolean = hintPreflight(left, right, null)

    private fun hintPreflight(left: BigFraction, right: BigFraction, poll: Cancellation?): Boolean {
        val bits = maxOf(left.num.magnitudeBitLength(), left.den.magnitudeBitLength()).toLong() +
            maxOf(right.num.magnitudeBitLength(), right.den.magnitudeBitLength()) + 2L
        if (bits > MAX_POINT_HINT_BITS * 2L + 2L) return false
        val words = bits / Long.SIZE_BITS + 1L
        operationStop.charge(bits + words * words)
        return !hintStopped(poll)
    }

    private fun hintStopped(poll: Cancellation?): Boolean = poll?.invoke() ?: (operationStop() || pointHintStop())

    init {
        require(preparedSource.validated || model.supportsExactLira() || model.supportsExactLra()) {
            "unsupported exact linear component"
        }
        nodesByLevel.put(0, root)
        for ((index, factor) in model.factors.withIndex()) {
            if ((factor.linearForm as? LinearForm.Disjunction)?.rows?.isEmpty() == true) {
                disjunctionAtoms[index] = emptyList()
            }
        }
    }

    override fun initialize(context: SearchContext): ComponentResult {
        check(this.context == null) { "a theory component belongs to one immutable source session" }
        this.context = context
        val contextStop = context.executionControls.cancellation
        parentStop = solveStop ?: contextStop
        pollContextStop = solveStop != null && solveStop !== contextStop
        beginOperation()
        return try {
            graphFirst = !hasPrivateReals && model.numIntVars == 0 &&
                solveContext.certificationPolicy === ProductionLpCertificationPolicy && realDifference != null
            if (graphFirst) smtStats?.observeRealGraphOpening()
            if ((!graphFirst && !installSource()) || operationStop()) {
                ComponentResult.Indeterminate
            } else {
                lp.initialize(context).takeUnless { operationStop() } ?: ComponentResult.Indeterminate
            }
        } finally {
            endOperation(context)
        }
    }

    override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
        beginOperation()
        return try {
            if (operationStop()) {
                ComponentResult.Indeterminate
            } else {
                if (decision is SearchDecision.Theory && !installSource()) return ComponentResult.Indeterminate
                val result = lp.assertWithin(decision, context, operationStop)
                if (operationStop()) ComponentResult.Indeterminate else result
            }
        } finally {
            endOperation(context)
        }
    }
    override fun propagate(context: SearchContext): ComponentResult = lp.propagate(context)
    override fun check(context: SearchContext): ComponentCheck = if (finiteIntVars.any { variable ->
            context.intLowerBound(variable) == null ||
                context.intLowerBound(variable) != context.intUpperBound(variable)
        }) ComponentCheck.Indeterminate else lp.check(context)
    override fun shareSourcePredicates(context: SearchContext): ComponentResult {
        beginOperation()
        return try {
            if (operationStop()) return ComponentResult.Indeterminate
            val rows = agreementSourceRows(context)
                ?: return if (operationStop()) ComponentResult.Indeterminate else ComponentResult.Consistent
            for (row in rows) {
                if (row.columns.any { it in sourceRealCount until model.numRealVars }) continue
                lpStop.charge(row.columns.size.toLong() + 1L)
                if (operationStop()) return ComponentResult.Indeterminate
                val terms = row.columns.indices.map { index ->
                    val column = row.columns[index]
                    val source = if (column < model.numRealVars) SearchRealValue(column)
                        else SearchIntValue(column - model.numRealVars)
                    SourceBoundTerm(source, row.coefficients[index])
                }
                val atom = SourceBoundAtom.rationalSplit(context, terms, row.rhs, row.strict)
                    ?: return agreementDeclined(context)
                if (context.boolValue(atom.positive.literal ushr 1) != (atom.positive.literal and 1 == 0)) {
                    val result = context.publish(SearchDecision.Theory(atom.positive))
                    if (result != ComponentResult.Consistent) return result
                }
            }
            ComponentResult.Consistent
        } finally {
            endOperation(context)
        }
    }

    override fun checkSourceCoordinates(model: AssembledSearchModel, context: SearchContext): ComponentCheck {
        beginOperation()
        return try {
            if (outcome != ComponentCheck.Feasible || operationStop() || !context.consumeCheck()) {
                return ComponentCheck.Indeterminate
            }
            val point = ArrayList<BigFraction>(this.model.numRealVars + this.model.numIntVars)
            for (variable in 0 until this.model.numRealVars) {
                if (variable >= sourceRealCount) {
                    point += candidate?.get(variable) ?: return coordinateDeclined(context)
                    continue
                }
                lpStop.charge(1L)
                if (operationStop()) return ComponentCheck.Indeterminate
                val id = SourceRealId(variable)
                if (model.valueOf(SearchExactAlgebraicRealValue(id)) != null ||
                    model.valueOf(SearchExactRealExpressionValue(id)) != null
                ) return coordinateDeclined(context)
                point += model.valueOf(SearchExactRealValue(id)) ?: return coordinateDeclined(context)
            }
            for (variable in 0 until this.model.numIntVars) {
                lpStop.charge(1L)
                if (operationStop()) return ComponentCheck.Indeterminate
                point += model.valueOf(SearchExactIntValue(SourceIntId(variable)))?.asFraction()
                    ?: return coordinateDeclined(context)
            }
            lpStop.charge(point.size.toLong() + 1L)
            if (operationStop()) return ComponentCheck.Indeterminate
            if (!point.admittedSourcePoint()) return coordinateDeclined(context)
            val rows = agreementSourceRows(context) ?: return coordinateDeclined(context)
            lpStop.charge(rows.sumOf { it.columns.size.toLong() + 1L })
            if (!operationStop() && point.satisfiesSourceRows(rows, lpStop) && !operationStop()) {
                ComponentCheck.Feasible
            } else if (operationStop()) ComponentCheck.Indeterminate else coordinateDeclined(context)
        } finally {
            endOperation(context)
        }
    }

    private fun agreementSourceRows(context: SearchContext): List<ExactRationalInequality>? {
        lpStop.charge(model.numIntVars.toLong() + model.numRealVars + preparedRows.size + 1L)
        if (operationStop()) return null
        val bounded = node.withPublishedBounds(model.numIntVars, context::intLowerBound, context::intUpperBound)
        return reduction.sourceRows(bools, bounded, lpStop)
    }

    private fun coordinateDeclined(context: SearchContext): ComponentCheck {
        if (!operationStop()) context.executionControls.reportStop(SearchStopCause.RepresentationDecline)
        return ComponentCheck.Indeterminate
    }

    private fun agreementDeclined(context: SearchContext): ComponentResult {
        context.executionControls.reportStop(SearchStopCause.RepresentationDecline)
        return ComponentResult.Indeterminate
    }

    override fun nextBranch(context: SearchContext): List<SearchDecision>? {
        beginOperation()
        return try {
            if (operationStop()) {
                outcome = ComponentCheck.Indeterminate
                null
            } else {
                val branches = lp.nextBranch(context)
                if (operationStop()) {
                    outcome = ComponentCheck.Indeterminate
                    null
                } else {
                    branches
                }
            }
        } finally {
            endOperation(context)
        }
    }
    override fun retract(decisionLevel: Int) = lp.retract(decisionLevel)
    override fun onRestart(context: SearchContext) {
        floatCandidate = null
        pointHintValues = null
        lp.onRestart(context)
    }
    override fun close() {
        floatCandidate = null
        releasePartialPointHints()
        closeSourceLpOwners(listOfNotNull(if (lpDelegate.isInitialized()) lp else null, reduction))
    }

    private fun registerDisjunction(index: Int, context: SearchContext): List<DisjunctAtom>? {
        val rows = model.factors[index].linearRows
        val disjuncts = ArrayList<DisjunctAtom>(rows.size)
        for ((rowIndex, row) in rows.withIndex()) {
            val address = RowAddress(index, rowIndex)
            disjuncts += registerDisjunct(row, context, address) ?: return null
        }
        disjunctionAtoms[index] = disjuncts
        return disjuncts
    }

    private fun nameableDisjunct(row: LinearRow): Boolean {
        if (row.booleanVariables().isNotEmpty() || (0 until row.size).any { term ->
                val ref = row.ref(term)
                Term.isReal(ref) && Term.realVar(ref) >= sourceRealCount
            }) return false
        val comparison = row.exactComparison(model.numRealVars, truth = true) { false }
        return comparison.terms.isNotEmpty() && (comparison.op == LinearOp.LE || comparison.op == LinearOp.GE)
    }

    private fun registerDisjunct(row: LinearRow, context: SearchContext, address: RowAddress): DisjunctAtom? {
        if (!nameableDisjunct(row)) {
            val atom = context.registerAtom(
                NamedDisjunctDecision(disjunctOwner, address, holds = true),
                NamedDisjunctDecision(disjunctOwner, address, holds = false),
            ) ?: return null
            return DisjunctAtom(atom.positive, atom.negative, address)
        }
        val comparison = row.exactComparison(model.numRealVars, truth = true) { false }
        val terms = comparison.terms.map { (column, coefficient) ->
            val source = if (column < model.numRealVars) {
                SearchRealValue(column)
            } else {
                SearchIntValue(column - model.numRealVars)
            }
            SourceBoundTerm(source, coefficient)
        }
        // An integral activity has no value strictly between consecutive integers, so the row and its
        // complement are both non-strict and share their names with source integer branching.
        val integral = !comparison.strict && comparison.terms.all { (column, coefficient) ->
            column >= model.numRealVars && coefficient.den == BIG_ONE
        }
        return when (comparison.op) {
            LinearOp.LE -> {
                val split = if (integral) {
                    SourceBoundAtom.integerSplit(context, terms, comparison.bound)
                } else {
                    SourceBoundAtom.rationalSplit(context, terms, comparison.bound, comparison.strict)
                }
                split?.let { DisjunctAtom(it.positive, it.negative, address) }
            }

            LinearOp.GE -> {
                val split = if (integral) {
                    val ceiling = comparison.bound.negated().floor().negate()
                    SourceBoundAtom.integerSplit(context, terms, (ceiling - BIG_ONE).asFraction())
                } else {
                    SourceBoundAtom.rationalSplit(context, terms, comparison.bound, !comparison.strict)
                }
                split?.let { DisjunctAtom(it.negative, it.positive, address) }
            }

            // An equality is two bounds and a disequality a union of two, so neither is one exact pair.
            LinearOp.EQ, LinearOp.NE -> null
        }
    }

    // The shared engine sees each disjunct only as an atom, so the disjunction itself is held here; a
    // learned clause carrying it could be forgotten by a database reduction. Reports whether it implied a disjunct,
    // whose row reaches this component only when the engine delivers the implication.
    private fun enforceDisjunctions(context: SearchContext): ComponentResult {
        impliedDisjunct = false
        for (disjuncts in disjunctionAtoms.values) {
            if (disjuncts.any { context.truth(it.holds) == true }) continue
            val undecided = disjuncts.filter { context.truth(it.holds) == null }
            if (undecided.size > 1) continue
            val open = undecided.singleOrNull()
            val reason = disjunctionReason(disjuncts, open, context) ?: return ComponentResult.Indeterminate
            if (open == null) {
                smtStats?.observeConflict(reason)
                outcome = ComponentCheck.Infeasible(reason)
                return ComponentResult.Conflict(reason)
            }
            val implied = context.imply(open.holds.literal, reason)
            if (implied !is ComponentResult.Consistent) return implied
            impliedDisjunct = true
        }
        return ComponentResult.Consistent
    }

    private fun disjunctionReason(
        disjuncts: List<DisjunctAtom>,
        open: DisjunctAtom?,
        context: SearchContext,
    ): SearchExplanation? = context.explainAtoms(
        SearchAtomPremise.All(
            disjuncts.filter { it !== open }.map { SearchAtomPremise.Asserted(SearchDecision.Theory(it.fails)) },
        ),
        open?.let { SearchDecision.Theory(it.holds) },
    )

    private fun disjunctionBranch(context: SearchContext): List<SearchDecision>? {
        var registered = false
        for ((index, factor) in model.factors.withIndex()) {
            if (factor.linearForm !is LinearForm.Disjunction) continue
            val disjuncts = disjunctionAtoms[index] ?: run {
                val created = registerDisjunction(index, context)
                if (created == null) {
                    outcome = ComponentCheck.Indeterminate
                    return null
                }
                registered = true
                created
            }
            if (disjuncts.any { context.truth(it.holds) == true }) continue
            val first = disjuncts.firstOrNull { context.truth(it.holds) == null }
            val open = if (first != null) preferredDisjunct(index, disjuncts, first, context) else null
            if (outcome != null) return null
            if (open != null) return listOf(SearchDecision.Theory(open.holds), SearchDecision.Theory(open.fails))
            val reason = disjunctionReason(disjuncts, null, context)
            outcome = if (reason == null) {
                ComponentCheck.Indeterminate
            } else {
                smtStats?.observeConflict(reason)
                ComponentCheck.Infeasible(reason)
            }
            return null
        }
        if (registered) captureCompleteWitness(candidate)
        return null
    }

    private fun preferredDisjunct(
        factor: Int,
        disjuncts: List<DisjunctAtom>,
        first: DisjunctAtom,
        context: SearchContext,
    ): DisjunctAtom? {
        if (!mixedPointCompletion || !defaultDisjunctionHints || pointHintStop()) return first
        val exact = candidate
        val numerical = floatCandidate
        if (exact == null && numerical == null) return first
        observeCompleteStage("DISJUNCTION_GUIDE_ATTEMPT")
        for (disjunct in disjuncts) {
            operationStop.charge(1L)
            if (operationStop()) {
                outcome = ComponentCheck.Indeterminate
                return null
            }
            if (pointHintStop()) return first
            if (context.truth(disjunct.holds) != null) continue
            val comparison = disjunctHintComparison(factor, disjunct) ?: continue
            val holds = hintTruth(comparison) { column ->
                val value = exact?.getOrNull(column) ?: numerical?.getOrNull(column)?.let { BigFraction.ofDouble(it) }
                value?.takeIf(::hintScalar)
            }
            if (operationStop()) {
                outcome = ComponentCheck.Indeterminate
                return null
            }
            if (holds == true) {
                observeCompleteStage(if (exact == null) "DISJUNCTION_FLOAT_GUIDE" else "DISJUNCTION_EXACT_GUIDE")
                if (disjunct !== first) observeCompleteStage("DISJUNCTION_GUIDED_ALTERNATIVE")
                return disjunct
            }
        }
        if (operationStop()) {
            outcome = ComponentCheck.Indeterminate
            return null
        }
        return first
    }

    private fun disjunctHintComparison(factor: Int, disjunct: DisjunctAtom): ExactComparison? {
        val row = model.factors[factor].linearRows[disjunct.address.row]
        if (row.activator != LinearRow.ALWAYS) return null
        disjunct.row?.let { return sourceAtomHintComparison(it) }
        return exactForms[factor][disjunct.address.row].hintComparison(
            { variable -> bools.getOrNull(variable)?.takeIf { it != UNASSIGNED }?.let { it == TRUE } },
            ::hintScalar, ::hintPreflight,
        )
    }

    private fun sourceAtomHintComparison(atom: SourceBoundAtom): ExactComparison? {
        val width = atom.terms.size.toLong() + 1L
        operationStop.charge(width * width + 3L * width)
        if (operationStop() || pointHintStop()) return null
        val terms = system.sourceTerms(atom) ?: return null
        if (!hintScalar(atom.threshold) || terms.values.any { !hintScalar(it) }) return null
        return ExactComparison(terms, atom.threshold, if (atom.upper) LinearOp.LE else LinearOp.GE,
            atom.strict, terms.keys.any { it < model.numRealVars })
    }

    private fun disequalityBranches(
        comparison: ExactComparison,
        first: RegisteredTheoryDecision,
        second: RegisteredTheoryDecision,
    ): List<SearchDecision>? {
        val choices = listOf(first, second)
        if (!mixedPointCompletion || !defaultDisjunctionHints || pointHintStop()) {
            return choices.map { SearchDecision.Theory(it) }
        }
        val exact = candidate
        val numerical = floatCandidate
        if (exact == null && numerical == null) return choices.map { SearchDecision.Theory(it) }
        observeCompleteStage("DISEQUALITY_GUIDE_ATTEMPT")
        val value: (Int) -> BigFraction? = { column ->
            val coordinate = exact?.getOrNull(column) ?: numerical?.getOrNull(column)?.let { BigFraction.ofDouble(it) }
            coordinate?.takeIf(::hintScalar)
        }
        var preferred = first
        if (hintScalar(comparison.bound) && comparison.terms.values.all(::hintScalar)) {
            val different = hintTruth(comparison, value)
            if (different == false) observeCompleteStage("DISEQUALITY_POINT_ON_ROW")
            if (different == true) {
                for (choice in choices) {
                    operationStop.charge(1L)
                    if (operationStop() || pointHintStop()) break
                    val atom = (choice.payload as? SourceBoundPredicate)?.sourceBound
                    val side = if (atom != null) sourceAtomHintComparison(atom) else {
                        val private = choice.payload as? NamedPrivateDisequality
                        if (private == null || private.owner !== disjunctOwner) continue
                        ExactComparison(comparison.terms, comparison.bound, private.direction, true,
                            comparison.hasReals, comparison.ordered)
                    }
                    if (side != null && hintTruth(side, value) == true) {
                        preferred = choice
                        observeCompleteStage(
                            if (exact == null) "DISEQUALITY_FLOAT_GUIDE" else "DISEQUALITY_EXACT_GUIDE",
                        )
                        break
                    }
                }
            }
        }
        if (operationStop()) {
            outcome = ComponentCheck.Indeterminate
            return null
        }
        if (preferred !== first) observeCompleteStage("DISEQUALITY_GUIDED_ALTERNATIVE")
        return if (preferred === first) choices.map { SearchDecision.Theory(it) }
            else listOf(SearchDecision.Theory(second), SearchDecision.Theory(first))
    }

    private fun accept(decision: SearchDecision, context: SearchContext): ComponentResult {
        if (decision is SearchDecision.Bool && decision.literal ushr 1 !in bools.indices) {
            return ComponentResult.Consistent
        }
        assignment = null
        outcome = null
        candidate = null
        floatCandidate = null
        val priorHints = pointHintValues
        pointHintValues = null
        val wasDirty = dirty
        dirty = true
        when (decision) {
            is SearchDecision.Bool -> {
                val variable = decision.literal ushr 1
                if (incrementalSourceRows) changedSourceBooleans += variable
                if (completeTraversalObserved && bools[variable] == UNASSIGNED) {
                    unassignedBooleans--
                    smtStats?.observeCompleteSearchBooleans(bools.size, unassignedBooleans)
                }
                bools[variable] = if (decision.literal and 1 == 0) TRUE else FALSE
                boolLevels[variable] = context.decisionLevel
                node = node.copy(
                    retainedReduction = null,
                    disequalityDirections = emptyMap(),
                    directionPremises = emptyMap(),
                )
                if (variable !in arithmeticVariables && bools.any { it == UNASSIGNED }) {
                    nodesByLevel.put(context.decisionLevel, node)
                    dirty = wasDirty
                    if (!dirty) {
                        pointHintValues = priorHints
                        pointHintLevel = context.decisionLevel
                    }
                    return ComponentResult.Consistent
                }
            }

            is SearchDecision.Theory -> when (val payload = decision.decision) {
                is RegisteredTheoryDecision -> {
                    val disjunct = payload.payload as? NamedDisjunctDecision
                    if (disjunct != null && disjunct.owner === disjunctOwner) {
                        if (context.atomLiteral(decision) == null) return ComponentResult.Indeterminate
                        node = node.copy(
                            rowAssertions = node.rowAssertions + (disjunct.address to payload),
                            retainedReduction = null,
                        )
                        nodesByLevel.put(context.decisionLevel, node)
                        return ComponentResult.Consistent
                    }
                    val privateDirection = payload.payload as? NamedPrivateDisequality
                    if (privateDirection != null && privateDirection.owner === disjunctOwner) {
                        if (context.atomLiteral(decision) == null) return ComponentResult.Indeterminate
                        node = node.withDirection(privateDirection.address, privateDirection.direction).copy(
                            directionPremises = node.directionPremises +
                                (privateDirection.address to SearchAtomPremise.Asserted(decision)),
                            retainedReduction = null,
                        )
                        nodesByLevel.put(context.decisionLevel, node)
                        return ComponentResult.Consistent
                    }
                    val integer = payload.payload as? SearchIntegerBound
                    val atom = if (integer != null) {
                        SourceBoundAtom.side(
                            listOf(SourceBoundTerm(SearchIntValue(integer.variable), BigFraction.ONE)),
                            integer.exactThreshold().asFraction(),
                            integer.upper,
                        ) ?: return ComponentResult.Indeterminate
                    } else {
                        (payload.payload as? SourceBoundPredicate)?.sourceBound ?: return ComponentResult.Consistent
                    }
                    if (context.atomLiteral(decision) == null) return ComponentResult.Indeterminate
                    if (system.sourceTerms(atom) == null) return ComponentResult.Indeterminate
                    branchNames[atom] = decision
                    val branchRows = atom.sourceRows(model.numRealVars)
                    val retained = node.retainedReduction?.takeIf { reduced ->
                        reduced.budget.run(
                            branchRows,
                            model.numRealVars + model.numIntVars,
                            operationStop,
                        ) {
                            branchRows.all { row ->
                                reduced.system.transform(row).columns.all(reduced.system::boundedColumn)
                            }
                        } == true
                    }
                    node = node.copy(sourceBranches = node.sourceBranches + atom, retainedReduction = retained)
                    if (!system.assertAtom(atom, SearchAtomPremise.Asserted(decision))) {
                        return ComponentResult.Indeterminate
                    }
                }
            }

            is SearchDecision.IntAtMost, is SearchDecision.IntAtLeast, is SearchDecision.IntEqual -> {
                node = node.copy(retainedReduction = null)
            }
        }
        nodesByLevel.put(context.decisionLevel, node)
        return ComponentResult.Consistent
    }

    private fun installSource(): Boolean {
        if (sourceInstalled) return true
        return system.install().also { sourceInstalled = it }
    }

    private fun assertSource(context: SearchContext): Boolean {
        val previousVisits = sourceRowVisits
        return try {
            assertSourceWithin(context)
        } finally {
            if (completeTraversalObserved && incrementalSourceRows) {
                smtStats?.observeCompleteSearchStage("INCREMENTAL_SOURCE_ROW", sourceRowVisits - previousVisits)
            }
        }
    }

    private fun assertSourceWithin(context: SearchContext): Boolean {
        if (!installSource() || !lp.atLevel(context.decisionLevel, operationStop)) return false
        val rootFixings = if (incrementalSourceRows && context.decisionLevel == 0) {
            sourceRootFixings() ?: return false
        } else null
        val refreshRoot = rootFixings != null && rootFixings != assertedRootFixings
        val selected = sourceRowsToAssert(refreshRoot) ?: return false
        val disequalities = ArrayList<Triple<RowAddress, ExactComparison, SearchAtomPremise>>()
        val comparisons = ArrayList<PremisedSmtComparison>()
        val indices = ArrayList<Int>()
        for (index in selected) {
            if (incrementalSourceRows) operationStop.charge(1)
            if (operationStop()) return false
            sourceRowVisits++
            val prepared = preparedRows[index]
            // Resolved source inputs stay fixed until the LP scope containing their assertion is popped.
            // A changed root fixing can simplify an already asserted source expression.
            if (sourceAssertionLevels[index] >= 0 &&
                (context.decisionLevel > 0 || (incrementalSourceRows && !refreshRoot))
            ) continue
            val address = prepared.address
            val asserted = node.rowAssertions[address]
            if (prepared.disjunction && asserted == null) continue
            val truth = node.rowTruth(address, prepared.row, bools) ?: continue
            val comparison = prepared.form.comparison(truth) { bools[it] == TRUE }
            val leaves = prepared.booleans.map { variable ->
                SearchAtomPremise.Asserted(SearchDecision.Bool(Lit.make(variable, bools[variable] == TRUE)))
            }.toMutableList<SearchAtomPremise>()
            if (asserted != null) leaves += SearchAtomPremise.Asserted(SearchDecision.Theory(asserted))
            val premise = SearchAtomPremise.All(leaves)
            if (comparison.op == LinearOp.NE) {
                disequalities += Triple(address, comparison, premise)
            } else {
                comparisons += PremisedSmtComparison(comparison, premise)
                indices += index
            }
        }
        if (!system.assertComparisons(comparisons, operationStop) { index ->
                val source = indices[index]
                if (sourceAssertionLevels[source] < 0 && context.decisionLevel > 0) scopedSourceAssertions.add(source)
                sourceAssertionLevels[source] = context.decisionLevel
            }
        ) return false
        if (lp.state?.conflict != null) return true
        for (atom in node.sourceBranches) {
            val premise = SearchAtomPremise.Asserted(branchNames[atom] ?: return false)
            if (!system.assertAtom(atom, premise)) return false
        }
        when (assertPublishedBounds(context)) {
            is LpBoundBatchResult.Declined -> return false
            is LpBoundBatchResult.Conflict -> return true
            is LpBoundBatchResult.Applied -> Unit
        }
        if (!assertDisequalities(disequalities)) return false
        if (incrementalSourceRows) pendingSourceRows.removeAll(selected.toSet())
        // Fixings discovered by this pass must trigger substitution in a later pass.
        if (rootFixings != null) assertedRootFixings = rootFixings
        nodesByLevel.put(context.decisionLevel, node)
        return true
    }

    private fun sourceRootFixings(): List<BigFraction?>? {
        val state = lp.state ?: return null
        val fixed = ArrayList<BigFraction?>(model.numRealVars + model.numIntVars)
        for (column in 0 until model.numRealVars + model.numIntVars) {
            operationStop.charge(1)
            if (operationStop()) return null
            val lower = state.activeSide(column, false)?.side
            val upper = state.activeSide(column, true)?.side
            fixed += if (lower != null && upper != null && !lower.strict && !upper.strict &&
                lower.number.value == upper.number.value && lower.premises == null && upper.premises == null
            ) lower.number.value else null
        }
        return fixed
    }

    private fun sourceRowsToAssert(refreshRoot: Boolean): Iterable<Int>? {
        if (!incrementalSourceRows) return preparedRows.indices
        if (sourceRowsByBoolean == null) {
            val dependencies = LinkedHashMap<Int, MutableList<Int>>()
            val recurring = ArrayList<Int>()
            for ((index, prepared) in preparedRows.withIndex()) {
                operationStop.charge(2L + prepared.booleans.size)
                if (operationStop()) return null
                for (variable in prepared.booleans) dependencies.getOrPut(variable) { ArrayList() } += index
                if (prepared.disjunction || prepared.row.relation == LinearOp.EQ ||
                    prepared.row.relation == LinearOp.NE) recurring += index
            }
            operationStop.charge(preparedRows.size.toLong() + dependencies.size + recurring.size + 1L)
            if (operationStop()) return null
            sourceRowsByBoolean = dependencies
            recurringSourceRows += recurring
            pendingSourceRows += preparedRows.indices
        }
        val dependencies = requireNotNull(sourceRowsByBoolean)
        for (variable in changedSourceBooleans) {
            val rows = dependencies[variable].orEmpty()
            operationStop.charge(rows.size.toLong() + 1L)
            if (operationStop()) return null
            sourceRowTouches += rows.size
            pendingSourceRows += rows
        }
        changedSourceBooleans.clear()
        // Disjunction choices and disequality sides can change without assigning a source Boolean.
        operationStop.charge(recurringSourceRows.size.toLong() + 1L)
        if (operationStop()) return null
        pendingSourceRows += recurringSourceRows
        val count = if (refreshRoot) preparedRows.size else pendingSourceRows.size
        operationStop.charge(count.toLong() * (1L + Int.SIZE_BITS - count.countLeadingZeroBits()))
        if (operationStop()) return null
        return if (refreshRoot) preparedRows.indices.toList() else pendingSourceRows.sorted()
    }

    private fun assertDisequalities(
        disequalities: List<Triple<RowAddress, ExactComparison, SearchAtomPremise>>,
    ): Boolean {
        if (disequalities.isEmpty()) return true
        val directions = node.disequalityDirections.toMutableMap()
        val premises = node.directionPremises.toMutableMap()
        var changed = false
        try {
            for ((address, comparison, premise) in disequalities) {
                val side = if (comparison.terms.isEmpty()) {
                    DerivedDisequalitySide(LinearOp.LE, SearchAtomPremise.All(emptyList()))
                } else {
                    directions[address]?.let {
                        DerivedDisequalitySide(it, premises.getValue(address))
                    } ?: system.disequalitySide(comparison) ?: continue
                }
                directions[address] = side.direction
                premises[address] = side.premise
                changed = true
                if (!system.assertComparison(
                        comparison,
                        side.direction,
                        SearchAtomPremise.All(listOf(premise, side.premise)),
                    )
                ) return false
            }
            return true
        } finally {
            // Retain the attempted prefix on interruption without mutating an ancestor's snapshot.
            if (changed) node = node.copy(disequalityDirections = directions, directionPremises = premises)
        }
    }

    private fun assertPublishedBounds(context: SearchContext): LpBoundBatchResult {
        if (model.numIntVars == 0) return lp.assertBounds(emptyList())
        val bounds = ArrayList<LpPremisedBound>()
        for (integer in 0 until model.numIntVars) {
            if (operationStop()) return LpBoundBatchResult.Declined(0)
            val column = model.numRealVars + integer
            context.intLowerBound(integer)?.let { value ->
                bounds += LpPremisedBound(
                    column, false, ExactLpSide(ExactLpNumber.of(value)), context.intLowerBoundPremise(integer),
                )
            }
            context.intUpperBound(integer)?.let { value ->
                bounds += LpPremisedBound(
                    column, true, ExactLpSide(ExactLpNumber.of(value)), context.intUpperBoundPremise(integer),
                )
            }
        }
        return lp.assertBounds(bounds)
    }

    private fun relax(context: SearchContext): ComponentResult {
        beginOperation()
        return try {
            relaxWithin(context)
        } finally {
            endOperation(context)
        }
    }

    private fun beginOperation() {
        operationBudgetExhausted = false
        context?.executionControls?.beginOperation()
        operationStop = operationAllowance?.invoke(parentStop)
            ?: checkNotNull(context).executionControls.operationAllowance(parentStop)
        lpStop.begin()
    }

    private fun endOperation(context: SearchContext) {
        lpStop.end()
        if (operationStop() && !context.cancelled()) {
            operationBudgetExhausted = true
            linearOperationStops++
            context.executionControls.reportStop(SearchStopCause.OperationAllowance)
        }
        operationStop = parentStop
    }

    private fun differenceConflict(
        result: RealDifferenceSystem.Result.Infeasible,
        context: SearchContext,
    ): ComponentResult? {
        if (node.sourceBranches.isNotEmpty() ||
            solveContext.certificationPolicy !== ProductionLpCertificationPolicy
        ) return null
        val clause = result.clause ?: return null
        if (clause.any { literal ->
                context.boolValue(Lit.variable(literal))?.let { Lit.evaluate(literal, it) } != false
            }
        ) return null
        if (operationStop()) return ComponentResult.Indeterminate
        val explanation = SearchExplanation(clause)
        dirty = false
        candidate = null
        assignment = null
        pointHintValues = null
        smtStats?.observeConflict(explanation)
        outcome = ComponentCheck.Infeasible(explanation)
        return ComponentResult.Conflict(explanation)
    }

    private fun relaxWithin(context: SearchContext): ComponentResult {
        if (operationStop()) return ComponentResult.Indeterminate
        if (!dirty) return ComponentResult.Consistent
        floatCandidate = null
        pointHintValues = null
        // Root publications precede their delivery to components during shared initialization.
        if (context.decisionLevel == 0 && bools.any { it == UNASSIGNED } &&
            bools.indices.all { context.boolValue(it) != null }
        ) return ComponentResult.Consistent
        val enforced = enforceDisjunctions(context)
        if (enforced !is ComponentResult.Consistent) return enforced
        // The implied row is not asserted yet; solving now would only be repeated once it is delivered.
        if (impliedDisjunct) return ComponentResult.Consistent
        if (!graphFirst && bools.any { it == UNASSIGNED } && arithmeticRows.none {
                it.truthUnder(bools) != null
            }
        ) {
            return ComponentResult.Consistent
        }
        var checked = false
        // Partial graph consistency needs no numerical guide; complete points still check source rows.
        if (graphFirst && node.sourceBranches.isEmpty()) {
            if (!context.consumeCheck()) return ComponentResult.Indeterminate
            checked = true
            when (val result = realDifference?.check(bools, operationStop)) {
                RealDifferenceSystem.Result.Interrupted -> return ComponentResult.Indeterminate
                is RealDifferenceSystem.Result.Infeasible -> {
                    differenceConflict(result, context)?.let { return it }
                }
                is RealDifferenceSystem.Result.Feasible -> {
                    if (bools.any { it == UNASSIGNED }) {
                        if (pointHintRows != null) {
                            operationStop.charge(model.numRealVars.toLong() + 1L)
                            if (!operationStop()) capturePointHints(result.point, context, graph = true)
                        }
                        if (operationStop()) return ComponentResult.Indeterminate
                        candidate = null
                        assignment = null
                        outcome = null
                        dirty = false
                        return ComponentResult.Consistent
                    }
                    candidate = result.point
                    acceptWitness(result.point)?.let {
                        if (operationStop()) return ComponentResult.Indeterminate
                        assignment = it
                        outcome = ComponentCheck.Feasible
                        dirty = false
                        return ComponentResult.Consistent
                    }
                }
                else -> Unit
            }
        }
        val asserted = measurePartialLinearPhase({ partialLinearAssertionNs += it }) { assertSource(context) }
        if (!asserted || operationStop()) {
            return ComponentResult.Indeterminate
        }
        if (partialLinearPropagation && bools.any { it == UNASSIGNED }) {
            val propagated = derivePartialLinearImplications(context, applyLocally = true)
            if (operationStop()) return ComponentResult.Indeterminate
            if (propagated.deductions.isNotEmpty()) {
                partialLinearDeferrals++
                return ComponentResult.Consistent
            }
        }
        if (!checked && !context.consumeCheck()) return ComponentResult.Indeterminate
        val complete = completeTraversalObserved && unassignedBooleans == 0
        if (complete) smtStats?.observeCompleteSearchLinearCheck()
        val result = measurePartialLinearPhase({ partialLinearNumericalNs += it }) {
            lp.solve(sparsePointRecovery = true, claim = LpClaim.FEASIBLE_COMPLETION)
        }
        if (complete) {
            val key = if (result == null) "NO_RESULT" else listOf(
                result.verdict.name,
                when (result.float?.optimal) {
                    true -> "FLOAT_OPTIMAL"
                    false -> "FLOAT_PARTIAL"
                    null -> "NO_FLOAT"
                },
                if (result.exactPrimal == null) "NO_POINT" else "EXACT_POINT",
                result.reconstruction?.let { "RECONSTRUCTION_${it.decline?.name ?: "NO_DECLINE"}" }
                    ?: "NO_RECONSTRUCTION",
                result.exactPoint?.let { "RECOVERY_${it.decline?.name ?: "NO_DECLINE"}" } ?: "NO_RECOVERY",
                result.refinement?.let { "REFINEMENT_${it.decline?.name ?: "NO_DECLINE"}" } ?: "NO_REFINEMENT",
                result.continuation?.let { "CONTINUATION_${it.decline?.name ?: "NO_DECLINE"}" } ?: "NO_CONTINUATION",
            ).joinToString("_")
            smtStats?.observeCompleteSearchLinearOutcome(key)
        }
        if (result == null) return ComponentResult.Indeterminate
        if (operationStop()) return ComponentResult.Indeterminate
        dirty = false
        candidate = result.exactPrimal?.take(model.numRealVars + model.numIntVars)
        if (result.verdict == LpVerdict.INFEASIBLE) {
            val explanation = lp.explainConflict(result.conflictSupport, context)
            smtStats?.observeConflict(explanation)
            outcome = ComponentCheck.Infeasible(explanation)
            return ComponentResult.Conflict(explanation)
        }
        if (complete && candidate != null) smtStats?.observeCompleteSearchLinearPoint()
        if (bools.any { it == UNASSIGNED }) {
            candidate?.let { capturePointHints(it, context) }
        } else if (candidate == null && mixedPointCompletion && result.float?.optimal == true &&
            model.numIntVars > 0 && model.numRealVars > 0 &&
            solveContext.certificationPolicy === ProductionLpCertificationPolicy
        ) {
            val coordinates = model.numRealVars + model.numIntVars
            lpStop.charge(coordinates.toLong())
            if (!operationStop() && result.float.primal.size >= coordinates) {
                floatCandidate = result.float.primal.copyOf(coordinates)
            }
        }
        if (operationStop()) return ComponentResult.Indeterminate
        captureCompleteWitness(candidate)
        // Source integer branching requires an independently checked continuous witness.
        return ComponentResult.Consistent
    }

    private fun observeCompleteStage(stage: String) {
        if (completeTraversalObserved) smtStats?.observeCompleteSearchStage(stage)
    }

    private fun branch(context: SearchContext): List<SearchDecision>? {
        observeCompleteStage("BRANCH")
        val finite = finiteIntVars.firstOrNull { variable ->
            context.intLowerBound(variable) == null ||
                context.intLowerBound(variable) != context.intUpperBound(variable)
        }
        if (finite != null) {
            observeCompleteStage("FINITE_PENDING")
            return finitePointBranch(finite, context)
        }
        if (bools.any { it == UNASSIGNED } || outcome != null) return null
        observeCompleteStage("FINITE_BOOLEAN_COMPLETE")
        if (operationStop() || !context.consumeCheck()) {
            outcome = ComponentCheck.Indeterminate
            return null
        }
        disjunctionBranch(context)?.let {
            observeCompleteStage("DISJUNCTION_BRANCH")
            return it
        }
        if (outcome != null) return null
        observeCompleteStage("DISJUNCTION_BRANCHER_DONE")
        node.nextDisequality(model, disjunctionAtoms, bools)?.let { address ->
            observeCompleteStage("DISEQUALITY_BRANCH")
            val row = model.factors[address.factor].linearRows[address.row]
            val truth = checkNotNull(node.rowTruth(address, row, bools))
            val comparison = exactForms[address.factor][address.row].comparison(truth) {
                bools[it] == TRUE
            }
            if (comparison.terms.keys.any { it in sourceRealCount until model.numRealVars }) {
                observeCompleteStage("DISEQUALITY_PRIVATE_BRANCH")
                val split = context.registerAtom(
                    NamedPrivateDisequality(disjunctOwner, address, LinearOp.LE),
                    NamedPrivateDisequality(disjunctOwner, address, LinearOp.GE),
                )
                if (split == null) {
                    observeCompleteStage("DISEQUALITY_REGISTER_DECLINED")
                    outcome = ComponentCheck.Indeterminate
                }
                return split?.let { disequalityBranches(comparison, it.positive, it.negative) }
            }
            observeCompleteStage("DISEQUALITY_SOURCE_BRANCH")
            val terms = comparison.terms.map { (column, coefficient) ->
                val source = if (column < model.numRealVars) {
                    SearchRealValue(column)
                } else {
                    SearchIntValue(column - model.numRealVars)
                }
                SourceBoundTerm(source, coefficient)
            }
            val split = if (comparison.hasReals) {
                SourceBoundAtom.rationalSplit(context, terms, comparison.bound, strict = true)
            } else {
                SourceBoundAtom.integerSplit(context, terms, comparison.bound - BigFraction.ONE)
            }
            if (split == null) {
                observeCompleteStage("DISEQUALITY_REGISTER_DECLINED")
                outcome = ComponentCheck.Indeterminate
            }
            return split?.let { disequalityBranches(comparison, it.negative, it.positive) }
        }
        observeCompleteStage("SOURCE_BRANCHERS_DONE")
        if (candidate == null) {
            val retained = floatCandidate
            floatCandidate = null
            if (retained != null) {
                val rows = agreementSourceRows(context)
                if (rows != null) {
                    candidate = completeMixedPoint(model.numRealVars, model.numIntVars, retained, rows, lpStop,
                        onStage = ::observeCompleteStage,
                        realCompletion = { columns, residual, token ->
                            reduction.completeReals(columns, residual, token, ::observeCompleteStage)
                        })
                } else {
                    observeCompleteStage("RECOVERY_ROWS_UNAVAILABLE")
                }
                if (!operationStop()) captureCompleteWitness(candidate)
                if (outcome != null) return null
            } else {
                observeCompleteStage("RECOVERY_NO_FLOAT")
            }
        }
        if (candidate == null || operationStop()) {
            outcome = ComponentCheck.Indeterminate
            return null
        }
        val reduced = node.retainedReduction ?: reduction.reduce(
            bools,
            node.withPublishedBounds(model.numIntVars, context::intLowerBound, context::intUpperBound),
            operationStop,
            smtStats,
        )
        if (reduced == ExactLiraReduction.Infeasible) {
            outcome = ComponentCheck.Infeasible()
            smtStats?.observeConflict(null)
            return null
        }
        if (reduced !is ExactLiraReduction.Bounded) {
            if (!operationStop() && context.executionControls.canCommitDecision()) {
                sourceIntegerSplit(requireNotNull(candidate), context)?.let { return it }
            }
            outcome = ComponentCheck.Indeterminate
            return null
        }
        node = node.copy(retainedReduction = reduced)
        nodesByLevel.put(context.decisionLevel, node)
        candidate?.let { point ->
            for (integer in 0 until reduced.system.integerColumns) {
                if (!reduced.system.boundedColumn(reduced.system.realColumns + integer)) continue
                val coefficients = reduced.system.transformedIntegerCoefficients(integer)
                var value = BigFraction.ZERO
                for (entry in coefficients.index.indices) {
                    value +=
                        coefficients.value[entry].asFraction() * point[model.numRealVars + coefficients.index[entry]]
                }
                if (!value.isInteger()) return registeredSplit(reduced, integer, value, context)
            }
        }
        val extension = (0 until reduced.system.realColumns + reduced.system.integerColumns)
            .any { !reduced.system.boundedColumn(it) }
        if (!extension) {
            outcome = ComponentCheck.Indeterminate
            return null
        }
        when (val bounded = reduced.solver.solve(node, node.searchedBranches(disjunctionAtoms), operationStop)) {
            is ExactReducedSearchResult.Split -> {
                return registeredSplit(reduced, bounded.integer, bounded.floor.asFraction(), context)
            }

            ExactReducedSearchResult.Infeasible -> {
                outcome = ComponentCheck.Infeasible()
                smtStats?.observeConflict(null)
            }

            ExactReducedSearchResult.Interrupted -> outcome = ComponentCheck.Indeterminate

            is ExactReducedSearchResult.Found -> {
                candidate = bounded.sourceValues
                assignment = acceptWitness(bounded.sourceValues)
                outcome = if (assignment != null && !operationStop()) {
                    ComponentCheck.Feasible
                } else {
                    ComponentCheck.Indeterminate
                }
            }
        }
        return null
    }

    private fun finitePointBranch(variable: Int, context: SearchContext): List<SearchDecision>? {
        if (dirty || hasSourceDisjunctions ||
            (outcome != null && outcome != ComponentCheck.Feasible)
        ) return null
        val point = candidate ?: return null
        lpStop.charge(model.numIntVars.toLong() + model.numBoolVars + preparedRows.size + 1L)
        if (operationStop() || bools.any { it == UNASSIGNED } ||
            node.nextDisequality(model, disjunctionAtoms, bools) != null || !context.consumeCheck()
        ) return null
        sourceIntegerSplit(point, context)?.let { return it }
        val value = point[model.numRealVars + variable]
        if (!value.isInteger() || !value.num.fitsLong()) return null
        val preferred = value.num.toLongExact()
        val lower = context.intLowerBound(variable) ?: return null
        val upper = context.intUpperBound(variable) ?: return null
        if (preferred !in lower..upper) return null
        return buildList {
            add(SearchDecision.IntEqual(variable, preferred))
            if (preferred > lower) add(SearchDecision.IntAtMost(variable, preferred - 1))
            if (preferred < upper) add(SearchDecision.IntAtLeast(variable, preferred + 1))
        }
    }

    private fun registeredSplit(
        reduced: ExactLiraReduction.Bounded,
        integer: Int,
        value: BigFraction,
        context: SearchContext,
    ): List<SearchDecision>? {
        val coefficients = reduced.system.transformedIntegerCoefficients(integer)
        val terms = coefficients.index.indices.map {
            SourceBoundTerm(SearchIntValue(coefficients.index[it]), coefficients.value[it].asFraction())
        }
        val split = SourceBoundAtom.integerSplit(context, terms, value)
        if (split == null) outcome = ComponentCheck.Indeterminate
        return split?.alternatives()
    }

    private fun sourceIntegerSplit(point: List<BigFraction>, context: SearchContext): List<SearchDecision>? {
        if (point.size != model.numRealVars + model.numIntVars) return null
        for (integer in 0 until model.numIntVars) {
            val value = point[model.numRealVars + integer]
            if (value.isInteger()) continue
            if (integer in finiteIntVars) {
                val floor = value.num / value.den -
                    if (value.num < BIG_ZERO && value.num % value.den != BIG_ZERO) BIG_ONE else BIG_ZERO
                if (!floor.fitsLong() || floor >= bigIntOf(Long.MAX_VALUE)) return null
                val threshold = floor.toLongExact()
                val lower = context.intLowerBound(integer) ?: return null
                val upper = context.intUpperBound(integer) ?: return null
                if (threshold < lower || threshold >= upper) return null
                return listOf(SearchDecision.IntAtMost(integer, threshold),
                    SearchDecision.IntAtLeast(integer, threshold + 1))
            }
            return SourceBoundAtom.integerSplit(
                context,
                listOf(SourceBoundTerm(SearchIntValue(integer), BigFraction.ONE)),
                value,
            )?.alternatives()
        }
        return null
    }

    private fun captureCompleteWitness(point: List<BigFraction>?) {
        if (point == null || operationStop() || bools.any { it == UNASSIGNED } ||
            !node.selectsEveryDisjunction(model, disjunctionAtoms) ||
            node.nextDisequality(model, disjunctionAtoms, bools) != null
        ) return
        acceptWitness(point)?.let {
            assignment = it
            outcome = ComponentCheck.Feasible
        }
    }

    private fun acceptWitness(point: List<BigFraction>): ExactLiraAssignment? {
        if (point.size != model.numRealVars + model.numIntVars ||
            (0 until model.numIntVars).any { !point[model.numRealVars + it].isInteger() }
        ) {
            return null
        }
        for (variable in 0 until model.numIntVars) {
            val domain = model.intDomainOrNull(variable) ?: continue
            val value = point[model.numRealVars + variable].num
            if (!value.fitsLong() || value.toLongExact() !in domain) return null
        }
        val token = lpStop
        val rows = reduction.sourceRows(bools, node, token) ?: return null
        if (!point.satisfiesSourceRows(rows, token)) return null
        val strict = rows.any { it.strict }
        val wide = rows.hasWideIntegerData() || point.any { it.num.abs() > WIDE_INTEGER_LIMIT }
        smtStats?.observeWitnessCandidate(strict, wide)
        return ExactLiraAssignment(
            bools.toCompleteValues(),
            Array(model.numIntVars) { point[model.numRealVars + it].num },
            point.take(sourceRealCount),
        ).also {
            smtStats?.observeWitnessAccepted(strict, wide)
        }
    }

    private fun retractSource(decisionLevel: Int) {
        var invalidated = 0L
        while (!scopedSourceAssertions.isEmpty()) {
            val index = scopedSourceAssertions.last()
            if (sourceAssertionLevels[index] <= decisionLevel) break
            scopedSourceAssertions.truncateTo(scopedSourceAssertions.size - 1)
            sourceAssertionLevels[index] = -1
            if (incrementalSourceRows) {
                pendingSourceRows += index
                sourceRowInvalidations++
                invalidated++
            }
        }
        if (completeTraversalObserved && incrementalSourceRows) {
            smtStats?.observeCompleteSearchStage("INCREMENTAL_SOURCE_INVALIDATION", invalidated)
        }
        for (variable in bools.indices) {
            if (boolLevels[variable] > decisionLevel) {
                if (incrementalSourceRows) changedSourceBooleans += variable
                if (completeTraversalObserved) unassignedBooleans++
                bools[variable] = UNASSIGNED
                boolLevels[variable] = -1
            }
        }
        nodesByLevel.removeKeysAbove(decisionLevel)
        node = nodesByLevel.valueAtMaxKey() ?: root
        assignment = null
        outcome = null
        candidate = null
        floatCandidate = null
        pointHintValues = null
        dirty = true
    }

    internal companion object {
        fun prepareSource(fragment: Problem, stop: Cancellation): PreparedExactLiraComponentFactory? {
            val source = sharedExactLiraSource(fragment, stop) ?: return null
            val factory = PreparedExactLiraComponentFactory { contribution, finite ->
                ExactLiraSearchComponent(source, contribution, finite)
            }
            return factory.takeUnless { stop() }
        }
    }

    /** Owned exact mixed assignment contributed by this component. */
    val assignmentKey = SearchModelKey(ExactLiraAssignment::class)

    /** Owned rational assignment for a source without integer coordinates. */
    val realAssignmentKey = SearchModelKey(ExactLraAssignment::class)

    override fun contributeModel(model: SearchModel, context: SearchContext) {
        if (outcome != ComponentCheck.Feasible || context.cancelled()) return
        assignment?.let { value ->
            model.contribute(assignmentKey, value)
            if (this.model.numIntVars == 0) {
                model.contribute(realAssignmentKey, ExactLraAssignment(value.bools, value.reals))
            }
            modelContribution?.invoke(value, model)
        }
    }
}

internal class PreparedExactLiraComponentFactory(
    private val openComponent: (((ExactLiraAssignment, SearchModel) -> Unit)?, IntArray) -> ExactLiraSearchComponent,
) {
    fun open(
        modelContribution: ((ExactLiraAssignment, SearchModel) -> Unit)? = null,
    ): ExactLiraSearchComponent = openComponent(modelContribution, intArrayOf())

    fun open(
        finiteIntegerVariables: IntArray,
        modelContribution: ((ExactLiraAssignment, SearchModel) -> Unit)? = null,
    ): ExactLiraSearchComponent = openComponent(modelContribution, finiteIntegerVariables.copyOf())
}

internal fun prepareExactLiraSource(
    fragment: Problem,
    stop: Cancellation,
): PreparedExactLiraComponentFactory? = ExactLiraSearchComponent.prepareSource(fragment, stop)

private class PreparedExactLiraSource(
    val model: Problem,
    val exactForms: List<List<ExactRowForm>>,
    val preparedRows: List<PreparedSourceRow>,
    val arithmeticRows: List<LinearRow>,
    val arithmeticVariables: Set<Int>,
    val validated: Boolean,
)

private fun legacyExactLiraSource(model: Problem): PreparedExactLiraSource {
    val exactForms = model.factors.map { factor -> factor.linearRows.map { it.exactForm(model.numRealVars) } }
    val preparedRows = model.factors.flatMapIndexed { factorIndex, factor ->
        factor.linearRows.mapIndexed { index, row ->
            PreparedSourceRow(
                RowAddress(factorIndex, index),
                row,
                exactForms[factorIndex][index],
                row.booleanVariables().toIntArray(),
                factor.linearForm is LinearForm.Disjunction,
            )
        }
    }
    val arithmeticRows = model.factors.flatMap { it.linearRows }.filter { row ->
        (0 until row.size).any { !Term.isBool(row.ref(it)) }
    }
    val arithmeticVariables = arithmeticRows.flatMap { it.booleanVariables() }.toSet()
    return PreparedExactLiraSource(
        model, exactForms, preparedRows, arithmeticRows, arithmeticVariables, validated = false,
    )
}

private fun sharedExactLiraSource(model: Problem, stop: Cancellation): PreparedExactLiraSource? {
    fun charge(units: Long): Boolean {
        if (stop()) return false
        stop.charge(units)
        return !stop()
    }
    if (!charge(1) || (model.numIntVars == 0 && model.numRealVars == 0)) return null
    val exactForms = ArrayList<List<ExactRowForm>>()
    val preparedRows = ArrayList<PreparedSourceRow>()
    val arithmeticRows = ArrayList<LinearRow>()
    val arithmeticVariables = LinkedHashSet<Int>()
    for ((factorIndex, factor) in model.factors.withIndex()) {
        if (!charge(1)) return null
        val form = factor.linearForm
        if (form !is LinearForm.Conjunction && form !is LinearForm.Disjunction) return null
        val forms = ArrayList<ExactRowForm>()
        for ((rowIndex, row) in factor.linearRows.withIndex()) {
            if (!charge(1)) return null
            when (val constants = row.constants) {
                is IntegralConstants -> Unit
                is RealConstants -> {
                    if (!constants.bound.isFinite()) return null
                    for (index in constants.intCoefficients.indices) {
                        if (!charge(1) || !constants.intCoefficients.at(index).isFinite()) return null
                    }
                    for (index in constants.realCoefficients.indices) {
                        if (!charge(1) || !constants.realCoefficients.at(index).isFinite()) return null
                    }
                }
            }
            val booleans = LinkedHashSet<Int>()
            if (row.activator != LinearRow.ALWAYS) booleans += row.activator
            var arithmetic = false
            for (index in 0 until row.size) {
                if (!charge(1)) return null
                val reference = row.ref(index)
                if (Term.isBool(reference)) {
                    booleans += Lit.variable(Term.lit(reference))
                } else {
                    arithmetic = true
                }
            }
            // Exact rational conversion and its owned term buffers are one atomic row operation.
            if (!charge(4L * row.size + 4L)) return null
            val exact = row.exactForm(model.numRealVars)
            if (stop()) return null
            forms += exact
            preparedRows += PreparedSourceRow(
                RowAddress(factorIndex, rowIndex), row, exact, booleans.toIntArray(), form is LinearForm.Disjunction,
            )
            if (arithmetic) {
                arithmeticRows += row
                for (variable in booleans) {
                    if (!charge(1)) return null
                    arithmeticVariables += variable
                }
            }
        }
        if (!charge(forms.size.toLong() + 1L)) return null
        exactForms += forms.toList()
    }
    val copies = exactForms.size.toLong() + preparedRows.size + arithmeticRows.size + arithmeticVariables.size + 1L
    if (!charge(copies)) return null
    val source = PreparedExactLiraSource(
        model, exactForms.toList(), preparedRows.toList(), arithmeticRows.toList(), arithmeticVariables.toSet(),
        validated = true,
    )
    return source.takeUnless { stop() }
}

private const val UNASSIGNED = -1
private const val DECLINED_HINT = -2
private const val MAX_POINT_HINT_ENTRIES = 65_536
private const val MAX_POINT_HINT_BITS = 4096
private const val FALSE = 0
private const val TRUE = 1

private fun BooleanArray.toStates(): IntArray = IntArray(size) { if (this[it]) TRUE else FALSE }

private fun IntArray.toCompleteValues(): BooleanArray = BooleanArray(size) { variable ->
    when (this[variable]) {
        TRUE -> true
        FALSE -> false
        else -> error("exact LIRA witness requested before Boolean assignment was complete")
    }
}

private fun LinearRow.truthUnder(bools: IntArray): Boolean? {
    for (k in 0 until size) {
        val reference = ref(k)
        if (Term.isBool(reference) && bools[Lit.variable(Term.lit(reference))] == UNASSIGNED) return null
    }
    if (activator == LinearRow.ALWAYS) return true
    return when (bools[activator]) {
        TRUE -> true
        FALSE -> false
        else -> null
    }
}

/** Outcome of the Double-Bounded Reduction at one Boolean/disjunction leaf. */
private sealed interface ExactLiraReduction {
    /** The bounded transformed system proves this leaf impossible. */
    data object Infeasible : ExactLiraReduction

    class Bounded(
        val system: ExactMixedEchelonHermite,
        val bounds: ExactMixedTriangularBounds,
        val unboundedRows: List<ExactRationalInequality>,
        val sourceRows: List<ExactRationalInequality>,
        val budget: SourceLpBudget,
    ) : ExactLiraReduction,
        AutoCloseable {
        val solver = ExactReducedLiraSystem(this)
        var extension: SourceLp? = null

        override fun close() {
            closeSourceLpOwners(listOfNotNull(solver, extension))
        }
    }

    data object Interrupted : ExactLiraReduction
}

/**
 * Cache the Boolean-leaf Double-Bounded Reduction artefact across integer branch-and-bound nodes.
 *
 * Reduced-coordinate branches are deliberately not part of this key: they search the fixed
 * double-bounded artefact, while source bounds and Boolean/disjunction choices select that artefact.
 */
private class ExactLiraReductionCache(
    private val model: Problem,
    private val exactForms: List<List<ExactRowForm>>,
    private val disjunctionAtoms: Map<Int, List<DisjunctAtom>>,
    solveContext: () -> LpSolveContext,
    onWork: (SourceLpWorkStats) -> Unit,
) : AutoCloseable {
    private val budget = SourceLpBudget(solveContext = solveContext, onWork = onWork)

    override fun close() {
        val owners = results.values.filterIsInstance<ExactLiraReduction.Bounded>()
        results.clear()
        closeSourceLpOwners(owners)
    }
    private val results = HashMap<ExactLiraReductionKey, ExactLiraReduction>()

    fun completeReals(
        columns: Int,
        rows: List<ExactRationalInequality>,
        stop: Cancellation,
        onStage: (String) -> Unit,
    ): List<BigFraction>? = completeMixedRealComponents(columns, rows, budget, stop, onStage)

    fun reduce(
        bools: IntArray,
        node: SearchNode,
        cancellation: Cancellation,
        observer: SmtStatsSink? = null,
    ): ExactLiraReduction {
        return budget.run(emptyList(), model.numRealVars + model.numIntVars, cancellation) { token ->
            if (!admitsSourcePreparation(node)) return@run ExactLiraReduction.Interrupted
            reduceAdmitted(bools, node, token, observer)
        } ?: ExactLiraReduction.Interrupted
    }

    private fun admitsSourcePreparation(node: SearchNode): Boolean {
        val searched = node.searchedBranches(disjunctionAtoms)
        if (model.numBoolVars > 512 || model.factors.size > 128 || node.branches.size > 128 ||
            searched.size > 128 || searched.sumOf { it.terms.size.toLong() } > 512L
        ) {
            return false
        }
        var terms = 0L
        var rows = 0L
        for (factor in model.factors) {
            if (factor.linearRows.size > 128) return false
            for (row in factor.linearRows) {
                rows++
                terms += row.size
                if (rows > 128L || terms > 512L) return false
                val constants = row.constants
                if (constants is IntegralConstants && (
                        constants.exactBound.magnitudeBitLength() > 4096 ||
                            (0 until row.size).any { constants.exactCoeff(it).magnitudeBitLength() > 4096 }
                        )
                ) {
                    return false
                }
            }
        }
        for (integer in 0 until model.numIntVars) {
            if (model.intBounds.lowerAsBigInteger(integer)?.magnitudeBitLength()?.let { it > 4096 } == true ||
                model.intBounds.upperAsBigInteger(integer)?.magnitudeBitLength()?.let { it > 4096 } == true
            ) {
                return false
            }
        }
        if (node.branches.any { branch ->
                branch.variable !in 0 until model.numIntVars ||
                    branch.lower?.magnitudeBitLength()?.let { it > 4096 } == true ||
                    branch.upper?.magnitudeBitLength()?.let { it > 4096 } == true
            }
        ) {
            return false
        }
        return node.sourceBranches.all { atom ->
            listOf(
                atom.threshold,
            ).admittedSourcePoint() && atom.terms.all { listOf(it.coefficient).admittedSourcePoint() }
        }
    }

    private fun reduceAdmitted(
        bools: IntArray,
        node: SearchNode,
        cancellation: Cancellation,
        observer: SmtStatsSink?,
    ): ExactLiraReduction {
        val mark = observer?.beginReduction()
        val key = ExactLiraReductionKey(
            bools.toList(),
            node.branches.sortedBy { it.variable },
            node.rowAssertions.entries.sortedBy { it.key }.map {
                it.key to (it.value.payload as NamedDisjunctDecision).holds
            },
            node.disequalityDirections.entries.sortedBy { it.key }.map { it.key to it.value },
            // The rows are a conjunction, so the same branches reached in another order select the same artefact.
            node.sourceBranches.toSet(),
        )
        results[key]?.let { cached ->
            mark?.let {
                observer.endReduction(
                    it,
                    cacheHit = true,
                    accepted = cached != ExactLiraReduction.Interrupted,
                )
            }
            return cached
        }
        val rows = sourceRows(bools, node, cancellation) ?: return ExactLiraReduction.Interrupted.also {
            mark?.let { observer.endReduction(it, cacheHit = false, accepted = false) }
        }
        var admitted = false
        var result = reduceRows(rows, cancellation) { admitted = true }
        if (!admitted) {
            val equalities = sourceEqualityRows(rows, model.numRealVars + model.numIntVars, budget, cancellation)
            if (equalities != null && equalities.isNotEmpty() && equalities.size < rows.size) {
                val subset = reduceRows(equalities, cancellation)
                if (subset == ExactLiraReduction.Infeasible && !cancellation()) result = subset
                (subset as? ExactLiraReduction.Bounded)?.close()
            }
        }
        results[key] = result
        mark?.let { observer.endReduction(it, cacheHit = false, accepted = result != ExactLiraReduction.Interrupted) }
        return result
    }

    private fun reduceRows(
        rows: List<ExactRationalInequality>,
        cancellation: Cancellation,
        onAdmitted: () -> Unit = {},
    ): ExactLiraReduction = budget.run(rows, model.numRealVars + model.numIntVars, cancellation) { token ->
        onAdmitted()
        when (
            val split = sourceDoubleBoundedSplit(
                rows,
                model.numRealVars + model.numIntVars,
                budget,
                token,
            )
        ) {
            ExactDoubleBoundedSplit.Infeasible -> ExactLiraReduction.Infeasible

            ExactDoubleBoundedSplit.Unknown -> ExactLiraReduction.Interrupted

            is ExactDoubleBoundedSplit.Split -> {
                val bounded = split.bounded.map { row ->
                    ExactMixedBoundedRow(
                        row.inequality.columns.indices.associate { index ->
                            row.inequality.columns[index] to row.inequality.coefficients[index]
                        },
                        row.lower,
                        row.inequality.rhs,
                        row.inequality.strict,
                    )
                }
                val transformed = exactMixedEchelonHermite(
                    bounded,
                    realColumns = model.numRealVars,
                    integerColumns = model.numIntVars,
                    cancellation = token,
                )
                if (transformed == null) {
                    ExactLiraReduction.Interrupted
                } else {
                    val bounds = exactMixedTriangularBounds(transformed)
                    if (bounds.inconsistent) {
                        ExactLiraReduction.Infeasible
                    } else {
                        ExactLiraReduction.Bounded(
                            transformed,
                            bounds,
                            split.unbounded.map(rows::get),
                            rows,
                            budget,
                        )
                    }
                }
            }
        }
    } ?: ExactLiraReduction.Interrupted

    fun sourceRows(bools: IntArray, node: SearchNode, cancellation: Cancellation): List<ExactRationalInequality>? {
        val rows = ArrayList<ExactRationalInequality>()
        val published = node.branches.associateBy { it.variable }
        for (integer in 0 until model.numIntVars) {
            if (cancellation()) return null
            val column = model.numRealVars + integer
            val branch = published[integer]
            maxOfNullable(model.intBounds.lowerAsBigInteger(integer), branch?.lower)?.let {
                rows += exactColumnLower(column, it.asFraction())
            }
            minOfNullable(model.intBounds.upperAsBigInteger(integer), branch?.upper)?.let {
                rows += exactColumnUpper(column, it.asFraction())
            }
        }
        for (real in 0 until model.numRealVars) {
            if (cancellation()) return null
            model.realLower[real].takeIf(Double::isFinite)?.let { rows += exactColumnLower(real, it.asFraction()) }
            model.realUpper[real].takeIf(Double::isFinite)?.let { rows += exactColumnUpper(real, it.asFraction()) }
        }
        val complete = node.forEachSelectedRow(model, disjunctionAtoms) { factor, index, row ->
            if (cancellation()) return null
            val truth = node.rowTruth(RowAddress(factor, index), row, bools) ?: return@forEachSelectedRow
            val comparison = exactForms[factor][index].comparison(truth) { bools[it] == TRUE }
            val direction = if (comparison.op == LinearOp.NE) {
                node.disequalityDirections[RowAddress(factor, index)]
            } else {
                null
            }
            comparison.rowsInto(rows, direction)
        }
        for (atom in node.sourceBranches) {
            if (cancellation()) return null
            rows += atom.sourceRows(model.numRealVars)
        }
        return rows.takeIf { complete }
    }
}

private data class ExactLiraReductionKey(
    val bools: List<Int>,
    val branches: List<IntegerBranch>,
    val comparisons: List<Pair<RowAddress, Boolean>>,
    val directions: List<Pair<RowAddress, LinearOp>>,
    val sourceBranches: Set<SourceBoundAtom>,
)

/**
 * The bounded phase of the Double-Bounded Reduction in mixed-echelon/Hermite coordinates; M. Bromberger,
 * "A Reduction from Unbounded Linear Mixed Arithmetic Problems into Bounded Problems", IJCAR 2018,
 * https://doi.org/10.1007/978-3-319-94205-6_22.
 *
 * Only nonzero integer columns of the transformed double-bounded system are branched. Lemma 8 of that paper
 * makes each of those coordinates finite; zero columns are deliberately absent from this search and
 * are filled by [ExactLiraReduction.Bounded.extend] after the bounded witness is found.
 */
private class ExactReducedLiraSystem(private val reduction: ExactLiraReduction.Bounded) : AutoCloseable {
    private val realColumns = reduction.system.realColumns
    private val integerColumns = reduction.system.integerColumns
    private val columns = realColumns + integerColumns

    fun isBounded(): Boolean = (0 until integerColumns).all { integer ->
        if (!reduction.system.boundedColumn(realColumns + integer)) {
            true
        } else {
            reduction.bounds.integerLower[integer] != null && reduction.bounds.integerUpper[integer] != null
        }
    }

    fun root(node: SearchNode): SearchNode {
        var result = node
        for (integer in 0 until integerColumns) {
            if (!reduction.system.boundedColumn(realColumns + integer)) continue
            result = result.withReducedBranch(
                IntegerBranch(
                    integer,
                    reduction.bounds.integerLower[integer],
                    reduction.bounds.integerUpper[integer],
                ),
            )
        }
        return result
    }

    fun model(node: SearchNode): List<ExactRationalInequality> {
        val rows = ArrayList<ExactRationalInequality>(reduction.system.rows.size * 2 + node.reducedBranches.size * 2)
        for (row in reduction.system.rows) {
            rows.add(row.asUpper())
            rows.add(row.asLower())
        }
        for (branch in node.reducedBranches) {
            val column = realColumns + branch.variable
            branch.lower?.let { lower ->
                rows.add(
                    ExactRationalInequality(
                        intArrayOf(column),
                        listOf(BigFraction.MINUS_ONE),
                        BigFraction.of(lower.negate(), BIG_ONE),
                    ),
                )
            }
            branch.upper?.let { upper ->
                rows.add(
                    ExactRationalInequality(
                        intArrayOf(column),
                        listOf(BigFraction.ONE),
                        BigFraction.of(upper, BIG_ONE),
                    ),
                )
            }
        }
        for (atom in node.sourceBranches) {
            rows += atom.sourceRows(realColumns).map(reduction.system::transform)
        }
        return rows
    }

    private val owner = SourceLp(model(root(SearchNode())), columns, reduction.budget)

    override fun close() = owner.close()

    fun fractionalInteger(values: List<BigFraction>): Int? = (0 until integerColumns).firstOrNull { integer ->
        reduction.system.boundedColumn(realColumns + integer) && !values[realColumns + integer].isInteger()
    }

    fun solve(node: SearchNode, searched: List<SourceBoundAtom>, cancellation: Cancellation): ExactReducedSearchResult {
        return reduction.budget.run(reduction.sourceRows, columns, cancellation) { token ->
            if (searched.size > 128 || searched.sumOf { it.terms.size.toLong() } > 512L ||
                node.sourceBranches.any { atom ->
                    !listOf(
                        atom.threshold,
                    ).admittedSourcePoint() || atom.terms.any { !listOf(it.coefficient).admittedSourcePoint() }
                }
            ) {
                return@run ExactReducedSearchResult.Interrupted
            }
            if (!isBounded()) return@run ExactReducedSearchResult.Interrupted
            val rooted = root(node)
            val outcome = owner.solve(
                token,
                branches = node.sourceBranches.flatMap { it.sourceRows(realColumns).map(reduction.system::transform) },
            ) ?: return@run ExactReducedSearchResult.Interrupted
            if (outcome.verdict == LpVerdict.INFEASIBLE) return@run ExactReducedSearchResult.Infeasible
            val values = outcome.exactPrimal?.take(columns) ?: return@run ExactReducedSearchResult.Interrupted
            val integer = fractionalInteger(values)
            if (integer != null) {
                return@run ExactReducedSearchResult.Split(
                    rooted,
                    integer,
                    values[realColumns + integer].floor(),
                )
            }
            reduction.extend(values, token)?.takeIf { source ->
                source.satisfiesSourceRows(node.sourceBranches.flatMap { it.sourceRows(realColumns) }, token)
            }?.let(ExactReducedSearchResult::Found)
                ?: ExactReducedSearchResult.Interrupted
        } ?: ExactReducedSearchResult.Interrupted
    }
}

private sealed interface ExactReducedSearchResult {
    data class Found(val sourceValues: List<BigFraction>) : ExactReducedSearchResult
    data class Split(val node: SearchNode, val integer: Int, val floor: BigInt) : ExactReducedSearchResult
    data object Infeasible : ExactReducedSearchResult
    data object Interrupted : ExactReducedSearchResult
}

private fun ExactLiraReduction.Bounded.extend(
    transformed: List<BigFraction>,
    cancellation: Cancellation,
): List<BigFraction>? {
    return budget.run(
        unboundedRows,
        system.realColumns + system.integerColumns,
        cancellation,
        rhs = transformed,
    ) { token ->
        val realFree = (0 until system.realColumns).filterNot(system::boundedColumn)
        val integerFree = (0 until system.integerColumns).filterNot { integer ->
            system.boundedColumn(system.realColumns + integer)
        }.map { integer -> system.realColumns + integer }
        val free = realFree + integerFree
        val compact = free.withIndex().associate { (index, column) -> column to index }
        val extensionRows = unboundedRows.map { source ->
            if (token()) return@run null
            val transformedRow = system.transform(source)
            var rhs = transformedRow.rhs
            val coefficients = HashMap<Int, BigFraction>()
            for (entry in transformedRow.columns.indices) {
                if (token()) return@run null
                val column = transformedRow.columns[entry]
                val coefficient = transformedRow.coefficients[entry]
                val target = compact[column]
                if (target == null) {
                    rhs -= coefficient * transformed[column]
                } else {
                    coefficients[target] = coefficient
                }
            }
            val ordered = coefficients.entries.sortedBy { it.key }
            ExactRationalInequality(
                ordered.map { it.key }.toIntArray(),
                ordered.map { it.value },
                rhs,
                transformedRow.strict,
            )
        }
        val half = BigFraction.of(BIG_ONE, BIG_TWO)
        val shifted = extensionRows.map { row ->
            if (token()) return@run null
            var norm = BigFraction.ZERO
            for (i in row.columns.indices) {
                if (token()) return@run null
                if (row.columns[i] >= realFree.size) {
                    val value = row.coefficients[i]
                    norm += if (value < BigFraction.ZERO) value.negated() else value
                }
            }
            ExactRationalInequality(row.columns, row.coefficients, row.rhs - half * norm, row.strict)
        }
        val owner = extension ?: SourceLp(shifted, free.size, budget).also { extension = it }
        val point = owner.solve(token, rhs = shifted.map { it.rhs })?.exactPrimal?.take(free.size) ?: return@run null
        val extension = point.mapIndexed { index, value ->
            if (token()) return@run null
            if (index < realFree.size) value else (value + half).floor().asFraction()
        }
        if (!extension.satisfiesSourceRows(extensionRows, token)) return@run null
        val completed = transformed.toMutableList()
        for ((index, column) in free.withIndex()) completed[column] = extension[index]
        if (token()) return@run null
        val recovered = system.recover(completed)
        recovered.takeIf { values -> values.satisfiesSourceRows(sourceRows, token) }
    }
}

private fun ExactMixedBoundedRow.asUpper(): ExactRationalInequality {
    val ordered = coefficients.entries.sortedBy { it.key }
    return ExactRationalInequality(ordered.map { it.key }.toIntArray(), ordered.map { it.value }, upper, upperStrict)
}

private fun ExactMixedBoundedRow.asLower(): ExactRationalInequality {
    val ordered = coefficients.entries.sortedBy { it.key }
    return ExactRationalInequality(
        ordered.map { it.key }.toIntArray(),
        ordered.map { it.value.negated() },
        lower.negated(),
    )
}

// Source integer values beyond the largest exactly representable double are a useful diagnostic for
// model conversions that would otherwise silently lose an integer unit.
private val WIDE_INTEGER_LIMIT = bigIntOf(1L shl 53)

private fun List<ExactRationalInequality>.hasWideIntegerData(): Boolean = any { row ->
    (row.rhs.den == BIG_ONE && row.rhs.num.abs() > WIDE_INTEGER_LIMIT) ||
        row.coefficients.any { coefficient ->
            coefficient.den == BIG_ONE && coefficient.num.abs() > WIDE_INTEGER_LIMIT
        }
}

private data class IntegerBranch(val variable: Int, val lower: BigInt? = null, val upper: BigInt? = null)

private data class IntegerLinearBranch(
    val variables: IntArray,
    val coefficients: Array<BigInt>,
    val lower: BigInt? = null,
    val upper: BigInt? = null,
) {
    fun sameShape(other: IntegerLinearBranch): Boolean =
        variables.contentEquals(other.variables) && coefficients.contentEquals(other.coefficients)
}

private data class RowAddress(val factor: Int, val row: Int) : Comparable<RowAddress> {
    override fun compareTo(other: RowAddress): Int = compareValuesBy(this, other, RowAddress::factor, RowAddress::row)
}

private class PreparedSourceRow(
    val address: RowAddress,
    val row: LinearRow,
    val form: ExactRowForm,
    val booleans: IntArray,
    val disjunction: Boolean,
)

private data class SearchNode(
    val branches: List<IntegerBranch> = emptyList(),
    val sourceBranches: List<SourceBoundAtom> = emptyList(),
    val retainedReduction: ExactLiraReduction.Bounded? = null,
    val reducedBranches: List<IntegerBranch> = emptyList(),
    val transformedBranches: List<IntegerLinearBranch> = emptyList(),
    val rowAssertions: Map<RowAddress, RegisteredTheoryDecision> = emptyMap(),
    val disequalityDirections: Map<RowAddress, LinearOp> = emptyMap(),
    val directionPremises: Map<RowAddress, SearchAtomPremise> = emptyMap(),
) {
    fun withBranch(branch: IntegerBranch): SearchNode {
        val existing = branches.indexOfFirst { it.variable == branch.variable }
        if (existing < 0) return copy(branches = branches + branch)
        val merged = branches[existing].copy(
            lower = maxOfNullable(branches[existing].lower, branch.lower),
            upper = minOfNullable(branches[existing].upper, branch.upper),
        )
        return copy(branches = branches.toMutableList().also { it[existing] = merged })
    }

    fun withReducedBranch(branch: IntegerBranch): SearchNode {
        val existing = reducedBranches.indexOfFirst { it.variable == branch.variable }
        if (existing < 0) return copy(reducedBranches = reducedBranches + branch)
        val merged = reducedBranches[existing].copy(
            lower = maxOfNullable(reducedBranches[existing].lower, branch.lower),
            upper = minOfNullable(reducedBranches[existing].upper, branch.upper),
        )
        return copy(reducedBranches = reducedBranches.toMutableList().also { it[existing] = merged })
    }

    fun withTransformedBranch(branch: IntegerLinearBranch): SearchNode =
        copy(transformedBranches = transformedBranches + branch)

    fun withTransformedSplit(branch: IntegerLinearBranch, lower: BigInt? = null, upper: BigInt? = null): SearchNode =
        copy(
            transformedBranches = transformedBranches.map { existing ->
                if (!existing.sameShape(branch)) {
                    existing
                } else {
                    existing.copy(
                        lower = maxOfNullable(existing.lower, lower),
                        upper = minOfNullable(existing.upper, upper),
                    )
                }
            },
        )

    fun withDirection(factor: RowAddress, direction: LinearOp): SearchNode =
        copy(disequalityDirections = disequalityDirections + (factor to direction))

    fun rowTruth(address: RowAddress, row: LinearRow, bools: IntArray): Boolean? {
        val truth = row.truthUnder(bools) ?: return null
        val asserted = rowAssertions[address]?.payload as? NamedDisjunctDecision
        return if (asserted?.holds == false) !truth else truth
    }

    fun selectsEveryDisjunction(model: Problem, disjunctionAtoms: Map<Int, List<DisjunctAtom>>): Boolean =
        forEachSelectedRow(model, disjunctionAtoms) { _, _, _ -> }

    // Scalar atoms state their rows through sourceBranches; compound comparisons retain both polarities here.
    inline fun forEachSelectedRow(
        model: Problem,
        disjunctionAtoms: Map<Int, List<DisjunctAtom>>,
        action: (Int, Int, LinearRow) -> Unit,
    ): Boolean {
        for ((index, factor) in model.factors.withIndex()) {
            val rows = factor.linearRows
            val disjuncts = disjunctionAtoms[index]
            if (disjuncts != null) {
                if (disjuncts.none {
                        it.row?.let { row -> row in sourceBranches } == true ||
                            (rowAssertions[it.address]?.payload as? NamedDisjunctDecision)?.holds == true
                    }
                ) {
                    return false
                }
                for (disjunct in disjuncts) {
                    if (disjunct.address in rowAssertions) {
                        action(index, disjunct.address.row, rows[disjunct.address.row])
                    }
                }
            } else if (factor.linearForm is LinearForm.Disjunction) {
                return false
            } else {
                for (rowIndex in rows.indices) action(index, rowIndex, rows[rowIndex])
            }
        }
        return true
    }

    fun nextDisequality(model: Problem, disjunctionAtoms: Map<Int, List<DisjunctAtom>>, bools: IntArray): RowAddress? {
        forEachSelectedRow(model, disjunctionAtoms) { factor, index, row ->
            val truth = rowTruth(RowAddress(factor, index), row, bools) ?: return@forEachSelectedRow
            if ((if (truth) row.relation else row.relation.complemented()) != LinearOp.NE) return@forEachSelectedRow
            val address = RowAddress(factor, index)
            if (address !in disequalityDirections) return address
        }
        return null
    }
}

private fun SearchNode.withReductionBounds(reduction: ExactLiraReduction.Bounded): SearchNode {
    var bounded = this
    for (integer in 0 until reduction.system.integerColumns) {
        val lower = reduction.bounds.integerLower[integer]
        val upper = reduction.bounds.integerUpper[integer]
        if (lower == null && upper == null) continue
        val coefficients = reduction.system.transformedIntegerCoefficients(integer)
        val branch = IntegerLinearBranch(coefficients.index, coefficients.value, lower, upper)
        if (bounded.transformedBranches.none { it.sameShape(branch) }) bounded = bounded.withTransformedBranch(branch)
    }
    return bounded
}

// Every bounded integer folded into the node's branches as [SearchNode.withBranch] would, in one pass: folding them
// one at a time scans and copies the branch list per bound, quadratic in the bounds a large root publishes.
private fun SearchNode.withPublishedBounds(
    numIntVars: Int,
    lowerBound: (Int) -> Long?,
    upperBound: (Int) -> Long?,
): SearchNode {
    var merged: MutableList<IntegerBranch>? = null
    var positions: MutableIntIntMap? = null
    for (integer in 0 until numIntVars) {
        val lower = lowerBound(integer)?.let(::bigIntOf)
        val upper = upperBound(integer)?.let(::bigIntOf)
        if (lower == null && upper == null) continue
        val index = positions ?: MutableIntIntMap(branches.size * 2).also { map ->
            branches.forEachIndexed { at, branch -> map.put(branch.variable, at) }
            positions = map
        }
        val at = index.getOrDefault(integer, -1)
        if (at < 0) {
            val list = merged ?: branches.toMutableList().also { merged = it }
            index.put(integer, list.size)
            list += IntegerBranch(integer, lower, upper)
        } else {
            val existing = (merged ?: branches)[at]
            val nextLower = maxOfNullable(existing.lower, lower)
            val nextUpper = minOfNullable(existing.upper, upper)
            if (nextLower === existing.lower && nextUpper === existing.upper) continue
            val list = merged ?: branches.toMutableList().also { merged = it }
            list[at] = existing.copy(lower = nextLower, upper = nextUpper)
        }
    }
    return merged?.let { copy(branches = it) } ?: this
}

// holds states one row of a comparison disjunction and fails its exact complement.
private class DisjunctAtom(
    val holds: RegisteredTheoryDecision,
    val fails: RegisteredTheoryDecision,
    val address: RowAddress,
) {
    val row = (holds.payload as? SourceBoundPredicate)?.sourceBound
    val complement = (fails.payload as? SourceBoundPredicate)?.sourceBound
}

// The source branches search made. A decided disjunct restates a model row the size limits already count.
private fun SearchNode.searchedBranches(disjunctionAtoms: Map<Int, List<DisjunctAtom>>): List<SourceBoundAtom> {
    if (disjunctionAtoms.isEmpty()) return sourceBranches
    val restated = HashSet<SourceBoundAtom>()
    for (disjuncts in disjunctionAtoms.values) {
        for (disjunct in disjuncts) {
            disjunct.row?.let { restated += it }
            disjunct.complement?.let { restated += it }
        }
    }
    return sourceBranches.filter { it !in restated }
}

private fun SearchContext.truth(decision: RegisteredTheoryDecision): Boolean? =
    boolValue(decision.literal ushr 1)?.let { it == (decision.literal and 1 == 0) }

// The owner fixes the entire source predicate, including its Boolean terms and activator, for this session.
private data class NamedPrivateDisequality(val owner: Any, val address: RowAddress, val direction: LinearOp) :
    SearchTheoryDecision

private data class NamedDisjunctDecision(val owner: Any, val address: RowAddress, val holds: Boolean) :
    SearchTheoryDecision

private fun SourceBoundAtom.sourceRows(realColumns: Int): List<ExactRationalInequality> {
    val columns = terms.associate { term ->
        val column = when (val key = term.source) {
            is SearchIntValue -> realColumns + key.variable
            is SearchRealValue -> key.variable
            else -> error("unsupported registered source")
        }
        column to if (upper) term.coefficient else term.coefficient.negated()
    }.entries.sortedBy { it.key }
    return listOf(
        ExactRationalInequality(
            columns.map { it.key }.toIntArray(),
            columns.map { it.value },
            if (upper) threshold else threshold.negated(),
            strict,
        ),
    )
}

private fun BigFraction.isInteger(): Boolean = den == BIG_ONE

private fun BigFraction.floor(): BigInt {
    val quotient = num / den
    return if (num < BIG_ZERO && num % den != BIG_ZERO) quotient - BIG_ONE else quotient
}

private fun maxOfNullable(a: BigInt?, b: BigInt?): BigInt? = if (a == null || b == null) a ?: b else maxOf(a, b)

private fun minOfNullable(a: BigInt?, b: BigInt?): BigInt? = if (a == null || b == null) a ?: b else minOf(a, b)
