package com.eignex.klause.compile

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.lowering.ALL_DIFFERENT_WITNESS_MIN_ARITY
import com.eignex.klause.lowering.allDifferentWindowSize
import com.eignex.klause.lowering.reifyAllDifferentWitness
import com.eignex.klause.model.AllDifferentOpt
import com.eignex.klause.model.And
import com.eignex.klause.model.BoolExpr
import com.eignex.klause.model.CircuitExpr
import com.eignex.klause.model.CumulativeExpr
import com.eignex.klause.model.CumulativeExprOpt
import com.eignex.klause.model.DisjunctiveExpr
import com.eignex.klause.model.DisjunctiveExprOpt
import com.eignex.klause.model.GccExprOpt
import com.eignex.klause.model.IntCmpOp
import com.eignex.klause.model.IntCompare
import com.eignex.klause.model.IntElement
import com.eignex.klause.model.IntExpr
import com.eignex.klause.model.IntIfThenElse
import com.eignex.klause.model.IntLit
import com.eignex.klause.model.IntRef
import com.eignex.klause.model.IntSum
import com.eignex.klause.model.NValueExprOpt
import com.eignex.klause.model.NValueMode
import com.eignex.klause.model.Not
import com.eignex.klause.model.Or
import com.eignex.klause.model.SubcircuitExpr
import com.eignex.util.collections.LongHashSet

/*
 * Reified global constraints return `b ↔ φ` by decomposing φ into [IntCompare], [And], [Or],
 * [IntIfThenElse] and [IntSum]. The primitive lowering supplies both directions of reification.
 * Native global propagators detect infeasibility but do not supply false-side witnesses, so only
 * top-level assertions use native factors through CompilerAssertions.
 */

private fun indicatorInt(cond: BoolExpr): IntExpr = IntIfThenElse(cond, IntLit(1), IntLit(0))

// The linear-size witness encoding when it applies, else pairwise NE.
internal fun Lowering.reifyAllDifferent(terms: List<IntExpr>): Int {
    witnessAllDifferent(terms)?.let { return it }
    val pairs = mutableListOf<BoolExpr>()
    for (i in terms.indices) {
        for (j in i + 1 until terms.size) {
            pairs += IntCompare(terms[i], IntCmpOp.NE, terms[j])
        }
    }
    return tseitinAnd(pairs)
}

internal fun Lowering.reifyAllDifferentOpt(expr: AllDifferentOpt): Int {
    val terms = expr.terms.map { lift(it) }
    val presentLits = IntArray(expr.presents.size) { lowerToLit(expr.presents[it]) }
    witnessAllDifferentOpt(terms, presentLits)?.let { return it }
    val pairs = mutableListOf<BoolExpr>()
    for (i in terms.indices) {
        for (j in i + 1 until terms.size) {
            pairs += Or(
                listOf(
                    Not(expr.presents[i]),
                    Not(expr.presents[j]),
                    IntCompare(terms[i], IntCmpOp.NE, terms[j]),
                ),
            )
        }
    }
    return tseitinAnd(pairs)
}

// Null unless the operands are distinct bare variables (the matching filter assumes distinct columns) over
// a value window the global can index.
private fun Lowering.witnessAllDifferent(terms: List<IntExpr>): Int? {
    if (terms.size < ALL_DIFFERENT_WITNESS_MIN_ARITY) return null
    if (!terms.all { it is IntRef }) return null
    val ids = IntArray(terms.size) { intVarOf((terms[it] as IntRef).name) }
    if (ids.toHashSet().size != ids.size) return null
    var lo = Long.MAX_VALUE
    var hi = Long.MIN_VALUE
    for (id in ids) {
        val d = intDomains[id]
        if (d.min < lo) lo = d.min
        if (d.max > hi) hi = d.max
    }
    val size = allDifferentWindowSize(lo, hi) ?: return null
    return reifyAllDifferentWitness(ids, lo, size) { min, max -> newIntVar(IntDomain(min, max)) }
}

