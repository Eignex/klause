package com.eignex.klause.formats.minizinc

/** AST for the `.ozn` subset used by MiniZinc solution output. */
internal sealed interface OznItem {
    data class VarDecl(val name: String, val type: OznType, val initializer: OznExpr?) : OznItem
    data class Output(val items: List<OznExpr>) : OznItem
}

/** Types used in `.ozn` variable declarations. */
internal sealed interface OznType {
    data object Bool : OznType
    data object Int : OznType
    data object Float : OznType
    data object SetOfInt : OznType

    /** Array type with explicit index ranges. */
    data class ArrayOf(val indexRanges: List<OznExpr>, val element: OznType) : OznType
}

internal sealed interface OznExpr {
    data class IntLit(val value: Long) : OznExpr
    data class FloatLit(val value: Double) : OznExpr
    data class BoolLit(val value: Boolean) : OznExpr
    data class StringLit(val value: String) : OznExpr
    data class Ident(val name: String) : OznExpr
    data class Range(val lo: OznExpr, val hi: OznExpr) : OznExpr

    /** Array literal. */
    data class ArrayLit(val elements: List<OznExpr>) : OznExpr

    /** Set literal. */
    data class SetLit(val elements: List<OznExpr>) : OznExpr

    /** Array or set comprehension. */
    data class Comprehension(val body: OznExpr, val generators: List<Generator>, val isSet: Boolean) : OznExpr
    data class Generator(val names: List<String>, val source: OznExpr, val where: OznExpr?)

    /** Function call expression. */
    data class Call(val name: String, val args: List<OznExpr>) : OznExpr

    /** Subscript expression. */
    data class Subscript(val target: OznExpr, val indices: List<OznExpr>) : OznExpr
    data class Unary(val op: String, val operand: OznExpr) : OznExpr
    data class Binary(val op: String, val left: OznExpr, val right: OznExpr) : OznExpr

    /** Conditional expression. */
    data class If(val branches: List<Pair<OznExpr, OznExpr>>, val elseExpr: OznExpr) : OznExpr

    /** Let-binding expression. */
    data class Let(val decls: List<OznItem.VarDecl>, val body: OznExpr) : OznExpr
}
