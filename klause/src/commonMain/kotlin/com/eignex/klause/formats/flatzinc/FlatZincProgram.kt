package com.eignex.klause.formats.flatzinc

import com.eignex.klause.ir.DefinitionHint
import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.ir.Problem
import com.eignex.klause.lowering.FloatBucketing
import com.eignex.klause.lowering.FloatRepresentation
import com.eignex.klause.lowering.SetLayout

/** Compiled FlatZinc model plus metadata used by solution writing and search configuration. */
@Suppress("LongParameterList", "TooManyFunctions")
class FlatZincProgram(
    /** Compiled solver problem. */
    problem: Problem,
    /** Parsed solve directive. */
    val solve: SolveDirective,
    /** FlatZinc bool variable name to solver bool id. */
    val boolVarsByName: Map<String, Int>,
    /** FlatZinc int variable name to solver int id. */
    intVarsByName: Map<String, Int>,
    /** FlatZinc float variable name to bucketing metadata. */
    floatVarsByName: Map<String, FloatBucketing>,
    /** Declared arrays by name. */
    arraysByName: Map<String, FlatZincArray>,
    /** Ordered output items or null when no output clause exists. */
    val outputItems: List<OutputItem>?,
    /** Search hints inferred from search annotations. */
    val searchHints: FlatZincSearchHints?,
    /** Enum labels preserved by the FlatZinc frontend. */
    val enumLabelsByVar: Map<String, List<String>> = emptyMap(),
    /** Set variable layouts by name. */
    val setVarsByName: Map<String, SetVarLayout> = emptyMap(),
    /** Source float component identities and approximation reasons. */
    floatRepresentations: Map<String, FloatRepresentation> = emptyMap(),
    /** Declared defaults and effective caller policy captured before lowering. */
    val semantics: EffectiveSourceSemantics = problem.semantics,
    /** Source integer/Boolean definitions in [problem]'s coordinates; rewrites must remap these hints.
     * No consumer state or dependency order is retained here. */
    val definitionHints: List<DefinitionHint> = emptyList(),
) {
    private val suppliedProblem = problem
    private val suppliedInts = intVarsByName
    private val suppliedFloats = floatVarsByName
    private val suppliedArrays = arraysByName
    private val suppliedRepresentations = floatRepresentations
    internal var retainedSource: FlatZincSourceState? = null
        private set

    /** Historical finite compatibility model, materialized only when requested. */
    val problem: Problem get() = retainedSource?.legacy?.problem ?: suppliedProblem

    /** Integer names in the historical finite compatibility frame. */
    val intVarsByName: Map<String, Int> get() = retainedSource?.legacy?.intVarsByName ?: suppliedInts

    /** Float decoders paired with the historical finite compatibility model. */
    val floatVarsByName: Map<String, FloatBucketing> get() = retainedSource?.legacy?.floatVarsByName ?: suppliedFloats

    /** Arrays paired with the historical finite compatibility model. */
    val arraysByName: Map<String, FlatZincArray> get() = retainedSource?.legacy?.arraysByName ?: suppliedArrays

    /** Component provenance paired with the historical finite compatibility model. */
    val floatRepresentations: Map<String, FloatRepresentation>
        get() = retainedSource?.legacy?.floatRepresentations ?: suppliedRepresentations

    /** Original real declarations and metadata, without forcing a private compatibility view. */
    val sourceProgram: FlatZincProgram get() = retainedSource?.original ?: this

    internal fun retainSource(state: FlatZincSourceState): FlatZincProgram {
        check(retainedSource == null) { "FlatZinc program already retains its source frame" }
        retainedSource = state
        return this
    }

    internal fun suppliedCopy(): FlatZincProgram = FlatZincProgram(
        suppliedProblem, solve, boolVarsByName, suppliedInts, suppliedFloats, suppliedArrays, outputItems,
        searchHints, enumLabelsByVar, setVarsByName, suppliedRepresentations, semantics, definitionHints,
    )

    /** Copy the public program fields, preserving source correspondence only for the actual model. */
    fun copy(
        problem: Problem = this.problem,
        solve: SolveDirective = this.solve,
        boolVarsByName: Map<String, Int> = this.boolVarsByName,
        intVarsByName: Map<String, Int> = this.intVarsByName,
        floatVarsByName: Map<String, FloatBucketing> = this.floatVarsByName,
        arraysByName: Map<String, FlatZincArray> = this.arraysByName,
        outputItems: List<OutputItem>? = this.outputItems,
        searchHints: FlatZincSearchHints? = this.searchHints,
        enumLabelsByVar: Map<String, List<String>> = this.enumLabelsByVar,
        setVarsByName: Map<String, SetVarLayout> = this.setVarsByName,
        floatRepresentations: Map<String, FloatRepresentation> = this.floatRepresentations,
        semantics: EffectiveSourceSemantics = this.semantics,
        definitionHints: List<DefinitionHint> = this.definitionHints,
    ): FlatZincProgram {
        val copied = FlatZincProgram(problem, solve, boolVarsByName, intVarsByName, floatVarsByName,
            arraysByName, outputItems, searchHints, enumLabelsByVar, setVarsByName, floatRepresentations,
            semantics, definitionHints)
        retainedSource?.copyFor(copied)?.let { copied.retainSource(it) }
        return copied
    }

    /** problem for positional source compatibility. */
    operator fun component1(): Problem = problem

    /** solve for positional source compatibility. */
    operator fun component2(): SolveDirective = solve

    /** boolVarsByName for positional source compatibility. */
    operator fun component3(): Map<String, Int> = boolVarsByName

    /** intVarsByName for positional source compatibility. */
    operator fun component4(): Map<String, Int> = intVarsByName

    /** floatVarsByName for positional source compatibility. */
    operator fun component5(): Map<String, FloatBucketing> = floatVarsByName

    /** arraysByName for positional source compatibility. */
    operator fun component6(): Map<String, FlatZincArray> = arraysByName

    /** outputItems for positional source compatibility. */
    operator fun component7(): List<OutputItem>? = outputItems

    /** searchHints for positional source compatibility. */
    operator fun component8(): FlatZincSearchHints? = searchHints

    /** enumLabelsByVar for positional source compatibility. */
    operator fun component9(): Map<String, List<String>> = enumLabelsByVar

    /** setVarsByName for positional source compatibility. */
    operator fun component10(): Map<String, SetVarLayout> = setVarsByName

    /** floatRepresentations for positional source compatibility. */
    operator fun component11(): Map<String, FloatRepresentation> = floatRepresentations

    /** semantics for positional source compatibility. */
    operator fun component12(): EffectiveSourceSemantics = semantics

    /** definitionHints for positional source compatibility. */
    operator fun component13(): List<DefinitionHint> = definitionHints

    private fun publicFields(): List<Any?> = listOf(
        problem, solve, boolVarsByName, intVarsByName, floatVarsByName, arraysByName, outputItems,
        searchHints, enumLabelsByVar, setVarsByName, floatRepresentations, semantics, definitionHints,
    )

    override fun equals(other: Any?): Boolean = this === other ||
        (other is FlatZincProgram && publicFields() == other.publicFields())

    override fun hashCode(): Int {
        var hash = 0
        for (field in publicFields()) hash = 31 * hash + (field?.hashCode() ?: 0)
        return hash
    }

    override fun toString(): String = publicFields().zip(FIELD_NAMES).joinToString(
        prefix = "FlatZincProgram(", postfix = ")",
    ) { (value, name) -> "$name=$value" }

    private companion object {
        val FIELD_NAMES = listOf(
            "problem", "solve", "boolVarsByName", "intVarsByName",
            "floatVarsByName", "arraysByName", "outputItems", "searchHints",
            "enumLabelsByVar", "setVarsByName", "floatRepresentations", "semantics",
            "definitionHints",
        )
    }
}

