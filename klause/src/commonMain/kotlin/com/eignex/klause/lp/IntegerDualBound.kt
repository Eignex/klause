package com.eignex.klause.lp

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.roundToLong

/**
 * Integer-multiplier exact lower bound on the minimized objective `cᵀz`, in portable Kotlin-Multiplatform
 * 128-bit integer arithmetic. It is the integer-exact twin of the
 * floating-point [safeObjectiveLowerBound]: instead of solving the dual system exactly in rationals it
 * takes the *approximate* float duals [y], **rounds them to integer multipliers** at a power-of-two
 * scale `2ᵏ`, and evaluates the Lagrangian
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
internal fun integerDualLowerBoundCeil(model: LpModel, y: DoubleArray, scaleBits: Int = DEFAULT_SCALE_BITS): Long? =
    integerCertify(model, y, scaleBits)?.objectiveBoundCeil(0L)

/** The integer duals from rounding the float duals at the chosen power-of-two scale `2ᵏ`. */
internal class RoundedDuals(val scaleBits: Int, val scale: Long, val mult: LongArray)

/** Round the float duals `y` to integer multipliers at a capped power-of-two scale `2ᵏ`, or null when a
 *  dual is non-finite or its scaled value escapes the exactly-representable range (so [roundToLong]
 *  recovers the true nearest integer). Shared by the bound, the [IntegerCertificate], the Farkas ray
 *  and the [integerTableauCuts] aggregation. */
internal fun roundDuals(model: LpModel, y: DoubleArray, scaleBits: Int = DEFAULT_SCALE_BITS): RoundedDuals? {
    val m = model.m
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
    val mult = LongArray(m)
    for (i in 0 until m) {
        val s = y[i] * scaleD
        if (!s.isFinite() || abs(s) >= MAX_EXACT_INT) return null
        mult[i] = s.roundToLong()
    }
    return RoundedDuals(k, scale, mult)
}

/**
 * The integer-multiplier LP-optimum data a node deduction needs (objective lower bound, per-column
 * reduced cost, dual-row support), carried as exact
 * scaled integers from rounded duals at scale `2ᵏ`. Every quantity is a valid deduction for **any**
 * integer multipliers, so rounding only weakens it — never makes it unsound (see [integerCertify]).
 */
internal class IntegerCertificate(
    private val scaleBits: Int,
    private val scale: Long,
    /** Scaled integer duals `2ᵏ·yᵢ`, one per row. */
    private val mult: LongArray,
    /** Scaled reduced cost `Dⱼ = 2ᵏ·cⱼ − Σᵢ multᵢ·Aᵢⱼ`, one per column (`0` for basic columns). */
    private val reduced: LongArray,
    /** `N = 2ᵏ · objective` (the Lagrangian lower bound, including `model.objConstant`). */
    private val numerator: Int128,
) {
    /** The scale exponent `k`: the objective is `objectiveNumerator / 2ᵏ`. Lets a caller summing several
     *  certificates' objectives (e.g. [componentLowerBoundCeil]) bring them to a common denominator. */
    val objectiveScaleBits: Int get() = scaleBits

    /** A copy of `N = 2ᵏ · objective` (the exact scaled objective numerator); see [objectiveScaleBits]. */
    fun objectiveNumerator(): Int128 = numerator.copy()

    /** Whether row [row] carries nonzero dual weight (for non-global-row premise citation). */
    fun dualNonzeroRow(row: Int): Boolean = mult[row] != 0L

    /** Sign of column [col]'s reduced cost (`-1`/`0`/`+1`). */
    fun reducedCostSign(col: Int): Int = reduced[col].let {
        if (it > 0L) {
            1
        } else if (it < 0L) {
            -1
        } else {
            0
        }
    }

    /** `⌈ objective + extraConstant ⌉` as a `Long`, or null when it does not fit (the caller keeps the
     *  node / falls back). [extraConstant] is the relaxation-level objective constant the caller folds in. */
    fun objectiveBoundCeil(extraConstant: Long): Long? {
        val n = numerator.copy()
        n.addProduct(extraConstant, scale)
        return n.ceilDivPow2(scaleBits)
    }

    /** The scaled improving gap `G = improvingMax·2ᵏ − N` (`= 2ᵏ·(improvingMax − objective)`). */
    private fun gapNumerator(improvingMax: Long): Int128 {
        val g = Int128()
        g.addProduct(improvingMax, scale)
        g.subtract(numerator)
        return g
    }

    /** Whether `improvingMax ≥ objective`, i.e. the reduced-cost-fixing gap is non-negative. */
    fun improvingGapNonNegative(improvingMax: Long): Boolean = gapNumerator(improvingMax).isNonNegative()

    /**
     * Max integer steps column [col] may move from its seated bound before it alone pushes the objective
     * past [improvingMax]: `⌊ (improvingMax − objective) / |reducedCost| ⌋`. Sound for any duals because
     * `cz = y·rhs + Σₖ rcₖzₖ` and every other box term is `≥ 0`, so `|rcⱼ|·Δⱼ ≤ improvingMax − objective`.
     * Null on a zero reduced cost or on overflow (the caller then skips the fix, which is sound).
     */
    fun fixSteps(col: Int, improvingMax: Long): Long? {
        val dj = reduced[col]
        if (dj == 0L || dj == Long.MIN_VALUE) return null
        return gapNumerator(improvingMax).floorDivPositive(if (dj < 0L) -dj else dj)
    }
}

