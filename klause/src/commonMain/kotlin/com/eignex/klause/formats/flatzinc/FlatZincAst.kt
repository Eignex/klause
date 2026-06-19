package com.eignex.klause.formats.flatzinc

/** Internal AST from [FlatZincParser]. */

internal sealed interface FznType {
    data object Bool : FznType

    data object IntAny : FznType

    data class IntRange(val lo: Long, val hi: Long) : FznType

    data class IntSet(val values: LongArray) : FznType

    data object FloatAny : FznType

    data class FloatRange(val lo: Double, val hi: Double) : FznType

    data class SetOfInt(val element: FznType) : FznType

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

    data class AnnCall(val name: String, val args: List<FznExpr>) : FznExpr
}

internal data class FznAnnotation(val name: String, val args: List<FznExpr>)

internal data class FznVarDecl(
    val name: String,
    val type: FznType,
    val isVar: Boolean,
    val annotations: List<FznAnnotation>,
    val value: FznExpr?,
)

internal data class FznConstraint(val name: String, val args: List<FznExpr>, val annotations: List<FznAnnotation>)

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
    val output: List<FznExpr>?,
)
