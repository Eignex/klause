package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic

internal class PolynomialProjection private constructor(
    private val levels: Array<List<Polynomial>>,
    private val variables: IntArray,
    private val sections: Array<Polynomial?>,
    arithmetic: PolynomialArithmetic,
    val inconsistentEquations: Boolean = false,
    val equationalSections: Int = 0,
) {
    private val ranks = IntArray(variables.size).also { ranks ->
        for (level in variables.indices) {
            arithmetic.step()
            ranks[variables[level]] = level
        }
    }
    val variableCount: Int get() = levels.size
    val polynomialCount: Long get() = levels.sumOf { it.size.toLong() }

    fun variable(level: Int): Int = variables[level]
    fun level(variable: Int): Int = ranks[variable]
    fun section(level: Int): Polynomial? = sections[level]
    fun polynomials(level: Int): List<Polynomial> {
        check(!inconsistentEquations)
        return levels[level].toList()
    }

    companion object {
        fun compute(
            polynomials: List<Polynomial>,
            variableCount: Int,
            arithmetic: PolynomialArithmetic,
            requiredZeroes: List<Polynomial> = emptyList(),
            preferredOrder: IntArray? = null,
        ): PolynomialProjection {
            val pending = prepare(polynomials, variableCount, arithmetic, requiredZeroes, preferredOrder)
            while (true) pending.next()?.let { return it }
        }

        fun prepare(
            polynomials: List<Polynomial>,
            variableCount: Int,
            arithmetic: PolynomialArithmetic,
            requiredZeroes: List<Polynomial> = emptyList(),
            preferredOrder: IntArray? = null,
            onSection: () -> Unit = {},
        ): Iterator<PolynomialProjection?> = sequence {
            require(variableCount >= 0)
            arithmetic.step()
            for (polynomial in polynomials) {
                arithmetic.step()
                require(polynomial.variables.all { it < variableCount })
            }
            val levels = Array(variableCount) {
                arithmetic.step()
                emptyList<Polynomial>()
            }
            val variables = IntArray(variableCount) { it }
            val sections = arrayOfNulls<Polynomial>(variableCount)
            val available = BooleanArray(variableCount) { true }
            val order = preferredOrder?.copyOf() ?: IntArray(variableCount) { it }
            require(order.size == variableCount)
            val seen = BooleanArray(variableCount)
            for (variable in order) {
                arithmetic.step()
                require(variable in 0 until variableCount && !seen[variable])
                seen[variable] = true
            }
            val inputs = HashSet<Polynomial>()
            if (requiredZeroes.isNotEmpty()) for (polynomial in polynomials) {
                arithmetic.step()
                inputs.add(polynomial)
            }
            for (equation in requiredZeroes) {
                arithmetic.step()
                require(equation in inputs) { "A required zero must belong to the input family" }
            }
            var equations = requiredZeroes
            var pending = projectionFamily(polynomials, arithmetic)
            var fallback = variableCount - 1
            var sectionCount = 0
            for (level in variableCount - 1 downTo 0) {
                arithmetic.step()
                if (inconsistentEquations(equations, arithmetic)) {
                    yield(PolynomialProjection(Array(variableCount) { emptyList() },
                        IntArray(variableCount) { it }, arrayOfNulls(variableCount), arithmetic, true, sectionCount))
                    return@sequence
                }
                equations = projectionFamily(equations, arithmetic)
                val section = equationalSection(equations, pending, arithmetic)
                while (fallback >= 0 && !available[order[fallback]]) {
                    arithmetic.step()
                    fallback--
                }
                val variable = section?.variable ?: order[fallback]
                check(variable >= 0 && available[variable])
                variables[level] = variable
                available[variable] = false
                val top = mutableListOf<Polynomial>()
                val lower = mutableListOf<Polynomial>()
                for (polynomial in pending) {
                    arithmetic.step()
                    if (variable in polynomial.variables) top.add(polynomial) else lower.add(polynomial)
                }
                levels[level] = if (section == null) top.toList() else emptyList()
                if (section != null) {
                    sections[level] = section.replacement
                    val rewritten = HashMap<Polynomial, Polynomial>()
                    val projected = ArrayList<Polynomial>()
                    for (polynomial in pending) {
                        arithmetic.step()
                        val value = if (variable in polynomial.variables) {
                            arithmetic.substitute(polynomial, variable, section.replacement)
                        } else polynomial
                        rewritten[polynomial] = value
                        projected.add(value)
                        yield(null)
                    }
                    equations = equations.map {
                        arithmetic.step()
                        rewritten.getValue(it)
                    }
                    pending = projectionFamily(projected, arithmetic)
                    sectionCount++
                    onSection()
                } else if (level > 0) {
                    equations = equations.filter {
                        arithmetic.step()
                        variable !in it.variables
                    }
                    val eliminated = eliminate(top, variable, arithmetic)
                    var projected: List<Polynomial>? = null
                    while (projected == null) {
                        projected = eliminated.next()
                        if (projected == null) yield(null)
                    }
                    pending = projectionFamily(lower + projected, arithmetic)
                }
            }
            arithmetic.step()
            if (inconsistentEquations(equations, arithmetic)) {
                yield(PolynomialProjection(Array(variableCount) { emptyList() },
                    IntArray(variableCount) { it }, arrayOfNulls(variableCount), arithmetic, true, sectionCount))
            } else yield(PolynomialProjection(levels, variables, sections, arithmetic,
                equationalSections = sectionCount))
        }.iterator()
    }
}

