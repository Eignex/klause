package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.RealConstants
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.complemented
import com.eignex.klause.model.PbOp
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.addExact
import com.ionspin.kotlin.bignum.integer.BigInteger

// Replacement kernels require the whole factor, rather than one of its implied rows.
internal fun Factor.equivalentLinear(): Linear? {
    val row = (linearForm as? LinearForm.Conjunction)?.rows?.singleOrNull() ?: return null
    if (row.activator != LinearRow.ALWAYS) return null
    return row.specializeLinear(true, emptyMap())
}

internal fun Factor.equivalentPseudoBoolean(): PseudoBoolean? {
    val row = (linearForm as? LinearForm.Conjunction)?.rows?.singleOrNull() ?: return null
    if (!row.isLongUnconditional || (0 until row.size).any { !Term.isBool(row.ref(it)) }) return null
    val op = when (row.relation) {
        LinearOp.LE -> PbOp.LE
        LinearOp.GE -> PbOp.GE
        LinearOp.EQ -> PbOp.EQ
        LinearOp.NE -> return null
    }
    if (this is PseudoBoolean) return this
    var lower = 0L
    var upper = 0L
    try {
        for (i in 0 until row.size) {
            val coefficient = row.coeff(i)
            if (coefficient < 0L) lower = addExact(lower, coefficient) else upper = addExact(upper, coefficient)
        }
    } catch (_: CheckedLongOverflowException) {
        return null
    }
    return PseudoBoolean(
        LongArray(row.size) { row.coeff(it) },
        IntArray(row.size) { Term.lit(row.ref(it)) },
        op,
        row.bound,
    )
}

internal fun LinearRow.specializeLinear(truth: Boolean, fixed: Map<Int, Boolean>): Linear? {
    val op = if (truth) relation else relation.complemented()
    if (truth && this is Linear) return this
    if (fixed.isEmpty() && (0 until size).any { Term.isBool(ref(it)) }) return null
    val isStrict = if (truth) strict else !strict
    val variables = ArrayList<Int>()
    return when (val c = constants) {
        is IntegralConstants -> {
            var rhs = c.exactBound
            val coefficients = ArrayList<BigInteger>()
            for (k in 0 until size) {
                val reference = ref(k)
                if (Term.isBool(reference)) {
                    val literal = Term.lit(reference)
                    val value = fixed[Lit.variable(literal)] ?: return null
                    if (value == Lit.isPositive(literal)) rhs -= c.exactCoeff(k)
                } else {
                    if (!Term.isInt(reference)) return null
                    variables.add(Term.intVar(reference))
                    coefficients.add(c.exactCoeff(k))
                }
            }
            if (variables.isEmpty()) return null
            if (isStrict && op == LinearOp.LE) rhs -= BigInteger.ONE
            if (isStrict && op == LinearOp.GE) rhs += BigInteger.ONE
            integralLinear(variables.toIntArray(), coefficients.toTypedArray(), op, rhs)
        }

        is RealConstants -> {
            val intCoefficients = ArrayList<Double>()
            val reals = ArrayList<Int>()
            val realCoefficients = ArrayList<Double>()
            var exactRhs = BigFraction.ofDouble(c.bound) ?: return null
            for (k in 0 until size) {
                val reference = ref(k)
                val coefficient = if (k < c.intCoefficients.size) {
                    c.intCoefficients.at(k)
                } else {
                    c.realCoefficients.at(k - c.intCoefficients.size)
                }
                when {
                    Term.isBool(reference) -> {
                        val literal = Term.lit(reference)
                        val value = fixed[Lit.variable(literal)] ?: return null
                        if (value == Lit.isPositive(literal)) {
                            exactRhs -= BigFraction.ofDouble(coefficient) ?: return null
                        }
                    }

                    Term.isInt(reference) -> {
                        variables.add(Term.intVar(reference))
                        intCoefficients.add(coefficient)
                    }

                    else -> {
                        reals.add(Term.realVar(reference))
                        realCoefficients.add(coefficient)
                    }
                }
            }
            if (reals.isEmpty()) return null
            val rhs = exactRhs.toDouble()
            if (BigFraction.ofDouble(rhs) != exactRhs) return null
            Linear(
                variables.toIntArray(),
                intCoefficients.toDoubleArray(),
                reals.toIntArray(),
                realCoefficients.toDoubleArray(),
                op,
                rhs,
                isStrict,
            )
        }
    }
}

internal fun integralLinear(vars: IntArray, coefficients: Array<BigInteger>, op: LinearOp, bound: BigInteger): Linear {
    val terms = LinkedHashMap<Int, BigInteger>()
    for (k in vars.indices) terms[vars[k]] = (terms[vars[k]] ?: BigInteger.ZERO) + coefficients[k]
    val negate = op == LinearOp.GE
    val normalized = terms.values.map { if (negate) -it else it }.toTypedArray()
    val rhs = if (negate) -bound else bound
    val relation = if (negate) LinearOp.LE else op
    val variables = terms.keys.toIntArray()
    val min = BigInteger.fromLong(Long.MIN_VALUE)
    val max = BigInteger.fromLong(Long.MAX_VALUE)
    return if (rhs in min..max && normalized.all { it in min..max }) {
        Linear(LongArray(normalized.size) { normalized[it].longValue() }, variables, relation, rhs.longValue())
    } else {
        Linear(variables, normalized, relation, rhs)
    }
}
