package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.ir.PolynomialRelation

internal class PolynomialFormulaReduction private constructor(
    val formula: PolynomialFormula,
    private val fibres: List<FormulaFibre>,
    val inconsistent: Boolean,
    private val booleansReduced: Boolean,
) {
    val eliminatedCount: Int get() = fibres.size
    val requiresOriginalCheck: Boolean get() = booleansReduced || fibres.isNotEmpty()

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
            var booleansReduced = false
            try {
                while (true) {
                    arithmetic.step()
                    val reducedBooleans = current.reduceBooleanDefinitions(arithmetic)
                    if (reducedBooleans != null) {
                        current = reducedBooleans
                        booleansReduced = true
                        yield(null)
                    }
                    val forced = current.forcedAtoms(arithmetic)
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
                        val graph = equationalSection(equations, family, arithmetic)
                        if (graph == null) {
                            if (reducedBooleans != null) {
                                yield(null)
                                continue
                            }
                            break
                        }
                        replacements[graph.variable] = graph.replacement
                    }
                    val rewritten = ArrayList<PolynomialAtom>()
                    val cache = HashMap<Polynomial, Polynomial>()
                    for (atom in current.atoms) {
                        arithmetic.step()
                        val polynomial = cache[atom.polynomial] ?: run {
                            var value = atom.polynomial
                            for (variable in atom.polynomial.variables) {
                                arithmetic.step()
                                val replacement = replacements[variable] ?: continue
                                value = arithmetic.substitute(value, variable, replacement)
                            }
                            cache[atom.polynomial] = value
                            value
                        }
                        rewritten.add(PolynomialAtom(polynomial, atom.relation))
                        yield(null)
                    }
                    val rewrittenFormula = current.withAtoms(rewritten, arithmetic)
                    for ((variable, replacement) in replacements) {
                        arithmetic.step()
                        fibres.add(FormulaFibre(variable, replacement))
                    }
                    current = rewrittenFormula
                    repeat(replacements.size) { arithmetic.step(); onGraph?.invoke() }
                    yield(null)
                }
            } catch (_: PolynomialResourceLimit) {
                // Only completed total substitutions and their reverse fibres survive a representation decline.
            }
            arithmetic.step()
            yield(PolynomialFormulaReduction(current, fibres.toList(), inconsistent, booleansReduced))
        }.iterator()
    }
}

private class FormulaFibre(val variable: Int, val replacement: Polynomial)
