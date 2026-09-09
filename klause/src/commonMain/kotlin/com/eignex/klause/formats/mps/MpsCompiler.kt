package com.eignex.klause.formats.mps

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearObjectiveSpec
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.lowering.RowScale
import com.eignex.klause.lowering.RowScaleBuilder
import com.eignex.klause.lowering.channelBoolTo01
import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpEntry
import com.eignex.klause.lp.engine.ExactLpModel
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpObjective
import com.eignex.klause.lp.engine.ExactLpPremise
import com.eignex.klause.lp.engine.ExactLpPremises
import com.eignex.klause.lp.engine.ExactLpRow
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Bits
import com.eignex.klause.util.EmptyDoubleArray
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.EmptyLongArray
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.nextUp

/** Raised when an MPS source model cannot be represented by klause's lowering. */
class MpsLoweringException(msg: String) : IllegalArgumentException("MPS: $msg")

private fun mpsLoweringError(msg: String): Nothing = throw MpsLoweringException(msg)

/** One MPS column in declaration order, for rendering a solution value back by name. */
data class MpsColumn(
    /** The column's declared name. */
    val name: String,
    /** True when the column is an LP-only continuous (real) variable, false for an integer variable. */
    val real: Boolean,
    /** The backing variable id — a real var id when [real], else an integer var id. */
    val id: Int,
)

/** An [MpsModel] lowered to a klause model. */
@Suppress("UndocumentedPublicFunction")
class MpsCompiled(
    /** The compiled solver problem — an integer variable per integer MPS column, an LP-only continuous
     *  variable per (bounded or unbounded) float column. */
    val model: Problem,
    /** Objective, or `null` for a feasibility instance (no `N` row). */
    val objective: LinearObjectiveSpec?,
    /** True when the objective is a maximise. */
    val maximize: Boolean,
    /** Columns in declaration order, each mapping a name to its integer- or real-variable id. */
    val columns: List<MpsColumn>,
    /** Power of ten the retained objective was multiplied by to carry its integer-column coefficients
     *  onto whole numbers (1 when they already are). */
    val objectiveScale: Long,
    /** Maximum absolute difference between the reported retained objective and the source objective,
     *  over the declared integer-column bounds; null when no objective term was dropped. */
    val objectiveErrorBound: Double?,
    /** True when an integer row was tightened to an inner approximation after a term underflowed.
     *  A satisfying assignment is sound for the source model, while exhaustion leaves its boundary
     *  unresolved. */
    val hasInnerConstraintApproximation: Boolean,
    /** Count of LP-only continuous (real) columns (zero for a pure-integer instance). */
    val floatColumns: Int,
) {
    private var exactLpSource: (() -> ExactLpModel)? = null
    private var exactLpCache: ExactLpModel? = null
    internal val exactLpModel: ExactLpModel?
        get() {
            exactLpCache?.let { return it }
            return exactLpSource?.invoke()?.also { exactLpCache = it }
        }

    internal fun withExactLpModel(source: () -> ExactLpModel): MpsCompiled = apply {
        check(exactLpSource == null) { "exact MPS LP model is already attached" }
        exactLpSource = source
    }

    fun copy(
        model: Problem = this.model,
        objective: LinearObjectiveSpec? = this.objective,
        maximize: Boolean = this.maximize,
        columns: List<MpsColumn> = this.columns,
        objectiveScale: Long = this.objectiveScale,
        objectiveErrorBound: Double? = this.objectiveErrorBound,
        hasInnerConstraintApproximation: Boolean = this.hasInnerConstraintApproximation,
        floatColumns: Int = this.floatColumns,
    ): MpsCompiled {
        val result = MpsCompiled(
            model,
            objective,
            maximize,
            columns,
            objectiveScale,
            objectiveErrorBound,
            hasInnerConstraintApproximation,
            floatColumns,
        )
        if (model === this.model && objective === this.objective && columns === this.columns &&
            maximize == this.maximize && objectiveScale == this.objectiveScale &&
            objectiveErrorBound.sameDataValue(this.objectiveErrorBound) &&
            hasInnerConstraintApproximation == this.hasInnerConstraintApproximation &&
            floatColumns == this.floatColumns
        ) {
            result.exactLpSource = exactLpSource
            result.exactLpCache = exactLpCache
        }
        return result
    }

    operator fun component1(): Problem = model
    operator fun component2(): LinearObjectiveSpec? = objective
    operator fun component3(): Boolean = maximize
    operator fun component4(): List<MpsColumn> = columns
    operator fun component5(): Long = objectiveScale
    operator fun component6(): Double? = objectiveErrorBound
    operator fun component7(): Boolean = hasInnerConstraintApproximation
    operator fun component8(): Int = floatColumns

    override fun equals(other: Any?): Boolean = other is MpsCompiled &&
        model == other.model && objective == other.objective && maximize == other.maximize &&
        columns == other.columns && objectiveScale == other.objectiveScale &&
        objectiveErrorBound.sameDataValue(other.objectiveErrorBound) &&
        hasInnerConstraintApproximation == other.hasInnerConstraintApproximation &&
        floatColumns == other.floatColumns

    override fun hashCode(): Int {
        var result = model.hashCode()
        result = 31 * result + (objective?.hashCode() ?: 0)
        result = 31 * result + maximize.hashCode()
        result = 31 * result + columns.hashCode()
        result = 31 * result + objectiveScale.hashCode()
        result = 31 * result + (objectiveErrorBound?.hashCode() ?: 0)
        result = 31 * result + hasInnerConstraintApproximation.hashCode()
        return 31 * result + floatColumns
    }

    override fun toString(): String = "MpsCompiled(model=$model, objective=$objective, maximize=$maximize, " +
        "columns=$columns, objectiveScale=$objectiveScale, objectiveErrorBound=$objectiveErrorBound, " +
        "hasInnerConstraintApproximation=$hasInnerConstraintApproximation, floatColumns=$floatColumns)"
}

