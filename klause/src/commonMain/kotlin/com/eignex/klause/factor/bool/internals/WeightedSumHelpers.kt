package com.eignex.klause.factor.bool.internals

import com.eignex.klause.factor.CoeffLookup
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.internals.ceilDivLong
import com.eignex.klause.factor.arithmetic.internals.floorDivLong
import com.eignex.klause.factor.compressViolation
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.solver.Lit
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.IntIntMap
import com.eignex.klause.util.MutableIntIntMap

internal fun linearHolds(sum: Long, op: LinearOp, bound: Int): Boolean = when (op) {
    LinearOp.LE -> sum <= bound
    LinearOp.EQ -> sum == bound.toLong()
    LinearOp.GE -> sum >= bound
    LinearOp.NE -> sum != bound.toLong()
}

internal fun linearDegree(sum: Long, op: LinearOp, bound: Int, softCap: Int): Int {
    val d = sum - bound
    return when (op) {
        LinearOp.LE -> if (d <= 0L) 0 else compressViolation(d, softCap)
        LinearOp.GE -> if (d >= 0L) 0 else compressViolation(-d, softCap)
        LinearOp.EQ -> if (d == 0L) 0 else compressViolation(if (d < 0L) -d else d, softCap)
        LinearOp.NE -> if (d != 0L) 0 else 1
    }
}

internal fun pbHolds(sum: Long, op: PbOp, bound: Int): Boolean = when (op) {
    PbOp.LE -> sum <= bound
    PbOp.GE -> sum >= bound
    PbOp.EQ -> sum == bound.toLong()
}

internal fun pbDistance(sum: Long, op: PbOp, bound: Int): Long = when (op) {
    PbOp.LE -> if (sum > bound) sum - bound else 0L
    PbOp.GE -> if (sum < bound) bound - sum else 0L
    PbOp.EQ -> if (sum >= bound) sum - bound else bound - sum
}

internal inline fun reifiedDegree(aux: Boolean, holds: Boolean, violatedDegree: () -> Int): Int = when {
    aux == holds -> 0
    aux -> violatedDegree()
    else -> 1
}

internal class CoalescedTerms(val vars: IntArray, val coeffs: IntArray)

// XCSP3 and direct-API callers can pass the same var twice; without coalescing the LS payload desyncs.
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

    // Coalesce in first-occurrence order with primitive maps. A boxed `HashMap<Int, Long>` here
    // dominated presolve on dense linear systems, where affine folding builds one coalesced row per
    // eliminated-variable incidence. `slotOf` maps a variable to its slot in [order]; [sums] accumulates
    // per slot. A zero-sum term is kept (a variable that appears stays), matching the boxed version.
    val order = IntArrayList(vars.size)
    val slotOf = MutableIntIntMap(vars.size * 2)
    val sums = LongArray(vars.size)
    for (i in vars.indices) {
        val v = vars[i]
        var slot = slotOf.getOrDefault(v, -1)
        if (slot < 0) {
            slot = order.size
            slotOf.put(v, slot)
            order.add(v)
        }
        sums[slot] += coeffs[i].toLong()
    }
    val outVars = order.toIntArray()
    val outCoeffs = IntArray(outVars.size) { idx ->
        val s = sums[idx]
        require(s in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            "coalesced coefficient overflow for var ${outVars[idx]}: $s"
        }
        s.toInt()
    }
    return CoalescedTerms(outVars, outCoeffs)
}

internal fun linearResidual(sum: Long, op: LinearOp, bound: Int, softCap: Int): Int = when (op) {
    LinearOp.LE -> compressViolation(sum - bound, softCap)

    LinearOp.GE -> compressViolation(bound.toLong() - sum, softCap)

    LinearOp.EQ -> {
        val d = sum - bound
        compressViolation(if (d < 0) -d else d, softCap)
    }

    LinearOp.NE -> 1
}

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

internal fun pbDegree(sum: Long, op: PbOp, bound: Int, softCap: Int): Int {
    if (pbHolds(sum, op, bound)) return 0
    return compressViolation(pbDistance(sum, op, bound), softCap)
}

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

internal fun pbFalseFormAntecedents(
    state: PropagationState,
    literals: IntArray,
    excludeVar: Int,
    extraLit: Int, // 0 == no extra literal
): IntArray? {
    var n = 0
    if (extraLit != 0) n++
    val seen = IntHashSet()
    for (lit in literals) {
        val v = Lit.variable(lit)
        if (v == excludeVar) continue
        if (extraLit != 0 && v == Lit.variable(extraLit)) continue
        if (!seen.add(v)) continue
        if (state.boolValues[v] != null) n++
    }
    if (n == 0) return null
    val out = IntArray(n)
    var w = 0
    if (extraLit != 0) out[w++] = extraLit
    seen.clear()
    for (lit in literals) {
        val v = Lit.variable(lit)
        if (v == excludeVar) continue
        if (extraLit != 0 && v == Lit.variable(extraLit)) continue
        if (!seen.add(v)) continue
        val b = state.boolValues[v] ?: continue
        out[w++] = Lit.make(v, !b)
    }
    return out
}

