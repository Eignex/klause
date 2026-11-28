package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.abs
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.ceilDiv
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.div
import com.eignex.util.numbers.floorDiv
import com.eignex.util.numbers.gcd
import com.eignex.util.numbers.isZero
import com.eignex.util.numbers.minus
import com.eignex.util.numbers.negate
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.remainderOf
import com.eignex.util.numbers.times
import com.eignex.util.numbers.unaryMinus

// Exact-arithmetic refutation over open ranges. The LP refutation works in `Long`/`Double`, and a
// coefficient times an open bound is exactly the product that overflows, so this runs first.
//
// Equalities defining an unbounded variable are substituted away first, collapsing `y = 4x, z = 4y, z ≤ x`
// into `15x ≤ 0`; then divisibility, then interval propagation. On the uncollapsed chain propagation
// diverges, multiplying the bound per link per round.
//
// `false` means "no conclusion", never "satisfiable": every give-up answers `false`.
internal fun exactBoundsInfeasible(
    openBounds: Array<OpenIntBounds>,
    constraints: List<Linear>,
    cancellation: ExecutionControl = ExecutionControl.Never,
): Boolean {
    val n = openBounds.size
    if (n == 0 || constraints.isEmpty()) return false
    val lo = arrayOfNulls<BigInt>(n)
    val hi = arrayOfNulls<BigInt>(n)
    for (v in 0 until n) {
        lo[v] = openBounds[v].lo?.let { bigIntOf(it) }
        hi[v] = openBounds[v].hi?.let { bigIntOf(it) }
    }
    val rows = constraints.mapNotNull { rowOf(it, n) }.toMutableList()
    if (rows.isEmpty()) return false
    eliminateOpenDefinitions(rows, lo, hi, cancellation)
    if (rows.any { divisibilityFails(it) }) return true
    return propagateToEmpty(rows, lo, hi, cancellation)
}

// `Σ c(i)·x(i)` is a multiple of `gcd(c)` however large the variables grow, so this survives unbounded
// domains where neither the rational relaxation nor interval propagation sees it. Run after substitution,
// which can expose a contradiction in a row that was clean before.
private fun divisibilityFails(row: Row): Boolean {
    if (!row.equality || row.coeffs.isEmpty()) return false
    var g = BIG_ZERO
    for (c in row.coeffs.values) g = g.gcd(c)
    if (g.isZero() || g == BIG_ONE) return false
    return !(row.bound.remainderOf(g)).isZero()
}

private class Row(val coeffs: MutableMap<Int, BigInt>, var bound: BigInt, val equality: Boolean)

private fun rowOf(f: Linear, n: Int): Row? {
    if (f.op != LinearOp.LE && f.op != LinearOp.EQ) return null
    val constants = f.integralConstants ?: return null
    val coeffs = HashMap<Int, BigInt>(f.vars.size)
    for (k in f.vars.indices) {
        val v = f.vars[k]
        if (v >= n) return null
        val c = constants.exactCoeff(k)
        if (!c.isZero()) coeffs[v] = (coeffs[v] ?: BIG_ZERO) + c
    }
    return Row(coeffs, constants.exactBound, f.op == LinearOp.EQ)
}

// A unit coefficient makes the definition exact over the integers, and an unbounded variable states
// nothing substitution would discard, so the rewritten system is equisatisfiable.
private fun eliminateOpenDefinitions(
    rows: MutableList<Row>,
    lo: Array<BigInt?>,
    hi: Array<BigInt?>,
    cancellation: ExecutionControl,
) {
    var budget = MAX_ELIMINATIONS
    while (budget-- > 0) {
        if (cancellation()) return
        var chosenRow = -1
        var chosenVar = -1
        outer@ for (r in rows.indices) {
            val row = rows[r]
            if (!row.equality) continue
            for ((v, c) in row.coeffs) {
                if (lo[v] != null || hi[v] != null) continue
                if (c.abs() != BIG_ONE) continue
                chosenRow = r
                chosenVar = v
                break@outer
            }
        }
        if (chosenRow < 0) return
        val definition = rows.removeAt(chosenRow)
        if (!substitute(rows, definition, chosenVar, cancellation)) return
    }
}

