package com.eignex.klause.backtrack.lp

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.lp.bounding.LpHintSink
import com.eignex.klause.lp.bounding.LpHintUnavailable
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.ExactLpModel
import com.eignex.klause.lp.engine.LpDirectionalCost
import com.eignex.klause.lp.engine.LpDirectionalValues
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpProbeSource
import com.eignex.klause.lp.engine.LpScopedRows
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import com.eignex.util.numbers.CheckedLongOverflowException
import com.eignex.util.numbers.magnitudeBitLength
import com.eignex.util.numbers.subExact
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Per-call cache of the most recent fractional LP value of each variable, for LP-guided value
 * ordering. Filled by the node LP solve and read by the descent to order branch values toward
 * the LP value (round-toward-LP diving). Purely advisory — it reorders candidate values the value
 * heuristic already produced, so it can change search order but never the optimum or feasibility.
 *
 * `NaN` means "no current LP value" (the LP has not solved for this variable, or not at this node),
 * in which case ordering is left untouched.
 */
internal class LpHints(
    numIntVars: Int,
    numBoolVars: Int,
    private val directionalGains: Boolean = false,
    private val observe: (String) -> Unit = {},
    private val charge: (Long) -> Unit = {},
    private val rootBasis: Boolean = false,
    private val freshVariableProbes: Boolean = false,
    private val basisVariableComparisons: Boolean = false,
    private val verifyBasisVariables: Boolean = false,
) : LpHintSink {
    private data class SideAdvice(val variable: VarRef, val split: Long, val up: Boolean, val basis: Boolean)

    private val collect = directionalGains && numIntVars.toLong() + numBoolVars <= 8192L
    private val history = LpDirectionalHistory(observe, charge = charge)
    private var freshRecord = false
    private var pointRelaxation: LpRelaxation? = null
    private var pointPrimal: DoubleArray? = null
    private var pointBasis: Basis? = null
    private var pendingSide: SideAdvice? = null
    private var activeSide: SideAdvice? = null
    private var rootBasisAttempted = false
    private var pointProbed = false
    private var lastProbeState: LpExactState? = null
    private var variableProbeProposal = false
    private var variableBasisProposal = false
    private var variableBasisAttempts = 0
    private var basisVerificationAttempted = false
    private var unproductiveVariableBasisAttempts = 0
    private var lastVariableBasisState: LpExactState? = null
    private var boundRecorded = false
    private var unavailable = LpHintUnavailable.UNOBSERVED
    private var scopeModel: ExactLpModel? = null
    private var scopeRows: LpScopedRows? = null
    private var scopeVariables = IntArray(0)
    private var scopeBooleans = BooleanArray(0)
    private var scopeConstant = 0L
    private var scopeCutoff = Double.NaN
    private var scopeChecked = false
    private var scopeIncumbentVersion = 0L
    private var scopeExternalExact: BigFraction? = null
    private var cutoff = Double.NaN
    private var incumbentVersion = 0L
    private var externalExact: BigFraction? = null

    fun beginNode(depth: Int, bound: Double, incumbentVersion: Long = 0L, externalExact: BigFraction? = null) {
        if (!collect) return
        if (scopeModel != null) charge(1L)
        cutoff = bound
        this.incumbentVersion = incumbentVersion
        this.externalExact = externalExact
        freshRecord = false
        pointRelaxation = null
        pointPrimal = null
        pointBasis = null
        pendingSide = null
        activeSide = null
        boundRecorded = false
        variableProbeProposal = false
        variableBasisProposal = false
        pointProbed = false
        unavailable = LpHintUnavailable.UNOBSERVED
        history.beginNode(depth)
    }

    override fun recordBound(relaxation: LpRelaxation, bound: Double) =
        recordEstimate(relaxation, bound, checked = true)

    override fun recordObjective(relaxation: LpRelaxation, objective: Double) =
        recordEstimate(relaxation, objective, checked = false)

    private fun recordEstimate(relaxation: LpRelaxation, bound: Double, checked: Boolean) {
        boundRecorded = false
        if (!collect || relaxation.colVarId.size > 8192 || !freshRecord ||
            pointRelaxation?.model !== relaxation.model
        ) {
            recordUnavailable(LpHintUnavailable.MODEL)
            return
        }
        val state = relaxation.model.exactState ?: run {
            recordUnavailable(LpHintUnavailable.MODEL)
            return
        }
        charge(2L * relaxation.colVarId.size + 3L + cutoffComparisonWork())
        val incompatibleModel = !scopeVariables.contentEquals(relaxation.colVarId) ||
            !scopeBooleans.contentEquals(relaxation.colIsBool) ||
            scopeConstant != relaxation.objectiveConstant || scopeChecked != checked ||
            (scopeModel !== state.baseModel && !rowExtension(state.baseModel))
        val changedContext = scopeModel !== state.baseModel || scopeRows !== state.rows || scopeCutoff != cutoff ||
            scopeIncumbentVersion != incumbentVersion || scopeExternalExact != externalExact
        if (incompatibleModel || changedContext) {
            pointBasis = null
            lastProbeState = null
            lastVariableBasisState = null
            if (scopeModel != null) observe("INCOMPATIBLE")
            charge(if (incompatibleModel) 2L * relaxation.colVarId.size + 9L else 5L)
            if (incompatibleModel) {
                history.clear(report = scopeModel != null)
                scopeVariables = relaxation.colVarId.copyOf()
                scopeBooleans = relaxation.colIsBool.copyOf()
                scopeConstant = relaxation.objectiveConstant
                scopeChecked = checked
            } else history.clearComparisons()
            scopeModel = state.baseModel
            scopeRows = state.rows
            scopeCutoff = cutoff
            scopeIncumbentVersion = incumbentVersion
            scopeExternalExact = externalExact
        }
        // The scoped constant cancels in every gain; adding it can round away a small difference.
        boundRecorded = bound.isFinite()
        if (boundRecorded) observe(if (checked) "BOUND_RECORDED" else "OPTIMUM_RECORDED")
        recordBasisProgress(history.record(bound))
    }

    private fun rowExtension(model: ExactLpModel): Boolean {
        val previous = scopeModel ?: return false
        charge(10L)
        if (!history.hasObservations) return false
        if (model.n != previous.n || model.m <= previous.m) return false
        if (!sameObjectiveUnits(model, previous)) return false
        val objective = model.objective
        val oldObjective = previous.objective
        val layoutWork = 3L * previous.numVars + 2L * (model.numVars - previous.numVars) +
            2L * previous.m + 2L * previous.n
        val remaining = 8192L
        if (layoutWork > remaining) return false
        charge(layoutWork)
        for (column in 0 until previous.numVars) {
            if (model.column(column) !== previous.column(column) ||
                objective.cost(column) !== oldObjective.cost(column)
            ) return false
        }
        for (column in previous.numVars until model.numVars) {
            if (objective.cost(column).legacyLong() != 0L) return false
        }
        for (row in 0 until previous.m) {
            if (model.row(row) !== previous.row(row) || model.rhs(row) !== previous.rhs(row)) return false
        }
        if (!unchangedMatrixPrefix(model, previous, remaining - layoutWork)) return false
        charge(1L)
        observe("ROW_EXTENSION")
        return true
    }

    private fun sameObjectiveUnits(model: ExactLpModel, previous: ExactLpModel): Boolean =
        model.objective.constant === previous.objective.constant &&
            model.objective.scale === previous.objective.scale &&
            model.objective.externalConstant === previous.objective.externalConstant &&
            model.objective.sense == previous.objective.sense

    private fun unchangedMatrixPrefix(model: ExactLpModel, previous: ExactLpModel, allowance: Long): Boolean {
        var remaining = allowance
        for (column in 0 until previous.n) {
            val before = previous.columnEntries(column)
            val after = model.columnEntries(column)
            val work = 3L * before.size + 3L
            if (work > remaining) return false
            charge(work)
            remaining -= work
            if (after.size < before.size) return false
            for (entry in before.indices) if (after[entry] !== before[entry]) return false
            if (after.size > before.size && after[before.size].row < previous.m) return false
        }
        return true
    }

    private fun cutoffComparisonWork(): Long {
        if (scopeExternalExact === externalExact) return 0L
        val oldBits = scopeExternalExact?.let { maxOf(it.num.magnitudeBitLength(), it.den.magnitudeBitLength()) } ?: 0
        val newBits = externalExact?.let { maxOf(it.num.magnitudeBitLength(), it.den.magnitudeBitLength()) } ?: 0
        return 2L * ((maxOf(oldBits, newBits).toLong() + 63L) / 64L)
    }

    override fun recordInfeasible() = history.infeasible()

    override fun recordUnavailable(reason: LpHintUnavailable) {
        if (!collect) return
        if (scopeModel != null) charge(1L)
        unavailable = reason
        pointBasis = null
    }

    override val finiteBoundWork: Long get() = if (collect && directionalGains && scopeChecked &&
        !boundRecorded && history.hasPendingChild
    ) 15L + cutoffComparisonWork() + basisProgressWork else 0L

    private val basisProgressWork: Long get() = if (basisVariableComparisons &&
        unproductiveVariableBasisAttempts > 0 && variableBasisAttempts < MAX_VARIABLE_BASIS_ATTEMPTS
    ) 2L else 0L

    private fun recordBasisProgress(gain: Double) {
        if (!(gain > 0.0)) return
        val work = basisProgressWork
        if (work == 0L) return
        charge(work)
        if (unproductiveVariableBasisAttempts >= MAX_UNPRODUCTIVE_VARIABLE_BASIS_ATTEMPTS) {
            observe("VARIABLE_BASIS_REOPENED")
        }
        unproductiveVariableBasisAttempts = 0
    }

    override fun recordFiniteBound(state: LpExactState?, sourceBound: Long) {
        if (!finiteBoundCompatible(state)) return
        charge(3L)
        if (sourceBound == Long.MIN_VALUE) {
            observe("FINITE_BOUND_UNAVAILABLE")
            return
        }
        val value = try {
            subExact(sourceBound, scopeConstant)
        } catch (_: CheckedLongOverflowException) {
            observe("FINITE_BOUND_UNAVAILABLE")
            return
        }
        observe("FINITE_BOUND_PREPARED")
        val gain = history.observeChild(value.toDouble())
        if (gain.isFinite()) {
            recordBasisProgress(gain)
            charge(1L)
            observe("FINITE_BOUND_RECORDED")
        }
    }

    private fun finiteBoundCompatible(state: LpExactState?): Boolean {
        if (finiteBoundWork == 0L || state == null || scopeModel !== state.baseModel || scopeRows !== state.rows
        ) return false
        charge(4L + cutoffComparisonWork())
        return scopeCutoff == cutoff && scopeIncumbentVersion == incumbentVersion && scopeExternalExact == externalExact
    }

    fun finishNode(state: LpExactState? = null) {
        if (!collect) return
        history.finishNode(unavailable.name)
        if (scopeModel != null && state != null) {
            if (scopeModel !== state.baseModel && !rowExtension(state.baseModel)) {
                observe("INCOMPATIBLE")
                resetHistory()
            } else if (scopeModel !== state.baseModel || scopeRows !== state.rows) {
                observe("INCOMPATIBLE")
                charge(8L)
                history.clearComparisons()
                scopeModel = state.baseModel
                scopeRows = state.rows
                freshRecord = false
                pointRelaxation = null
                pointPrimal = null
                pointBasis = null
                pendingSide = null
                activeSide = null
                boundRecorded = false
            }
        }
    }

    fun branch(variable: VarRef, domain: IntDomain? = null) {
        activeSide = pendingSide?.takeIf {
            val point = if (it.variable == variable) value(variable, domain) else Double.NaN
            point.isFinite() && floor(point) == it.split.toDouble()
        }
        pendingSide = null
        if (collect && scopeModel != null) {
            history.branch(variable, if (boundRecorded) value(variable, domain) else Double.NaN)
        }
    }

    fun commit(decision: SearchDecision, depth: Int) {
        if (collect && scopeModel != null) {
            history.commit(decision, depth)
        }
    }

    fun prepareVariableComparison(variables: Int): Boolean {
        if (!collect || !freshRecord || variables !in 1..8192 ||
            !boundRecorded || !history.mayHaveScores
        ) return false
        charge(3L * variables + 4L)
        observe("VARIABLE_COMPARISON")
        return true
    }

    fun compareVariables(
        first: VarRef?,
        firstDomain: IntDomain?,
        second: VarRef?,
        secondDomain: IntDomain?,
    ): VarRef? {
        variableProbeProposal = false
        variableBasisProposal = false
        first ?: return null
        val firstScore = directionalScore(first, firstDomain)
        val secondScore = second?.let { directionalScore(it, secondDomain) } ?: Double.NaN
        if (!firstScore.isFinite() || !secondScore.isFinite()) {
            charge(1L)
            observe("VARIABLE_COMPARISON_INCOMPLETE")
            return null
        }
        val proposal = when {
            secondScore > 1e-9 && secondScore > firstScore -> second
            firstScore > 1e-9 -> first
            else -> null
        }
        if (proposal != null) {
            charge(1L)
            observe("VARIABLE_ESTIMATED")
        }
        return proposal
    }

    fun applyVariableChoice(proposal: VarRef?, baseline: VarRef?): VarRef? {
        val probed = variableProbeProposal
        val basis = variableBasisProposal
        variableProbeProposal = false
        variableBasisProposal = false
        proposal ?: return baseline
        if (basis && verifyBasisVariables) return baseline
        charge(2L)
        observe("VARIABLE_WITHHELD")
        if (probed) observe("VARIABLE_PROBE_WITHHELD")
        if (basis) observe("VARIABLE_BASIS_WITHHELD")
        observe(if (proposal == baseline) "VARIABLE_PROPOSAL_AGREED" else "VARIABLE_PROPOSAL_CHANGED")
        return baseline
    }

    fun prepareBasisVariableComparison(variables: Int): Boolean {
        if (!basisVariableChoiceReady || variables !in 1..8192) return false
        charge(2L * variables + 4L)
        observe("VARIABLE_BASIS_SCAN")
        return true
    }

    private val basisVariableChoiceReady: Boolean get() = collect && basisVariableComparisons &&
        !history.isRootNode && freshRecord && boundRecorded && pointBasis != null &&
        basisVariableAttemptsAvailable &&
        pointRelaxation?.model?.exactState?.let { it !== lastVariableBasisState } == true

    private val basisVariableAttemptsAvailable: Boolean get() =
        variableBasisAttempts < MAX_VARIABLE_BASIS_ATTEMPTS &&
            unproductiveVariableBasisAttempts < MAX_UNPRODUCTIVE_VARIABLE_BASIS_ATTEMPTS &&
            (!verifyBasisVariables || !basisVerificationAttempted)

    val basisVerificationRequired: Boolean get() = verifyBasisVariables

    val basisComparisonFollowupWork: Long get() = if (verifyBasisVariables) 64L else 48L

    fun compareBasisVariables(
        first: VarRef?,
        firstDomain: IntDomain?,
        second: VarRef?,
        secondDomain: IntDomain?,
        reserve: (Long) -> Boolean,
        estimate: (LpModel, IntArray) -> List<LpDirectionalCost?>?,
    ): VarRef? {
        variableProbeProposal = false
        variableBasisProposal = false
        if (!basisVariableChoiceReady || first == null || second == null) return null
        val relaxation = pointRelaxation ?: return null
        val firstValue = value(first, firstDomain)
        val secondValue = value(second, secondDomain)
        if (!fractional(first, firstValue) || !fractional(second, secondValue)) return null
        val firstColumn = relaxation.column(first)
        val secondColumn = relaxation.column(second)
        if (firstColumn < 0 || secondColumn < 0 || firstColumn == secondColumn) return null
        variableBasisAttempts++
        unproductiveVariableBasisAttempts++
        lastVariableBasisState = relaxation.model.exactState
        observe("VARIABLE_BASIS_ATTEMPT")
        if (!reserve(20L)) {
            return declineBasisVariableChoice("VARIABLE_BASIS_UNFUNDED")
        }
        val costs = estimate(relaxation.model, intArrayOf(firstColumn, secondColumn))
        if (costs == null || costs.size != 2) {
            return declineBasisVariableChoice("VARIABLE_BASIS_DECLINED")
        }
        charge(26L)
        val firstCost = costs[0] ?: return declineBasisVariableChoice("VARIABLE_BASIS_MISSING_DIRECTION")
        val secondCost = costs[1] ?: return declineBasisVariableChoice("VARIABLE_BASIS_MISSING_DIRECTION")
        val firstScore = basisScore(firstCost, firstValue)
        val secondScore = basisScore(secondCost, secondValue)
        if (!firstScore.isFinite() || !secondScore.isFinite()) {
            return declineBasisVariableChoice("VARIABLE_BASIS_MISSING_DIRECTION")
        }
        observe("VARIABLE_BASIS_COMPARED")
        if (secondScore <= 1e-9 || secondScore <= firstScore) {
            return declineBasisVariableChoice("VARIABLE_BASIS_BASELINE")
        }
        charge(16L)
        val firstFraction = firstValue - floor(firstValue)
        val secondFraction = secondValue - floor(secondValue)
        if (secondCost.down * secondFraction < firstCost.down * firstFraction ||
            secondCost.up * (1.0 - secondFraction) < firstCost.up * (1.0 - firstFraction)
        ) {
            return declineBasisVariableChoice("VARIABLE_BASIS_DIRECTION_TRADEOFF")
        }
        unproductiveVariableBasisAttempts = 0
        variableBasisProposal = true
        observe("VARIABLE_BASIS_ESTIMATED")
        return second
    }

    private fun declineBasisVariableChoice(outcome: String): VarRef? {
        observe(outcome)
        if (unproductiveVariableBasisAttempts >= MAX_UNPRODUCTIVE_VARIABLE_BASIS_ATTEMPTS) {
            observe("VARIABLE_BASIS_STOPPED")
        }
        return null
    }

    val probeVariableChoiceReady: Boolean get() = collect &&
        (if (verifyBasisVariables) variableBasisProposal && !basisVerificationAttempted else freshVariableProbes) &&
        !history.isRootNode &&
        freshRecord && boundRecorded && pointBasis != null && canProbePoint

    private val canProbePoint: Boolean get() = !pointProbed &&
        pointRelaxation?.model?.exactState?.let { it !== lastProbeState } == true

    override fun recordProbeBasis(relaxation: LpRelaxation, basis: Basis) {
        val basisComparison = basisVariableComparisons && basisVariableAttemptsAvailable
        if (!collect || (!freshVariableProbes && !basisComparison) || history.isRootNode ||
            !freshRecord || !boundRecorded ||
            pointRelaxation?.model !== relaxation.model
        ) return
        charge(2L)
        pointBasis = basis
        if (freshVariableProbes) observe("VARIABLE_PROBE_SOURCE")
        if (basisComparison) observe("VARIABLE_BASIS_SOURCE")
    }

    fun compareProbeVariables(
        first: VarRef?,
        firstDomain: IntDomain?,
        second: VarRef?,
        secondDomain: IntDomain?,
        reserve: (Long) -> Boolean,
        estimate: (LpProbeSource, IntArray, LongArray) -> List<LpDirectionalValues>?,
    ): VarRef? {
        val ready = probeVariableChoiceReady
        variableBasisProposal = false
        variableProbeProposal = false
        if (!ready || first == null || second == null) return null
        val relaxation = pointRelaxation ?: return null
        val basis = pointBasis ?: return null
        val firstValue = value(first, firstDomain)
        val secondValue = value(second, secondDomain)
        if (!fractional(first, firstValue) || !fractional(second, secondValue)) return null
        val firstColumn = relaxation.column(first)
        val secondColumn = relaxation.column(second)
        if (firstColumn < 0 || secondColumn < 0 || firstColumn == secondColumn) return null
        if (verifyBasisVariables) {
            charge(1L)
            basisVerificationAttempted = true
            observe("VARIABLE_BASIS_VERIFICATION_ATTEMPT")
        }
        pointProbed = true
        lastProbeState = relaxation.model.exactState
        observe("VARIABLE_PROBE_ATTEMPT")
        if (!reserve(20L)) {
            observe("VARIABLE_PROBE_UNFUNDED")
            return null
        }
        val values = estimate(LpProbeSource(relaxation.model, basis), intArrayOf(firstColumn, secondColumn),
            longArrayOf(floor(firstValue).toLong(), floor(secondValue).toLong()))
        if (values == null || values.size != 2) {
            observe("VARIABLE_PROBE_DECLINED")
            return null
        }
        charge(18L)
        val firstValues = values[0]
        val secondValues = values[1]
        if (!firstValues.down.isFinite() || !firstValues.up.isFinite() ||
            !secondValues.down.isFinite() || !secondValues.up.isFinite()
        ) {
            observe("VARIABLE_PROBE_MISSING_DIRECTION")
            return null
        }
        val improvement = minOf(secondValues.down, secondValues.up) - minOf(firstValues.down, firstValues.up)
        val strongImprovement = maxOf(secondValues.down, secondValues.up) -
            maxOf(firstValues.down, firstValues.up)
        if (!improvement.isFinite() || !strongImprovement.isFinite()) {
            observe("VARIABLE_PROBE_MISSING_DIRECTION")
            return null
        }
        observe("VARIABLE_PROBE_COMPARED")
        val strongerTie = improvement in 0.0..1e-9 && strongImprovement > 1e-9
        if (improvement <= 1e-9 && !strongerTie) {
            observe(if (improvement < -1e-9) "VARIABLE_PROBE_BASELINE" else "VARIABLE_PROBE_TIED")
            return null
        }
        if (secondValues.down < firstValues.down || secondValues.up < firstValues.up) {
            observe("VARIABLE_PROBE_DIRECTION_TRADEOFF")
            return null
        }
        if (strongerTie) observe("VARIABLE_PROBE_STRONGER_TIE")
        variableProbeProposal = true
        observe("VARIABLE_PROBE_ESTIMATED")
        return second
    }

    private fun basisScore(cost: LpDirectionalCost, value: Double): Double {
        if (!cost.down.isFinite() || !cost.up.isFinite() || cost.down < 0.0 || cost.up < 0.0) return Double.NaN
        val fraction = value - floor(value)
        val down = cost.down * fraction
        val up = cost.up * (1.0 - fraction)
        return minOf(down, up) + maxOf(down, up) / 6.0
    }

    fun directionalScore(variable: VarRef, domain: IntDomain? = null): Double =
        directionalPrediction(variable, value(variable, domain))?.score ?: Double.NaN

    private fun directionalPrediction(variable: VarRef, value: Double): LpDirectionalHistory.Prediction? {
        if (!collect || !directionalGains || !boundRecorded) return null
        charge(1L)
        return if (history.mayHaveScores) history.prediction(variable, value) else null
    }

    val rootChoiceReady: Boolean get() = collect && rootBasis && freshRecord && history.isRootNode &&
        !rootBasisAttempted && activeSide == null

    fun selectRootAlternatives(
        variable: VarRef,
        domain: IntDomain?,
        alternatives: List<SearchDecision>,
        allowBasis: Boolean = true,
        reserve: (Long) -> Boolean = { work ->
            charge(work)
            true
        },
        estimate: (LpModel, IntArray) -> List<LpDirectionalCost?>?,
    ): List<SearchDecision> {
        if (!rootChoiceReady) return orderAlternatives(variable, alternatives, allowBasis)
        if (alternatives.size != 2) return alternatives
        if (!reserve(32L)) return alternatives
        val first = alternatives[0]
        val second = alternatives[1]
        val split = when (variable) {
            is VarRef.Bool -> {
                if (first !is SearchDecision.Bool || second !is SearchDecision.Bool ||
                    (first.literal ushr 1) != variable.varId || (first.literal xor second.literal) != 1
                ) return alternatives
                0L
            }
            is VarRef.IntVar -> {
                val lower = (first as? SearchDecision.IntAtMost) ?: (second as? SearchDecision.IntAtMost)
                    ?: return alternatives
                val upper = (first as? SearchDecision.IntAtLeast) ?: (second as? SearchDecision.IntAtLeast)
                    ?: return alternatives
                if (lower.variable != variable.varId || upper.variable != variable.varId ||
                    lower.upper == Long.MAX_VALUE || upper.lower != lower.upper + 1L ||
                    domain == null || lower.upper < domain.min || lower.upper >= domain.max
                ) return alternatives
                lower.upper
            }
        }
        observe("BASIS_PARTITION_PREPARED")
        prepareSide(variable, domain, allowBasis = true, rootOnly = true, estimate = estimate)
        val advice = pendingSide
        activeSide = advice
        pendingSide = null
        val changedPartition: Boolean
        val proposed: List<SearchDecision>
        if (advice?.basis == true && variable is VarRef.IntVar && split != advice.split) {
            val lower = SearchDecision.IntAtMost(variable.varId, advice.split)
            val upper = SearchDecision.IntAtLeast(variable.varId, advice.split + 1L)
            changedPartition = true
            proposed = if (first is SearchDecision.IntAtMost) listOf(lower, upper) else listOf(upper, lower)
        } else {
            changedPartition = false
            proposed = alternatives
        }
        val ordered = orderAlternatives(variable, proposed, allowBasis)
        if (advice?.basis != true) return ordered
        if (!allowBasis) return alternatives
        if (changedPartition) observe("BASIS_PARTITION_CHANGED")
        observe(if (changedPartition || ordered[0] != alternatives[0]) "BASIS_ROOT_CHOICE_CHANGED"
            else "BASIS_ROOT_CHOICE_AGREED")
        return ordered
    }

    fun prepareSelectedSide(
        variable: VarRef?,
        domain: IntDomain?,
        estimate: (LpModel, IntArray) -> List<LpDirectionalCost?>?,
    ): Boolean = prepareSide(variable, domain, allowBasis = rootBasis, rootOnly = true, estimate = estimate)

    fun prepareSide(
        variable: VarRef?,
        domain: IntDomain?,
        allowBasis: Boolean = true,
        rootOnly: Boolean = false,
        estimate: (LpModel, IntArray) -> List<LpDirectionalCost?>?,
    ): Boolean {
        pendingSide = null
        activeSide = null
        if (!collect || !directionalGains || !freshRecord || variable == null) return false
        val value = value(variable, domain)
        if (!fractional(variable, value)) return false
        val prediction = directionalPrediction(variable, value)
        if (prediction != null) {
            return prediction.score > 1e-9 &&
                rememberSide(variable, value, prediction.down, prediction.up, basis = false)
        }
        if (!allowBasis || (rootOnly && (!history.isRootNode || rootBasisAttempted))) return false
        val relaxation = pointRelaxation ?: return false
        val column = relaxation.column(variable)
        if (column < 0) return false
        charge(if (rootOnly) 5L else 4L)
        if (rootOnly) rootBasisAttempted = true
        observe("BASIS_ATTEMPT")
        val costs = estimate(relaxation.model, intArrayOf(column))
        if (costs == null || costs.size != 1) {
            observe("BASIS_DECLINED")
            return false
        }
        val cost = costs[0]
        if (cost == null || !cost.down.isFinite() || !cost.up.isFinite() || cost.down < 0.0 || cost.up < 0.0) {
            observe("BASIS_MISSING_DIRECTION")
            return false
        }
        val fraction = value - floor(value)
        val down = cost.down * fraction
        val up = cost.up * (1.0 - fraction)
        val score = minOf(down, up) + maxOf(down, up) / 6.0
        if (!score.isFinite() || score <= 1e-9) return false
        observe("BASIS_ESTIMATED")
        return rememberSide(variable, value, down, up, basis = true)
    }

    private fun rememberSide(variable: VarRef, value: Double, down: Double, up: Double, basis: Boolean): Boolean {
        charge(8L)
        if (down == up) return false
        pendingSide = SideAdvice(variable, floor(value).toLong(), up > down, basis)
        return true
    }

    fun orderAlternatives(
        variable: VarRef,
        alternatives: List<SearchDecision>,
        allowBasis: Boolean = true,
    ): List<SearchDecision> {
        val advice = activeSide ?: return alternatives
        activeSide = null
        return applySide(variable, alternatives, advice, allowBasis)
    }

    private fun applySide(
        variable: VarRef,
        alternatives: List<SearchDecision>,
        advice: SideAdvice,
        allowBasis: Boolean,
    ): List<SearchDecision> {
        charge(12L)
        if (advice.variable != variable) {
            observe("SIDE_FALLBACK")
            observe(if (advice.basis) "BASIS_SIDE_FALLBACK_VARIABLE" else "HISTORY_SIDE_FALLBACK_VARIABLE")
            return alternatives
        }
        if (alternatives.size != 2) {
            observe("SIDE_FALLBACK")
            observe(if (advice.basis) "BASIS_SIDE_FALLBACK_ARITY" else "HISTORY_SIDE_FALLBACK_ARITY")
            return alternatives
        }
        val firstUp = alternatives[0].matchesSide(advice, up = true)
        val firstDown = alternatives[0].matchesSide(advice, up = false)
        if (!(firstUp && alternatives[1].matchesSide(advice, up = false)) &&
            !(firstDown && alternatives[1].matchesSide(advice, up = true))
        ) {
            observe("SIDE_FALLBACK")
            observe(if (advice.basis) "BASIS_SIDE_FALLBACK_PARTITION" else "HISTORY_SIDE_FALLBACK_PARTITION")
            return alternatives
        }
        if (advice.basis && !allowBasis) {
            observe("BASIS_SIDE_WITHHELD")
            return alternatives
        }
        observe(if (advice.basis) "BASIS_SIDE_CONSUMED" else "HISTORY_SIDE_CONSUMED")
        observe("SIDE_CONSUMED")
        val changed = firstUp != advice.up
        observe(if (changed) "SIDE_BASELINE_CHANGED" else "SIDE_BASELINE_AGREED")
        return if (changed) listOf(alternatives[1], alternatives[0]) else alternatives
    }

    private fun SearchDecision.matchesSide(advice: SideAdvice, up: Boolean): Boolean = when (this) {
        is SearchDecision.Bool ->
            (advice.variable as? VarRef.Bool)?.varId == (literal ushr 1) && (literal and 1 == 0) == up
        is SearchDecision.IntAtMost ->
            !up && (advice.variable as? VarRef.IntVar)?.varId == variable && upper == advice.split
        is SearchDecision.IntAtLeast ->
            up && (advice.variable as? VarRef.IntVar)?.varId == variable && lower == advice.split + 1L
        else -> false
    }

    private fun LpRelaxation.column(variable: VarRef): Int = when (variable) {
        is VarRef.IntVar -> intColOf.getOrElse(variable.varId) { -1 }
        is VarRef.Bool -> boolColOf.getOrElse(variable.varId) { -1 }
    }

    private fun fractional(variable: VarRef, value: Double): Boolean = value.isFinite() &&
        value - floor(value) in INT_TOL..(1.0 - INT_TOL) &&
        (variable !is VarRef.Bool || value in 0.0..1.0)

    private fun value(variable: VarRef, domain: IntDomain? = null): Double {
        if (!freshRecord) return Double.NaN
        val legacy = when (variable) {
            is VarRef.IntVar -> if (intStamp.getOrElse(variable.varId) { -1 } == solveStamp) {
                intVal.getOrElse(variable.varId) { Double.NaN }
            } else Double.NaN
            is VarRef.Bool -> if (boolStamp.getOrElse(variable.varId) { -1 } == solveStamp) {
                boolVal.getOrElse(variable.varId) { Double.NaN }
            } else Double.NaN
        }
        val snapshot = pointPrimal
        val value = if (snapshot != null) {
            charge(3L)
            val column = pointRelaxation?.column(variable) ?: -1
            val point = snapshot.getOrElse(column) { Double.NaN }
            // Advice must describe the split supplied by the unchanged fallback value ordering.
            if (variable is VarRef.IntVar && floor(point) != floor(legacy)) Double.NaN else point
        } else legacy
        if (domain != null && value.isFinite()) {
            val lower = floor(value)
            charge(2L)
            if (lower != value && (lower.toLong() !in domain || (lower + 1.0).toLong() !in domain)) {
                return Double.NaN
            }
        }
        return value
    }

    private val intVal by lazy(LazyThreadSafetyMode.NONE) { DoubleArray(numIntVars) { Double.NaN } }
    private val boolVal by lazy(LazyThreadSafetyMode.NONE) { DoubleArray(numBoolVars) { Double.NaN } }

    // Freshness stamps: hints are consulted only when written by the latest LP solve. Values are
    // never invalidated on backtrack, so without the stamp a hint recorded in an abandoned subtree
    // would keep steering value order at unrelated nodes.
    private val intStamp by lazy(LazyThreadSafetyMode.NONE) { IntArray(numIntVars) { -1 } }
    private val boolStamp by lazy(LazyThreadSafetyMode.NONE) { IntArray(numBoolVars) { -1 } }
    private var solveStamp = 0

    // Decayed running average of `|reduced cost|` each variable showed while nonbasic, a
    // reduced-cost pseudo-cost (an estimate of the objective's sensitivity to the variable). NaN = the
    // variable has never been nonbasic, so there is no estimate yet. Read by [branchScore]; advisory.
    private val intRc by lazy(LazyThreadSafetyMode.NONE) { DoubleArray(numIntVars) { Double.NaN } }
    private val boolRc by lazy(LazyThreadSafetyMode.NONE) { DoubleArray(numBoolVars) { Double.NaN } }

    private var recorded = false

    fun resetHistory() {
        if (!collect) return
        if (scopeModel != null) {
            charge(scopeVariables.size.toLong() + scopeBooleans.size)
            history.clear()
        }
        scopeModel = null
        scopeRows = null
        scopeVariables = IntArray(0)
        scopeBooleans = BooleanArray(0)
        scopeChecked = false
        scopeIncumbentVersion = 0L
        scopeExternalExact = null
        incumbentVersion = 0L
        externalExact = null
        freshRecord = false
        pointRelaxation = null
        pointPrimal = null
        pointBasis = null
        pendingSide = null
        activeSide = null
        boundRecorded = false
        unavailable = LpHintUnavailable.UNOBSERVED
        variableProbeProposal = false
        lastProbeState = null
        variableBasisProposal = false
        lastVariableBasisState = null
        pointProbed = false
    }

    override fun clear() {
        resetHistory()
        if (!recorded) return
        intVal.fill(Double.NaN)
        boolVal.fill(Double.NaN)
        intRc.fill(Double.NaN)
        boolRc.fill(Double.NaN)
        intStamp.fill(-1)
        boolStamp.fill(-1)
        solveStamp = 0
        recorded = false
    }

    override fun recordPoint(relaxation: LpRelaxation, primal: DoubleArray, duals: DoubleArray) {
        variableBasisProposal = false
        pointProbed = false
        pointPrimal = null
        pointBasis = null
        pendingSide = null
        activeSide = null
        pointRelaxation = null
        freshRecord = false
        boundRecorded = false
        if (!collect || relaxation.colVarId.size > 8192) return
        val size = minOf(primal.size, relaxation.colVarId.size)
        charge(size.toLong() + 1L)
        pointPrimal = primal.copyOf(size)
        pointRelaxation = relaxation
        freshRecord = true
        unavailable = LpHintUnavailable.NO_BOUND
    }

    /**
     * Record an advisory LP iterate: its fractional primal (per structural column,
     * `FloatLpResult.primal`) and, from the [duals], the decayed reduced-cost average of
     * each variable (the reduced cost `dⱼ = cⱼ − yᵀAⱼ`; near-zero for basic columns). Keyed by the
     * relaxation's column→variable map. Purely advisory — drives value ([order]) and variable
     * ([branchScore]) selection, never feasibility or the optimum.
     */
    override fun record(relaxation: LpRelaxation, primal: DoubleArray, duals: DoubleArray) {
        variableBasisProposal = false
        pointProbed = false
        recorded = true
        freshRecord = collect
        pointRelaxation = if (collect) relaxation else null
        pointPrimal = null
        pointBasis = null
        pendingSide = null
        activeSide = null
        boundRecorded = false
        unavailable = LpHintUnavailable.NO_BOUND
        val model = relaxation.model
        solveStamp++
        for (col in relaxation.colVarId.indices) {
            val v = relaxation.colVarId[col]
            if (v < 0 || col >= primal.size) continue // auxiliary column (e.g. circuit arc) — no CP variable to hint
            if (relaxation.colIsBool[col]) {
                boolVal[v] = primal[col]
                boolStamp[v] = solveStamp
            } else {
                intVal[v] = primal[col]
                intStamp[v] = solveStamp
            }
            // Reduced cost dⱼ = cⱼ − yᵀAⱼ over the structural column; |dⱼ| feeds the pseudo-cost average. Read
            // through the double view, which holds a real row's coefficients; the integer store holds it as zeros.
            if (col >= model.n) continue
            var dot = 0.0
            model.forEachInColumnD(col) { i, a -> dot += duals[i] * a }
            val rc = abs(model.costD(col) - dot)
            if (rc <= RC_TOL) continue // basic / zero-reduced-cost: no objective-sensitivity signal
            if (relaxation.colIsBool[col]) {
                boolRc[v] = if (boolRc[v].isNaN()) rc else RC_DECAY * boolRc[v] + (1.0 - RC_DECAY) * rc
            } else {
                intRc[v] = if (intRc[v].isNaN()) rc else RC_DECAY * intRc[v] + (1.0 - RC_DECAY) * rc
            }
        }
    }

    /**
     * Branching priority of [varRef] for reduced-cost-average variable selection: its reduced-cost
     * pseudo-cost weighted by how fractional its last LP value is (distance to the nearest integer).
     * A variable the LP already pins to an integer scores `0` (no branching needed); one with no LP
     * value scores `NaN` (the selector falls back). Higher = branch here first.
     */
    fun branchScore(varRef: VarRef): Double {
        if (!recorded) return Double.NaN
        val isBool = varRef is VarRef.Bool
        val id = when (varRef) {
            is VarRef.IntVar -> varRef.varId
            is VarRef.Bool -> varRef.varId
        }
        val vals = if (isBool) boolVal else intVal
        if (id < 0 || id >= vals.size) return Double.NaN
        if ((if (isBool) boolStamp else intStamp)[id] != solveStamp) return Double.NaN
        val lpVal = vals[id]
        if (lpVal.isNaN()) return Double.NaN
        val frac = lpVal - floor(lpVal)
        val fractionality = min(frac, 1.0 - frac) // 0 when the LP value is integral
        val rc = (if (isBool) boolRc else intRc)[id]
        val weight = if (rc.isNaN()) 1.0 else rc + RC_EPS // unseen variables get the bare fractionality
        return weight * fractionality
    }

    /**
     * Steer [values] by the variable's fresh LP value; unchanged when none exists. For an int var
     * with a *fractional* LP value the head becomes `floor(lp)`: the engine's int decision is the
     * bound split `x ≤ head` / `x ≥ head + 1`, and splitting at the floor is the dichotomy that
     * separates the fractional LP point between the two children. An integral LP value (and a bool)
     * keeps round-toward-LP diving order instead — there is nothing to separate, the LP point is a
     * pin preference. Lazy for the int case: the original sequence is never materialised, so a wide
     * domain costs O(consumed), not a full sort.
     */
    fun order(varRef: VarRef, values: Sequence<Long>): Sequence<Long> {
        if (!recorded) return values
        val id = when (varRef) {
            is VarRef.IntVar -> varRef.varId
            is VarRef.Bool -> varRef.varId
        }
        val hint: Double
        if (varRef is VarRef.IntVar) {
            hint = intVal.getOrElse(id) { Double.NaN }
            if (hint.isNaN() || intStamp.getOrElse(id) { -1 } != solveStamp) return values
            val fl = floor(hint)
            val fractional = hint - fl > INT_TOL && hint - fl < 1.0 - INT_TOL
            val target = if (fractional) fl.toLong() else hint.roundToLong()
            return sequence {
                yield(target)
                for (v in values) if (v != target) yield(v)
            }
        }
        hint = boolVal.getOrElse(id) { Double.NaN }
        if (hint.isNaN() || boolStamp.getOrElse(id) { -1 } != solveStamp) return values
        val target = hint.roundToLong()
        // Stable sort by |value − round(LP)|: two candidates at most for a bool.
        return values.sortedBy { abs(it - target) }.asSequence()
    }

    private companion object {
        const val MAX_VARIABLE_BASIS_ATTEMPTS = 8
        const val MAX_UNPRODUCTIVE_VARIABLE_BASIS_ATTEMPTS = 2

        /** Below this, a reduced cost is treated as zero (basic column) — no pseudo-cost update. */
        const val RC_TOL = 1e-7

        /** Exponential-decay factor for the reduced-cost running average (older solves fade). */
        const val RC_DECAY = 0.9

        /** Floor added to the pseudo-cost weight so fractionality still ranks unseen-cost variables. */
        const val RC_EPS = 1e-6

        /** LP values within this of an integer count as integral (solver feasibility tolerance). */
        const val INT_TOL = 1e-6
    }
}
