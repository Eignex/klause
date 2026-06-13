package com.eignex.klause.schema

import com.eignex.klause.model.BoolExpr
import com.eignex.klause.model.IntExpr
import com.eignex.klause.model.IntTerm
import com.eignex.klause.model.Not
import com.eignex.klause.model.SetCard
import com.eignex.klause.model.SetDiff
import com.eignex.klause.model.SetDisjoint
import com.eignex.klause.model.SetEq
import com.eignex.klause.model.SetExpr
import com.eignex.klause.model.SetIn
import com.eignex.klause.model.SetIntersect
import com.eignex.klause.model.SetLiteral
import com.eignex.klause.model.SetNominalIn
import com.eignex.klause.model.SetNominalLiteral
import com.eignex.klause.model.SetRef
import com.eignex.klause.model.SetSubsetOf
import com.eignex.klause.model.SetTerm
import com.eignex.klause.model.SetUnion

/**
 * Set variable handle over an integer universe. Internally lowers to one indicator
 * Boolean per universe element; the decoder reads those indicators back to a [Set]<Int>.
 *
 * Use the infix operators in `com.eignex.klause.model.SetOperators` (`inSet`, `subsetOf`,
 * `disjointFrom`, `union`, `intersect`, `diff`, `eq`, `ne`) and the [card] / [size]
 * function for cardinality. Set-valued combinators ([SetUnion] / [SetIntersect] /
 * [SetDiff] / [SetLiteral]) return a [SetExpr] tree that the compiler materialises into
 * fresh indicator bools at lowering time.
 */
class IntSetHandle(
    /** Name of the set variable. */
    val name: String,
    /** The integer universe the set draws from. */
    val universe: List<Int>,
) : SetTerm {
    override fun toSetExpr(): SetExpr = SetRef(name)
}

/**
 * Set variable over a nominal universe of labels. The label universe is fixed at
 * schema-construction time; the decoder returns a [Set]<String> of the labels currently
 * indicated.
 */
class NominalSetHandle(
    /** Name of the set variable. */
    val name: String,
    /** The nominal label universe the set draws from. */
    val labels: List<String>,
) : SetTerm {
    override fun toSetExpr(): SetExpr = SetRef(name)
}

/**
 * Free-standing constant set literal over an integer universe. Use [setOfInts] / [setOf]
 * to build one in DSL position — the result is a [SetTerm] that's accepted everywhere a
 * set-var handle is, so `x inSet setOfInts(1, 3, 5)` and `S subsetOf setOfInts(0..9)`
 * both work.
 */
class IntSetLiteralTerm(
    /** The literal set's elements. */
    val elements: List<Int>,
) : SetTerm {
    override fun toSetExpr(): SetExpr = SetLiteral(elements)
}

/** Free-standing constant set literal over a nominal universe (labels). */
class NominalSetLiteralTerm(
    /** The literal set's nominal labels. */
    val labels: List<String>,
) : SetTerm {
    override fun toSetExpr(): SetExpr = SetNominalLiteral(labels)
}

/** Builder for an int-set literal. Accepted in set-position throughout the DSL. */
fun setOfInts(vararg values: Int): IntSetLiteralTerm = IntSetLiteralTerm(values.toList())

/** Builder for an int-set literal over a contiguous range. */
fun setOfInts(range: IntRange): IntSetLiteralTerm = IntSetLiteralTerm(range.toList())

/** Builder for a nominal-set literal. Accepted everywhere a nominal-set handle is. */
fun setOfLabels(vararg labels: String): NominalSetLiteralTerm = NominalSetLiteralTerm(labels.toList())

// -----------------------------------------------------------------------------------
//  Set operators
// -----------------------------------------------------------------------------------
// Mirror the README's promised surface: inSet, subsetOf, disjointFrom, union, intersect,
// diff, eq, ne, card. Every operator is a thin AST constructor — the compiler does the
// actual lowering against the indicator-bool encoding.

/** `int ∈ set`. */
infix fun IntTerm.inSet(s: SetTerm): BoolExpr = SetIn(this.toIntExpr(), s.toSetExpr())

/** `label ∈ nominal-set`. The label must be in the operand set's nominal universe. */
infix fun String.inSet(s: SetTerm): BoolExpr = SetNominalIn(this, s.toSetExpr())

/** `S ⊆ T`. */
infix fun SetTerm.subsetOf(other: SetTerm): BoolExpr = SetSubsetOf(this.toSetExpr(), other.toSetExpr())

/** `S ∩ T = ∅`. */
infix fun SetTerm.disjointFrom(other: SetTerm): BoolExpr = SetDisjoint(this.toSetExpr(), other.toSetExpr())

/** `S = T`. */
infix fun SetTerm.eq(other: SetTerm): BoolExpr = SetEq(this.toSetExpr(), other.toSetExpr())

/** `S ≠ T`. */
infix fun SetTerm.ne(other: SetTerm): BoolExpr = Not(SetEq(this.toSetExpr(), other.toSetExpr()))

/** `S ∪ T`. Returns a set expression — usually consumed by [eq] / [subsetOf] / [card]. */
infix fun SetTerm.union(other: SetTerm): SetExpr = SetUnion(this.toSetExpr(), other.toSetExpr())

/** `S ∩ T`. Returns a set expression. */
infix fun SetTerm.intersect(other: SetTerm): SetExpr = SetIntersect(this.toSetExpr(), other.toSetExpr())

/** `S ∖ T` — set difference. Returns a set expression. */
infix fun SetTerm.diff(other: SetTerm): SetExpr = SetDiff(this.toSetExpr(), other.toSetExpr())

/** Cardinality `|S|`. */
fun card(s: SetTerm): IntExpr = SetCard(s.toSetExpr())

/** Alias for [card] matching the README's `size`-style naming used elsewhere. */
fun size(s: SetTerm): IntExpr = card(s)
