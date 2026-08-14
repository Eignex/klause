package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.formats.WideIntColumns
import com.eignex.klause.formats.wideIntColumns
import com.eignex.klause.solver.Factor
import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * Lower a **declared** integer whose implied values leave the 64-bit range onto digit columns.
 *
 * The fresh quantities `abs`/`div`/`mod`/`ite` introduce already get digits (`SmtLibWide.kt`). A declared
 * variable is the other half: in `b = 8a, c = 8b, d = 8c, e = 8d` with `a ≥ 2^60`, every model has
 * `e ≥ 2^72`, so no assignment fits any `Long` domain and the search finds nothing to report. Substituting
 * `e` by `Σᵢ dᵢ·2^(width·i)` in the rows that mention it puts the value somewhere it fits, and the search
 * can find the model that was always there.
 *
 * A variable is substituted only when the model's own bounds put *every* value it can take outside the
 * 64-bit range — never merely because its range is wide. Digits cost the row its LP relaxation and its
 * deferred bound tightening, which is a bad trade wherever an ordinary domain would still have held a
 * solution; the point is to reach models that no `Long` domain can represent at all.
 *
 * The digit count comes from bound propagation over the model's own rows, started from the domains as the
 * fallback box left them. That makes this a way to *reach* the models the box admits, not a way to enlarge
 * the box: a side the box invented is still invented, so a refutation over the digits is still only a
 * refutation inside the box and the clamp verdict is unchanged.
 */

private val LONG_MAX_MAGNITUDE = BigInteger.fromLong(Long.MAX_VALUE)
private val LONG_MIN_MAGNITUDE = BigInteger.fromLong(Long.MIN_VALUE)

/** Digit columns per substituted variable, empty when nothing needed substituting. */
internal fun SmtLib.Builder.digitizeWideInts(
    inventedLo: BooleanArray,
    inventedHi: BooleanArray,
): Map<Int, WideIntColumns> {
    val rows = factors.filterIsInstance<Linear>()
    if (rows.any { it.hasReals }) return emptyMap()
    val needed = forcedOutOfRange(inventedLo, inventedHi, rows)
    if (needed.isEmpty()) return emptyMap()
    val magnitude = derivedMagnitudes(inventedLo, inventedHi, rows)
    // Only the rows that carry integer terms can be rewritten; anything else mentioning a substituted
    // variable would silently lose it, so the whole substitution is declined instead.
    val substituted = needed.toHashSet()
    if (factors.any { it !is Clause && it !is Linear && it !is ReifiedLinear && mentions(it, substituted) }) {
        return emptyMap()
    }
    val columns = HashMap<Int, WideIntColumns>(needed.size)
    for (v in needed) {
        val cols = wideIntColumns(magnitude[v], BigInteger.ONE) { lo, hi -> newInt(lo, hi) } ?: return emptyMap()
        columns[v] = cols
    }
    val rewritten = ArrayList<Factor>(factors.size)
    for (f in factors) {
        rewritten.add(
            when {
                f is Linear && mentionsVars(f.vars, substituted) -> substituteLinear(f, columns)
                f is ReifiedLinear && mentionsVars(f.vars, substituted) -> substituteReified(f, columns)
                else -> f
            },
        )
    }
    factors.clear()
    factors.addAll(rewritten)
    // The variable itself no longer appears in any row; its value is read back off its digits.
    for (v in needed) intDomains[v] = openOrFinite(0L, 0L)
    return columns
}

private fun mentionsVars(vars: IntArray, substituted: Set<Int>): Boolean = vars.any { it in substituted }

private fun mentions(f: Factor, substituted: Set<Int>): Boolean = mentionsVars(f.intVars, substituted)

/** A row with each substituted variable replaced by the weighted sum of its digit columns. */
private fun substituteLinear(f: Linear, columns: Map<Int, WideIntColumns>): Linear {
    val (vars, coeffs) = expandTerms(f.vars, { k -> f.wideCoeffs?.get(k) ?: BigInteger.fromLong(f.coeff(k)) }, columns)
    return Linear(vars, coeffs, f.op, f.wideBound ?: BigInteger.fromLong(f.bound))
}

