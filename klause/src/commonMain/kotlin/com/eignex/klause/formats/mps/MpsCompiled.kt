package com.eignex.klause.formats.mps

import com.eignex.klause.config.DEFAULT_FLOAT_SCALE
import com.eignex.klause.config.DEFAULT_UNBOUNDED_SEARCH_BOUND
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.formats.ObjectiveSense
import com.eignex.klause.lp.OpenIntBounds
import com.eignex.klause.lp.smallModelIntBound
import com.eignex.klause.lp.tightenOpenIntBounds
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.EmptyDoubleArray
import com.eignex.klause.util.EmptyLongArray
import kotlin.math.abs
import kotlin.math.roundToLong

/** One MPS column in declaration order, for rendering a solution value back by name. */
data class MpsColumn(
    /** The column's declared name. */
    val name: String,
    /** True when the column is an LP-only continuous (real) variable, false for an integer variable. */
    val real: Boolean,
    /** The backing variable id — a real var id when [real], else an integer var id. */
    val id: Int,
)

/** An [MpsModel] lowered to a klause [Problem], ready to solve. */
data class MpsCompiled(
    /** The compiled solver problem — an integer variable per integer MPS column, an LP-only continuous
     *  variable per (bounded or unbounded) float column. */
    val problem: Problem,
    /** Objective, or `null` for a feasibility instance (no `N` row). */
    val objective: LinearObjective?,
    /** True when the objective is a maximise. */
    val maximize: Boolean,
    /** Columns in declaration order, each mapping a name to its integer- or real-variable id. */
    val columns: List<MpsColumn>,
    /** Fixed-point factor the objective was multiplied by (1 when it is already integral); divide the
     *  reported objective by it for the true value. */
    val objectiveScale: Long,
    /** True when an integer variable unbounded on some side was clamped to the finite search range, so an
     *  `unsat`/optimum is only valid within that range. */
    val clamped: Boolean,
    /** Count of LP-only continuous (real) columns (zero for a pure-integer instance). */
    val floatColumns: Int,
)

/** Bounds at or beyond this magnitude are the MPS "infinity" convention (`1e30`), not a literal bound. */
private const val MPS_INFINITY = 1e20

/**
 * Lower an [MpsModel] to a klause [Problem] for the hybrid MIP/CP engine (issue #1232):
 *  - **integer columns** become integer (CP search) variables; a side left unbounded (or at the `1e30`
 *    marker) is tightened by OBBT ([tightenOpenIntBounds]) over the constraint relaxation, and only a
 *    side OBBT cannot bound is clamped to `±[searchBound]` (flagged by [MpsCompiled.clamped]).
 *  - **float columns** become LP-only continuous variables — present in the LP relaxation, absent from CP
 *    search; the simplex resolves them at nodes and leaves. Their real bounds carry through directly, so
 *    an unbounded float is no longer rejected (its open side is `±∞`).
 *  - a constraint or objective term touching a float becomes a real ([Double]-coefficient) [Linear] row;
 *    a purely-integer row with a fractional coefficient is still scaled by [floatScale] to stay integral.
 */
