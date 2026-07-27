package com.eignex.klause.lp

import com.eignex.klause.solver.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * Exact rational feasibility of an [LpModel]: slack-form rows `A·x = rhs` over boxes `0 ≤ xⱼ ≤ uⱼ`
 * (`hasUpper[j]` false ⇒ open above). Coefficients come from the double view when the model has one —
 * every finite double is exactly a rational `±m·2ᵉ`, so no scaling ladder or tolerance is involved —
 * and from the Long arrays otherwise. The last-resort certifier behind [solveAndCertify]: it runs
 * only when the float solve plus the cheap exact checks leave the verdict INDETERMINATE, and decides
 * it in exact arithmetic.
 *
 * The method is a bounded-variable feasibility simplex on a dense [BigFraction] tableau, starting
 * from the slack basis. The objective is identically zero, so every basis is dual-feasible and no
 * dual ratio test is needed: repeatedly take the least-index basic variable outside its box, move it
 * exactly onto the violated bound with the least-index sign-eligible nonbasic column, and pivot.
 * When no eligible column exists the row itself is an exact certificate of infeasibility (the row
 * expresses the basic variable as its extreme attainable value, which still violates the box).
 * Least-index (Bland-style) selection plus the `maxPivots` cap bounds the run; a capped or
 * cancelled run returns [RationalFeasibility.UNKNOWN] — sound, the verdict just stays undecided.
 */
internal enum class RationalFeasibility { FEASIBLE, INFEASIBLE, UNKNOWN }

/** The exact verdict plus, on FEASIBLE, a concrete structural-column witness (evaluated at a small
 *  positive delta when strict rows are present). */
internal class RationalOutcome(val feasibility: RationalFeasibility, val witness: DoubleArray? = null)

internal fun rationalFeasible(
    model: LpModel,
    cancellation: Cancellation = Cancellation.Never,
    maxPivots: Int = defaultRationalPivotCap(model),
): RationalFeasibility = rationalOutcome(model, cancellation, maxPivots).feasibility

