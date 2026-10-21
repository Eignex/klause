package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Monomial
import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.arithmetic.quadratic.QuadraticCandidates
import com.eignex.klause.arithmetic.quadratic.QuadraticCertification
import com.eignex.klause.arithmetic.quadratic.QuadraticControl
import com.eignex.klause.arithmetic.quadratic.QuadraticModel
import com.eignex.klause.arithmetic.quadratic.QuadraticRow
import com.eignex.klause.arithmetic.quadratic.QuadraticRowRelation
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation

internal fun preparePolynomialQuadraticRefutation(
    atoms: List<PolynomialAtom>,
    arithmetic: PolynomialArithmetic,
): Iterator<Boolean?> = sequence {
    val control = QuadraticControl(Cancellation { arithmetic.step(); false }, maximumMatrixCells = null)
    val candidates = QuadraticCandidates(control)
    val checker = QuadraticCertification(control)
    for (atom in atoms) {
        for (objective in quadraticOrientations(atom, arithmetic)) {
            val model = quadraticModel(objective.polynomial, arithmetic) ?: continue
            if (!checker.positiveSemidefinite(model)) continue
            if (quadraticConflict(model, objective.relation == PolynomialRelation.LT, candidates, checker)) {
                yield(true)
                return@sequence
            }
            yield(null)
            val coordinates = objective.polynomial.variables
            for (premise in atoms) {
                arithmetic.step()
                val row = quadraticAffineRow(premise, coordinates, arithmetic) ?: continue
                try {
                    val size = model.dimension.toLong() + 2
                    arithmetic.matrixCells(size, size)
                } catch (_: PolynomialResourceLimit) {
                    continue
                }
                val constrained = QuadraticModel(model.linear, model.hessian, model.constant, listOf(row))
                if (quadraticConflict(constrained, objective.relation == PolynomialRelation.LT, candidates, checker)) {
                    yield(true)
                    return@sequence
                }
                yield(null)
            }
        }
        yield(null)
    }
    yield(false)
}.iterator()

private fun quadraticOrientations(atom: PolynomialAtom, arithmetic: PolynomialArithmetic): List<PolynomialAtom> {
    arithmetic.step()
    return when (atom.relation) {
        PolynomialRelation.LE, PolynomialRelation.LT -> listOf(atom)
        PolynomialRelation.GE -> listOf(PolynomialAtom(arithmetic.negate(atom.polynomial), PolynomialRelation.LE))
        PolynomialRelation.GT -> listOf(PolynomialAtom(arithmetic.negate(atom.polynomial), PolynomialRelation.LT))
        PolynomialRelation.EQ -> listOf(atom, PolynomialAtom(arithmetic.negate(atom.polynomial), atom.relation))
        PolynomialRelation.NE -> emptyList()
    }
}

private fun quadraticModel(polynomial: Polynomial, arithmetic: PolynomialArithmetic): QuadraticModel? {
    val terms = polynomial.terms
    var quadratic = false
    for (term in terms) {
        arithmetic.step()
        val degree = term.monomial.variables.sumOf { arithmetic.step(); term.monomial.exponent(it).toLong() }
        if (degree > 2) return null
        if (degree == 2L) quadratic = true
    }
    if (!quadratic) return null
    val coordinates = polynomial.variables
    try {
        val size = coordinates.size.toLong() + 1
        arithmetic.matrixCells(size, size)
    } catch (_: PolynomialResourceLimit) {
        return null
    }
    val ids = coordinates.withIndex().associate { arithmetic.step(); it.value to it.index }
    val linear = MutableList(coordinates.size) { arithmetic.step(); BigFraction.ZERO }
    val hessian = List(coordinates.size) {
        arithmetic.step()
        MutableList(coordinates.size) { arithmetic.step(); BigFraction.ZERO }
    }
    for (term in terms) {
        arithmetic.step()
        val variables = term.monomial.variables
        if (variables.isEmpty()) continue
        val i = ids.getValue(variables.first())
        when {
            variables.size == 2 -> {
                val j = ids.getValue(variables.last())
                hessian[i][j] += term.coefficient
                hessian[j][i] += term.coefficient
            }
            term.monomial.exponent(variables.single()) == 2 ->
                hessian[i][i] += term.coefficient * BigFraction.ofLong(2)
            else -> linear[i] += term.coefficient
        }
    }
    return QuadraticModel(linear, hessian, polynomial.coefficient(Monomial.ONE))
}

private fun quadraticAffineRow(
    atom: PolynomialAtom,
    coordinates: List<Int>,
    arithmetic: PolynomialArithmetic,
): QuadraticRow? {
    val relation = when (atom.relation) {
        PolynomialRelation.LE, PolynomialRelation.GE -> QuadraticRowRelation.LE
        PolynomialRelation.LT, PolynomialRelation.GT -> QuadraticRowRelation.LT
        PolynomialRelation.EQ -> QuadraticRowRelation.EQ
        PolynomialRelation.NE -> return null
    }
    val reversed = atom.relation == PolynomialRelation.GE || atom.relation == PolynomialRelation.GT
    val coefficients = MutableList(coordinates.size) { arithmetic.step(); BigFraction.ZERO }
    for (term in atom.polynomial.terms) {
        arithmetic.step()
        val variable = term.monomial.variables.singleOrNull()
        if (term.monomial == Monomial.ONE) continue
        if (variable == null || term.monomial.exponent(variable) != 1) return null
        val index = coordinates.indexOf(variable)
        if (index < 0) return null
        coefficients[index] += if (reversed) term.coefficient.negated() else term.coefficient
    }
    val constant = atom.polynomial.coefficient(Monomial.ONE)
    return QuadraticRow(coefficients, if (reversed) constant else constant.negated(), relation)
}

private fun quadraticConflict(
    model: QuadraticModel,
    strict: Boolean,
    candidates: QuadraticCandidates,
    checker: QuadraticCertification,
): Boolean {
    val stationary = candidates.stationary(model, model.rows.indices.toList()) ?: return false
    val bound = checker.bound(model, stationary) ?: return false
    if (bound.value.signum() > 0) return true
    if (!bound.value.isZero) return false
    // Positive weight on a strict premise makes equality with the certified lower bound impossible.
    return strict || model.rows.indices.any {
        model.rows[it].relation == QuadraticRowRelation.LT && stationary.multipliers[it].signum() > 0
    }
}
