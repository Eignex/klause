package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Monomial
import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialTerm
import com.eignex.klause.ir.PolynomialRelation

internal class PolynomialNonzeroReduction(val atoms: List<PolynomialAtom>, val changed: Boolean)

internal fun preparePolynomialNonzeroReduction(
    atoms: List<PolynomialAtom>,
    arithmetic: PolynomialArithmetic,
): Iterator<PolynomialNonzeroReduction?> = sequence {
    val nonzero = HashSet<Int>()
    for (atom in atoms) {
        arithmetic.step()
        if (!atom.holds(0)) {
            val term = atom.polynomial.terms.singleOrNull()
            if (term != null) for (variable in term.monomial.variables) {
                arithmetic.step()
                nonzero.add(variable)
            }
        }
        yield(null)
    }
    if (nonzero.isEmpty()) {
        arithmetic.step()
        yield(PolynomialNonzeroReduction(atoms, false))
        return@sequence
    }
    val result = ArrayList<PolynomialAtom>()
    var changed = false
    for (atom in atoms) {
        arithmetic.step()
        val terms = if (atom.relation == PolynomialRelation.EQ) atom.polynomial.terms else emptyList()
        val factor = knownNonzeroFactor(terms, nonzero, arithmetic)
        if (factor == null) {
            result.add(atom)
        } else {
            val divided = terms.map { term ->
                arithmetic.step()
                PolynomialTerm(checkNotNull(term.monomial.quotient(factor)), term.coefficient)
            }
            result.add(PolynomialAtom(Polynomial.of(divided), atom.relation))
            changed = true
        }
        yield(null)
    }
    arithmetic.step()
    yield(PolynomialNonzeroReduction(if (changed) result.toList() else atoms, changed))
}.iterator()

private fun knownNonzeroFactor(
    terms: List<PolynomialTerm>,
    nonzero: Set<Int>,
    arithmetic: PolynomialArithmetic,
): Monomial? {
    arithmetic.step()
    val first = terms.firstOrNull()?.monomial ?: return null
    val powers = HashMap<Int, Int>()
    for (variable in first.variables) {
        arithmetic.step()
        if (variable in nonzero) powers[variable] = first.exponent(variable)
    }
    for (index in 1 until terms.size) {
        arithmetic.step()
        if (powers.isEmpty()) return null
        for (variable in powers.keys.toList()) {
            arithmetic.step()
            val degree = minOf(powers.getValue(variable), terms[index].monomial.exponent(variable))
            if (degree == 0) powers.remove(variable) else powers[variable] = degree
        }
    }
    return if (powers.isEmpty()) null else Monomial.of(powers)
}