internal fun rationalOutcome(
    model: LpModel,
    cancellation: Cancellation = Cancellation.Never,
    maxPivots: Int = defaultRationalPivotCap(model),
): RationalOutcome {
    val m = model.m
    val total = model.numVars
    if (m == 0) return RationalOutcome(RationalFeasibility.FEASIBLE, DoubleArray(model.n))
    // Dense tableau T over all columns with basis = slack columns (identity), so initially
    // x_slack(i) = rhs(i) − Σ_struct A(i,j)·x(j) with every structural column nonbasic at zero.
    val dv = model.doubleView
    val t = Array(m) { Array(total) { BigFraction.ZERO } }
    if (dv != null) {
        for (j in 0 until model.n) {
            for (p in dv.colPtr[j] until dv.colPtr[j + 1]) {
                t[dv.rowIdx[p]][j] = BigFraction.ofDouble(dv.colVal[p])
                    ?: return RationalOutcome(RationalFeasibility.UNKNOWN)
            }
        }
    } else {
        for (j in 0 until model.n) model.forEachInColumn(j) { i, a -> t[i][j] = BigFraction.ofLong(a) }
    }
    for (i in 0 until m) t[i][model.n + i] = BigFraction.ONE
    // A strict row `a·x < b` enters the delta-ordered field as `a·x ≤ b − δ`: the rhs carries a −1
    // delta component, and lexicographic feasibility is exactly strict feasibility of the original.
    val rhs = Array(m) {
        val base = if (dv != null) {
            BigFraction.ofDouble(dv.rhs[it]) ?: return RationalOutcome(RationalFeasibility.UNKNOWN)
        } else {
            BigFraction.ofLong(model.rhs[it])
        }
        DeltaFraction(base, if (model.rowStrict[it]) BigFraction.MINUS_ONE else BigFraction.ZERO)
    }
    val basis = IntArray(m) { model.n + it }
    val inBasisRow = IntArray(total) { -1 }
    for (i in 0 until m) inBasisRow[basis[i]] = i
    // Nonbasic columns sit at a bound; false = lower (0), true = upper (u).
    val atUpper = BooleanArray(total)
    val uppers = Array<BigFraction?>(total) { j ->
        when {
            !model.hasUpper[j] -> null
            dv != null -> BigFraction.ofDouble(dv.upper[j]) ?: return RationalOutcome(RationalFeasibility.UNKNOWN)
            else -> BigFraction.ofLong(model.upper[j])
        }
    }

    fun upperOf(j: Int): BigFraction? = uppers[j]

    // Basic values from the nonbasic bounds: x_B(i) = rhs(i) − Σ_{nonbasic j at upper} T(i,j)·u(j).
    fun basicValue(i: Int): DeltaFraction {
        var v = rhs[i]
        for (j in 0 until total) {
            if (inBasisRow[j] >= 0 || !atUpper[j]) continue
            val u = upperOf(j) ?: continue
            val c = t[i][j]
            if (!c.isZero) v -= c * u
        }
        return v
    }

    var pivots = 0
    while (true) {
        if (cancellation.isCancelled() || pivots >= maxPivots) return RationalOutcome(RationalFeasibility.UNKNOWN)
        // Least-index violated basic variable.
        var row = -1
        var needIncrease = false
        var target: BigFraction? = null
        for (i in 0 until m) {
            val bv = basicValue(i)
            val u = upperOf(basis[i])
            if (bv.signum() < 0) {
                if (row < 0 || basis[i] < basis[row]) {
                    row = i
                    needIncrease = true
                    target = BigFraction.ZERO
                }
            } else if (u != null && bv.compareToPure(u) > 0) {
                if (row < 0 || basis[i] < basis[row]) {
                    row = i
                    needIncrease = false
                    target = u
                }
            }
        }
        if (row < 0) {
            return RationalOutcome(
                RationalFeasibility.FEASIBLE,
                structuralWitness(model, t, rhs, basis, inBasisRow, atUpper, uppers),
            )
        }
        // Least-index nonbasic column that can move the violated variable toward its box. Increasing
        // x_B(row) means decreasing Σ T(row,j)·x(j): a column at lower moving up needs T < 0, a column
        // at upper moving down needs T > 0 (mirrored for decreasing).
        var enter = -1
        for (j in 0 until total) {
            if (inBasisRow[j] >= 0) continue
            val c = t[row][j]
            if (c.isZero) continue
            val movesUp = if (atUpper[j]) c.signum() > 0 else c.signum() < 0
            if (movesUp == needIncrease) {
                enter = j
                break
            }
        }
        if (enter < 0) {
            // The violated variable already sits at its extreme attainable value: exact infeasibility —
            // unless the proof leans on a probe stand-in bound (a `±∞` side realized as a huge finite
            // box). Infeasibility relative to the probe box does not refute the true unbounded model,
            // so such a proof degrades to UNKNOWN.
            if (basis[row] < model.n &&
                (model.probeClampedHi[basis[row]] || model.probeClampedLo[basis[row]])
            ) {
                return RationalOutcome(RationalFeasibility.UNKNOWN)
            }
            for (j in 0 until total) {
                if (inBasisRow[j] >= 0 || t[row][j].isZero) continue
                if (atUpper[j] && j < model.n && model.probeClampedHi[j]) {
                    return RationalOutcome(RationalFeasibility.UNKNOWN)
                }
            }
            return RationalOutcome(RationalFeasibility.INFEASIBLE)
        }
        // Pivot fully: the leaving variable lands exactly on its violated bound; solve row for `enter`.
        val leave = basis[row]
        val pivotCoeff = t[row][enter]
        // Row of the leaving variable: x_leave = basicExpr(row); rewrite as x_enter = ... and substitute.
        val inv = pivotCoeff.reciprocal()
        for (j in 0 until total) t[row][j] = t[row][j] * inv
        rhs[row] = rhs[row].times(inv)
        t[row][leave] = inv
        t[row][enter] = BigFraction.ZERO
        // The leaving column becomes an explicit nonbasic column pinned at the violated bound.
        for (i in 0 until m) {
            if (i == row) continue
            val f = t[i][enter]
            if (f.isZero) continue
            for (j in 0 until total) {
                val rj = t[row][j]
                if (!rj.isZero) t[i][j] = t[i][j] - f * rj
            }
            rhs[i] = rhs[i].minus(rhs[row].times(f))
            t[i][enter] = BigFraction.ZERO
        }
        basis[row] = enter
        inBasisRow[enter] = row
        inBasisRow[leave] = -1
        // Pin the leaving variable at the bound it was violating toward.
        atUpper[leave] = target !== null && target != BigFraction.ZERO
        // Entering column leaves its bound; its old bound flag no longer applies as nonbasic state.
        atUpper[enter] = false
        pivots++
    }
}

