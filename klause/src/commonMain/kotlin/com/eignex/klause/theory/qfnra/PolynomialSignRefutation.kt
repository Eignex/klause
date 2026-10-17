package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialTerm
import com.eignex.klause.theory.qfnra.PolynomialSigns.ALL_SIGNS
import com.eignex.klause.theory.qfnra.PolynomialSigns.NEGATIVE_SIGN
import com.eignex.klause.theory.qfnra.PolynomialSigns.NONZERO_SIGNS
import com.eignex.klause.theory.qfnra.PolynomialSigns.POSITIVE_SIGN
import com.eignex.klause.theory.qfnra.PolynomialSigns.ZERO_SIGN
import com.eignex.klause.theory.qfnra.PolynomialSigns.addSigns
import com.eignex.klause.theory.qfnra.PolynomialSigns.multiplySigns
import com.eignex.klause.theory.qfnra.PolynomialSigns.negateSigns
import com.eignex.klause.theory.qfnra.PolynomialSigns.relationSigns

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

