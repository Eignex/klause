package com.eignex.klause.solver.factor

import com.eignex.klause.ast.PbOp
import com.eignex.klause.solver.Lit
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntIntMap

/*
 * Shared violation math and helpers for the weighted-sum factor family — Linear, ReifiedLinear,
 * PseudoBoolean, ReifiedPseudoBoolean. Previously each factor carried its own copy of
 * holds/violates/degree/residual/distance, snapTarget, a local floorDiv/ceilDiv, and the
 * signedWeightByVar build; that duplication is collapsed here (issue #100) so the running-sum
 * arithmetic lives in a single place.
 *
 * Critically, every running sum is a Long: with coefficients/weights near 2^20 and domains near
 * 2^12 the weighted total exceeds 32 bits, so a 32-bit accumulator would wrap and make
 * isViolated / violationDegree silently wrong (issue #72). Keeping the math here means that
 * widening is applied once rather than four times.
 */

/* ------------------------------------------------------------------ *
 *  Linear:  Σ coeffs[i] · vars[i]  ⟨op⟩  bound
 * ------------------------------------------------------------------ */

/** True iff `sum ⟨op⟩ bound` holds. The negation is the "violated" predicate. */
internal fun linearHolds(sum: Long, op: LinearOp, bound: Int): Boolean = when (op) {
    LinearOp.LE -> sum <= bound
    LinearOp.EQ -> sum == bound.toLong()
    LinearOp.GE -> sum >= bound
    LinearOp.NE -> sum != bound.toLong()
}

/**
 * Graded residual magnitude — the [com.eignex.klause.solver.factor.compressViolation]-compressed
 * distance the sum must move to satisfy the comparison. Defined for any [sum]; callers gate it
 * behind [linearHolds] (a satisfied relation has degree 0). `NE` has no natural magnitude, so it
 * is the unit residual (sum is pinned to `bound`).
 */
internal fun linearResidual(sum: Long, op: LinearOp, bound: Int): Int = when (op) {
    LinearOp.LE -> compressViolation(sum - bound)

    LinearOp.GE -> compressViolation(bound.toLong() - sum)

    LinearOp.EQ -> {
        val d = sum - bound
        compressViolation(if (d < 0) -d else d)
    }

    LinearOp.NE -> 1
}

/** Graded violation degree: `0` when satisfied, otherwise [linearResidual]. */
internal fun linearDegree(sum: Long, op: LinearOp, bound: Int): Int =
    if (linearHolds(sum, op, bound)) 0 else linearResidual(sum, op, bound)

/**
 * Integer value for a single variable (whose other terms sum to [sumWithout], coefficient
 * [coeff]) that drives `coeff·v + sumWithout ⟨op⟩ bound` to the desired side. [wantHolds]`=true`
 * snaps to the value that makes the relation hold; `false` snaps to one that violates it by a
 * single unit (used by the reified factors to explore the opposite reification side). Returns
 * `null` when no integer value achieves the target (e.g. `EQ` with a non-divisible numerator).
 * `Long`-clean: numerator and quotient are computed in 64-bit so wide coefficients can't wrap.
 */
internal fun snapLinearTarget(op: LinearOp, bound: Int, coeff: Int, sumWithout: Long, wantHolds: Boolean): Long? {
    if (coeff == 0) return null
    val c = coeff.toLong()
    val numerator = bound - sumWithout
    val targetEq = numerator / c
    return when (op) {
        LinearOp.EQ -> when {
            wantHolds && numerator % c != 0L -> null
            wantHolds -> targetEq
            else -> targetEq + 1
        }

        LinearOp.LE -> if (wantHolds) {
            if (coeff > 0) floorDivLong(numerator, c) else ceilDivLong(numerator, c)
        } else {
            if (coeff > 0) floorDivLong(numerator, c) + 1 else ceilDivLong(numerator, c) - 1
        }

        LinearOp.GE -> if (wantHolds) {
            if (coeff > 0) ceilDivLong(numerator, c) else floorDivLong(numerator, c)
        } else {
            if (coeff > 0) ceilDivLong(numerator, c) - 1 else floorDivLong(numerator, c) + 1
        }

        LinearOp.NE -> when {
            wantHolds -> if (numerator % c == 0L) targetEq + 1 else null
            numerator % c == 0L -> targetEq
            else -> null
        }
    }
}

