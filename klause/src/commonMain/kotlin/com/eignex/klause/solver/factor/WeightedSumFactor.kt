package com.eignex.klause.solver.factor

import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.IntIntMap

/** Common parent of the weighted-sum factors: a running `Σ` in `longPayload(factorId)` with the
 *  shared [holds] / [residual] / [degree] contract over it. */
abstract class WeightedSumFactor : Factor {

    protected abstract fun holds(sum: Long): Boolean

    protected abstract fun residual(sum: Long, softCap: Int): Int

    protected open fun degree(sum: Long, softCap: Int): Int = if (holds(sum)) 0 else residual(sum, softCap)
}

/*
 * Shared violation math and helpers for the weighted-sum factor family — Linear, ReifiedLinear,
 * PseudoBoolean, ReifiedPseudoBoolean. Centralising holds/violates/degree/residual/distance,
 * snapTarget, floorDiv/ceilDiv, and the signedWeightByVar build keeps the running-sum arithmetic
 * in a single place.
 *
 * Critically, every running sum is a Long: with coefficients/weights near 2^20 and domains near
 * 2^12 the weighted total exceeds 32 bits, so a 32-bit accumulator would wrap and make
 * isViolated / violationDegree silently wrong. Keeping the math here means that widening is
 * applied once rather than four times.
 */

/* ------------------------------------------------------------------ *
 *  Linear:  Σ `coeffs[i]` · `vars[i]`  ⟨op⟩  bound
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
internal fun linearResidual(sum: Long, op: LinearOp, bound: Int, softCap: Int): Int = when (op) {
    LinearOp.LE -> compressViolation(sum - bound, softCap)

    LinearOp.GE -> compressViolation(bound.toLong() - sum, softCap)

    LinearOp.EQ -> {
        val d = sum - bound
        compressViolation(if (d < 0) -d else d, softCap)
    }

    LinearOp.NE -> 1
}

/**
 * Graded violation degree: `0` when satisfied, otherwise the [compressViolation]-compressed
 * distance. Computes the signed gap `sum − bound` once and branches on its sign, fusing what would
 * otherwise be a separate [linearHolds] test followed by a [linearResidual] re-derivation.
 */
internal fun linearDegree(sum: Long, op: LinearOp, bound: Int, softCap: Int): Int {
    val d = sum - bound
    return when (op) {
        LinearOp.LE -> if (d <= 0L) 0 else compressViolation(d, softCap)
        LinearOp.GE -> if (d >= 0L) 0 else compressViolation(-d, softCap)
        LinearOp.EQ -> if (d == 0L) 0 else compressViolation(if (d < 0L) -d else d, softCap)
        LinearOp.NE -> if (d != 0L) 0 else 1
    }
}

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
 *  PseudoBoolean:  Σ `weights[i]` · lit_i  ⟨op⟩  bound
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

internal fun signedFlipDelta(state: LocalSearchState, signedByVar: IntIntMap, boolVar: Int, current: Boolean): Int {
    val signed = signedByVar[boolVar]
    if (signed == 0) return 0
    val pre = if (current) state.assignment.boolValue(boolVar) else !state.assignment.boolValue(boolVar)
    return if (pre) -signed else signed
}

