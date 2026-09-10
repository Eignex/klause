package com.eignex.klause.lp.engine

import com.eignex.klause.util.Int128
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
 * The result is `⌈L⌉` when the source objective has a verified integer lattice; otherwise it declines.
 * Because the scale is a power of two, the final
 * division is an arithmetic shift ([Int128.ceilDivPow2]); no 128÷64 division is needed.
 *
 * @param model the slack-form relaxation (`A z = rhs`, `0 ≤ z ≤ upper`) whose objective is bounded.
 * @param y approximate dual vector over the `m` rows (e.g. from [RevisedSimplex]); length `≥ model.m`.
 * @param scaleBits the requested power-of-two scale exponent; capped down so the rounded multipliers
 *   stay exactly representable as `Double` (and below the [MAX_EXACT_INT] round-trip guard).
 * @param observer optional sink for the integer-certificate attempt.
 */
internal fun integerDualLowerBoundCeil(
    model: LpModel,
    y: DoubleArray,
    scaleBits: Int = DEFAULT_SCALE_BITS,
    observer: LpCertificationObserver? = null,
): Long? = if (model.hasIntegralObjective()) {
    integerCertify(model, y, scaleBits, observer)?.objectiveBoundCeil(0L)
} else {
    null
}

/**
 * `⌈L⌉` on a **continuous** model's true minimized objective, certified over its scaled-integer
 * rationalization: the integer certificate bounds the scaled objective `s·(cᵀz + objConstant)` exactly,
 * and the scale divides back out through `⌈⌈N / 2ᵏ⌉ / s⌉ = ⌈N / (2ᵏ·s)⌉`, so the result is a sound
 * lower bound only for a verified integer source objective lattice. The float duals need no rescaling —
 * row-scaling by `s`
 * leaves the optimal duals unchanged, and any multipliers are sound regardless. Null when the model
 * does not rationalize (an exactly-scaled objective constant included) or the certification overflows.
 */
internal fun rationalizedDualLowerBoundCeil(
    model: LpModel,
    y: DoubleArray,
    scaleBits: Int = DEFAULT_SCALE_BITS,
    observer: LpCertificationObserver? = null,
): Long? {
    val r = rationalizeToIntegerModel(
        model,
        outwardRealUppers = true,
        observer = observer,
        requireExactObjectiveConstant = true,
    ) ?: return null
    val scaled = integerCertify(r.model, y, scaleBits, observer)?.objectiveBoundCeil(0L) ?: return null
    return if (model.hasIntegralObjective()) ceilDivPositive(scaled, r.scale) else null
}

/** `⌈a / d⌉` for a positive [d]: truncating division adjusted upward on a positive remainder. */
private fun ceilDivPositive(a: Long, d: Long): Long {
    val q = a / d
    return if (a % d > 0L) q + 1L else q
}

/** The integer duals from rounding the float duals at the chosen power-of-two scale `2ᵏ`. */
internal class RoundedDuals(val scaleBits: Int, val scale: Long, val mult: LongArray)

/** Round the float duals `y` to integer multipliers at a capped power-of-two scale `2ᵏ`, or null when a
 *  dual is non-finite or its scaled value escapes the exactly-representable range (so [roundToLong]
 *  recovers the true nearest integer). Shared by the bound, the [IntegerCertificate], the Farkas ray
 *  and the [integerTableauCuts] aggregation. */
