package com.eignex.klause.solver.lp

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log2
import kotlin.math.roundToLong

/**
 * Integer-multiplier exact lower bound on the minimized objective `cᵀz` — the Kotlin-Multiplatform
 * analogue of CP-SAT's `int128` `PropagateExactLpReason` (#B0). It is the integer-exact twin of the
 * floating-point [safeObjectiveLowerBound]: instead of solving the dual system exactly in rationals
 * (as [ExactBasisCertifier] does) it takes the *approximate* float duals [y], **rounds them to integer
 * multipliers** at a power-of-two scale `2ᵏ`, and evaluates the Lagrangian
 * `L(y) = y·rhs + Σⱼ min_{[0,uⱼ]} dⱼ·zⱼ` exactly with a 128-bit accumulator ([Int128]).
 *
 * Soundness rests on the fact that the slack-form constraints are equalities, so `L(y)` is a valid
 * lower bound on the optimum for **any** `y` — there is no need to solve for, or even approximate, the
 * optimal dual. Rounding the duals only weakens the bound; it can never make it unsound. Every error
 * path returns null (the caller keeps the node), exactly like the rational certifier:
 *  - a non-finite dual, or a multiplier that escapes the exactly-representable range;
 *  - a reduced cost too large to evaluate in 64 bits, or a 128-bit accumulator overflow;
 *  - a strictly-negative reduced cost on a variable with no finite upper bound (unbounded Lagrangian).
 *
 * The result is `⌈L⌉` — a valid integer lower bound. Because the scale is a power of two, the final
 * division is an arithmetic shift ([Int128.ceilDivPow2]); no 128÷64 division is needed.
 *
 * @param model the slack-form relaxation (`A z = rhs`, `0 ≤ z ≤ upper`) whose objective is bounded.
 * @param y approximate dual vector over the `m` rows (e.g. from [RevisedSimplex]); length `≥ model.m`.
 * @param scaleBits the requested power-of-two scale exponent; capped down so the rounded multipliers
 *   stay exactly representable as `Double` (and below the [MAX_EXACT_INT] round-trip guard).
 */
internal fun integerDualLowerBoundCeil(model: LpModel, y: DoubleArray, scaleBits: Int = DEFAULT_SCALE_BITS): Long? {
    val m = model.m
    val n = model.n
    var maxY = 0.0
    for (i in 0 until m) {
        val yi = y[i]
        if (!yi.isFinite()) return null
        val a = abs(yi)
        if (a > maxY) maxY = a
    }
    val k = chooseScale(maxY, scaleBits)
    val scale = 1L shl k
    val scaleD = scale.toDouble()

    // Round the duals to integer multipliers; bail if one escapes the exactly-representable range so
    // that roundToLong recovers the true nearest integer.
    val mult = LongArray(m)
    for (i in 0 until m) {
        val s = y[i] * scaleD
        if (!s.isFinite() || abs(s) >= MAX_EXACT_INT) return null
        mult[i] = s.roundToLong()
    }

    val acc = Int128() // N = 2ᵏ · (L − objConstant), accumulated exactly
    for (i in 0 until m) acc.addProduct(mult[i], model.rhs[i])

    val dAcc = Int128() // scaled reduced cost Dⱼ = 2ᵏ·cⱼ − Σᵢ mᵢ·Aᵢⱼ, reused per column
    for (j in 0 until model.numVars) {
        dAcc.clear()
        dAcc.addProduct(model.cost[j], scale) // 2ᵏ·cⱼ
        if (j >= n) {
            // Slack column j is the unit vector e_{j−n}.
            dAcc.addProduct(-mult[j - n], 1L)
        } else {
            model.forEachInColumn(j) { i, a -> dAcc.addProduct(-mult[i], a) }
        }
        if (!dAcc.fitsLong()) return null // reduced cost too large to evaluate ⇒ keep node (sound)
        val dj = dAcc.toLong()
        if (dj < 0L) {
            if (!model.hasUpper[j]) return null // unbounded below
            acc.addProduct(dj, model.upper[j]) // min over [0,uⱼ] of dⱼ·zⱼ is dⱼ·uⱼ (scaled)
        }
    }
    // Re-add the lower-bound-shift constant the relaxation folded out (`c·lo`), scaled by 2ᵏ.
    acc.addProduct(model.objConstant, scale)
    return acc.ceilDivPow2(k)
}

/** The power-of-two scale exponent: the requested [scaleBits], capped so `maxY · 2ᵏ` stays below the
 *  exactly-representable range, and clamped to the shift range [Int128.ceilDivPow2] accepts. */
private fun chooseScale(maxY: Double, scaleBits: Int): Int {
    if (maxY <= 0.0) return scaleBits.coerceIn(0, MAX_SCALE_BITS)
    val headroom = MULTIPLIER_BITS - ceil(log2(maxY)).toInt()
    return scaleBits.coerceAtMost(headroom).coerceIn(0, MAX_SCALE_BITS)
}

/** Requested scale: fine enough to keep rounding loss negligible, capped by [chooseScale]. */
private const val DEFAULT_SCALE_BITS = 40

/** Hard cap on the scale exponent (must satisfy [Int128.ceilDivPow2]'s `0..62` and `1L shl k`). */
private const val MAX_SCALE_BITS = 40

/** Keep scaled multipliers below `2⁵²` so they remain exact `Double`s and round-trip cleanly. */
private const val MULTIPLIER_BITS = 52

/** Integers below this magnitude round-trip exactly through `Double` (`2⁵³`); matches the certifier. */
private const val MAX_EXACT_INT: Double = 9.007199254740992E15
