package com.eignex.klause.formats.mps

import com.eignex.klause.config.DEFAULT_FLOAT_BUCKETS
import com.eignex.klause.config.DEFAULT_FLOAT_SCALE
import com.eignex.klause.config.DEFAULT_UNBOUNDED_SEARCH_BOUND
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.formats.FloatBucketing
import com.eignex.klause.formats.ObjectiveSense
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.EmptyLongArray
import kotlin.math.abs
import kotlin.math.roundToLong

/** An [MpsModel] lowered to a klause [Problem], ready to solve. */
data class MpsCompiled(
    /** The compiled solver problem — one integer variable per MPS column (an integer column directly, a
     *  bounded float as its bucket index). */
    val problem: Problem,
    /** Objective, or `null` for a feasibility instance (no `N` row). */
    val objective: LinearObjective?,
    /** True when the objective is a maximise. */
    val maximize: Boolean,
    /** Declared variable name to backing integer-variable id. */
    val varNames: Map<String, Int>,
    /** Bucketing metadata by variable id for the (bounded) float columns, for rendering their values. */
    val floatBucketings: Map<Int, FloatBucketing>,
    /** Fixed-point factor the objective was multiplied by (1 when it is already integral); divide the
     *  reported objective by it for the true value. */
    val objectiveScale: Long,
    /** True when a variable unbounded on some side was clamped to the finite search range, so an
     *  `unsat`/optimum is only valid within that range. */
    val clamped: Boolean,
    /** Count of bounded float columns that were bucketed (zero for a pure-integer instance). */
    val floatColumns: Int,
)

/** Bounds at or beyond this magnitude are the MPS "infinity" convention (`1e30`), not a literal bound. */
private const val MPS_INFINITY = 1e20

/**
 * Lower an [MpsModel] to a klause [Problem]. klause is integer-only, so:
 *  - **integer columns** become integer variables; a side left unbounded (or at the `1e30` marker) is
 *    clamped to `±[searchBound]` — the shared unbounded-search range (an unbounded `Long` domain would
 *    be an O(span) bake bomb), flagged by [MpsCompiled.clamped].
 *  - **bounded float columns** are discretised into [floatBuckets] buckets (the same config as the DSL),
 *    the solver reasoning over the bucket index; a row that mentions a float, or carries a fractional
 *    coefficient, is scaled by [floatScale] so its coefficients stay integral.
 *  - **unbounded float columns are rejected** — only a bounded float can be bucketed.
 */
fun MpsModel.toProblem(
    searchBound: Long = DEFAULT_UNBOUNDED_SEARCH_BOUND,
    floatBuckets: Int = DEFAULT_FLOAT_BUCKETS,
    floatScale: Long = DEFAULT_FLOAT_SCALE,
): MpsCompiled {
    var clamped = false
    val floatBk = HashMap<Int, FloatBucketing>()
    val domains = Array(variables.size) { i ->
        val v = variables[i]
        if (v.integer) {
            val lo = intBound(v.lower, -searchBound) { clamped = true }
            val hi = intBound(v.upper, searchBound) { clamped = true }
            if (lo <= hi) IntDomain(lo, hi) else IntDomain(lo, lo)
        } else {
            val lo = v.lower
            val hi = v.upper
            if (lo == null || hi == null || lo <= -MPS_INFINITY || hi >= MPS_INFINITY) {
                throw MpsFormatException("unbounded float variable '${v.name}' (only bounded floats can be bucketed)")
            }
            floatBk[i] = FloatBucketing(varId = i, lo = lo, hi = hi, buckets = floatBuckets)
            IntDomain(0L, (floatBuckets - 1).toLong())
        }
    }

    val factors = ArrayList<Factor>()
    for (c in constraints) {
        if (c.indices.isEmpty()) {
            // A term-free row is `0 OP rhs`. When 0 satisfies the bound the row is redundant — the
            // common MPS placeholder (e.g. an objective-tracking `ZBESTROW` at `0 <= 0`); drop it.
            // Otherwise the constraint, and so the whole model, is infeasible.
            if (!emptyRowHolds(c.lower, c.upper)) {
                throw MpsFormatException("constraint row '${c.name}' has no variables but its bound is infeasible")
            }
            continue
        }
        if (rowNeedsScaling(c.indices, c.coeffs, c.lower, c.upper, floatBk)) {
            var boundAdjust = 0L
            val coeffs = LongArray(c.indices.size) { j ->
                scaledTermCoeff(c.indices[j], c.coeffs[j], floatBk, floatScale) { boundAdjust += it }
            }
            emitRow(factors, coeffs, c.indices, c.lower, c.upper) { (it * floatScale).roundToLong() - boundAdjust }
        } else {
            val coeffs = LongArray(c.indices.size) { c.coeffs[it].roundToLong() }
            emitRow(factors, coeffs, c.indices, c.lower, c.upper) { it.roundToLong() }
        }
    }

    val objScale = if (objectiveNeedsScaling(floatBk)) floatScale else 1L
    val objective = if (objective.indices.isEmpty()) null else buildObjective(floatBk, objScale)

    val problem = Problem(
        numBoolVars = 0,
        numIntVars = variables.size,
        intDomains = domains,
        factors = factors.toTypedArray(),
        // Defer the root bake: a clamped-wide domain would otherwise grind O(span) at construction.
        preFolded = true,
    )
    return MpsCompiled(
        problem,
        objective,
        sense == ObjectiveSense.MAXIMIZE,
        variables.withIndex().associate { (i, v) -> v.name to i },
        floatBk,
        objScale,
        clamped,
        floatBk.size,
    )
}

