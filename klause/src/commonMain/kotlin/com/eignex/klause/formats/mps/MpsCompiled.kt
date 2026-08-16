package com.eignex.klause.formats.mps

import com.eignex.klause.config.DEFAULT_FLOAT_SCALE
import com.eignex.klause.config.DEFAULT_UNBOUNDED_SEARCH_BOUND
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.formats.ObjectiveSense
import com.eignex.klause.formats.channelBoolTo01
import com.eignex.klause.formats.dualFixableBounds
import com.eignex.klause.formats.rootFixedReifiedRows
import com.eignex.klause.lp.DeferredIntBounds
import com.eignex.klause.lp.OpenIntBounds
import com.eignex.klause.lp.smallModelIntBound
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
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
    /** Deferred integer-domain bounding (OBBT), or `null` when every integer column is already finite.
     *  Compiling closes each open side to a cheap fallback box; the LP tightening runs in the presolve
     *  phase ([DeferredIntBounds.run]), which also decides whether a side fell back to a lossy clamp — the
     *  honest-`unknown`/optimum-only-valid signal, known only after that runs. */
    val deferredBounds: DeferredIntBounds?,
    /** Count of LP-only continuous (real) columns (zero for a pure-integer instance). */
    val floatColumns: Int,
)

/** Bounds at or beyond this magnitude are the MPS "infinity" convention (`1e30`), not a literal bound. */
private const val MPS_INFINITY = 1e20

/**
 * Lower an [MpsModel] to a klause [Problem] for the hybrid MIP/CP engine:
 *  - **integer columns** become integer (CP search) variables; a side left unbounded (or at the `1e30`
 *    marker) is closed to the cheap fallback box now, and the OBBT tightening over the constraint
 *    relaxation is deferred to the presolve phase ([MpsCompiled.deferredBounds]); a side OBBT cannot bound
 *    stays clamped to `±[searchBound]`.
 *  - **float columns** become LP-only continuous variables — present in the LP relaxation, absent from CP
 *    search; the simplex resolves them at nodes and leaves. Their real bounds carry through directly, so
 *    an unbounded float keeps an open side of `±∞`.
 *  - an **indicated row** (an `INDICATORS` entry) becomes a reified row plus a `guard -> cond` clause over
 *    a Boolean channelled to its binary column, so the row is relaxed at the column's other value.
 *  - a constraint or objective term touching a float becomes a real ([Double]-coefficient) [Linear] row;
 *    a purely-integer row with a fractional coefficient is still scaled by [floatScale] to stay integral.
 */
