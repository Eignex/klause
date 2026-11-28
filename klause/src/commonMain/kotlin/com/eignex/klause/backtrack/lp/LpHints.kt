package com.eignex.klause.backtrack.lp

import com.eignex.klause.lp.bounding.LpHintSink
import com.eignex.klause.lp.engine.ExactLpModel
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpScopedRows
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.ExecutionControl
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToLong

// LP values, reduced-cost pseudo-costs and directional history from node solves, read by the descent to
// order variables and values. Advisory only: it changes search order, never feasibility or the optimum.
internal class LpHints(
    numIntVars: Int,
    numBoolVars: Int,
    private val directionalGains: Boolean = false,
    private val observe: (String) -> Unit = {},
    private val charge: (Long) -> Unit = {},
    private val applyAdvice: Boolean = true,
) : LpHintSink {
    private val collect = directionalGains && numIntVars.toLong() + numBoolVars <= 8192L
    private val history = LpDirectionalHistory(observe, charge = charge)
    private val passiveChoice = if (collect) LpPassiveVariableChoice(applyAdvice, observe, charge) else null
    private val passiveValues = if (collect) LpPassiveValueChoice(applyAdvice, observe, charge) else null
    private val passiveCollection = if (collect) LpPassiveCollection(observe, charge) else null
    private var passivePoint: LpRelaxation? = null
    private var passiveState: LpExactState? = null
    private var freshRecord = false
    private var objectiveRecorded = false
    private var directionalChoice: VarRef? = null
    private var scopeModel: ExactLpModel? = null
    private var scopeRows: LpScopedRows? = null

    // Bound views share the relaxation's read-only structural column maps.
    private var scopeVariables = IntArray(0)
    private var scopeBooleans = BooleanArray(0)
    private var scopeConstant = 0L
    private var scopeCutoff = Double.NaN
    private var cutoff = Double.NaN

    fun beginNode(depth: Int, bound: Double) {
        clearPassivePoint()
        if (scopeModel != null) charge(1L)
        cutoff = bound
        freshRecord = false
        objectiveRecorded = false
        directionalChoice = null
        if (collect) history.beginNode(depth)
    }

    override fun recordObjective(relaxation: LpRelaxation, objective: Double) {
        if (passiveChoice != null) {
            charge(2L)
            observe("PASSIVE_VARIABLE_OPTIMUM_CHECK")
            passiveChoice.clear()
            passiveValues?.clear()
            passiveCollection?.clear()
            passiveState = null
        }
        if (!collect || relaxation.colVarId.size > 8192) return
        val state = relaxation.model.exactState ?: return
        charge(5L)
        val difference = scopeDifference(relaxation, state)
        if (difference != null) {
            if (scopeModel != null) reportScopeDifference(difference)
            history.clear(report = scopeModel != null)
            scopeModel = state.baseModel
            scopeRows = state.rows
            scopeConstant = relaxation.objectiveConstant
            scopeCutoff = cutoff
        }
        charge(2L)
        scopeVariables = relaxation.colVarId
        scopeBooleans = relaxation.colIsBool
        // The scoped constant cancels in every gain; adding it can round away a small difference.
        objectiveRecorded = objective.isFinite()
        if (passiveChoice != null && passivePoint === relaxation) passiveState = state
        history.record(objective)
    }

    private fun scopeDifference(relaxation: LpRelaxation, state: LpExactState): String? = when {
        scopeModel !== state.baseModel -> "INCOMPATIBLE_MODEL"
        scopeRows !== state.rows -> "INCOMPATIBLE_ROWS"
        scopeConstant != relaxation.objectiveConstant -> "INCOMPATIBLE_OBJECTIVE_CONSTANT"
        scopeCutoff != cutoff -> "INCOMPATIBLE_CUTOFF"
        !matchesScope(relaxation.colVarId) -> "INCOMPATIBLE_VARIABLE_MAPPING"
        !matchesScope(relaxation.colIsBool) -> "INCOMPATIBLE_VARIABLE_KIND"
        else -> null
    }

    private fun reportScopeDifference(difference: String) {
        charge(1L)
        observe("INCOMPATIBLE")
        observe(difference)
    }

    private fun matchesScope(variables: IntArray): Boolean {
        charge(1L)
        if (scopeVariables === variables) return true
        if (scopeVariables.size != variables.size) return false
        for (index in variables.indices) {
            charge(1L)
            if (scopeVariables[index] != variables[index]) return false
        }
        return true
    }

    private fun matchesScope(booleans: BooleanArray): Boolean {
        charge(1L)
        if (scopeBooleans === booleans) return true
        if (scopeBooleans.size != booleans.size) return false
        for (index in booleans.indices) {
            charge(1L)
            if (scopeBooleans[index] != booleans[index]) return false
        }
        return true
    }

    override fun recordInfeasible() {
        clearPassivePoint()
        history.infeasible()
    }

    fun finishNode(state: LpExactState? = null) {
        passiveChoice?.clear()
        passiveValues?.clear()
        passiveCollection?.clear()
        history.finishNode()
        if (scopeModel == null || state == null) return
        val difference = when {
            scopeModel !== state.baseModel -> "INCOMPATIBLE_MODEL"
            scopeRows !== state.rows -> "INCOMPATIBLE_ROWS"
            else -> null
        }
        if (difference != null) {
            reportScopeDifference(difference)
            resetHistory()
        }
    }

    fun branch(variable: VarRef) {
        passiveValues?.clear()
        passiveCollection?.clear()
        passiveChoice?.branch(variable, if (passivePointCurrent()) solveStamp else -1)
        if (directionalGains && scopeModel != null) {
            observe(if (directionalChoice == variable) "CONSUMED" else "FALLBACK")
        }
        directionalChoice = null
        if (collect && scopeModel != null) {
            history.branch(variable, value(variable))
        }
    }

    fun commit(decision: SearchDecision, depth: Int, cancelled: ExecutionControl = ExecutionControl.Never) {
        if (passiveChoice?.nominatedVariable != null) {
            charge(2L)
            passiveChoice.commit(decision) { !cancelled() && passivePointCurrent() }
        }
        passiveValues?.commit(decision, solveStamp) { !cancelled() && passivePointCurrent() }
        passiveCollection?.commit(decision, solveStamp) { !cancelled() && passivePointCurrent() }
        if (collect && scopeModel != null) {
            history.commit(decision, depth)
        }
    }

    fun directionalScore(variable: VarRef): Double {
        if (!hasDirectionalScore()) return Double.NaN
        return readyDirectionalScore(variable)
    }

    fun directionalScore(varId: Int, isBool: Boolean): Double {
        if (!hasDirectionalScore()) return Double.NaN
        return readyDirectionalScore(if (isBool) VarRef.Bool(varId) else VarRef.IntVar(varId))
    }

    private fun hasDirectionalScore(): Boolean = collect && directionalGains && objectiveRecorded &&
        history.hasReliableHistory && passivePointCurrent()

    val hasDirectionalCandidates: Boolean get() = hasDirectionalScore()

    private fun readyDirectionalScore(variable: VarRef): Double {
        charge(1L)
        observe("PASSIVE_VARIABLE_SCORE")
        val point = value(variable)
        if (variable is VarRef.Bool && point !in 0.0..1.0) return Double.NaN
        return history.score(variable, point).also {
            if (it.isFinite()) observe("PASSIVE_VARIABLE_RELIABLE")
        }
    }

    fun selected(variable: VarRef?) {
        passiveChoice?.clear()
        directionalChoice = variable
    }

    fun beginPassiveSelection() {
        passiveChoice?.clear()
        if (!collect || !history.hasReliableHistory) return
        charge(4L)
        observe("PASSIVE_VARIABLE_CHECK")
    }

    fun pickVariable(
        baseline: VarRef?,
        minimumScore: Double,
        available: (VarRef) -> Boolean,
        cancelled: () -> Boolean,
    ): VarRef? {
        beginPassiveSelection()
        if (!hasDirectionalScore()) return baseline.also { selected(null) }
        if (baseline == null) return selectVariable(null, null, cancelled)
        val point = passivePoint
        val state = passiveState
        val stamp = solveStamp
        val current = {
            !cancelled() && passivePoint === point && passiveState === state &&
                stamp == solveStamp && passivePointCurrent()
        }
        charge(1L)
        val reference = history.estimate(baseline, value(baseline))
        if (!current()) return baseline.also { selected(null) }
        if (!reference.isFinite()) {
            observe("PASSIVE_VARIABLE_BASELINE_UNRELIABLE")
            return baseline.also { selected(null) }
        }
        var proposal: VarRef? = null
        var bestScore = minimumScore
        history.forEachCandidate(current) { variable ->
            if (available(variable)) {
                val score = readyDirectionalScore(variable)
                val previous = proposal
                val precedes = previous != null && if ((variable is VarRef.Bool) == (previous is VarRef.Bool))
                    variable.varId < previous.varId else variable is VarRef.Bool
                if (score > bestScore || (score == bestScore && precedes)) {
                    bestScore = score
                    proposal = variable
                }
            }
        }
        if (!current()) return baseline.also { selected(null) }
        return if (proposal == null) baseline.also { selected(null) }
        else selectVariable(proposal, baseline, cancelled)
    }

    fun selectVariable(proposal: VarRef?, baseline: VarRef?, cancelled: () -> Boolean = { false }): VarRef? {
        val choice = passiveChoice ?: return baseline
        if (proposal == null || baseline == null) {
            charge(4L)
            observe("PASSIVE_VARIABLE_NO_BASELINE")
            selected(null)
            return baseline
        }
        val point = passivePoint
        val state = passiveState
        val stamp = solveStamp
        val current = {
            !cancelled() && passivePoint === point && passiveState === state &&
                stamp == solveStamp && passivePointCurrent()
        }
        val proposed = LpPassiveVariableChoice.Candidate(proposal, value(proposal))
        val reference = if (proposal == baseline) proposed
        else LpPassiveVariableChoice.Candidate(baseline, value(baseline))
        val selected = choice.select(proposed, reference, stamp,
            fresh = current,
            reliable = {
                charge(2L)
                val gain = history.score(proposal, proposed.point)
                val baselineGain = if (proposal == baseline) gain else history.estimate(baseline, reference.point)
                if (!baselineGain.isFinite()) observe("PASSIVE_VARIABLE_BASELINE_UNRELIABLE")
                history.hasReliableHistory && gain.isFinite() && baselineGain.isFinite() &&
                    (proposal == baseline || gain > baselineGain)
            })
        if (choice.nominatedVariable != null && !current()) {
            choice.clear()
            directionalChoice = null
            observe("PASSIVE_VARIABLE_STALE")
            return baseline
        }
        directionalChoice = choice.nominatedVariable.takeIf { applyAdvice }
        return selected
    }

    private fun passivePointCurrent(): Boolean = freshRecord && objectiveRecorded && passiveState != null &&
        passivePoint?.model?.exactState === passiveState

    private fun clearPassivePoint() {
        val choice = passiveChoice ?: return
        choice.clear()
        passiveValues?.clear()
        passiveCollection?.clear()
        passivePoint = null
        passiveState = null
    }

    fun orderAlternatives(
        variable: VarRef,
        alternatives: List<SearchDecision>,
        cancelled: ExecutionControl,
    ): List<SearchDecision> {
        val collection = passiveCollection ?: return alternatives
        val values = checkNotNull(passiveValues)
        values.clear()
        collection.clear()
        val valueReady = history.hasValueHistory
        val collectReady = cutoff.isFinite() && collection.available && history.hasCollectionHistory
        if (!history.hasObservations || (!valueReady && !collectReady) ||
            cancelled() || !passivePointCurrent()
        ) return alternatives
        val point = passivePoint
        val state = passiveState
        val stamp = solveStamp
        val current = {
            !cancelled() && passivePoint === point && passiveState === state &&
                stamp == solveStamp && passivePointCurrent()
        }
        if (valueReady) {
            val advised = values.select(variable, value(variable), alternatives, history, stamp,
                cutoff = cutoff - scopeConstant.toDouble(), fresh = current)
            if (values.hasNomination) return advised
        }
        if (!collectReady || !current()) return alternatives
        return collection.select(variable, value(variable), alternatives, history, stamp, current)
    }

    private fun value(variable: VarRef): Double = if (!freshRecord) Double.NaN else when (variable) {
        is VarRef.IntVar -> if (intStamp.getOrElse(variable.varId) { -1 } == solveStamp) {
            intVal.getOrElse(variable.varId) { Double.NaN }
        } else Double.NaN
        is VarRef.Bool -> if (boolStamp.getOrElse(variable.varId) { -1 } == solveStamp) {
            boolVal.getOrElse(variable.varId) { Double.NaN }
        } else Double.NaN
    }

    private val intVal by lazy(LazyThreadSafetyMode.NONE) { DoubleArray(numIntVars) { Double.NaN } }
    private val boolVal by lazy(LazyThreadSafetyMode.NONE) { DoubleArray(numBoolVars) { Double.NaN } }

    // Hints count only when written by the latest LP solve: values are never invalidated on backtrack, so a
    // hint from an abandoned subtree would otherwise steer unrelated nodes.
    private val intStamp by lazy(LazyThreadSafetyMode.NONE) { IntArray(numIntVars) { -1 } }
    private val boolStamp by lazy(LazyThreadSafetyMode.NONE) { IntArray(numBoolVars) { -1 } }
    private var solveStamp = 0

    // Decayed average of |reduced cost| while nonbasic, a pseudo-cost; NaN until first nonbasic.
    private val intRc by lazy(LazyThreadSafetyMode.NONE) { DoubleArray(numIntVars) { Double.NaN } }
    private val boolRc by lazy(LazyThreadSafetyMode.NONE) { DoubleArray(numBoolVars) { Double.NaN } }

    private var recorded = false

    fun resetHistory() {
        clearPassivePoint()
        if (scopeModel != null) {
            charge(2L)
            history.clear()
        }
        scopeModel = null
        scopeRows = null
        scopeVariables = IntArray(0)
        scopeBooleans = BooleanArray(0)
        freshRecord = false
        objectiveRecorded = false
        directionalChoice = null
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

    override fun record(relaxation: LpRelaxation, primal: DoubleArray, duals: DoubleArray) {
        if (passiveChoice != null) {
            charge(2L)
            observe("PASSIVE_VARIABLE_POINT")
            passiveChoice.clear()
            passiveValues?.clear()
            passiveCollection?.clear()
            passivePoint = relaxation
            passiveState = null
        }
        recorded = true
        freshRecord = true
        val model = relaxation.model
        solveStamp++
        for (col in relaxation.colVarId.indices) {
            val v = relaxation.colVarId[col]
            if (v < 0 || col >= primal.size) continue
            if (relaxation.colIsBool[col]) {
                boolVal[v] = primal[col]
                boolStamp[v] = solveStamp
            } else {
                intVal[v] = primal[col]
                intStamp[v] = solveStamp
            }
            // dⱼ = cⱼ − yᵀAⱼ, read through the double view: the integer store holds a real row's coefficients as zeros.
            if (col >= model.n) continue
            var dot = 0.0
            model.forEachInColumnD(col) { i, a -> dot += duals[i] * a }
            val rc = abs(model.costD(col) - dot)
            if (rc <= RC_TOL) continue
            if (relaxation.colIsBool[col]) {
                boolRc[v] = if (boolRc[v].isNaN()) rc else RC_DECAY * boolRc[v] + (1.0 - RC_DECAY) * rc
            } else {
                intRc[v] = if (intRc[v].isNaN()) rc else RC_DECAY * intRc[v] + (1.0 - RC_DECAY) * rc
            }
        }
    }

    // Pseudo-cost × distance of the fresh LP value to the nearest integer; NaN without a fresh LP value.
    fun branchScore(varRef: VarRef): Double = branchScore(varRef.varId, varRef is VarRef.Bool)

    fun branchScore(id: Int, isBool: Boolean): Double {
        if (!recorded) return Double.NaN
        val vals = if (isBool) boolVal else intVal
        if (id < 0 || id >= vals.size) return Double.NaN
        if ((if (isBool) boolStamp else intStamp)[id] != solveStamp) return Double.NaN
        val lpVal = vals[id]
        if (lpVal.isNaN()) return Double.NaN
        val frac = lpVal - floor(lpVal)
        val fractionality = min(frac, 1.0 - frac)
        val rc = (if (isBool) boolRc else intRc)[id]
        val weight = if (rc.isNaN()) 1.0 else rc + RC_EPS // unseen variables get the bare fractionality
        return weight * fractionality
    }

    // A fractional integer LP value puts floor(lp) first: the int decision splits x ≤ head / x ≥ head + 1, and
    // the floor separates the LP point between the children. An integral value or a Boolean rounds toward the
    // LP. Lazy for integers, so a wide domain costs O(consumed).
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
        return values.sortedBy { abs(it - target) }.asSequence()
    }

    private companion object {
        const val RC_TOL = 1e-7

        const val RC_DECAY = 0.9

        // Keeps fractionality ranking variables whose cost is unseen.
        const val RC_EPS = 1e-6

        const val INT_TOL = 1e-6
    }
}
