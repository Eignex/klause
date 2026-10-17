package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialTerm
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.theory.qfnra.PolynomialSigns.ALL_SIGNS
import com.eignex.klause.theory.qfnra.PolynomialSigns.negateSigns
import com.eignex.klause.theory.qfnra.PolynomialSigns.relationSigns

internal fun preparePolynomialMonomialRefutation(
    atoms: List<PolynomialAtom>,
    arithmetic: PolynomialArithmetic,
): Iterator<Boolean?> = sequence {
    val signs = HashMap<Int, Int>()
    for (atom in atoms) {
        arithmetic.step()
        val term = atom.polynomial.terms.singleOrNull()
        val variable = term?.monomial?.variables?.singleOrNull()
        if (term != null && variable != null && term.monomial.exponent(variable) == 1) {
            val allowed = relationSigns(atom.relation)
            val required = if (term.coefficient.signum() > 0) allowed else negateSigns(allowed)
            val restricted = (signs[variable] ?: ALL_SIGNS) and required
            if (restricted == 0) {
                yield(true)
                return@sequence
            }
            signs[variable] = restricted
        }
        yield(null)
    }
    for (atom in atoms) {
        val checking = prepareSquareRefutation(atom, signs, arithmetic)
        var refuted: Boolean? = null
        while (refuted == null) {
            refuted = checking.next()
            if (refuted == null) yield(null)
        }
        if (refuted) {
            yield(true)
            return@sequence
        }
    }
    for ((index, equation) in atoms.withIndex()) {
        arithmetic.step()
        if (equation.relation != PolynomialRelation.EQ) continue
        val terms = equation.polynomial.terms
        val occurrences = HashMap<Int, Int>()
        for (term in terms) for (variable in term.monomial.variables) {
            arithmetic.step()
            occurrences[variable] = (occurrences[variable] ?: 0) + 1
        }
        for (term in terms) {
            arithmetic.step()
            val variable = term.monomial.variables.firstOrNull {
                arithmetic.step(); occurrences[it] == 1 && term.monomial.exponent(it) == 1
            } ?: continue
            val remainder = arithmetic.subtract(equation.polynomial, Polynomial.of(listOf(term)))
            val replacement = arithmetic.scale(remainder, term.coefficient.reciprocal().negated())
            for ((otherIndex, atom) in atoms.withIndex()) {
                arithmetic.step()
                if (otherIndex == index) continue
                val rewriting = prepareMonomialConsequence(atom.polynomial, term, variable, replacement, arithmetic)
                var consequence: Polynomial? = null
                while (consequence == null) {
                    consequence = rewriting.next()
                    if (consequence == null) yield(null)
                }
                val checking = prepareSquareRefutation(PolynomialAtom(consequence, atom.relation), signs, arithmetic)
                var refuted: Boolean? = null
                while (refuted == null) {
                    refuted = checking.next()
                    if (refuted == null) yield(null)
                }
                if (refuted) {
                    yield(true)
                    return@sequence
                }
            }
        }
    }
    arithmetic.step()
    yield(false)
}.iterator()

private fun prepareSquareRefutation(
    atom: PolynomialAtom,
    signs: Map<Int, Int>,
    arithmetic: PolynomialArithmetic,
): Iterator<Boolean?> = sequence {
    for (orientation in listOf(BigFraction.ONE, BigFraction.MINUS_ONE)) {
        val checking = preparePolynomialSquareSigns(atom.polynomial, signs, arithmetic, orientation)
        var possible: Int? = null
        while (possible == null) {
            possible = checking.next()
            if (possible == null) yield(null)
        }
        val allowed = relationSigns(atom.relation)
        if (possible and (if (orientation.signum() > 0) allowed else negateSigns(allowed)) == 0) {
            arithmetic.step()
            yield(true)
            return@sequence
        }
    }
    arithmetic.step()
    yield(false)
}.iterator()

private fun prepareMonomialConsequence(
    polynomial: Polynomial,
    pivot: PolynomialTerm,
    variable: Int,
    replacement: Polynomial,
    arithmetic: PolynomialArithmetic,
): Iterator<Polynomial?> = sequence {
    arithmetic.step()
    require(variable !in replacement.variables)
    var current = polynomial
    while (true) {
        var rewritten = false
        var result = Polynomial.ZERO
        for (term in current.terms) {
            arithmetic.step()
            val quotient = term.monomial.quotient(pivot.monomial)
            if (quotient == null) {
                result = arithmetic.add(result, Polynomial.of(listOf(term)))
            } else {
                rewritten = true
                val multiplying = arithmetic.prepareMultiply(
                    Polynomial.of(listOf(PolynomialTerm(quotient, term.coefficient))), replacement,
                )
                var product: Polynomial? = null
                while (product == null) {
                    product = multiplying.next()
                    if (product == null) yield(null)
                }
                result = arithmetic.add(result, product)
            }
            yield(null)
        }
        if (!rewritten) {
            arithmetic.step()
            yield(current)
            return@sequence
        }
        current = result
        yield(null)
    }
}.iterator()