private fun inconsistentEquations(equations: List<Polynomial>, arithmetic: PolynomialArithmetic): Boolean {
    for (equation in equations) {
        arithmetic.step()
        if (!equation.isZero && equation.variables.isEmpty()) return true
    }
    return false
}

private fun projectionFamily(values: List<Polynomial>, arithmetic: PolynomialArithmetic): List<Polynomial> {
    val distinct = LinkedHashSet<Polynomial>()
    for (polynomial in values) {
        arithmetic.step()
        if (polynomial.mainVariable == null) continue
        distinct.add(arithmetic.scale(polynomial, polynomial.terms.last().coefficient.reciprocal()))
    }
    return distinct.toList()
}

private fun eliminate(
    polynomials: List<Polynomial>,
    variable: Int,
    arithmetic: PolynomialArithmetic,
): Iterator<List<Polynomial>?> = sequence {
    val projected = mutableListOf<Polynomial>()
    val families = mutableListOf<List<Polynomial>>()
    for (polynomial in polynomials) {
        arithmetic.step()
        val coefficients = arithmetic.coefficients(polynomial, variable)
        projected.addAll(coefficients)
        val reducta = reducta(polynomial, coefficients, variable, arithmetic)
        families.add(reducta)
        for (reductum in reducta) {
            arithmetic.step()
            val pending = arithmetic.preparePrincipalSubresultants(
                reductum, arithmetic.derivative(reductum, variable), variable,
            )
            var result: List<Polynomial>? = null
            while (result == null) {
                result = pending.next()
                if (result == null) yield(null)
            }
            projected.addAll(result)
            yield(null)
        }
    }
    for (left in families.indices) {
        for (right in left + 1 until families.size) {
            for (a in families[left]) {
                for (b in families[right]) {
                    arithmetic.step()
                    val pending = arithmetic.preparePrincipalSubresultants(a, b, variable)
                    var result: List<Polynomial>? = null
                    while (result == null) {
                        result = pending.next()
                        if (result == null) yield(null)
                    }
                    projected.addAll(result)
                    yield(null)
                }
            }
        }
    }
    yield(projected)
}.iterator()

private fun reducta(
    polynomial: Polynomial,
    coefficients: List<Polynomial>,
    variable: Int,
    arithmetic: PolynomialArithmetic,
): List<Polynomial> {
    val result = mutableListOf<Polynomial>()
    var remaining = polynomial
    for (degree in coefficients.lastIndex downTo 1) {
        arithmetic.step()
        val coefficient = coefficients[degree]
        if (coefficient.isZero) continue
        result.add(remaining)
        val leading = arithmetic.multiply(coefficient, arithmetic.power(Polynomial.variable(variable), degree))
        remaining = arithmetic.subtract(remaining, leading)
    }
    return result
}