internal inline fun reifiedDegree(aux: Boolean, holds: Boolean, violatedDegree: () -> Int): Int = when {
    aux == holds -> 0
    aux -> violatedDegree()
    else -> 1
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
    val seen = IntHashSet(vars.size)
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

/* ------------------------------------------------------------------ *
 *  Bool-literal-bodied reified LS machinery, shared by the two
 *  `auxBoolVar ↔ (predicate over bool literals)` factors —
 *  ReifiedCardinality and ReifiedPseudoBoolean. The running body total
 *  lives in longPayload(factorId); [degreeAt] is the factor's reified
 *  gradient at a (possibly hypothetical) total.
 * ------------------------------------------------------------------ */

// Δ-degree of flipping [boolVar]: an aux flip toggles the indicator side; a body flip shifts the
// total by its signed contribution from [signedByVar].
internal inline fun reifiedBoolDelta(
    state: LocalSearchState,
    factorId: Int,
    boolVar: Int,
    auxBoolVar: Int,
    signedByVar: IntIntMap,
    degreeAt: (total: Long, aux: Boolean, softCap: Int) -> Int,
): Int {
    val aux = state.assignment.boolValue(auxBoolVar)
    val total = state.longPayload[factorId]
    val cap = state.violationSoftCap
    return if (boolVar == auxBoolVar) {
        degreeAt(total, !aux, cap) - degreeAt(total, aux, cap)
    } else {
        val change = signedFlipDelta(state, signedByVar, boolVar, current = true)
        degreeAt(total + change, aux, cap) - degreeAt(total, aux, cap)
    }
}

// Apply form: commits the new total to longPayload(factorId) for a body flip (aux flips leave the
// total unchanged) and returns the Δ-degree.
internal inline fun reifiedBoolApply(
    state: LocalSearchState,
    factorId: Int,
    boolVar: Int,
    auxBoolVar: Int,
    signedByVar: IntIntMap,
    degreeAt: (total: Long, aux: Boolean, softCap: Int) -> Int,
): Int {
    val oldTotal = state.longPayload[factorId]
    val cap = state.violationSoftCap
    if (boolVar == auxBoolVar) {
        val newAux = state.assignment.boolValue(auxBoolVar)
        return degreeAt(oldTotal, newAux, cap) - degreeAt(oldTotal, !newAux, cap)
    }
    val change = signedFlipDelta(state, signedByVar, boolVar, current = false)
    val newTotal = oldTotal + change
    state.longPayload[factorId] = newTotal
    val aux = state.assignment.boolValue(auxBoolVar)
    return degreeAt(newTotal, aux, cap) - degreeAt(oldTotal, aux, cap)
}

// Incremental break/make maintenance after [flippedVar] flipped: recover the pre-flip (total, aux),
// then for each var in [boolVars] update its break/make contribution by the sign of the Δ-degree
// its own flip would produce (the value reifiedBoolDelta returns), pre vs post.
internal inline fun reifiedBoolUpdateBreakMake(
    state: LocalSearchState,
    factorId: Int,
    flippedVar: Int,
    auxBoolVar: Int,
    signedByVar: IntIntMap,
    boolVars: IntArray,
    degreeAt: (total: Long, aux: Boolean, softCap: Int) -> Int,
) {
    val newTotal = state.longPayload[factorId]
    val newAux = state.assignment.boolValue(auxBoolVar)
    val oldAux: Boolean
    val oldTotal: Long
    if (flippedVar == auxBoolVar) {
        oldAux = !newAux
        oldTotal = newTotal
    } else {
        oldAux = newAux
        val signedFlipped = signedByVar[flippedVar]
        if (signedFlipped == 0) return
        val flippedPost = state.assignment.boolValue(flippedVar)
        val changeV = if (flippedPost) signedFlipped else -signedFlipped
        oldTotal = newTotal - changeV
    }
    val cap = state.violationSoftCap
    val oldDeg = degreeAt(oldTotal, oldAux, cap)
    val newDeg = degreeAt(newTotal, newAux, cap)
    for (u in boolVars) {
        val preDelta: Int
        val postDelta: Int
        if (u == auxBoolVar) {
            preDelta = degreeAt(oldTotal, !oldAux, cap) - oldDeg
            postDelta = degreeAt(newTotal, !newAux, cap) - newDeg
        } else {
            val signedU = signedByVar[u]
            if (signedU == 0) {
                preDelta = 0
                postDelta = 0
            } else {
                val uPost = state.assignment.boolValue(u)
                val uPre = if (u == flippedVar) !uPost else uPost
                val preChangeU = if (uPre) -signedU else signedU
                val postChangeU = if (uPost) -signedU else signedU
                preDelta = degreeAt(oldTotal + preChangeU, oldAux, cap) - oldDeg
                postDelta = degreeAt(newTotal + postChangeU, newAux, cap) - newDeg
            }
        }
        val preBreak = preDelta > 0
        val preMake = preDelta < 0
        val postBreak = postDelta > 0
        val postMake = postDelta < 0
        if (preBreak != postBreak) {
            if (postBreak) state.boolBreakCount[u]++ else state.boolBreakCount[u]--
        }
        if (preMake != postMake) {
            if (postMake) state.boolMakeCount[u]++ else state.boolMakeCount[u]--
        }
    }
}