/* ------------------------------------------------------------------ *
 *  PseudoBoolean:  Σ weights[i] · lit_i  ⟨op⟩  bound
 * ------------------------------------------------------------------ */

/** True iff `sum ⟨op⟩ bound` holds for a pseudo-Boolean comparison. */
internal fun pbHolds(sum: Long, op: PbOp, bound: Int): Boolean = when (op) {
    PbOp.LE -> sum <= bound
    PbOp.GE -> sum >= bound
    PbOp.EQ -> sum == bound.toLong()
}

/** How far [sum] is from satisfying `⟨op⟩ bound` (`0` when satisfied) — the repair gradient
 *  the pseudo-Boolean factors score candidate flips against. */
internal fun pbDistance(sum: Long, op: PbOp, bound: Int): Long = when (op) {
    PbOp.LE -> if (sum > bound) sum - bound else 0L
    PbOp.GE -> if (sum < bound) bound - sum else 0L
    PbOp.EQ -> if (sum >= bound) sum - bound else bound - sum
}

/**
 * Per-variable signed weight `Σ weight·sign(lit)` over [literals], optionally skipping
 * [exclude] (the reified factors exclude their aux var, which never shifts the body sum).
 * Flipping `v` shifts the running sum by `±signed[v]`, computed in O(1) instead of scanning
 * the whole factor. Shared by [PseudoBoolean] and [ReifiedPseudoBoolean].
 */
internal fun buildSignedWeightByVar(weights: IntArray, literals: IntArray, exclude: Int = -1): IntIntMap {
    val signs = HashMap<Int, Int>()
    for (i in literals.indices) {
        val v = Lit.variable(literals[i])
        if (v == exclude) continue
        val s = if (Lit.isPositive(literals[i])) weights[i] else -weights[i]
        signs[v] = (signs[v] ?: 0) + s
    }
    return IntIntMap.build(
        keys = signs.keys.toIntArray(),
        values = signs.values.toIntArray(),
        absent = 0,
    )
}

/* ------------------------------------------------------------------ *
 *  Duplicate-variable coalescing for the int-weighted-sum factors
 * ------------------------------------------------------------------ */

/** A `(vars, coeffs)` term list with each variable appearing at most once. */
internal class CoalescedTerms(val vars: IntArray, val coeffs: IntArray)

/**
 * Sum coefficients per distinct variable, preserving first-occurrence order, so the int-weighted
 * sum is carried by one entry per variable. The MiniZinc compiler already coalesces (via
 * `coeffsToArrays`), but the XCSP3 and direct-API construction paths can hand the same variable
 * twice (`2x + 3x ≤ b`). Left split, the local-search payload desyncs: [Linear.initialize] sums
 * every index (`5·x`) while the O(1) [CoeffLookup] returns a single entry's coefficient (`3`), so
 * a move on `x` shifts the payload by only `3·Δ` and `isViolated` / `violationDegree` go silently
 * wrong (issue #84). Coalescing in the [Linear] / [ReifiedLinear] constructor makes the factor
 * robust regardless of caller.
 *
 * Returns the inputs unchanged (same arrays) when no variable repeats, so the common distinct-var
 * path allocates nothing. The summed coefficient is accumulated in `Long` and required to fit
 * `Int` — consistent with the rest of the family's overflow discipline (issue #72).
 */
internal fun coalesceLinearTerms(vars: IntArray, coeffs: IntArray): CoalescedTerms {
    require(vars.size == coeffs.size) { "coeffs/vars length mismatch" }
    val seen = HashSet<Int>(vars.size)
    var hasDuplicate = false
    for (v in vars) {
        if (!seen.add(v)) {
            hasDuplicate = true
            break
        }
    }
    if (!hasDuplicate) return CoalescedTerms(vars, coeffs)

    val order = IntArrayList(vars.size)
    val sums = HashMap<Int, Long>(vars.size)
    for (i in vars.indices) {
        val v = vars[i]
        if (v !in sums) order.add(v)
        sums[v] = (sums[v] ?: 0L) + coeffs[i].toLong()
    }
    val outVars = order.toIntArray()
    val outCoeffs = IntArray(outVars.size) { idx ->
        val s = sums.getValue(outVars[idx])
        require(s in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            "coalesced coefficient overflow for var ${outVars[idx]}: $s"
        }
        s.toInt()
    }
    return CoalescedTerms(outVars, outCoeffs)
}
