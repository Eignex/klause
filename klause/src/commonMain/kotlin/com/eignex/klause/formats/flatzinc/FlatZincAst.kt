package com.eignex.klause.formats.flatzinc

/**
 * Internal AST produced by [FlatZincParser]. The compiler ([FlatZincCompiler]) walks this
 * to build a klause [com.eignex.klause.solver.Problem].
 */

internal sealed interface FznType {
    data object Bool : FznType
    /** Unbounded int variable. */
    data object IntAny : FznType
    /** Integer in `[lo, hi]`. */
    data class IntRange(val lo: Long, val hi: Long) : FznType
    /** Integer drawn from a finite set (e.g. `{1, 3, 5}`). */
    data class IntSet(val values: LongArray) : FznType
    /** Unbounded float. */
    data object FloatAny : FznType
    /** Float in `[lo, hi]`. */
    data class FloatRange(val lo: Double, val hi: Double) : FznType
    /** Array of fixed length over an element type. [elementIsVar] is `true` when the
     *  declaration spelled `array [...] of var T` (the array structure is fixed but
     *  each element binds to a solver variable), `false` for `array [...] of T`. */
    data class Array(val length: Int, val element: FznType, val elementIsVar: Boolean = false) : FznType
}

internal sealed interface FznExpr {
    data class BoolLit(val value: Boolean) : FznExpr
    data class IntLit(val value: Long) : FznExpr
    data class FloatLit(val value: Double) : FznExpr
    data class StringLit(val value: String) : FznExpr
    data class Ident(val name: String) : FznExpr
    data class ArrayAccess(val name: String, val index: Int) : FznExpr
    data class ArrayLit(val elements: List<FznExpr>) : FznExpr
    data class IntSetLit(val values: LongArray) : FznExpr
    data class IntRangeLit(val lo: Long, val hi: Long) : FznExpr
    /** Annotation expression `name(arg1, arg2, ...)` — appears inside annotation lists. */
    data class AnnCall(val name: String, val args: List<FznExpr>) : FznExpr
}

internal data class FznAnnotation(val name: String, val args: List<FznExpr>)

internal data class FznVarDecl(
    val name: String,
    val type: FznType,
    /** `true` for `var T`, `false` for parameter (constant) declarations. */
    val isVar: Boolean,
    val annotations: List<FznAnnotation>,
    /** Initialization expression. For parameters this is the constant value. For vars it
     *  can be an alias of another var (e.g. `var int: y :: ... = x;`). */
    val value: FznExpr?,
)

internal data class FznConstraint(
    val name: String,
    val args: List<FznExpr>,
    val annotations: List<FznAnnotation>,
)

internal sealed interface FznSolve {
    val annotations: List<FznAnnotation>
    data class Satisfy(override val annotations: List<FznAnnotation>) : FznSolve
    data class Minimize(override val annotations: List<FznAnnotation>, val obj: FznExpr) : FznSolve
    data class Maximize(override val annotations: List<FznAnnotation>, val obj: FznExpr) : FznSolve
}

internal data class FznModel(
    val varDecls: List<FznVarDecl>,
    val constraints: List<FznConstraint>,
    val solve: FznSolve,
    /** Raw `output [...]` expression list; `null` if there's no output item. */
    val output: List<FznExpr>?,
)
