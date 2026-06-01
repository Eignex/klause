package com.eignex.klause.ast

operator fun IntTerm.plus(other: IntTerm): IntExpr = flattenSum(this.toIntExpr(), other.toIntExpr())
operator fun IntTerm.plus(c: Int): IntExpr = flattenSum(this.toIntExpr(), IntLit(c))
operator fun Int.plus(t: IntTerm): IntExpr = flattenSum(IntLit(this), t.toIntExpr())

operator fun IntTerm.minus(other: IntTerm): IntExpr = flattenSum(this.toIntExpr(), negate(other.toIntExpr()))
operator fun IntTerm.minus(c: Int): IntExpr = flattenSum(this.toIntExpr(), IntLit(-c))
operator fun Int.minus(t: IntTerm): IntExpr = flattenSum(IntLit(this), negate(t.toIntExpr()))

operator fun IntTerm.unaryMinus(): IntExpr = negate(this.toIntExpr())

operator fun IntTerm.times(c: Int): IntExpr = scale(c, this.toIntExpr())
operator fun Int.times(t: IntTerm): IntExpr = scale(this, t.toIntExpr())
operator fun IntTerm.times(other: IntTerm): IntExpr {
    val l = this.toIntExpr()
    val r = other.toIntExpr()
    if (l is IntLit) return scale(l.value, r)
    if (r is IntLit) return scale(r.value, l)
    return IntMul(l, r)
}

operator fun IntTerm.div(other: IntTerm): IntExpr = IntDiv(toIntExpr(), other.toIntExpr())
operator fun IntTerm.rem(other: IntTerm): IntExpr = IntMod(toIntExpr(), other.toIntExpr())

infix fun IntTerm.le(other: IntTerm): BoolExpr = IntCompare(toIntExpr(), IntCmpOp.LE, other.toIntExpr())
infix fun IntTerm.le(c: Int): BoolExpr = IntCompare(toIntExpr(), IntCmpOp.LE, IntLit(c))
infix fun Int.le(t: IntTerm): BoolExpr = IntCompare(IntLit(this), IntCmpOp.LE, t.toIntExpr())

infix fun IntTerm.lt(other: IntTerm): BoolExpr = IntCompare(toIntExpr(), IntCmpOp.LT, other.toIntExpr())
infix fun IntTerm.lt(c: Int): BoolExpr = IntCompare(toIntExpr(), IntCmpOp.LT, IntLit(c))
infix fun Int.lt(t: IntTerm): BoolExpr = IntCompare(IntLit(this), IntCmpOp.LT, t.toIntExpr())

infix fun IntTerm.ge(other: IntTerm): BoolExpr = IntCompare(toIntExpr(), IntCmpOp.GE, other.toIntExpr())
infix fun IntTerm.ge(c: Int): BoolExpr = IntCompare(toIntExpr(), IntCmpOp.GE, IntLit(c))
infix fun Int.ge(t: IntTerm): BoolExpr = IntCompare(IntLit(this), IntCmpOp.GE, t.toIntExpr())

infix fun IntTerm.gt(other: IntTerm): BoolExpr = IntCompare(toIntExpr(), IntCmpOp.GT, other.toIntExpr())
infix fun IntTerm.gt(c: Int): BoolExpr = IntCompare(toIntExpr(), IntCmpOp.GT, IntLit(c))
infix fun Int.gt(t: IntTerm): BoolExpr = IntCompare(IntLit(this), IntCmpOp.GT, t.toIntExpr())

infix fun IntTerm.eq(other: IntTerm): BoolExpr = IntCompare(toIntExpr(), IntCmpOp.EQ, other.toIntExpr())
infix fun IntTerm.eq(c: Int): BoolExpr = IntCompare(toIntExpr(), IntCmpOp.EQ, IntLit(c))
infix fun Int.eq(t: IntTerm): BoolExpr = IntCompare(IntLit(this), IntCmpOp.EQ, t.toIntExpr())

infix fun IntTerm.ne(other: IntTerm): BoolExpr = IntCompare(toIntExpr(), IntCmpOp.NE, other.toIntExpr())
infix fun IntTerm.ne(c: Int): BoolExpr = IntCompare(toIntExpr(), IntCmpOp.NE, IntLit(c))
infix fun Int.ne(t: IntTerm): BoolExpr = IntCompare(IntLit(this), IntCmpOp.NE, t.toIntExpr())

private fun flattenSum(left: IntExpr, right: IntExpr): IntExpr {
    val children = mutableListOf<IntExpr>()
    if (left is IntSum) children.addAll(left.children) else children.add(left)
    if (right is IntSum) children.addAll(right.children) else children.add(right)
    return IntSum(children)
}

private fun negate(expr: IntExpr): IntExpr = when (expr) {
    is IntLit -> IntLit(-expr.value)
    is IntScale -> IntScale(-expr.coeff, expr.child)
    else -> IntScale(-1, expr)
}

private fun scale(coeff: Int, expr: IntExpr): IntExpr {
    if (coeff == 0) return IntLit(0)
    if (coeff == 1) return expr
    return when (expr) {
        is IntLit -> IntLit(coeff * expr.value)
        is IntScale -> IntScale(coeff * expr.coeff, expr.child)
        else -> IntScale(coeff, expr)
    }
}
