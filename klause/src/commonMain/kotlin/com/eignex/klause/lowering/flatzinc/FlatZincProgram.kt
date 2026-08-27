package com.eignex.klause.lowering.flatzinc

import com.eignex.klause.formats.flatzinc.*
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.lowering.FloatBucketing
import com.eignex.klause.solver.objective.IncrementalObjective

/** Compiled FlatZinc model plus metadata used by solution writing and search defaults. */
data class FlatZincProgram(
    /** Compiled solver problem. */
    val problem: Problem,
    /** Parsed solve directive. */
    val solve: SolveDirective,
    /** FlatZinc bool variable name to solver bool id. */
    val boolVarsByName: Map<String, Int>,
    /** FlatZinc int variable name to solver int id. */
    val intVarsByName: Map<String, Int>,
    /** FlatZinc float variable name to bucketing metadata. */
    val floatVarsByName: Map<String, FloatBucketing>,
    /** Declared arrays by name. */
    val arraysByName: Map<String, FlatZincArray>,
    /** Ordered output items or null when no output clause exists. */
    val outputItems: List<OutputItem>?,
    /** Search hints inferred from search annotations. */
    val searchHints: FlatZincSearchHints?,
    /** Enum labels preserved by the FlatZinc frontend. */
    val enumLabelsByVar: Map<String, List<String>> = emptyMap(),
    /** Set variable layouts by name. */
    val setVarsByName: Map<String, SetVarLayout> = emptyMap(),
    /** Local-search incremental objective when available. */
    val lsObjective: IncrementalObjective? = null,
    /** Local-search definitional sweep when available. */
    val definitionalSweep: DefinitionalSweep? = null,
)

/** Bool-indicator layout of one FlatZinc set variable. */
data class SetVarLayout(
    /** Set the variable name. */
    val name: String,
    /** Sorted universe values. */
    val elements: IntArray,
    /** Indicator bool ids parallel to [elements]. */
    val indicatorBoolIds: IntArray,
) {
    init {
        require(elements.size == indicatorBoolIds.size) { "SetVarLayout: parallel arrays of unequal length" }
    }

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

        /** Float objective via bucketed int variable. */
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

            /** Float elements represented by bucket indices. */
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