// extraLit threads a reif-var pin into each implied propagation's antecedents (0 = none).
internal fun propagatePbBounds(
    state: PropagationState,
    weights: IntArray,
    literals: IntArray,
    op: PbOp,
    bound: Long,
    extraLit: Int = 0,
): Boolean {
    val n = literals.size
    val litLo = LongArray(n)
    val litHi = LongArray(n)
    var sumLo = 0L
    var sumHi = 0L
    for (i in 0 until n) {
        val w = weights[i].toLong()
        val v = Lit.variable(literals[i])
        val b = state.boolValues[v]
        val lo: Long
        val hi: Long
        when {
            b == null -> {
                lo = minOf(0L, w)
                hi = maxOf(0L, w)
            }

            Lit.evaluate(literals[i], b) -> {
                lo = w
                hi = w
            }

            else -> {
                lo = 0L
                hi = 0L
            }
        }
        litLo[i] = lo
        litHi[i] = hi
        sumLo += lo
        sumHi += hi
    }
    when (op) {
        PbOp.LE -> if (sumLo > bound) return false
        PbOp.GE -> if (sumHi < bound) return false
        PbOp.EQ -> if (sumLo > bound || sumHi < bound) return false
    }
    for (i in 0 until n) {
        val w = weights[i].toLong()
        if (w == 0L) continue
        val v = Lit.variable(literals[i])
        if (state.boolValues[v] != null) continue
        val otherLo = sumLo - litLo[i]
        val otherHi = sumHi - litHi[i]
        val trueOk = pbFeasible(op, otherLo + w, otherHi + w, bound)
        val falseOk = pbFeasible(op, otherLo, otherHi, bound)
        if (!trueOk && !falseOk) return false
        if (!trueOk) {
            val ant = pbFalseFormAntecedents(state, literals, excludeVar = v, extraLit = extraLit)
            if (!state.pinBool(v, !Lit.isPositive(literals[i]), ant)) return false
        } else if (!falseOk) {
            val ant = pbFalseFormAntecedents(state, literals, excludeVar = v, extraLit = extraLit)
            if (!state.pinBool(v, Lit.isPositive(literals[i]), ant)) return false
        }
    }
    return true
}

private fun pbFeasible(op: PbOp, lo: Long, hi: Long, bound: Long): Boolean = when (op) {
    PbOp.LE -> lo <= bound
    PbOp.GE -> hi >= bound
    PbOp.EQ -> lo <= bound && hi >= bound
}

internal fun pbSumRange(state: PropagationState, weights: IntArray, literals: IntArray): LongArray {
    var lo = 0L
    var hi = 0L
    for (i in literals.indices) {
        val w = weights[i].toLong()
        val v = Lit.variable(literals[i])
        val b = state.boolValues[v]
        when {
            b == null -> {
                lo += minOf(0L, w)
                hi += maxOf(0L, w)
            }

            Lit.evaluate(literals[i], b) -> {
                lo += w
                hi += w
            }

            else -> {}
        }
    }
    return longArrayOf(lo, hi)
}

internal fun buildParityByVar(boolVars: IntArray, literals: IntArray): CoeffLookup {
    val parities = IntArray(boolVars.size)
    for (i in boolVars.indices) {
        var n = 0
        for (lit in literals) if (Lit.variable(lit) == boolVars[i]) n++
        parities[i] = n and 1
    }
    return CoeffLookup.build(boolVars, parities)
}

internal fun buildSignedLitsByVar(literals: IntArray, exclude: Int = -1): IntIntMap {
    val signs = HashMap<Int, Int>()
    for (lit in literals) {
        val v = Lit.variable(lit)
        if (v == exclude) continue
        signs[v] = (signs[v] ?: 0) + if (Lit.isPositive(lit)) 1 else -1
    }
    return IntIntMap.build(keys = signs.keys.toIntArray(), values = signs.values.toIntArray(), absent = 0)
}

internal inline fun nonReifiedBoolUpdateBreakMakeLoop(
    state: LocalSearchState,
    flippedVar: Int,
    signedByVar: IntIntMap,
    boolVars: IntArray,
    oldSum: Long,
    newSum: Long,
    degreeAt: (sum: Long, softCap: Int) -> Int,
) {
    val cap = state.violationSoftCap
    val oldDeg = degreeAt(oldSum, cap)
    val newDeg = degreeAt(newSum, cap)
    for (u in boolVars) {
        val signedU = signedByVar[u]
        if (signedU == 0) continue
        val uPost = state.assignment.boolValue(u)
        val uPre = if (u == flippedVar) !uPost else uPost
        val oldChangeU = if (uPre) -signedU else signedU
        val newChangeU = if (uPost) -signedU else signedU
        val preDelta = degreeAt(oldSum + oldChangeU, cap) - oldDeg
        val postDelta = degreeAt(newSum + newChangeU, cap) - newDeg
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
