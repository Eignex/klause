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
    private val applyAdvice: Boolean = true,
) : LpHintSink {
    private val collect = directionalGains && numIntVars.toLong() + numBoolVars <= 8192L
    private val history = LpDirectionalHistory(observe, charge = charge)
    private val passiveChoice = if (collect) LpPassiveVariableChoice(false, observe, charge) else null
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
    private var pruningOutcome: Boolean? = null

    fun beginNode(depth: Int, bound: Double) {
        clearPassivePoint()
        if (scopeModel != null) charge(1L)
        cutoff = bound
        pruningOutcome = null
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
        history.record(objective, retainOutcome = cutoff.isFinite())
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

    override fun recordOutcome(pruned: Boolean?) {
        if (!collect || !cutoff.isFinite() || !history.hasPendingOutcome) return
        charge(1L)
        pruningOutcome = pruned
    }

    fun finishNode(state: LpExactState? = null) {
        passiveChoice?.clear()
        passiveValues?.clear()
        passiveCollection?.clear()
        if (scopeModel == null || state == null) {
            history.finishNode()
            return
        }
        val difference = when {
            scopeModel !== state.baseModel -> "INCOMPATIBLE_MODEL"
            scopeRows !== state.rows -> "INCOMPATIBLE_ROWS"
            else -> null
        }
        if (difference != null) {
            reportScopeDifference(difference)
            resetHistory()
        }
        history.finishNode(pruningOutcome)
        pruningOutcome = null
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
        history.hasPruningHistory && passivePointCurrent()

    private fun readyDirectionalScore(variable: VarRef): Double {
        charge(1L)
        observe("PASSIVE_VARIABLE_SCORE")
        val point = value(variable)
        if (variable is VarRef.Bool && point !in 0.0..1.0) return Double.NaN
        return history.pruningScore(variable, point).also {
            if (it.isFinite()) observe("PASSIVE_VARIABLE_RELIABLE")
        }
    }

    fun selected(variable: VarRef?) {
        passiveChoice?.clear()
        directionalChoice = variable
    }

    fun beginPassiveSelection() {
        passiveChoice?.clear()
        if (!collect || !history.hasPruningHistory) return
        charge(4L)
        observe("PASSIVE_VARIABLE_CHECK")
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
                val gain = history.pruningScore(proposal, proposed.point)
                val baselineGain = if (proposal == baseline) gain else history.pruningEstimate(baseline, reference.point)
                if (!baselineGain.isFinite()) observe("PASSIVE_VARIABLE_BASELINE_UNRELIABLE")
                history.hasPruningHistory && gain.isFinite() && baselineGain.isFinite() &&
                    (proposal == baseline || gain > baselineGain)
            })
        if (choice.nominatedVariable != null && !current()) {
            choice.clear()
            directionalChoice = null
            observe("PASSIVE_VARIABLE_STALE")
            return baseline
        }
        directionalChoice = null
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
        val valueReady = cutoff.isFinite() && history.hasPruningHistory
        val collectReady = cutoff > Double.NEGATIVE_INFINITY && collection.available && history.hasCollectionHistory
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
            charge(1L)
            val advised = values.select(variable, value(variable), alternatives, history,
                cutoff - scopeConstant.toDouble(), stamp, fresh = current)
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

    fun restart() {
        clearPassivePoint()
        if (scopeModel != null) {
            charge(2L)
            history.restart()
        }
        freshRecord = false
        objectiveRecorded = false
        directionalChoice = null
    }

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

    /**
     * Record an LP solution: its fractional primal (per structural column,
     * `FloatLpResult.primal`) and, from the [duals], the decayed reduced-cost average of
     * each variable (the reduced cost `dⱼ = cⱼ − yᵀAⱼ`; near-zero for basic columns). Keyed by the
     * relaxation's column→variable map. Purely advisory — drives value ([order]) and variable
     * ([branchScore]) selection, never feasibility or the optimum.
     */
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
    fun branchScore(varRef: VarRef): Double = branchScore(varRef.varId, varRef is VarRef.Bool)

    fun branchScore(id: Int, isBool: Boolean): Double {
        if (!recorded) return Double.NaN
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