/** Bool-indicator layout of one FlatZinc set variable. */
data class SetVarLayout(
    /** Set variable name. */
    val name: String,
    /** Sorted universe values. */
    val elements: IntArray,
    /** Indicator bool ids parallel to [elements]. */
    val indicatorBoolIds: IntArray,
) {
    internal val layout = SetLayout(elements, indicatorBoolIds)

    override fun equals(other: Any?): Boolean {
        if (other !is SetVarLayout) return false
        return name == other.name &&
            elements.contentEquals(other.elements) &&
            indicatorBoolIds.contentEquals(other.indicatorBoolIds)
    }
    override fun hashCode(): Int {
        var h = name.hashCode()
        h = 31 * h + elements.contentHashCode()
        h = 31 * h + indicatorBoolIds.contentHashCode()
        return h
    }
}

/** Parsed `solve` directive. */
sealed interface SolveDirective {
    /** Satisfy model directive. */
    data object Satisfy : SolveDirective

    /** Minimize objective directive. */
    data class Minimize(
        /** Objective variable name. */
        val objVar: String,
        /** Objective variable kind. */
        val kind: ObjKind,
    ) : SolveDirective

    /** Maximize objective directive. */
    data class Maximize(
        /** Objective variable name. */
        val objVar: String,
        /** Objective variable kind. */
        val kind: ObjKind,
    ) : SolveDirective

