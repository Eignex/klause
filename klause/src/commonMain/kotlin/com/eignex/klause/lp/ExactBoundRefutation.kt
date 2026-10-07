package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.abs
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.div
import com.eignex.klause.util.isZero
import com.eignex.klause.util.minus
import com.eignex.klause.util.negate
import com.eignex.klause.util.plus
import com.eignex.klause.util.rem
import com.eignex.klause.util.signum
import com.eignex.klause.util.times
import com.eignex.klause.util.unaryMinus

/**
 * Whether the constraint system is unsatisfiable over its **genuinely open** ranges, decided in exact
 * arithmetic.
 *
 * The LP refutation next door answers the same question, but it answers it in `Long` and `Double`: a
 * coefficient times an open bound is exactly the product that leaves 64 bits, and the build then throws
 * and the refutation is abandoned. That is the whole difficulty with an unbounded model — the values it
 * forces are large — so this pass runs first, in arithmetic that does not wrap.
 *
 * Two steps. An equality that *defines* an otherwise unbounded variable is substituted away first, which
 * collapses a chain like `y = 4x, z = 4y, z ≤ x` into the single row `15x ≤ 0`; then interval propagation
 * decides what is left. The order matters: on the chain itself propagation diverges, multiplying the
 * bound by 4 per link per round and never closing, while the eliminated form is refuted at a glance.
 *
 * Only the refuting direction is usable: `false` means "no conclusion", never "satisfiable". Every give-up
 * — a spent budget, a work cap, a system that stays open — answers `false`, so this only ever forfeits a
 * refutation and never invents one.
 */
internal fun exactBoundsInfeasible(
    openBounds: Array<OpenIntBounds>,
    constraints: List<Linear>,
    cancellation: Cancellation = Cancellation.Never,
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

/**
 * Whether an equality is unsatisfiable over the integers because its coefficients' common divisor does
 * not divide its right-hand side.
 *
 * This is the refutation that survives unbounded domains: `Σ cᵢxᵢ` is a multiple of `gcd(cᵢ)` whatever
 * the `xᵢ` are, so a right-hand side outside that lattice has no integer solution however large the
 * variables may grow. A relaxation cannot see it — such a system is usually feasible over the rationals —
 * and interval propagation cannot either, which leaves it to arithmetic on the coefficients themselves.
 *
 * Worth doing after substitution rather than before: eliminating a variable changes a row's coefficients,
 * so a contradiction can appear in a row that was clean in the original system.
 */
private fun divisibilityFails(row: Row): Boolean {
    if (!row.equality || row.coeffs.isEmpty()) return false
    var g = BIG_ZERO
    for (c in row.coeffs.values) g = gcd(g, c.abs())
    if (g.isZero() || g == BIG_ONE) return false
    return !(row.bound % g).isZero()
}

private tailrec fun gcd(a: BigInt, b: BigInt): BigInt = if (b.isZero()) a else gcd(b, a % b)

/** A row `Σ coeffs·vars ⟨op⟩ bound` in exact arithmetic, mutable so substitution can rewrite it. */
private class Row(val coeffs: MutableMap<Int, BigInt>, var bound: BigInt, val equality: Boolean)

/** The exact form of [f], or null when it is outside the fragment (a `≠` row constrains no interval). */
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

/**
 * Substitute away each variable that an equality *defines* and that carries no bound of its own.
 *
 * Both conditions are needed. A unit coefficient makes the definition exact over the integers, and open
 * bounds on both sides mean the variable states nothing that substituting it would discard — the rewritten
 * system is then equisatisfiable, not merely a relaxation.
 */
private fun eliminateOpenDefinitions(
    rows: MutableList<Row>,
    lo: Array<BigInt?>,
    hi: Array<BigInt?>,
    cancellation: Cancellation,
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

/**
 * Replace [target] everywhere by the value [definition] gives it. Returns false when the rewrite would
 * grow past the work cap, which abandons elimination with the rows already rewritten — still a sound
 * system, just a less reduced one.
 */
private fun substitute(rows: MutableList<Row>, definition: Row, target: Int, cancellation: Cancellation): Boolean {
    val c = definition.coeffs.getValue(target)
    // |c| = 1, so `target = (bound − rest) / c` is exact: scale by c itself rather than dividing.
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

/** Interval propagation to a fixpoint; true as soon as a domain comes out empty. */
private fun propagateToEmpty(
    rows: List<Row>,
    lo: Array<BigInt?>,
    hi: Array<BigInt?>,
    cancellation: Cancellation,
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

/** One row's tightening pass; returns true as soon as a domain comes out empty. */
private inline fun tightenRow(
    row: Row,
    lo: Array<BigInt?>,
    hi: Array<BigInt?>,
    cancellation: Cancellation,
    onChange: () -> Unit,
): Boolean {
    for ((j, c) in row.coeffs) {
        // Each term re-sums the rest of the row in big integers, so a wide row is quadratic and one row
        // alone can outlast the whole budget. Polled per term, which caps the uninterruptible unit at a
        // single pass over the row; the caller reads the early exit as "not refuted", the same answer an
        // exhausted propagation gives.
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

// Work units per big-integer term this refutation touches, against one simplex op: median time per unit over
// the calibration sample's routed models (60 MIPLIB 2017, 40 QF_LIA), ~4.3e-4 ms.
private const val EXACT_REFUTATION_WORK_WEIGHT = 161L

/** Enough sweeps for a long implication chain, few enough that a slowly-converging system still stops. */
private const val MAX_ROUNDS = 64

/** One definition per variable is the most that can be eliminated; the cap is a runaway backstop. */
private const val MAX_ELIMINATIONS = 4096

/** Substitution densifies rows; past this width the reduction costs more than the refutation is worth. */
private const val MAX_ROW_TERMS = 512

private fun floorDiv(a: BigInt, b: BigInt): BigInt {
    val q = a / b
    return if (a % b != BIG_ZERO && (a.signum() < 0) != (b.signum() < 0)) q - BIG_ONE else q
}

private fun ceilDiv(a: BigInt, b: BigInt): BigInt {
    val q = a / b
    return if (a % b != BIG_ZERO && (a.signum() < 0) == (b.signum() < 0)) q + BIG_ONE else q
}
