package com.eignex.klause.formats.mps

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.ir.LinearObjectiveSpec
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SourceSemanticsOwner
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.pipeline.toleranceQualification
import com.eignex.klause.solver.validation.LinearSourceCheck
import com.eignex.klause.solver.validation.checkLinearSourceWitness
import com.eignex.klause.util.Cancellation

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
    /** The compiled solver problem — integer variables for integer columns and proven integral definitions,
     *  with LP-only continuous variables for the remaining float columns. */
    val model: Problem,
    /** Objective, or `null` for a feasibility instance (no `N` row). */
    val objective: LinearObjectiveSpec?,
    /** True when the objective is a maximise. */
    val maximize: Boolean,
    /** Columns in declaration order, each mapping a name to its integer- or real-variable id. */
    val columns: List<MpsColumn>,
    /** Positive multiplier from source objective units to retained working objective units. */
    val objectiveScale: Long,
    /** Count of the source's continuous columns (zero for a pure-integer instance). */
    val floatColumns: Int,
) : SourceSemanticsOwner {
    override val semantics: EffectiveSourceSemantics get() = model.semantics
    private var sourceModel: LinearSourceProblem? = null
    private var sourceMismatch: String? = null
    private var toleranceMismatch: String? = null

    /** Whether lowered rows, bounds and objective retain the source under its effective declaration policy. */
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
     * Whether nominal constraints and tolerance acceptance have provably identical feasible points.
     * Representation correspondence remains separate; an interrupted check establishes no equivalence.
     */
    fun exactClaimsCoverTolerance(cancellation: Cancellation = Cancellation.Never): Boolean {
        if (cancellation()) return false
        val source = sourceModel ?: return false
        return checkNotNull(semantics.effective.feasibilityTolerance).isExact ||
            source.toleranceQualification(cancellation)?.verify() == true
    }

    /**
     * An exact objective bound from tolerance-accepted declarations and enabled one-term rows, in source units.
     * The bound is lower for minimization and upper for maximization.
     * An open required side or interruption yields null.
     */
    fun toleranceObjectiveBound(cancellation: Cancellation = Cancellation.Never): BigFraction? =
        sourceModel?.toleranceQualification(cancellation)?.objectiveBound()

    /** Check finite binary64 coordinates against original source numbers under the effective policy. */
    fun withinTolerance(
        ints: LongArray,
        reals: DoubleArray,
        cancellation: Cancellation = Cancellation.Never,
    ): Boolean {
        if (cancellation() || sourceModel == null) return false
        val values = columns.map { column ->
            cancellation.charge(1L)
            if (cancellation()) return false
            if (column.real) {
                reals.getOrNull(column.id)?.let(BigFraction::ofDouble) ?: return false
            } else {
                ints.getOrNull(column.id)?.let(BigFraction::ofLong) ?: return false
            }
        }
        return try {
            sourceWitness(values, cancellation)
            true
        } catch (_: MpsLoweringException) {
            false
        }
    }

    internal fun withLinearSource(
        source: LinearSourceProblem,
        mismatch: String?,
        toleranceMismatch: String?,
    ): MpsCompiled = apply {
        check(sourceModel == null) { "MPS source model is already attached" }
        require(source.semantics == semantics) { "MPS source interpretation differs from the compiled model" }
        sourceModel = source
        sourceMismatch = mismatch
        this.toleranceMismatch = toleranceMismatch
    }

    /** Check a solved point against the original source using the declared effective tolerance. */
    fun sourceWitness(ints: LongArray, reals: List<BigFraction>?): MpsSourceWitness =
        sourceWitness(columns.map { column -> sourceColumnValue(column, ints, reals) })

    /**
     * Attempt [sourceWitness] when columns, rows and coefficient entries fit [maxEntries]. Return `null`
     * for oversized or rejected candidates; feasibility uses the same source correspondence and tolerance policy.
     */
    fun sourceWitnessOrNull(
        ints: LongArray,
        reals: List<BigFraction>?,
        maxEntries: Int,
    ): MpsSourceWitness? {
        require(maxEntries >= 0)
        val source = sourceModel ?: return null
        var remaining = maxEntries.toLong() - columns.size - source.objective?.termValues.orEmpty().size
        if (remaining < 0L) return null
        for (row in source.rowValues) {
            remaining -= row.termValues.size.toLong() + 1L
            if (remaining < 0L) return null
        }
        return try {
            sourceWitness(ints, reals)
        } catch (_: MpsLoweringException) {
            null
        }
    }

    /**
     * Check exact coordinates in source [columns] order, including integer values outside `Long`.
     * Source integrality is required exactly; rows and bounds use the effective absolute and relative tolerance.
     */
    fun sourceWitness(
        values: List<BigFraction>,
        cancellation: Cancellation = Cancellation.Never,
    ): MpsSourceWitness {
        val source = checkNotNull(sourceModel) { "MPS source model is unavailable" }
        return when (val checked = checkLinearSourceWitness(source, values, cancellation)) {
            is LinearSourceCheck.Accepted -> checked.witness.let { witness ->
                MpsSourceWitness(witness.values, witness.objective ?: BigFraction.ZERO, witness.exact)
            }
            LinearSourceCheck.Rejected -> mpsLoweringError("source witness violates original declarations or rows")
            LinearSourceCheck.Declined -> mpsLoweringError("source witness check cancelled")
        }
    }

    /**
     * The exact source objective at a binary64 point, in source units and sense: the decimal coefficients times the
     * doubles themselves, so a printed float witness reports its own objective free of summation error. Null when a
     * value is missing or not finite.
     */
    fun sourceObjective(ints: LongArray, reals: DoubleArray): BigFraction? {
        val source = checkNotNull(sourceModel) { "MPS source model is unavailable" }
        val original = source.objective ?: return BigFraction.ZERO
        return original.termValues.fold(BigFraction.of(original.constant.numerator, original.constant.denominator)) {
                sum, term ->
            val column = columns[term.column]
            val value = if (column.real) {
                reals.getOrNull(column.id)?.let(BigFraction::ofDouble)
            } else {
                ints.getOrNull(column.id)?.let(BigFraction::ofLong)
            }
            sum + BigFraction.of(term.coefficient.numerator, term.coefficient.denominator) * (value ?: return null)
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

/** A point checked against the original MPS decimal authority. */
data class MpsSourceWitness(
    /** Values in source column order. */
    val values: List<BigFraction>,
    /** Objective in source units and sense. */
    val objective: BigFraction,
    /** Whether every source row and bound holds without feasibility slack. */
    val exact: Boolean = true,
)

/** Primal feasibility tolerance of MPS results under tolerance semantics. */
const val MPS_TOLERANCE: Double = 1e-7

/** Compile one captured MPS source through the shared linear working view under its effective policy. */
fun MpsModel.toProblem(
    settings: ProblemSettings = KlauseConfig.current.problemSettings(),
    semanticOverrides: SemanticOverrides = SemanticOverrides(),
): MpsCompiled {
    val source = checkNotNull(toLinearSourceProblem(semanticOverrides))
    return compileMpsView(source, settings,
        allowProjection = !checkNotNull(source.semantics.effective.feasibilityTolerance).isExact)
}
