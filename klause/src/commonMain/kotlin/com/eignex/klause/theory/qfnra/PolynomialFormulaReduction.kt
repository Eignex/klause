package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.util.magnitudeBitLength

internal class PolynomialFormulaReduction private constructor(
    val formula: PolynomialFormula,
    private val fibres: List<FormulaFibre>,
    val inconsistent: Boolean,
) {
    val eliminatedCount: Int get() = fibres.size

    fun reconstruct(
        assignment: QfNraAssignment,
        arithmetic: PolynomialArithmetic,
        algebraic: AlgebraicArithmetic,
    ): Map<Int, AlgebraicReal> {
        val point = HashMap<Int, AlgebraicReal>()
        for (id in 0 until assignment.realCount) {
            arithmetic.step()
            point[id] = assignment.realValue(id)
        }
        for (fibre in fibres.asReversed()) {
            arithmetic.step()
            point[fibre.variable] = algebraic.evaluate(fibre.replacement, point)
        }
        arithmetic.step()
        return point
    }

    companion object {
        fun prepare(
            formula: PolynomialFormula,
            realCount: Int,
            arithmetic: PolynomialArithmetic,
            onGraph: (() -> Unit)? = null,
        ): Iterator<PolynomialFormulaReduction?> = sequence {
            var current = formula
            val fibres = ArrayList<FormulaFibre>()
            var inconsistent = false
            var signsChecked = false
            var termLimit = 0L
            var bitLimit = 0L
            for (atom in formula.atoms) {
                arithmetic.step()
                termLimit += atom.polynomial.terms.size
                bitLimit = saturatedSum(bitLimit, scalarFootprint(atom.polynomial, arithmetic))
            }
            try {
                val required = formula.requiredAtoms(arithmetic)
                while (true) {
                    arithmetic.step()
                    val forced = current.forcedAtoms(required)
                    if (forced == null) {
                        inconsistent = true
                        break
                    }
                    if (!signsChecked) {
                        val checking = preparePolynomialSignRefutation(forced, realCount, arithmetic)
                        var conflict: Boolean? = null
                        while (conflict == null) {
                            conflict = checking.next()
                            if (conflict == null) yield(null)
                        }
                        if (conflict) {
                            inconsistent = true
                            break
                        }
                        signsChecked = true
                    }
                    val preparing = preparePolynomialConjunction(forced, arithmetic)
                    var conjunction: PolynomialConjunction? = null
                    while (conjunction == null) {
                        conjunction = preparing.next()
                        if (conjunction == null) yield(null)
                    }
                    if (conjunction.inconsistent) {
                        inconsistent = true
                        break
                    }
                    val equations = conjunction.atoms.mapNotNull { atom ->
                        arithmetic.step()
                        if (atom.relation == PolynomialRelation.EQ) atom.polynomial else null
                    }
                    val replacements = LinkedHashMap<Int, Polynomial>()
                    for (equation in equations) {
                        arithmetic.step()
                        val variable = equation.variables.singleOrNull() ?: continue
                        if (equation.degree(variable) != 1) continue
                        val graph = equationalSection(listOf(equation), emptyList(), arithmetic) ?: continue
                        val previous = replacements.put(graph.variable, graph.replacement)
                        if (previous != null && previous != graph.replacement) {
                            inconsistent = true
                            break
                        }
                    }
                    if (inconsistent) break
                    if (replacements.isEmpty()) {
                        val family = current.atoms.map { arithmetic.step(); it.polynomial }
                        val graph = equationalSection(equations, family, arithmetic) ?: break
                        replacements[graph.variable] = graph.replacement
                    }
                    val rewritten = ArrayList<PolynomialAtom>()
                    val cache = HashMap<Polynomial, Polynomial>()
                    var terms = 0L
                    var bits = 0L
                    var declined = false
                    for (atom in current.atoms) {
                        arithmetic.step()
                        val polynomial = cache[atom.polynomial] ?: run {
                            var value = atom.polynomial
                            var fits = true
                            for (variable in atom.polynomial.variables) {
                                arithmetic.step()
                                val replacement = replacements[variable] ?: continue
                                if (!substitutionFits(value, variable, replacement, termLimit, bitLimit, arithmetic)) {
                                    fits = false
                                    break
                                }
                                value = arithmetic.substitute(value, variable, replacement)
                            }
                            if (fits) {
                                cache[atom.polynomial] = value
                                value
                            } else null
                        }
                        if (polynomial == null || polynomial.terms.size > termLimit - terms) {
                            declined = true
                            break
                        }
                        val scalarBits = scalarFootprint(polynomial, arithmetic)
                        if (scalarBits > bitLimit - bits) {
                            declined = true
                            break
                        }
                        terms += polynomial.terms.size
                        bits += scalarBits
                        rewritten.add(PolynomialAtom(polynomial, atom.relation))
                        yield(null)
                    }
                    if (declined) break
                    for ((variable, replacement) in replacements) {
                        arithmetic.step()
                        fibres.add(FormulaFibre(variable, replacement))
                    }
                    current = current.withAtoms(rewritten)
                    repeat(replacements.size) { arithmetic.step(); onGraph?.invoke() }
                    yield(null)
                }
            } catch (_: PolynomialResourceLimit) {
                // Only completed total substitutions and their reverse fibres survive a representation decline.
            }
            arithmetic.step()
            yield(PolynomialFormulaReduction(current, fibres.toList(), inconsistent))
        }.iterator()
    }
}