// Presence folds into the value, since the reification already claims the global's presence literals: an
// absent position takes a position-unique sentinel above the window, so only present positions can collide.
private fun Lowering.witnessAllDifferentOpt(terms: List<IntExpr>, presentLits: IntArray): Int? {
    val n = terms.size
    if (n < ALL_DIFFERENT_WITNESS_MIN_ARITY) return null
    var lo = Long.MAX_VALUE
    var hi = Long.MIN_VALUE
    for (t in terms) {
        val d = domainOf(t)
        if (d.min < lo) lo = d.min
        if (d.max > hi) hi = d.max
    }
    // Sentinels in [hi + 1, hi + n] are IntLit values, so the window must fit in Int.
    if (hi > Int.MAX_VALUE - n.toLong() || lo < Int.MIN_VALUE.toLong()) return null
    val size = allDifferentWindowSize(lo, hi + n) ?: return null
    val ids = IntArray(n) { i -> presenceSubstituted(terms[i], presentLits[i], lo, hi + n, (hi + 1 + i).toInt()) }
    return reifyAllDifferentWitness(ids, lo, size) { min, max -> newIntVar(IntDomain(min, max)) }
}

// Both directions are pinned: a free absent case would let two absent positions fake a duplicate.
private fun Lowering.presenceSubstituted(
    term: IntExpr,
    presentLit: Int,
    windowLo: Long,
    windowHi: Long,
    sentinel: Int,
): Int {
    val name = newAuxIntVar(IntDomain(windowLo, windowHi))
    val ref = IntRef(name)
    val isTerm = reifyIntCompare(IntCompare(ref, IntCmpOp.EQ, term))
    val isSentinel = reifyIntCompare(IntCompare(ref, IntCmpOp.EQ, IntLit(sentinel)))
    factors += Clause(intArrayOf(Lit.negate(presentLit), isTerm))
    factors += Clause(intArrayOf(presentLit, isSentinel))
    return intVarOf(name)
}

// Sums the indicators `∃i (p_i ∧ x_i = v)` over the union domain and compares against `n`.
internal fun Lowering.reifyNValueOpt(expr: NValueExprOpt): Int {
    val unionSet = LongHashSet()
    for (i in expr.xs.indices) {
        val xLifted = lift(expr.xs[i])
        val d = domainOf(xLifted)
        for (v in d.min..d.max) unionSet.add(v)
    }
    // Ascending order keeps the lowering deterministic.
    val perValue: List<IntExpr> = unionSet.toLongArray().sorted().map { v ->
        val anyHolds = expr.xs.indices.map { i ->
            And(listOf(expr.presents[i], IntCompare(expr.xs[i], IntCmpOp.EQ, IntLit(v.toInt()))))
        }
        indicatorInt(if (anyHolds.size == 1) anyHolds[0] else Or(anyHolds))
    }
    val sum: IntExpr = if (perValue.size == 1) perValue[0] else IntSum(perValue)
    val op = when (expr.mode) {
        NValueMode.EQ -> IntCmpOp.EQ

        NValueMode.AT_LEAST -> IntCmpOp.LE // n ≤ |distinct|

        NValueMode.AT_MOST -> IntCmpOp.GE // n ≥ |distinct|
    }
    return reifyIntCompare(IntCompare(expr.n, op, sum))
}

// Per cover value: `low(k) ≤ Σ_i 1{p_i ∧ x_i = cover(k)} ≤ high(k)`.
internal fun Lowering.reifyGccOpt(expr: GccExprOpt): Int {
    val pieces = mutableListOf<BoolExpr>()
    for (k in expr.cover.indices) {
        val coverVal = expr.cover[k]
        val perI = expr.xs.indices.map { i ->
            indicatorInt(And(listOf(expr.presents[i], IntCompare(expr.xs[i], IntCmpOp.EQ, IntLit(coverVal)))))
        }
        val sum: IntExpr = if (perI.size == 1) perI[0] else IntSum(perI)
        pieces += IntCompare(sum, IntCmpOp.GE, IntLit(expr.low[k]))
        pieces += IntCompare(sum, IntCmpOp.LE, IntLit(expr.high[k]))
    }
    if (expr.closed) {
        // p_i → ∨_k x_i = cover(k).
        val coverSet = expr.cover.toSet()
        for (i in expr.xs.indices) {
            val inCover: BoolExpr = if (coverSet.size == 1) {
                IntCompare(expr.xs[i], IntCmpOp.EQ, IntLit(expr.cover[0]))
            } else {
                Or(expr.cover.map { IntCompare(expr.xs[i], IntCmpOp.EQ, IntLit(it)) })
            }
            pieces += Or(listOf(Not(expr.presents[i]), inCover))
        }
    }
    return tseitinAnd(pieces)
}

