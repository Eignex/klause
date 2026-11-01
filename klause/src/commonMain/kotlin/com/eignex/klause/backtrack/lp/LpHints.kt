package com.eignex.klause.backtrack.lp

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.lp.bounding.LpHintSink
import com.eignex.klause.lp.bounding.LpHintUnavailable
import com.eignex.klause.lp.engine.ExactLpModel
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpScopedRows
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.magnitudeBitLength
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
) : LpHintSink {
    private val collect = numIntVars.toLong() + numBoolVars <= 8192L
    private val history = LpDirectionalHistory(observe, charge = charge, useClassPriors = directionalGains)
    private var freshRecord = false
    private var pointModel: LpModel? = null
    private var boundRecorded = false
    private var unavailable = LpHintUnavailable.UNOBSERVED
    private var directionalChoice: VarRef? = null
    private var baselineChoice: VarRef? = null
    private var priorChoice = false
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
        if (scopeModel != null) charge(1L)
        cutoff = bound
        this.incumbentVersion = incumbentVersion
        this.externalExact = externalExact
        freshRecord = false
        pointModel = null
        boundRecorded = false
        unavailable = LpHintUnavailable.UNOBSERVED
        directionalChoice = null
        baselineChoice = null
        priorChoice = false
        if (collect) history.beginNode(depth)
    }

    override fun recordBound(relaxation: LpRelaxation, bound: Double) =
        recordEstimate(relaxation, bound, checked = true)

    override fun recordObjective(relaxation: LpRelaxation, objective: Double) =
        recordEstimate(relaxation, objective, checked = false)

    private fun recordEstimate(relaxation: LpRelaxation, bound: Double, checked: Boolean) {
        boundRecorded = false
        if (!collect || relaxation.colVarId.size > 8192 || !freshRecord || pointModel !== relaxation.model) {
            recordUnavailable(LpHintUnavailable.MODEL)
            return
        }
        val state = relaxation.model.exactState ?: run {
            recordUnavailable(LpHintUnavailable.MODEL)
            return
        }
        charge(2L * relaxation.colVarId.size + 3L + cutoffComparisonWork())
        if (scopeModel !== state.baseModel || scopeRows !== state.rows ||
            !scopeVariables.contentEquals(relaxation.colVarId) || !scopeBooleans.contentEquals(relaxation.colIsBool) ||
            scopeConstant != relaxation.objectiveConstant || scopeCutoff != cutoff || scopeChecked != checked ||
            scopeIncumbentVersion != incumbentVersion || scopeExternalExact != externalExact
        ) {
            if (scopeModel != null) observe("INCOMPATIBLE")
            charge(history.retainedEntries.toLong() + 2L * relaxation.colVarId.size)
            history.clear(report = scopeModel != null)
            scopeModel = state.baseModel
            scopeRows = state.rows
            scopeVariables = relaxation.colVarId.copyOf()
            scopeBooleans = relaxation.colIsBool.copyOf()
            scopeConstant = relaxation.objectiveConstant
            scopeCutoff = cutoff
            scopeChecked = checked
            scopeIncumbentVersion = incumbentVersion
            scopeExternalExact = externalExact
        }
        // The scoped constant cancels in every gain; adding it can round away a small difference.
        boundRecorded = bound.isFinite()
        if (boundRecorded) observe(if (checked) "BOUND_RECORDED" else "OPTIMUM_RECORDED")
        history.record(bound)
    }

    private fun cutoffComparisonWork(): Long {
        if (scopeExternalExact === externalExact) return 0L
        val oldBits = scopeExternalExact?.let { maxOf(it.num.magnitudeBitLength(), it.den.magnitudeBitLength()) } ?: 0
        val newBits = externalExact?.let { maxOf(it.num.magnitudeBitLength(), it.den.magnitudeBitLength()) } ?: 0
        return 2L * ((maxOf(oldBits, newBits).toLong() + 63L) / 64L)
    }

    override fun recordInfeasible() = history.infeasible()

    override fun recordUnavailable(reason: LpHintUnavailable) {
        if (scopeModel != null) charge(1L)
        unavailable = reason
    }

    fun finishNode(state: LpExactState? = null) {
        history.finishNode(unavailable.name)
        if (scopeModel != null && state != null &&
            (scopeModel !== state.baseModel || scopeRows !== state.rows)
        ) {
            observe("INCOMPATIBLE")
            resetHistory()
        }
    }

    fun branch(variable: VarRef, domain: IntDomain? = null) {
        if (directionalGains && scopeModel != null) {
            observe(if (directionalChoice == variable) "CONSUMED" else "FALLBACK")
            if (directionalChoice == variable) {
                observe(if (priorChoice) "PRIOR_CONSUMED" else "VARIABLE_CONSUMED")
                val comparison = when (baselineChoice) {
                    null -> "BASELINE_MISSING"
                    variable -> "BASELINE_AGREED"
                    else -> "BASELINE_CHANGED"
                }
                observe(comparison)
            }
        }
        directionalChoice = null
        baselineChoice = null
        priorChoice = false
        if (collect && scopeModel != null) {
            charge(129L)
            history.branch(variable, if (boundRecorded) value(variable, domain) else Double.NaN)
        }
    }

    fun commit(decision: SearchDecision, depth: Int) {
        if (collect && scopeModel != null) {
            charge(129L)
            history.commit(decision, depth)
        }
    }

    fun hasDirectionalScores(): Boolean {
        if (!collect || !directionalGains || !boundRecorded) return false
        charge(1L)
        return history.mayHaveScores
    }

    fun directionalScore(variable: VarRef, domain: IntDomain? = null): Double {
        if (!collect || !directionalGains || !boundRecorded) return Double.NaN
        charge(1L)
        return history.score(variable, value(variable, domain))
    }

    fun selected(variable: VarRef?, baseline: VarRef?, score: Double, baselineDomain: IntDomain? = null): VarRef? {
        directionalChoice = variable
        baselineChoice = baseline
        priorChoice = variable != null && history.usesPrior(variable)
        if (priorChoice && baseline != null && variable != baseline) {
            charge(1L)
            val reference = history.referenceScore(baseline, value(baseline, baselineDomain))
            if (!(score > reference)) {
                directionalChoice = null
                priorChoice = false
                observe("PRIOR_REJECTED")
            }
        }
        return directionalChoice
    }

    private fun value(variable: VarRef, domain: IntDomain? = null): Double {
        val value = if (!freshRecord) Double.NaN else when (variable) {
            is VarRef.IntVar -> if (intStamp.getOrElse(variable.varId) { -1 } == solveStamp) {
                intVal.getOrElse(variable.varId) { Double.NaN }
            } else Double.NaN
            is VarRef.Bool -> if (boolStamp.getOrElse(variable.varId) { -1 } == solveStamp) {
                boolVal.getOrElse(variable.varId) { Double.NaN }
            } else Double.NaN
        }
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
        if (scopeModel != null) {
            charge(history.retainedEntries.toLong() + scopeVariables.size + scopeBooleans.size)
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
        pointModel = null
        boundRecorded = false
        directionalChoice = null
        baselineChoice = null
        priorChoice = false
        unavailable = LpHintUnavailable.UNOBSERVED
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
     * Record an advisory LP iterate: its fractional primal (per structural column,
     * `FloatLpResult.primal`) and, from the [duals], the decayed reduced-cost average of
     * each variable (the reduced cost `dⱼ = cⱼ − yᵀAⱼ`; near-zero for basic columns). Keyed by the
     * relaxation's column→variable map. Purely advisory — drives value ([order]) and variable
     * ([branchScore]) selection, never feasibility or the optimum.
     */
    override fun record(relaxation: LpRelaxation, primal: DoubleArray, duals: DoubleArray) {
        recorded = true
        freshRecord = true
        pointModel = relaxation.model
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