/** The reified twin of [substituteLinear]; a reified row keeps its op, so nothing is re-canonicalised. */
private fun substituteReified(f: ReifiedLinear, columns: Map<Int, WideIntColumns>): ReifiedLinear {
    val (vars, coeffs) = expandTerms(f.vars, { k -> f.wideCoeffs?.get(k) ?: BigInteger.fromLong(f.coeff(k)) }, columns)
    return ReifiedLinear(f.auxBoolVar, vars, coeffs, f.op, f.wideBound ?: BigInteger.fromLong(f.bound))
}

/** `(vars, coeffs)` with every substituted variable's term expanded into one term per digit column. */
private fun expandTerms(
    vars: IntArray,
    coeffAt: (Int) -> BigInteger,
    columns: Map<Int, WideIntColumns>,
): Pair<IntArray, Array<BigInteger>> {
    val outVars = ArrayList<Int>(vars.size)
    val outCoeffs = ArrayList<BigInteger>(vars.size)
    for (k in vars.indices) {
        val c = coeffAt(k)
        val cols = columns[vars[k]]
        if (cols == null) {
            outVars.add(vars[k])
            outCoeffs.add(c)
        } else {
            val weights = cols.weights()
            for (i in cols.columns.indices) {
                outVars.add(cols.columns[i])
                outCoeffs.add(c * weights[i])
            }
        }
    }
    return outVars.toIntArray() to Array(outCoeffs.size) { outCoeffs[it] }
}

/**
 * The variables whose every possible value lies outside the 64-bit range, so no ordinary domain can hold
 * one.
 *
 * This is ordinary bound tightening — intersect, never widen — run from the bounds the *model* states,
 * with an invented side taken as infinite rather than as the box. A bound derived that way is the model's,
 * so a variable it pushes past `Long` genuinely has nowhere to live, which is the only case where trading
 * the row's LP relaxation for digits pays.
 */
private fun SmtLib.Builder.forcedOutOfRange(
    inventedLo: BooleanArray,
    inventedHi: BooleanArray,
    rows: List<Linear>,
): List<Int> {
    val lo = arrayOfNulls<BigInteger>(nextInt)
    val hi = arrayOfNulls<BigInteger>(nextInt)
    for (v in 0 until nextInt) {
        if (!inventedLo[v]) lo[v] = BigInteger.fromLong(domainMin(v))
        if (!inventedHi[v]) hi[v] = BigInteger.fromLong(domainMax(v))
    }
    repeat(minOf(nextInt, MAX_WIDEN_ROUNDS)) {
        for (f in rows) {
            if (f.op != LinearOp.LE && f.op != LinearOp.EQ) continue
            val n = f.vars.size
            val coeff = Array(n) { k -> f.wideCoeffs?.get(k) ?: BigInteger.fromLong(f.coeff(k)) }
            val bound = f.wideBound ?: BigInteger.fromLong(f.bound)
            for (j in 0 until n) {
                val c = coeff[j]
                if (c.isZero()) continue
                var restMin: BigInteger? = BigInteger.ZERO
                var restMax: BigInteger? = BigInteger.ZERO
                for (i in 0 until n) {
                    if (i == j) continue
                    val ci = coeff[i]
                    val low = if (ci > BigInteger.ZERO) lo[f.vars[i]] else hi[f.vars[i]]
                    val high = if (ci > BigInteger.ZERO) hi[f.vars[i]] else lo[f.vars[i]]
                    restMin = if (restMin == null || low == null) null else restMin + ci * low
                    restMax = if (restMax == null || high == null) null else restMax + ci * high
                }
                val upper = restMin?.let { rest -> bound - rest }
                val lower = if (f.op == LinearOp.EQ) restMax?.let { rest -> bound - rest } else null
                val positive = c > BigInteger.ZERO
                val newHi = if (positive) upper?.let { s -> floorDiv(s, c) } else lower?.let { s -> floorDiv(s, c) }
                val newLo = if (positive) lower?.let { s -> ceilDiv(s, c) } else upper?.let { s -> ceilDiv(s, c) }
                val v = f.vars[j]
                val curHi = hi[v]
                val curLo = lo[v]
                if (newHi != null && (curHi == null || newHi < curHi)) hi[v] = newHi
                if (newLo != null && (curLo == null || newLo > curLo)) lo[v] = newLo
            }
        }
    }
    return (0 until nextInt).filter { v ->
        val l = lo[v]
        val h = hi[v]
        (l != null && l > LONG_MAX_MAGNITUDE) || (h != null && h < LONG_MIN_MAGNITUDE)
    }
}