    /** Objective variable value kind. */
    enum class ObjKind {
        /** Boolean objective. */
        Bool,

        /** Integer objective. */
        Int,

        /** Float objective, a bucketed int variable or a real column. */
        Float,
    }
}

/** FlatZinc array declaration payload. */
sealed interface FlatZincArray {
    /** Array name. */
    val name: String

    /** Array length. */
    val length: Int

    /** Parameter bool array. */
    data class BoolParam(
        override val name: String,
        /** Constant values. */
        val values: BooleanArray,
    ) : FlatZincArray {
        override val length: Int get() = values.size
    }

    /** Parameter int array. */
    data class IntParam(
        override val name: String,
        /** Constant values. May exceed 32-bit range (e.g. large linear coefficients). */
        val values: LongArray,
    ) : FlatZincArray {
        override val length: Int get() = values.size
    }

    /** Parameter float array. */
    data class FloatParam(
        override val name: String,
        /** Constant values. */
        val values: DoubleArray,
    ) : FlatZincArray {
        override val length: Int get() = values.size
    }

    /** Parameter array of set-of-int constants. */
    data class IntSetParam(
        override val name: String,
        /** Constant set values, each sorted. */
        val values: List<IntArray>,
    ) : FlatZincArray {
        override val length: Int get() = values.size
    }

    /** Variable array payload. */
    data class Vars(
        override val name: String,
        /** Per-element solver var ids. */
        val varIds: IntArray,
        /** Per-element value kind. */
        val elementKind: ElementKind,
        /** Optional bucketing metadata for float arrays. */
        val floatBucketings: List<FloatBucketing>? = null,
    ) : FlatZincArray {
        override val length: Int get() = varIds.size

        /** Variable array element kind. */
        enum class ElementKind {
            /** Boolean elements. */
            Bool,

            /** Integer elements. */
            Int,

            /** Float elements, as bucket indices or real columns. */
            Float,
        }
    }

    /** Array of set variables. */
    data class SetVars(
        override val name: String,
        /** Per-element set layouts. */
        val layouts: List<SetVarLayout>,
    ) : FlatZincArray {
        override val length: Int get() = layouts.size
    }
}

/** One item in a FlatZinc `output [ ... ]` declaration. */
sealed interface OutputItem {
    /** Literal text segment. */
    data class Literal(
        /** Literal text. */
        val text: String,
    ) : OutputItem

    /** Scalar variable render request. */
    data class ShowVar(
        /** Variable name. */
        val name: String,
    ) : OutputItem

    /** Array render request. */
    data class ShowArray(
        /** Array name. */
        val name: String,
    ) : OutputItem
}
