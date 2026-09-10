package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Int128
import com.ionspin.kotlin.bignum.integer.BigInteger

internal fun exactPointWitness(
    model: LpModel,
    primal: DoubleArray,
    observer: LpCertificationObserver? = null,
): ExactLpWitness? {
    val point = if (primal.size == model.n && primal.all { it.isFinite() } && model.finiteExactInput()) {
        checkedLpWitness(model, primal.map { checkNotNull(BigFraction.ofDouble(it)) }) ?: run {
            var common = BigInteger.ONE
            val limit = BigInteger.fromLong(MAX_POINT_DENOMINATOR)
            val candidate = primal.map { value ->
                val part = reconstructRational(value, maxDenominator = MAX_POINT_DENOMINATOR) ?: return@run null
                val denominator = BigInteger.fromLong(part.denominator)
                common = common / common.gcd(denominator) * denominator
                if (common > limit) return@run null
                BigFraction.of(BigInteger.fromLong(part.numerator), denominator)
            }
            checkedLpWitness(model, candidate)
        }
    } else {
        null
    }
    observer?.observe(LpCertifier.EXACT_POINT, point != null)
    return point
}

internal fun exactBasisWitness(
    model: LpModel,
    basis: Basis,
    observer: LpCertificationObserver? = null,
): ExactLpWitness? {
    var point: ExactLpWitness? = null
    exactBasisFeasibleUnchecked(model, basis, observer) { candidate ->
        point = checkedLpWitness(model, candidate)
    }
    observer?.observe(LpCertifier.EXACT_BASIS, point != null)
    return point
}

internal fun exactPointFeasible(
    model: LpModel,
    primal: DoubleArray,
    observer: LpCertificationObserver? = null,
): Boolean = exactPointWitness(model, primal, observer) != null

/**
 * Exact primal-feasibility check of a reported LP [Basis] over an integer-coefficient [LpModel], in
 * bounded 128-bit arithmetic — the feasibility twin of [integerFarkasRay]. The float simplex reports
 * which `m` columns are basic and where each nonbasic column is pinned; this
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
 * rationalization ([rationalizeToIntegerModel]), where a positive integer scale preserves the feasible
 * region so the proof carries back exactly.
 */
internal fun exactBasisFeasible(model: LpModel, basis: Basis, observer: LpCertificationObserver? = null): Boolean? =
    exactBasisFeasibleUnchecked(model, basis, observer).also {
        observer?.observe(LpCertifier.EXACT_BASIS, it == true)
    }

private fun exactBasisFeasibleUnchecked(
    model: LpModel,
    basis: Basis,
    observer: LpCertificationObserver?,
    onPoint: ((List<BigFraction>) -> Unit)? = null,
): Boolean? {
    if (!model.finiteExactInput() || !validBasisDeclaration(model, basis)) return null
    val rationalized = rationalizeToIntegerModel(model, outwardRealUppers = true, observer = observer) ?: return null
    val integral = rationalized.model
    val m = integral.m
    if (m > MAX_EXACT_BASIS) return null
    val basic = basis.basicVars
    val point = MutableList(model.numVars) { j ->
        if (basis.status[j] == VarStatus.AT_UPPER) BigFraction.ofLong(integral.upper[j]) else BigFraction.ZERO
    }
    // An outward-rounded seat is not the declared exact upper-bound status.
    for (j in 0 until model.n) {
        if (basis.status[j] == VarStatus.AT_UPPER && point[j] != model.exactUpper(j)) return null
    }
    if (m == 0) {
        val source = List(model.n) { j -> point[j] + model.exactShift(j) }
        if (checkedLpWitness(model, source) == null) return false
        onPoint?.invoke(source)
        return true
    }

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

    val det = bareissDet(Array(m) { b[it].copyOf() }) ?: return null
    if (det == 0L) return null // singular basis — cannot reconstruct the point

    for (t in 0 until m) {
        val bt = Array(m) { r -> b[r].copyOf() }
        for (i in 0 until m) bt[i][t] = rhsAdj[i]
        val detT = bareissDet(bt) ?: return null
        point[basic[t]] = BigFraction.of(BigInteger.fromLong(detT), BigInteger.fromLong(det))
    }
    val source = List(model.n) { j -> point[j] + model.exactShift(j) }
    if (checkedLpWitness(model, source) == null) return false
    onPoint?.invoke(source)
    return true
}

private fun validBasisDeclaration(model: LpModel, basis: Basis): Boolean {
    if (basis.status.size != model.numVars || basis.basicVars.size != model.m) return false
    val seen = BooleanArray(model.numVars)
    for (j in basis.basicVars) {
        if (j !in seen.indices || seen[j] || basis.status[j] != VarStatus.BASIC) return false
        seen[j] = true
    }
    return basis.status.indices.all { j ->
        when (basis.status[j]) {
            VarStatus.BASIC -> seen[j]
            VarStatus.AT_LOWER -> j >= model.n || !model.probeClampedLo[j]
            VarStatus.AT_UPPER -> model.hasFiniteUpper(j) && (j >= model.n || !model.probeClampedHi[j])
        }
    }
}

/**
 * The exact Farkas ray `ρ = B⁻ᵀeᵣ` of the dual-unbounded [basis] with leaving row [row], scaled by
 * `|det B|` so every entry is an integer. Null when the basis is singular, oversized, or a minor escapes
 * the exact range — the caller then falls back to rounding the float ray.
 *
 * Rounding the float ray cannot serve here. A certificate must satisfy `ρ·Aⱼ = 0` *exactly* for every
 * column with no finite upper bound: a variable split as `x = x⁺ − x⁻` has `A_{x⁻} = −A_{x⁺}`, so a
 * nonzero `ρ·A_{x⁺}` leaves one of the two halves unbounded above in the box max whichever sign the ray
 * takes, and no scaling repairs it. The float ray satisfies the condition only to within its own error,
 * and per-entry rounding turns that residual into a nonzero integer. Solving the basis exactly gives the
 * annihilation for free: `ρ·Aⱼ = eᵣᵀB⁻¹Aⱼ` is an entry of a unit vector for every basic column.
 *
 * Cramer's rule supplies it without an inverse: `ρᵢ·det B = det(Bᵀ with column i replaced by eᵣ)`. The
 * result is normalized to a *positive* multiple of `ρ`, since a negative multiple of a Farkas ray is not
 * one.
 */
internal fun exactFarkasRay(model: LpModel, basis: Basis, row: Int): LongArray? {
    if (model.doubleView != null || !model.finiteExactInput() || !validBasisDeclaration(model, basis)) return null
    val m = model.m
    if (m == 0 || row < 0 || row >= m || m > MAX_EXACT_BASIS) return null
    val basic = basis.basicVars
    if (basic.size != m) return null

    // Bᵀ[t][i] = B[i][t] = A[i][basic[t]].
    val bt = Array(m) { LongArray(m) }
    for (t in 0 until m) {
        forEachColumnEntry(model, basic[t]) { i, a -> bt[t][i] = a }
    }
    val det = bareissDet(Array(m) { bt[it].copyOf() }) ?: return null
    if (det == 0L) return null

    val ray = LongArray(m)
    for (i in 0 until m) {
        val mi = Array(m) { t -> bt[t].copyOf() }
        for (t in 0 until m) mi[t][i] = if (t == row) 1L else 0L
        ray[i] = bareissDet(mi) ?: return null
    }
    if (det < 0L) {
        for (i in 0 until m) {
            if (ray[i] == Long.MIN_VALUE) return null
            ray[i] = -ray[i]
        }
    }
    return ray
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

private const val MAX_POINT_DENOMINATOR = 1L shl 40