/**
 * The magnitude each variable's value can reach, by bound propagation over [rows] from the current
 * domains.
 *
 * Only an invented side moves, and only ever *outward*. The box is a stand-in on those sides, so a value
 * the rows force beyond it is the one worth representing; letting it move inward instead would be one
 * invented bound narrowing another, which is what shrinks a model out of reach of its own solutions.
 * A side the model states is never touched.
 *
 * The sweep is capped at one round per variable. A chain of equalities needs one round per link, and a
 * cyclic system would otherwise widen without end.
 */
private fun SmtLib.Builder.derivedMagnitudes(
    inventedLo: BooleanArray,
    inventedHi: BooleanArray,
    rows: List<Linear>,
): Array<BigInteger> {
    val lo = Array(nextInt) { BigInteger.fromLong(domainMin(it)) }
    val hi = Array(nextInt) { BigInteger.fromLong(domainMax(it)) }
    val rounds = minOf(nextInt, MAX_WIDEN_ROUNDS)
    repeat(rounds) {
        var changed = false
        for (f in rows) {
            if (f.op != LinearOp.LE && f.op != LinearOp.EQ) continue
            val n = f.vars.size
            if (n == 0) continue
            val coeff = Array(n) { k -> f.wideCoeffs?.get(k) ?: BigInteger.fromLong(f.coeff(k)) }
            val bound = f.wideBound ?: BigInteger.fromLong(f.bound)
            for (j in 0 until n) {
                val v = f.vars[j]
                if (v >= inventedLo.size || !(inventedLo[v] || inventedHi[v])) continue
                val c = coeff[j]
                if (c.isZero()) continue
                var restMin = BigInteger.ZERO
                var restMax = BigInteger.ZERO
                for (i in 0 until n) {
                    if (i == j) continue
                    val ci = coeff[i]
                    val xi = f.vars[i]
                    val a = ci * lo[xi]
                    val b = ci * hi[xi]
                    restMin += if (a < b) a else b
                    restMax += if (a < b) b else a
                }
                // c·x ≤ bound − restMin always; an equality also gives c·x ≥ bound − restMax.
                val upper = bound - restMin
                val lower = if (f.op == LinearOp.EQ) bound - restMax else null
                val positive = c > BigInteger.ZERO
                val newHi = if (positive) floorDiv(upper, c) else lower?.let { side -> floorDiv(side, c) }
                val newLo = if (positive) lower?.let { side -> ceilDiv(side, c) } else ceilDiv(upper, c)
                if (newHi != null && inventedHi[v] && newHi > hi[v]) {
                    hi[v] = newHi
                    changed = true
                }
                if (newLo != null && inventedLo[v] && newLo < lo[v]) {
                    lo[v] = newLo
                    changed = true
                }
            }
        }
        if (!changed) return@repeat
    }
    return Array(nextInt) {
        val a = lo[it].abs()
        val b = hi[it].abs()
        if (a > b) a else b
    }
}

/** One widening round per variable is enough for a chain; the cap keeps a cyclic system finite. */
private const val MAX_WIDEN_ROUNDS = 64

private fun SmtLib.Builder.domainMin(v: Int): Long = when (val d = intDomains[v]) {
    is PresolveDomain.Finite -> d.domain.min
    is PresolveDomain.Open -> d.lo ?: 0L
}

private fun SmtLib.Builder.domainMax(v: Int): Long = when (val d = intDomains[v]) {
    is PresolveDomain.Finite -> d.domain.max
    is PresolveDomain.Open -> d.hi ?: 0L
}

private fun floorDiv(a: BigInteger, b: BigInteger): BigInteger {
    val q = a / b
    return if (a % b != BigInteger.ZERO && (a.signum() < 0) != (b.signum() < 0)) q - BigInteger.ONE else q
}

private fun ceilDiv(a: BigInteger, b: BigInteger): BigInteger {
    val q = a / b
    return if (a % b != BigInteger.ZERO && (a.signum() < 0) == (b.signum() < 0)) q + BigInteger.ONE else q
}
