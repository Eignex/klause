package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Monomial
import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.simplex.exact.BigFraction

internal fun preparePolynomialOrthogonalGauge(
    atoms: List<PolynomialAtom>,
    fixed: Set<Int>,
    arithmetic: PolynomialArithmetic,
): Iterator<List<PolynomialAtom>?> = sequence {
    val candidates = LinkedHashMap<Int, MutableList<List<Int>>>()
    val occupied = HashSet<Int>()
    for (atom in atoms) {
        arithmetic.step()
        val variables = normCoordinates(atom.polynomial, arithmetic)
        if (variables != null && variables.none { arithmetic.step(); it in occupied || it in fixed }) {
            candidates.getOrPut(variables.size) { ArrayList() }.add(variables)
            for (variable in variables) {
                arithmetic.step()
                occupied.add(variable)
            }
        }
        yield(null)
    }
    for (blocks in candidates.values) {
        arithmetic.step()
        if (blocks.size < 2) continue
        val dimension = blocks.first().size
        val coordinates = HashMap<Int, GaugeCoordinate>()
        for ((block, variables) in blocks.withIndex()) for ((axis, variable) in variables.withIndex()) {
            arithmetic.step()
            coordinates[variable] = GaugeCoordinate(block, axis)
        }
        var invariant = true
        var coupled = false
        for (atom in atoms) {
            arithmetic.step()
            val certificate = gramPolynomial(atom.polynomial, coordinates, dimension, arithmetic)
            if (certificate == null) {
                invariant = false
                break
            }
            coupled = coupled || certificate
            yield(null)
        }
        if (!invariant || !coupled) continue
        val constraints = ArrayList<PolynomialAtom>()
        // A common orthogonal change of basis gives every orbit a lower trapezoidal representative.
        // Weak pivots retain zero vectors and rank-deficient configurations.
        for (row in 0 until minOf(blocks.size, dimension)) {
            arithmetic.step()
            for (axis in row + 1 until dimension) {
                arithmetic.step()
                constraints.add(PolynomialAtom(Polynomial.variable(blocks[row][axis]), PolynomialRelation.EQ))
            }
            constraints.add(PolynomialAtom(Polynomial.variable(blocks[row][row]), PolynomialRelation.GE))
            yield(null)
        }
        arithmetic.step()
        yield(constraints)
        return@sequence
    }
    arithmetic.step()
    yield(emptyList())
}.iterator()

private fun normCoordinates(polynomial: Polynomial, arithmetic: PolynomialArithmetic): List<Int>? {
    val variables = ArrayList<Int>()
    var coefficient: BigFraction? = null
    for (term in polynomial.terms) {
        arithmetic.step()
        val ids = term.monomial.variables
        if (ids.isEmpty()) continue
        if (ids.size != 1 || term.monomial.exponent(ids.single()) != 2) return null
        if (coefficient != null && coefficient != term.coefficient) return null
        coefficient = term.coefficient
        variables.add(ids.single())
    }
    arithmetic.step()
    return if (variables.size >= 2) variables.sorted() else null
}

private class GaugeCoordinate(val block: Int, val axis: Int)
private data class GramCoefficient(val first: Int, val second: Int, val parameter: Monomial)
private class GramAxes(val coefficient: BigFraction, val axes: MutableSet<Int>)

private fun gramPolynomial(
    polynomial: Polynomial,
    coordinates: Map<Int, GaugeCoordinate>,
    dimension: Int,
    arithmetic: PolynomialArithmetic,
): Boolean? {
    val coefficients = HashMap<GramCoefficient, GramAxes>()
    var coupled = false
    for (term in polynomial.terms) {
        arithmetic.step()
        val parameter = HashMap<Int, Int>()
        val rotated = ArrayList<GaugeCoordinate>()
        for (variable in term.monomial.variables) {
            arithmetic.step()
            val exponent = term.monomial.exponent(variable)
            val coordinate = coordinates[variable]
            if (coordinate == null) parameter[variable] = exponent
            else {
                if (exponent > 2 || rotated.size > 2 - exponent) return null
                repeat(exponent) { arithmetic.step(); rotated.add(coordinate) }
            }
        }
        if (rotated.isEmpty()) continue
        if (rotated.size != 2 || rotated[0].axis != rotated[1].axis) return null
        val first = minOf(rotated[0].block, rotated[1].block)
        val second = maxOf(rotated[0].block, rotated[1].block)
        coupled = coupled || first != second
        val key = GramCoefficient(first, second, Monomial.of(parameter))
        val previous = coefficients.getOrPut(key) { GramAxes(term.coefficient, HashSet()) }
        if (previous.coefficient != term.coefficient || !previous.axes.add(rotated[0].axis)) return null
    }
    for (coefficient in coefficients.values) {
        arithmetic.step()
        if (coefficient.axes.size != dimension) return null
    }
    arithmetic.step()
    return coupled
}