/** A row is scaled when it mentions a bucketed float, or any coefficient/bound is non-integral. */
private fun rowNeedsScaling(
    indices: IntArray,
    coeffs: DoubleArray,
    lower: Double?,
    upper: Double?,
    floatBk: Map<Int, FloatBucketing>,
): Boolean = indices.any { floatBk.containsKey(it) } ||
    coeffs.any { !isIntegral(it) } ||
    (lower != null && !isIntegral(lower)) ||
    (upper != null && !isIntegral(upper))

private fun MpsModel.objectiveNeedsScaling(floatBk: Map<Int, FloatBucketing>): Boolean =
    objective.indices.any { floatBk.containsKey(it) } ||
        objective.coeffs.any { !isIntegral(it) } ||
        !isIntegral(objective.constant)

/** The scaled coefficient of one term, feeding any float lower-bound offset to [addOffset]. For an
 *  integer variable it is `coef·scale`; for a bucketed float `coef·step·scale`, since its value is
 *  `lo + step·bucket`, with the `coef·lo·scale` part moved to the row bound via [addOffset]. */
private inline fun scaledTermCoeff(
    varId: Int,
    coef: Double,
    floatBk: Map<Int, FloatBucketing>,
    scale: Long,
    addOffset: (Long) -> Unit,
): Long {
    val bk = floatBk[varId] ?: return (coef * scale).roundToLong()
    val step = if (bk.buckets > 1) (bk.hi - bk.lo) / (bk.buckets - 1) else 0.0
    addOffset((coef * bk.lo * scale).roundToLong())
    return (coef * step * scale).roundToLong()
}

private fun MpsModel.buildObjective(floatBk: Map<Int, FloatBucketing>, scale: Long): LinearObjective {
    val intCoefficients = LongArray(variables.size)
    var constant = (objective.constant * scale).roundToLong()
    objective.indices.forEachIndexed { k, idx ->
        intCoefficients[idx] = scaledTermCoeff(idx, objective.coeffs[k], floatBk, scale) { constant += it }
    }
    return LinearObjective(boolWeights = EmptyLongArray, intCoefficients = intCoefficients, constant = constant)
}

/** Emit the [Linear] factor(s) for a two-sided row, transforming each raw bound through [bound]. */
private inline fun emitRow(
    factors: MutableList<Factor>,
    coeffs: LongArray,
    vars: IntArray,
    lower: Double?,
    upper: Double?,
    bound: (Double) -> Long,
) {
    when {
        lower != null && upper != null && lower == upper -> factors.add(Linear(coeffs, vars, LinearOp.EQ, bound(lower)))

        lower != null && upper != null -> {
            factors.add(Linear(coeffs, vars, LinearOp.LE, bound(upper)))
            factors.add(Linear(coeffs, vars, LinearOp.GE, bound(lower)))
        }

        upper != null -> factors.add(Linear(coeffs, vars, LinearOp.LE, bound(upper)))

        lower != null -> factors.add(Linear(coeffs, vars, LinearOp.GE, bound(lower)))
    }
}

/** Whether `0 OP rhs` holds for a term-free row: `0` must clear any lower bound and stay under any
 *  upper one (a two-sided or `EQ` row folds to `lower <= 0 <= upper`; an absent side is unconstrained). */
private fun emptyRowHolds(lower: Double?, upper: Double?): Boolean =
    (lower == null || lower <= 0.0) && (upper == null || upper >= 0.0)

/** Resolve an integer-column bound: `null` or the `1e30` marker clamps to [clampTo] (via [onClamp]). */
private inline fun intBound(value: Double?, clampTo: Long, onClamp: () -> Unit): Long =
    if (value == null || value >= MPS_INFINITY || value <= -MPS_INFINITY) {
        onClamp()
        clampTo
    } else {
        value.roundToLong()
    }

private fun isIntegral(value: Double): Boolean = abs(value - value.roundToLong()) <= 1e-6
