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
 * Lexicographic less-or-equal: `a` is no greater than `b` when read left-to-right. Empty lists
 * are equal (true). Lists must be the same length.
 */
fun lexLeq(a: List<IntTerm>, b: List<IntTerm>): BoolExpr = lexChain(a, b, strict = false)

/** Strict lexicographic less-than. */
fun lexLt(a: List<IntTerm>, b: List<IntTerm>): BoolExpr = lexChain(a, b, strict = true)

private fun lexChain(a: List<IntTerm>, b: List<IntTerm>, strict: Boolean): BoolExpr {
    require(a.size == b.size) { "lex: lists must have equal length, got ${a.size} and ${b.size}" }
    require(a.isNotEmpty()) { "lex: empty lists are not supported" }
    val ax = a.map { it.toIntExpr() }
    val bx = b.map { it.toIntExpr() }
    // Tail recursion: lex(a, b) ⟺ a[0] < b[0] ∨ (a[0] = b[0] ∧ lex(a[1..], b[1..])).
    // Base case at the last index: <= becomes a[k] ≤ b[k], < becomes a[k] < b[k].
    var result: BoolExpr = if (strict) {
        IntCompare(ax.last(), IntCmpOp.LT, bx.last())
    } else {
        IntCompare(ax.last(), IntCmpOp.LE, bx.last())
    }
    for (i in ax.size - 2 downTo 0) {
        val less = IntCompare(ax[i], IntCmpOp.LT, bx[i])
        val equal = IntCompare(ax[i], IntCmpOp.EQ, bx[i])
        result = Or(listOf(less, And(listOf(equal, result))))
    }
    return result
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
