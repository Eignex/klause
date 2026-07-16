package com.eignex.klause.lp

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
