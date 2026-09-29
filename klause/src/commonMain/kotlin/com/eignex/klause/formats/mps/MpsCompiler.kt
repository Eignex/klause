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
import com.eignex.klause.util.EmptyLongArray
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ulp

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
    /** Power of ten multiplying the retained objective, set by integer terms when present and by real
     *  terms for a pure-real objective. */
    val objectiveScale: Long,
    /** Count of the source's continuous columns (zero for a pure-integer instance). */
    val floatColumns: Int,
) {
    private var exactLpSource: (() -> ExactLpModel)? = null
    private var exactLpCache: ExactLpModel? = null
    private var sourceModel: MpsModel? = null
    private var sourceMismatch: String? = null
    private var toleranceMismatch: String? = null

    /** Whether the lowered rows, bounds, and objective have the original source values. */
    val sourceExact: Boolean get() = sourceModel != null && sourceMismatch == null

    /** The first source value the lowered model does not retain, if any. */
    val sourceDifference: String?
        get() = if (sourceModel == null) "compiled model lacks source mapping" else sourceMismatch

    /**
     * The first lowered value that differs from its binary64 source value by more than a few ulp — the difference
     * that matters under tolerance semantics, where decimal restatement and scaled-cost rounding do not count.
     */
    val toleranceDifference: String?
        get() = if (sourceModel == null) "compiled model lacks source mapping" else toleranceMismatch

    /**
     * Whether a point satisfies every source row and column bound within tolerance: at most
     * [MPS_TOLERANCE]·max(1, |bound|, Σ|aᵢxᵢ|) per row and [MPS_TOLERANCE]·max(1, |bound|) per bound, in binary64
     * source values. [ints] and [reals] are indexed by the lowered model's integer and real variable ids; an
     * indicated row is checked only when its indicator holds.
     */
    fun withinTolerance(ints: LongArray, reals: DoubleArray): Boolean {
        if (sourceModel == null) return false
        val source = toleranceSource
        val values = DoubleArray(columns.size) { index ->
            val column = columns[index]
            val value = if (column.real) reals.getOrNull(column.id) else ints.getOrNull(column.id)?.toDouble()
            value ?: return false
        }
        for (index in values.indices) {
            val value = values[index]
            if (!value.isFinite()) return false
            val lower = source.lower[index]
            val upper = source.upper[index]
            if (value < lower - MPS_TOLERANCE * maxOf(1.0, abs(lower))) return false
            if (value > upper + MPS_TOLERANCE * maxOf(1.0, abs(upper))) return false
        }
        for (row in source.rows) {
            if (row.indicatorColumn >= 0 && values[row.indicatorColumn] != row.indicatorValue) continue
            var activity = 0.0
            var magnitude = 0.0
            for (entry in row.columns.indices) {
                val term = row.coefficients[entry] * values[row.columns[entry]]
                activity += term
                magnitude += abs(term)
            }
            if (activity < row.lower - MPS_TOLERANCE * maxOf(1.0, abs(row.lower), magnitude)) return false
            if (activity > row.upper + MPS_TOLERANCE * maxOf(1.0, abs(row.upper), magnitude)) return false
        }
        return true
    }

    // Binary64 source values, read once: tolerance checks run from every portfolio arm. Read only once the source is
    // attached, so the snapshot never caches its absence.
    private val toleranceSource: ToleranceSource by lazy {
        val source = checkNotNull(sourceModel) { "MPS source model is unavailable" }
        val numbers = source.sourceNumbers()
        val bounds = numbers.variableBounds
        ToleranceSource(
            lower = DoubleArray(columns.size) { bounds[it].first.finiteMps()?.double ?: Double.NEGATIVE_INFINITY },
            upper = DoubleArray(columns.size) { bounds[it].second.finiteMps()?.double ?: Double.POSITIVE_INFINITY },
            rows = source.constraints.mapIndexed { rowIndex, row ->
                val (lower, upper) = numbers.constraintBounds[rowIndex]
                ToleranceRow(
                    row.indices.copyOf(),
                    DoubleArray(row.indices.size) { numbers.constraintCoefficients[rowIndex][it].double },
                    lower.finiteMps()?.double ?: Double.NEGATIVE_INFINITY,
                    upper.finiteMps()?.double ?: Double.POSITIVE_INFINITY,
                    row.indicator?.column ?: -1,
                    if (row.indicator?.whenOne == true) 1.0 else 0.0,
                )
            },
        )
    }

    internal val exactLpModel: ExactLpModel?
        get() {
            exactLpCache?.let { return it }
            return exactLpSource?.invoke()?.also { exactLpCache = it }
        }

    internal fun withExactLpModel(source: () -> ExactLpModel): MpsCompiled = apply {
        check(exactLpSource == null) { "exact MPS LP model is already attached" }
        exactLpSource = source
    }

    internal fun withSourceModel(source: MpsModel, mismatch: String?, toleranceMismatch: String?): MpsCompiled = apply {
        check(sourceModel == null) { "MPS source model is already attached" }
        sourceModel = source
        sourceMismatch = mismatch
        this.toleranceMismatch = toleranceMismatch
    }

    /** Check a solved point against the original decimal MPS rows, bounds, and objective. */
    fun sourceWitness(ints: LongArray, reals: List<BigFraction>?): MpsSourceWitness {
        val source = checkNotNull(sourceModel) { "MPS source model is unavailable" }
        val numbers = source.sourceNumbers()
        val values = columns.map { column -> sourceColumnValue(column, ints, reals) }
        source.variables.forEachIndexed { index, variable ->
            val value = values[index]
            val (lower, upper) = numbers.variableBounds[index]
            if (lower.finiteMps()?.fraction?.let { value < it } == true ||
                upper.finiteMps()?.fraction?.let { value > it } == true
            ) {
                throw MpsLoweringException("source witness violates bound on '${variable.name}'")
            }
        }
        source.constraints.forEachIndexed { rowIndex, row ->
            val indicator = row.indicator
            val trigger = if (indicator?.whenOne == true) 1L else 0L
            if (indicator != null && values[indicator.column] != BigFraction.ofLong(trigger)) {
                return@forEachIndexed
            }
            val activity = row.indices.indices.fold(BigFraction.ZERO) { sum, entry ->
                sum + numbers.constraintCoefficients[rowIndex][entry].fraction * values[row.indices[entry]]
            }
            val (lower, upper) = numbers.constraintBounds[rowIndex]
            if (lower.finiteMps()?.fraction?.let { activity < it } == true ||
                upper.finiteMps()?.fraction?.let { activity > it } == true
            ) {
                throw MpsLoweringException("source witness violates row '${row.name}'")
            }
        }
        val objective = source.objective.indices.indices.fold(numbers.objectiveConstant.fraction) { sum, entry ->
            sum + numbers.objectiveCoefficients[entry].fraction * values[source.objective.indices[entry]]
        }
        return MpsSourceWitness(values, objective)
    }

    /**
     * The exact source objective at a binary64 point, in source units and sense: the decimal coefficients times the
     * doubles themselves, so a printed float witness reports its own objective free of summation error. Null when a
     * value is missing or not finite.
     */
    fun sourceObjective(ints: LongArray, reals: DoubleArray): BigFraction? {
        val source = checkNotNull(sourceModel) { "MPS source model is unavailable" }
        val numbers = source.sourceNumbers()
        return source.objective.indices.indices.fold(numbers.objectiveConstant.fraction) { sum, entry ->
            val column = columns[source.objective.indices[entry]]
            val value = if (column.real) {
                reals.getOrNull(column.id)?.let(BigFraction::ofDouble)
            } else {
                ints.getOrNull(column.id)?.let(BigFraction::ofLong)
            }
            sum + numbers.objectiveCoefficients[entry].fraction * (value ?: return null)
        }
    }

    fun copy(
        model: Problem = this.model,
        objective: LinearObjectiveSpec? = this.objective,
        maximize: Boolean = this.maximize,
        columns: List<MpsColumn> = this.columns,
        objectiveScale: Long = this.objectiveScale,
        floatColumns: Int = this.floatColumns,
    ): MpsCompiled {
        val result = MpsCompiled(model, objective, maximize, columns, objectiveScale, floatColumns)
        if (model === this.model && objective === this.objective && columns === this.columns &&
            maximize == this.maximize && objectiveScale == this.objectiveScale && floatColumns == this.floatColumns
        ) {
            result.exactLpSource = exactLpSource
            result.exactLpCache = exactLpCache
            result.sourceModel = sourceModel
            result.sourceMismatch = sourceMismatch
            result.toleranceMismatch = toleranceMismatch
        }
        return result
    }

    operator fun component1(): Problem = model
    operator fun component2(): LinearObjectiveSpec? = objective
    operator fun component3(): Boolean = maximize
    operator fun component4(): List<MpsColumn> = columns
    operator fun component5(): Long = objectiveScale
    operator fun component6(): Int = floatColumns

    override fun equals(other: Any?): Boolean = other is MpsCompiled &&
        model == other.model && objective == other.objective && maximize == other.maximize &&
        columns == other.columns && objectiveScale == other.objectiveScale && floatColumns == other.floatColumns

    override fun hashCode(): Int {
        var result = model.hashCode()
        result = 31 * result + (objective?.hashCode() ?: 0)
        result = 31 * result + maximize.hashCode()
        result = 31 * result + columns.hashCode()
        result = 31 * result + objectiveScale.hashCode()
        return 31 * result + floatColumns
    }

    override fun toString(): String = "MpsCompiled(model=$model, objective=$objective, maximize=$maximize, " +
        "columns=$columns, objectiveScale=$objectiveScale, floatColumns=$floatColumns)"
}

