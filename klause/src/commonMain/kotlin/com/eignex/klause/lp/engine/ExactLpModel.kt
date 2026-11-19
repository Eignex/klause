package com.eignex.klause.lp.engine

import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.addExact
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.mulExact
import com.eignex.util.numbers.toLongExact

// The exact model types deliberately do not implement ExactSimplexModel: the Long-valued simplex sees their
// values only through a checked projection.
internal class ExactLpNumber private constructor(
    val value: BigFraction,
    val ieeeBits: Long?,
    private val integer: Long?,
) {
    val approximation: Double by lazy(LazyThreadSafetyMode.PUBLICATION) {
        ieeeBits?.let { Double.fromBits(it) } ?: value.toDouble()
    }

    // An integer LpModel has no binary64 provenance, so a value carrying IEEE input bits has no form there.
    fun integerModelLong(): Long? = if (ieeeBits == null) exactLong() else null

    fun exactLong(): Long? = integer

    override fun equals(other: Any?): Boolean =
        other is ExactLpNumber && value == other.value && ieeeBits == other.ieeeBits
    override fun hashCode(): Int = 31 * value.hashCode() + (ieeeBits?.hashCode() ?: 0)

    companion object {
        private val minimumLong = bigIntOf(Long.MIN_VALUE)
        private val maximumLong = bigIntOf(Long.MAX_VALUE)
        private val zero = ExactLpNumber(BigFraction.ZERO, null, 0L)
        private val one = ExactLpNumber(BigFraction.ONE, null, 1L)
        private val negativeOne = ExactLpNumber(BigFraction.ofLong(-1L), null, -1L)

        fun of(value: Long): ExactLpNumber = when (value) {
            0L -> zero
            1L -> one
            -1L -> negativeOne
            else -> ExactLpNumber(BigFraction.ofLong(value), null, value)
        }

        fun of(value: BigFraction): ExactLpNumber {
            val integer = integerOf(value)
            return when (integer) {
                0L -> zero
                1L -> one
                -1L -> negativeOne
                else -> ExactLpNumber(value, null, integer)
            }
        }
        fun ofIeee(value: Double): ExactLpNumber {
            val exact = requireNotNull(BigFraction.ofDouble(value)) { "exact input must be finite" }
            return ExactLpNumber(exact, value.toRawBits(), integerOf(exact))
        }

        private fun integerOf(value: BigFraction): Long? = if (value.den != BIG_ONE ||
            value.num < minimumLong || value.num > maximumLong
        ) {
            null
        } else {
            value.num.toLongExact()
        }
    }
}

internal data class ExactLpPremise(val variable: Int, val upper: Boolean, val threshold: ExactLpNumber) {
    init {
        require(variable >= 0)
    }
}

internal class ExactLpPremises(bounds: List<ExactLpPremise>, literals: List<Int> = emptyList()) {
    private val bounds = bounds.toList()
    private val literals = literals.toList()
    val size: Long get() = bounds.size.toLong() * 3 + literals.size
    val hasIeeeInput: Boolean get() = bounds.any { it.threshold.ieeeBits != null }

    fun boundEntries(): List<ExactLpPremise> = bounds.toList()
    fun literalEntries(): List<Int> = literals.toList()

    fun toRowPremises(): LpRowPremises? {
        val thresholds = LongArray(bounds.size)
        for (i in bounds.indices) thresholds[i] = bounds[i].threshold.integerModelLong() ?: return null
        return LpRowPremises(
            IntArray(bounds.size) { bounds[it].variable },
            BooleanArray(bounds.size) { bounds[it].upper },
            thresholds,
            literals.toIntArray(),
        )
    }

    override fun equals(other: Any?): Boolean = other is ExactLpPremises &&
        bounds == other.bounds && literals == other.literals

    override fun hashCode(): Int = 31 * bounds.hashCode() + literals.hashCode()
}