private fun Double?.sameDataValue(other: Double?): Boolean = when {
    this == null -> other == null
    other == null -> false
    else -> toBits() == other.toBits()
}

/** Bounds at or beyond this magnitude are the MPS "infinity" convention (`1e30`), not a literal bound. */
private const val MPS_INFINITY = 1e20

/**
 * Lower an [MpsModel] to a klause [Problem] for the hybrid MIP/CP engine:
 *  - **integer columns** become model integer variables; a side left unbounded (or at the `1e30`
 *    marker) stays open for pipeline selection.
 *  - **float columns** become LP-only continuous variables — present in the LP relaxation, absent from CP
 *    search; the simplex resolves them at nodes and leaves. Their real bounds carry through directly, so
 *    an unbounded float keeps an open side of `±∞`.
 *  - an **indicated row** (an `INDICATORS` entry) becomes a reified row plus a `guard -> cond` clause over
 *    a Boolean channelled to its binary column, so the row is relaxed at the column's other value.
 *  - a constraint or objective term touching a float becomes a real ([Double]-coefficient) [Linear] row;
 *    a purely-integer row with a fractional coefficient is multiplied onto the least common denominator
 *    of the decimals it is written with, so the integer row restates the source rather than rounding it.
 */
fun MpsModel.toProblem(): MpsCompiled {
    val exactInput = exactAdapterSnapshot()
    val isFloat = BooleanArray(variables.size) { !variables[it].integer }
    val intVarOf = IntArray(variables.size) { -1 }
    val realVarOf = IntArray(variables.size) { -1 }
    var numInt = 0
    var numReal = 0
    for (i in variables.indices) if (isFloat[i]) realVarOf[i] = numReal++ else intVarOf[i] = numInt++

    // Real-variable bounds use ±∞. Integer source sides remain genuinely open when MPS omits them.
    val realLower = DoubleArray(numReal)
    val realUpper = DoubleArray(numReal)
    val lower = LongArray(numInt)
    val upper = LongArray(numInt)
    var openLoBits: Bits? = null
    var openHiBits: Bits? = null
    for (i in variables.indices) {
        val v = variables[i]
        if (isFloat[i]) {
            realLower[realVarOf[i]] = openLower(v.lower)
            realUpper[realVarOf[i]] = openUpper(v.upper)
        } else {
            val id = intVarOf[i]
            val lo = intLowerOrNull(v.lower)
            val hi = intUpperOrNull(v.upper)
            lower[id] = lo ?: 0L
            upper[id] = hi ?: 0L
            if (lo == null) (openLoBits ?: Bits(numInt).also { openLoBits = it }).set(id)
            if (hi == null) (openHiBits ?: Bits(numInt).also { openHiBits = it }).set(id)
        }
    }

    val factors = ArrayList<Factor>()
    val guards = IndicatorGuards(variables, intVarOf, factors)
    var hasInnerConstraintApproximation = false
    for (c in constraints) {
        if (c.indices.isEmpty()) {
            if (!emptyRowHolds(c.lower, c.upper)) {
                throw MpsLoweringException("constraint row '${c.name}' has no variables but its bound is infeasible")
            }
            continue
        }
        val touchesFloat = c.indices.any { isFloat[it] }
        val indicator = c.indicator
        if (indicator == null) {
            if (touchesFloat) {
                emitRealRow(factors, c, isFloat, intVarOf, realVarOf)
            } else {
                hasInnerConstraintApproximation = emitIntRow(factors, c, variables, intVarOf) ||
                    hasInnerConstraintApproximation
            }
        } else {
            val guard = guards.guardFor(indicator, c.name)
            if (touchesFloat) {
                emitIndicatedRealRow(factors, c, isFloat, intVarOf, realVarOf, guard, guards)
            } else {
                hasInnerConstraintApproximation =
                    emitIndicatedIntRow(factors, c, variables, intVarOf, guard, guards) ||
                    hasInnerConstraintApproximation
            }
        }
    }

    // A declared crossing is infeasible. Keep both stated bounds in the model by restating the upper side
    // as a row; the canonicalized range only lets the finite-domain representation exist if materialized.
    for (j in 0 until numInt) {
        if (openLoBits?.get(j) != true && openHiBits?.get(j) != true && lower[j] > upper[j]) {
            factors.add(Linear(longArrayOf(1L), intArrayOf(j), LinearOp.LE, upper[j]))
            upper[j] = lower[j]
        }
    }

    val objRowScale = objectiveRowScale(isFloat)
    val objScale = objRowScale.multiplier
    val objectiveErrorBound =
        if (objective.indices.isEmpty()) null else objectiveApproximationError(objRowScale, isFloat)
    val objective = if (objective.indices.isEmpty()) {
        null
    } else {
        buildObjective(isFloat, intVarOf, realVarOf, guards.numBool, numInt, numReal, objRowScale)
    }

    val model = Problem(
        numBoolVars = guards.numBool,
        intBounds = IntBounds.fromModelBounds(lower, upper, openLoBits, openHiBits),
        factors = factors.toTypedArray(),
        numRealVars = numReal,
        realLower = realLower,
        realUpper = realUpper,
    )
    val columns = variables.mapIndexed { i, v ->
        MpsColumn(v.name, isFloat[i], if (isFloat[i]) realVarOf[i] else intVarOf[i])
    }
    return MpsCompiled(
        model,
        objective,
        sense == ObjectiveSense.MAXIMIZE,
        columns,
        objScale,
        objectiveErrorBound,
        hasInnerConstraintApproximation,
        numReal,
    ).withExactLpModel { exactInput.toExactLpModel() }
}

