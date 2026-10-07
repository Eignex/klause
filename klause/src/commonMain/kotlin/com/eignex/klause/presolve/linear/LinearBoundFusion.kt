package com.eignex.klause.presolve.linear

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.impliedLinearRows
import com.eignex.klause.ir.linearRows
import com.eignex.klause.presolve.PassDelta
import com.eignex.klause.presolve.PresolveShared
import com.eignex.klause.presolve.integralLinear
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.abs
import com.eignex.klause.util.addExact
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.div
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.gcd
import com.eignex.klause.util.maxOf
import com.eignex.klause.util.minOf
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
import com.eignex.klause.util.rem
import com.eignex.klause.util.subExact
import com.eignex.klause.util.times
import com.eignex.klause.util.toLongExact
import com.eignex.klause.util.unaryMinus

internal object LinearBoundFusion {
    private class Group(val terms: Terms) {
        var upper: BigInt? = null
        var lower: BigInt? = null
        var hasEquality = false
        val droppable = ArrayList<Int>()
    }

    fun fuseLinearBounds(problem: Problem): PassDelta {
        val groups = HashMap<Terms, Group>()
        for ((index, factor) in problem.factors.withIndex()) {
            for (row in factor.impliedLinearRows) {
                val canonical = canonicalize(row) ?: continue
                val group = groups.getOrPut(canonical.terms) {
                    Group(canonical.terms)
                }
                if (row.relation == LinearOp.EQ || canonical.upper) {
                    group.upper = group.upper?.let { minOf(it, canonical.bound) } ?: canonical.bound
                }
                if (row.relation == LinearOp.EQ || !canonical.upper) {
                    group.lower = group.lower?.let { maxOf(it, canonical.bound) } ?: canonical.bound
                }
                if (row.relation == LinearOp.EQ) {
                    group.hasEquality = true
                } else if (factor.linearForm is LinearForm.Conjunction && factor.linearRows.size == 1) {
                    group.droppable.add(index)
                }
            }
        }
        val dropped = ArrayList<Int>()
        val added = ArrayList<Factor>()
        for (group in groups.values) {
            val lower = group.lower ?: continue
            val upper = group.upper ?: continue
            if (lower > upper) return PassDelta(infeasible = true)
            if (lower == upper && !group.hasEquality) {
                dropped.addAll(group.droppable)
                added.add(integralLinear(group.terms.vars, group.terms.exactCoefficients(), LinearOp.EQ, upper))
            }
        }
        return PassDelta(droppedIndices = dropped.toIntArray(), addedFactors = added)
    }

    private class Terms(
        val vars: IntArray,
        private val coefficients: LongArray?,
        private val wideCoefficients: Array<BigInt>? = null,
    ) {
        private val hash =
            31 * vars.contentHashCode() + (coefficients?.contentHashCode() ?: wideCoefficients.contentHashCode())

        fun exactCoefficients(): Array<BigInt> = wideCoefficients ?: Array(vars.size) {
            bigIntOf(checkNotNull(coefficients)[it])
        }

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean = other is Terms && vars.contentEquals(other.vars) &&
            coefficients.contentEquals(other.coefficients) && wideCoefficients.contentEquals(other.wideCoefficients)
    }

    private class Canonical(val terms: Terms, val bound: BigInt, val upper: Boolean)

    private fun canonicalize(row: LinearRow): Canonical? {
        val constants = row.constants as? IntegralConstants ?: return null
        if (!row.isIntegerOnly || row.activator != LinearRow.ALWAYS || row.relation == LinearOp.NE) return null
        if (constants is IntegerConstants) {
            try {
                return canonicalizeLong(row, constants)
            } catch (_: CheckedLongOverflowException) {
                // Coalescing, strictness and orientation must preserve values outside Long as well.
            }
        }
        return canonicalizeWide(row, constants)
    }

    private fun canonicalizeLong(row: LinearRow, constants: IntegerConstants): Canonical? {
        val order = (0 until row.size).sortedBy { Term.intVar(row.ref(it)) }
        val vars = IntArray(row.size)
        val coefficients = LongArray(row.size)
        var count = 0
        for (k in order) {
            val variable = Term.intVar(row.ref(k))
            if (count > 0 && vars[count - 1] == variable) {
                coefficients[count - 1] = addExact(coefficients[count - 1], constants.coeff(k))
            } else {
                vars[count] = variable
                coefficients[count++] = constants.coeff(k)
            }
        }
        var nonzero = 0
        for (k in 0 until count) {
            if (coefficients[k] == 0L) continue
            if (coefficients[k] == Long.MIN_VALUE) {
                throw CheckedLongOverflowException("coefficient magnitude exceeds Long")
            }
            vars[nonzero] = vars[k]
            coefficients[nonzero++] = coefficients[k]
        }
        if (nonzero == 0) return null
        val compact = coefficients.copyOf(nonzero)
        val gcd = PresolveShared.gcdOf(compact)
        var bound = constants.bound
        if (row.strict && row.relation == LinearOp.LE) bound = subExact(bound, 1L)
        if (row.strict && row.relation == LinearOp.GE) bound = addExact(bound, 1L)
        val negate = row.relation == LinearOp.GE
        if (negate) bound = subExact(0L, bound)
        if (row.relation == LinearOp.EQ && bound % gcd != 0L) return null
        val reducedBound = bound.floorDiv(gcd)
        val flip = (compact[0] < 0L) != negate
        for (k in compact.indices) {
            compact[k] /= gcd
            if (flip != negate) compact[k] = -compact[k]
        }
        return Canonical(
            Terms(vars.copyOf(nonzero), compact),
            bigIntOf(if (flip) subExact(0L, reducedBound) else reducedBound),
            upper = !flip,
        )
    }

    private fun canonicalizeWide(row: LinearRow, constants: IntegralConstants): Canonical? {
        val terms = HashMap<Int, BigInt>()
        for (k in 0 until row.size) {
            val variable = Term.intVar(row.ref(k))
            terms[variable] = (terms[variable] ?: BIG_ZERO) + constants.exactCoeff(k)
        }
        val ordered = terms.entries.filter { it.value != BIG_ZERO }.sortedBy { it.key }
        if (ordered.isEmpty()) return null
        val gcd = ordered.fold(BIG_ZERO) { value, term -> value.gcd(term.value.abs()) }
        var bound = constants.exactBound
        if (row.strict && row.relation == LinearOp.LE) bound -= BIG_ONE
        if (row.strict && row.relation == LinearOp.GE) bound += BIG_ONE
        val sign = if (row.relation == LinearOp.GE) -BIG_ONE else BIG_ONE
        bound *= sign
        if (row.relation == LinearOp.EQ && bound % gcd != BIG_ZERO) return null
        val quotient = bound / gcd
        val reducedBound = if (bound < BIG_ZERO && bound % gcd != BIG_ZERO) {
            quotient - BIG_ONE
        } else {
            quotient
        }
        val flip = ordered.first().value * sign < BIG_ZERO
        val orientation = if (flip) -sign else sign
        val coefficients = ordered.map { it.value / gcd * orientation }.toTypedArray()
        val vars = ordered.map { it.key }.toIntArray()
        val key = if (coefficients.all { it.fitsLong() }) {
            Terms(vars, LongArray(coefficients.size) { coefficients[it].toLongExact() })
        } else {
            Terms(vars, null, coefficients)
        }
        return Canonical(
            key,
            if (flip) -reducedBound else reducedBound,
            upper = !flip,
        )
    }
}
