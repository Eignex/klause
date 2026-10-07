package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.arithmetic.internals.ceilDiv
import com.eignex.klause.factor.arithmetic.internals.floorDiv
import com.eignex.klause.factor.bool.internals.reifiedDegree
import com.eignex.klause.factor.compressViolation
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Int128
import com.eignex.klause.util.IntIntMap
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.div
import com.eignex.klause.util.magnitudeBitLength
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
import com.eignex.klause.util.rem
import com.eignex.klause.util.shl
import com.eignex.klause.util.signum
import com.eignex.klause.util.times
import com.eignex.klause.util.toDouble
import com.eignex.klause.util.toLong
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.sign

/**
 * LS invariant for a [Linear] or [ReifiedLinear] row whose running sum a `Long` might not hold: over-64-bit
 * constants, or terms whose coefficients and domains can carry `Σ coeff·value` past the 64-bit range.
 *
 * That range is a worst case over the whole domain, and the sums search actually visits are usually far smaller.
 * So a row whose constants fit a `Long` keeps its sum in the narrowest exact form that holds it: a `Long` updated
 * with overflow-checked arithmetic, an [Int128] once a step leaves 64 bits, and a [BigInt] only once a step
 * leaves 128. Each step back inside a narrower range returns the sum to it. A row with over-64-bit constants
 * always sums in [BigInt]. Whatever the form, the violation and move deltas are the row's own, exactly.
 */