// Build the authoritative source LP beside the compatible hybrid Problem lowering.
internal fun MpsModel.toExactLpModel(source: MpsSourceNumbers = sourceNumbers()): ExactLpModel {
    val zero = ExactLpNumber.of(0L)
    val origins = source.variableBounds.map { (lower, _) ->
        lower.finiteMps()?.takeUnless(MpsSourceNumber::isIeee)?.toExactLpNumber() ?: zero
    }
    val columns = variables.mapIndexed { index, variable ->
        val (lower, upper) = source.variableBounds[index]
        ExactLpColumn(
            ExactLpBounds(
                lower.finiteMps()?.shiftedBy(origins[index])?.let(::ExactLpSide),
                upper.finiteMps()?.shiftedBy(origins[index])?.let(::ExactLpSide),
            ),
            origin = origins[index],
            integral = variable.integer,
            tag = index,
        )
    }.toMutableList()
    val matrix = List(variables.size) { ArrayList<ExactLpEntry>() }
    val rows = ArrayList<ExactLpRow>()
    val rhs = ArrayList<ExactLpNumber>()
    for (rowIndex in constraints.indices) {
        val constraint = constraints[rowIndex]
        val coefficients = source.constraintCoefficients[rowIndex]
        val (lower, upper) = source.constraintBounds[rowIndex]
        val finiteLower = lower.finiteMps()
        val finiteUpper = upper.finiteMps()
        val premises = constraint.indicator?.let { indicator ->
            val trigger = ExactLpNumber.of(if (indicator.whenOne) 1L else 0L)
            ExactLpPremises(
                listOf(
                    ExactLpPremise(indicator.column, upper = false, threshold = trigger),
                    ExactLpPremise(indicator.column, upper = true, threshold = trigger),
                ),
            )
        }
        fun addRow(bound: MpsSourceNumber, negate: Boolean, equality: Boolean) {
            var shifted = bound.fraction
            var recentered = false
            val terms = LinkedHashMap<Int, ExactLpNumber>()
            for (entry in constraint.indices.indices) {
                val column = constraint.indices[entry]
                val coefficient = coefficients[entry].fraction
                val contribution = coefficient * origins[column].value
                shifted -= contribution
                recentered = recentered || !contribution.isZero
                val signed = if (negate) coefficient.negated() else coefficient
                if (!signed.isZero) {
                    val number = coefficients[entry].toExactLpNumber(negate)
                    val previous = terms[column]
                    val combined = if (previous == null) number else ExactLpNumber.of(previous.value + number.value)
                    if (combined.value.isZero) terms.remove(column) else terms[column] = combined
                }
            }
            if (negate) shifted = shifted.negated()
            val nextRow = rows.size
            for ((column, number) in terms) matrix[column].add(ExactLpEntry(nextRow, number))
            rhs.add(if (recentered) ExactLpNumber.of(shifted) else bound.toExactLpNumber(negate))
            rows.add(ExactLpRow(global = premises == null, premises = premises))
            val slackIntegral = equality || (
                constraint.indices.indices.all { entry ->
                    variables[constraint.indices[entry]].integer && coefficients[entry].fraction.den.isOne()
                } && bound.fraction.den.isOne()
                )
            columns.add(
                ExactLpColumn(
                    if (equality) {
                        ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))
                    } else {
                        ExactLpBounds(lower = ExactLpSide(zero))
                    },
                    integral = slackIntegral,
                ),
            )
        }
        if (finiteLower != null && finiteUpper != null && finiteLower.fraction == finiteUpper.fraction) {
            addRow(finiteLower, negate = false, equality = true)
        } else {
            finiteUpper?.let { addRow(it, negate = false, equality = false) }
            finiteLower?.let { addRow(it, negate = true, equality = false) }
        }
    }
    val sourceCosts = MutableList(variables.size) { zero }
    objective.indices.forEachIndexed { entry, column ->
        val number = source.objectiveCoefficients[entry]
            .toExactLpNumber(negated = sense == ObjectiveSense.MAXIMIZE)
        sourceCosts[column] = if (sourceCosts[column].value.isZero) {
            number
        } else {
            ExactLpNumber.of(sourceCosts[column].value + number.value)
        }
    }
    repeat(rows.size) { sourceCosts.add(zero) }
    var shiftedConstant = source.objectiveConstant.fraction
    var objectiveRecentered = false
    for (column in variables.indices) {
        val sourceCost = sourceCosts[column].value
        val signedSourceCost = if (sense == ObjectiveSense.MAXIMIZE) sourceCost.negated() else sourceCost
        val contribution = signedSourceCost * origins[column].value
        shiftedConstant += contribution
        objectiveRecentered = objectiveRecentered || !contribution.isZero
    }
    if (sense == ObjectiveSense.MAXIMIZE) shiftedConstant = shiftedConstant.negated()
    val objectiveConstant = if (objectiveRecentered) {
        ExactLpNumber.of(shiftedConstant)
    } else {
        source.objectiveConstant.toExactLpNumber(negated = sense == ObjectiveSense.MAXIMIZE)
    }
    return ExactLpModel(
        matrix,
        rhs,
        columns,
        rows,
        ExactLpObjective(
            sourceCosts,
            objectiveConstant,
            sense = if (sense == ObjectiveSense.MAXIMIZE) Sense.MAXIMIZE else Sense.MINIMIZE,
        ),
    )
}

