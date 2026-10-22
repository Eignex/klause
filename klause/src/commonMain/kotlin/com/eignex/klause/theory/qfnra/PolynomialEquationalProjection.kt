package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialTerm
import com.eignex.klause.util.magnitudeBitLength

internal class PolynomialEquationalProjection(val variable: Int, val replacement: Polynomial)

internal fun independentEquationalSections(
    equations: List<Polynomial>,
    family: List<Polynomial>,
    arithmetic: PolynomialArithmetic,
): List<PolynomialEquationalProjection> {
    if (equations.isEmpty()) return emptyList()
    val occurrences = sectionOccurrences(family, arithmetic)
    val candidates = ArrayList<IndexedValue<SectionPivot>>()
    for ((index, equation) in equations.withIndex()) {
        arithmetic.step()
        val pivot = sectionPivot(listOf(equation), occurrences, arithmetic) ?: continue
        candidates.add(IndexedValue(index, pivot))
    }
    candidates.sortWith(Comparator { left, right ->
        arithmetic.step()
        when {
            left.value.before(right.value) -> -1
            right.value.before(left.value) -> 1
            else -> left.index.compareTo(right.index)
        }
    })
    val sections = ArrayList<PolynomialEquationalProjection>()
    val selected = HashSet<Polynomial>()
    val variables = HashSet<Int>()
    val dependencies = HashSet<Int>()
    for (candidate in candidates) {
        arithmetic.step()
        val pivot = candidate.value
        if (pivot.polynomial in selected) continue
        var dependent = false
        for (variable in pivot.polynomial.variables) {
            arithmetic.step()
            if (variable in variables) {
                dependent = true
                break
            }
        }
        if (dependent || pivot.variable in dependencies) break
        val section = section(pivot, arithmetic)
        sections.add(section)
        selected.add(pivot.polynomial)
        variables.add(section.variable)
        for (variable in section.replacement.variables) {
            arithmetic.step()
            dependencies.add(variable)
        }
    }
    arithmetic.step()
    return sections
}

internal fun equationalSection(
    equations: List<Polynomial>,
    family: List<Polynomial>,
    arithmetic: PolynomialArithmetic,
): PolynomialEquationalProjection? {
    if (equations.isEmpty()) return null
    val pivot = sectionPivot(equations, sectionOccurrences(family, arithmetic), arithmetic) ?: return null
    return section(pivot, arithmetic)
}

private fun sectionOccurrences(family: List<Polynomial>, arithmetic: PolynomialArithmetic): Map<Int, Int> {
    val occurrences = HashMap<Int, Int>()
    for (polynomial in family) for (variable in polynomial.variables) {
        arithmetic.step()
        occurrences[variable] = (occurrences[variable] ?: 0) + 1
    }
    return occurrences
}

private fun sectionPivot(
    equations: List<Polynomial>,
    occurrences: Map<Int, Int>,
    arithmetic: PolynomialArithmetic,
): SectionPivot? {
    var best: SectionPivot? = null
    for (polynomial in equations) {
        arithmetic.step()
        val terms = polynomial.terms
        val counts = HashMap<Int, Int>()
        for (term in terms) for (variable in term.monomial.variables) {
            arithmetic.step()
            counts[variable] = (counts[variable] ?: 0) + 1
        }
        for (term in terms) {
            arithmetic.step()
            val variable = term.monomial.variables.singleOrNull() ?: continue
            if (term.monomial.exponent(variable) != 1 || counts[variable] != 1) continue
            val fill = (terms.size - 1).toLong() * ((occurrences[variable] ?: 1) - 1)
            val bits = term.coefficient.num.magnitudeBitLength().toLong() + term.coefficient.den.magnitudeBitLength()
            val candidate = SectionPivot(polynomial, variable, term, fill, bits)
            if (best == null || candidate.before(best)) best = candidate
        }
    }
    return best
}

private fun section(pivot: SectionPivot, arithmetic: PolynomialArithmetic): PolynomialEquationalProjection {
    val remainder = ArrayList<PolynomialTerm>()
    for (term in pivot.polynomial.terms) {
        arithmetic.step()
        if (term != pivot.term) remainder.add(term)
    }
    // A nonzero rational coefficient makes the section total, including singular base points.
    val replacement = arithmetic.scale(Polynomial.of(remainder), pivot.term.coefficient.reciprocal().negated())
    return PolynomialEquationalProjection(pivot.variable, replacement)
}

private class SectionPivot(
    val polynomial: Polynomial,
    val variable: Int,
    val term: PolynomialTerm,
    val fill: Long,
    val bits: Long,
) {
    fun before(other: SectionPivot): Boolean = when {
        fill != other.fill -> fill < other.fill
        bits != other.bits -> bits < other.bits
        else -> variable > other.variable
    }
}