// False past the width cap; the rows already rewritten remain a sound, less reduced system.
private fun substitute(rows: MutableList<Row>, definition: Row, target: Int, cancellation: ExecutionControl): Boolean {
    val c = definition.coeffs.getValue(target)
    // |c| = 1, so dividing by c is multiplying by c.
    val scale = if (c > BIG_ZERO) BIG_ONE else BIG_ONE.negate()
    val valueBound = definition.bound * scale
    val valueTerms = HashMap<Int, BigInt>(definition.coeffs.size)
    for ((v, ci) in definition.coeffs) {
        if (v == target) continue
        valueTerms[v] = -ci * scale
    }
    cancellation.charge(EXACT_REFUTATION_WORK_WEIGHT * rows.size)
    for (row in rows) {
        val k = row.coeffs.remove(target) ?: continue
        cancellation.charge(EXACT_REFUTATION_WORK_WEIGHT * (1L + valueTerms.size))
        row.bound -= k * valueBound
        for ((v, ci) in valueTerms) {
            val merged = (row.coeffs[v] ?: BIG_ZERO) + k * ci
            if (merged.isZero()) row.coeffs.remove(v) else row.coeffs[v] = merged
        }
        if (row.coeffs.size > MAX_ROW_TERMS) return false
    }
    return true
}

private fun propagateToEmpty(
    rows: List<Row>,
    lo: Array<BigInt?>,
    hi: Array<BigInt?>,
    cancellation: ExecutionControl,
): Boolean {
    repeat(MAX_ROUNDS) {
        var changed = false
        for (row in rows) {
            if (cancellation()) return false
            if (tightenRow(row, lo, hi, cancellation) { changed = true }) return true
        }
        if (!changed) return false
    }
    return false
}

private inline fun tightenRow(
    row: Row,
    lo: Array<BigInt?>,
    hi: Array<BigInt?>,
    cancellation: ExecutionControl,
    onChange: () -> Unit,
): Boolean {
    for ((j, c) in row.coeffs) {
        // A wide row is quadratic in big integers and can outlast the whole budget alone, so poll per
        // term; an early exit reads as "not refuted".
        if (cancellation()) return false
        cancellation.charge(EXACT_REFUTATION_WORK_WEIGHT * row.coeffs.size)
        var restMin: BigInt? = BIG_ZERO
        var restMax: BigInt? = BIG_ZERO
        for ((i, ci) in row.coeffs) {
            if (i == j) continue
            val positive = ci > BIG_ZERO
            val low = if (positive) lo[i] else hi[i]
            val high = if (positive) hi[i] else lo[i]
            restMin = if (restMin == null || low == null) null else restMin + ci * low
            restMax = if (restMax == null || high == null) null else restMax + ci * high
        }
        // Σ c·x ⟨op⟩ bound isolates term j as c·xⱼ ≤ bound − restMin, and an equality also gives
        // c·xⱼ ≥ bound − restMax.
        val upper = restMin?.let { rest -> row.bound - rest }
        val lower = if (row.equality) restMax?.let { rest -> row.bound - rest } else null
        val positive = c > BIG_ZERO
        val newHi = if (positive) upper?.let { s -> floorDiv(s, c) } else lower?.let { s -> floorDiv(s, c) }
        val newLo = if (positive) lower?.let { s -> ceilDiv(s, c) } else upper?.let { s -> ceilDiv(s, c) }
        val curHi = hi[j]
        val curLo = lo[j]
        if (newHi != null && (curHi == null || newHi < curHi)) {
            hi[j] = newHi
            onChange()
        }
        if (newLo != null && (curLo == null || newLo > curLo)) {
            lo[j] = newLo
            onChange()
        }
        val l = lo[j]
        val h = hi[j]
        if (l != null && h != null && l > h) return true
    }
    return false
}

// Work units per big-integer term touched, relative to one simplex op.
private const val EXACT_REFUTATION_WORK_WEIGHT = 161L

// Enough sweeps for a long implication chain, few enough that a slowly-converging system still stops.
private const val MAX_ROUNDS = 64

// A runaway backstop: at most one definition per variable can be eliminated.
private const val MAX_ELIMINATIONS = 4096

// Substitution densifies rows; past this width the reduction costs more than the refutation is worth.
private const val MAX_ROW_TERMS = 512