private class FormulaFibre(val variable: Int, val replacement: Polynomial)

private fun saturatedSum(left: Long, right: Long): Long =
    if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

private fun scalarFootprint(polynomial: Polynomial, arithmetic: PolynomialArithmetic): Long {
    var result = 0L
    for (term in polynomial.terms) {
        arithmetic.step()
        val bits = term.coefficient.num.magnitudeBitLength().toLong() + term.coefficient.den.magnitudeBitLength()
        result = saturatedSum(result, bits)
    }
    return result
}

private fun substitutionFits(
    polynomial: Polynomial,
    variable: Int,
    replacement: Polynomial,
    termLimit: Long,
    bitLimit: Long,
    arithmetic: PolynomialArithmetic,
): Boolean {
    var replacementNumerator = 0L
    var replacementDenominator = 0L
    for (term in replacement.terms) {
        arithmetic.step()
        replacementNumerator = maxOf(replacementNumerator, term.coefficient.num.magnitudeBitLength().toLong())
        val denominator = term.coefficient.den.magnitudeBitLength()
        replacementDenominator = maxOf(replacementDenominator, if (denominator == 1) 0 else denominator.toLong())
    }
    var terms = 0L
    var numerator = 0L
    var denominator = 0L
    for (term in polynomial.terms) {
        arithmetic.step()
        val degree = term.monomial.exponent(variable)
        if (degree > 0 && replacement.isZero) continue
        var expanded = 1L
        if (replacement.terms.size > 1) repeat(degree) {
            arithmetic.step()
            if (expanded > termLimit / replacement.terms.size) return false
            expanded *= replacement.terms.size
        }
        if (expanded > termLimit - terms) return false
        terms += expanded
        val num = term.coefficient.num.magnitudeBitLength().toLong()
        val denBits = term.coefficient.den.magnitudeBitLength()
        val den = if (denBits == 1) 0L else denBits.toLong()
        if (num > bitLimit || den > bitLimit ||
            (replacementNumerator > 0 && degree > (bitLimit - num) / replacementNumerator) ||
            (replacementDenominator > 0 && degree > (bitLimit - den) / replacementDenominator)
        ) return false
        numerator = maxOf(numerator, num + degree * replacementNumerator)
        denominator = maxOf(denominator, den + degree * replacementDenominator)
    }
    if (terms == 0L) return true
    val carry = if (terms == 1L) 0 else 64 - (terms - 1).countLeadingZeroBits()
    if (carry > bitLimit - numerator) return false
    val remaining = bitLimit - numerator - carry
    // A common denominator can multiply every contributing denominator; numerator addition also carries.
    return denominator == 0L || terms <= (remaining / 2) / denominator
}