private fun MpsModel.exactAdapterSnapshot(): MpsModel = MpsModel(
    name,
    sense,
    MpsObjective(objective.name, objective.indices.copyOf(), objective.coeffs.copyOf(), objective.constant),
    variables.toList(),
    constraints.map { row ->
        MpsConstraint(
            row.name,
            row.indices.copyOf(),
            row.coeffs.copyOf(),
            row.lower,
            row.upper,
            row.indicator?.copy(),
        )
    },
).withSourceNumbers(sourceNumbers())

private val MPS_INFINITY_EXACT = BigFraction.of(
    BigInteger.parseString("100000000000000000000", 10),
    BigInteger.ONE,
)

private fun MpsSourceNumber?.finiteMps(): MpsSourceNumber? = this?.takeIf {
    it.fraction > MPS_INFINITY_EXACT.negated() && it.fraction < MPS_INFINITY_EXACT
}

private fun MpsSourceNumber.shiftedBy(origin: ExactLpNumber): ExactLpNumber =
    if (origin.value.isZero) toExactLpNumber() else ExactLpNumber.of(fraction - origin.value)

private fun com.ionspin.kotlin.bignum.integer.BigInteger.isOne(): Boolean =
    this == com.ionspin.kotlin.bignum.integer.BigInteger.ONE