internal fun roundDuals(model: LpModel, y: DoubleArray, scaleBits: Int = DEFAULT_SCALE_BITS): RoundedDuals? {
    val m = model.m
    if (y.size != m) return null
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
 * The integer-multiplier Lagrangian data a node deduction needs (objective lower bound, per-column
 * reduced cost, dual-row support), carried as exact
 * scaled integers from rounded duals at scale `2ᵏ`. Every quantity is a valid deduction for **any**
 * integer multipliers, so rounding only weakens it — never makes it unsound (see [integerCertify]).
 */
internal class IntegerCertificate(
    private val scaleBits: Int,
    private val scale: Long,
    /** Scaled integer duals `2ᵏ·yᵢ`, one per row. */
    private val mult: LongArray,
    /** Scaled reduced cost `Dⱼ = 2ᵏ·cⱼ − Σᵢ multᵢ·Aᵢⱼ`, one per column. Rounded
     *  multipliers can leave a nonzero exact reduced cost even on a float-basic column. */
    private val reduced: LongArray,
    /** `N = 2ᵏ · objective` (the Lagrangian lower bound, including `model.objConstant`). */
    private val numerator: Int128,
) {
    /** The scale exponent `k`: the objective is `objectiveNumerator / 2ᵏ`. Lets a caller summing several
     *  certificates' objectives bring them to a common denominator. */
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

    /** The scaled source-objective improving gap
     * `G = improvingMax·2ᵏ − (N + sourceConstant·2ᵏ)`. */
    private fun gapNumerator(improvingMax: Long, sourceConstant: Long): Int128 {
        val g = Int128()
        g.addProduct(improvingMax, scale)
        g.subtract(numerator)
        val constant = Int128()
        constant.addProduct(sourceConstant, scale)
        g.subtract(constant)
        return g
    }

    /** Whether `improvingMax ≥ objective + sourceConstant`, i.e. the fixing gap is non-negative. */
    fun improvingGapNonNegative(improvingMax: Long, sourceConstant: Long): Boolean =
        gapNumerator(improvingMax, sourceConstant).isNonNegative()

    /**
     * Max integer steps column [col] may move from its reduced-cost-minimizing endpoint before it pushes the objective
     * past [improvingMax]: `⌊ (improvingMax − sourceConstant − objective) / |reducedCost| ⌋`.
     * Sound for any duals because `sourceObjective = sourceConstant + y·rhs + Σₖ rcₖzₖ` and every
     * other box term is `≥ 0`, so `|rcⱼ|·Δⱼ ≤ improvingMax − sourceConstant − objective`.
     * Null on a zero reduced cost or on overflow (the caller then skips the fix, which is sound).
     */
    fun fixSteps(col: Int, improvingMax: Long, sourceConstant: Long): Long? {
        val dj = reduced[col]
        if (dj == 0L || dj == Long.MIN_VALUE) return null
        return gapNumerator(improvingMax, sourceConstant).floorDivPositive(if (dj < 0L) -dj else dj)
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

internal fun integerCertify(
    model: LpModel,
    y: DoubleArray,
    scaleBits: Int = DEFAULT_SCALE_BITS,
    observer: LpCertificationObserver? = null,
): IntegerCertificate? = integerCertifyUnchecked(model, y, scaleBits).also {
    observer?.observe(LpCertifier.INTEGER, it != null)
}

private fun integerCertifyUnchecked(model: LpModel, y: DoubleArray, scaleBits: Int): IntegerCertificate? {
    if (model.hasContinuous || !model.finiteExactInput()) return null
    val rd = roundDuals(model, y, scaleBits) ?: return null
    val m = model.m
    val n = model.n
    val scale = rd.scale
    val mult = repairedMultipliers(model, rd.mult, scale)
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
        if (dj > 0L && j < n && model.probeClampedLo[j]) return null
        if (dj < 0L) {
            if (!model.hasUpper[j] || (j < n && model.probeClampedHi[j])) return null
            acc.addProduct(dj, model.upper[j]) // min over [0,uⱼ] of dⱼ·zⱼ is dⱼ·uⱼ (scaled)
        }
    }
    // Re-add the lower-bound-shift constant the relaxation folded out (`c·lo`), scaled by 2ᵏ.
    acc.addProduct(model.objConstant, scale)
    if (acc.overflow) return null
    return IntegerCertificate(rd.scaleBits, scale, mult, reduced, acc)
}

/**
 * [mult] with each row multiplier moved to where its own slack's reduced cost is zero, for the rows
 * where it had gone the other way; [mult] itself when none had.
 *
 * The Lagrangian is valid for **any** multipliers, so one is free to be moved, and moving it is worth
 * far more than the alternative. A slack column carries no upper bound, so `min dⱼ·zⱼ` over its box is
 * `−∞` the moment `dⱼ` is negative and the whole certificate is abandoned. What sends it negative is
 * not a dual worth respecting: an approximate `y` leaves a multiplier whose exact value is zero
 * rounded to a hair below it. Declining over that loses every bound on a model whose columns are all
 * open, which is exactly where a bound is wanted.
 *
 * This mirrors the repair the float bound already makes ([safeObjectiveLowerBound]), so the exact
 * bound no longer declines where the cheap one succeeds. It is not a tolerance: the moved multiplier
 * is used for the whole certificate, so what comes back is the true Lagrangian of a different, equally
 * valid choice. A multiplier that was genuinely wrong rather than merely rounded is moved just the
 * same, and only costs the bound some tightness.
 *
 * A structural column with no finite upper cannot be repaired this way — its reduced cost reads every
 * multiplier at once — so one left negative still abandons the certificate.
 */
private fun repairedMultipliers(model: LpModel, mult: LongArray, scale: Long): LongArray {
    var repaired: LongArray? = null
    for (i in 0 until model.m) {
        val slack = model.n + i
        if (model.hasUpper[slack]) continue
        // A slack column is the unit column eᵢ, so its scaled reduced cost is `2ᵏ·c_slack − mᵢ`.
        val scaled = Int128()
        scaled.addProduct(model.cost[slack], scale)
        if (!scaled.fitsLong()) continue // the moved multiplier is not expressible; leave it to decline
        val target = scaled.toLong()
        // `target − mult[i] < 0` without risking the subtraction overflowing.
        if (target >= mult[i]) continue
        val fix = repaired ?: mult.copyOf().also { repaired = it }
        fix[i] = target
    }
    return repaired ?: mult
}

/** Which route produced a Farkas certificate, for a caller measuring where the chain earns its keep. */
internal enum class FarkasRoute { RECONSTRUCTED, EXACT_BASIS, ROUNDED, NONE }

/**
 * Exact Farkas infeasibility certificate as an **integer** ray. [ray] is the float `ρ = B⁻ᵀeᵣ` the
 * dual-unbounded termination
 * produced ([RevisedSimplex.infeasibleRay]); it is rounded to integer multipliers and both signs are
 * checked against the exact Farkas condition `ρ·rhs > Σⱼ max(0, ρ·Aⱼ)·uⱼ` in 128-bit arithmetic. The
 * returned integer ray (whichever sign certifies) proves infeasibility for **any** integer ρ, so a
 * float-misled ray simply fails the check and the node is kept — the prune is sound regardless. Null
 * when neither sign certifies, the rounding fails, or a 128-bit term overflows.
 */

internal fun integerFarkasRay(
    model: LpModel,
    ray: DoubleArray,
    scaleBits: Int = DEFAULT_SCALE_BITS,
    basis: Basis? = null,
    basisRow: Int = -1,
    onRoute: ((FarkasRoute) -> Unit)? = null,
    observer: LpCertificationObserver? = null,
): LongArray? {
    if (ray.size != model.m || !model.finiteExactInput()) {
        onRoute?.invoke(FarkasRoute.NONE)
        return null
    }
    if (model.hasContinuous) {
        // A real model is certified over its scaled-integer rationalization (the existing 128-bit Farkas);
        // scaling by a positive 2ᵏ preserves feasibility, so an infeasibility proof carries back exactly.
        val integral = rationalizeToIntegerModel(
            model,
            outwardRealUppers = true,
            observer = observer,
        )?.model
        if (integral != null) return integerFarkasRay(integral, ray, scaleBits, basis, basisRow, onRoute, observer)
    }
    // Reconstruction first: the ray's entries are ratios of minors of B, so they are small rationals, and
    // recovering them exactly annihilates the open columns the same way a basis solve does — without the
    // basis, and with no bound on the dimension. See [reconstructIntegerVector].
    reconstructIntegerVector(ray)?.let { exact ->
        if (farkasCertifies(model, exact)) {
            onRoute?.invoke(FarkasRoute.RECONSTRUCTED)
            return exact
        }
        val negated = LongArray(exact.size) { if (exact[it] == Long.MIN_VALUE) return@let else -exact[it] }
        if (farkasCertifies(model, negated)) {
            onRoute?.invoke(FarkasRoute.RECONSTRUCTED)
            return negated
        }
    }
    // The exact basis solve next: it annihilates the open columns exactly too, but is capped at
    // [MAX_EXACT_BASIS] rows (see [exactFarkasRay]).
    if (basis != null) {
        val exact = exactFarkasRay(model, basis, basisRow)
        val certified = when {
            exact == null -> null
            farkasCertifies(model, exact) -> exact
            exact.any { it == Long.MIN_VALUE } -> null
            else -> LongArray(exact.size) { -exact[it] }.takeIf { farkasCertifies(model, it) }
        }
        observer?.observe(LpCertifier.EXACT_FARKAS, certified != null)
        if (certified != null) {
            onRoute?.invoke(FarkasRoute.EXACT_BASIS)
            return certified
        }
    }
    val rd = roundDuals(model, ray, scaleBits) ?: run {
        onRoute?.invoke(FarkasRoute.NONE)
        return null
    }
    if (farkasCertifies(model, rd.mult)) {
        onRoute?.invoke(FarkasRoute.ROUNDED)
        return rd.mult
    }
    val neg = LongArray(rd.mult.size) { -rd.mult[it] }
    if (farkasCertifies(model, neg)) {
        onRoute?.invoke(FarkasRoute.ROUNDED)
        return neg
    }
    onRoute?.invoke(FarkasRoute.NONE)
    return null
}

/** Whether integer ray [rho] is a Farkas infeasibility certificate: `ρ·rhs > Σⱼ max(0, ρ·Aⱼ)·uⱼ`,
 *  evaluated exactly in 128 bits. A column with `ρ·Aⱼ > 0` but no finite upper bound makes the box max
 *  unbounded (this ρ cannot certify); a term that escapes 64/128 bits likewise bails (false, keep node). */
private fun farkasCertifies(model: LpModel, rho: LongArray): Boolean {
    if (model.hasContinuous) return sourceFarkasValid(model, rho)
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
        if (aj < 0L && j < model.n && model.probeClampedLo[j]) return false
        if (aj > 0L) {
            // A probe-clamped side stands in for `+∞`, so its `upper` is an artefact of the encoding
            // rather than a bound of the model: folding it into the box max would certify against a
            // box the model never had. Treated as unbounded, exactly like a column with no upper.
            if (!model.hasUpper[j] || (j < model.n && model.probeClampedHi[j])) return false
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

/** A continuous model's scaled-integer copy: the integral [model], the positive [scale] `s` its
 *  coefficients were multiplied by, and whether the objective constant scaled exactly
 *  ([objConstantExact] — Farkas and feasibility certificates never read the objective, so they
 *  tolerate an inexact constant; an objective-bound certificate must decline). */
internal class RationalizedLp(val model: LpModel, val scale: Long, val objConstantExact: Boolean)

/**
 * A dyadically scaled copy of the exact finite binary data in a legacy Double view, or null outside
 * the bounded Long scaling budget. Parsed rational authority belongs to [ExactLpModel] and cannot be
 * recovered from this projection. Structural coordinates stay unchanged; logical coordinates scale
 * with the rows, so their costs stay unchanged and their finite sides scale with the RHS.
 *
 * Real structural uppers round outward for bounds/refutations and inward for point candidates.
 * Those candidates still require an authoritative point check. Row premises, strictness and absent
 * probe sides retain their source meaning. An inexact objective constant is usable only for feasibility.
 */
internal fun rationalizeToIntegerModel(
    model: LpModel,
    outwardRealUppers: Boolean,
    observer: LpCertificationObserver? = null,
    requireExactObjectiveConstant: Boolean = false,
): RationalizedLp? {
    val rationalized = rationalizeToIntegerModelUnchecked(model, outwardRealUppers)
    val accepted = rationalized != null && (!requireExactObjectiveConstant || rationalized.objConstantExact)
    if (model.doubleView != null) observer?.observeExactInput(accepted)
    return if (accepted) rationalized else null
}

private fun rationalizeToIntegerModelUnchecked(model: LpModel, outwardRealUppers: Boolean): RationalizedLp? {
    if (!model.finiteExactInput()) return null
    val dv = model.doubleView ?: return RationalizedLp(model, 1L, objConstantExact = true)
    val n = model.n
    val numVars = model.numVars
    val s = commonScale(model) ?: return null
    val upper = LongArray(numVars)
    for (j in 0 until numVars) {
        if (!dv.hasUpper[j]) continue
        if (j >= n) {
            upper[j] = scaledInteger(dv.upper[j], s) ?: return null
        } else {
            val u = if (outwardRealUppers) ceil(dv.upper[j]) else floor(dv.upper[j])
            if (u < 0.0 || u >= LONG_LIMIT) return null
            upper[j] = u.toLong()
        }
    }
    val objConstant = scaledInteger(dv.objConstant, s)
    return RationalizedLp(
        LpModel(
            n = n,
            m = model.m,
            csc = Csc(
                dv.colPtr.copyOf(),
                dv.rowIdx.copyOf(),
                LongArray(dv.colVal.size) { checkNotNull(scaledInteger(dv.colVal[it], s)) },
            ),
            rhs = LongArray(model.m) { checkNotNull(scaledInteger(dv.rhs[it], s)) },
            cost = LongArray(numVars) { checkNotNull(scaledInteger(dv.cost[it], if (it < n) s else 1.0)) },
            upper = upper,
            hasUpper = dv.hasUpper.copyOf(),
            loShift = LongArray(n),
            objConstant = objConstant ?: 0L,
            sense = model.sense,
            tag = model.tag.copyOf(),
            rowGlobal = model.rowGlobal.copyOf(),
            rowStrict = model.rowStrict.copyOf(),
            rowPremises = model.rowPremises.copyOf(),
            probeClampedLo = model.probeClampedLo.copyOf(),
            probeClampedHi = model.probeClampedHi.copyOf(),
        ),
        s.toLong(),
        objConstant != null,
    )
}

private fun commonScale(model: LpModel): Double? {
    val dv = checkNotNull(model.doubleView)
    // Implicit logical columns absorb the row scale, so fractional logical costs cannot enter this route.
    if ((model.n until model.numVars).any { scaledInteger(dv.cost[it], 1.0) == null }) return null
    var fallback: Double? = null
    for (k in 0..MAX_SCALE_BITS) {
        val s = (1L shl k).toDouble()
        if (dv.colVal.all { scaledInteger(it, s) != null } && dv.rhs.all { scaledInteger(it, s) != null } &&
            (0 until model.n).all { scaledInteger(dv.cost[it], s) != null } &&
            (model.n until model.numVars).all { !dv.hasUpper[it] || scaledInteger(dv.upper[it], s) != null }
        ) {
            if (fallback == null) fallback = s
            if (scaledInteger(dv.objConstant, s) != null) return s
        }
    }
    return fallback
}

private fun scaledInteger(value: Double, scale: Double): Long? {
    val scaled = value * scale
    // Multiplication by an in-range power of two is exact; an integral result cannot have underflowed.
    if (!scaled.isFinite() || scaled != floor(scaled) || abs(scaled) >= MAX_EXACT_INT) return null
    if (value != 0.0 && scaled == 0.0) return null
    return scaled.toLong()
}

/** Requested scale: fine enough to keep rounding loss negligible, capped by [chooseScale]. */
private const val DEFAULT_SCALE_BITS = 40

/** Hard cap on the scale exponent (must satisfy [Int128.ceilDivPow2]'s `0..62` and `1L shl k`). */
private const val MAX_SCALE_BITS = 40

/** Keep scaled multipliers below `2⁵²` so they remain exact `Double`s and round-trip cleanly. */
private const val MULTIPLIER_BITS = 52

/** Integers below this magnitude round-trip exactly through `Double` (`2⁵³`); matches the certifier. */
private const val MAX_EXACT_INT: Double = 9.007199254740992E15

/** `Long.MAX_VALUE` as a `Double` (`2⁶³`): a real column's rounded upper must stay below it to convert. */
private const val LONG_LIMIT: Double = 9.223372036854776E18