internal fun Lowering.reifyDisjunctive(expr: DisjunctiveExpr): Int {
    val pieces = mutableListOf<BoolExpr>()
    for (i in expr.starts.indices) {
        for (j in i + 1 until expr.starts.size) {
            val di = expr.durations[i]
            val dj = expr.durations[j]
            if (di == 0 || dj == 0) continue
            pieces += pairwiseNoOverlap(expr.starts[i], di, expr.starts[j], dj)
        }
    }
    return if (pieces.isEmpty()) trueLit() else tseitinAnd(pieces)
}

internal fun Lowering.reifyDisjunctiveOpt(expr: DisjunctiveExprOpt): Int {
    val pieces = mutableListOf<BoolExpr>()
    for (i in expr.starts.indices) {
        for (j in i + 1 until expr.starts.size) {
            val di = expr.durations[i]
            val dj = expr.durations[j]
            if (di == 0 || dj == 0) continue
            val noOverlap = pairwiseNoOverlap(expr.starts[i], di, expr.starts[j], dj)
            pieces += Or(listOf(Not(expr.presents[i]), Not(expr.presents[j]), noOverlap))
        }
    }
    return if (pieces.isEmpty()) trueLit() else tseitinAnd(pieces)
}

private fun pairwiseNoOverlap(si: IntExpr, di: Int, sj: IntExpr, dj: Int): BoolExpr = Or(
    listOf(
        IntCompare(IntSum(listOf(si, IntLit(di))), IntCmpOp.LE, sj),
        IntCompare(IntSum(listOf(sj, IntLit(dj))), IntCmpOp.LE, si),
    ),
)

// Time-tabling decomposition over the static horizon `[minEst, maxLct)`.
internal fun Lowering.reifyCumulative(expr: CumulativeExpr): Int =
    cumulativeTimeTabling(expr.starts, expr.durations, expr.resources, expr.capacity, presents = null)

internal fun Lowering.reifyCumulativeOpt(expr: CumulativeExprOpt): Int =
    cumulativeTimeTabling(expr.starts, expr.durations, expr.resources, expr.capacity, presents = expr.presents)

// Position-vector decomposition after Miller, Tucker and Zemlin, "Integer Programming Formulation of
// Traveling Salesman Problems", JACM 1960 (https://doi.org/10.1145/321043.321046): `succ` is a
// self-loop-free permutation whose walk from node 0 visits every node before returning.
internal fun Lowering.reifyCircuit(expr: CircuitExpr): Int {
    val n = expr.succ.size
    val offset = expr.valueOffset
    val pieces = mutableListOf<BoolExpr>()
    pieces += allDiffAsAnd(expr.succ)
    for (i in 0 until n) {
        pieces += IntCompare(expr.succ[i], IntCmpOp.NE, IntLit(i + offset))
    }
    if (n <= 1) return tseitinAnd(pieces)
    // Position zero is fixed; the remaining positions range over [1, n-1].
    val posVars: List<IntExpr> = (0 until n).map { i ->
        if (i == 0) {
            IntLit(0)
        } else {
            IntRef(newAuxIntVar(IntDomain(1L, (n - 1).toLong())))
        }
    }
    val nonZeroPos: List<IntExpr> = posVars.drop(1)
    if (nonZeroPos.size >= 2) pieces += allDiffAsAnd(nonZeroPos)
    // MTZ chain: each successor closes at node zero or advances its position by one.
    val posVarsAsList: List<IntExpr> = posVars
    val succIndex0: List<IntExpr> = expr.succ.map { s ->
        if (offset == 0) s else IntSum(listOf(s, IntLit(-offset)))
    }
    for (i in 0 until n) {
        val isClosing = IntCompare(expr.succ[i], IntCmpOp.EQ, IntLit(offset))
        val posAtSucc = IntElement(succIndex0[i], posVarsAsList)
        val advance = IntCompare(posAtSucc, IntCmpOp.EQ, IntSum(listOf(posVars[i], IntLit(1))))
        pieces += Or(listOf(isClosing, advance))
    }
    return tseitinAnd(pieces)
}

