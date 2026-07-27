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
 * The method is a bounded-variable feasibility simplex on a dense fraction tableau, starting from the
 * slack basis. The objective is identically zero, so every basis is dual-feasible and no dual ratio
 * test is needed: repeatedly take the least-index basic variable outside its box, move it exactly
 * onto the violated bound with the least-index sign-eligible nonbasic column, and pivot. When no
 * eligible column exists the row itself is an exact certificate of infeasibility (the row expresses
 * the basic variable as its extreme attainable value, which still violates the box). Least-index
 * (Bland-style) selection plus the `maxPivots` cap bounds the run; a capped or cancelled run returns
 * [RationalFeasibility.UNKNOWN] — sound, the verdict just stays undecided.
 *
 * The arithmetic is two-level ([FracOps]): the fixed-width 128-bit fraction level ([Frac128Ops])
 * runs first — the common case at a leaf, two-word integer kernels, no big-integer work — and the
 * run escalates to the unbounded [BigFraction] level only when a pivot chain genuinely escapes 128
 * bits (the level latches its overflow flag, the run is voided, and the whole solve restarts at the
 * big level, so every reported verdict is computed in one consistent arithmetic).
 */
internal enum class RationalFeasibility { FEASIBLE, INFEASIBLE, UNKNOWN }

/** The exact verdict plus, on FEASIBLE, a concrete structural-column witness (evaluated at a small
 *  positive delta when strict rows are present). */
internal class RationalOutcome(val feasibility: RationalFeasibility, val witness: DoubleArray? = null)

/**
 * An arithmetic level for the exact simplex: a rational number type with exact operations. A
 * fixed-width level signals exhaustion by latching [overflowed] — its results are then void and the
 * caller escalates; the unbounded level never latches. [ofDouble] returns null for a value the
 * level cannot represent (non-finite always; out-of-range for a fixed-width level).
 */
internal interface FracOps<F> {
    val zero: F
    val one: F
    val minusOne: F
    val half: F

    fun ofLong(v: Long): F

    fun ofDouble(v: Double): F?

    fun plus(a: F, b: F): F

    fun minus(a: F, b: F): F

    fun times(a: F, b: F): F

    fun reciprocal(a: F): F

    fun signum(a: F): Int

    fun compare(a: F, b: F): Int

    fun toDouble(a: F): Double

    fun overflowed(): Boolean

    fun isZero(a: F): Boolean = signum(a) == 0
}

/** The unbounded [BigFraction] level; never overflows. */
internal object BigFracOps : FracOps<BigFraction> {
    override val zero: BigFraction = BigFraction.ZERO
    override val one: BigFraction = BigFraction.ONE
    override val minusOne: BigFraction = BigFraction.MINUS_ONE
    override val half: BigFraction = BigFraction.of(BigInteger.ONE, BigInteger.TWO)

    override fun ofLong(v: Long): BigFraction = BigFraction.ofLong(v)

    override fun ofDouble(v: Double): BigFraction? = BigFraction.ofDouble(v)

    override fun plus(a: BigFraction, b: BigFraction): BigFraction = a + b

    override fun minus(a: BigFraction, b: BigFraction): BigFraction = a - b

    override fun times(a: BigFraction, b: BigFraction): BigFraction = a * b

    override fun reciprocal(a: BigFraction): BigFraction = a.reciprocal()

    override fun signum(a: BigFraction): Int = a.signum()

    override fun compare(a: BigFraction, b: BigFraction): Int = a.compareTo(b)

    override fun toDouble(a: BigFraction): Double = a.toDouble()

    override fun overflowed(): Boolean = false

    override fun isZero(a: BigFraction): Boolean = a.isZero
}

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
    if (model.m == 0) return RationalOutcome(RationalFeasibility.FEASIBLE, DoubleArray(model.n))
    // Fixed-width level first; a voided run (latched overflow / unrepresentable input) escalates.
    runSimplex(Frac128Ops(), model, cancellation, maxPivots)?.let { return it }
    return runSimplex(BigFracOps, model, cancellation, maxPivots)
        ?: RationalOutcome(RationalFeasibility.UNKNOWN)
}

/** One simplex run at arithmetic level [ops]; null when the level cannot carry it (escalate). At the
 *  unbounded level null only arises from a non-finite input coefficient, which no level can carry —
 *  the caller maps that to UNKNOWN. */