fun MpsModel.toProblem(
    searchBound: Long = DEFAULT_UNBOUNDED_SEARCH_BOUND,
    @Suppress("UNUSED_PARAMETER") floatBuckets: Int = 0,
    floatScale: Long = DEFAULT_FLOAT_SCALE,
    cancellation: Cancellation = Cancellation.Never,
): MpsCompiled {
    val isFloat = BooleanArray(variables.size) { !variables[it].integer }
    val intVarOf = IntArray(variables.size) { -1 }
    val realVarOf = IntArray(variables.size) { -1 }
    var numInt = 0
    var numReal = 0
    for (i in variables.indices) if (isFloat[i]) realVarOf[i] = numReal++ else intVarOf[i] = numInt++

    // Real-variable bounds (open sides realised as ±∞). Integer OBBT input keeps true open sides (null)
    // so OBBT can close the genuine unbounded region before any clamp.
    val realLower = DoubleArray(numReal)
    val realUpper = DoubleArray(numReal)
    val obbtInput = arrayOfNulls<OpenIntBounds>(numInt)
    for (i in variables.indices) {
        val v = variables[i]
        if (isFloat[i]) {
            realLower[realVarOf[i]] = openLower(v.lower)
            realUpper[realVarOf[i]] = openUpper(v.upper)
        } else {
            obbtInput[intVarOf[i]] = OpenIntBounds(intBoundOrNull(v.lower), intBoundOrNull(v.upper))
        }
    }

    val factors = ArrayList<Factor>()
    for (c in constraints) {
        if (c.indices.isEmpty()) {
            if (!emptyRowHolds(c.lower, c.upper)) {
                throw MpsFormatException("constraint row '${c.name}' has no variables but its bound is infeasible")
            }
            continue
        }
        if (c.indices.any { isFloat[it] }) {
            emitRealRow(factors, c, isFloat, intVarOf, realVarOf)
        } else {
            emitIntRow(factors, c, intVarOf, floatScale)
        }
    }

    // OBBT over the purely-integer rows only (a real-bearing Linear carries placeholder integer data).
    val intLinears = factors.filterIsInstance<Linear>().filter { !it.hasReals }

    // Bound OBBT by the load deadline: on a large model each open-int side is a full LP solve over the
    // relaxation, so an unbounded pass could outlast the whole solve budget. A side left un-tightened when
    // the deadline trips is clamped below (sound — the clamp only ever loosens).
    @Suppress("UNCHECKED_CAST")
    val tightened = tightenOpenIntBounds(obbtInput as Array<OpenIntBounds>, intLinears, cancellation)
    // A pure-integer feasibility model whose small-model bound ([smallModelIntBound]) fits keeps
    // exact verdicts: the finite box is equisatisfiable with the unbounded model, so no clamp flag.
    // Never under an objective (the box could truncate an unbounded optimum into a spurious finite
    // one); mixed models and oversized bounds fall back to the lossy searchable window.
    val small = if (numReal == 0 && objective.indices.isEmpty()) smallModelIntBound(numInt, factors) else null
    val box = small ?: searchBound
    var clamped = false
    val domains = Array(numInt) { j ->
        val lo = tightened[j].lo ?: (-box).also { if (small == null) clamped = true }
        val hi = tightened[j].hi ?: box.also { if (small == null) clamped = true }
        if (lo <= hi) IntDomain(lo, hi) else IntDomain(lo, lo)
    }

    val objScale = if (objectiveNeedsScaling(isFloat)) floatScale else 1L
    val objective = if (objective.indices.isEmpty()) {
        null
    } else {
        buildObjective(isFloat, intVarOf, realVarOf, numInt, numReal, objScale)
    }

    val problem = Problem(
        numBoolVars = 0,
        numIntVars = numInt,
        intDomains = domains,
        factors = factors.toTypedArray(),
        preFolded = true,
        numRealVars = numReal,
        realLower = realLower,
        realUpper = realUpper,
    )
    val columns = variables.mapIndexed { i, v ->
        MpsColumn(v.name, isFloat[i], if (isFloat[i]) realVarOf[i] else intVarOf[i])
    }
    return MpsCompiled(problem, objective, sense == ObjectiveSense.MAXIMIZE, columns, objScale, clamped, numReal)
}

/** Emit a purely-integer row over integer-variable ids, scaling by [floatScale] when a coefficient or
 *  bound is fractional so the integer [Linear] stays exact. */
private fun emitIntRow(factors: MutableList<Factor>, c: MpsConstraint, intVarOf: IntArray, floatScale: Long) {
    val vars = IntArray(c.indices.size) { intVarOf[c.indices[it]] }
    val scale = if (c.coeffs.any { !isIntegral(it) } || !boundsIntegral(c.lower, c.upper)) floatScale else 1L
    val coeffs = LongArray(c.indices.size) { (c.coeffs[it] * scale).roundToLong() }
    emitRow(c.lower, c.upper, { (it * scale).roundToLong() }) { op, bound ->
        factors.add(Linear(coeffs, vars, op, bound))
    }
}

