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
    // Pigeonhole guard: if all operands are bare schema handles, check that the union of their
    // domains is large enough to host one distinct value per term.
    val handles = xs.mapNotNull { it as? com.eignex.klause.schema.IntHandle }
    if (handles.size == xs.size) {
        val unionSize = unionDomainSize(handles.map { it.min to it.max })
        require(unionSize >= xs.size) {
            "allDifferent: union of operand domains has only $unionSize distinct values, " +
                "cannot host ${xs.size} all-different terms (pigeonhole UNSAT)."
        }
    }
    return AllDifferent(xs.map { it.toIntExpr() })
}

private fun unionDomainSize(ranges: List<Pair<Int, Int>>): Long {
    if (ranges.isEmpty()) return 0L
    val sorted = ranges.sortedBy { it.first }
    var total = 0L
    var curLo = sorted[0].first
    var curHi = sorted[0].second
    for (i in 1 until sorted.size) {
        val (lo, hi) = sorted[i]
        if (lo <= curHi + 1) {
            if (hi > curHi) curHi = hi
        } else {
            total += (curHi - curLo + 1).toLong()
            curLo = lo; curHi = hi
        }
    }
    total += (curHi - curLo + 1).toLong()
    return total
}

/**
 * N-ary XOR. Evaluates to true iff an odd number of children are true.
 */
fun xor(vararg children: BoolTerm): BoolExpr {
    require(children.isNotEmpty()) { "xor: need at least one child" }
    return XorExpr(children.map { it.toExpr() })
}

/**
 * Pseudo-Boolean weighted-sum constraint: `Σ wᵢ * lᵢ ⟨op⟩ bound` over Boolean literals.
 * Each literal contributes its weight when true, 0 when false.
 */
fun pseudoBoolean(weights: List<Int>, lits: List<BoolTerm>, op: PbOp, bound: Int): BoolExpr {
    require(weights.size == lits.size) { "pseudoBoolean: weights/lits length mismatch" }
    require(lits.isNotEmpty()) { "pseudoBoolean: need at least one literal" }
    return PseudoBooleanExpr(weights, lits.map { it.toExpr() }, op, bound)
}

fun pbAtMost(weights: List<Int>, lits: List<BoolTerm>, k: Int): BoolExpr =
    pseudoBoolean(weights, lits, PbOp.LE, k)

fun pbAtLeast(weights: List<Int>, lits: List<BoolTerm>, k: Int): BoolExpr =
    pseudoBoolean(weights, lits, PbOp.GE, k)

fun pbExactly(weights: List<Int>, lits: List<BoolTerm>, k: Int): BoolExpr =
    pseudoBoolean(weights, lits, PbOp.EQ, k)

/**
 * Extensional positive table: `vars` must equal one of the listed `allowed` tuples.
 */
fun table(vars: List<IntTerm>, allowed: List<List<Int>>): BoolExpr {
    validateTableTuples(vars, allowed)
    return TableConstraint(vars.map { it.toIntExpr() }, allowed, negative = false)
}

/**
 * Extensional negative table: `vars` must not equal any of the listed `forbidden` tuples.
 */
fun notTable(vars: List<IntTerm>, forbidden: List<List<Int>>): BoolExpr {
    validateTableTuples(vars, forbidden)
    return TableConstraint(vars.map { it.toIntExpr() }, forbidden, negative = true)
}

private fun validateTableTuples(vars: List<IntTerm>, tuples: List<List<Int>>) {
    require(vars.isNotEmpty()) { "table: vars must not be empty" }
    require(tuples.isNotEmpty()) { "table: tuples must not be empty" }
    require(tuples.all { it.size == vars.size }) {
        "table: every tuple must have arity ${vars.size}"
    }
    for ((i, term) in vars.withIndex()) {
        val handle = term as? com.eignex.klause.schema.IntHandle ?: continue
        for ((tIdx, tup) in tuples.withIndex()) {
            val v = tup[i]
            require(v in handle.min..handle.max) {
                "table: tuple #$tIdx value $v at position $i is outside operand " +
                    "${handle.name}'s domain [${handle.min}..${handle.max}]"
            }
        }
    }
}

/**
 * Global cardinality constraint. For each `(value, range)` entry, the count of `vars` taking
 * that value must lie inside `range`. Decomposes into one [CardinalityExpr] per entry.
 */
fun gcc(vars: List<IntTerm>, valueCounts: Map<Int, IntRange>): BoolExpr {
    require(vars.isNotEmpty()) { "gcc(): vars must not be empty" }
    require(valueCounts.isNotEmpty()) { "gcc(): valueCounts must not be empty" }
    val parts = valueCounts.map { (value, range) ->
        require(range.first <= range.last) { "gcc: invalid range for value $value: $range" }
        require(range.first >= 0) { "gcc: count range first must be >= 0 for value $value: $range" }
        require(range.last <= vars.size) {
            "gcc: count range last ($range) exceeds number of vars (${vars.size}) for value $value"
        }
        val children = vars.map { v -> IntCompare(v.toIntExpr(), IntCmpOp.EQ, IntLit(value)) }
        CardinalityExpr(children, range.first, range.last)
    }
    return if (parts.size == 1) parts[0] else And(parts)
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
