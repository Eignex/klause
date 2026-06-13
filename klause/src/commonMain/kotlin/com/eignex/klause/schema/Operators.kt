package com.eignex.klause.schema

import com.eignex.klause.model.And
import com.eignex.klause.model.AtLeast
import com.eignex.klause.model.AtMost
import com.eignex.klause.model.BoolExpr
import com.eignex.klause.model.BoolRef
import com.eignex.klause.model.BoolTerm
import com.eignex.klause.model.CardinalityExpr
import com.eignex.klause.model.Iff
import com.eignex.klause.model.Implies
import com.eignex.klause.model.Not
import com.eignex.klause.model.Or

/** Logical AND `this ∧ other`. */
infix fun BoolTerm.and(other: BoolTerm): BoolExpr {
    val l = toExpr()
    val r = other.toExpr()
    val children = mutableListOf<BoolExpr>()
    if (l is And) children.addAll(l.children) else children.add(l)
    if (r is And) children.addAll(r.children) else children.add(r)
    return And(children)
}

/** Logical OR `this ∨ other`. */
infix fun BoolTerm.or(other: BoolTerm): BoolExpr {
    val l = toExpr()
    val r = other.toExpr()
    val children = mutableListOf<BoolExpr>()
    if (l is Or) children.addAll(l.children) else children.add(l)
    if (r is Or) children.addAll(r.children) else children.add(r)
    return Or(children)
}

/** Material implication `this → other`. */
infix fun BoolTerm.implies(other: BoolTerm): BoolExpr = Implies(toExpr(), other.toExpr())

/** Bi-implication `this ↔ other`. */
infix fun BoolTerm.iff(other: BoolTerm): BoolExpr = Iff(toExpr(), other.toExpr())

/** Logical negation `¬this`. */
operator fun BoolTerm.not(): BoolExpr = when (val e = toExpr()) {
    is BoolRef -> e.copy(negated = !e.negated)
    is Not -> e.child
    else -> Not(e)
}

/** At most `k` of [terms] are true. */
fun atMost(k: Int, vararg terms: BoolTerm): BoolExpr = AtMost(terms.map { it.toExpr() }, k)

/** At least `k` of [terms] are true. */
fun atLeast(k: Int, vararg terms: BoolTerm): BoolExpr = AtLeast(terms.map { it.toExpr() }, k)

/** Between [min] and [max] of [terms] are true. */
fun cardinality(min: Int, max: Int, vararg terms: BoolTerm): BoolExpr =
    CardinalityExpr(terms.map { it.toExpr() }, min, max)
