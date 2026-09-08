package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.RealConstants
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.complemented
import com.eignex.klause.ir.impliedLinearRows
import com.eignex.klause.simplex.exact.BigFraction
import com.ionspin.kotlin.bignum.integer.BigInteger

/** Rows whose activators are fixed by root unit clauses. */
internal fun rootFixedReifiedRows(factors: List<Factor>): List<Linear> =
    presolveLinearRows(factors, activatedOnly = true)

/**
 * Specialize implied rows for presolve's linear arithmetic kernels, independently of finite domains.
 * Boolean terms and activators are substituted only when root facts determine them. Disjunctive rows
 * are not individually implied; relaxation rows may prove bounds but do not authorize factor removal.
 */
internal fun presolveLinearRows(factors: List<Factor>, activatedOnly: Boolean = false): List<Linear> {
    val fixed = HashMap<Int, Boolean>()
    val conflicting = HashSet<Int>()
    for (factor in factors) {
        if (factor !is Clause || factor.literals.size != 1) continue
        val literal = factor.literals[0]
        val variable = Lit.variable(literal)
        val truth = Lit.isPositive(literal)
        if (fixed.put(variable, truth)?.let { it != truth } == true) conflicting.add(variable)
    }
    for (variable in conflicting) fixed.remove(variable)
    return buildList {
        for (factor in factors) {
            for (row in factor.impliedLinearRows) {
                if (activatedOnly && row.activator == LinearRow.ALWAYS) continue
                val truth = if (row.activator == LinearRow.ALWAYS) true else fixed[row.activator] ?: continue
                row.specializeLinear(truth, fixed)?.let(::add)
            }
        }
    }
}

private fun LinearRow.specializeLinear(truth: Boolean, fixed: Map<Int, Boolean>): Linear? {
    val op = if (truth) relation else relation.complemented()
    if (op == LinearOp.NE) return null
    if (truth && this is Linear) return this
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