/** A delta-rational `a + d·δ` over an infinitesimal positive δ, ordered lexicographically. Carries
 *  strictness through the exact feasibility simplex: `x < b` is `x ≤ b − δ`. */
internal class DeltaFraction(val a: BigFraction, val d: BigFraction) {
    fun minus(o: DeltaFraction): DeltaFraction = DeltaFraction(a - o.a, d - o.d)

    fun times(k: BigFraction): DeltaFraction = DeltaFraction(a * k, d * k)

    operator fun minus(k: BigFraction): DeltaFraction = DeltaFraction(a - k, d)

    fun signum(): Int {
        val sa = a.signum()
        return if (sa != 0) sa else d.signum()
    }

    /** Lexicographic comparison against a pure (delta-free) fraction. */
    fun compareToPure(o: BigFraction): Int {
        val c = a.compareTo(o)
        return if (c != 0) c else d.signum()
    }

    companion object {
        val ZERO = DeltaFraction(BigFraction.ZERO, BigFraction.ZERO)
        fun pure(v: BigFraction): DeltaFraction = DeltaFraction(v, BigFraction.ZERO)
    }
}

/**
 * Concrete structural-column values from a lex-feasible final state, with δ instantiated at a
 * positive rational small enough that every delta-dependent basic value stays inside its box.
 * Every constraint is affine in δ, so any δ below the per-constraint thresholds works; the
 * thresholds are computed exactly and halved once to sit strictly inside.
 */
private fun structuralWitness(
    model: LpModel,
    t: Array<Array<BigFraction>>,
    rhs: Array<DeltaFraction>,
    basis: IntArray,
    inBasisRow: IntArray,
    atUpper: BooleanArray,
    uppers: Array<BigFraction?>,
): DoubleArray {
    val m = model.m
    val total = model.numVars
    // Recompute each basic value as a DeltaFraction (mirrors basicValue in the caller).
    val basic = Array(m) { i ->
        var v = rhs[i]
        for (j in 0 until total) {
            if (inBasisRow[j] >= 0 || !atUpper[j]) continue
            val u = uppers[j] ?: continue
            val c = t[i][j]
            if (!c.isZero) v = v.minus(DeltaFraction.pure(u * c))
        }
        v
    }
    // δ threshold: for each basic value a + d·δ needing `>= 0` (d < 0 ⇒ δ ≤ a/(−d)) and, with a finite
    // upper u, `<= u` (d > 0 ⇒ δ ≤ (u − a)/d). Lex-feasibility guarantees each ratio is positive.
    var delta = BigFraction.ONE
    for (i in 0 until m) {
        val v = basic[i]
        if (v.d.signum() < 0) {
            val cap = v.a * v.d.negated().reciprocal()
            if (cap < delta) delta = cap
        }
        val u = uppers[basis[i]]
        if (u != null && v.d.signum() > 0) {
            val cap = (u - v.a) * v.d.reciprocal()
            if (cap < delta) delta = cap
        }
    }
    delta *= HALF
    val out = DoubleArray(model.n)
    for (j in 0 until model.n) {
        val row = inBasisRow[j]
        val value = when {
            row >= 0 -> basic[row].a + basic[row].d * delta
            atUpper[j] -> uppers[j] ?: BigFraction.ZERO
            else -> BigFraction.ZERO
        }
        out[j] = value.toDouble()
    }
    return out
}

