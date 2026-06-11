package com.eignex.klause.formats.flatzinc

import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.localsearch.DefinitionalSweep
import com.eignex.klause.solver.objective.IncrementalObjective

/**
 * Parsed FlatZinc file lifted into klause's [Problem] representation plus the metadata a
 * MiniZinc-backend solver needs to print results: the solve directive, variable-name maps
 * (so output items can address vars by their FlatZinc names), and bucketing info for any
 * float variables that were discretized into integer buckets.
 *
 * Build via [parseFlatZinc]; consume via [writeFlatZincSolution].
 */
data class FlatZincProgram(
    /** The compiled solver problem. */
    val problem: Problem,
    /** The parsed solve directive. */
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
     * Search recipe emitted alongside the joint GaussianXor system when the model carries
     * two or more xor constraints: branch the system's rare variables first so error-pattern
     * style decompositions fall out (see the compiler's xorSearchParams). Null when the model
     * has fewer than two xors. Callers running a portfolio can race it as an extra worker.
     */
    val xorSearchParams: BacktrackParams? = null,
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
     * objective), an [IncrementalObjective] that recomputes the
     * objective from the decision variables. This gives local search a real per-move gradient
     * a plain `minimizeInt(V)` [com.eignex.klause.solver.objective.LinearObjective] lacks (it only sees
     * `V` itself). `null` for satisfy models, bare-decision-var objectives, or cones with a
     * node shape the builder can't evaluate exactly. Intended for the **local-search** engine
     * only — complete/reference backends keep the [com.eignex.klause.solver.objective.LinearObjective].
     */
    val lsObjective: IncrementalObjective? = null,
    /**
     * The model-wide definitional DAG (every evaluable `defines_var` constraint, topologically
     * ordered) for the **local-search** engine's restart sweep: evaluate defined vars from the
     * free decision vars instead of searching them. `null` when the model has no evaluable
     * definitions. See [com.eignex.klause.solver.localsearch.DefinitionalSweep].
     */
    val definitionalSweep: DefinitionalSweep? = null,
)

/** Bool-indicator decomposition of a `var set of E` declaration. Element values are stored
 *  in ascending order; [indicatorBoolIds] is parallel — `indicatorBoolIds[i]` is the bool
 *  var whose value tracks `elements[i] ∈ S`. */
data class SetVarLayout(
    /** The set variable's name. */
    val name: String,
    /** Universe element values, ascending. */
    val elements: IntArray,
    /** Indicator bool ids parallel to [elements]. */
    val indicatorBoolIds: IntArray,
) {
    init {
        require(elements.size == indicatorBoolIds.size) { "SetVarLayout: parallel arrays of unequal length" }
    }

    /** Number of universe elements. */
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
    /** Find any feasible solution. */
    data object Satisfy : SolveDirective

    /** Minimise [objVar]. */
    data class Minimize(
        /** Objective variable name. */
        val objVar: String,
        /** Kind of the objective variable. */
        val kind: ObjKind,
    ) : SolveDirective

    /** Maximise [objVar]. */
    data class Maximize(
        /** Objective variable name. */
        val objVar: String,
        /** Kind of the objective variable. */
        val kind: ObjKind,
    ) : SolveDirective

    /** Distinguishes the var kind so the writer / solver can pick the right resolution path. */
    enum class ObjKind {
        /** Boolean objective. */
        Bool,

        /** Integer objective. */
        Int,

        /** Float (bucketed) objective. */
        Float,
    }
}

/**
 * Float variables are discretized into integer buckets in `[0, buckets-1]`. The continuous
 * value for bucket `i` is `lo + i * (hi - lo) / (buckets - 1)`. Used by the writer to print
 * back the float value of a solved bucket index.
 */
data class FloatBucketing(
    /** Backing integer (bucket-index) variable id. */
    val varId: Int,
    /** Inclusive lower real bound. */
    val lo: Double,
    /** Inclusive upper real bound. */
    val hi: Double,
    /** Number of buckets. */
    val buckets: Int,
) {
    /** Real value for [bucketIndex]. */
    fun valueOf(bucketIndex: Int): Double = if (buckets <= 1) {
        lo
    } else {
        lo + bucketIndex * (hi - lo) / (buckets - 1)
    }
}

/** Captures arrays declared in the FlatZinc file (parameter or variable arrays). */
sealed interface FlatZincArray {
    /** The array's name. */
    val name: String

    /** Number of elements. */
    val length: Int

    /** Parameter array: every element is a constant. */
    data class BoolParam(
        override val name: String,
        /** The constant Boolean values. */
        val values: BooleanArray,
    ) : FlatZincArray {
        override val length: Int get() = values.size
    }

    /** Integer parameter array. */
    data class IntParam(
        override val name: String,
        /** The constant integer values. */
        val values: IntArray,
    ) : FlatZincArray {
        override val length: Int get() = values.size
    }

    /** Float parameter array. */
    data class FloatParam(
        override val name: String,
        /** The constant float values. */
        val values: DoubleArray,
    ) : FlatZincArray {
        override val length: Int get() = values.size
    }

    /** Parameter array of set-of-int constants. Each `values[i]` is a sorted int array
     *  giving the elements of the i-th set. Read by `array_set_element` to materialise
     *  the per-universe-element selection mask. */
    data class IntSetParam(
        override val name: String,
        /** Each element is the sorted int elements of one set. */
        val values: List<IntArray>,
    ) : FlatZincArray {
        override val length: Int get() = values.size
    }

    /** Variable array: each element is a klause var id. `elementKind` says how to read it. */
    data class Vars(
        override val name: String,
        /** klause variable ids per element. */
        val varIds: IntArray,
        /** How to read each element. */
        val elementKind: ElementKind,
        /** For float arrays, per-element bucketing (parallel to [varIds]). */
        val floatBucketings: List<FloatBucketing>? = null,
    ) : FlatZincArray {
        override val length: Int get() = varIds.size

        /** Element kind of a variable array. */
        enum class ElementKind {
            /** Boolean element. */
            Bool,

            /** Integer element. */
            Int,

            /** Float (bucketed) element. */
            Float,
        }
    }

    /** Array of set vars: each element is its own [SetVarLayout] (bool-indicator
     *  decomposition). `all_disjoint`, `set_partition_into`, etc. dispatch through this. */
    data class SetVars(
        override val name: String,
        /** Per-element set-var layouts. */
        val layouts: List<SetVarLayout>,
    ) : FlatZincArray {
        override val length: Int get() = layouts.size
    }
}

/**
 * One item in a FlatZinc `output [ ... ]` declaration. Solution writer evaluates these in
 * order against a solved [com.eignex.klause.solver.Sample] and concatenates the text.
 */
sealed interface OutputItem {
    /** Quoted string from the output literal — emitted as-is. */
    data class Literal(
        /** The literal text, emitted as-is. */
        val text: String,
    ) : OutputItem

    /** `show(x)` for a scalar variable. The writer looks up `name` in the program's var maps. */
    data class ShowVar(
        /** Name of the variable to show. */
        val name: String,
    ) : OutputItem

    /** `show(arr)` for an array — writer formats as `[a, b, c, ...]`. */
    data class ShowArray(
        /** Name of the array to show. */
        val name: String,
    ) : OutputItem
}