private fun <F> runSimplex(
    ops: FracOps<F>,
    model: LpModel,
    cancellation: Cancellation,
    maxPivots: Int,
): RationalOutcome? {
    val m = model.m
    val total = model.numVars
    val dv = model.doubleView

    // Dense tableau T over all columns with basis = slack columns (identity), so initially
    // x_slack(i) = rhs(i) − Σ_struct A(i,j)·x(j) with every structural column nonbasic at zero.
    val t = Array(m) { MutableList(total) { ops.zero } }
    if (dv != null) {
        for (j in 0 until model.n) {
            for (p in dv.colPtr[j] until dv.colPtr[j + 1]) {
                t[dv.rowIdx[p]][j] = ops.ofDouble(dv.colVal[p]) ?: return null
            }
        }
    } else {
        for (j in 0 until model.n) model.forEachInColumn(j) { i, a -> t[i][j] = ops.ofLong(a) }
    }
    for (i in 0 until m) t[i][model.n + i] = ops.one
    // A strict row `a·x < b` enters the delta-ordered field as `a·x ≤ b − δ`: the rhs carries a −1
    // delta component, and lexicographic feasibility is exactly strict feasibility of the original.
    // Only the rhs ever carries a delta part, so the tableau itself stays delta-free.
    val rhsA = MutableList(m) { ops.zero }
    val rhsD = MutableList(m) { ops.zero }
    for (i in 0 until m) {
        rhsA[i] = if (dv != null) ops.ofDouble(dv.rhs[i]) ?: return null else ops.ofLong(model.rhs[i])
        if (model.rowStrict[i]) rhsD[i] = ops.minusOne
    }
    val basis = IntArray(m) { model.n + it }
    val inBasisRow = IntArray(total) { -1 }
    for (i in 0 until m) inBasisRow[basis[i]] = i
    // Nonbasic columns sit at a bound; false = lower (0), true = upper (u).
    val atUpper = BooleanArray(total)
    val uppers = MutableList<F?>(total) { null }
    for (j in 0 until total) {
        if (!model.hasUpper[j]) continue
        uppers[j] = if (dv != null) ops.ofDouble(dv.upper[j]) ?: return null else ops.ofLong(model.upper[j])
    }

    // Basic values from the nonbasic bounds: x_B(i) = rhs(i) − Σ_{nonbasic j at upper} T(i,j)·u(j).
    fun basicValueA(i: Int): F {
        var v = rhsA[i]
        for (j in 0 until total) {
            if (inBasisRow[j] >= 0 || !atUpper[j]) continue
            val u = uppers[j] ?: continue
            val c = t[i][j]
            if (!ops.isZero(c)) v = ops.minus(v, ops.times(c, u))
        }
        return v
    }

    fun deltaSignum(a: F, d: F): Int {
        val sa = ops.signum(a)
        return if (sa != 0) sa else ops.signum(d)
    }

    var pivots = 0
    while (true) {
        if (ops.overflowed()) return null
        if (cancellation.isCancelled() || pivots >= maxPivots) return unknownOutcome()
        // Least-index violated basic variable.
        var row = -1
        var needIncrease = false
        var targetUpper = false
        for (i in 0 until m) {
            val bvA = basicValueA(i)
            val u = uppers[basis[i]]
            if (deltaSignum(bvA, rhsD[i]) < 0) {
                if (row < 0 || basis[i] < basis[row]) {
                    row = i
                    needIncrease = true
                    targetUpper = false
                }
            } else if (u != null && deltaSignum(ops.minus(bvA, u), rhsD[i]) > 0) {
                if (row < 0 || basis[i] < basis[row]) {
                    row = i
                    needIncrease = false
                    targetUpper = true
                }
            }
        }
        if (ops.overflowed()) return null
        if (row < 0) {
            val witness = structuralWitness(ops, WitnessState(model, t, rhsA, rhsD, basis, inBasisRow, atUpper, uppers))
            // A latched overflow during witness extraction voids the run: the verdict may stand but
            // the point does not, and the big-level rerun produces both consistently.
            if (ops.overflowed()) return null
            return RationalOutcome(RationalFeasibility.FEASIBLE, witness)
        }
        // Least-index nonbasic column that can move the violated variable toward its box. Increasing
        // x_B(row) means decreasing Σ T(row,j)·x(j): a column at lower moving up needs T < 0, a column
        // at upper moving down needs T > 0 (mirrored for decreasing).
        var enter = -1
        for (j in 0 until total) {
            if (inBasisRow[j] >= 0) continue
            val c = t[row][j]
            if (ops.isZero(c)) continue
            val movesUp = if (atUpper[j]) ops.signum(c) > 0 else ops.signum(c) < 0
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
                return unknownOutcome()
            }
            for (j in 0 until total) {
                if (inBasisRow[j] >= 0 || ops.isZero(t[row][j])) continue
                if (atUpper[j] && j < model.n && model.probeClampedHi[j]) return unknownOutcome()
            }
            return RationalOutcome(RationalFeasibility.INFEASIBLE)
        }
        // Pivot fully: the leaving variable lands exactly on its violated bound; solve row for `enter`.
        val leave = basis[row]
        val inv = ops.reciprocal(t[row][enter])
        for (j in 0 until total) t[row][j] = ops.times(t[row][j], inv)
        rhsA[row] = ops.times(rhsA[row], inv)
        rhsD[row] = ops.times(rhsD[row], inv)
        t[row][leave] = inv
        t[row][enter] = ops.zero
        // The leaving column becomes an explicit nonbasic column pinned at the violated bound.
        for (i in 0 until m) {
            if (i == row) continue
            val f = t[i][enter]
            if (ops.isZero(f)) continue
            for (j in 0 until total) {
                val rj = t[row][j]
                if (!ops.isZero(rj)) t[i][j] = ops.minus(t[i][j], ops.times(f, rj))
            }
            rhsA[i] = ops.minus(rhsA[i], ops.times(rhsA[row], f))
            rhsD[i] = ops.minus(rhsD[i], ops.times(rhsD[row], f))
            t[i][enter] = ops.zero
        }
        basis[row] = enter
        inBasisRow[enter] = row
        inBasisRow[leave] = -1
        // Pin the leaving variable at the bound it was violating toward; the entering column leaves
        // its bound, so its old flag no longer applies as nonbasic state.
        atUpper[leave] = targetUpper
        atUpper[enter] = false
        pivots++
    }
}