private val HALF = BigFraction.of(BigInteger.ONE, BigInteger.TWO)

/** Pivot cap: generous for the small leaf models the fallback targets, tiny relative to a search. */
internal fun defaultRationalPivotCap(model: LpModel): Int = 200 + 20 * (model.m + model.n)

/** Immutable rational number over the multiplatform big integer, always normalized (gcd 1, positive
 *  denominator). The unbounded second level of the exact rational arithmetic — the 128-bit
 *  fixed-width level handles the common case and escalates here on overflow. */
internal class BigFraction private constructor(val num: BigInteger, val den: BigInteger) {

    val isZero: Boolean get() = num.isZero()

    fun signum(): Int = num.signum()

    fun negated(): BigFraction = if (isZero) this else BigFraction(-num, den)

    fun toDouble(): Double = num.doubleValue(exactRequired = false) / den.doubleValue(exactRequired = false)

    operator fun plus(other: BigFraction): BigFraction = of(num * other.den + other.num * den, den * other.den)

    operator fun minus(other: BigFraction): BigFraction = of(num * other.den - other.num * den, den * other.den)

    operator fun times(other: BigFraction): BigFraction = of(num * other.num, den * other.den)

    fun reciprocal(): BigFraction {
        require(!isZero) { "reciprocal of zero" }
        return of(den, num)
    }

    operator fun compareTo(other: BigFraction): Int = (num * other.den).compareTo(other.num * den)

    override fun equals(other: Any?): Boolean = other is BigFraction && num == other.num && den == other.den

    override fun hashCode(): Int = num.hashCode() * 31 + den.hashCode()

    override fun toString(): String = if (den == BigInteger.ONE) "$num" else "$num/$den"

    companion object {
        val ZERO = BigFraction(BigInteger.ZERO, BigInteger.ONE)
        val ONE = BigFraction(BigInteger.ONE, BigInteger.ONE)
        val MINUS_ONE = BigFraction(-BigInteger.ONE, BigInteger.ONE)

        fun ofLong(v: Long): BigFraction = if (v == 0L) ZERO else BigFraction(BigInteger.fromLong(v), BigInteger.ONE)

        fun of(num: BigInteger, den: BigInteger): BigFraction {
            require(!den.isZero()) { "zero denominator" }
            if (num.isZero()) return ZERO
            val negative = den.signum() < 0
            val n = if (negative) -num else num
            val d = if (negative) -den else den
            val g = n.gcd(d)
            return BigFraction(n / g, d / g)
        }

        /** The exact rational value of a finite double: `v = ±m·2ᵉ` from its IEEE decomposition.
         *  Null for non-finite values. */
        fun ofDouble(v: Double): BigFraction? {
            if (v == 0.0) return ZERO
            if (!v.isFinite()) return null
            val bits = v.toRawBits()
            val expBits = ((bits ushr 52) and 0x7FFL).toInt()
            var m = bits and 0xFFFFFFFFFFFFFL
            var e = if (expBits == 0) {
                -1074
            } else {
                m = m or (1L shl 52)
                expBits - 1075
            }
            val tz = m.countTrailingZeroBits()
            m = m shr tz
            e += tz
            val mag = BigInteger.fromLong(if (bits < 0L) -m else m)
            return if (e >= 0) of(mag shl e, BigInteger.ONE) else of(mag, BigInteger.ONE shl -e)
        }
    }
}