fun MpsModel.toProblem(
    searchBound: Long = DEFAULT_UNBOUNDED_SEARCH_BOUND,
    @Suppress("UNUSED_PARAMETER") floatBuckets: Int = 0,
    floatScale: Long = DEFAULT_FLOAT_SCALE,
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
    val guards = IndicatorGuards(variables, intVarOf, factors)
    for (c in constraints) {
        if (c.indices.isEmpty()) {
            if (!emptyRowHolds(c.lower, c.upper)) {
                throw MpsFormatException("constraint row '${c.name}' has no variables but its bound is infeasible")
            }
            continue
        }
        val touchesFloat = c.indices.any { isFloat[it] }
        val indicator = c.indicator
        if (indicator == null) {
            if (touchesFloat) {
                emitRealRow(factors, c, isFloat, intVarOf, realVarOf)
            } else {
                emitIntRow(factors, c, intVarOf, floatScale)
            }
        } else {
            val guard = guards.guardFor(indicator, c.name)
            if (touchesFloat) {
                emitIndicatedRealRow(factors, c, isFloat, intVarOf, realVarOf, guard, guards)
            } else {
                emitIndicatedIntRow(factors, c, intVarOf, floatScale, guard, guards)
            }
        }
    }

    // OBBT over the purely-integer rows only (a real-bearing Linear carries placeholder integer data).
    // An indicator row whose literal a unit clause fixes is an ordinary constraint of the model, so it
    // belongs here too — otherwise a bound the model states through its boolean structure is invisible.
    val allLinears = factors.filterIsInstance<Linear>() + rootFixedReifiedRows(factors)
    val intLinears = allLinears.filter { it.isIntegerCore }
    // A mixed row is context, not noise: on a MIP the rows that bound an integer column usually carry
    // continuous terms too, so leaving them out leaves OBBT nothing to bound those columns with.
    val realLinears = allLinears.filter { it.hasReals }

    // A pure-integer feasibility model whose small-model bound ([smallModelIntBound]) fits keeps exact
    // verdicts: the finite box is equisatisfiable with the unbounded model, so no clamp flag. Never under
    // an objective (the box could truncate an unbounded optimum into a spurious finite one); mixed models
    // and oversized bounds fall back to the lossy searchable window.
    val small = if (numReal == 0 && objective.indices.isEmpty()) smallModelIntBound(numInt, factors) else null
    // The fallback box is a stand-in, so choose one the objective can still be evaluated over. At the full
    // searchable range a column reaches ~2^62 and the weighted sum wraps on the first few terms, which is
    // worse than a narrow box: the search then optimises a wrapped value and is rewarded for driving
    // columns further out. Narrowing keeps the model clamped either way — it only keeps the arithmetic
    // honest.
    val box = small ?: minOf(searchBound, objectiveSafeBox(isFloat, intVarOf, numInt, floatScale))

    // Defer OBBT to the presolve phase (compiling only reads): close each open integer side to the cheap
    // fallback box now, and capture the OBBT inputs so the deferred run can tighten under the solve
    // deadline. A side the LP cannot bound stays at the fallback, clamped when that box is lossy.
    @Suppress("UNCHECKED_CAST")
    val declaredBounds = obbtInput as Array<OpenIntBounds>
    // Close what the objective makes pointless to explore before deciding anything is open: a column the
    // cost only ever pushes toward one end has an optimum there, and a side closed that way is the
    // model's own, not an invented window. OBBT cannot reach these — the relaxation is genuinely
    // unbounded in those directions.
    val objScaleForCost = if (objectiveNeedsScaling(isFloat)) floatScale else 1L
    val minimiseCost = objectiveIntCoefficients(isFloat, intVarOf, numInt, objScaleForCost, sense)
    val openBounds = dualFixableBounds(numInt, factors, declaredBounds) { minimiseCost[it] }
    val deferredBounds = if (openBounds.any { it.lo == null || it.hi == null }) {
        DeferredIntBounds(
            openBounds,
            intLinears,
            realLinears,
            numReal,
            -box,
            box,
            small == null,
            realLower = realLower,
            realUpper = realUpper,
        )
    } else {
        null
    }
    val domains = Array(numInt) { j ->
        val lo = openBounds[j].lo ?: -box
        val hi = openBounds[j].hi ?: box
        if (lo <= hi) IntDomain(lo, hi) else IntDomain(lo, lo)
    }

    val objScale = objScaleForCost
    val objective = if (objective.indices.isEmpty()) {
        null
    } else {
        buildObjective(isFloat, intVarOf, realVarOf, guards.numBool, numInt, numReal, objScale)
    }

    // A raw problem: the root bake is deferred to presolve. On a wide clamped domain an
    // integer-infeasible equality would grind O(span) if baked at construction; presolve's strengthen
    // pass catches that first, at solve time, before the (now-lazy) bake runs.
    val problem = Problem(
        numBoolVars = guards.numBool,
        numIntVars = numInt,
        intDomains = domains,
        factors = factors.toTypedArray(),
        numRealVars = numReal,
        realLower = realLower,
        realUpper = realUpper,
    )
    val columns = variables.mapIndexed { i, v ->
        MpsColumn(v.name, isFloat[i], if (isFloat[i]) realVarOf[i] else intVarOf[i])
    }
    return MpsCompiled(problem, objective, sense == ObjectiveSense.MAXIMIZE, columns, objScale, deferredBounds, numReal)
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

/** A row's terms split into its integer and its continuous part, each variable-id/coefficient parallel. */
private class RealRowParts(
    val intVars: IntArray,
    val intCoeffs: DoubleArray,
    val realVars: IntArray,
    val realCoeffs: DoubleArray,
)

private fun splitRealRow(
    c: MpsConstraint,
    isFloat: BooleanArray,
    intVarOf: IntArray,
    realVarOf: IntArray,
): RealRowParts {
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
    return RealRowParts(
        intVars.toIntArray(),
        intCoeffs.toDoubleArray(),
        realVars.toIntArray(),
        realCoeffs.toDoubleArray(),
    )
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
    val p = splitRealRow(c, isFloat, intVarOf, realVarOf)
    emitRow(c.lower, c.upper, { it }) { op, bound ->
        factors.add(Linear(p.intVars, p.intCoeffs, p.realVars, p.realCoeffs, op, bound))
    }
}

/**
 * Allocator for the Boolean guards an `INDICATORS` section needs. One guard per (column, trigger value)
 * pair channels the binary column onto a bool, so every row that pair gates shares it; the per-row
 * reification bools are minted separately by the row emitters.
 */
private class IndicatorGuards(
    private val variables: List<MpsVar>,
    private val intVarOf: IntArray,
    private val factors: MutableList<Factor>,
) {
    /** Boolean variables allocated so far — the problem's `numBoolVars`. */
    var numBool = 0
        private set

    private val byTrigger = HashMap<MpsIndicator, Int>()

    fun newBool(): Int = numBool++

    /** The guard bool for [indicator] — true exactly when its column holds the trigger value. [rowName]
     *  names the row in errors. */
    fun guardFor(indicator: MpsIndicator, rowName: String): Int = byTrigger.getOrPut(indicator) {
        val v = variables[indicator.column]
        // A continuous column is LP-only, with no CP variable to equality-test, so it cannot gate a row.
        if (!v.integer) {
            throw MpsFormatException(
                "INDICATORS row '$rowName' names continuous column '${v.name}'; an indicator must be integer",
            )
        }
        val guard = newBool()
        val intVar = intVarOf[indicator.column]
        // The guard is an equality test against the trigger value, exact over any integer domain. The
        // binary channel is the same equality with the tight LP form, so it is taken when the declared
        // bounds actually say binary — an integer column left unbounded (this parser's `[0, +∞)`
        // default) still gates correctly through the general form rather than being rejected.
        if (v.lower == 0.0 && v.upper == 1.0) {
            channelBoolTo01(factors, guard, intVar, whenTrue = indicator.whenOne)
        } else {
            val trigger = if (indicator.whenOne) 1 else 0
            factors.add(ReifiedLinear(guard, intArrayOf(1), intArrayOf(intVar), LinearOp.EQ, trigger))
        }
        guard
    }
}

/** Post `guard -> cond`, the one-directional half of an indicated row's reification. */
private fun postGuardImplies(factors: MutableList<Factor>, guard: Int, cond: Int) {
    factors.add(Clause(intArrayOf(Lit.negate(Lit.make(guard, true)), Lit.make(cond, true))))
}

/** Emit a purely-integer indicated row: a fresh `cond <-> row` reification per emitted part plus the
 *  `guard -> cond` clause, so the row is relaxed whenever the indicator column takes the other value. */
private fun emitIndicatedIntRow(
    factors: MutableList<Factor>,
    c: MpsConstraint,
    intVarOf: IntArray,
    floatScale: Long,
    guard: Int,
    guards: IndicatorGuards,
) {
    val vars = IntArray(c.indices.size) { intVarOf[c.indices[it]] }
    val scale = if (c.coeffs.any { !isIntegral(it) } || !boundsIntegral(c.lower, c.upper)) floatScale else 1L
    val coeffs = LongArray(c.indices.size) { (c.coeffs[it] * scale).roundToLong() }
    emitRow(c.lower, c.upper, { (it * scale).roundToLong() }) { op, bound ->
        val cond = guards.newBool()
        factors.add(ReifiedLinear(cond, coeffs, vars, op, bound))
        postGuardImplies(factors, guard, cond)
    }
}

/** Emit an indicated row touching a continuous column. [ReifiedRealLinear] carries inequalities only, so
 *  an equality row becomes a separately-reified `≤` atom and `≥` atom. */
private fun emitIndicatedRealRow(
    factors: MutableList<Factor>,
    c: MpsConstraint,
    isFloat: BooleanArray,
    intVarOf: IntArray,
    realVarOf: IntArray,
    guard: Int,
    guards: IndicatorGuards,
) {
    val p = splitRealRow(c, isFloat, intVarOf, realVarOf)
    fun post(op: LinearOp, bound: Double) {
        val cond = guards.newBool()
        factors.add(ReifiedRealLinear(cond, p.intVars, p.intCoeffs, p.realVars, p.realCoeffs, op, bound))
        postGuardImplies(factors, guard, cond)
    }
    c.upper?.let { post(LinearOp.LE, it) }
    c.lower?.let { post(LinearOp.GE, it) }
}

private fun MpsModel.objectiveNeedsScaling(isFloat: BooleanArray): Boolean =
    objective.indices.withIndex().any { (k, idx) -> !isFloat[idx] && !isIntegral(objective.coeffs[k]) } ||
        !isIntegral(objective.constant)

/**
 * The widest box over which `constant + Σ c·x` still fits `Long`, or [Long.MAX_VALUE] when the objective
 * places no limit. Deliberately generous — half the range is left as headroom for the accumulation order,
 * which the evaluator is free to choose.
 */
private fun MpsModel.objectiveSafeBox(isFloat: BooleanArray, intVarOf: IntArray, numInt: Int, scale: Long): Long {
    var weight = 0L
    objective.indices.forEachIndexed { k, idx ->
        if (isFloat[idx] || intVarOf[idx] >= numInt) return@forEachIndexed
        val c = (objective.coeffs[k] * scale).roundToLong()
        val magnitude = if (c < 0L) -c else c
        // Saturate rather than wrap while measuring; a saturated total simply yields the narrowest box.
        weight = if (weight > Long.MAX_VALUE - magnitude) Long.MAX_VALUE else weight + magnitude
    }
    return if (weight <= 1L) Long.MAX_VALUE else (Long.MAX_VALUE / 2L) / weight
}

/** The objective's integer coefficients oriented for minimisation, whatever the model's sense. */
private fun MpsModel.objectiveIntCoefficients(
    isFloat: BooleanArray,
    intVarOf: IntArray,
    numInt: Int,
    scale: Long,
    sense: ObjectiveSense,
): LongArray {
    val out = LongArray(numInt)
    val flip = if (sense == ObjectiveSense.MAXIMIZE) -1L else 1L
    objective.indices.forEachIndexed { k, idx ->
        if (!isFloat[idx]) out[intVarOf[idx]] = flip * (objective.coeffs[k] * scale).roundToLong()
    }
    return out
}

private fun MpsModel.buildObjective(
    isFloat: BooleanArray,
    intVarOf: IntArray,
    realVarOf: IntArray,
    numBool: Int,
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
        // Indicator guards carry no objective weight, but the weight vector may not outrun `numBoolVars`.
        boolWeights = if (numBool == 0) EmptyLongArray else LongArray(numBool),
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
