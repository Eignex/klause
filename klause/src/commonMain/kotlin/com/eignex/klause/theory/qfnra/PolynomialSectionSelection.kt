package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialTerm
import com.eignex.klause.util.magnitudeBitLength

internal class PolynomialSectionSelection(private val arithmetic: PolynomialArithmetic) {
    private var familyCounts: Map<Polynomial, Int> = emptyMap()
    private var occurrences: Map<Int, Int> = emptyMap()
    private var coefficients: Map<Polynomial, List<SectionCoefficient>> = emptyMap()

    fun select(equations: List<Polynomial>, family: List<Polynomial>): PolynomialEquationalProjection? {
        arithmetic.step()
        if (equations.isEmpty()) {
            familyCounts = emptyMap()
            occurrences = emptyMap()
            coefficients = emptyMap()
            return null
        }
        val nextFamily = HashMap<Polynomial, Int>()
        for (polynomial in family) {
            arithmetic.step()
            nextFamily[polynomial] = (nextFamily[polynomial] ?: 0) + 1
        }
        val nextOccurrences = HashMap<Int, Int>()
        for ((variable, count) in occurrences) {
            arithmetic.step()
            nextOccurrences[variable] = count
        }
        for ((polynomial, count) in familyCounts) {
            arithmetic.step()
            updateOccurrences(nextOccurrences, polynomial, (nextFamily[polynomial] ?: 0) - count)
        }
        for ((polynomial, count) in nextFamily) {
            arithmetic.step()
            if (polynomial !in familyCounts) updateOccurrences(nextOccurrences, polynomial, count)
        }
        val nextCoefficients = HashMap<Polynomial, List<SectionCoefficient>>()
        var best: SelectionPivot? = null
        for (polynomial in equations) {
            arithmetic.step()
            val candidates = nextCoefficients.getOrPut(polynomial) {
                coefficients[polynomial] ?: captureCoefficients(polynomial)
            }
            for (coefficient in candidates) {
                arithmetic.step()
                val fill = (polynomial.terms.size - 1).toLong() *
                    ((nextOccurrences[coefficient.variable] ?: 1) - 1)
                val candidate = SelectionPivot(polynomial, coefficient, fill)
                if (best == null || candidate.before(best)) best = candidate
            }
        }
        val selected = best?.let {
            totalPolynomialSection(it.coefficient.variable, it.polynomial, it.coefficient.term, arithmetic)
        }
        arithmetic.step()
        // Only current equations and family counts survive; an interrupted update cannot replace them.
        familyCounts = nextFamily
        occurrences = nextOccurrences
        coefficients = nextCoefficients
        return selected
    }

    private fun updateOccurrences(counts: MutableMap<Int, Int>, polynomial: Polynomial, difference: Int) {
        if (difference == 0) return
        for (variable in polynomial.variables) {
            arithmetic.step()
            val count = (counts[variable] ?: 0) + difference
            if (count == 0) counts.remove(variable) else counts[variable] = count
        }
    }

    private fun captureCoefficients(polynomial: Polynomial): List<SectionCoefficient> {
        val counts = HashMap<Int, Int>()
        for (term in polynomial.terms) for (variable in term.monomial.variables) {
            arithmetic.step()
            counts[variable] = (counts[variable] ?: 0) + 1
        }
        val result = ArrayList<SectionCoefficient>()
        for (term in polynomial.terms) {
            arithmetic.step()
            val variable = term.monomial.variables.singleOrNull() ?: continue
            if (term.monomial.exponent(variable) != 1 || counts[variable] != 1) continue
            val bits = term.coefficient.num.magnitudeBitLength().toLong() + term.coefficient.den.magnitudeBitLength()
            result.add(SectionCoefficient(variable, term, bits))
        }
        return result
    }
}

private class SectionCoefficient(val variable: Int, val term: PolynomialTerm, val bits: Long)

private class SelectionPivot(
    val polynomial: Polynomial,
    val coefficient: SectionCoefficient,
    val fill: Long,
) {
    fun before(other: SelectionPivot): Boolean = when {
        fill != other.fill -> fill < other.fill
        coefficient.bits != other.coefficient.bits -> coefficient.bits < other.coefficient.bits
        else -> coefficient.variable > other.coefficient.variable
    }
}
