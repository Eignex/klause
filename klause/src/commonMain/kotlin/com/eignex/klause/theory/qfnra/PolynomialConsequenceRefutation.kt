package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Monomial
import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialTerm
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.magnitudeBitLength

internal fun refutePolynomialConsequences(
    formula: PolynomialFormula,
    arithmetic: PolynomialArithmetic,
    maximumAtoms: Int,
    maximumTerms: Int,
    maximumBits: Int,
): Boolean {
    val atoms = formula.conjunctiveAtoms(arithmetic, maximumAtoms) ?: return false
    return PolynomialConsequenceRefutation(arithmetic, maximumTerms, maximumBits).refutes(atoms)
}

private class PolynomialConsequenceRefutation(
    private val arithmetic: PolynomialArithmetic,
    private val maximumTerms: Int,
    private val maximumBits: Int,
) {
    private val signs = HashMap<Int, Int>()
    private val inputDegrees = HashMap<Int, Int>()

    fun refutes(atoms: List<PolynomialAtom>): Boolean {
        if (!admitted(atoms, captureDegrees = true)) return false
        for (atom in atoms) {
            arithmetic.step()
            val term = atom.polynomial.terms.singleOrNull() ?: continue
            val variable = term.monomial.variables.singleOrNull() ?: continue
            if (term.monomial.exponent(variable) != 1) continue
            val allowed = relationSigns(atom.relation)
            val required = if (term.coefficient.signum() > 0) allowed else negate(allowed)
            val restricted = (signs[variable] ?: ALL) and required
            if (restricted == 0) return true
            signs[variable] = restricted
        }
        var rows = atoms
        val usedVariables = HashSet<Int>()
        while (true) {
            val stripped = ArrayList<PolynomialAtom>()
            for (row in rows) {
                arithmetic.step()
                stripped.add(if (row.relation == PolynomialRelation.EQ) {
                    PolynomialAtom(stripNonzero(row.polynomial), row.relation)
                } else row)
            }
            if (!admitted(stripped)) return false
            for (row in stripped) {
                arithmetic.step()
                if (refutes(row)) return true
            }
            val pivot = pivot(stripped, usedVariables) ?: return false
            val replacement = replacement(stripped[pivot.row].polynomial, pivot.term) ?: return false
            val updated = ArrayList<PolynomialAtom>()
            var count = 0
            for ((index, row) in stripped.withIndex()) {
                arithmetic.step()
                // Only consequences are refuted; a dropped defining row never accepts a model.
                if (index == pivot.row) continue
                val polynomial = rewrite(row.polynomial, pivot, replacement, maximumTerms - count) ?: return false
                val terms = polynomial.terms
                if (terms.size > maximumTerms - count) return false
                count += terms.size
                updated.add(PolynomialAtom(polynomial, row.relation))
            }
            if (!admitted(updated)) return false
            arithmetic.step()
            usedVariables.add(pivot.variable)
            rows = updated
        }
    }

    private fun admitted(atoms: List<PolynomialAtom>, captureDegrees: Boolean = false): Boolean {
        var count = 0
        for (atom in atoms) {
            arithmetic.step()
            val terms = atom.polynomial.terms
            if (terms.size > maximumTerms - count) return false
            count += terms.size
            for (term in terms) {
                if (!admitted(term.coefficient)) return false
                for (variable in term.monomial.variables) {
                    arithmetic.step()
                    val degree = term.monomial.exponent(variable)
                    if (captureDegrees) inputDegrees[variable] = maxOf(inputDegrees[variable] ?: 0, degree)
                    else if (degree > (inputDegrees[variable] ?: 0)) return false
                }
            }
        }
        return true
    }

    private fun admitted(value: BigFraction): Boolean {
        arithmetic.step()
        return value.num.magnitudeBitLength() <= maximumBits && value.den.magnitudeBitLength() <= maximumBits
    }

    private fun stripNonzero(polynomial: Polynomial): Polynomial {
        val terms = polynomial.terms
        val first = terms.firstOrNull() ?: return polynomial
        val powers = LinkedHashMap<Int, Int>()
        for (variable in first.monomial.variables) {
            arithmetic.step()
            if ((signs[variable] ?: ALL) and ZERO != 0) continue
            var degree = first.monomial.exponent(variable)
            for (term in terms) {
                arithmetic.step()
                degree = minOf(degree, term.monomial.exponent(variable))
            }
            if (degree > 0) powers[variable] = degree
        }
        if (powers.isEmpty()) return polynomial
        val divisor = Monomial.of(powers)
        return Polynomial.of(terms.map { term ->
            repeat(term.monomial.variables.size + powers.size + 1) { arithmetic.step() }
            PolynomialTerm(checkNotNull(term.monomial.quotient(divisor)), term.coefficient)
        })
    }

    private data class Pivot(val row: Int, val variable: Int, val term: PolynomialTerm, val rank: List<Int>)

    private fun pivot(atoms: List<PolynomialAtom>, used: Set<Int>): Pivot? {
        var best: Pivot? = null
        for ((index, atom) in atoms.withIndex()) {
            arithmetic.step()
            if (atom.relation != PolynomialRelation.EQ) continue
            val terms = atom.polynomial.terms
            val occurrences = HashMap<Int, Int>()
            for (term in terms) for (variable in term.monomial.variables) {
                arithmetic.step()
                occurrences[variable] = (occurrences[variable] ?: 0) + 1
            }
            for (term in terms) for (variable in term.monomial.variables) {
                arithmetic.step()
                if (variable in used || occurrences[variable] != 1 || term.monomial.exponent(variable) != 1) continue
                val rank = listOf(term.monomial.variables.size, inputDegrees.getValue(variable),
                    terms.size, index, variable)
                val previous = best
                if (previous == null || rank.indices.firstOrNull { arithmetic.step(); rank[it] != previous.rank[it] }
                        ?.let { rank[it] < previous.rank[it] } == true
                ) best = Pivot(index, variable, term, rank)
            }
        }
        return best
    }

    private fun replacement(polynomial: Polynomial, pivot: PolynomialTerm): Polynomial? {
        arithmetic.step()
        val scale = pivot.coefficient.reciprocal().negated()
        if (!admitted(scale)) return null
        val result = ArrayList<PolynomialTerm>()
        for (term in polynomial.terms) {
            arithmetic.step()
            if (term.monomial == pivot.monomial) continue
            val coefficient = term.coefficient * scale
            if (!admitted(coefficient)) return null
            result.add(PolynomialTerm(term.monomial, coefficient))
        }
        return Polynomial.of(result)
    }

    private fun rewrite(polynomial: Polynomial, pivot: Pivot, replacement: Polynomial, limit: Int): Polynomial? {
        var current = polynomial
        val replacements = replacement.terms
        while (true) {
            val terms = current.terms
            val updated = LinkedHashMap<Monomial, BigFraction>()
            var changed = false
            for (term in terms) {
                repeat(term.monomial.variables.size + pivot.term.monomial.variables.size + 1) { arithmetic.step() }
                val quotient = term.monomial.quotient(pivot.term.monomial)
                if (quotient == null) {
                    if (!merge(updated, term.monomial, term.coefficient, limit)) return null
                    continue
                }
                changed = true
                for (other in replacements) {
                    arithmetic.step()
                    for (variable in other.monomial.variables) {
                        arithmetic.step()
                        val degree = quotient.exponent(variable).toLong() + other.monomial.exponent(variable)
                        if (degree > inputDegrees.getValue(variable)) return null
                    }
                    val monomial = quotient.times(other.monomial)
                    val coefficient = term.coefficient * other.coefficient
                    if (!admitted(coefficient) || !merge(updated, monomial, coefficient, limit)) return null
                }
            }
            if (!changed) return current
            repeat(updated.size + 1) { arithmetic.step() }
            current = Polynomial.of(updated.map { PolynomialTerm(it.key, it.value) })
        }
    }

    private fun merge(
        coefficients: MutableMap<Monomial, BigFraction>,
        monomial: Monomial,
        value: BigFraction,
        limit: Int,
    ): Boolean {
        arithmetic.step()
        val sum = (coefficients[monomial] ?: BigFraction.ZERO) + value
        if (!admitted(sum)) return false
        if (sum.isZero) coefficients.remove(monomial)
        else {
            if (monomial !in coefficients && coefficients.size >= limit) return false
            coefficients[monomial] = sum
        }
        return true
    }

    private fun refutes(atom: PolynomialAtom): Boolean {
        for (orientation in listOf(BigFraction.ONE, BigFraction.MINUS_ONE)) {
            val coefficients = LinkedHashMap<Monomial, BigFraction>()
            for (term in atom.polynomial.terms) {
                arithmetic.step()
                coefficients[term.monomial] = term.coefficient * orientation
            }
            var squares = false
            for (negative in coefficients.keys.toList()) {
                arithmetic.step()
                if (coefficients.getValue(negative).signum() >= 0) continue
                for (positive in coefficients.keys.toList()) {
                    arithmetic.step()
                    val left = coefficients.getValue(positive)
                    if (left.signum() <= 0) continue
                    val reflected = reflected(negative, positive) ?: continue
                    if (reflected == positive) continue
                    val right = coefficients[reflected] ?: continue
                    if (right.signum() <= 0 || monomialSigns(common(positive, reflected)) and NEGATIVE != 0) continue
                    arithmetic.step()
                    // Equal portions leave a source-nonnegative common monomial times a binomial square.
                    val needed = coefficients.getValue(negative).negated() * BigFraction.ofLong(2).reciprocal()
                    var amount = if (left < right) left else right
                    if (needed < amount) amount = needed
                    val nextLeft = left - amount
                    val nextRight = right - amount
                    val nextNegative = coefficients.getValue(negative) + amount + amount
                    if (!admitted(nextLeft) || !admitted(nextRight) || !admitted(nextNegative)) return false
                    coefficients[positive] = nextLeft
                    coefficients[reflected] = nextRight
                    coefficients[negative] = nextNegative
                    squares = true
                    if (nextNegative.isZero) break
                }
            }
            var sum = if (squares) ZERO or POSITIVE else ZERO
            for ((monomial, coefficient) in coefficients) {
                arithmetic.step()
                if (coefficient.isZero) continue
                val term = monomialSigns(monomial)
                sum = addSigns(sum, if (coefficient.signum() > 0) term else negate(term))
            }
            val allowed = relationSigns(atom.relation)
            if (sum and (if (orientation.signum() > 0) allowed else negate(allowed)) == 0) return true
        }
        return false
    }

    private fun reflected(center: Monomial, side: Monomial): Monomial? {
        for (variable in side.variables) { arithmetic.step(); if (center.exponent(variable) == 0) return null }
        val powers = LinkedHashMap<Int, Int>()
        for (variable in center.variables) {
            arithmetic.step()
            val degree = 2L * center.exponent(variable) - side.exponent(variable)
            if (degree !in 0L..inputDegrees.getValue(variable).toLong()) return null
            if (degree > 0) powers[variable] = degree.toInt()
        }
        return Monomial.of(powers)
    }

    private fun common(left: Monomial, right: Monomial): Monomial {
        val powers = LinkedHashMap<Int, Int>()
        for (variable in left.variables) {
            arithmetic.step()
            val degree = minOf(left.exponent(variable), right.exponent(variable))
            if (degree > 0) powers[variable] = degree
        }
        return Monomial.of(powers)
    }

    private fun monomialSigns(monomial: Monomial): Int {
        var product = POSITIVE
        for (variable in monomial.variables) {
            arithmetic.step()
            var factor = signs[variable] ?: ALL
            if (monomial.exponent(variable) and 1 == 0) factor = (factor and ZERO) or
                if (factor and (NEGATIVE or POSITIVE) == 0) 0 else POSITIVE
            product = (if ((product or factor) and ZERO != 0) ZERO else 0) or
                (if ((product and NEGATIVE != 0 && factor and POSITIVE != 0) ||
                    (product and POSITIVE != 0 && factor and NEGATIVE != 0)
                ) NEGATIVE else 0) or
                (if ((product and NEGATIVE != 0 && factor and NEGATIVE != 0) ||
                    (product and POSITIVE != 0 && factor and POSITIVE != 0)
                ) POSITIVE else 0)
        }
        return product
    }
}

private fun relationSigns(relation: PolynomialRelation): Int = when (relation) {
    PolynomialRelation.EQ -> ZERO
    PolynomialRelation.NE -> NEGATIVE or POSITIVE
    PolynomialRelation.LT -> NEGATIVE
    PolynomialRelation.LE -> NEGATIVE or ZERO
    PolynomialRelation.GT -> POSITIVE
    PolynomialRelation.GE -> POSITIVE or ZERO
}

private fun negate(signs: Int): Int = (signs and ZERO) or
    (if (signs and NEGATIVE != 0) POSITIVE else 0) or (if (signs and POSITIVE != 0) NEGATIVE else 0)

private fun addSigns(left: Int, right: Int): Int {
    if ((left and NEGATIVE != 0 && right and POSITIVE != 0) ||
        (left and POSITIVE != 0 && right and NEGATIVE != 0)
    ) return ALL
    return (left and right and ZERO) or ((left or right) and (NEGATIVE or POSITIVE))
}

private const val NEGATIVE = 1
private const val ZERO = 2
private const val POSITIVE = 4
private const val ALL = 7
