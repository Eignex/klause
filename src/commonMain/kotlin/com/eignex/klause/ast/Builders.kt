package com.eignex.klause.ast

fun min(vararg xs: IntTerm): IntExpr {
    require(xs.isNotEmpty()) { "min(): need at least one argument" }
    return IntMin(xs.map { it.toIntExpr() })
}

fun max(vararg xs: IntTerm): IntExpr {
    require(xs.isNotEmpty()) { "max(): need at least one argument" }
    return IntMax(xs.map { it.toIntExpr() })
}

fun abs(x: IntTerm): IntExpr = IntAbs(x.toIntExpr())

fun ifThenElse(cond: BoolTerm, thenE: IntTerm, elseE: IntTerm): IntExpr =
    IntIfThenElse(cond.toExpr(), thenE.toIntExpr(), elseE.toIntExpr())

fun element(index: IntTerm, items: List<IntTerm>): IntExpr {
    require(items.isNotEmpty()) { "element(): items must not be empty" }
    return IntElement(index.toIntExpr(), items.map { it.toIntExpr() })
}

fun allDifferent(vararg xs: IntTerm): BoolExpr {
    require(xs.size >= 2) { "allDifferent(): need at least two terms" }
    return AllDifferent(xs.map { it.toIntExpr() })
}

/**
 * Channel an integer variable to a list of Boolean indicators: `bools[i] iff (intVar = offset+i)`
 * for every index `i`. Useful for switching between an integer and a one-hot Boolean encoding.
 */
fun channel(intVar: IntTerm, bools: List<BoolTerm>, offset: Int = 0): BoolExpr {
    require(bools.isNotEmpty()) { "channel(): need at least one indicator" }
    val intExpr = intVar.toIntExpr()
    val pieces = bools.mapIndexed { i, b ->
        Iff(b.toExpr(), IntCompare(intExpr, IntCmpOp.EQ, IntLit(offset + i)))
    }
    return if (pieces.size == 1) pieces[0] else And(pieces)
}