/** Emit a purely-integer row over integer-variable ids, multiplied onto the scale that carries its
 *  coefficients and bounds onto whole numbers. */
private fun emitIntRow(
    factors: MutableList<Factor>,
    c: MpsConstraint,
    variables: List<MpsVar>,
    intVarOf: IntArray,
): Boolean {
    val vars = IntArray(c.indices.size) { intVarOf[c.indices[it]] }
    val scale = c.integerRowScale()
    val coeffs = LongArray(c.indices.size) { scale.scale(c.coeffs[it]) }
    return emitInnerRow(
        c,
        scale,
        variables,
        post = { op, bound -> factors.add(Linear(coeffs, vars, op, bound)) },
        postImpossible = { factors.add(Linear(EmptyLongArray, EmptyIntArray, LinearOp.LE, -1L)) },
    )
}

/**
 * The scale carrying a purely-integer row onto whole numbers.
 *
 * A row whose smallest term rounds to zero at every usable scale is emitted as an inner approximation:
 * every finite side is tightened enough that a satisfying retained row also satisfies the source row.
 */
private fun MpsConstraint.integerRowScale(): RowScale {
    val builder = RowScaleBuilder()
    for (coeff in coeffs) builder.observe(coeff)
    rowBound(lower)?.let { builder.observe(it) }
    rowBound(upper)?.let { builder.observe(it) }
    return builder.resolve()
}

/**
 * Emits the inner side of an underflowing integer row. `R` is the retained integral row and `S` the
 * source row in scaled units. With `|R - S| <= error`, `R <= floor(bound - error)` and
 * `R >= ceil(bound + error)` imply their respective source sides. A missing finite bound makes the
 * inner condition empty instead of making an unsound claim about a candidate.
 */
private fun emitInnerRow(
    c: MpsConstraint,
    scale: RowScale,
    variables: List<MpsVar>,
    post: (LinearOp, Long) -> Unit,
    postImpossible: () -> Unit,
): Boolean {
    if (scale !is RowScale.Unrepresentable) {
        emitRow(rowBound(c.lower), rowBound(c.upper), scale::scale, post)
        return false
    }
    val error = c.innerApproximationError(variables, scale)
    if (error == null) {
        postImpossible()
        return true
    }
    c.upper?.let { upper ->
        val bound = floor(upper * scale.multiplier - error)
        if (bound < Long.MIN_VALUE) {
            postImpossible()
        } else {
            post(LinearOp.LE, bound.toLong())
        }
    }
    c.lower?.let { lower ->
        val bound = ceil(lower * scale.multiplier + error)
        if (bound > Long.MAX_VALUE) {
            postImpossible()
        } else {
            post(LinearOp.GE, bound.toLong())
        }
    }
    return true
}

