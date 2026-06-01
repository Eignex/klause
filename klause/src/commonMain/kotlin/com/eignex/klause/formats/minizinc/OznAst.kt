package com.eignex.klause.formats.minizinc

/**
 * Abstract syntax for `.ozn` files. The expression language is the subset of MiniZinc's
 * expression grammar that MiniZinc actually emits when rendering output items + the
 * variable declarations it pins to the .ozn (rather than the .fzn). Notable shape:
 *
 *  - top-level items are either [OznItem.VarDecl] (`int: n = 4;`) or [OznItem.Output]
 *    (`output ["q = ", show(q), "\n"];`).
 *  - expressions include literals (int, float, bool, string), identifiers, ranges,
 *    arithmetic, comparisons, calls (`show`, `array2d`, `bool2int`, ...), array/set
 *    literals, array subscripts, conditionals (`if-then-elseif-else-endif`), and
 *    array/set comprehensions (`[expr | i in 1..n where p]`).
 *
 * Evaluated by [OznEvaluator] against a binding map produced by the FZN solver.
 */
internal sealed interface OznItem {
    data class VarDecl(val name: String, val type: OznType, val initializer: OznExpr?) : OznItem
    data class Output(val items: List<OznExpr>) : OznItem
}

/**
 * Type annotations declared in .ozn variable decls. MiniZinc keeps types minimal here —
 * just enough so [OznEvaluator] can resolve the FZN-side binding by name and reshape it
 * for the output expression (array2d unfolds a flat FZN array under a 2D MZN view, etc.).
 */
internal sealed interface OznType {
    data object Bool : OznType
    data object Int : OznType
    data object Float : OznType
    data object SetOfInt : OznType

    /** Array. Each entry in [indexRanges] is an [OznExpr] that evaluates to a range. */
    data class ArrayOf(val indexRanges: List<OznExpr>, val element: OznType) : OznType
}

internal sealed interface OznExpr {
    data class IntLit(val value: Long) : OznExpr
    data class FloatLit(val value: Double) : OznExpr
    data class BoolLit(val value: Boolean) : OznExpr
    data class StringLit(val value: String) : OznExpr
    data class Ident(val name: String) : OznExpr
    data class Range(val lo: OznExpr, val hi: OznExpr) : OznExpr

    /** `[ e1, e2, ... ]`. */
    data class ArrayLit(val elements: List<OznExpr>) : OznExpr

    /** `{ e1, e2, ... }`. */
    data class SetLit(val elements: List<OznExpr>) : OznExpr

    /** `[ body | i in r1, j in r2 where cond ]` or the `{ ... }` set form. */
    data class Comprehension(val body: OznExpr, val generators: List<Generator>, val isSet: Boolean) : OznExpr
    data class Generator(val names: List<String>, val source: OznExpr, val where: OznExpr?)

    /** Built-in function call: `show(x)`, `array2d(r1, r2, xs)`, `bool2int(b)`, etc. */
    data class Call(val name: String, val args: List<OznExpr>) : OznExpr

    /** Array subscript: `x[i]`, `x[i, j]`, `x[i, j, k]`. */
    data class Subscript(val target: OznExpr, val indices: List<OznExpr>) : OznExpr
    data class Unary(val op: String, val operand: OznExpr) : OznExpr
    data class Binary(val op: String, val left: OznExpr, val right: OznExpr) : OznExpr

    /** `if c then a [elseif c2 then a2]* else b endif`. */
    data class If(
        val branches: List<Pair<OznExpr, OznExpr>>, // (cond, then) including initial if
        val elseExpr: OznExpr,
    ) : OznExpr

    /** `let { decls } in body`. Local bindings within an expression. */
    data class Let(val decls: List<OznItem.VarDecl>, val body: OznExpr) : OznExpr
}
