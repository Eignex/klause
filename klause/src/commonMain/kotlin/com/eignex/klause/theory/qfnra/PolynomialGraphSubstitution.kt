package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialTerm
import com.eignex.klause.ir.PolynomialRelation

internal class PolynomialGraphDefinition(val atom: Int, val variable: Int, val replacement: Polynomial)

internal fun triangularGraphSections(
    atoms: List<PolynomialAtom>,
    arithmetic: PolynomialArithmetic,
): List<PolynomialGraphDefinition> {
    val selected = HashMap<Int, PolynomialGraphDefinition>()
    for ((index, atom) in atoms.withIndex()) {
        arithmetic.step()
        if (atom.relation != PolynomialRelation.EQ) continue
        var variable = -1
        for (id in atom.polynomial.variables) {
            arithmetic.step()
            variable = maxOf(variable, id)
        }
        if (variable < 0 || variable in selected) continue
        var pivot: PolynomialTerm? = null
        var total = true
        for (term in atom.polynomial.terms) {
            arithmetic.step()
            val exponent = term.monomial.exponent(variable)
            if (exponent == 0) continue
            if (pivot != null || exponent != 1 || term.monomial.variables.size != 1) {
                total = false
                break
            }
            pivot = term
        }
        if (!total || pivot == null) continue
        val remainder = ArrayList<PolynomialTerm>()
        for (term in atom.polynomial.terms) {
            arithmetic.step()
            if (term != pivot) remainder.add(term)
        }
        val replacement = arithmetic.scale(Polynomial.of(remainder), pivot.coefficient.reciprocal().negated())
        selected[variable] = PolynomialGraphDefinition(index, variable, replacement)
    }
    return selected.values.sortedWith { left, right ->
        arithmetic.step()
        right.variable.compareTo(left.variable)
    }
}

internal class PolynomialGraphSubstitution(
    sections: List<PolynomialGraphDefinition>,
    private val arithmetic: PolynomialArithmetic,
) {
    private val definitions = sections.associate { arithmetic.step(); it.variable to it.replacement }
    private val closed = HashMap<Int, Polynomial>()

    fun prepare(value: Polynomial): Iterator<Polynomial?> = sequence {
        var result = value
        for (variable in value.variables) {
            arithmetic.step()
            if (variable !in definitions) continue
            val pending = ArrayDeque<Pair<Int, Boolean>>()
            pending.addLast(variable to false)
            while (pending.isNotEmpty()) {
                arithmetic.step()
                val (id, combine) = pending.removeLast()
                if (id in closed) continue
                val replacement = definitions.getValue(id)
                if (!combine) {
                    pending.addLast(id to true)
                    for (dependency in replacement.variables) {
                        arithmetic.step()
                        if (dependency in definitions && dependency !in closed) {
                            check(dependency < id)
                            pending.addLast(dependency to false)
                        }
                    }
                    yield(null)
                    continue
                }
                var expanded = replacement
                for (dependency in replacement.variables) {
                    arithmetic.step()
                    if (dependency !in definitions) continue
                    expanded = arithmetic.substitute(expanded, dependency, closed.getValue(dependency))
                    yield(null)
                }
                closed[id] = expanded
                yield(null)
            }
            result = arithmetic.substitute(result, variable, closed.getValue(variable))
            yield(null)
        }
        arithmetic.step()
        yield(result)
    }.iterator()
}