private fun sourceColumnValue(column: MpsColumn, ints: LongArray, reals: List<BigFraction>?): BigFraction =
    if (column.real) {
        reals?.getOrNull(column.id)
            ?: throw MpsLoweringException("continuous column '${column.name}' lacks an exact certified value")
    } else {
        ints.getOrNull(column.id)?.let(BigFraction::ofLong)
            ?: throw MpsLoweringException("integer column '${column.name}' has no value")
    }

private class ToleranceSource(val lower: DoubleArray, val upper: DoubleArray, val rows: List<ToleranceRow>)

private class ToleranceRow(
    val columns: IntArray,
    val coefficients: DoubleArray,
    val lower: Double,
    val upper: Double,
    val indicatorColumn: Int,
    val indicatorValue: Double,
)

/** A point checked against the original MPS decimal authority. */
data class MpsSourceWitness(
    /** Values in source column order. */
    val values: List<BigFraction>,
    /** Objective in source units and sense. */
    val objective: BigFraction,
)

/** Primal feasibility tolerance of MPS results under tolerance semantics, the HiGHS default. */
const val MPS_TOLERANCE: Double = 1e-7

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
 *    A row no power of ten restates exactly on [Long] is rebuilt from its exact source numbers, over
 *    [BigInteger] coefficients when they outgrow [Long].
 *  - an objective too wide for one power of ten is restated as an auxiliary continuous column `z` with
 *    the real row `z = Σ cᵢxᵢ + constant`, and `z` is optimized; [MpsCompiled.columns] omits `z`.
 */
