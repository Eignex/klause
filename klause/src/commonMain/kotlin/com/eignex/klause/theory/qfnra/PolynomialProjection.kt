package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialTerm

internal class PolynomialRootSection(val variable: Int, val polynomial: Polynomial, val degree: Int)

internal class PolynomialProjection private constructor(
    private val levels: Array<List<Polynomial>>,
    private val variables: IntArray,
    private val sections: Array<Polynomial?>,
    private val rootSections: Array<PolynomialRootSection?>,
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
    fun rootSection(level: Int): PolynomialRootSection? = rootSections[level]
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
            nonzeroVariables: Set<Int> = emptySet(),
            nonzeroPolynomials: List<Polynomial> = emptyList(),
        ): PolynomialProjection {
            val pending = prepare(polynomials, variableCount, arithmetic,
                requiredZeroes, preferredOrder, nonzeroVariables, nonzeroPolynomials)
            while (true) pending.next()?.let { return it }
        }

        fun prepare(
            polynomials: List<Polynomial>,
            variableCount: Int,
            arithmetic: PolynomialArithmetic,
            requiredZeroes: List<Polynomial> = emptyList(),
            preferredOrder: IntArray? = null,
            nonzeroVariables: Set<Int> = emptySet(),
            nonzeroPolynomials: List<Polynomial> = emptyList(),
            onElimination: ((Int, List<Polynomial>, Polynomial?) -> Unit)? = null,
            onRootSection: () -> Unit = {},
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
            val roots = arrayOfNulls<PolynomialRootSection>(variableCount)
            val available = BooleanArray(variableCount) { true }
            val order = preferredOrder?.copyOf() ?: IntArray(variableCount) { it }
            require(order.size == variableCount)
            val seen = BooleanArray(variableCount)
            val preference = IntArray(variableCount)
            for ((rank, variable) in order.withIndex()) {
                arithmetic.step()
                require(variable in 0 until variableCount && !seen[variable])
                seen[variable] = true
                preference[variable] = rank
            }
            val nonzero = nonzeroVariables.toMutableSet()
            for (variable in nonzero) {
                arithmetic.step()
                require(variable in 0 until variableCount)
            }
            val inputs = HashSet<Polynomial>()
            if (requiredZeroes.isNotEmpty() || nonzeroPolynomials.isNotEmpty()) for (polynomial in polynomials) {
                arithmetic.step()
                inputs.add(polynomial)
            }
            for (equation in requiredZeroes) {
                arithmetic.step()
                require(equation in inputs) { "A required zero must belong to the input family" }
            }
            for (polynomial in nonzeroPolynomials) {
                arithmetic.step()
                require(polynomial in inputs) { "A required nonzero must belong to the input family" }
            }
            var equations = requiredZeroes
            var pending = projectionFamily(polynomials, arithmetic)
            var exclusions = nonzeroFamily(nonzeroPolynomials, nonzero, arithmetic)
            var fallback = variableCount - 1
            var sectionCount = 0
            for (level in variableCount - 1 downTo 0) {
                arithmetic.step()
                if (exclusions == null || inconsistentEquations(equations, arithmetic)) {
                    yield(PolynomialProjection(Array(variableCount) { emptyList() },
                        IntArray(variableCount) { it }, arrayOfNulls(variableCount),
                        arrayOfNulls(variableCount), arithmetic, true, sectionCount))
                    return@sequence
                }
                equations = projectionFamily(equations, arithmetic)
                val section = equationalSection(equations, pending, arithmetic)
                if (section != null && section.variable in nonzero && section.replacement.isZero) {
                    yield(PolynomialProjection(Array(variableCount) { emptyList() },
                        IntArray(variableCount) { it }, arrayOfNulls(variableCount),
                        arrayOfNulls(variableCount), arithmetic, true, sectionCount))
                    return@sequence
                }
                val root = if (section == null) {
                    rootSection(equations, preference, available, nonzero, arithmetic)
                } else null
                while (fallback >= 0 && !available[order[fallback]]) {
                    arithmetic.step()
                    fallback--
                }
                val variable = section?.variable ?: root?.variable ?: order[fallback]
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
                if (root != null) {
                    roots[level] = root
                    onRootSection()
                }
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
                    val rewrittenExclusions = checkNotNull(exclusions).map {
                        arithmetic.step()
                        rewritten.getValue(it)
                    }
                    exclusions = nonzeroFamily(rewrittenExclusions, nonzero, arithmetic)
                    pending = projectionFamily(projected, arithmetic)
                    sectionCount++
                    onSection()
                } else if (level > 0) {
                    exclusions = checkNotNull(exclusions).filter {
                        arithmetic.step()
                        variable !in it.variables
                    }
                    val required = HashSet<Polynomial>()
                    val lowerEquations = ArrayList<Polynomial>()
                    for (equation in equations) {
                        arithmetic.step()
                        required.add(equation)
                        if (variable !in equation.variables) lowerEquations.add(equation)
                    }
                    var independent = true
                    for (polynomial in top) for (coordinate in polynomial.variables) {
                        arithmetic.step()
                        if (coordinate != variable) independent = false
                    }
                    if (independent) {
                        // Rational univariate boundaries contribute no lower-coordinate projection.
                        equations = lowerEquations
                        pending = projectionFamily(lower, arithmetic)
                        yield(null)
                        continue
                    }
                    onElimination?.invoke(variable, top, root?.polynomial)
                    val eliminated = eliminate(top, variable, arithmetic, root?.polynomial, required)
                    var projected: ProjectionElimination? = null
                    while (projected == null) {
                        projected = eliminated.next()
                        if (projected == null) yield(null)
                    }
                    equations = lowerEquations + projected.equations
                    pending = projectionFamily(lower + projected.polynomials, arithmetic)
                }
            }
            arithmetic.step()
            if (exclusions == null || inconsistentEquations(equations, arithmetic)) {
                yield(PolynomialProjection(Array(variableCount) { emptyList() },
                    IntArray(variableCount) { it }, arrayOfNulls(variableCount),
                    arrayOfNulls(variableCount), arithmetic, true, sectionCount))
            } else yield(PolynomialProjection(levels, variables, sections, roots, arithmetic,
                equationalSections = sectionCount))
        }.iterator()
    }
}

