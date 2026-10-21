package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Monomial
import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.simplex.exact.BigFraction

internal fun preparePolynomialAffineConsequences(
    atoms: List<PolynomialAtom>,
    arithmetic: PolynomialArithmetic,
): Iterator<List<PolynomialAtom>?> = sequence {
    val retained = HashSet<Int>()
    val inputs = LinkedHashMap<Int, MutableList<ConsequenceInput>>()
    val seen = HashSet<PolynomialAtom>()
    for (atom in atoms) {
        arithmetic.step()
        seen.add(atom)
        val coefficients = LinkedHashMap<Int, BigFraction>()
        val blocked = HashSet<Int>()
        val support = HashSet<Int>()
        val inequality = atom.relation != PolynomialRelation.EQ && atom.relation != PolynomialRelation.NE
        for (term in atom.polynomial.terms) {
            arithmetic.step()
            val variables = term.monomial.variables
            val affine = inequality && variables.size == 1 && term.monomial.exponent(variables.single()) == 1
            for (variable in variables) {
                arithmetic.step()
                support.add(variable)
                if (affine) coefficients[variable] = term.coefficient else blocked.add(variable)
            }
        }
        for (variable in blocked) {
            arithmetic.step()
            retained.add(variable)
        }
        for ((variable, coefficient) in coefficients) {
            arithmetic.step()
            if (variable !in blocked) {
                inputs.getOrPut(variable) { ArrayList() }
                    .add(ConsequenceInput(atom, coefficient, support.size))
            }
        }
        yield(null)
    }
    val consequences = ArrayList<AffineConsequence>()
    for ((variable, rows) in inputs) {
        arithmetic.step()
        if (variable !in retained) continue
        val lower = ArrayList<ConsequenceBound>()
        val upper = ArrayList<ConsequenceBound>()
        val coordinate = Polynomial.variable(variable)
        for (row in rows) {
            arithmetic.step()
            val remainder = arithmetic.subtract(row.atom.polynomial, arithmetic.scale(coordinate, row.coefficient))
            val bound = ConsequenceBound(arithmetic.scale(remainder, row.coefficient.reciprocal().negated()),
                row.atom.relation == PolynomialRelation.GT || row.atom.relation == PolynomialRelation.LT,
                row.support)
            val increasing = row.atom.relation == PolynomialRelation.GE || row.atom.relation == PolynomialRelation.GT
            if ((row.coefficient.signum() > 0) == increasing) lower.add(bound) else upper.add(bound)
            yield(null)
        }
        for (a in lower) for (b in upper) {
            arithmetic.step()
            val polynomial = arithmetic.subtract(b.polynomial, a.polynomial)
            val support = polynomial.variables.size
            if (support < maxOf(a.support, b.support)) {
                val atom = PolynomialAtom(polynomial,
                    if (a.strict || b.strict) PolynomialRelation.GT else PolynomialRelation.GE)
                val tautology = support == 0 && atom.holds(polynomial.coefficient(Monomial.ONE).signum())
                if (!tautology && seen.add(atom)) consequences.add(AffineConsequence(atom, support))
            }
            yield(null)
        }
    }
    val ordered = consequences.sortedWith { a, b -> arithmetic.step(); a.support.compareTo(b.support) }
    arithmetic.step()
    yield(ordered.map { arithmetic.step(); it.atom })
}.iterator()

private class ConsequenceInput(val atom: PolynomialAtom, val coefficient: BigFraction, val support: Int)
private class ConsequenceBound(val polynomial: Polynomial, val strict: Boolean, val support: Int)
private class AffineConsequence(val atom: PolynomialAtom, val support: Int)