/**
 * Certify a node LP optimum from the float duals [y] (e.g. [RevisedSimplex] `duals`), as an
 * [IntegerCertificate] over exact scaled integers. Rounds the duals to integer multipliers and evaluates
 * the Lagrangian
 * `L(y) = y·rhs + Σⱼ min_{[0,uⱼ]} dⱼ·zⱼ` and every reduced cost in a 128-bit accumulator. Sound for
 * **any** integer multipliers (the slack-form constraints are equalities), so this never needs the
 * optimal dual; rounding only weakens the bound / reduced costs. Every error path returns null (the
 * caller keeps the node / falls back), exactly like the rational certifier:
 *  - a non-finite dual or a multiplier outside the exactly-representable range;
 *  - a reduced cost too large to evaluate in 64 bits, or a 128-bit accumulator overflow;
 *  - a strictly-negative reduced cost on a column with no finite upper bound (unbounded Lagrangian).
 */
internal fun integerCertify(model: LpModel, y: DoubleArray, scaleBits: Int = DEFAULT_SCALE_BITS): IntegerCertificate? {
    if (model.hasContinuous) return null // a real coefficient is not integrally certifiable here (Phase 3b)
    val rd = roundDuals(model, y, scaleBits) ?: return null
    val m = model.m
    val n = model.n
    val mult = rd.mult
    val scale = rd.scale
    val acc = Int128() // N = 2ᵏ · (objective − objConstant), accumulated exactly
    for (i in 0 until m) acc.addProduct(mult[i], model.rhs[i])
    val reduced = LongArray(model.numVars)
    val dAcc = Int128() // scaled reduced cost Dⱼ = 2ᵏ·cⱼ − Σᵢ mᵢ·Aᵢⱼ, reused per column
    for (j in 0 until model.numVars) {
        dAcc.clear()
        dAcc.addProduct(model.cost[j], scale) // 2ᵏ·cⱼ
        if (j >= n) {
            dAcc.addProduct(-mult[j - n], 1L) // slack column j is the unit vector e_{j−n}
        } else {
            model.forEachInColumn(j) { i, a -> dAcc.addProduct(-mult[i], a) }
        }
        if (!dAcc.fitsLong()) return null // reduced cost too large to evaluate ⇒ keep node (sound)
        val dj = dAcc.toLong()
        reduced[j] = dj
        if (dj < 0L) {
            if (!model.hasUpper[j]) return null // unbounded below
            acc.addProduct(dj, model.upper[j]) // min over [0,uⱼ] of dⱼ·zⱼ is dⱼ·uⱼ (scaled)
        }
    }
    // Re-add the lower-bound-shift constant the relaxation folded out (`c·lo`), scaled by 2ᵏ.
    acc.addProduct(model.objConstant, scale)
    if (acc.overflow) return null
    return IntegerCertificate(rd.scaleBits, scale, mult, reduced, acc)
}

/**
 * Exact Farkas infeasibility certificate as an **integer** ray. [ray] is the float `ρ = B⁻ᵀeᵣ` the
 * dual-unbounded termination
 * produced ([RevisedSimplex.infeasibleRay]); it is rounded to integer multipliers and both signs are
 * checked against the exact Farkas condition `ρ·rhs > Σⱼ max(0, ρ·Aⱼ)·uⱼ` in 128-bit arithmetic. The
 * returned integer ray (whichever sign certifies) proves infeasibility for **any** integer ρ, so a
 * float-misled ray simply fails the check and the node is kept — the prune is sound regardless. Null
 * when neither sign certifies, the rounding fails, or a 128-bit term overflows.
 */
internal fun integerFarkasRay(model: LpModel, ray: DoubleArray, scaleBits: Int = DEFAULT_SCALE_BITS): LongArray? {
    if (model.hasContinuous) {
        // A real model is certified over its scaled-integer rationalization (the existing 128-bit Farkas);
        // scaling by a positive 2ᵏ preserves feasibility, so an infeasibility proof carries back exactly.
        val integral = rationalizeToIntegerModel(model) ?: return null
        return integerFarkasRay(integral, ray, scaleBits)
    }
    val rd = roundDuals(model, ray, scaleBits) ?: return null
    if (farkasCertifies(model, rd.mult)) return rd.mult
    val neg = LongArray(rd.mult.size) { -rd.mult[it] }
    return if (farkasCertifies(model, neg)) neg else null
}

/** Whether integer ray [rho] is a Farkas infeasibility certificate: `ρ·rhs > Σⱼ max(0, ρ·Aⱼ)·uⱼ`,
 *  evaluated exactly in 128 bits. A column with `ρ·Aⱼ > 0` but no finite upper bound makes the box max
 *  unbounded (this ρ cannot certify); a term that escapes 64/128 bits likewise bails (false, keep node). */
