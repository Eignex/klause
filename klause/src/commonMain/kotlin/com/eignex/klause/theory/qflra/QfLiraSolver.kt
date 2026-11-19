package com.eignex.klause.theory.qflra

import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.indices
import com.eignex.klause.ir.linearRows
import com.eignex.klause.lp.ExactComparison
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
import com.eignex.klause.lp.exactComparison
import com.eignex.klause.lp.satisfiesSourceRows
import com.eignex.klause.simplex.exact.ExactContinuationLimits
import com.eignex.klause.simplex.exact.ExactContinuationMetrics
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.result.SmtStatsSink
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
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.PollStride
import com.eignex.klause.util.WorkMeter
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.MutableIntObjectMap
import com.eignex.util.collections.removeKeysAbove
import com.eignex.util.collections.valueAtMaxKey
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.abs
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.fitsLong
import com.eignex.util.numbers.floorDiv
import com.eignex.util.numbers.magnitudeBitLength
import com.eignex.util.numbers.maxOf
import com.eignex.util.numbers.minus
import com.eignex.util.numbers.toLongExact
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
    private var impliedDisjunct = false
    private val reduction = ExactLiraReductionCache(model, exactForms, disjunctionAtoms, { solveContext }) {
        smtStats?.observeSourceLp(it)
    }
    private val nodesByLevel = MutableIntObjectMap<SearchNode>()
    private var node = root
    private var assignment: ExactLiraAssignment? = null
    private var outcome: ComponentCheck? = null
    private var unconsumedLpPoint: List<BigFraction>? = null
    private var retainedLpPoint: List<BigFraction>? = null
    private var sourceCheckedPoint: List<BigFraction>? = null
    private var sourceCheckedBooleans: IntArray? = null
    private var candidate: List<BigFraction>? = null
        set(value) {
            field = value
            unconsumedLpPoint = null
        }
    private var floatCandidate: DoubleArray? = null
    private var pointHintRows: List<PreparedSourceRow>? = null
    private var pointHintValues: IntArray? = null
    private var pointHintCacheable = false
    private var pointHintPoint: List<BigFraction>? = null
    private var retainedPointHintValues: IntArray? = null
    private var pointHintLevel = -1
    private var pointHintStop = ExecutionControl.Never
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
    private var solveContext = LpSolveContext.Production
    private var solveStop: ExecutionControl? = null
    private var operationAllowance: ((ExecutionControl) -> ExecutionControl)? = null
    private var operationStop = ExecutionControl.Never
    private var operationAccounting: WorkMeter? = null
    private var parentStop = ExecutionControl.Never
    private var pollContextStop = true
    private val lpStop = LinearOperationCancellation({ operationStop }) {
        pollContextStop && context?.cancelled() == true
    }
    private val hintStop = LinearOperationCancellation({ operationStop }) { pointHintStop() }
    internal var operationBudgetExhausted = false
        private set
    private val arithmeticRows = preparedSource.arithmeticRows
    private val arithmeticVariables = preparedSource.arithmeticVariables
    private val branchNames = HashMap<SourceBoundAtom, SearchDecision>()
    private var context: SearchContext? = null
    private val lpDelegate: Lazy<LpPropagator> = lazy {
        LpPropagator(
            object : LpSearchPolicy {
                override val eagerAssertionScopes: Boolean get() = false
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
                    if (cost is LpCertifierCost.Metered) lpStop.charge(cost.work)
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
                    lpStop.charge(metrics.work)
                    primaryLpObserver?.observeBasisVerification(metrics)
                }
                override fun observeContinuation(metrics: ExactContinuationMetrics) {
                    lpStop.charge(metrics.work)
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
    private val systemDelegate = lazy {
        LiveQfLraSystem(model, lp, exactForms, strictMargin = model.numIntVars == 0, preparationStop = lpStop)
    }
    private val system by systemDelegate

    internal fun solveWith(context: LpSolveContext) {
        check(this.context == null)
        solveContext = context
    }

    internal fun useSolveStop(stop: ExecutionControl?) {
        check(context == null)
        solveStop = stop
    }

    internal fun useOperationAllowance(allowance: (ExecutionControl) -> ExecutionControl) {
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
        stop: ExecutionControl,
        defaultOpening: Boolean = false,
    ): Pair<IntArray, SearchCandidateHints>? {
        check(context == null)
        if (model.numRealVars == 0 && (!defaultOpening || model.numIntVars == 0)) return null
        if (model.numBoolVars == 0 || model.numBoolVars > MAX_POINT_HINT_ENTRIES ||
            preparedRows.size > MAX_POINT_HINT_ENTRIES || stop()
        ) {
            return null
        }
        var entries = model.numRealVars.toLong() + model.numIntVars + model.numBoolVars
        val accounting = stop.accounting
        val stride = PollStride(calls = 2048)
        val rows = ArrayList<PreparedSourceRow>()
        val variables = LinkedHashSet<Int>()
        for (prepared in preparedRows) {
            accounting?.charge(1)
            if (stride.due() && stop()) return null
            if (prepared.row.activator == LinearRow.ALWAYS) continue
            if (prepared.row.activator !in bools.indices) return null
            entries += prepared.row.size.toLong() + 1L
            if (entries > MAX_POINT_HINT_ENTRIES) return null
            rows += prepared
            variables += prepared.row.activator
        }
        accounting?.charge(2L * rows.size + variables.size + 1L)
        if (stop() || rows.isEmpty()) return null
        pointHintRows = rows.toList()
        pointHintCacheable = rows.none { it.form.hasBooleanTerms }
        pointHintStop = stop
        return variables.toIntArray() to SearchCandidateHints { variable ->
            observeCompleteStage("POINT_HINT_CONSULTED")
            if (!partialPointHintAvailable()) {
                observeCompleteStage("POINT_HINT_UNAVAILABLE")
                null
            } else {
                val preference = when (pointHintValues?.getOrNull(variable)) {
                    LIRA_TRUE -> true
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
        pointHintPoint = null
        retainedPointHintValues = null
        pointHintCacheable = false
        pointHintLevel = -1
        pointHintStop = ExecutionControl.Never
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
        operationAccounting?.charge(bools.size.toLong() + 1L)
        if (lpStop.poll()) return null
        val heads = IntArray(bools.size)
        for (prepared in preparedRows) {
            operationAccounting?.charge(1)
            if (lpStop.poll()) return null
            if (prepared.row.activator != LinearRow.ALWAYS) heads[prepared.row.activator]++
        }
        val candidates = ArrayList<PreparedSourceRow>()
        for (prepared in preparedRows) {
            operationAccounting?.charge(1)
            if (lpStop.poll()) return null
            val row = prepared.row
            if (prepared.disjunction || row.activator == LinearRow.ALWAYS || heads[row.activator] != 1) continue
            operationAccounting?.charge(row.size.toLong())
            if ((0 until row.size).none {
                    Term.isBool(row.ref(it)) && Lit.variable(Term.lit(row.ref(it))) == row.activator
                }
            )
                candidates += prepared
        }
        operationAccounting?.charge(candidates.size.toLong() + 1L)
        if (lpStop.poll()) return null
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
                operationAccounting?.charge(1)
                if (lpStop.poll()) break
                visited++
                val variable = prepared.row.activator
                if (current.boolValue(variable) != null) continue
                val row = prepared.row
                operationAccounting?.charge(row.size.toLong() + prepared.booleans.size)
                if (prepared.booleans.any { it != variable && bools[it] == UNASSIGNED }) continue
                eligible++
                val comparison = prepared.form.comparison(true) { bools[it] == LIRA_TRUE }
                val inputs = if (partialLinearCache) {
                    partialLinearInputs(comparison, partialLinearDeclines[prepared])
                } else
                    null
                if (inputs != null && partialLinearDeclines[prepared] == inputs) {
                    partialLinearUnchanged++
                    continue
                }
                val affine = if (incrementalLinearPropagation && comparison.terms.size > 1) {
                    affineProbes++
                    system.affineImplication(comparison, operationStop)
                } else
                    null
                var unavailable = false
                partialLinearEvaluations++
                val implication = affine ?: partialLinearImplication(comparison, { column, upper ->
                    val metadata = state.model.column(column)
                    if (metadata.tag != column || !metadata.origin.value.isZero)
                        null
                    else
                        state.activeSide(column, upper)?.let { active ->
                            val premise = lp.activeBoundPremise(column, upper) ?: SearchAtomPremise.Unavailable
                            if (premise === SearchAtomPremise.Unavailable) unavailable = true
                            ExplainedLinearBound(active.side.number.value, active.side.strict, premise)
                        }
                }, operationStop)
                if (implication == null) {
                    if (inputs != null && !unavailable && !lpStop.poll()) partialLinearDeclines[prepared] = inputs
                    continue
                }
                partialLinearDeclines.remove(prepared)
                val rhs = prepared.booleans.filter { it != variable }.map { input ->
                    SearchAtomPremise.Asserted(SearchDecision.Bool(Lit.make(input, bools[input] == LIRA_TRUE)))
                }
                val decision = SearchDecision.Bool(Lit.make(variable, implication.truth))
                val reason = current.explainAtoms(SearchAtomPremise.All(rhs + implication.premise), decision)
                if (reason == null) {
                    unexplained++
                    continue
                }
                operationAccounting?.charge(reason.literals.size.toLong() + rhs.size + 1L)
                if (lpStop.poll()) break
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

    private fun partialLinearInputs(
        comparison: ExactComparison,
        previous: PartialLinearInputs?,
    ): PartialLinearInputs? {
        val state = lp.state ?: return null
        val affine = if (incrementalLinearPropagation && comparison.terms.size > 1) {
            system.affineInputSides(comparison, operationStop) ?: return null
        } else emptyList()
        if (previous != null && previous.bound == comparison.bound &&
            previous.sides.size == comparison.terms.size * 2 + affine.size
        ) {
            var unchanged = true
            var index = 0
            for (column in comparison.terms.keys) {
                operationAccounting?.charge(3)
                if (lpStop.poll()) return null
                val metadata = state.model.column(column)
                if (metadata.tag != column || !metadata.origin.value.isZero) return null
                for (upper in 0..1) {
                    val side = state.activeSide(column, upper == 1)?.side
                    val retained = previous.sides[index++]
                    if (retained?.value != side?.number?.value || retained?.strict != side?.strict) unchanged = false
                }
            }
            for (side in affine) if (previous.sides[index++] != side) unchanged = false
            if (unchanged) return previous
        }
        val sides = ArrayList<PartialLinearSide?>(comparison.terms.size * 2)
        for (column in comparison.terms.keys) {
            operationAccounting?.charge(3)
            if (lpStop.poll()) return null
            val metadata = state.model.column(column)
            if (metadata.tag != column || !metadata.origin.value.isZero) return null
            for (upper in listOf(false, true)) {
                sides += state.activeSide(column, upper)?.side?.let { PartialLinearSide(it.number.value, it.strict) }
            }
        }
        sides += affine
        return PartialLinearInputs(comparison.bound, sides)
    }

    private fun capturePointHints(point: List<BigFraction>, context: SearchContext) {
        pointHintValues = null
        val rows = pointHintRows ?: return
        if (point.size != model.numRealVars + model.numIntVars || operationStop() || pointHintStop()) return
        operationAccounting?.charge(point.size.toLong() + 1L)
        val retained = retainedPointHintValues
        if (pointHintCacheable && retained != null && point == pointHintPoint) {
            if (hintStop() || operationStop() || pointHintStop()) return
            pointHintLevel = context.decisionLevel
            pointHintValues = retained
            smtStats?.observePointHintCapture()
            observeCompleteStage("POINT_HINT_REUSE")
            return
        }
        operationAccounting ?.charge(model.numBoolVars.toLong() + point.size + 1L)
        if (operationStop() || point.any { hintStop.poll() || !hintScalar(it) }) return
        if (operationStop() || pointHintStop()) return
        val values = IntArray(model.numBoolVars) { UNASSIGNED }
        for (prepared in rows) {
            operationAccounting?.charge(1)
            if (hintStop.poll()) return
            val variable = prepared.row.activator
            if (values[variable] == DECLINED_HINT) continue
            val comparison = if (prepared.disjunction)
                null else
                prepared.form.hintComparison(
                    { variable -> bools[variable].takeIf { it != UNASSIGNED }?.let { it == LIRA_TRUE } },
                    { !hintStop.poll() && hintScalar(it) }, ::hintPreflight,
                )
            val preference = comparison?.let { hintTruth(it, point) }
            if (preference == null) {
                values[variable] = DECLINED_HINT
                continue
            }
            val value = if (preference) LIRA_TRUE else FALSE
            if (values[variable] != UNASSIGNED && values[variable] != value) {
                values[variable] = DECLINED_HINT
            } else {
                values[variable] = value
            }
        }
        if (pointHintCacheable) {
            operationAccounting?.charge(point.size.toLong() + 1L)
            if (hintStop() || operationStop() || pointHintStop()) return
            pointHintPoint = point.toList()
            retainedPointHintValues = values
        }
        if (hintStop() || operationStop() || pointHintStop()) return
        pointHintLevel = context.decisionLevel
        pointHintValues = values
        smtStats?.observePointHintCapture()
        observeCompleteStage("POINT_HINT_LP_CAPTURE")
    }

    private fun hintTruth(
        comparison: ExactComparison, point: List<BigFraction>,
    ): Boolean? = hintTruth(comparison) { point.getOrNull(it) }

    private inline fun hintTruth(comparison: ExactComparison, value: (Int) -> BigFraction?): Boolean? {
        var activity = BigFraction.ZERO
        for ((column, coefficient) in comparison.terms) {
            if (hintStop.poll()) return null
            val coordinate = value(column) ?: return null
            if (!hintPreflight(coefficient, coordinate)) return null
            val product = coefficient * coordinate
            if (!hintScalar(product) || !hintPreflight(activity, product)) return null
            activity += product
            if (!hintScalar(activity)) return null
        }
        if (!hintPreflight(activity, comparison.bound)) return null
        return when (comparison.op) {
            LinearOp.LE -> if (comparison.strict) activity < comparison.bound else activity <= comparison.bound
            LinearOp.GE -> if (comparison.strict) activity > comparison.bound else activity >= comparison.bound
            LinearOp.EQ -> activity == comparison.bound
            LinearOp.NE -> activity != comparison.bound
        }
    }

    private fun hintScalar(value: BigFraction): Boolean {
        val bits = maxOf(value.num.magnitudeBitLength(), value.den.magnitudeBitLength())
        operationAccounting?.charge(1L + bits / Long.SIZE_BITS)
        return bits <= MAX_POINT_HINT_BITS
    }

    private fun hintPreflight(left: BigFraction, right: BigFraction): Boolean {
        val bits = maxOf(left.num.magnitudeBitLength(), left.den.magnitudeBitLength()).toLong() +
            maxOf(right.num.magnitudeBitLength(), right.den.magnitudeBitLength()) + 2L
        if (bits > MAX_POINT_HINT_BITS * 2L + 2L) return false
        val words = bits / Long.SIZE_BITS + 1L
        operationAccounting?.charge(bits + words * words)
        return true
    }

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
            if (!installSource() || operationStop()) {
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
                if (decision is SearchDecision.Theory &&
                    (!installSource() || !lp.atLevel(context.decisionLevel, lpStop))
                ) return ComponentResult.Indeterminate
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
        })
        ComponentCheck.Indeterminate else
        lp.check(context)
    override fun shareSourcePredicates(context: SearchContext): ComponentResult {
        beginOperation()
        return try {
            if (operationStop()) return ComponentResult.Indeterminate
            val rows = agreementSourceRows(context)
                ?: return if (operationStop()) ComponentResult.Indeterminate else ComponentResult.Consistent
            for (row in rows) {
                if (row.columns.any { it in sourceRealCount until model.numRealVars }) continue
                operationAccounting?.charge(row.columns.size.toLong() + 1L)
                if (operationStop()) return ComponentResult.Indeterminate
                val terms = row.columns.indices.map { index ->
                    val column = row.columns[index]
                    val source = if (column < model.numRealVars)
                        SearchRealValue(column)
                    else
                        SearchIntValue(column - model.numRealVars)
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
                operationAccounting?.charge(1L)
                if (operationStop()) return ComponentCheck.Indeterminate
                val id = SourceRealId(variable)
                if (model.valueOf(SearchExactAlgebraicRealValue(id)) != null ||
                    model.valueOf(SearchExactRealExpressionValue(id)) != null
                )
                    return coordinateDeclined(context)
                point += model.valueOf(SearchExactRealValue(id)) ?: return coordinateDeclined(context)
            }
            for (variable in 0 until this.model.numIntVars) {
                operationAccounting?.charge(1L)
                if (operationStop()) return ComponentCheck.Indeterminate
                point += model.valueOf(SearchExactIntValue(SourceIntId(variable)))?.asFraction()
                    ?: return coordinateDeclined(context)
            }
            operationAccounting?.charge(point.size.toLong() + 1L)
            if (operationStop()) return ComponentCheck.Indeterminate
            if (!point.admittedSourcePoint()) return coordinateDeclined(context)
            val rows = agreementSourceRows(context) ?: return coordinateDeclined(context)
            operationAccounting?.charge(rows.sumOf { it.columns.size.toLong() + 1L })
            if (!operationStop() && point.satisfiesSourceRows(rows, lpStop) && !operationStop()) {
                ComponentCheck.Feasible
            } else if (operationStop())
                ComponentCheck.Indeterminate else
                coordinateDeclined(context)
        } finally {
            endOperation(context)
        }
    }

    private fun agreementSourceRows(context: SearchContext): List<ExactRationalInequality>? {
        operationAccounting?.charge(model.numIntVars.toLong() + model.numRealVars + preparedRows.size + 1L)
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
        retainedLpPoint = null
        sourceCheckedPoint = null
        sourceCheckedBooleans = null
        if (systemDelegate.isInitialized()) system.releasePoint()
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
            })
            return false
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
                    val ceiling = comparison.bound.ceilInteger()
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
            operationAccounting?.charge(1L)
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
            { variable -> bools.getOrNull(variable)?.takeIf { it != UNASSIGNED }?.let { it == LIRA_TRUE } },
            ::hintScalar, ::hintPreflight,
        )
    }

    private fun sourceAtomHintComparison(atom: SourceBoundAtom): ExactComparison? {
        val width = atom.terms.size.toLong() + 1L
        operationAccounting?.charge(width * width + 3L * width)
        if (operationStop() || pointHintStop()) return null
        val terms = system.sourceTerms(atom) ?: return null
        if (!hintScalar(atom.threshold) || terms.values.any { hintStop.poll() || !hintScalar(it) }) return null
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
        if (hintScalar(comparison.bound) && comparison.terms.values.all { !hintStop.poll() && hintScalar(it) }) {
            val different = hintTruth(comparison, value)
            if (different == false) observeCompleteStage("DISEQUALITY_POINT_ON_ROW")
            if (different == true) {
                for (choice in choices) {
                    operationAccounting?.charge(1L)
                    if (operationStop() || pointHintStop()) break
                    val atom = (choice.payload as? SourceBoundPredicate)?.sourceBound
                    val side = if (atom != null)
                        sourceAtomHintComparison(atom) else {
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
        return if (preferred === first)
            choices.map { SearchDecision.Theory(it) }
        else
            listOf(SearchDecision.Theory(second), SearchDecision.Theory(first))
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
                bools[variable] = if (decision.literal and 1 == 0) LIRA_TRUE else FALSE
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
        if (!installSource() || !lp.atLevel(context.decisionLevel, lpStop)) return false
        val rootFixings = if (incrementalSourceRows && context.decisionLevel == 0) {
            sourceRootFixings() ?: return false
        } else
            null
        val refreshRoot = rootFixings != null && rootFixings != assertedRootFixings
        val selected = sourceRowsToAssert(refreshRoot) ?: return false
        val disequalities = ArrayList<Triple<RowAddress, ExactComparison, SearchAtomPremise>>()
        val comparisons = ArrayList<PremisedSmtComparison>()
        val indices = ArrayList<Int>()
        for (index in selected) {
            if (incrementalSourceRows) operationAccounting?.charge(1)
            if (lpStop.poll()) return false
            sourceRowVisits++
            val prepared = preparedRows[index]
            // Resolved source inputs stay fixed until the LP scope containing their assertion is popped.
            // A changed root fixing can simplify an already asserted source expression.
            if (sourceAssertionLevels[index] >= 0 &&
                (context.decisionLevel > 0 || (incrementalSourceRows && !refreshRoot))
            )
                continue
            val address = prepared.address
            val asserted = node.rowAssertions[address]
            if (prepared.disjunction && asserted == null) continue
            val truth = node.rowTruth(address, prepared.row, bools) ?: continue
            val comparison = prepared.form.comparison(truth) { bools[it] == LIRA_TRUE }
            val leaves = prepared.booleans.map { variable ->
                SearchAtomPremise.Asserted(SearchDecision.Bool(Lit.make(variable, bools[variable] == LIRA_TRUE)))
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
        if (!system.assertComparisons(comparisons, lpStop) { index ->
                val source = indices[index]
                if (sourceAssertionLevels[source] < 0 && context.decisionLevel > 0) scopedSourceAssertions.add(source)
                sourceAssertionLevels[source] = context.decisionLevel
            }
        )
            return false
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
            operationAccounting?.charge(1)
            if (lpStop.poll()) return null
            val lower = state.activeSide(column, false)?.side
            val upper = state.activeSide(column, true)?.side
            fixed += if (lower != null && upper != null && !lower.strict && !upper.strict &&
                lower.number.value == upper.number.value && lower.premises == null && upper.premises == null
            )
                lower.number.value else
                null
        }
        return fixed
    }

    private fun sourceRowsToAssert(refreshRoot: Boolean): Iterable<Int>? {
        if (!incrementalSourceRows) return preparedRows.indices
        if (sourceRowsByBoolean == null) {
            val dependencies = LinkedHashMap<Int, MutableList<Int>>()
            val recurring = ArrayList<Int>()
            for ((index, prepared) in preparedRows.withIndex()) {
                operationAccounting?.charge(2L + prepared.booleans.size)
                if (lpStop.poll()) return null
                for (variable in prepared.booleans) dependencies.getOrPut(variable) { ArrayList() } += index
                if (prepared.disjunction || prepared.row.relation == LinearOp.EQ ||
                    prepared.row.relation == LinearOp.NE)
                    recurring += index
            }
            operationAccounting?.charge(preparedRows.size.toLong() + dependencies.size + recurring.size + 1L)
            if (lpStop.poll()) return null
            sourceRowsByBoolean = dependencies
            recurringSourceRows += recurring
            pendingSourceRows += preparedRows.indices
        }
        val dependencies = requireNotNull(sourceRowsByBoolean)
        for (variable in changedSourceBooleans) {
            val rows = dependencies[variable].orEmpty()
            operationAccounting?.charge(rows.size.toLong() + 1L)
            if (lpStop.poll()) return null
            sourceRowTouches += rows.size
            pendingSourceRows += rows
        }
        changedSourceBooleans.clear()
        // Disjunction choices and disequality sides can change without assigning a source Boolean.
        operationAccounting?.charge(recurringSourceRows.size.toLong() + 1L)
        if (lpStop.poll()) return null
        pendingSourceRows += recurringSourceRows
        val count = if (refreshRoot) preparedRows.size else pendingSourceRows.size
        operationAccounting?.charge(count.toLong() * (1L + Int.SIZE_BITS - count.countLeadingZeroBits()))
        if (lpStop.poll()) return null
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
                )
                    return false
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
            if (lpStop.poll()) return LpBoundBatchResult.Declined(0)
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
        operationAccounting = operationStop.accounting
        lpStop.begin()
        hintStop.begin()
    }

    private fun endOperation(context: SearchContext) {
        lpStop.end()
        hintStop.end()
        if (operationStop() && !context.cancelled()) {
            operationBudgetExhausted = true
            linearOperationStops++
            context.executionControls.reportStop(SearchStopCause.OperationAllowance)
        }
        operationStop = parentStop
        operationAccounting = parentStop.accounting
    }

    private fun relaxWithin(context: SearchContext): ComponentResult {
        if (operationStop()) return ComponentResult.Indeterminate
        if (!dirty) return ComponentResult.Consistent
        floatCandidate = null
        pointHintValues = null
        // Root publications precede their delivery to components during shared initialization.
        if (context.decisionLevel == 0 && bools.any { it == UNASSIGNED } &&
            bools.indices.all { context.boolValue(it) != null }
        )
            return ComponentResult.Consistent
        val enforced = enforceDisjunctions(context)
        if (enforced !is ComponentResult.Consistent) return enforced
        // The implied row is not asserted yet; solving now would only be repeated once it is delivered.
        if (impliedDisjunct) return ComponentResult.Consistent
        if (model.numIntVars > 0 && bools.any { it == UNASSIGNED } && arithmeticRows.none {
                it.truthUnder(bools) != null
            }
        ) {
            return ComponentResult.Consistent
        }
        if (completeTraversalObserved && acceptsRetainedSourcePoint()) {
            if (!context.consumeCheck()) return ComponentResult.Indeterminate
            candidate = checkNotNull(retainedLpPoint).take(model.numRealVars)
            dirty = false
            observeCompleteStage("LP_SOURCE_POINT_RECHECK")
            if (unassignedBooleans > 0) capturePointHints(checkNotNull(candidate), context)
            else captureCompleteWitness(candidate)
            return if (operationStop()) ComponentResult.Indeterminate else ComponentResult.Consistent
        }
        if (operationStop()) return ComponentResult.Indeterminate
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
        if (!context.consumeCheck()) return ComponentResult.Indeterminate
        val retained = retainedLpPoint
        if (retained != null && (model.numIntVars == 0 || model.numRealVars > 0) &&
            solveContext.certificationPolicy === ProductionLpCertificationPolicy &&
            system.acceptsPoint(retained, lpStop)
        ) {
            candidate = retained.take(model.numRealVars + model.numIntVars)
            dirty = false
            observeCompleteStage("LP_POINT_RECHECK")
            if (bools.any { it == UNASSIGNED }) capturePointHints(checkNotNull(candidate), context)
            else captureCompleteWitness(candidate)
            return if (operationStop()) ComponentResult.Indeterminate else ComponentResult.Consistent
        }
        if (operationStop()) return ComponentResult.Indeterminate
        val complete = completeTraversalObserved && unassignedBooleans == 0
        if (complete) smtStats?.observeCompleteSearchLinearCheck()
        var result = measurePartialLinearPhase({ partialLinearNumericalNs += it }) {
            lp.solve(sparsePointRecovery = true, claim = LpClaim.FEASIBLE_COMPLETION)
        }
        if (result != null && result.verdict != LpVerdict.INFEASIBLE && system.usesStrictMargin &&
            !system.hasPositiveMargin(result.exactPrimal) && !operationStop()
        ) {
            result = lp.solve(sparsePointRecovery = true, claim = LpClaim.BOUND) ?: result
        }
        if (complete) {
            val key = if (result == null)
                "NO_RESULT" else
                listOf(
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
                    result.continuation?.run { "CONTINUATION_${decline?.name ?: "NO_DECLINE"}" } ?: "NO_CONTINUATION",
                ).joinToString("_")
            smtStats?.observeCompleteSearchLinearOutcome(key)
        }
        if (result == null) return ComponentResult.Indeterminate
        if (operationStop()) return ComponentResult.Indeterminate
        dirty = false
        candidate = result.exactPrimal?.takeIf(system::hasPositiveMargin)
            ?.take(model.numRealVars + model.numIntVars)
        if (candidate != null) retainedLpPoint = result.exactPrimal?.take(system.pointColumns)
        unconsumedLpPoint = candidate
        val marginConflict = system.usesStrictMargin && result.lowerBound?.let { it >= BigFraction.ZERO } == true
        if (result.verdict == LpVerdict.INFEASIBLE || marginConflict) {
            val support = if (marginConflict) result.bound?.support else result.conflictSupport
            val explanation = lp.explainConflict(support, context)
            smtStats?.observeConflict(explanation)
            outcome = ComponentCheck.Infeasible(explanation)
            LpClaim.CONTRADICTION.consumed(primaryLpObserver)
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
            operationAccounting?.charge(coordinates.toLong())
            if (!operationStop() && result.float.primal.size >= coordinates) {
                floatCandidate = result.float.primal.copyOf(coordinates)
            }
        }
        if (operationStop()) return ComponentResult.Indeterminate
        captureCompleteWitness(candidate)
        // Source integer branching requires an independently checked continuous witness.
        return ComponentResult.Consistent
    }

    private fun acceptsRetainedSourcePoint(): Boolean {
        val point = retainedLpPoint ?: return false
        if (model.numIntVars != 0 || solveContext.certificationPolicy !== ProductionLpCertificationPolicy ||
            !system.acceptsPoint(point, lpStop)
        ) return false
        val prior = sourceCheckedBooleans
        val dependencies = sourceRowsByBoolean
        val selected = if (sourceCheckedPoint === point && prior != null && dependencies != null) {
            val changed = LinkedHashSet<Int>()
            operationAccounting?.charge(bools.size.toLong() + 1L)
            for (variable in bools.indices) {
                if (prior[variable] == bools[variable]) continue
                val rows = dependencies[variable].orEmpty()
                operationAccounting?.charge(rows.size.toLong() + 1L)
                if (lpStop.poll()) return false
                changed += rows
            }
            operationAccounting?.charge(recurringSourceRows.size.toLong() + 1L)
            if (lpStop.poll()) return false
            changed += recurringSourceRows
            changed
        } else preparedRows.indices
        var visited = 0L
        for (index in selected) {
            visited++
            val prepared = preparedRows[index]
            operationAccounting?.charge(prepared.booleans.size.toLong() + 1L)
            if (lpStop.poll()) return false
            if (prepared.disjunction && prepared.address !in node.rowAssertions) continue
            val truth = node.rowTruth(prepared.address, prepared.row, bools) ?: continue
            val comparison = prepared.form.comparison(truth) { bools[it] == LIRA_TRUE }
            val direction = node.disequalityDirections[prepared.address]
            if (comparison.op == LinearOp.NE && direction == null) return false
            if (!system.checkedPointSatisfies(comparison, direction, lpStop)) {
                return false
            }
        }
        operationAccounting?.charge(bools.size.toLong() + 1L)
        if (operationStop()) return false
        sourceCheckedPoint = point
        if (prior == null) sourceCheckedBooleans = bools.copyOf() else bools.copyInto(prior)
        observeCompleteStage("LP_SOURCE_POINT_ROWS", visited)
        return true
    }

    private fun observeCompleteStage(stage: String, count: Long = 1L) {
        if (completeTraversalObserved) smtStats?.observeCompleteSearchStage(stage, count)
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
                bools[it] == LIRA_TRUE
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
        )
            return null
        val point = candidate ?: return null
        operationAccounting?.charge(model.numIntVars.toLong() + model.numBoolVars + preparedRows.size + 1L)
        if (operationStop() || bools.any { it == UNASSIGNED } ||
            node.nextDisequality(model, disjunctionAtoms, bools) != null || !context.consumeCheck()
        )
            return null
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
                val floor = floorDiv(value.num, value.den)
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
        )
            return
        acceptWitness(point)?.let {
            assignment = it
            outcome = ComponentCheck.Feasible
            if (point === unconsumedLpPoint) {
                unconsumedLpPoint = null
                LpClaim.FEASIBLE_COMPLETION.consumed(primaryLpObserver)
            }
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
        fun prepareSource(fragment: Problem, stop: ExecutionControl): PreparedExactLiraComponentFactory? {
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

private const val UNASSIGNED = -1
private const val DECLINED_HINT = -2
private const val MAX_POINT_HINT_ENTRIES = 65_536
private const val MAX_POINT_HINT_BITS = 4096
private const val FALSE = 0
internal const val LIRA_TRUE = 1

private fun IntArray.toCompleteValues(): BooleanArray = BooleanArray(size) { variable ->
    when (this[variable]) {
        LIRA_TRUE -> true
        FALSE -> false
        else -> error("exact LIRA witness requested before Boolean assignment was complete")
    }
}

internal fun LinearRow.truthUnder(bools: IntArray): Boolean? {
    for (k in 0 until size) {
        val reference = ref(k)
        if (Term.isBool(reference) && bools[Lit.variable(Term.lit(reference))] == UNASSIGNED) return null
    }
    if (activator == LinearRow.ALWAYS) return true
    return when (bools[activator]) {
        LIRA_TRUE -> true
        FALSE -> false
        else -> null
    }
}

/** Outcome of the Double-Bounded Reduction at one Boolean/disjunction leaf. */
// Source integer values beyond the largest exactly representable double are a useful diagnostic for
// model conversions that would otherwise silently lose an integer unit.
private val WIDE_INTEGER_LIMIT = bigIntOf(1L shl 53)

private fun List<ExactRationalInequality>.hasWideIntegerData(): Boolean = any { row ->
    (row.rhs.den == BIG_ONE && row.rhs.num.abs() > WIDE_INTEGER_LIMIT) ||
        row.coefficients.any { coefficient ->
            coefficient.den == BIG_ONE && coefficient.num.abs() > WIDE_INTEGER_LIMIT
        }
}
