package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Monomial
import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.magnitudeBitLength

internal class PolynomialWitnessDefinitions(
    private val arithmetic: PolynomialArithmetic,
    private val maximumTerms: Int,
    private val maximumDegree: Int,
    private val maximumBits: Int,
) {
    fun point(
        atoms: List<PolynomialAtom>,
        realCount: Int,
        fixed: Map<Int, BigFraction>,
    ): Map<Int, BigFraction>? {
        repeat(atoms.size + 1) { arithmetic.step() }
        var residual = atoms.toList()
        val definitions = ArrayList<Pair<Int, Polynomial>>()
        if (!admitted(residual.map { it.polynomial })) return null
        while (true) {
            val pivot = pivot(residual, fixed) ?: break
            arithmetic.step()
            val polynomial = residual[pivot.atom].polynomial
            val term = arithmetic.scale(Polynomial.variable(pivot.variable), pivot.coefficient)
            arithmetic.step()
            val inverse = pivot.coefficient.reciprocal().negated()
            if (!admitted(inverse)) return null
            val replacement = arithmetic.scale(arithmetic.subtract(polynomial, term), inverse)
            val updated = ArrayList<PolynomialAtom>()
            for ((index, atom) in residual.withIndex()) {
                arithmetic.step()
                if (index != pivot.atom) {
                    updated.add(PolynomialAtom(arithmetic.substitute(atom.polynomial, pivot.variable, replacement),
                        atom.relation))
                }
            }
            val retained = buildList {
                for (definition in definitions) { arithmetic.step(); add(definition.second) }
                arithmetic.step()
                add(replacement)
                for (atom in updated) { arithmetic.step(); add(atom.polynomial) }
            }
            if (!admitted(retained)) return null
            arithmetic.step()
            definitions.add(pivot.variable to replacement)
            residual = updated
        }
        if (definitions.isEmpty()) return null
        val referenced = HashSet<Int>()
        for (atom in atoms) for (variable in atom.polynomial.variables) {
            arithmetic.step()
            require(variable in 0 until realCount)
            referenced.add(variable)
        }
        val point = LinkedHashMap<Int, BigFraction>()
        for (id in 0 until realCount) {
            arithmetic.step()
            val value = fixed[id] ?: if (id in referenced) BigFraction.ONE else BigFraction.ZERO
            if (!admitted(value)) return null
            point[id] = value
        }
        if (!hint(residual, point, fixed)) return null
        for ((variable, polynomial) in definitions.asReversed()) {
            arithmetic.step()
            point[variable] = evaluate(polynomial, point) ?: return null
        }
        return point
    }

    private fun hint(
        atoms: List<PolynomialAtom>,
        point: MutableMap<Int, BigFraction>,
        fixed: Map<Int, BigFraction>,
    ): Boolean {
        for (atom in atoms) {
            arithmetic.step()
            val terms = atom.polynomial.terms
            val linear = terms.filter { arithmetic.step(); it.monomial != Monomial.ONE }.singleOrNull() ?: continue
            val variable = linear.monomial.variables.singleOrNull() ?: continue
            if (variable in fixed || linear.monomial.exponent(variable) != 1) continue
            arithmetic.step()
            val root = atom.polynomial.coefficient(Monomial.ONE).negated() * linear.coefficient.reciprocal()
            if (!admitted(root)) return false
            arithmetic.step()
            val at = point.getValue(variable)
            val sign = if (linear.coefficient.signum() > 0) at.compareTo(root) else -at.compareTo(root)
            if (atom.holds(sign)) continue
            arithmetic.step()
            val direction = when (atom.relation) {
                PolynomialRelation.EQ, PolynomialRelation.LE, PolynomialRelation.GE -> BigFraction.ZERO
                PolynomialRelation.NE, PolynomialRelation.GT -> BigFraction.ONE
                PolynomialRelation.LT -> BigFraction.MINUS_ONE
            }
            val next = root + if (linear.coefficient.signum() > 0) direction else direction.negated()
            if (!admitted(next)) return false
            point[variable] = next
        }
        return true
    }

    private data class Pivot(val atom: Int, val variable: Int, val coefficient: BigFraction)

    private fun pivot(atoms: List<PolynomialAtom>, fixed: Map<Int, BigFraction>): Pivot? {
        for ((index, atom) in atoms.withIndex()) {
            arithmetic.step()
            if (atom.relation != PolynomialRelation.EQ) continue
            val terms = atom.polynomial.terms
            for (term in terms.asReversed()) {
                arithmetic.step()
                val variable = term.monomial.variables.singleOrNull() ?: continue
                if (variable in fixed || term.monomial.exponent(variable) != 1) continue
                if (terms.all {
                        arithmetic.step()
                        it.monomial == term.monomial || it.monomial.exponent(variable) == 0
                    }
                ) return Pivot(index, variable, term.coefficient)
            }
        }
        return null
    }

    private fun admitted(polynomials: List<Polynomial>): Boolean {
        var count = 0
        for (polynomial in polynomials) {
            arithmetic.step()
            val terms = polynomial.terms
            if (terms.size > maximumTerms - count) return false
            count += terms.size
            for (term in terms) {
                if (!admitted(term.coefficient)) return false
                for (variable in term.monomial.variables) {
                    arithmetic.step()
                    if (term.monomial.exponent(variable) > maximumDegree) return false
                }
            }
        }
        return true
    }

    private fun admitted(value: BigFraction): Boolean {
        arithmetic.step()
        return value.num.magnitudeBitLength() <= maximumBits && value.den.magnitudeBitLength() <= maximumBits
    }

    private fun evaluate(polynomial: Polynomial, point: Map<Int, BigFraction>): BigFraction? {
        var sum = BigFraction.ZERO
        for (term in polynomial.terms) {
            arithmetic.step()
            var product = term.coefficient
            for (variable in term.monomial.variables) {
                arithmetic.step()
                val factor = power(point.getValue(variable), term.monomial.exponent(variable)) ?: return null
                arithmetic.step()
                product *= factor
                if (!admitted(product)) return null
            }
            arithmetic.step()
            sum += product
            if (!admitted(sum)) return null
        }
        return sum
    }

    private fun power(value: BigFraction, exponent: Int): BigFraction? {
        var result = BigFraction.ONE
        var base = value
        var remaining = exponent
        while (remaining != 0) {
            arithmetic.step()
            if (remaining and 1 != 0) {
                result *= base
                if (!admitted(result)) return null
            }
            remaining = remaining ushr 1
            if (remaining != 0) {
                arithmetic.step()
                base *= base
                if (!admitted(base)) return null
            }
        }
        return result
    }
}
