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
)

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
data class FloatBucketing(
    val varId: Int,
    val lo: Double,
    val hi: Double,
    val buckets: Int,
) {
    fun valueOf(bucketIndex: Int): Double =
        if (buckets <= 1) lo
        else lo + bucketIndex * (hi - lo) / (buckets - 1)
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
