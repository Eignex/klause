package com.eignex.klause.solver.lp

import com.eignex.klause.solver.lp.cut.Cut
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sqrt

/** A basic LP value within this of an integer carries no Gomory cut. */
private const val TABLEAU_FRAC_TOL = 1e-6

/** Minimum normalized violation at the LP point for a tableau cut to be worth emitting. */
private const val TABLEAU_MIN_VIOLATION = 1e-4

/** Cap on the distinct divisors tried per fractional row (highest-magnitude coefficients first). */
private const val MAX_DIVISORS = 32

/**
 * Gomory / MIR tableau cuts via CP-SAT-style integer-multiplier row aggregation, in 128-bit integer
 * arithmetic — the int128 replacement for the rational tableau-cut path.
 *
 * For each basic structural variable with a fractional LP value, the float tableau row `ρ = B⁻ᵀeᵢ` is
 * rounded to **integer** multipliers at a power-of-two scale ([roundDuals]). Aggregating the original
 * problem rows with those multipliers gives one exact, valid integer constraint
 * `Σₖ gₖ zₖ + Σ_{≤-row r} w_r s_r = H` over the shifted structural columns `zₖ ∈ [0, uₖ]` and the
 * non-negative `≤`-row slacks `s_r` (valid for **any** integer multipliers, since the rows are
 * equalities in slack form). A super-additive rounding function `f` — `⌊·/d⌋` for Chvátal–Gomory, the
 * mixed-integer-rounding function for [mir] — yields the valid inequality `Σ f(gₖ) zₖ + Σ f(w_r) s_r ≤
 * f(H)`; back-substituting `s_r = rhs_r − Σₖ A_{rk} zₖ` (exact, an equality) expresses it over the
 * structural columns alone. The divisor `d` is searched for the one whose cut is most violated at the
 * LP point. Every step is integer / [Int128]; an overflow or a non-violated result drops that
 * candidate, so the returned cuts are always rigorously valid.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
internal fun integerTableauCuts(
    model: LpModel,
    basis: Basis,
    primal: DoubleArray,
    maxCuts: Int,
    mir: Boolean,
): List<Cut> {
    val m = model.m
    val n = model.n
    if (m == 0 || n == 0) return emptyList()

    // Float LU of the basis `B` (its column `t` is the basic column `basic[t]`, which may be a slack);
    // btran gives the tableau rows.
    val rowsMap = Array(m) { HashMap<Int, Double>() }
    for (t in 0 until m) {
        val col = basis.basicVars[t]
        if (col < n) {
            model.forEachInColumn(col) { i, v -> rowsMap[i][t] = v.toDouble() }
        } else {
            rowsMap[col - n][t] = 1.0 // slack column is the unit vector e_{col−n}
        }
    }
    val lu = SparseLu.factorize(rowsMap, m) ?: return emptyList()

    val isLeRow = BooleanArray(m) { !model.hasUpper[model.slackCol(it)] } // ≤-row slack is free above
    val zStar = DoubleArray(n) { primal[it] - model.loShift[it].toDouble() } // shifted LP point

    val cuts = ArrayList<Cut>()
    val unit = DoubleArray(m)
    for (i in 0 until m) {
        if (cuts.size >= maxCuts) break
        val bvar = basis.basicVars[i]
        if (bvar >= n) continue // cut on fractional structural variables only
        if (abs(primal[bvar] - round(primal[bvar])) < TABLEAU_FRAC_TOL) continue // integral ⇒ no cut

        for (r in 0 until m) unit[r] = if (r == i) 1.0 else 0.0
        val w = roundDuals(model, lu.btran(unit))?.mult ?: continue

        var global = true
        var anyWeight = false
        for (r in 0 until m) {
            if (w[r] == 0L) continue
            anyWeight = true
            if (!model.rowGlobal[r]) global = false
        }
        if (!anyWeight) continue

        val cut = bestRoundedCut(model, w, isLeRow, zStar, mir, global) ?: continue
        cuts.add(cut)
    }
    return cuts
}

/** Aggregated coefficient `gₖ = Σ_r w_r·A_{rk}` of structural column [k] (exact, [Int128]). */
private fun aggregatedColumn(model: LpModel, w: LongArray, k: Int): Int128 {
    val g = Int128()
    model.forEachInColumn(k) { r, a -> g.addProduct(w[r], a) }
    return g
}

/** `⌊ H/d ⌋`-shaped super-additive transform of [a] under divisor [d] with remainder [f0]: `⌊a/d⌋` for
 *  Chvátal–Gomory, `(d−f0)·⌊a/d⌋ + max(0, (a mod d) − f0)` (scaled MIR) when [mir]. Null on overflow. */
private fun superAdditive(a: Int128, d: Long, f0: Long, mir: Boolean): Long? {
    val floor = a.floorDivPositive(d) ?: return null
    if (!mir) return floor
    val rem = a.copy()
    rem.addProduct(-d, floor) // a − d·⌊a/d⌋ ∈ [0, d)
    if (!rem.fitsLong()) return null
    val extra = (rem.toLong() - f0).coerceAtLeast(0L)
    val t = Int128()
    t.addProduct(d - f0, floor)
    t.addLong(extra)
    return if (t.fitsLong()) t.toLong() else null
}

