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
): PolynomialEquationalProjection? {
    var best: SectionPivot? = null
    visitSectionPivots(equations, family, arithmetic) { candidate ->
        val previous = best
        if (previous == null || candidate.before(previous)) best = candidate
    }
    return best?.projection(arithmetic)
}

internal fun equationalSections(
    equations: List<Polynomial>,
    family: List<Polynomial>,
    arithmetic: PolynomialArithmetic,
): List<PolynomialEquationalProjection> {
    val candidates = ArrayList<SectionPivot>()
    visitSectionPivots(equations, family, arithmetic) { candidates.add(it) }
    candidates.sortWith { left, right ->
        arithmetic.step()
        when {
            left.before(right) -> -1
            right.before(left) -> 1
            else -> 0
        }
    }
    val targets = HashSet<Int>()
    val dependencies = HashSet<Int>()
    val result = ArrayList<PolynomialEquationalProjection>()
    // Independent replacements commute and cannot reintroduce an eliminated coordinate.
    for (candidate in candidates) {
        arithmetic.step()
        val variable = candidate.variable
        if (variable in targets || variable in dependencies) continue
        val inputs = candidate.polynomial.variables.filter {
            arithmetic.step()
            it != variable
        }
        if (inputs.any { arithmetic.step(); it in targets }) continue
        result.add(candidate.projection(arithmetic))
        targets.add(variable)
        dependencies.addAll(inputs)
    }
    return result
}

private fun visitSectionPivots(
    equations: List<Polynomial>,
    family: List<Polynomial>,
    arithmetic: PolynomialArithmetic,
    visit: (SectionPivot) -> Unit,
) {
    if (equations.isEmpty()) return
    val occurrences = HashMap<Int, Int>()
    for (polynomial in family) for (variable in polynomial.variables) {
        arithmetic.step()
        occurrences[variable] = (occurrences[variable] ?: 0) + 1
    }
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
            visit(SectionPivot(polynomial, variable, term, fill, bits))
        }
    }
}

private fun SectionPivot.projection(arithmetic: PolynomialArithmetic): PolynomialEquationalProjection {
    val remainder = ArrayList<PolynomialTerm>()
    for (next in polynomial.terms) {
        arithmetic.step()
        if (next != term) remainder.add(next)
    }
    // A nonzero rational coefficient makes the section total, including singular base points.
    val replacement = arithmetic.scale(Polynomial.of(remainder), term.coefficient.reciprocal().negated())
    return PolynomialEquationalProjection(variable, replacement)
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
