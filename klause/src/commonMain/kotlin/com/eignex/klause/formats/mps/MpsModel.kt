package com.eignex.klause.formats.mps

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.simplex.exact.BigFraction

internal class MpsSourceNumber private constructor(
    private val rational: BigFraction?,
    private val ieeeBits: Long?,
    private val projectionBits: Long,
) {
    val fraction: BigFraction
        get() = rational ?: requireNotNull(BigFraction.ofDouble(Double.fromBits(checkNotNull(ieeeBits))))
    val double: Double get() = Double.fromBits(projectionBits)
    val isIeee: Boolean get() = ieeeBits != null

    fun plus(other: MpsSourceNumber): MpsSourceNumber = parsed(fraction + other.fraction, double + other.double)

    fun minus(other: MpsSourceNumber): MpsSourceNumber = parsed(fraction - other.fraction, double - other.double)

    fun negated(): MpsSourceNumber = parsed(fraction.negated(), -double)

    fun absolute(): MpsSourceNumber = if (fraction.signum() < 0) negated() else this

    fun toExactLpNumber(): ExactLpNumber = ieeeBits?.let { ExactLpNumber.ofIeee(Double.fromBits(it)) }
        ?: ExactLpNumber.of(fraction)

    fun toExactLpNumber(negated: Boolean): ExactLpNumber = if (!negated) {
        toExactLpNumber()
    } else {
        ieeeBits?.let { ExactLpNumber.ofIeee(-Double.fromBits(it)) } ?: ExactLpNumber.of(fraction.negated())
    }

    companion object {
        fun parsed(value: BigFraction, projection: Double = value.toDouble()): MpsSourceNumber =
            MpsSourceNumber(value, null, projection.toRawBits())

        fun ieee(value: Double): MpsSourceNumber {
            require(value.isFinite()) { "MPS source number must be finite" }
            return MpsSourceNumber(null, value.toRawBits(), value.toRawBits())
        }
    }
}

internal class MpsSourceNumbers(
    variableBounds: List<Pair<MpsSourceNumber?, MpsSourceNumber?>>,
    constraintCoefficients: List<List<MpsSourceNumber>>,
    constraintBounds: List<Pair<MpsSourceNumber?, MpsSourceNumber?>>,
    objectiveCoefficients: List<MpsSourceNumber>,
    val objectiveConstant: MpsSourceNumber,
    private val snapshot: MpsSourceSnapshot? = null,
) {
    val variableBounds = variableBounds.toList()
    val constraintCoefficients = constraintCoefficients.map { it.toList() }
    val constraintBounds = constraintBounds.toList()
    val objectiveCoefficients = objectiveCoefficients.toList()

    fun attachedTo(
        objective: MpsObjective,
        variables: List<MpsVar>,
        constraints: List<MpsConstraint>,
    ): MpsSourceNumbers = MpsSourceNumbers(
        variableBounds,
        constraintCoefficients,
        constraintBounds,
        objectiveCoefficients,
        objectiveConstant,
        MpsSourceSnapshot.capture(objective, variables, constraints),
    )

    fun reconciled(
        objective: MpsObjective,
        variables: List<MpsVar>,
        constraints: List<MpsConstraint>,
    ): MpsSourceNumbers {
        val before = snapshot
        if (before != null && before.matches(objective, variables, constraints)) return this
        val nextObjective = objective.coeffs.indices.map { entry ->
            if (before != null && objective === before.objective && entry < objectiveCoefficients.size &&
                objective.coeffs[entry].toRawBits() == before.objectiveCoefficientBits[entry]
            ) {
                objectiveCoefficients[entry]
            } else {
                MpsSourceNumber.ieee(objective.coeffs[entry])
            }
        }
        val nextConstant = if (before != null && objective === before.objective) {
            objectiveConstant
        } else {
            MpsSourceNumber.ieee(objective.constant)
        }
        val nextVariables = variables.map { variable ->
            val sourceIndex = before?.variables?.indexOfFirst { it === variable } ?: -1
            if (sourceIndex >= 0) {
                variableBounds[sourceIndex]
            } else {
                variable.lower.toVariableLowerSource() to variable.upper.toVariableUpperSource()
            }
        }
        val nextCoefficients = constraints.map { row ->
            val sourceIndex = before?.constraints?.indexOfFirst { it === row } ?: -1
            row.coeffs.indices.map { entry ->
                if (before != null && sourceIndex >= 0 && entry < constraintCoefficients[sourceIndex].size &&
                    row.coeffs[entry].toRawBits() == before.constraintCoefficientBits[sourceIndex][entry]
                ) {
                    constraintCoefficients[sourceIndex][entry]
                } else {
                    MpsSourceNumber.ieee(row.coeffs[entry])
                }
            }
        }
        val nextBounds = constraints.map { row ->
            val sourceIndex = before?.constraints?.indexOfFirst { it === row } ?: -1
            if (sourceIndex >= 0) {
                constraintBounds[sourceIndex]
            } else {
                row.lower.toRowBoundSource() to row.upper.toRowBoundSource()
            }
        }
        return MpsSourceNumbers(nextVariables, nextCoefficients, nextBounds, nextObjective, nextConstant)
            .attachedTo(objective, variables, constraints)
    }

    companion object {
        fun ieee(
            objective: MpsObjective,
            variables: List<MpsVar>,
            constraints: List<MpsConstraint>,
        ): MpsSourceNumbers = MpsSourceNumbers(
            variables.map { variable ->
                variable.lower.toVariableLowerSource() to variable.upper.toVariableUpperSource()
            },
            constraints.map { row -> row.coeffs.map(MpsSourceNumber::ieee) },
            constraints.map { row -> row.lower.toRowBoundSource() to row.upper.toRowBoundSource() },
            objective.coeffs.map(MpsSourceNumber::ieee),
            MpsSourceNumber.ieee(objective.constant),
        ).attachedTo(objective, variables, constraints)
    }
}