/** Build the most-violated valid cut over all candidate divisors for the aggregation [w], or null. */
@Suppress("LongMethod", "ReturnCount")
private fun bestRoundedCut(
    model: LpModel,
    w: LongArray,
    isLeRow: BooleanArray,
    zStar: DoubleArray,
    mir: Boolean,
    global: Boolean,
): Cut? {
    val m = model.m
    val n = model.n

    // H = Σ_r w_r·rhs_r, and the touched structural columns with their aggregated coefficient.
    val hAgg = Int128()
    for (r in 0 until m) if (w[r] != 0L) hAgg.addProduct(w[r], model.rhs[r])
    // The aggregated coefficient gₖ for EVERY structural column: a ≤-row slack back-substitution can put
    // a nonzero coefficient on a column whose gₖ = 0, so the cut loop below must visit all columns.
    val colG = Array(n) { aggregatedColumn(model, w, it) }
    val divisorSet = LinkedHashSet<Long>()
    var anyTouched = false
    for (k in 0 until n) {
        val g = colG[k]
        if (g.fitsLong()) {
            if (g.toLong() != 0L) anyTouched = true
            val mag = abs(g.toLong())
            if (mag > 1L) divisorSet.add(mag)
        } else {
            anyTouched = true
        }
    }
    if (!anyTouched) return null
    val divisors = divisorSet.toLongArray()
    divisors.sort()
    // Highest magnitudes first (likely the basic column), capped.
    val tryDivisors = LongArray(minOf(divisors.size, MAX_DIVISORS)) { divisors[divisors.size - 1 - it] }

    var best: Cut? = null
    var bestScore = TABLEAU_MIN_VIOLATION
    for (d in tryDivisors) {
        val floorH = hAgg.floorDivPositive(d) ?: continue
        val remH = hAgg.copy()
        remH.addProduct(-d, floorH)
        if (!remH.fitsLong()) continue
        val f0 = remH.toLong() // H mod d ∈ [0, d)
        val fH = superAdditive(hAgg, d, f0, mir) ?: continue

        // f(w_r) for the ≤-row slacks, which back-substitution folds into the structural columns / rhs.
        val fw = LongArray(m)
        var fwOk = true
        for (r in 0 until m) {
            if (w[r] == 0L || !isLeRow[r]) continue
            val wr = Int128()
            wr.addLong(w[r])
            val f = superAdditive(wr, d, f0, mir) ?: run { fwOk = false; 0L }
            if (!fwOk) break
            fw[r] = f
        }
        if (!fwOk) continue

        // Cₖ = f(gₖ) − Σ_{≤-row r ∋ k} f(w_r)·A_{rk}; D = f(H) − Σ_{≤-row r} f(w_r)·rhs_r.
        val dAcc = Int128()
        dAcc.addLong(fH)
        for (r in 0 until m) if (fw[r] != 0L) dAcc.addProduct(-fw[r], model.rhs[r])
        if (!dAcc.fitsLong()) continue
        val rhsLe = dAcc.toLong()

        val cutCols = IntArrayList()
        val cutVals = LongArrayList()
        var ok = true
        var dot = 0.0
        for (k in 0 until n) {
            val fg = superAdditive(colG[k], d, f0, mir) ?: run { ok = false; 0L }
            if (!ok) break
            val cAcc = Int128()
            cAcc.addLong(fg)
            model.forEachInColumn(k) { r, a -> if (fw[r] != 0L) cAcc.addProduct(-fw[r], a) }
            if (!cAcc.fitsLong()) { ok = false; break }
            val ck = cAcc.toLong()
            if (ck == 0L) continue
            cutCols.add(k)
            cutVals.add(ck)
            dot += ck.toDouble() * zStar[k]
        }
        if (!ok || cutCols.size == 0) continue

        // Violated iff Σ Cₖ zₖ* > D; score by the violation normalized by the cut's L2 norm.
        val violation = dot - rhsLe.toDouble()
        if (violation <= 0.0) continue
        var norm = 0.0
        for (idx in 0 until cutVals.size) norm += cutVals[idx].toDouble() * cutVals[idx].toDouble()
        norm = sqrt(norm)
        val score = if (norm > 0.0) violation / norm else 0.0
        if (score <= bestScore) continue

        val cut = emitGeCut(model, cutCols, cutVals, rhsLe, global) ?: continue
        best = cut
        bestScore = score
    }
    return best
}

/** Turn the `≤` cut `Σ vals_k·z_k ≤ rhsLe` (shifted columns) into klause's `Σ a_k·x_k ≥ b` form,
 *  unshifting `z_k = x_k − lo_k` and gcd-reducing; null if a coefficient or the rhs overflows `Long`. */
private fun emitGeCut(
    model: LpModel,
    cols: IntArrayList,
    leVals: LongArrayList,
    rhsLe: Long,
    global: Boolean,
): Cut? {
    // Σ vals_k z_k ≤ rhsLe ⇔ Σ (−vals_k) x_k ≥ −rhsLe − Σ vals_k·lo_k.
    val rhsAcc = Int128()
    rhsAcc.addLong(rhsLe)
    for (idx in 0 until cols.size) rhsAcc.addProduct(leVals[idx], model.loShift[cols[idx]])
    // rhsAcc = rhsLe + Σ vals_k·lo_k; the GE rhs is its negation.
    if (!rhsAcc.fitsLong()) return null
    var rhs = -rhsAcc.toLong()

    val count = cols.size
    val outCols = IntArray(count)
    val outVals = LongArray(count)
    var g = 0L
    for (idx in 0 until count) {
        val v = -leVals[idx]
        outCols[idx] = cols[idx]
        outVals[idx] = v
        g = gcdLong(g, v)
    }
    if (g == 0L) return null
    if (g > 1L) {
        for (idx in 0 until count) outVals[idx] /= g
        // Σ (vals/g) x ≥ rhs/g, integral LHS ⇒ ≥ ⌈rhs/g⌉.
        rhs = ceilDiv(rhs, g)
    }
    return Cut(outCols, outVals, Relation.GE, rhs, global)
}

/** `⌈ a / b ⌉` for `b > 0`. */
private fun ceilDiv(a: Long, b: Long): Long {
    val q = a / b
    val r = a % b
    return if (r > 0L) q + 1L else q
}