internal class ExactLinearInvariant(
    constants: IntegralConstants,
    private val vars: IntArray,
    private val op: LinearOp,
    /** The reifying Boolean, or -1 for a plain row. */
    private val auxBoolVar: Int = -1,
) : Invariant {
    private val coeffs: Array<BigInt> = Array(vars.size) { constants.exactCoeff(it) }
    private val bound: BigInt = constants.exactBound

    // The constants as `Long`s when they fit, which is what lets the sum live in a `Long` or an [Int128].
    private val longCoeffs: LongArray? =
        (constants as? IntegerConstants)?.let { c -> LongArray(vars.size) { c.coeff(it) } }
    private val longBound: Long = (constants as? IntegerConstants)?.bound ?: 0L

    // [longBound] in 128 bits, read only, so the residual of a wide sum is one subtraction.
    private val wideBound: Int128 = Int128().apply { addLong(longBound) }
    private val index = IntIntMap.build(vars, IntArray(vars.size) { it }, absent = -1)

    /** One row's sum in the narrowest exact form, with scratch space so scoring a move allocates nothing. */
    private class Sum {
        var form: Int = LONG
        var long: Long = 0L
        val wide = Int128()
        var big: BigInt = BIG_ZERO

        // Scratch: a candidate sum, and one term of a step.
        val candidate = Int128()
        val term = Int128()
    }

    private fun sumOf(state: LocalSearchState, factorId: Int): Sum = state.refPayload[factorId] as Sum

    private fun coeffOf(intVar: Int): BigInt {
        val i = index[intVar]
        return if (i < 0) BIG_ZERO else coeffs[i]
    }

    private fun longCoeffOf(intVar: Int): Long {
        val i = index[intVar]
        return if (i < 0) 0L else checkNotNull(longCoeffs)[i]
    }

    // The row's sum as a [BigInt], whatever form holds it.
    private fun exact(sum: Sum): BigInt = when (sum.form) {
        LONG -> bigIntOf(sum.long)
        WIDE -> toBigInt(sum.wide)
        else -> sum.big
    }

    // Store [value], in the narrowest form the constants and the value allow.
    private fun store(sum: Sum, value: BigInt) {
        if (longCoeffs != null && value.magnitudeBitLength() < Long.SIZE_BITS) {
            sum.form = LONG
            sum.long = value.toLong()
        } else {
            sum.form = BIG
            sum.big = value
        }
    }

    // Whether the body holds when `sum - bound` has sign [sign].
    private fun holdsSign(sign: Int): Boolean = when (op) {
        LinearOp.LE -> sign <= 0
        LinearOp.GE -> sign >= 0
        LinearOp.EQ -> sign == 0
        LinearOp.NE -> sign != 0
    }

    // The body's degree when `sum - bound` has sign [sign] and magnitude [magnitude], saturated to a `Long`.
    private fun bodyDegree(sign: Int, magnitude: Long, softCap: Int): Int = when (op) {
        LinearOp.NE -> if (sign != 0) 0 else 1
        else -> if (holdsSign(sign)) 0 else compressViolation(magnitude, softCap)
    }

    private fun degreeOf(sign: Int, magnitude: Long, aux: Boolean, softCap: Int): Int = if (auxBoolVar < 0) {
        bodyDegree(sign, magnitude, softCap)
    } else {
        reifiedDegree(aux, holdsSign(sign)) { bodyDegree(sign, magnitude, softCap) }
    }

    private fun degree(sum: BigInt, aux: Boolean, softCap: Int): Int {
        val residual = sum - bound
        return degreeOf(residual.signum(), saturatedMagnitude(residual), aux, softCap)
    }

    // The degree of the 128-bit value in [value], which this consumes as scratch: it is turned into the residual.
    private fun degreeOfWide(value: Int128, aux: Boolean, softCap: Int): Int {
        value.subtract(wideBound)
        val sign = when {
            value.hi < 0L -> -1
            value.hi == 0L && value.lo == 0L -> 0
            else -> 1
        }
        val magnitude = if (value.fitsLong()) magnitude(value.lo) else Long.MAX_VALUE
        return degreeOf(sign, magnitude, aux, softCap)
    }

    // The degree of [sum] as it stands.
    private fun currentDegree(sum: Sum, aux: Boolean, softCap: Int): Int {
        when (sum.form) {
            LONG -> {
                val residual = sum.long - longBound
                if (!subOverflows(sum.long, longBound, residual)) {
                    return degreeOf(residual.sign, magnitude(residual), aux, softCap)
                }
                sum.candidate.clear()
                sum.candidate.addLong(sum.long)
                return degreeOfWide(sum.candidate, aux, softCap)
            }

            WIDE -> {
                sum.candidate.clear()
                sum.candidate.add(sum.wide)
                return degreeOfWide(sum.candidate, aux, softCap)
            }

            else -> return degree(sum.big, aux, softCap)
        }
    }

    // Leave in [Sum.candidate] the sum after [intVar] moves from [old] to [new]; false when that leaves 128 bits.
    private fun stepWide(sum: Sum, intVar: Int, old: Long, new: Long): Boolean {
        val c = longCoeffOf(intVar)
        val candidate = sum.candidate
        candidate.clear()
        if (sum.form == LONG) candidate.addLong(sum.long) else candidate.add(sum.wide)
        candidate.addProduct(c, new)
        sum.term.clear()
        sum.term.addProduct(c, old)
        candidate.subtract(sum.term)
        return !candidate.overflow
    }

    private fun aux(state: LocalSearchState): Boolean = auxBoolVar >= 0 && state.assignment.boolValue(auxBoolVar)

    override fun initialize(state: LocalSearchState, factorId: Int) {
        var value = BIG_ZERO
        for (i in vars.indices) value += coeffs[i] * bigIntOf(state.assignment.intValue(vars[i]))
        store(Sum().also { state.refPayload[factorId] = it }, value)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        currentDegree(sumOf(state, factorId), aux(state), state.violationSoftCap) > 0

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        currentDegree(sumOf(state, factorId), aux(state), state.violationSoftCap)

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int {
        val sum = sumOf(state, factorId)
        val old = state.assignment.intValue(intVar)
        val aux = aux(state)
        val softCap = state.violationSoftCap
        val c = if (longCoeffs != null) longCoeffOf(intVar) else 0L
        val degree = when {
            sum.form == LONG && longStep(sum.long, c, old, newValue) -> {
                val next = sum.long + c * (newValue - old)
                val residual = next - longBound
                if (subOverflows(next, longBound, residual)) {
                    sum.candidate.clear()
                    sum.candidate.addLong(next)
                    degreeOfWide(sum.candidate, aux, softCap)
                } else {
                    degreeOf(residual.sign, magnitude(residual), aux, softCap)
                }
            }

            sum.form != BIG && stepWide(sum, intVar, old, newValue) -> degreeOfWide(sum.candidate, aux, softCap)

            else -> {
                val step = bigIntOf(newValue) - bigIntOf(old)
                degree(exact(sum) + coeffOf(intVar) * step, aux, softCap)
            }
        }
        return degree - state.factorDegree[factorId]
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Int {
        val sum = sumOf(state, factorId)
        val new = state.assignment.intValue(intVar)
        val aux = aux(state)
        val softCap = state.violationSoftCap
        val before = currentDegree(sum, aux, softCap)
        val c = if (longCoeffs != null) longCoeffOf(intVar) else 0L
        when {
            sum.form == LONG && longStep(sum.long, c, oldValue, new) -> sum.long += c * (new - oldValue)

            sum.form != BIG && stepWide(sum, intVar, oldValue, new) -> {
                if (sum.candidate.fitsLong()) {
                    sum.form = LONG
                    sum.long = sum.candidate.toLong()
                } else {
                    sum.form = WIDE
                    sum.wide.clear()
                    sum.wide.add(sum.candidate)
                }
            }

            else -> {
                val step = bigIntOf(new) - bigIntOf(oldValue)
                store(sum, exact(sum) + coeffOf(intVar) * step)
            }
        }
        return currentDegree(sum, aux, softCap) - before
    }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (boolVar != auxBoolVar) return 0
        return currentDegree(sumOf(state, factorId), !aux(state), state.violationSoftCap) - state.factorDegree[factorId]
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (boolVar != auxBoolVar) return 0
        val sum = sumOf(state, factorId)
        val aux = aux(state)
        return currentDegree(sum, aux, state.violationSoftCap) - currentDegree(sum, !aux, state.violationSoftCap)
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val sum = sumOf(state, factorId)
        val aux = aux(state)
        // The body must hold for a plain row or a true reifier, and fail for a false one.
        val want = auxBoolVar < 0 || aux
        if (holdsSign(residualSign(sum)) == want) return
        if (auxBoolVar >= 0) sink.addBoolFlip(auxBoolVar)
        // A wide-constant row works its targets out exactly; every other row only proposes them, and each proposal
        // is scored exactly before it is taken, so a target that lands one value off costs a candidate, not a fact.
        val exactSum = if (longCoeffs == null) exact(sum) else null
        val n = vars.size
        val sampled = n > REPAIR_SAMPLE_CAP
        repeat(if (sampled) REPAIR_SAMPLE_CAP else n) { k ->
            val i = if (sampled) state.rng.nextInt(n) else k
            val v = vars[i]
            val c = coeffs[i]
            if (c == BIG_ZERO) return@repeat
            val cur = state.assignment.intValue(v)
            val d = state.rootDomains[v]
            if (op == LinearOp.NE || (op == LinearOp.EQ && !want)) {
                if (cur > d.min) sink.addChannelingIntSet(state, v, d.lower(cur))
                if (cur < d.max) sink.addChannelingIntSet(state, v, d.higher(cur))
                return@repeat
            }
            val clamped = if (exactSum != null) {
                val target = target(c, bound - (exactSum - c * bigIntOf(cur)), want) ?: return@repeat
                clampExact(d, target)
            } else {
                d.clamp(proposedTarget(sum, checkNotNull(longCoeffs)[i], cur, want) ?: return@repeat)
            }
            if (clamped != cur) sink.addChannelingIntSet(state, v, clamped)
        }
    }

    // The sign of `sum - bound`, whatever form holds the sum.
    private fun residualSign(sum: Sum): Int = when (sum.form) {
        LONG -> {
            val residual = sum.long - longBound
            if (!subOverflows(sum.long, longBound, residual)) {
                residual.sign
            } else {
                // Overflow means the operands' signs differ, so the residual has the sign of the sum.
                sum.long.sign
            }
        }

        WIDE -> {
            sum.candidate.clear()
            sum.candidate.add(sum.wide)
            sum.candidate.subtract(wideBound)
            if (sum.candidate.hi < 0L) {
                -1
            } else if (sum.candidate.hi == 0L && sum.candidate.lo == 0L) {
                0
            } else {
                1
            }
        }

        else -> (sum.big - bound).signum()
    }

    // The value of the term `c·x`, now at [cur], that brings the body to [want]: exact when `bound − (sum − c·cur)`
    // fits a `Long`, else estimated in `Double`. Null when an equality has no integer value for it.
    private fun proposedTarget(sum: Sum, c: Long, cur: Long, want: Boolean): Long? {
        if (sum.form == LONG) {
            val term = c * cur
            val rest = sum.long - term
            val remaining = longBound - rest
            val fits = !mulOverflows(c, cur, term) && !subOverflows(sum.long, term, rest) &&
                !subOverflows(longBound, rest, remaining) && !(remaining == Long.MIN_VALUE && c == -1L)
            if (fits) return longTarget(c, remaining, want)
        }
        val remaining = longBound.toDouble() - (approximate(sum) - c.toDouble() * cur.toDouble())
        return doubleTarget(c, remaining / c.toDouble(), want)
    }

    private fun approximate(sum: Sum): Double = when (sum.form) {
        LONG -> sum.long.toDouble()
        WIDE -> sum.wide.hi.toDouble() * TWO_TO_64_DOUBLE + sum.wide.lo.toULong().toDouble()
        else -> sum.big.toDouble()
    }

    // [target] in `Long` arithmetic, for a [remaining] that fits.
    private fun longTarget(c: Long, remaining: Long, want: Boolean): Long? {
        val positive = c > 0L
        val floor = remaining.floorDiv(c)
        val ceil = if (remaining % c == 0L) floor else floor + 1L
        return when (op) {
            LinearOp.EQ -> if (remaining % c == 0L) floor else null

            LinearOp.LE -> when {
                want -> if (positive) floor else ceil
                positive -> floor + 1L
                else -> ceil - 1L
            }

            LinearOp.GE -> when {
                want -> if (positive) ceil else floor
                positive -> ceil - 1L
                else -> floor + 1L
            }

            LinearOp.NE -> null
        }
    }

    // [longTarget] from a quotient estimated in `Double`, saturated to the `Long` range.
    private fun doubleTarget(c: Long, quotient: Double, want: Boolean): Long? {
        if (quotient.isNaN()) return null
        val positive = c > 0L
        val floor = saturatedLong(floor(quotient))
        val ceil = saturatedLong(ceil(quotient))
        return when (op) {
            LinearOp.EQ -> saturatedLong(round(quotient))

            LinearOp.LE -> when {
                want -> if (positive) floor else ceil
                positive -> floor + if (floor == Long.MAX_VALUE) 0L else 1L
                else -> ceil - if (ceil == Long.MIN_VALUE) 0L else 1L
            }

            LinearOp.GE -> when {
                want -> if (positive) ceil else floor
                positive -> ceil - if (ceil == Long.MIN_VALUE) 0L else 1L
                else -> floor + if (floor == Long.MAX_VALUE) 0L else 1L
            }

            LinearOp.NE -> null
        }
    }

    // The value of a term with coefficient [c] that brings the body to [want], given [remaining] = bound minus the
    // rest of the row; null when no integer value of the term satisfies an equality. Mirrors snapLinearTarget.
    private fun target(c: BigInt, remaining: BigInt, want: Boolean): BigInt? {
        val positive = c > BIG_ZERO
        return when (op) {
            LinearOp.EQ -> if (remaining % c == BIG_ZERO) remaining / c else null

            LinearOp.LE -> when {
                want -> if (positive) floorDiv(remaining, c) else ceilDiv(remaining, c)
                positive -> floorDiv(remaining, c) + BIG_ONE
                else -> ceilDiv(remaining, c) - BIG_ONE
            }

            LinearOp.GE -> when {
                want -> if (positive) ceilDiv(remaining, c) else floorDiv(remaining, c)
                positive -> ceilDiv(remaining, c) - BIG_ONE
                else -> floorDiv(remaining, c) + BIG_ONE
            }

            LinearOp.NE -> null
        }
    }

    private companion object {
        // Repair candidates drawn per pick on a wide row, as [LinearInvariant] does.
        const val REPAIR_SAMPLE_CAP: Int = 256

        fun saturated(value: BigInt): Long = when {
            value.magnitudeBitLength() < Long.SIZE_BITS -> value.toLong()
            value.signum() > 0 -> Long.MAX_VALUE
            else -> Long.MIN_VALUE
        }

        // |value|, saturated to `Long.MAX_VALUE` exactly as [magnitude] saturates a `Long`.
        fun saturatedMagnitude(value: BigInt): Long =
            if (value.magnitudeBitLength() < Long.SIZE_BITS) magnitude(value.toLong()) else Long.MAX_VALUE

        fun clampExact(d: IntDomain, target: BigInt): Long = d.clamp(saturated(target))

        // The forms a row's sum takes, narrowest first.
        const val LONG: Int = 0
        const val WIDE: Int = 1
        const val BIG: Int = 2

        val TWO_TO_64: BigInt = BIG_ONE shl 64
        const val TWO_TO_64_DOUBLE: Double = 18446744073709551616.0

        fun saturatedLong(value: Double): Long = when {
            value >= Long.MAX_VALUE.toDouble() -> Long.MAX_VALUE
            value <= Long.MIN_VALUE.toDouble() -> Long.MIN_VALUE
            else -> value.toLong()
        }

        // Whether `sum + c·(new − old)` stays inside 64 bits at every step.
        fun longStep(sum: Long, c: Long, old: Long, new: Long): Boolean {
            val step = new - old
            if (subOverflows(new, old, step)) return false
            val term = c * step
            if (mulOverflows(c, step, term)) return false
            return !addOverflows(sum, term, sum + term)
        }

        fun toBigInt(value: Int128): BigInt = bigIntOf(value.hi) * TWO_TO_64 + bigIntOf(value.lo.toULong())

        fun addOverflows(a: Long, b: Long, sum: Long): Boolean = (a xor sum) and (b xor sum) < 0L

        fun subOverflows(a: Long, b: Long, difference: Long): Boolean = (a xor b) and (a xor difference) < 0L

        // Two factors under 2^31 in magnitude cannot overflow, which spares the division on the common step.
        fun mulOverflows(a: Long, b: Long, product: Long): Boolean {
            if (((a + HALF_RANGE) or (b + HALF_RANGE)) ushr 32 == 0L) return false
            return a != 0L && (product / a != b || (a == -1L && b == Long.MIN_VALUE))
        }

        const val HALF_RANGE: Long = 1L shl 31

        // |value|, with `Long.MIN_VALUE`'s magnitude saturated to `Long.MAX_VALUE`.
        fun magnitude(value: Long): Long = if (value == Long.MIN_VALUE) Long.MAX_VALUE else abs(value)
    }
}

/**
 * Whether a row's running sum can leave the 64-bit range over [domains]: `|bound| + Σ |coeff|·max(|min|, |max|)`
 * reaches 2^61. Below that, every partial sum and every move delta `coeff·(new − old)` fits a `Long`, so the `Long`
 * invariant is exact and the row pays nothing for being exact. Estimated in `Double`, whose rounding is far inside
 * the factor of two between this limit and the `Long` range.
 */
internal fun needsExactSum(constants: IntegralConstants, vars: IntArray, domains: Array<IntDomain>): Boolean {
    if (constants !is IntegerConstants) return true
    var total = abs(constants.bound.toDouble())
    for (i in vars.indices) {
        val d = domains[vars[i]]
        val reach = maxOf(abs(d.min.toDouble()), abs(d.max.toDouble()))
        total += abs(constants.coeff(i).toDouble()) * reach
        if (total >= EXACT_SUM_LIMIT) return true
    }
    return false
}

private const val EXACT_SUM_LIMIT: Double = (1L shl 61).toDouble()
