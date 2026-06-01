package com.eignex.klause.formats.flatzinc

import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams

/**
 * Parsed FlatZinc file lifted into klause's [Problem] representation plus the metadata a
 * MiniZinc-backend solver needs to print results: the solve directive, variable-name maps
 * (so output items can address vars by their FlatZinc names), and bucketing info for any
 * float variables that were discretized into integer buckets.
 *
 * Build via [parseFlatZinc]; consume via [writeFlatZincSolution].
 */
data class FlatZincProgram(
    val problem: Problem,
    val solve: SolveDirective,
    /** FlatZinc bool-var name → klause bool var id. */
    val boolVarsByName: Map<String, Int>,
    /** FlatZinc int-var name → klause int var id. */
    val intVarsByName: Map<String, Int>,
    /**
     * FlatZinc float-var name → bucketing record. The actual klause var is an int (bucket
     * index); [FloatBucketing] tells [writeFlatZincSolution] how to recover the real value.
     */
    val floatVarsByName: Map<String, FloatBucketing>,
    /** Array names → element kind + (for var-arrays) klause var ids. */
    val arraysByName: Map<String, FlatZincArray>,
    /** Output items in order; null when the file had no `output` declaration. */
    val outputItems: List<OutputItem>?,
    /**
     * Suggested [BacktrackParams] derived from the `solve :: int_search(...) / bool_search(...)`
     * annotation, or `null` if no recognised search annotation was present. Callers using
     * `BacktrackSolver` can pass this directly to honour the FlatZinc author's intended
     * search strategy; everyone else can ignore it.
     */
    val defaultBacktrackParams: BacktrackParams?,
    /**
     * For int vars that originated as MiniZinc enums, the ordered label list — index `i` (0-based)
     * corresponds to the FlatZinc integer value `i + 1`. Empty when no enum metadata was carried
     * through the FZN file.
     *
     * MiniZinc strips enum tag names when lowering to FZN; the standard recovery route is the
     * paired `.ozn` mapping file (not yet parsed). As a stopgap, the klause MZN library can
     * emit a `klause_enum_labels(["Red","Green","Blue"])` annotation on each enum-typed var
     * decl; the parser preserves those into this map so downstream tooling can decode integer
     * solutions back to enum tags.
     */
    val enumLabelsByVar: Map<String, List<String>> = emptyMap(),
    /**
     * For each `var set of E: S` declaration, the bool-indicator decomposition. klause has
     * no native set-domain type — every set var is materialised as one indicator bool per
     * universe element. `setVarsByName["S"].elements[i]` is the integer value of element `i`;
     * `setVarsByName["S"].indicatorBoolIds[i]` is the klause bool var that's `true` iff that
     * element is in the set. The writer reconstructs `{e1, e2, ...}` MiniZinc output by
     * walking these in tandem.
     */
    val setVarsByName: Map<String, SetVarLayout> = emptyMap(),
    /**
     * For an optimization model whose objective variable is *functionally defined* by a cone of
     * `defines_var` constraints (abs / max / min / linear aux vars — the usual decomposed
     * objective), an [com.eignex.klause.solver.IncrementalObjective] that recomputes the
     * objective from the decision variables. This gives local search a real per-move gradient
     * a plain `minimizeInt(V)` [com.eignex.klause.solver.LinearObjective] lacks (it only sees
     * `V` itself). `null` for satisfy models, bare-decision-var objectives, or cones with a
     * node shape the builder can't evaluate exactly. Intended for the **local-search** engine
     * only — complete/reference backends keep the [com.eignex.klause.solver.LinearObjective].
     */
    val lsObjective: com.eignex.klause.solver.IncrementalObjective? = null,
)

/** Bool-indicator decomposition of a `var set of E` declaration. Element values are stored
 *  in ascending order; [indicatorBoolIds] is parallel — `indicatorBoolIds[i]` is the bool
 *  var whose value tracks `elements[i] ∈ S`. */
data class SetVarLayout(val name: String, val elements: IntArray, val indicatorBoolIds: IntArray) {
    init {
        require(elements.size == indicatorBoolIds.size) { "SetVarLayout: parallel arrays of unequal length" }
    }
    val universeSize: Int get() = elements.size

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

/** Top-level solve directive parsed from `solve satisfy ;` / `solve minimize x ;` etc. */
sealed interface SolveDirective {
    data object Satisfy : SolveDirective
    data class Minimize(val objVar: String, val kind: ObjKind) : SolveDirective
    data class Maximize(val objVar: String, val kind: ObjKind) : SolveDirective

    /** Distinguishes the var kind so the writer / solver can pick the right resolution path. */
    enum class ObjKind { Bool, Int, Float }
}

/**
 * Float variables are discretized into integer buckets in `[0, buckets-1]`. The continuous
 * value for bucket `i` is `lo + i * (hi - lo) / (buckets - 1)`. Used by the writer to print
 * back the float value of a solved bucket index.
 */
data class FloatBucketing(val varId: Int, val lo: Double, val hi: Double, val buckets: Int) {
    fun valueOf(bucketIndex: Int): Double = if (buckets <= 1) {
        lo
    } else {
        lo + bucketIndex * (hi - lo) / (buckets - 1)
    }
}

/** Captures arrays declared in the FlatZinc file (parameter or variable arrays). */
sealed interface FlatZincArray {
    val name: String
    val length: Int

    /** Parameter array: every element is a constant. */
    data class BoolParam(override val name: String, val values: BooleanArray) : FlatZincArray {
        override val length: Int get() = values.size
    }
    data class IntParam(override val name: String, val values: IntArray) : FlatZincArray {
        override val length: Int get() = values.size
    }
    data class FloatParam(override val name: String, val values: DoubleArray) : FlatZincArray {
        override val length: Int get() = values.size
    }

    /** Parameter array of set-of-int constants. Each `values[i]` is a sorted int array
     *  giving the elements of the i-th set. Read by `array_set_element` to materialise
     *  the per-universe-element selection mask. */
    data class IntSetParam(override val name: String, val values: List<IntArray>) : FlatZincArray {
        override val length: Int get() = values.size
    }

    /** Variable array: each element is a klause var id. `elementKind` says how to read it. */
    data class Vars(
        override val name: String,
        val varIds: IntArray,
        val elementKind: ElementKind,
        /** For float arrays, per-element bucketing (parallel to [varIds]). */
        val floatBucketings: List<FloatBucketing>? = null,
    ) : FlatZincArray {
        override val length: Int get() = varIds.size
        enum class ElementKind { Bool, Int, Float }
    }

    /** Array of set vars: each element is its own [SetVarLayout] (bool-indicator
     *  decomposition). `all_disjoint`, `set_partition_into`, etc. dispatch through this. */
    data class SetVars(override val name: String, val layouts: List<SetVarLayout>) : FlatZincArray {
        override val length: Int get() = layouts.size
    }
}

/**
 * One item in a FlatZinc `output [ ... ]` declaration. Solution writer evaluates these in
 * order against a solved [com.eignex.klause.solver.Sample] and concatenates the text.
 */
sealed interface OutputItem {
    /** Quoted string from the output literal — emitted as-is. */
    data class Literal(val text: String) : OutputItem

    /** `show(x)` for a scalar variable. The writer looks up `name` in the program's var maps. */
    data class ShowVar(val name: String) : OutputItem

    /** `show(arr)` for an array — writer formats as `[a, b, c, ...]`. */
    data class ShowArray(val name: String) : OutputItem
}