/** Error bound in the retained row's integral units, or null when an underflowing term is unbounded. */
private fun MpsConstraint.innerApproximationError(variables: List<MpsVar>, scale: RowScale): Double? {
    var error = 0.0
    indices.forEachIndexed { k, index ->
        val delta = abs(scale.scale(coeffs[k]).toDouble() - coeffs[k] * scale.multiplier)
        if (delta == 0.0) return@forEachIndexed
        val variable = variables[index]
        val lower = intLowerOrNull(variable.lower) ?: return null
        val upper = intUpperOrNull(variable.upper) ?: return null
        error = (error + (delta * maxOf(abs(lower.toDouble()), abs(upper.toDouble()))).nextUp()).nextUp()
    }
    return error
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
    scale: RowScale?,
): RealRowParts {
    val intVars = ArrayList<Int>()
    val intCoeffs = ArrayList<Double>()
    val realVars = ArrayList<Int>()
    val realCoeffs = ArrayList<Double>()
    for (k in c.indices.indices) {
        val idx = c.indices[k]
        val coeff = scale.restate(c.coeffs[k])
        if (isFloat[idx]) {
            realVars.add(realVarOf[idx])
            realCoeffs.add(coeff)
        } else {
            intVars.add(intVarOf[idx])
            intCoeffs.add(coeff)
        }
    }
    return RealRowParts(
        intVars.toIntArray(),
        intCoeffs.toDoubleArray(),
        realVars.toIntArray(),
        realCoeffs.toDoubleArray(),
    )
}

/**
 * The multiplier restating a row that touches a continuous column in whole numbers, or `null` when the
 * row's values carry no recoverable source decimal.
 *
 * The LP reads these coefficients exactly, so a field written `0.9` and kept as its binary double states
 * a row the file does not: `0.9·x = 54` and `x = 60` are together infeasible over that double, and the
 * relaxation then certifies a feasible model refuted. Multiplying the row through by the denominator its
 * own decimals name restates it — `9·x = 540` — rather than approximating it.
 *
 * Only [RowScale.Exact] is used. A rounded multiplier would move coefficients the double already holds,
 * trading a faithful row for a whole-numbered one, and the exactness this fixes is the whole point.
 */
private fun MpsConstraint.realRowScale(): RowScale? {
    val builder = RowScaleBuilder()
    for (coeff in coeffs) builder.observe(coeff)
    rowBound(lower)?.let { builder.observe(it) }
    rowBound(upper)?.let { builder.observe(it) }
    return builder.resolve() as? RowScale.Exact
}

/** [value] on this scale as a whole number, or unchanged when there is no scale to restate it on. */
private fun RowScale?.restate(value: Double): Double = if (this == null) value else scale(value).toDouble()

/** Emit a row touching a continuous variable as a real ([Double]-coefficient) LP-only [Linear] row over
 *  its integer and real parts. */
