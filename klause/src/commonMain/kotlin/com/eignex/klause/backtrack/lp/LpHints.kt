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
    private val variableContexts: Boolean = false,
) : LpHintSink {
    private val collect = directionalGains && numIntVars.toLong() + numBoolVars <= 8192L
    private val history = LpDirectionalHistory(observe, charge = charge)
    private var passiveVariable: LpPassiveVariableChoice? = null
    private var passiveValues: LpPassiveValueChoice? = null
    private var passiveCollection: LpPassiveCollection? = null
    private var passivePoint: LpRelaxation? = null
    private var passiveState: LpExactState? = null
    private var freshRecord = false
    private var objectiveRecorded = false
    private var scopeModel: ExactLpModel? = null
    private var scopeRows: LpScopedRows? = null

    // Bound views share the relaxation's read-only structural column maps.
    private var scopeVariables = IntArray(0)
    private var scopeBooleans = BooleanArray(0)
    private var scopeConstant = 0L
    private var scopeCutoff = Double.NaN
    private var cutoff = Double.NaN
    private var nodeStamp = 0L
    private var pruningOutcome: Boolean? = null
    private var pruningScope: LpRelaxation? = null
    private var variableContextRemaining = 128

    fun beginNode(depth: Int, bound: Double) {
        nodeStamp++
        val version = nodeStamp
        pruningOutcome = null
        pruningScope = null
        clearPassivePoint()
        if (scopeModel != null) charge(1L)
        if (version != nodeStamp) return
        cutoff = bound
        freshRecord = false
        objectiveRecorded = false
        if (collect && bound.isFinite()) {
            if (passiveValues == null) {
                charge(16L)
                if (version != nodeStamp) return
                passiveValues = LpPassiveValueChoice(applyAdvice, observe, charge)
                passiveCollection = LpPassiveCollection(observe, charge)
            }
            history.beginNode(depth)
        } else if (scopeModel != null) resetHistory()
    }

    override fun recordObjective(relaxation: LpRelaxation, objective: Double) {
        if (collect && cutoff.isFinite()) {
            charge(2L)
            observe("PASSIVE_POINT_OPTIMUM_CHECK")
            passiveVariable?.clear()
            passiveValues?.clear()
            passiveCollection?.clear()
            passiveState = null
        }
        if (!collect || !cutoff.isFinite() || relaxation.colVarId.size > 8192) return
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
        if (collect && passivePoint === relaxation) passiveState = state
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

    override fun recordInfeasible(relaxation: LpRelaxation?) {
        clearPassivePoint()
        if (!collect || !cutoff.isFinite() || scopeModel == null) return
        val state = relaxation?.model?.exactState
        val model = scopeModel
        val rows = scopeRows
        val bound = cutoff
        charge(5L)
        val difference = if (state == null) "INCOMPATIBLE_MISSING_SCOPE" else scopeDifference(relaxation, state)
        if (difference != null) {
            reportScopeDifference(difference)
            resetHistory()
            return
        }
        if (scopeModel !== model || scopeRows !== rows || cutoff != bound) return
        history.infeasible()
    }

    override fun recordOutcome(pruned: Boolean?, relaxation: LpRelaxation?) {
        if (!collect || !cutoff.isFinite()) return
        val version = nodeStamp
        charge(2L)
        if (version != nodeStamp) return
        pruningOutcome = pruned
        pruningScope = relaxation
    }

    fun finishNode(state: LpExactState? = null, cancelled: ExecutionControl = ExecutionControl.Never) {
        passiveVariable?.clear()
        passiveValues?.clear()
        passiveCollection?.clear()
        val outcome = pruningOutcome
        val relaxation = pruningScope
        pruningOutcome = null
        pruningScope = null
        val model = scopeModel
        val rows = scopeRows
        val bound = cutoff
        val version = nodeStamp
        if (model == null || state == null) {
            history.finishNode()
            return
        }
        val ownerDifference = when {
            model !== state.baseModel -> "INCOMPATIBLE_MODEL"
            rows !== state.rows -> "INCOMPATIBLE_ROWS"
            else -> null
        }
        if (ownerDifference != null) {
            reportScopeDifference(ownerDifference)
            resetHistory()
            return
        }
        if (outcome != null && relaxation == null) {
            reportScopeDifference("INCOMPATIBLE_MISSING_SCOPE")
            resetHistory()
            return
        }
        if (outcome == null || relaxation == null || cancelled()) {
            history.finishNode()
            return
        }
        charge(5L)
        val exact = relaxation.model.exactState
        val difference = if (exact == null) "INCOMPATIBLE_MISSING_SCOPE" else scopeDifference(relaxation, exact)
        if (difference != null) {
            reportScopeDifference(difference)
            resetHistory()
            return
        }
        history.finishNode(outcome) {
            !cancelled() && nodeStamp == version && scopeModel === model && scopeRows === rows && cutoff == bound
        }
    }

    fun branch(variable: VarRef) {
        passiveVariable?.branch(variable, solveStamp)
        passiveValues?.clear()
        passiveCollection?.clear()
        if (collect && scopeModel != null) {
            history.branch(variable, value(variable))
        }
    }

    fun commit(decision: SearchDecision, depth: Int, cancelled: ExecutionControl = ExecutionControl.Never) {
        passiveVariable?.commit(decision) { !cancelled() && passivePointCurrent() }
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

    private fun readyDirectionalScore(variable: VarRef): Double {
        charge(1L)
        observe("PASSIVE_VARIABLE_SCORE")
        val point = value(variable)
        if (variable is VarRef.Bool && point !in 0.0..1.0) return Double.NaN
        return history.score(variable, point).also {
            if (it.isFinite()) observe("PASSIVE_VARIABLE_RELIABLE")
        }
    }

    private fun passivePointCurrent(): Boolean = freshRecord && objectiveRecorded && passiveState != null &&
        passivePoint?.model?.exactState === passiveState

    fun pickVariable(
        baseline: VarRef,
        available: (VarRef) -> Boolean,
        cancelled: ExecutionControl,
    ): VarRef {
        passiveVariable?.clear()
        if (!collect || !history.hasReliableHistory || !passivePointCurrent() || cancelled()) return baseline
        val version = nodeStamp
        charge(12L)
        if (version != nodeStamp || !passivePointCurrent() || cancelled()) return baseline
        val point = passivePoint
        val state = passiveState
        val stamp = solveStamp
        fun current(): Boolean =
            !cancelled() && nodeStamp == version && passivePoint === point && passiveState === state &&
                solveStamp == stamp && passivePointCurrent()
        val workingCutoff = cutoff - scopeConstant.toDouble()
        if (!available(baseline) || !current()) return baseline
        fun baselineThreshold(): Double {
            charge(12L)
            if (!current() || !history.hasPositiveGap(workingCutoff)) return Double.NaN
            val baselineValue = value(baseline)
            val validPoint = baselineValue.isFinite() && when (baseline) {
                is VarRef.Bool -> baselineValue in 0.0..1.0
                is VarRef.IntVar -> baselineValue >= Long.MIN_VALUE.toDouble() &&
                    baselineValue < Long.MAX_VALUE.toDouble()
            }
            if (validPoint && baselineValue == floor(baselineValue)) {
                observe("PASSIVE_VARIABLE_INTEGRAL_BASELINE")
                return if (current()) 0.0 else Double.NaN
            }
            val prediction = history.minimumPrediction(baseline, baselineValue, workingCutoff, upper = true)
            if (!current()) return Double.NaN
            if (prediction.isFinite()) return prediction
            val fraction = baselineValue - floor(baselineValue)
            if (!validPoint || fraction <= INT_TOL || fraction >= 1.0 - INT_TOL ||
                history.hasReliablePair(baseline, baselineValue) || !current()
            ) return Double.NaN
            observe("PASSIVE_VARIABLE_UNMEASURED_BASELINE")
            return if (current()) UNMEASURED_REFERENCE else Double.NaN
        }
        val reference = baselineThreshold()
        if (!current()) return baseline
        if (!reference.isFinite() && reference != UNMEASURED_REFERENCE) {
            observe("PASSIVE_VARIABLE_BASELINE_UNRELIABLE")
            return baseline
        }
        var proposal: VarRef? = null
        var bestGain = reference
        history.forEachCandidate(::current) { variable ->
            if (variable != baseline && available(variable) && current()) {
                val gain = history.minimumPrediction(variable, value(variable), workingCutoff)
                val previous = proposal
                val precedes = previous != null && if ((variable is VarRef.Bool) == (previous is VarRef.Bool))
                    variable.varId < previous.varId else variable is VarRef.Bool
                if (gain.isFinite() && gain > 0.0 && (gain > bestGain || (gain == bestGain && precedes))) {
                    bestGain = gain
                    proposal = variable
                }
            }
        }
        if (!current()) return baseline
        val proposed = proposal ?: return baseline
        val context = captureVariableContext(baseline, proposed, reference, ::current)
        if (!current()) return baseline
        if (passiveVariable == null) {
            charge(8L)
            if (!current()) return baseline
            passiveVariable = LpPassiveVariableChoice(applyAdvice, observe, charge)
        }
        val choice = checkNotNull(passiveVariable)
        val selected = choice.select(
            LpPassiveVariableChoice.Candidate(proposed, value(proposed)),
            LpPassiveVariableChoice.Candidate(baseline, value(baseline)), stamp, ::current,
            reliable = {
                val proposedGain = history.minimumPrediction(proposed, value(proposed), workingCutoff)
                val baselineGain = baselineThreshold()
                current() && available(proposed) && available(baseline) &&
                    proposedGain.isFinite() && proposedGain > 0.0 &&
                    (baselineGain == UNMEASURED_REFERENCE ||
                        (baselineGain.isFinite() && proposedGain > baselineGain))
            },
            context = context,
        )
        if (!current()) {
            choice.clear()
            return baseline
        }
        return selected
    }

    private inline fun captureVariableContext(
        baseline: VarRef,
        proposal: VarRef,
        reference: Double,
        crossinline current: () -> Boolean,
    ): String? {
        if (!variableContexts || variableContextRemaining <= 0) return null
        variableContextRemaining--
        charge(12L)
        if (!current()) return null
        observe("PASSIVE_VARIABLE_CONTEXT_CHECK")
        if (!current()) return null
        val baselinePoint = value(baseline)
        val proposedPoint = value(proposal)
        val baselineFraction = baselinePoint - floor(baselinePoint)
        val proposedFraction = proposedPoint - floor(proposedPoint)
        val context = when {
            !baselinePoint.isFinite() || !proposedPoint.isFinite() ||
                proposedFraction <= INT_TOL || proposedFraction >= 1.0 - INT_TOL -> "UNKNOWN"
            baselineFraction == 0.0 -> "INTEGRAL_BASELINE"
            reference != UNMEASURED_REFERENCE -> "KNOWN_FRACTIONAL"
            else -> {
                val ordinary = min(baselineFraction, 1.0 - baselineFraction)
                val proposed = min(proposedFraction, 1.0 - proposedFraction)
                when {
                    proposed < ordinary -> "UNMEASURED_LOWER"
                    proposed > ordinary -> "UNMEASURED_HIGHER"
                    else -> "UNMEASURED_EQUAL"
                }
            }
        }
        observe("PASSIVE_VARIABLE_CONTEXT_NOMINATION_" + context)
        if (!current()) return null
        if (variableContextRemaining == 0) {
            observe("PASSIVE_VARIABLE_CONTEXT_EXHAUSTED")
            if (!current()) return null
        }
        return context
    }

    private fun clearPassivePoint() {
        if (!collect) return
        passiveVariable?.clear()
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
        passiveVariable?.alternatives(variable, alternatives, solveStamp) { !cancelled() && passivePointCurrent() }
        val collection = passiveCollection ?: return alternatives
        val values = checkNotNull(passiveValues)
        collection.clear()
        values.clear()
        val valueReady = alternatives.size == 2 && cutoff.isFinite() &&
            history.hasValueHistory && history.hasPruningHistory
        val collectReady = cutoff.isFinite() && collection.available && history.hasCollectionHistory
        if (!history.hasObservations || (!valueReady && !collectReady) ||
            cancelled() || !passivePointCurrent()
        ) return alternatives
        val point = passivePoint
        val state = passiveState
        val stamp = solveStamp
        val pointValue = value(variable)
        val preference = if (valueReady) {
            history.preferDirection(variable, pointValue, cutoff - scopeConstant.toDouble())
        } else null
        if (cancelled() || passivePoint !== point || passiveState !== state ||
            stamp != solveStamp || !passivePointCurrent()
        ) return alternatives
        if (valueReady && preference == null) observe("PASSIVE_VALUE_NO_PROPOSAL")
        if (preference == null && !collectReady) return alternatives
        fun current(): Boolean =
            !cancelled() && passivePoint === point && passiveState === state &&
                stamp == solveStamp && passivePointCurrent()
        if (preference != null) {
            val advised = values.select(variable, pointValue, alternatives, preference, stamp, fresh = ::current)
            if (values.hasNomination) return advised
        }
        if (!collectReady || !current()) return alternatives
        return collection.select(variable, pointValue, alternatives, history, stamp, ::current)
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
        nodeStamp++
        pruningOutcome = null
        pruningScope = null
        clearPassivePoint()
        if (scopeModel != null) {
            charge(2L)
            history.restart()
        }
        freshRecord = false
        objectiveRecorded = false
    }

    fun resetHistory() {
        nodeStamp++
        pruningOutcome = null
        pruningScope = null
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
        if (collect && cutoff.isFinite()) {
            charge(2L)
            observe("PASSIVE_POINT_RECORDED")
            passiveVariable?.clear()
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
        const val UNMEASURED_REFERENCE = Double.NEGATIVE_INFINITY

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
