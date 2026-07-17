package com.eignex.klause.lp

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Exact feasibility of the float primal **point** itself (issue #1232): when every structural value the
 * float solve reported snaps to an exact dyadic rational `p_j / 2ᴷ`, verify that point satisfies every
 * row (`Σ aᵢⱼ zⱼ = rhs` for an equality slack, `≤ rhs` for an inequality one) and the box `0 ≤ zⱼ ≤ uⱼ`
 * exactly, in 128-bit integer arithmetic over the scaled-integer rationalization. Returns true only on a
 * proven-feasible point; false when the snapped point is not exactly representable or violates a
 * constraint (the caller then falls back / declines). This is robust where [exactBasisFeasible]'s basis
 * reconstruction is finicky — e.g. a degenerate inequality-plus-equality vertex — since it checks the
 * point, not the basis.
 */
internal fun exactPointFeasible(model: LpModel, primal: DoubleArray): Boolean {
    val integral = rationalizeToIntegerModel(model) ?: return false
    val n = integral.n
    val m = integral.m
    // Shifted values zⱼ = primalⱼ − loShiftⱼ, snapped to a common dyadic denominator 2ᴷ (exact only).
    val z = DoubleArray(n) { primal[it] - model.loShiftD(it) }
    val k = dyadicScaleBits(z) ?: return false
    val d = (1L shl k).toDouble()
    val p = LongArray(n) { (z[it] * d).roundToLong() }
    // Box: 0 ≤ pⱼ ≤ uⱼ·2ᴷ.
    for (j in 0 until n) {
        if (p[j] < 0L) return false
        if (integral.hasUpper[j]) {
            val cap = Int128()
            cap.addProduct(integral.upper[j], 1L shl k)
            val pj = Int128()
            pj.addLong(p[j])
            pj.subtract(cap)
            // pⱼ > uⱼ·2ᴷ (strictly positive difference) ⇒ out of the box.
            if (pj.overflow || (pj.isNonNegative() && !(pj.hi == 0L && pj.lo == 0L))) return false
        }
    }
    // Per-row L = Σⱼ aᵢⱼ·pⱼ; compare to rhsᵢ·2ᴷ under the row's relation (equality slack ⇒ ==, else ≤).
    val lhs = Array(m) { Int128() }
    for (j in 0 until n) integral.forEachInColumn(j) { i, a -> lhs[i].addProduct(a, p[j]) }
    for (i in 0 until m) {
        val r = Int128()
        r.addProduct(integral.rhs[i], 1L shl k)
        val diff = lhs[i].copy()
        diff.subtract(r) // L − rhs·2ᴷ
        if (diff.overflow) return false
        val isEquality = integral.hasUpper[integral.slackCol(i)]
        val sign = when {
            diff.hi < 0L -> -1
            diff.hi == 0L && diff.lo == 0L -> 0
            else -> 1
        }
        if (sign > 0) return false // L > rhs violates both `≤` and `==`
        if (isEquality && sign < 0) return false // L < rhs violates `==`
    }
    return true
}

/** Smallest `k ≤ MAX_SCALE_BITS` making every value in [z] an exact integer multiple of `2⁻ᵏ`, or null. */
private fun dyadicScaleBits(z: DoubleArray): Int? {
    for (k in 0..DYADIC_MAX_BITS) {
        val s = (1L shl k).toDouble()
        if (z.all {
                val v = it * s
                v.isFinite() && v == floor(v) && abs(v) < DYADIC_MAX_INT
            }
        ) {
            return k
        }
    }
    return null
}

private const val DYADIC_MAX_BITS = 40
private const val DYADIC_MAX_INT = 9.007199254740992E15

/**
 * Exact primal-feasibility check of a reported LP [Basis] over an integer-coefficient [LpModel], in
 * bounded 128-bit arithmetic — the feasibility twin of [integerFarkasRay] (issue #1232, Phase 8). The
 * float simplex reports which `m` columns are basic and where each nonbasic column is pinned; this
 * reconstructs the basic solution `x_B = B⁻¹ b'` **exactly** (Cramer's rule over fraction-free / Bareiss
 * determinants, so every intermediate is an exact integer) and checks `0 ≤ x_B ≤ u` exactly.
 *
 * Returns:
 *  - `true`  — the basic solution is primal-feasible, so the LP has a feasible point (a certified SAT);
 *  - `false` — a basic variable provably violates its bounds (the float basis is not primal-feasible);
 *  - `null`  — the exact arithmetic could not settle it: a singular basis, or any 128-bit overflow /
 *    non-representable determinant (the plan's cap — the verdict then degrades to `unknown`, never a
 *    wrong SAT).
 *
 * Soundness rests on exactness: a `true` is a genuine rational feasible point (basic values `x_t =
 * detₜ/det` with nonbasic columns at their bounds satisfy `A x = b` by construction of the basis and the
 * bound checks confirm the box). Continuous models are certified over their scaled-integer
 * rationalization ([rationalizeToIntegerModel]), where a positive `2ᵏ` scale preserves the feasible
 * region so the proof carries back exactly.
 */
internal fun exactBasisFeasible(model: LpModel, basis: Basis): Boolean? {
    val integral = rationalizeToIntegerModel(model) ?: return null
    val m = integral.m
    if (m == 0) return true // no rows ⇒ the box `0 ≤ x ≤ u` (all nonbasic at a valid bound) is feasible
    if (m > MAX_EXACT_BASIS) return null // beyond this the fraction-free minors cannot stay in 128 bits
    val basic = basis.basicVars
    if (basic.size != m) return null

    // b'[i] = rhs[i] − Σ_{nonbasic j at upper} A[i][j]·u[j]. Nonbasic-at-lower columns are 0 (lower is 0
    // in the normalized model), so only upper-pinned columns move the right-hand side.
    val rhsAdj = LongArray(m) { integral.rhs[it] }
    for (j in 0 until integral.numVars) {
        if (basis.status[j] != VarStatus.AT_UPPER) continue
        if (!integral.hasUpper[j]) return null // an unbounded column pinned at upper is nonsensical here
        val u = integral.upper[j]
        forEachColumnEntry(integral, j) { i, a ->
            val acc = Int128()
            acc.addLong(rhsAdj[i])
            val prod = Int128()
            prod.addProduct(a, u)
            acc.subtract(prod)
            if (!acc.fitsLong()) return null
            rhsAdj[i] = acc.toLong()
        }
    }

    // Basis matrix B (m×m), column t = basic column basic[t]; B[i][t] = A[i][basic[t]].
    val b = Array(m) { LongArray(m) }
    for (t in 0 until m) {
        forEachColumnEntry(integral, basic[t]) { i, a -> b[i][t] = a }
    }

    val det = bareissDet(b) ?: return null
    if (det == 0L) return null // singular basis — cannot reconstruct the point

    // Cramer: x_t = det(B with column t replaced by b') / det. Check 0 ≤ x_t ≤ u[basic[t]] exactly,
    // sign-aware in `det` (multiplying an inequality by det flips it when det < 0).
    val detPositive = det > 0L
    for (t in 0 until m) {
        val bt = Array(m) { r -> b[r].copyOf() }
        for (i in 0 until m) bt[i][t] = rhsAdj[i]
        val detT = bareissDet(bt) ?: return null

        // x_t ≥ 0  ⟺  detT/det ≥ 0  ⟺  detT and det share sign (or detT == 0).
        if (detT != 0L && (detT > 0L) != detPositive) return false

        // x_t ≤ u  ⟺  detT ≤ u·det (det > 0) or detT ≥ u·det (det < 0), when the column is bounded.
        val col = basic[t]
        if (col < integral.numVars && integral.hasUpper[col]) {
            val uDet = Int128()
            uDet.addProduct(integral.upper[col], det)
            val dt = Int128()
            dt.addLong(detT)
            dt.subtract(uDet) // detT − u·det
            if (dt.overflow) return null
            // detT − u·det ≤ 0 required when det > 0; ≥ 0 when det < 0.
            val sign = detSign(dt)
            if (detPositive && sign > 0) return false
            if (!detPositive && sign < 0) return false
        }
    }
    return true
}

/** Sign of an [Int128] value assumed non-overflowed: `-1`, `0`, or `+1`. */
private fun detSign(v: Int128): Int = when {
    v.hi < 0L -> -1
    v.hi == 0L && v.lo == 0L -> 0
    else -> 1
}

/** Iterate column [col]'s nonzero entries of the integer [model] as `(row, coeff)` — structural columns
 *  from the CSC, a slack column as the implicit unit vector. */
private inline fun forEachColumnEntry(model: LpModel, col: Int, action: (row: Int, coeff: Long) -> Unit) {
    if (col < model.n) {
        model.forEachInColumn(col, action)
    } else {
        action(col - model.n, 1L)
    }
}

/**
 * Determinant of the `n×n` integer matrix [a] by fraction-free (Bareiss) Gaussian elimination — every
 * intermediate is an exact integer (a minor of the original), so no rounding. Returns the determinant,
 * `0` for a singular matrix, or null when any step escapes the exactly-divisible 64-bit range (the
 * 128-bit product of two entries divided by the previous pivot must land back in a `Long`); the caller
 * then declines. Mutates a copy, not [a]'s rows are copied by the caller when reused.
 */
@Suppress("ReturnCount")
private fun bareissDet(a: Array<LongArray>): Long? {
    val n = a.size
    var prev = 1L
    var sign = 1
    for (k in 0 until n) {
        if (a[k][k] == 0L) {
            var swap = -1
            for (i in k + 1 until n) {
                if (a[i][k] != 0L) {
                    swap = i
                    break
                }
            }
            if (swap == -1) return 0L // a zero column at this stage ⇒ singular
            val tmp = a[k]
            a[k] = a[swap]
            a[swap] = tmp
            sign = -sign
        }
        val pivot = a[k][k]
        for (i in k + 1 until n) {
            for (j in k + 1 until n) {
                // a[i][j] = (a[i][j]·pivot − a[i][k]·a[k][j]) / prev, exact by Bareiss's identity.
                val acc = Int128()
                acc.addProduct(a[i][j], pivot)
                val sub = Int128()
                sub.addProduct(a[i][k], a[k][j])
                acc.subtract(sub)
                a[i][j] = acc.divExactByLong(prev) ?: return null
            }
            a[i][k] = 0L
        }
        prev = pivot
    }
    val d = a[n - 1][n - 1]
    return if (sign < 0) {
        if (d == Long.MIN_VALUE) null else -d
    } else {
        d
    }
}

/** Largest basis dimension the fraction-free solve attempts; beyond it the exact minors overflow 128
 *  bits for all but trivial coefficients, so the check declines (degrading the verdict to `unknown`). */
private const val MAX_EXACT_BASIS = 48