internal data class ExactLpSide(
    val number: ExactLpNumber,
    val strict: Boolean = false,
    val premises: ExactLpPremises? = null,
)

internal data class ExactLpBounds(val lower: ExactLpSide? = null, val upper: ExactLpSide? = null) {
    val consistent: Boolean get() = lower == null || upper == null ||
        lower.number.value < upper.number.value ||
        (lower.number.value == upper.number.value && !lower.strict && !upper.strict)
    val fixed: Boolean get() = lower != null && upper != null && consistent &&
        lower.number.value == upper.number.value
}

internal data class ExactLpColumn(
    val bounds: ExactLpBounds,
    val origin: ExactLpNumber = ExactLpNumber.of(0L),
    val integral: Boolean = true,
    val tag: Int = -1,
)

internal data class ExactLpRow(
    val global: Boolean = true,
    val strict: Boolean = false,
    val premises: ExactLpPremises? = null,
    val slack: LpRowSlack? = null,
)

internal data class ExactLpEntry(val row: Int, val number: ExactLpNumber)

// Costs and k are in scaled minimized units; externalConstant is already in minimized source units.
// J_min = (c*z + k)/scale + externalConstant; J_source = sign(sense)*J_min.
internal class ExactLpObjective(
    costs: List<ExactLpNumber>,
    val constant: ExactLpNumber = ExactLpNumber.of(0L),
    val scale: ExactLpNumber = ExactLpNumber.of(1L),
    val externalConstant: ExactLpNumber = ExactLpNumber.of(0L),
    val sense: Sense = Sense.MINIMIZE,
) {
    private val costs = costs.toList()
    val size: Int get() = costs.size

    init {
        require(scale.value.signum() > 0) { "objective scale must be positive" }
    }

    fun cost(column: Int): ExactLpNumber = costs[column]

    fun minimizedValue(point: List<BigFraction>): BigFraction {
        require(point.size == size)
        var value = constant.value
        for (j in point.indices) value += costs[j].value * point[j]
        return value * scale.value.reciprocal() + externalConstant.value
    }

    fun sourceValue(point: List<BigFraction>): BigFraction = minimizedValue(point) *
        BigFraction.ofLong(if (sense == Sense.MINIMIZE) 1L else -1L)

    fun withConstant(value: ExactLpNumber): ExactLpObjective = ExactLpObjective(
        costs,
        value,
        scale,
        externalConstant,
        sense,
    )

    override fun equals(other: Any?): Boolean = other is ExactLpObjective && costs == other.costs &&
        constant == other.constant && scale == other.scale && externalConstant == other.externalConstant &&
        sense == other.sense

    override fun hashCode(): Int = listOf(costs, constant, scale, externalConstant, sense).hashCode()
}

