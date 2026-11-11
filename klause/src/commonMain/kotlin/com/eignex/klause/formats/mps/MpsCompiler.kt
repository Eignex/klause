package com.eignex.klause.formats.mps

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.ir.LinearObjectiveSpec
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SourceSemanticsOwner

/** Raised when an MPS source model cannot be represented by klause's lowering. */
class MpsLoweringException(msg: String) : IllegalArgumentException("MPS: $msg")

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

    /** Whether lowered rows, bounds and objective retain the source under its effective declaration policy. */
    val sourceExact: Boolean get() = sourceModel != null && sourceMismatch == null

    /** The first source value the lowered model does not retain, if any. */
    val sourceDifference: String?
        get() = if (sourceModel == null) "compiled model lacks source mapping" else sourceMismatch

    internal fun withLinearSource(
        source: LinearSourceProblem,
        mismatch: String?,
    ): MpsCompiled = apply {
        check(sourceModel == null) { "MPS source model is already attached" }
        require(source.semantics == semantics) { "MPS source interpretation differs from the compiled model" }
        sourceModel = source
        sourceMismatch = mismatch
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

/** Compile one captured MPS source through the shared linear working view under its effective policy. */
fun MpsModel.toProblem(
    settings: ProblemSettings = KlauseConfig.current.problemSettings(),
    semanticOverrides: SemanticOverrides = SemanticOverrides(),
): MpsCompiled {
    val source = checkNotNull(toLinearSourceProblem(semanticOverrides))
    return compileMpsView(source, settings,
        allowProjection = !checkNotNull(source.semantics.effective.feasibilityTolerance).isExact)
}