// A self-loop `succ(i) = i + offset` excludes node `i`; each node satisfies
// `excluded(i) ∨ pos(succ(i)) = pos(i) + 1 ∨ pos(succ(i)) = 0`.
internal fun Lowering.reifySubcircuit(expr: SubcircuitExpr): Int {
    val n = expr.succ.size
    val offset = expr.valueOffset
    val pieces = mutableListOf<BoolExpr>()
    pieces += allDiffAsAnd(expr.succ)
    if (n == 0) return tseitinAnd(pieces)
    val posVars: List<IntExpr> = (0 until n).map { IntRef(newAuxIntVar(IntDomain(0L, (n - 1).toLong()))) }
    val succIndex0: List<IntExpr> = expr.succ.map { s ->
        if (offset == 0) s else IntSum(listOf(s, IntLit(-offset)))
    }
    for (i in 0 until n) {
        val isExcluded = IntCompare(expr.succ[i], IntCmpOp.EQ, IntLit(i + offset))
        val posAtSucc = IntElement(succIndex0[i], posVars)
        val advance = IntCompare(posAtSucc, IntCmpOp.EQ, IntSum(listOf(posVars[i], IntLit(1))))
        val closing = IntCompare(posAtSucc, IntCmpOp.EQ, IntLit(0))
        pieces += Or(listOf(isExcluded, advance, closing))
    }
    return tseitinAnd(pieces)
}

private fun allDiffAsAnd(terms: List<IntExpr>): BoolExpr {
    if (terms.size < 2) return IntCompare(IntLit(0), IntCmpOp.EQ, IntLit(0))
    val pairs = mutableListOf<BoolExpr>()
    for (i in terms.indices) {
        for (j in i + 1 until terms.size) {
            pairs += IntCompare(terms[i], IntCmpOp.NE, terms[j])
        }
    }
    return if (pairs.size == 1) pairs[0] else And(pairs)
}

private fun Lowering.cumulativeTimeTabling(
    starts: List<IntExpr>,
    durations: List<Int>,
    resources: List<Int>,
    capacity: Int,
    presents: List<BoolExpr>?,
): Int {
    var horizonLo = Long.MAX_VALUE
    var horizonHi = Long.MIN_VALUE
    for (i in starts.indices) {
        val sLifted = lift(starts[i])
        val d = domainOf(sLifted)
        if (d.min < horizonLo) horizonLo = d.min
        val hi = d.max + durations[i]
        if (hi > horizonHi) horizonHi = hi
    }
    if (horizonLo >= horizonHi) return trueLit()
    val pieces = mutableListOf<BoolExpr>()
    for (t in horizonLo until horizonHi) {
        // Σ_i r_i · 1{start_i ≤ t < start_i + d_i ∧ p_i} ≤ capacity
        val terms = mutableListOf<IntExpr>()
        for (i in starts.indices) {
            val d = durations[i]
            val r = resources[i]
            if (d == 0 || r == 0) continue
            val runsAt = And(
                listOf(
                    IntCompare(starts[i], IntCmpOp.LE, IntLit(t.toInt())),
                    IntCompare(IntSum(listOf(starts[i], IntLit(d))), IntCmpOp.GT, IntLit(t.toInt())),
                ),
            )
            val gated: BoolExpr = if (presents == null) {
                runsAt
            } else {
                And(listOf(presents[i], runsAt))
            }
            terms += IntIfThenElse(gated, IntLit(r), IntLit(0))
        }
        if (terms.isEmpty()) continue
        val sum: IntExpr = if (terms.size == 1) terms[0] else IntSum(terms)
        pieces += IntCompare(sum, IntCmpOp.LE, IntLit(capacity))
    }
    return if (pieces.isEmpty()) trueLit() else tseitinAnd(pieces)
}
