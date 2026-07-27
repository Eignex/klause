package com.eignex.klause.lp

import com.eignex.klause.solver.Cancellation
import com.eignex.klause.util.BigInt

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

internal fun rationalFeasible(
    model: LpModel,
    cancellation: Cancellation = Cancellation.Never,
    maxPivots: Int = defaultRationalPivotCap(model),
): RationalFeasibility {
    val m = model.m
    val total = model.numVars
    if (m == 0) return RationalFeasibility.FEASIBLE
    // Dense tableau T over all columns with basis = slack columns (identity), so initially
    // x_slack(i) = rhs(i) − Σ_struct A(i,j)·x(j) with every structural column nonbasic at zero.
    val dv = model.doubleView
    val t = Array(m) { Array(total) { BigFraction.ZERO } }
    if (dv != null) {
        for (j in 0 until model.n) {
            for (p in dv.colPtr[j] until dv.colPtr[j + 1]) {
                t[dv.rowIdx[p]][j] = BigFraction.ofDouble(dv.colVal[p]) ?: return RationalFeasibility.UNKNOWN
            }
        }
    } else {
        for (j in 0 until model.n) model.forEachInColumn(j) { i, a -> t[i][j] = BigFraction.ofLong(a) }
    }
    for (i in 0 until m) t[i][model.n + i] = BigFraction.ONE
    val rhs = Array(m) {
        if (dv != null) {
            BigFraction.ofDouble(dv.rhs[it]) ?: return RationalFeasibility.UNKNOWN
        } else {
            BigFraction.ofLong(model.rhs[it])
        }
    }
    val basis = IntArray(m) { model.n + it }
    val inBasisRow = IntArray(total) { -1 }
    for (i in 0 until m) inBasisRow[basis[i]] = i
    // Nonbasic columns sit at a bound; false = lower (0), true = upper (u).
    val atUpper = BooleanArray(total)
    val uppers = Array<BigFraction?>(total) { j ->
        when {
            !model.hasUpper[j] -> null
            dv != null -> BigFraction.ofDouble(dv.upper[j]) ?: return RationalFeasibility.UNKNOWN
            else -> BigFraction.ofLong(model.upper[j])
        }
    }

    fun upperOf(j: Int): BigFraction? = uppers[j]

    // Basic values from the nonbasic bounds: x_B(i) = rhs(i) − Σ_{nonbasic j at upper} T(i,j)·u(j).
    fun basicValue(i: Int): BigFraction {
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
        if (cancellation.isCancelled() || pivots >= maxPivots) return RationalFeasibility.UNKNOWN
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
            } else if (u != null && bv > u) {
                if (row < 0 || basis[i] < basis[row]) {
                    row = i
                    needIncrease = false
                    target = u
                }
            }
        }
        if (row < 0) return RationalFeasibility.FEASIBLE
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
            // The violated variable already sits at its extreme attainable value: exact infeasibility.
            return RationalFeasibility.INFEASIBLE
        }
        // Pivot fully: the leaving variable lands exactly on its violated bound; solve row for `enter`.
        val leave = basis[row]
        val pivotCoeff = t[row][enter]
        // Row of the leaving variable: x_leave = basicExpr(row); rewrite as x_enter = ... and substitute.
        val inv = pivotCoeff.reciprocal()
        for (j in 0 until total) t[row][j] = t[row][j] * inv
        rhs[row] = rhs[row] * inv
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
            rhs[i] = rhs[i] - f * rhs[row]
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

/** Pivot cap: generous for the small leaf models the fallback targets, tiny relative to a search. */
internal fun defaultRationalPivotCap(model: LpModel): Int = 200 + 20 * (model.m + model.n)

/** Immutable rational number over [BigInt], always normalized (gcd 1, positive denominator). */
internal class BigFraction private constructor(val num: BigInt, val den: BigInt) {

    val isZero: Boolean get() = num.isZero

    fun signum(): Int = num.sign

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

    override fun toString(): String = if (den == BigInt.ONE) "$num" else "$num/$den"

    companion object {
        val ZERO = BigFraction(BigInt.ZERO, BigInt.ONE)
        val ONE = BigFraction(BigInt.ONE, BigInt.ONE)

        fun ofLong(v: Long): BigFraction = if (v == 0L) ZERO else BigFraction(BigInt.fromLong(v), BigInt.ONE)

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
            val mag = BigInt.fromLong(if (bits < 0L) -m else m)
            return if (e >= 0) of(mag * pow2(e), BigInt.ONE) else of(mag, pow2(-e))
        }

        private fun pow2(e: Int): BigInt {
            var r = BigInt.ONE
            var left = e
            while (left > 0) {
                val step = minOf(left, 62)
                r *= BigInt.fromLong(1L shl step)
                left -= step
            }
            return r
        }

        fun of(num: BigInt, den: BigInt): BigFraction {
            require(!den.isZero) { "zero denominator" }
            if (num.isZero) return ZERO
            val sign = if (den.isNegative) -BigInt.ONE else BigInt.ONE
            val n = num * sign
            val d = den * sign
            val g = n.gcd(d)
            val (nq, _) = n.divRem(g)
            val (dq, _) = d.divRem(g)
            return BigFraction(nq, dq)
        }
    }
}