private fun emitRealRow(
    factors: MutableList<Factor>,
    c: MpsConstraint,
    isFloat: BooleanArray,
    intVarOf: IntArray,
    realVarOf: IntArray,
) {
    val scale = c.realRowScale()
    val p = splitRealRow(c, isFloat, intVarOf, realVarOf, scale)
    emitRow(rowBound(c.lower), rowBound(c.upper), scale::restate) { op, bound ->
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
            throw MpsLoweringException(
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
    variables: List<MpsVar>,
    intVarOf: IntArray,
    guard: Int,
    guards: IndicatorGuards,
): Boolean {
    val vars = IntArray(c.indices.size) { intVarOf[c.indices[it]] }
    val scale = c.integerRowScale()
    val coeffs = LongArray(c.indices.size) { scale.scale(c.coeffs[it]) }
    fun post(op: LinearOp, bound: Long, rowCoeffs: LongArray = coeffs, rowVars: IntArray = vars) {
        val cond = guards.newBool()
        factors.add(ReifiedLinear(cond, rowCoeffs, rowVars, op, bound))
        postGuardImplies(factors, guard, cond)
    }
    return emitInnerRow(
        c,
        scale,
        variables,
        post = { op, bound -> post(op, bound) },
        postImpossible = { post(LinearOp.LE, -1L, EmptyLongArray, EmptyIntArray) },
    )
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
    val scale = c.realRowScale()
    val p = splitRealRow(c, isFloat, intVarOf, realVarOf, scale)
    fun post(op: LinearOp, bound: Double) {
        val cond = guards.newBool()
        factors.add(ReifiedRealLinear(cond, p.intVars, p.intCoeffs, p.realVars, p.realCoeffs, op, bound))
        postGuardImplies(factors, guard, cond)
    }
    rowBound(c.upper)?.let { post(LinearOp.LE, scale.restate(it)) }
    rowBound(c.lower)?.let { post(LinearOp.GE, scale.restate(it)) }
}

/** The scale carrying the objective's integer-column coefficients and its constant onto whole numbers.
 *  A term on a float column keeps its double and so places no demand on the scale; an underflowing
 *  integer term is handled by [objectiveApproximationError]. */
private fun MpsModel.objectiveRowScale(isFloat: BooleanArray): RowScale {
    val builder = RowScaleBuilder()
    objective.indices.forEachIndexed { k, idx -> if (!isFloat[idx]) builder.observe(objective.coeffs[k]) }
    builder.observe(objective.constant)
    return builder.resolve()
}

/**
 * Bounds the objective difference introduced when [scale] drops a coefficient. Every dropped term must
 * have a finite integer-column bound; otherwise no finite statement about the source objective exists.
 */
private fun MpsModel.objectiveApproximationError(scale: RowScale, isFloat: BooleanArray): Double? {
    if (scale !is RowScale.Unrepresentable) return null
    var error = abs(scale.scale(objective.constant).toDouble() / scale.multiplier - objective.constant)
    objective.indices.forEachIndexed { k, index ->
        if (isFloat[index]) return@forEachIndexed
        val source = objective.coeffs[k]
        val delta = abs(scale.scale(source).toDouble() / scale.multiplier - source)
        if (delta == 0.0) return@forEachIndexed
        val variable = variables[index]
        val lower = intLowerOrNull(variable.lower)
            ?: mpsLoweringError(
                "objective drops a term on unbounded column '${variable.name}', so its error is unbounded",
            )
        val upper = intUpperOrNull(variable.upper)
            ?: mpsLoweringError(
                "objective drops a term on unbounded column '${variable.name}', so its error is unbounded",
            )
        error += delta * maxOf(abs(lower.toDouble()), abs(upper.toDouble()))
    }
    if (!error.isFinite()) mpsLoweringError("objective approximation error exceeds a finite double")
    return error
}

private fun MpsModel.buildObjective(
    isFloat: BooleanArray,
    intVarOf: IntArray,
    realVarOf: IntArray,
    numBool: Int,
    numInt: Int,
    numReal: Int,
    scale: RowScale,
): LinearObjectiveSpec {
    val intCoefficients = LongArray(numInt)
    val realCoefficients = DoubleArray(numReal)
    objective.indices.forEachIndexed { k, idx ->
        if (isFloat[idx]) {
            realCoefficients[realVarOf[idx]] = objective.coeffs[k] * scale.multiplier.toDouble()
        } else {
            intCoefficients[intVarOf[idx]] = scale.scale(objective.coeffs[k])
        }
    }
    return LinearObjectiveSpec(
        // Indicator guards carry no objective weight, but the weight vector may not outrun `numBoolVars`.
        boolWeights = if (numBool == 0) EmptyLongArray else LongArray(numBool),
        intCoefficients = intCoefficients,
        constant = scale.scale(objective.constant),
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

/** A row bound, with the `1e30` marker read as the open side it stands for. */
private fun rowBound(value: Double?): Double? =
    if (value == null || value >= MPS_INFINITY || value <= -MPS_INFINITY) null else value

/** A declared lower bound on an integer column, tightened to the first integer the column may take:
 *  rounding a fractional bound to the nearest integer would admit a value the source excludes. */
private fun intLowerOrNull(value: Double?): Long? =
    if (value == null || value >= MPS_INFINITY || value <= -MPS_INFINITY) null else ceil(value).toLong()

/** A declared upper bound on an integer column, tightened to the last integer the column may take. */
private fun intUpperOrNull(value: Double?): Long? =
    if (value == null || value >= MPS_INFINITY || value <= -MPS_INFINITY) null else floor(value).toLong()

private fun openLower(value: Double?): Double =
    if (value == null || value <= -MPS_INFINITY) Double.NEGATIVE_INFINITY else value

private fun openUpper(value: Double?): Double =
    if (value == null || value >= MPS_INFINITY) Double.POSITIVE_INFINITY else value
