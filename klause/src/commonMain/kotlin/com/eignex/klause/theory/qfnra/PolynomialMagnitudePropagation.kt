package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Monomial
import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialTerm
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.simplex.exact.BigFraction

internal fun preparePolynomialMagnitudeRefutation(
    atoms: List<PolynomialAtom>,
    arithmetic: PolynomialArithmetic,
): Iterator<Boolean?> = sequence {
    val bounds = HashMap<Int, BigFraction>()
    val payloads = ArrayList<List<PolynomialTerm>>()
    for (atom in atoms) {
        arithmetic.step()
        val terms = atom.polynomial.terms
        payloads.add(terms)
        collectMagnitudes(atom.relation, terms, bounds, arithmetic)
        yield(null)
    }
    for ((index, terms) in payloads.withIndex()) {
        arithmetic.step()
        val enclosure = magnitudeEnclosure(terms, bounds, arithmetic)
        if (enclosure != null && excludesRelation(enclosure, atoms[index].relation, arithmetic)) {
            yield(true)
            return@sequence
        }
        yield(null)
    }
    arithmetic.step()
    yield(false)
}.iterator()

private fun collectMagnitudes(
    relation: PolynomialRelation,
    terms: List<PolynomialTerm>,
    bounds: MutableMap<Int, BigFraction>,
    arithmetic: PolynomialArithmetic,
) {
    arithmetic.step()
    val orientation = when (relation) {
        PolynomialRelation.LT, PolynomialRelation.LE -> 1
        PolynomialRelation.GT, PolynomialRelation.GE -> -1
        PolynomialRelation.EQ -> terms.firstOrNull {
            arithmetic.step(); it.monomial != Monomial.ONE
        }?.coefficient?.signum() ?: return
        PolynomialRelation.NE -> return
    }
    var constant = BigFraction.ZERO
    for (term in terms) {
        arithmetic.step()
        if (term.monomial == Monomial.ONE) {
            constant = if (orientation > 0) term.coefficient else term.coefficient.negated()
            continue
        }
        if (term.coefficient.signum() != orientation) return
        for (variable in term.monomial.variables) {
            arithmetic.step()
            if (term.monomial.exponent(variable) and 1 != 0) return
        }
    }
    if (constant.signum() > 0) return
    val ceiling = constant.negated()
    for (term in terms) {
        arithmetic.step()
        val variable = term.monomial.variables.singleOrNull() ?: continue
        val coefficient = if (orientation > 0) term.coefficient else term.coefficient.negated()
        val ratio = ceiling * coefficient.reciprocal()
        // For t > 1 and positive even d, t^d >= t; a zero ceiling forces each summand to zero.
        val radius = if (ratio.isZero || ratio > BigFraction.ONE) ratio else BigFraction.ONE
        arithmetic.step()
        val previous = bounds[variable]
        if (previous == null || radius < previous) bounds[variable] = radius
    }
}

private fun magnitudeEnclosure(
    terms: List<PolynomialTerm>,
    bounds: Map<Int, BigFraction>,
    arithmetic: PolynomialArithmetic,
): Pair<BigFraction, BigFraction>? {
    var lower = BigFraction.ZERO
    var upper = BigFraction.ZERO
    for (term in terms) {
        arithmetic.step()
        var radius = if (term.coefficient.signum() > 0) term.coefficient else term.coefficient.negated()
        var odd = false
        for (variable in term.monomial.variables) {
            arithmetic.step()
            if (radius.isZero) break
            val bound = bounds[variable] ?: return null
            val degree = term.monomial.exponent(variable)
            odd = odd || (degree and 1 != 0)
            val factor = arithmetic.power(Polynomial.constant(bound), degree).coefficient(Monomial.ONE)
            arithmetic.step()
            radius *= factor
        }
        val termLower = when {
            term.monomial == Monomial.ONE -> term.coefficient
            odd || term.coefficient.signum() < 0 -> radius.negated()
            else -> BigFraction.ZERO
        }
        val termUpper = when {
            term.monomial == Monomial.ONE -> term.coefficient
            odd || term.coefficient.signum() > 0 -> radius
            else -> BigFraction.ZERO
        }
        arithmetic.step()
        lower += termLower
        arithmetic.step()
        upper += termUpper
    }
    return lower to upper
}

private fun excludesRelation(
    enclosure: Pair<BigFraction, BigFraction>,
    relation: PolynomialRelation,
    arithmetic: PolynomialArithmetic,
): Boolean {
    arithmetic.step()
    val lower = enclosure.first.signum()
    val upper = enclosure.second.signum()
    return when (relation) {
        PolynomialRelation.EQ -> lower > 0 || upper < 0
        PolynomialRelation.NE -> lower == 0 && upper == 0
        PolynomialRelation.LT -> lower >= 0
        PolynomialRelation.LE -> lower > 0
        PolynomialRelation.GT -> upper <= 0
        PolynomialRelation.GE -> upper < 0
    }
}