internal class MpsSourceSnapshot(
    val objective: MpsObjective,
    val objectiveIndices: IntArray,
    val objectiveCoefficientBits: LongArray,
    val variables: List<MpsVar>,
    val constraints: List<MpsConstraint>,
    val constraintIndices: List<IntArray>,
    val constraintCoefficientBits: List<LongArray>,
) {
    fun matches(objective: MpsObjective, variables: List<MpsVar>, constraints: List<MpsConstraint>): Boolean {
        if (objective !== this.objective || !objective.indices.contentEquals(objectiveIndices) ||
            !objective.coeffs.rawBitsEqual(objectiveCoefficientBits)
        ) {
            return false
        }
        if (variables.size != this.variables.size || variables.indices.any { variables[it] !== this.variables[it] }) {
            return false
        }
        if (constraints.size != this.constraints.size) return false
        return constraints.indices.all { index ->
            val row = constraints[index]
            row === this.constraints[index] && row.indices.contentEquals(constraintIndices[index]) &&
                row.coeffs.rawBitsEqual(constraintCoefficientBits[index])
        }
    }

    companion object {
        fun capture(
            objective: MpsObjective,
            variables: List<MpsVar>,
            constraints: List<MpsConstraint>,
        ): MpsSourceSnapshot = MpsSourceSnapshot(
            objective,
            objective.indices.copyOf(),
            LongArray(objective.coeffs.size) { objective.coeffs[it].toRawBits() },
            variables.toList(),
            constraints.toList(),
            constraints.map { it.indices.copyOf() },
            constraints.map { row -> LongArray(row.coeffs.size) { row.coeffs[it].toRawBits() } },
        )
    }
}

private fun DoubleArray.rawBitsEqual(bits: LongArray): Boolean =
    size == bits.size && indices.all { this[it].toRawBits() == bits[it] }

private fun Double?.toVariableLowerSource(): MpsSourceNumber? = when (this) {
    null, Double.NEGATIVE_INFINITY -> null
    else -> MpsSourceNumber.ieee(this)
}

private fun Double?.toVariableUpperSource(): MpsSourceNumber? = when (this) {
    null, Double.POSITIVE_INFINITY -> null
    else -> MpsSourceNumber.ieee(this)
}

