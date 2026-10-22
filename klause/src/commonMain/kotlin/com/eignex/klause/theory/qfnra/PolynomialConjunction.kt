package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Monomial
import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialRemainder
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.theory.qfnra.PolynomialSigns.NEGATIVE_SIGN
import com.eignex.klause.theory.qfnra.PolynomialSigns.NONZERO_SIGNS
import com.eignex.klause.theory.qfnra.PolynomialSigns.POSITIVE_SIGN
import com.eignex.klause.theory.qfnra.PolynomialSigns.ZERO_SIGN
import com.eignex.klause.theory.qfnra.PolynomialSigns.negateSigns
import com.eignex.klause.theory.qfnra.PolynomialSigns.relationSigns

internal class PolynomialConjunction(val atoms: List<PolynomialAtom>, val inconsistent: Boolean, val changed: Boolean)

internal fun preparePolynomialConjunction(
    atoms: List<PolynomialAtom>,
    arithmetic: PolynomialArithmetic,
): Iterator<PolynomialConjunction?> = sequence {
    val groups = LinkedHashMap<Polynomial, ConjunctiveSigns>()
    var changed = false
    for (atom in atoms) {
        arithmetic.step()
        val polynomial = atom.polynomial
        if (polynomial.variables.isEmpty()) {
            if (!atom.holds(polynomial.coefficient(Monomial.ONE).signum())) {
                arithmetic.step()
                yield(PolynomialConjunction(emptyList(), true, true))
                return@sequence
            }
            changed = true
        } else {
            val coefficient = polynomial.terms.first().coefficient
            val key = arithmetic.scale(polynomial, coefficient.reciprocal())
            val positive = coefficient.signum() > 0
            val signs = relationSigns(atom.relation).let { if (positive) it else negateSigns(it) }
            val previous = groups[key]
            if (previous == null) groups[key] = ConjunctiveSigns(polynomial, positive, signs)
            else {
                previous.signs = previous.signs and signs
                changed = true
                if (previous.signs == 0) {
                    arithmetic.step()
                    yield(PolynomialConjunction(emptyList(), true, true))
                    return@sequence
                }
            }
        }
        yield(null)
    }
    val result = groups.values.map {
        arithmetic.step()
        val signs = if (it.positive) it.signs else negateSigns(it.signs)
        PolynomialAtom(it.polynomial, conjunctionRelation(signs))
    }
    val reducing = prepareEqualityRemainders(result, arithmetic)
    var reduced: PolynomialConjunction? = null
    while (reduced == null) {
        reduced = reducing.next()
        if (reduced == null) yield(null)
    }
    arithmetic.step()
    yield(PolynomialConjunction(reduced.atoms, reduced.inconsistent, changed || reduced.changed))
}.iterator()

private fun prepareEqualityRemainders(
    atoms: List<PolynomialAtom>,
    arithmetic: PolynomialArithmetic,
): Iterator<PolynomialConjunction?> = sequence {
    val equations = atoms.mapNotNull {
        arithmetic.step()
        if (it.relation == PolynomialRelation.EQ) it.polynomial else null
    }
    if (equations.isEmpty()) {
        yield(PolynomialConjunction(atoms, false, false))
        return@sequence
    }
    val result = ArrayList<PolynomialAtom>()
    var changed = false
    val remainders = PolynomialRemainder(equations, arithmetic)
    for (atom in atoms) {
        arithmetic.step()
        if (atom.relation == PolynomialRelation.EQ) {
            result.add(atom)
            continue
        }
        var polynomial: Polynomial? = null
        try {
            val reducing = remainders.prepare(atom.polynomial)
            while (polynomial == null) {
                polynomial = reducing.next()
                if (polynomial == null) yield(null)
            }
        } catch (_: PolynomialResourceLimit) {
            polynomial = atom.polynomial
        }
        if (polynomial == atom.polynomial) {
            result.add(atom)
            continue
        }
        changed = true
        if (polynomial.variables.isEmpty()) {
            if (!atom.holds(polynomial.coefficient(Monomial.ONE).signum())) {
                yield(PolynomialConjunction(emptyList(), true, true))
                return@sequence
            }
        } else result.add(PolynomialAtom(polynomial, atom.relation))
        yield(null)
    }
    yield(PolynomialConjunction(result, false, changed))
}.iterator()

private class ConjunctiveSigns(val polynomial: Polynomial, val positive: Boolean, var signs: Int)

private fun conjunctionRelation(signs: Int): PolynomialRelation = when (signs) {
    NEGATIVE_SIGN -> PolynomialRelation.LT
    ZERO_SIGN -> PolynomialRelation.EQ
    NEGATIVE_SIGN or ZERO_SIGN -> PolynomialRelation.LE
    POSITIVE_SIGN -> PolynomialRelation.GT
    NONZERO_SIGNS -> PolynomialRelation.NE
    ZERO_SIGN or POSITIVE_SIGN -> PolynomialRelation.GE
    else -> error("empty polynomial conjunction signs")
}