private fun farkasCertifies(model: LpModel, rho: LongArray): Boolean {
    val lhs = Int128()
    for (i in 0 until model.m) lhs.addProduct(rho[i], model.rhs[i])
    val boxMax = Int128()
    val ajAcc = Int128()
    for (j in 0 until model.numVars) {
        ajAcc.clear()
        if (j >= model.n) {
            ajAcc.addProduct(rho[j - model.n], 1L)
        } else {
            model.forEachInColumn(j) { i, a -> ajAcc.addProduct(rho[i], a) }
        }
        if (!ajAcc.fitsLong()) return false
        val aj = ajAcc.toLong()
        if (aj > 0L) {
            if (!model.hasUpper[j]) return false // box max unbounded ⇒ this ρ cannot certify
            boxMax.addProduct(aj, model.upper[j])
        }
    }
    val diff = lhs.copy() // ρ·rhs − Σ max(0, ρ·Aⱼ)·uⱼ
    diff.subtract(boxMax)
    return diff.isNonNegative() && !(diff.hi == 0L && diff.lo == 0L) // strictly > 0
}

/** The power-of-two scale exponent: the requested [scaleBits], capped so `maxY · 2ᵏ` stays below the
 *  exactly-representable range, and clamped to the shift range [Int128.ceilDivPow2] accepts. */
private fun chooseScale(maxY: Double, scaleBits: Int): Int {
    if (maxY <= 0.0) return scaleBits.coerceIn(0, MAX_SCALE_BITS)
    val headroom = MULTIPLIER_BITS - ceil(log2(maxY)).toInt()
    return scaleBits.coerceAtMost(headroom).coerceIn(0, MAX_SCALE_BITS)
}

/**
 * A scaled-integer copy of a continuous [model], or null when it cannot be rationalized within budget.
 * Multiplies the double-view coefficients (matrix, rhs, cost) by a common power of two `2ᵏ` so they
 * become exact [Long]s, keeping the (integer) variable bounds. The existing 128-bit certifiers then apply
 * unchanged: scaling by a positive `2ᵏ` leaves the feasible region intact, so an infeasibility (Farkas)
 * certificate over the scaled model proves the real model infeasible. Returns null — leaving the LP
 * `INDETERMINATE`, never mis-certified — when a bound is non-integral, a coefficient needs more than
 * [MAX_SCALE_BITS] fractional bits, or a scaled value escapes the exactly-representable range.
 */
internal fun rationalizeToIntegerModel(model: LpModel): LpModel? {
    val dv = model.doubleView ?: return model
    val n = model.n
    val numVars = model.numVars
    val upper = LongArray(numVars)
    for (j in 0 until numVars) {
        if (!dv.hasUpper[j]) continue
        val u = dv.upper[j]
        if (!u.isFinite() || u != floor(u) || abs(u) >= MAX_EXACT_INT) return null
        upper[j] = u.toLong()
    }
    val k = commonScaleBits(dv) ?: return null
    val s = (1L shl k).toDouble()
    return LpModel(
        n = n,
        m = model.m,
        csc = Csc(
            dv.colPtr.copyOf(),
            dv.rowIdx.copyOf(),
            LongArray(dv.colVal.size) { (dv.colVal[it] * s).roundToLong() },
        ),
        rhs = LongArray(model.m) { (dv.rhs[it] * s).roundToLong() },
        cost = LongArray(numVars) { (dv.cost[it] * s).roundToLong() },
        upper = upper,
        hasUpper = dv.hasUpper.copyOf(),
        loShift = LongArray(n),
        objConstant = 0L,
        sense = model.sense,
        tag = IntArray(n) { -1 },
    )
}

/** Smallest power-of-two scale `k ≤ [MAX_SCALE_BITS]` at which every double-view coefficient becomes an
 *  exact integer within the round-trip range, or null when none does. */
private fun commonScaleBits(dv: LpDoubleView): Int? {
    for (k in 0..MAX_SCALE_BITS) {
        val s = (1L shl k).toDouble()
        if (exactlyIntegral(dv.colVal, s) && exactlyIntegral(dv.rhs, s) && exactlyIntegral(dv.cost, s)) return k
    }
    return null
}

private fun exactlyIntegral(a: DoubleArray, scale: Double): Boolean {
    for (x in a) {
        val v = x * scale
        if (!v.isFinite() || v != floor(v) || abs(v) >= MAX_EXACT_INT) return false
    }
    return true
}

/** Requested scale: fine enough to keep rounding loss negligible, capped by [chooseScale]. */
private const val DEFAULT_SCALE_BITS = 40

/** Hard cap on the scale exponent (must satisfy [Int128.ceilDivPow2]'s `0..62` and `1L shl k`). */
private const val MAX_SCALE_BITS = 40

/** Keep scaled multipliers below `2⁵²` so they remain exact `Double`s and round-trip cleanly. */
private const val MULTIPLIER_BITS = 52

/** Integers below this magnitude round-trip exactly through `Double` (`2⁵³`); matches the certifier. */
private const val MAX_EXACT_INT: Double = 9.007199254740992E15