private fun Double?.toRowBoundSource(): MpsSourceNumber? = when {
    this == null || isInfinite() -> null
    else -> MpsSourceNumber.ieee(this)
}

/** A decision variable from an MPS source model. */
data class MpsVar(
    /** Source identifier. */
    val name: String,
    /** Whether the variable has integral values. */
    val integer: Boolean,
    /** Optional lower bound. */
    val lower: Double?,
    /** Optional upper bound. */
    val upper: Double?,
)

/** An MPS row indicator. */
data class MpsIndicator(
    /** Index of the controlling variable. */
    val column: Int,
    /** Whether the row is enabled when the variable equals one. */
    val whenOne: Boolean,
)

/** A two-sided sparse linear MPS row. */
data class MpsConstraint(
    /** Source row identifier. */
    val name: String,
    /** Variable index for each coefficient. */
    val indices: IntArray,
    /** Coefficients aligned with [indices]. */
    val coeffs: DoubleArray,
    /** Optional lower bound. */
    val lower: Double?,
    /** Optional upper bound. */
    val upper: Double?,
    /** Optional condition enabling this row. */
    val indicator: MpsIndicator? = null,
)

/** A sparse linear MPS objective. */
data class MpsObjective(
    /** Source row identifier. */
    val name: String,
    /** Variable index for each coefficient. */
    val indices: IntArray,
    /** Coefficients aligned with [indices]. */
    val coeffs: DoubleArray,
    /** Constant term. */
    val constant: Double,
)

/** A parsed MPS instance before lowering to a klause problem. */
@Suppress("UndocumentedPublicFunction")
class MpsModel(
    /** Instance name. */
    val name: String,
    /** Objective direction. */
    val sense: ObjectiveSense,
    /** Parsed objective row. */
    val objective: MpsObjective,
    /** Decision variables in column order. */
    val variables: List<MpsVar>,
    /** Linear rows. */
    val constraints: List<MpsConstraint>,
) {
    private var parsedSourceNumbers: MpsSourceNumbers? = null

    internal fun sourceNumbers(): MpsSourceNumbers {
        val current = (parsedSourceNumbers ?: MpsSourceNumbers.ieee(objective, variables, constraints))
            .reconciled(objective, variables, constraints)
        parsedSourceNumbers = current
        return current
    }

    internal fun withSourceNumbers(source: MpsSourceNumbers): MpsModel = apply {
        check(parsedSourceNumbers == null) { "MPS source authority is already attached" }
        require(source.variableBounds.size == variables.size)
        require(source.constraintCoefficients.size == constraints.size)
        require(source.constraintBounds.size == constraints.size)
        require(source.objectiveCoefficients.size == objective.coeffs.size)
        parsedSourceNumbers = source.attachedTo(objective, variables, constraints)
    }

    fun copy(
        name: String = this.name,
        sense: ObjectiveSense = this.sense,
        objective: MpsObjective = this.objective,
        variables: List<MpsVar> = this.variables,
        constraints: List<MpsConstraint> = this.constraints,
    ): MpsModel {
        val source = sourceNumbers().reconciled(objective, variables, constraints)
        return MpsModel(name, sense, objective, variables, constraints).withSourceNumbers(source)
    }

    operator fun component1(): String = name
    operator fun component2(): ObjectiveSense = sense
    operator fun component3(): MpsObjective = objective
    operator fun component4(): List<MpsVar> = variables
    operator fun component5(): List<MpsConstraint> = constraints

    override fun equals(other: Any?): Boolean = other is MpsModel &&
        name == other.name && sense == other.sense && objective == other.objective &&
        variables == other.variables && constraints == other.constraints

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + sense.hashCode()
        result = 31 * result + objective.hashCode()
        result = 31 * result + variables.hashCode()
        return 31 * result + constraints.hashCode()
    }

    override fun toString(): String =
        "MpsModel(name=$name, sense=$sense, objective=$objective, variables=$variables, constraints=$constraints)"
}
