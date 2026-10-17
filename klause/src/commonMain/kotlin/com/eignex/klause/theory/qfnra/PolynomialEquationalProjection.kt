package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialTerm
import com.eignex.klause.util.magnitudeBitLength

internal class PolynomialEquationalProjection(val variable: Int, val replacement: Polynomial)

internal fun equationalSection(
    equations: List<Polynomial>,
    family: List<Polynomial>,
    arithmetic: PolynomialArithmetic,
): PolynomialEquationalProjection? = sectionPivots(equations, family, arithmetic, false).best
    ?.projection(arithmetic)

internal fun equationalSections(
    equations: List<Polynomial>,
    family: List<Polynomial>,
    arithmetic: PolynomialArithmetic,
): List<PolynomialEquationalProjection> {
    val pivots = sectionPivots(equations, family, arithmetic, true)
    val selected = if (pivots.leaves.size > 1) pivots.leaves else listOfNotNull(pivots.best)
    return selected.map { arithmetic.step(); it.projection(arithmetic) }
}

private class SectionPivots(val best: SectionPivot?, val leaves: List<SectionPivot>)

private fun sectionPivots(
    equations: List<Polynomial>,
    family: List<Polynomial>,
    arithmetic: PolynomialArithmetic,
    collectLeaves: Boolean,
): SectionPivots {
    if (equations.isEmpty()) return SectionPivots(null, emptyList())
    val occurrences = HashMap<Int, Int>()
    for (polynomial in family) for (variable in polynomial.variables) {
        arithmetic.step()
        occurrences[variable] = (occurrences[variable] ?: 0) + 1
    }
    var best: SectionPivot? = null
    val leaves = ArrayList<SectionPivot>()
    for (polynomial in equations) {
        arithmetic.step()
        val terms = polynomial.terms
        val counts = HashMap<Int, Int>()
        for (term in terms) for (variable in term.monomial.variables) {
            arithmetic.step()
            counts[variable] = (counts[variable] ?: 0) + 1
        }
        var leaf: SectionPivot? = null
        for (term in terms) {
            arithmetic.step()
            val variable = term.monomial.variables.singleOrNull() ?: continue
            if (term.monomial.exponent(variable) != 1 || counts[variable] != 1) continue
            val fill = (terms.size - 1).toLong() * ((occurrences[variable] ?: 1) - 1)
            val bits = term.coefficient.num.magnitudeBitLength().toLong() + term.coefficient.den.magnitudeBitLength()
            val candidate = SectionPivot(polynomial, variable, term, fill, bits)
            if (best == null || candidate.before(best)) best = candidate
            if (collectLeaves && occurrences[variable] == 1 && (leaf == null || candidate.before(leaf))) {
                leaf = candidate
            }
        }
        leaf?.let(leaves::add)
    }
    return SectionPivots(best, leaves)
}

private fun SectionPivot.projection(arithmetic: PolynomialArithmetic): PolynomialEquationalProjection {
    val pivot = this
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