private fun unknownOutcome(): RationalOutcome = RationalOutcome(RationalFeasibility.UNKNOWN)

/** The final simplex state a witness is extracted from. */
private class WitnessState<F>(
    val model: LpModel,
    val t: Array<MutableList<F>>,
    val rhsA: MutableList<F>,
    val rhsD: MutableList<F>,
    val basis: IntArray,
    val inBasisRow: IntArray,
    val atUpper: BooleanArray,
    val uppers: MutableList<F?>,
)

/**
 * Concrete structural-column values from a lex-feasible final state, with δ instantiated at a
 * positive rational small enough that every delta-dependent basic value stays inside its box.
 * Every constraint is affine in δ, so any δ below the per-constraint thresholds works; the
 * thresholds are computed exactly and halved once to sit strictly inside.
 */
private fun <F> structuralWitness(ops: FracOps<F>, st: WitnessState<F>): DoubleArray {
    val m = st.model.m
    val total = st.model.numVars
    // Recompute each basic value's real and delta parts (mirrors basicValueA in the caller).
    val basicA = MutableList(m) { ops.zero }
    for (i in 0 until m) {
        var v = st.rhsA[i]
        for (j in 0 until total) {
            if (st.inBasisRow[j] >= 0 || !st.atUpper[j]) continue
            val u = st.uppers[j] ?: continue
            val c = st.t[i][j]
            if (!ops.isZero(c)) v = ops.minus(v, ops.times(u, c))
        }
        basicA[i] = v
    }
    // δ threshold: each basic value a + d·δ needing `>= 0` (d < 0 ⇒ δ ≤ a/(−d)) and, with a finite
    // upper u, `<= u` (d > 0 ⇒ δ ≤ (u − a)/d). Lex-feasibility guarantees each ratio is positive.
    var delta = ops.one
    for (i in 0 until m) {
        val a = basicA[i]
        val d = st.rhsD[i]
        if (ops.signum(d) < 0) {
            val cap = ops.times(a, ops.reciprocal(ops.times(d, ops.minusOne)))
            if (ops.compare(cap, delta) < 0) delta = cap
        }
        val u = st.uppers[st.basis[i]]
        if (u != null && ops.signum(d) > 0) {
            val cap = ops.times(ops.minus(u, a), ops.reciprocal(d))
            if (ops.compare(cap, delta) < 0) delta = cap
        }
    }
    delta = ops.times(delta, ops.half)
    val out = DoubleArray(st.model.n)
    for (j in 0 until st.model.n) {
        val row = st.inBasisRow[j]
        val value = when {
            row >= 0 -> ops.plus(basicA[row], ops.times(st.rhsD[row], delta))
            st.atUpper[j] -> st.uppers[j] ?: ops.zero
            else -> ops.zero
        }
        out[j] = ops.toDouble(value)
    }
    return out
}

/** Pivot cap: generous for the small leaf models the fallback targets, tiny relative to a search. */
internal fun defaultRationalPivotCap(model: LpModel): Int = 200 + 20 * (model.m + model.n)

/** Immutable rational number over the multiplatform big integer, always normalized (gcd 1, positive
 *  denominator). The unbounded second level of the exact rational arithmetic — the 128-bit
 *  fixed-width level ([Frac128Ops]) handles the common case and escalates here on overflow. */
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
