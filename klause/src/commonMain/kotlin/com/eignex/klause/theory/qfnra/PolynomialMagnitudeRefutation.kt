package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Monomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialTerm
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.magnitudeBitLength

internal fun refutePolynomialMagnitudes(
    formula: PolynomialFormula,
    arithmetic: PolynomialArithmetic,
    maximumAtoms: Int,
    maximumTerms: Int,
    maximumDegree: Int,
    maximumBits: Int,
): Boolean {
    val atoms = formula.conjunctiveAtoms(arithmetic, maximumAtoms) ?: return false
    return PolynomialMagnitudeRefutation(arithmetic, maximumTerms, maximumDegree, maximumBits).refutes(atoms)
}

private class PolynomialMagnitudeRefutation(
    private val arithmetic: PolynomialArithmetic,
    private val maximumTerms: Int,
    private val maximumDegree: Int,
    private val maximumBits: Int,
) {
    fun refutes(atoms: List<PolynomialAtom>): Boolean {
        val payloads = payloads(atoms) ?: return false
        val bounds = magnitudes(atoms, payloads) ?: return false
        if (bounds.isEmpty()) return false
        for (index in atoms.indices) {
            arithmetic.step()
            val enclosure = enclose(payloads[index], bounds) ?: continue
            arithmetic.step()
            val lower = enclosure.first.signum()
            val upper = enclosure.second.signum()
            val refuted = when (atoms[index].relation) {
                PolynomialRelation.EQ -> lower > 0 || upper < 0
                PolynomialRelation.NE -> lower == 0 && upper == 0
                PolynomialRelation.LT -> lower >= 0
                PolynomialRelation.LE -> lower > 0
                PolynomialRelation.GT -> upper <= 0
                PolynomialRelation.GE -> upper < 0
            }
            if (refuted) return true
        }
        return false
    }

    private fun payloads(atoms: List<PolynomialAtom>): List<List<PolynomialTerm>>? {
        val payloads = ArrayList<List<PolynomialTerm>>()
        var count = 0
        for (atom in atoms) {
            arithmetic.step()
            val terms = atom.polynomial.terms
            if (terms.size > maximumTerms - count) return null
            count += terms.size
            for (term in terms) {
                if (!admitted(term.coefficient)) return null
                for (variable in term.monomial.variables) {
                    arithmetic.step()
                    if (term.monomial.exponent(variable) > maximumDegree) return null
                }
            }
            payloads.add(terms)
        }
        return payloads
    }

    private fun magnitudes(
        atoms: List<PolynomialAtom>,
        payloads: List<List<PolynomialTerm>>,
    ): Map<Int, BigFraction>? {
        val bounds = HashMap<Int, BigFraction>()
        for (index in atoms.indices) {
            arithmetic.step()
            val terms = payloads[index]
            val orientation = when (atoms[index].relation) {
                PolynomialRelation.LT, PolynomialRelation.LE -> 1
                PolynomialRelation.GT, PolynomialRelation.GE -> -1
                PolynomialRelation.EQ -> terms.firstOrNull {
                    arithmetic.step(); it.monomial != Monomial.ONE
                }?.coefficient?.signum() ?: continue
                PolynomialRelation.NE -> continue
            }
            var constant = BigFraction.ZERO
            var nonnegative = true
            for (term in terms) {
                arithmetic.step()
                if (term.monomial == Monomial.ONE) {
                    constant = if (orientation > 0) term.coefficient else term.coefficient.negated()
                    continue
                }
                if (term.coefficient.signum() != orientation) nonnegative = false
                for (variable in term.monomial.variables) {
                    arithmetic.step()
                    if (term.monomial.exponent(variable) and 1 != 0) nonnegative = false
                }
            }
            if (!nonnegative || constant.signum() > 0) continue
            val ceiling = constant.negated()
            for (term in terms) {
                arithmetic.step()
                val variable = term.monomial.variables.singleOrNull() ?: continue
                val coefficient = if (orientation > 0) term.coefficient else term.coefficient.negated()
                arithmetic.step()
                val ratio = ceiling * coefficient.reciprocal()
                if (!admitted(ratio)) return null
                // For t > 1 and positive even d, t^d >= t; zero forces every nonnegative summand to zero.
                val radius = if (ratio.isZero || ratio > BigFraction.ONE) ratio else BigFraction.ONE
                arithmetic.step()
                val previous = bounds[variable]
                if (previous == null || radius < previous) bounds[variable] = radius
            }
        }
        return bounds
    }

    private fun enclose(
        terms: List<PolynomialTerm>,
        bounds: Map<Int, BigFraction>,
    ): Pair<BigFraction, BigFraction>? {
        var lower = BigFraction.ZERO
        var upper = BigFraction.ZERO
        for (term in terms) {
            arithmetic.step()
            var radius = if (term.coefficient.signum() > 0) term.coefficient else term.coefficient.negated()
            var odd = false
            for (variable in term.monomial.variables) {
                arithmetic.step()
                val bound = bounds[variable] ?: return null
                val degree = term.monomial.exponent(variable)
                odd = odd || (degree and 1 != 0)
                val factor = power(bound, degree) ?: return null
                arithmetic.step()
                radius *= factor
                if (!admitted(radius)) return null
            }
            val termLower: BigFraction
            val termUpper: BigFraction
            if (term.monomial == Monomial.ONE) {
                termLower = term.coefficient
                termUpper = term.coefficient
            } else {
                termLower = if (odd || term.coefficient.signum() < 0) radius.negated() else BigFraction.ZERO
                termUpper = if (odd || term.coefficient.signum() > 0) radius else BigFraction.ZERO
            }
            arithmetic.step()
            lower += termLower
            if (!admitted(lower)) return null
            arithmetic.step()
            upper += termUpper
            if (!admitted(upper)) return null
        }
        return lower to upper
    }

    private fun power(value: BigFraction, exponent: Int): BigFraction? {
        var result = BigFraction.ONE
        repeat(exponent) {
            arithmetic.step()
            result *= value
            if (!admitted(result)) return null
        }
        return result
    }

    private fun admitted(value: BigFraction): Boolean {
        arithmetic.step()
        return value.num.magnitudeBitLength() <= maximumBits && value.den.magnitudeBitLength() <= maximumBits
    }
}
