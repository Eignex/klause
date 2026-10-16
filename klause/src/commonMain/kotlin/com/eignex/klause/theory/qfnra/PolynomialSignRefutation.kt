package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialTerm
import com.eignex.klause.ir.PolynomialRelation

internal fun refutePolynomialSigns(
    formula: PolynomialFormula,
    arithmetic: PolynomialArithmetic,
    maximumAtoms: Int,
    maximumTerms: Int,
): Boolean {
    val atoms = formula.conjunctiveAtoms(arithmetic, maximumAtoms) ?: return false
    val payloads = ArrayList<List<PolynomialTerm>>()
    var count = 0
    for (atom in atoms) {
        arithmetic.step()
        val terms = atom.polynomial.terms
        if (terms.size > maximumTerms - count) return false
        count += terms.size
        repeat(terms.size + 1) { arithmetic.step() }
        payloads.add(terms)
    }
    val signs = HashMap<Int, Int>()
    for (index in atoms.indices) {
        arithmetic.step()
        val term = payloads[index].singleOrNull() ?: continue
        val variables = term.monomial.variables
        arithmetic.step()
        val variable = variables.singleOrNull() ?: continue
        if (term.monomial.exponent(variable) != 1) continue
        val allowed = relationSigns(atoms[index].relation)
        val required = if (term.coefficient.signum() > 0) allowed else negateSigns(allowed)
        val restricted = (signs[variable] ?: ALL_SIGNS) and required
        if (restricted == 0) return true
        signs[variable] = restricted
    }
    for (index in atoms.indices) {
        arithmetic.step()
        var sum = ZERO_SIGN
        for (term in payloads[index]) {
            arithmetic.step()
            var product = if (term.coefficient.signum() > 0) POSITIVE_SIGN else NEGATIVE_SIGN
            for (variable in term.monomial.variables) {
                arithmetic.step()
                var factor = signs[variable] ?: ALL_SIGNS
                if (term.monomial.exponent(variable) and 1 == 0) {
                    factor = (factor and ZERO_SIGN) or
                        if (factor and NONZERO_SIGNS == 0) 0 else POSITIVE_SIGN
                }
                product = multiplySigns(product, factor)
            }
            sum = addSigns(sum, product)
        }
        if (sum and relationSigns(atoms[index].relation) == 0) return true
    }
    return false
}

private fun relationSigns(relation: PolynomialRelation): Int = when (relation) {
    PolynomialRelation.EQ -> ZERO_SIGN
    PolynomialRelation.NE -> NONZERO_SIGNS
    PolynomialRelation.LT -> NEGATIVE_SIGN
    PolynomialRelation.LE -> NEGATIVE_SIGN or ZERO_SIGN
    PolynomialRelation.GT -> POSITIVE_SIGN
    PolynomialRelation.GE -> POSITIVE_SIGN or ZERO_SIGN
}

private fun negateSigns(signs: Int): Int = (signs and ZERO_SIGN) or
    (if (signs and NEGATIVE_SIGN == 0) 0 else POSITIVE_SIGN) or
    (if (signs and POSITIVE_SIGN == 0) 0 else NEGATIVE_SIGN)

private fun multiplySigns(left: Int, right: Int): Int =
    (if ((left or right) and ZERO_SIGN == 0) 0 else ZERO_SIGN) or
        (if (left and NEGATIVE_SIGN != 0 && right and POSITIVE_SIGN != 0 ||
            left and POSITIVE_SIGN != 0 && right and NEGATIVE_SIGN != 0
        ) NEGATIVE_SIGN else 0) or
        (if (left and NEGATIVE_SIGN != 0 && right and NEGATIVE_SIGN != 0 ||
            left and POSITIVE_SIGN != 0 && right and POSITIVE_SIGN != 0
        ) POSITIVE_SIGN else 0)

private fun addSigns(left: Int, right: Int): Int {
    if (left == ZERO_SIGN) return right
    if (right == ZERO_SIGN) return left
    if (left and NEGATIVE_SIGN != 0 && right and POSITIVE_SIGN != 0 ||
        left and POSITIVE_SIGN != 0 && right and NEGATIVE_SIGN != 0
    ) return ALL_SIGNS
    return left or right
}

private const val NEGATIVE_SIGN = 1
private const val ZERO_SIGN = 2
private const val POSITIVE_SIGN = 4
private const val NONZERO_SIGNS = 5
private const val ALL_SIGNS = 7