internal class ExactLpModel private constructor(
    private val matrix: List<List<ExactLpEntry>>,
    private val rightHandSide: List<ExactLpNumber>,
    private val columns: List<ExactLpColumn>,
    private val rows: List<ExactLpRow>,
    val objective: ExactLpObjective,
    validateMatrix: Boolean,
) {
    constructor(
        matrix: List<List<ExactLpEntry>>,
        rhs: List<ExactLpNumber>,
        columns: List<ExactLpColumn>,
        rows: List<ExactLpRow>,
        objective: ExactLpObjective,
    ) : this(matrix.map { it.toList() }, rhs.toList(), columns.toList(), rows.toList(), objective, true)
    val n: Int get() = matrix.size
    val m: Int get() = rows.size
    val numVars: Int get() = columns.size
    private var storageCache: LpLayoutStorage? = null
    val layoutStorage: LpLayoutStorage get() = storageCache ?: LpLayoutStorage.of(this).also { storageCache = it }
    val keySize: Long get() = layoutStorage.total

    init {
        require(n.toLong() + m == numVars.toLong() && rightHandSide.size == m && objective.size == numVars)
        if (validateMatrix) {
            for (column in this.matrix) {
                var previous = -1
                for (entry in column) {
                    require(entry.row in 0 until m && entry.row > previous)
                    previous = entry.row
                }
            }
            require((n until numVars).all { this.columns[it].origin.value.isZero }) { "slack origins must be zero" }
        }
    }

    fun column(j: Int): ExactLpColumn = columns[j]
    fun row(i: Int): ExactLpRow = rows[i]
    fun rhs(i: Int): ExactLpNumber = rightHandSide[i]
    fun entries(j: Int): List<ExactLpEntry> = matrix[j].toList()

    // Engine kernels read the owned immutable list; caller snapshots go through entries.
    internal fun columnEntries(j: Int): List<ExactLpEntry> = matrix[j]

    fun copy(
        rhs: List<ExactLpNumber> = this.rightHandSide,
        columns: List<ExactLpColumn> = this.columns,
        rows: List<ExactLpRow> = this.rows,
        objective: ExactLpObjective = this.objective,
    ): ExactLpModel {
        require(columns.size == numVars && columns.indices.all { columns[it].origin == this.columns[it].origin }) {
            "coordinate changes require recentered"
        }
        require(rhs.size == m)
        return ExactLpModel(
            matrix,
            if (rhs === rightHandSide) rightHandSide else rhs.toList(),
            if (columns === this.columns) this.columns else columns.toList(),
            if (rows === this.rows) this.rows else rows.toList(),
            objective,
            false,
        ).also { it.storageCache = if (rows === this.rows) layoutStorage else layoutStorage.withRows(rows) }
    }

    /**
     * This model with [columns] in place of its own, for a caller that changed only bounds and integrality, so
     * every origin, and with it every invariant [copy] rechecks, is the one this model already holds. The bound
     * trail derives a state per search push from its predecessor this way; [copy]'s checks are linear in the
     * columns, with exact arithmetic, and would cost every push that much.
     */
    fun withBoundColumns(columns: List<ExactLpColumn>): ExactLpModel {
        require(columns.size == numVars)
        return ExactLpModel(matrix, rightHandSide, columns, rows, objective, false).also {
            it.storageCache = layoutStorage
        }
    }

    fun withObjective(objective: ExactLpObjective): ExactLpModel {
        require(objective.size == numVars)
        return ExactLpModel(matrix, rightHandSide, columns, rows, objective, false).also {
            it.storageCache = layoutStorage
        }
    }

    internal fun sharesRegion(other: ExactLpModel): Boolean = matrix === other.matrix &&
        rightHandSide === other.rightHandSide && columns === other.columns && rows === other.rows

    fun recentered(origins: List<ExactLpNumber>): ExactLpModel {
        require(origins.size == n)
        if ((0 until n).all { origins[it] == columns[it].origin }) return copy()
        require(!hasIeeeInput() && origins.none { it.ieeeBits != null }) {
            "IEEE source recenter requires retained input history"
        }
        val rhs = rightHandSide.toMutableList()
        val columns = this.columns.toMutableList()
        var constant = objective.constant.value
        for (j in 0 until n) {
            val delta = origins[j].value - columns[j].origin.value
            if (!delta.isZero) {
                for (entry in matrix[j]) {
                    rhs[entry.row] = ExactLpNumber.of(rhs[entry.row].value - entry.number.value * delta)
                }
                constant += objective.cost(j).value * delta
            }
            columns[j] = columns[j].copy(bounds = columns[j].bounds.recentered(delta), origin = origins[j])
        }
        val nextObjective = if (constant == objective.constant.value) {
            objective
        } else {
            objective.withConstant(ExactLpNumber.of(constant))
        }
        return ExactLpModel(matrix, rhs.toList(), columns.toList(), rows, nextObjective, false).also {
            it.storageCache = layoutStorage
        }
    }

    private fun hasIeeeInput(): Boolean = matrix.any { entries -> entries.any { it.number.ieeeBits != null } } ||
        rightHandSide.any { it.ieeeBits != null } || columns.any { column ->
            column.origin.ieeeBits != null || listOfNotNull(column.bounds.lower, column.bounds.upper).any {
                it.number.ieeeBits != null || it.premises?.hasIeeeInput == true
            }
        } || rows.any { it.premises?.hasIeeeInput == true } ||
        (0 until objective.size).any { objective.cost(it).ieeeBits != null } ||
        listOf(objective.constant, objective.scale, objective.externalConstant).any { it.ieeeBits != null }

    // Full immutable value comparison includes raw input bits, provenance and objective units.
    fun sameAuthority(other: ExactLpModel): Boolean = matrix == other.matrix &&
        rightHandSide == other.rightHandSide && columns == other.columns && rows == other.rows &&
        objective == other.objective

    fun sameMatrix(other: ExactLpModel): Boolean = n == other.n && m == other.m &&
        matrix == other.matrix && rows == other.rows

    fun toIntegerModel(): LpModel? {
        if (columns.any { !it.bounds.consistent || !it.integral } || rows.any { it.strict || it.slack != null }) {
            return null
        }
        if (objective.scale != ExactLpNumber.of(1L) || objective.externalConstant != ExactLpNumber.of(0L)) return null
        val upper = LongArray(numVars)
        val hasUpper = BooleanArray(numVars)
        val origin = LongArray(n)
        val cost = LongArray(numVars)
        for (j in columns.indices) {
            val bounds = columns[j].bounds
            if (bounds.lower != ExactLpSide(ExactLpNumber.of(0L))) return null
            bounds.upper?.let { side ->
                if (side.strict || side.premises != null) return null
                upper[j] = side.number.integerModelLong() ?: return null
                hasUpper[j] = true
            }
            if (j >= n && hasUpper[j] && upper[j] != 0L) return null
            if (j < n) {
                origin[j] = columns[j].origin.integerModelLong() ?: return null
            } else if (columns[j].origin != ExactLpNumber.of(0L) || columns[j].tag != -1) {
                return null
            }
            cost[j] = objective.cost(j).integerModelLong() ?: return null
        }
        val constant = objective.constant.integerModelLong() ?: return null
        val rhs = LongArray(m)
        val premises = arrayOfNulls<LpRowPremises>(m)
        for (i in rows.indices) {
            rhs[i] = rightHandSide[i].integerModelLong() ?: return null
            premises[i] = rows[i].premises?.let { it.toRowPremises() ?: return null }
        }
        val pointers = IntArray(n + 1)
        for (j in 0 until n) pointers[j + 1] = pointers[j] + matrix[j].size
        val indices = IntArray(pointers[n])
        val values = LongArray(pointers[n])
        for (j in 0 until n) {
            for ((k, entry) in matrix[j].withIndex()) {
                indices[pointers[j] + k] = entry.row
                values[pointers[j] + k] = entry.number.integerModelLong() ?: return null
            }
        }
        val flipped = rhs.copyOf()
        try {
            for (j in 0 until n) {
                for (k in pointers[j] until pointers[j + 1]) {
                    flipped[indices[k]] = addExact(flipped[indices[k]], mulExact(values[k], origin[j]))
                }
            }
        } catch (_: ArithmeticException) {
            return null
        }
        return LpModel(
            n, m, Csc(pointers, indices, values), rhs, cost, upper, hasUpper, origin, constant,
            objective.sense, IntArray(n) { columns[it].tag },
            rowGlobal = BooleanArray(m) { rows[it].global }, rowPremises = premises, flippedRhs = flipped,
        )
    }
}

private fun ExactLpBounds.recentered(delta: BigFraction): ExactLpBounds {
    if (delta.isZero) return this
    fun shift(side: ExactLpSide?): ExactLpSide? = side?.copy(number = ExactLpNumber.of(side.number.value - delta))
    return ExactLpBounds(shift(lower), shift(upper))
}