private fun rootSection(
    equations: List<Polynomial>,
    preference: IntArray,
    available: BooleanArray,
    nonzero: Set<Int>,
    arithmetic: PolynomialArithmetic,
): PolynomialRootSection? {
    var best: PolynomialRootSection? = null
    for (equation in equations) {
        arithmetic.step()
        for ((variable, term) in monomialLeaders(equation, arithmetic)) {
            arithmetic.step()
            if (term == null || !available[variable]) continue
            var justified = true
            for (dependency in term.monomial.variables) {
                arithmetic.step()
                if (dependency != variable && dependency !in nonzero) {
                    justified = false
                    break
                }
            }
            if (!justified) continue
            if (best == null || preference[variable] > preference[best.variable]) {
                best = PolynomialRootSection(variable, equation, term.monomial.exponent(variable))
            }
        }
    }
    return best
}

private fun monomialLeaders(
    polynomial: Polynomial,
    arithmetic: PolynomialArithmetic,
): Map<Int, PolynomialTerm?> {
    val degrees = HashMap<Int, Int>()
    val leaders = LinkedHashMap<Int, PolynomialTerm?>()
    for (term in polynomial.terms) {
        arithmetic.step()
        for (variable in term.monomial.variables) {
            arithmetic.step()
            val degree = term.monomial.exponent(variable)
            val previous = degrees[variable] ?: 0
            if (degree > previous) {
                degrees[variable] = degree
                leaders[variable] = term
            } else if (degree == previous) leaders[variable] = null
        }
    }
    return leaders
}

private fun inconsistentEquations(equations: List<Polynomial>, arithmetic: PolynomialArithmetic): Boolean {
    for (equation in equations) {
        arithmetic.step()
        if (!equation.isZero && equation.variables.isEmpty()) return true
    }
    return false
}

private fun nonzeroFamily(
    polynomials: List<Polynomial>,
    variables: MutableSet<Int>,
    arithmetic: PolynomialArithmetic,
): List<Polynomial>? {
    for (polynomial in polynomials) {
        arithmetic.step()
        if (polynomial.isZero) return null
        val term = polynomial.terms.singleOrNull() ?: continue
        for (variable in term.monomial.variables) {
            arithmetic.step()
            variables.add(variable)
        }
    }
    return projectionFamily(polynomials, arithmetic)
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

private class ProjectionElimination(val polynomials: List<Polynomial>, val equations: List<Polynomial>)

private fun eliminate(
    polynomials: List<Polynomial>,
    variable: Int,
    arithmetic: PolynomialArithmetic,
    equation: Polynomial? = null,
    required: Set<Polynomial> = emptySet(),
): Iterator<ProjectionElimination?> = sequence {
    val projected = mutableListOf<Polynomial>()
    val necessary = LinkedHashSet<Polynomial>()
    val families = mutableListOf<List<Polynomial>>()
    for (polynomial in polynomials) {
        arithmetic.step()
        val coefficients = arithmetic.coefficients(polynomial, variable)
        projected.addAll(coefficients)
        val reducta = if (polynomial == equation) listOf(polynomial)
        else reducta(polynomial, coefficients, variable, arithmetic)
        families.add(reducta)
        if (equation != null && polynomial != equation) continue
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
            arithmetic.step()
            if (equation != null && polynomials[left] != equation && polynomials[right] != equation) continue
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
                    if (a == polynomials[left] && b == polynomials[right] &&
                        a in required && b in required
                    ) {
                        val resultant = result.firstOrNull()
                        if (resultant != null && !resultant.isZero) {
                            // Common roots of required equations force their full resultant to vanish.
                            necessary.add(resultant)
                            if (resultant.mainVariable == null) {
                                yield(ProjectionElimination(projected.toList(), necessary.toList()))
                                return@sequence
                            }
                        }
                    }
                    yield(null)
                }
            }
        }
    }
    yield(ProjectionElimination(projected.toList(), necessary.toList()))
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
        // A nonzero rational leading coefficient preserves this degree on every base cell.
        if (coefficient.mainVariable == null) break
        val leading = arithmetic.multiply(coefficient, arithmetic.power(Polynomial.variable(variable), degree))
        remaining = arithmetic.subtract(remaining, leading)
    }
    return result
}