fun MpsModel.toProblem(): MpsCompiled {
    val exactInput = exactAdapterSnapshot()
    val sourceNumbers = exactInput.sourceNumbers()
    val isFloat = BooleanArray(variables.size) { !variables[it].integer }
    val intVarOf = IntArray(variables.size) { -1 }
    val realVarOf = IntArray(variables.size) { -1 }
    var numInt = 0
    var numReal = 0
    for (i in variables.indices) if (isFloat[i]) realVarOf[i] = numReal++ else intVarOf[i] = numInt++

    val objRowScale = objectiveRowScale(isFloat)
    val objectiveRow = if (objective.indices.isNotEmpty() && objRowScale is RowScale.Unrepresentable) {
        objectiveColumnRow()
    } else {
        null
    }
    val objectiveColumn = if (objectiveRow == null) -1 else numReal
    val numModelReal = if (objectiveRow == null) numReal else numReal + 1

    // Real-variable bounds use ±∞. Integer source sides remain genuinely open when MPS omits them.
    val realLower = DoubleArray(numModelReal)
    val realUpper = DoubleArray(numModelReal)
    if (objectiveRow != null) {
        realLower[objectiveColumn] = Double.NEGATIVE_INFINITY
        realUpper[objectiveColumn] = Double.POSITIVE_INFINITY
    }
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
    for ((rowIndex, c) in constraints.withIndex()) {
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
                emitIntRow(factors, c, intVarOf) { exactIntegerRow(c, rowIndex, sourceNumbers, intVarOf) }
            }
        } else {
            val guard = guards.guardFor(indicator, c.name)
            if (touchesFloat) {
                emitIndicatedRealRow(factors, c, isFloat, intVarOf, realVarOf, guard, guards)
            } else {
                emitIndicatedIntRow(factors, c, intVarOf, guard, guards) {
                    exactIntegerRow(c, rowIndex, sourceNumbers, intVarOf)
                }
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

    if (objectiveRow != null) {
        emitRealRow(
            factors,
            objectiveRow,
            isFloat.copyOf(variables.size + 1).also { it[variables.size] = true },
            intVarOf.copyOf(variables.size + 1).also { it[variables.size] = -1 },
            realVarOf.copyOf(variables.size + 1).also { it[variables.size] = objectiveColumn },
        )
    }
    val objective = when {
        objective.indices.isEmpty() -> null
        objectiveRow != null -> columnObjective(guards.numBool, numInt, numModelReal, objectiveColumn)
        else -> buildObjective(isFloat, intVarOf, realVarOf, guards.numBool, numInt, numReal, objRowScale)
    }

    val model = Problem(
        numBoolVars = guards.numBool,
        intBounds = IntBounds.fromModelBounds(lower, upper, openLoBits, openHiBits),
        factors = factors.toTypedArray(),
        numRealVars = numModelReal,
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
        if (objectiveRow == null) objRowScale.multiplier else 1L,
        numReal,
    ).withExactLpModel { exactInput.toExactLpModel() }
        .withSourceModel(
            exactInput,
            exactInput.sourceMismatch(isFloat, objRowScale, objectiveRow),
            exactInput.toleranceMismatch(isFloat, objRowScale, objectiveRow),
        )
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

/**
 * Emit a purely-integer row over integer-variable ids, multiplied onto the scale that carries its coefficients and
 * bounds onto whole numbers. A row no power of ten restates exactly is rebuilt from its [exact] source numbers.
 */
private inline fun emitIntRow(
    factors: MutableList<Factor>,
    c: MpsConstraint,
    intVarOf: IntArray,
    exact: () -> ExactIntegerRow,
) {
    val scale = c.integerRowScale()
    if (scale !is RowScale.Exact) {
        val row = exact()
        for (side in row.sides) {
            if (row.vars.isEmpty()) {
                if (!side.holdsWithoutTerms()) {
                    throw MpsLoweringException("constraint row '${c.name}' cancels to no variables but is infeasible")
                }
                continue
            }
            val narrow = row.narrowCoefficients
            factors.add(
                if (narrow != null && side.bound.fitsLong()) {
                    Linear(narrow, row.vars, side.op, side.bound.longValue())
                } else {
                    Linear(row.vars, row.coefficients, side.op, side.bound)
                },
            )
        }
        return
    }
    val vars = IntArray(c.indices.size) { intVarOf[c.indices[it]] }
    val coeffs = LongArray(c.indices.size) { scale.scale(c.coeffs[it]) }
    emitRow(rowBound(c.lower), rowBound(c.upper), scale::scale) { op, bound ->
        factors.add(Linear(coeffs, vars, op, bound))
    }
}

/**
 * The scale carrying a purely-integer row onto whole numbers. Only a [RowScale.Exact] scale is used; a row whose
 * values would round at every usable power of ten is rebuilt through [exactIntegerRow] instead.
 */
private fun MpsConstraint.integerRowScale(): RowScale {
    val builder = RowScaleBuilder()
    for (coeff in coeffs) builder.observe(coeff)
    rowBound(lower)?.let { builder.observe(it) }
    rowBound(upper)?.let { builder.observe(it) }
    return builder.resolve()
}

/** One side `Σ coefficients·vars ⟨op⟩ bound` of an [ExactIntegerRow]. */
private class ExactIntegerSide(val op: LinearOp, val bound: BigInteger) {
    fun holdsWithoutTerms(): Boolean = when (op) {
        LinearOp.LE -> bound.signum() >= 0
        LinearOp.GE -> bound.signum() <= 0
        LinearOp.EQ -> bound.isZero()
        LinearOp.NE -> !bound.isZero()
    }
}

/** A purely-integer source row over distinct integer-variable ids with coprime whole coefficients. */
private class ExactIntegerRow(
    val vars: IntArray,
    val coefficients: Array<BigInteger>,
    val sides: List<ExactIntegerSide>,
) {
    /** The coefficients as [Long], or null when one does not fit. */
    val narrowCoefficients: LongArray? =
        if (coefficients.all { it.fitsLong() }) LongArray(coefficients.size) { coefficients[it].longValue() } else null
}

/**
 * The integer row [c] restated exactly: its source coefficients multiplied onto their least common denominator and
 * divided by their greatest common divisor. Over integer columns the left side is then whole, so a fractional upper
 * bound floors and a fractional lower bound ceils without changing the row's solutions; a fractional equality becomes
 * the two contradictory sides it implies.
 */
private fun exactIntegerRow(
    c: MpsConstraint,
    rowIndex: Int,
    source: MpsSourceNumbers,
    intVarOf: IntArray,
): ExactIntegerRow {
    val numbers = source.constraintCoefficients[rowIndex]
    val sums = LinkedHashMap<Int, BigFraction>()
    for (entry in c.indices.indices) {
        val variable = intVarOf[c.indices[entry]]
        sums[variable] = (sums[variable] ?: BigFraction.ZERO) + numbers[entry].fraction
    }
    val terms = sums.entries.filter { !it.value.isZero }
    var denominator = BigInteger.ONE
    for ((_, value) in terms) denominator = denominator / denominator.gcd(value.den) * value.den
    val scaled = terms.map { (_, value) -> value.num * (denominator / value.den) }
    var divisor: BigInteger? = null
    for (value in scaled) divisor = divisor?.gcd(value.abs()) ?: value.abs()
    val multiplier = BigFraction.of(denominator, divisor ?: BigInteger.ONE)
    val (lower, upper) = source.constraintBounds[rowIndex]
    val low = lower.finiteMps()?.fraction?.times(multiplier)
    val high = upper.finiteMps()?.fraction?.times(multiplier)
    val sides = buildList {
        if (low != null && low == high && low.den.isOne()) {
            add(ExactIntegerSide(LinearOp.EQ, low.num))
        } else {
            high?.let { add(ExactIntegerSide(LinearOp.LE, it.floor())) }
            low?.let { add(ExactIntegerSide(LinearOp.GE, it.ceil())) }
        }
    }
    return ExactIntegerRow(
        IntArray(terms.size) { terms[it].key },
        Array(scaled.size) { scaled[it] / (divisor ?: BigInteger.ONE) },
        sides,
    )
}

private fun BigFraction.floor(): BigInteger {
    val quotient = num / den
    return if (num.signum() < 0 && quotient * den != num) quotient - BigInteger.ONE else quotient
}

private fun BigFraction.ceil(): BigInteger = -negated().floor()

private val LONG_MIN = BigInteger.fromLong(Long.MIN_VALUE)
private val LONG_MAX = BigInteger.fromLong(Long.MAX_VALUE)

private fun BigInteger.fitsLong(): Boolean = this in LONG_MIN..LONG_MAX

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

private fun MpsModel.sourceMismatch(
    isFloat: BooleanArray,
    objectiveScale: RowScale,
    objectiveRow: MpsConstraint?,
): String? {
    val source = sourceNumbers()
    for (index in variables.indices) {
        val variable = variables[index]
        val (lower, upper) = source.variableBounds[index]
        val valid = if (isFloat[index]) {
            sourceValueMatches(lower, openLower(variable.lower)) &&
                sourceValueMatches(upper, openUpper(variable.upper))
        } else {
            sourceIntegerLowerMatches(lower, variable.lower) &&
                sourceIntegerUpperMatches(upper, variable.upper)
        }
        if (!valid) return "column bound '${variable.name}'"
    }
    for (rowIndex in constraints.indices) {
        val row = constraints[rowIndex]
        if (row.restatedFromSource(isFloat)) continue
        val scale = if (row.indices.any { isFloat[it] }) row.realRowScale() else row.integerRowScale()
        if (scale !is RowScale.Exact) return "row '${row.name}' scale"
        val coefficients = source.constraintCoefficients[rowIndex]
        for (entry in row.indices.indices) {
            if (!scaledValueMatches(coefficients[entry], row.coeffs[entry], scale)) {
                return "row '${row.name}' coefficient"
            }
        }
        val (lower, upper) = source.constraintBounds[rowIndex]
        if (!scaledBoundMatches(lower, rowBound(row.lower), scale) ||
            !scaledBoundMatches(upper, rowBound(row.upper), scale)
        ) {
            return "row '${row.name}' bound"
        }
    }
    if (objectiveRow != null) {
        val scale = objectiveRow.realRowScale()
        fun retained(value: Double): BigFraction? = if (scale == null) {
            BigFraction.ofDouble(value)
        } else {
            BigFraction.of(BigInteger.fromLong(scale.scale(value)), BigInteger.fromLong(scale.multiplier))
        }
        for (entry in objective.indices.indices) {
            if (retained(objectiveRow.coeffs[entry]) != source.objectiveCoefficients[entry].fraction.negated()) {
                return "objective coefficient '${variables[objective.indices[entry]].name}'"
            }
        }
        val constant = objectiveRow.lower ?: return "objective constant"
        return if (retained(constant) == source.objectiveConstant.fraction) null else "objective constant"
    }
    if (objectiveScale !is RowScale.Exact) return "objective scale"
    val onlyRealTerms = objective.indices.all { isFloat[it] }
    for (entry in objective.indices.indices) {
        val index = objective.indices[entry]
        val retained = if (isFloat[index]) {
            BigFraction.ofDouble(objectiveScale.realObjectiveCoefficient(objective.coeffs[entry], onlyRealTerms))
        } else {
            BigFraction.ofLong(objectiveScale.scale(objective.coeffs[entry]))
        }
        if (retained != source.objectiveCoefficients[entry].fraction * BigFraction.ofLong(objectiveScale.multiplier)) {
            return "objective coefficient '${variables[objective.indices[entry]].name}'"
        }
    }
    if (!scaledValueMatches(source.objectiveConstant, objective.constant, objectiveScale)) {
        return "objective constant"
    }
    return null
}

private fun MpsModel.toleranceMismatch(
    isFloat: BooleanArray,
    objectiveScale: RowScale,
    objectiveRow: MpsConstraint?,
): String? {
    val source = sourceNumbers()
    for (index in variables.indices) {
        val variable = variables[index]
        val (lower, upper) = source.variableBounds[index]
        val valid = if (isFloat[index]) {
            nearlyEqual(openLower(variable.lower), lower.finiteMps()?.double ?: Double.NEGATIVE_INFINITY) &&
                nearlyEqual(openUpper(variable.upper), upper.finiteMps()?.double ?: Double.POSITIVE_INFINITY)
        } else {
            sourceIntegerLowerMatches(lower, variable.lower) && sourceIntegerUpperMatches(upper, variable.upper)
        }
        if (!valid) return "column bound '${variable.name}'"
    }
    for (rowIndex in constraints.indices) {
        val row = constraints[rowIndex]
        if (row.restatedFromSource(isFloat)) continue
        val scale: RowScale? = if (row.indices.any { isFloat[it] }) row.realRowScale() else row.integerRowScale()
        fun retained(value: Double): Double =
            if (scale == null) value else scale.scale(value).toDouble() / scale.multiplier
        val coefficients = source.constraintCoefficients[rowIndex]
        if (row.indices.indices.any { !nearlyEqual(retained(row.coeffs[it]), coefficients[it].double) }) {
            return "row '${row.name}' coefficient"
        }
        val (lower, upper) = source.constraintBounds[rowIndex]
        fun sideMatches(side: MpsSourceNumber?, value: Double?): Boolean {
            val exact = side.finiteMps() ?: return rowBound(value) == null
            val bound = rowBound(value) ?: return false
            return nearlyEqual(retained(bound), exact.double)
        }
        if (!sideMatches(lower, row.lower) || !sideMatches(upper, row.upper)) return "row '${row.name}' bound"
    }
    if (objectiveRow != null) {
        val scale = objectiveRow.realRowScale()
        fun retained(value: Double): Double =
            if (scale == null) value else scale.scale(value).toDouble() / scale.multiplier
        for (entry in objective.indices.indices) {
            if (!nearlyEqual(retained(objectiveRow.coeffs[entry]), -source.objectiveCoefficients[entry].double)) {
                return "objective coefficient '${variables[objective.indices[entry]].name}'"
            }
        }
        val constant = objectiveRow.lower ?: return "objective constant"
        return if (nearlyEqual(retained(constant), source.objectiveConstant.double)) null else "objective constant"
    }
    val onlyRealTerms = objective.indices.all { isFloat[it] }
    val multiplier = objectiveScale.multiplier.toDouble()
    for (entry in objective.indices.indices) {
        val index = objective.indices[entry]
        val value = objective.coeffs[entry]
        val retained = if (isFloat[index]) {
            objectiveScale.realObjectiveCoefficient(value, onlyRealTerms) / multiplier
        } else {
            objectiveScale.scale(value).toDouble() / multiplier
        }
        if (!nearlyEqual(retained, source.objectiveCoefficients[entry].double)) {
            return "objective coefficient '${variables[index].name}'"
        }
    }
    val constant = objectiveScale.scale(objective.constant).toDouble() / multiplier
    return if (nearlyEqual(constant, source.objectiveConstant.double)) null else "objective constant"
}

// An integer row no power of ten restates is lowered from its exact source numbers, so it restates the source.
private fun MpsConstraint.restatedFromSource(isFloat: BooleanArray): Boolean =
    indices.none { isFloat[it] } && integerRowScale() !is RowScale.Exact

// Equal within a few ulp of [source]: zero equals only zero and an infinity only itself.
private fun nearlyEqual(value: Double, source: Double): Boolean = when {
    value == source -> true
    source == 0.0 || !source.isFinite() || !value.isFinite() -> false
    else -> abs(value - source) <= NEARLY_EQUAL_ULPS * source.absoluteValue.ulp
}

private const val NEARLY_EQUAL_ULPS = 4.0

private fun scaledValueMatches(source: MpsSourceNumber, value: Double, scale: RowScale.Exact): Boolean =
    source.fraction * BigFraction.ofLong(scale.multiplier) ==
        BigFraction.ofLong(scale.scale(value))

private fun scaledBoundMatches(source: MpsSourceNumber?, value: Double?, scale: RowScale.Exact): Boolean {
    val exact = source.finiteMps() ?: return value == null
    return value != null && scaledValueMatches(exact, value, scale)
}

private fun sourceValueMatches(source: MpsSourceNumber?, value: Double): Boolean =
    source.finiteMps()?.fraction == BigFraction.ofDouble(value)

private fun sourceIntegerLowerMatches(source: MpsSourceNumber?, value: Double?): Boolean {
    val exact = source.finiteMps()?.fraction ?: return intLowerOrNull(value) == null
    if (exact < BigFraction.ofLong(Long.MIN_VALUE)) return false
    val lowered = intLowerOrNull(value) ?: return false
    return BigFraction.ofLong(lowered) >= exact &&
        (lowered == Long.MIN_VALUE || BigFraction.ofLong(lowered - 1L) < exact)
}

private fun sourceIntegerUpperMatches(source: MpsSourceNumber?, value: Double?): Boolean {
    val exact = source.finiteMps()?.fraction ?: return intUpperOrNull(value) == null
    if (exact > BigFraction.ofLong(Long.MAX_VALUE)) return false
    val lowered = intUpperOrNull(value) ?: return false
    return BigFraction.ofLong(lowered) <= exact &&
        (lowered == Long.MAX_VALUE || BigFraction.ofLong(lowered + 1L) > exact)
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
 *  `guard -> cond` clause, so the row is relaxed whenever the indicator column takes the other value. A row no
 *  power of ten restates exactly is rebuilt from its [exact] source numbers. */
private inline fun emitIndicatedIntRow(
    factors: MutableList<Factor>,
    c: MpsConstraint,
    intVarOf: IntArray,
    guard: Int,
    guards: IndicatorGuards,
    exact: () -> ExactIntegerRow,
) {
    val scale = c.integerRowScale()
    if (scale !is RowScale.Exact) {
        val row = exact()
        for (side in row.sides) {
            if (row.vars.isEmpty()) {
                // A side violated with no terms left forbids the trigger value.
                if (!side.holdsWithoutTerms()) factors.add(Clause(intArrayOf(Lit.negate(Lit.make(guard, true)))))
                continue
            }
            val cond = guards.newBool()
            val narrow = row.narrowCoefficients
            factors.add(
                if (narrow != null && side.bound.fitsLong()) {
                    ReifiedLinear(cond, narrow, row.vars, side.op, side.bound.longValue())
                } else {
                    ReifiedLinear(cond, row.vars, row.coefficients, side.op, side.bound)
                },
            )
            postGuardImplies(factors, guard, cond)
        }
        return
    }
    val vars = IntArray(c.indices.size) { intVarOf[c.indices[it]] }
    val coeffs = LongArray(c.indices.size) { scale.scale(c.coeffs[it]) }
    emitRow(rowBound(c.lower), rowBound(c.upper), scale::scale) { op, bound ->
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

/** The scale carrying integer objective coefficients onto whole numbers when possible. A pure-real
 *  objective uses its real terms to recover their source decimals. */
private fun MpsModel.objectiveRowScale(isFloat: BooleanArray): RowScale {
    val builder = RowScaleBuilder()
    val onlyRealTerms = objective.indices.all { isFloat[it] }
    objective.indices.forEachIndexed { k, index ->
        if (onlyRealTerms || !isFloat[index]) builder.observe(objective.coeffs[k])
    }
    builder.observe(objective.constant)
    return builder.resolve()
}

private fun RowScale.realObjectiveCoefficient(value: Double, onlyRealTerms: Boolean): Double =
    if (onlyRealTerms && this is RowScale.Exact) scale(value).toDouble() else value * multiplier.toDouble()

/**
 * The row `z − Σ cᵢxᵢ = constant` defining the auxiliary objective column `z`, which takes the column index just past
 * the source columns. It is a real row, so its coefficients keep their source values rather than a shared scale.
 */
private fun MpsModel.objectiveColumnRow(): MpsConstraint {
    val constant = rowBound(objective.constant)
        ?: throw MpsLoweringException("objective constant ${objective.constant} is at the MPS infinity marker")
    val terms = objective.coeffs.size
    return MpsConstraint(
        objective.name,
        objective.indices + variables.size,
        DoubleArray(terms + 1) { if (it < terms) -objective.coeffs[it] else 1.0 },
        constant,
        constant,
    )
}

/** The objective `min z` (or `max z`) over the auxiliary objective [column]. */
private fun columnObjective(numBool: Int, numInt: Int, numReal: Int, column: Int): LinearObjectiveSpec =
    LinearObjectiveSpec(
        boolWeights = if (numBool == 0) EmptyLongArray else LongArray(numBool),
        intCoefficients = LongArray(numInt),
        constant = 0L,
        realCoefficients = DoubleArray(numReal).also { it[column] = 1.0 },
    )

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
    val onlyRealTerms = objective.indices.all { isFloat[it] }
    objective.indices.forEachIndexed { k, idx ->
        if (isFloat[idx]) {
            realCoefficients[realVarOf[idx]] = scale.realObjectiveCoefficient(objective.coeffs[k], onlyRealTerms)
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