/** Emit a row touching a continuous variable as a real ([Double]-coefficient) LP-only [Linear] row over
 *  its integer and real parts. */
private fun emitRealRow(
    factors: MutableList<Factor>,
    c: MpsConstraint,
    isFloat: BooleanArray,
    intVarOf: IntArray,
    realVarOf: IntArray,
) {
    val intVars = ArrayList<Int>()
    val intCoeffs = ArrayList<Double>()
    val realVars = ArrayList<Int>()
    val realCoeffs = ArrayList<Double>()
    for (k in c.indices.indices) {
        val idx = c.indices[k]
        if (isFloat[idx]) {
            realVars.add(realVarOf[idx])
            realCoeffs.add(c.coeffs[k])
        } else {
            intVars.add(intVarOf[idx])
            intCoeffs.add(c.coeffs[k])
        }
    }
    val iv = intVars.toIntArray()
    val ic = intCoeffs.toDoubleArray()
    val rv = realVars.toIntArray()
    val rc = realCoeffs.toDoubleArray()
    emitRow(c.lower, c.upper, { it }) { op, bound ->
        factors.add(Linear(iv, ic, rv, rc, op, bound))
    }
}

private fun MpsModel.objectiveNeedsScaling(isFloat: BooleanArray): Boolean =
    objective.indices.withIndex().any { (k, idx) -> !isFloat[idx] && !isIntegral(objective.coeffs[k]) } ||
        !isIntegral(objective.constant)

private fun MpsModel.buildObjective(
    isFloat: BooleanArray,
    intVarOf: IntArray,
    realVarOf: IntArray,
    numInt: Int,
    numReal: Int,
    scale: Long,
): LinearObjective {
    val intCoefficients = LongArray(numInt)
    val realCoefficients = DoubleArray(numReal)
    objective.indices.forEachIndexed { k, idx ->
        if (isFloat[idx]) {
            realCoefficients[realVarOf[idx]] = objective.coeffs[k] * scale
        } else {
            intCoefficients[intVarOf[idx]] = (objective.coeffs[k] * scale).roundToLong()
        }
    }
    return LinearObjective(
        boolWeights = EmptyLongArray,
        intCoefficients = intCoefficients,
        constant = (objective.constant * scale).roundToLong(),
        realCoefficients = if (numReal == 0) EmptyDoubleArray else realCoefficients,
    )
}

/** Emit the row's factor(s) for a two-sided / equality / one-sided bound, transforming each raw bound
 *  through [bound] and posting through [post] with the resolved op and typed bound. */
private inline fun <T> emitRow(lower: Double?, upper: Double?, bound: (Double) -> T, post: (LinearOp, T) -> Unit) {
    when {
        lower != null && upper != null && lower == upper -> post(LinearOp.EQ, bound(lower))

        lower != null && upper != null -> {
            post(LinearOp.LE, bound(upper))
            post(LinearOp.GE, bound(lower))
        }

        upper != null -> post(LinearOp.LE, bound(upper))

        lower != null -> post(LinearOp.GE, bound(lower))
    }
}

private fun emptyRowHolds(lower: Double?, upper: Double?): Boolean =
    (lower == null || lower <= 0.0) && (upper == null || upper >= 0.0)

private fun intBoundOrNull(value: Double?): Long? =
    if (value == null || value >= MPS_INFINITY || value <= -MPS_INFINITY) null else value.roundToLong()

private fun openLower(value: Double?): Double =
    if (value == null || value <= -MPS_INFINITY) Double.NEGATIVE_INFINITY else value

private fun openUpper(value: Double?): Double =
    if (value == null || value >= MPS_INFINITY) Double.POSITIVE_INFINITY else value

private fun boundsIntegral(lower: Double?, upper: Double?): Boolean =
    (lower == null || isIntegral(lower)) && (upper == null || isIntegral(upper))

private fun isIntegral(value: Double): Boolean = abs(value - value.roundToLong()) <= 1e-6
