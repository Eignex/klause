package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.Monomial
import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.quadratic.CheckedQuadraticOptimum
import com.eignex.klause.arithmetic.quadratic.QuadraticCandidates
import com.eignex.klause.arithmetic.quadratic.QuadraticCertification
import com.eignex.klause.arithmetic.quadratic.QuadraticControl
import com.eignex.klause.arithmetic.quadratic.QuadraticModel
import com.eignex.klause.arithmetic.quadratic.QuadraticRow
import com.eignex.klause.arithmetic.quadratic.QuadraticRowRelation
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.requireVariableCounts
import com.eignex.klause.simplex.exact.BigFraction

internal class ConvexPolynomialOptimum(
    val formula: PolynomialRealFormula,
    val objective: PolynomialExpression,
    val assignment: QfNraAssignment,
    val certificate: CheckedQuadraticOptimum,
    bools: Map<Int, Boolean>,
) {
    val bools = bools.toMap()
}

internal fun tryConvexPolynomialOptimum(
    formula: PolynomialRealFormula,
    objective: PolynomialExpression,
    boolCount: Int,
    realCount: Int,
    bools: Map<Int, Boolean>,
    arithmetic: PolynomialArithmetic,
    control: QuadraticControl,
): ConvexPolynomialOptimum? {
    formula.requireVariableCounts(boolCount, realCount, arithmetic::step)
    require(bools.keys.all { it in 0 until boolCount })
    val algebraic = AlgebraicArithmetic(arithmetic)
    val conditioned = PolynomialBooleanCondition.apply(formula, bools, arithmetic::step)
    val model = polynomialQuadraticModel(conditioned, objective, realCount, bools, arithmetic, control) ?: return null
    val candidates = QuadraticCandidates(control)
    val checker = QuadraticCertification(control)
    val proposed = candidates.proposeActiveRows(model, List(realCount) { 0.0 })
    val selections = buildList {
        proposed?.let(::add)
        add(emptyList())
        for (row in model.rows.indices.take(64)) add(listOf(row))
    }.distinct()
    for (active in selections) {
        val stationary = candidates.stationary(model, active) ?: continue
        val bound = checker.bound(model, stationary) ?: continue
        val optimum = checker.optimum(bound) ?: continue
        val assignment = QfNraAssignment(
            BooleanArray(boolCount) { arithmetic.step(); bools[it] ?: false },
            optimum.point.map(algebraic::rational),
        )
        val coordinates = assignment.reals.withIndex().associate { it.index to it.value }
        val source = PolynomialFormula.normalize(formula, arithmetic)
        val atoms = IntArray(source.atoms.size) { index ->
            arithmetic.step()
            val atom = source.atoms[index]
            if (atom.holds(algebraic.sign(algebraic.evaluate(atom.polynomial, coordinates)))) PolynomialFormula.TRUE
            else PolynomialFormula.FALSE
        }
        val truth = IntArray(boolCount) {
            arithmetic.step()
            if (assignment.boolValue(it)) PolynomialFormula.TRUE else PolynomialFormula.FALSE
        }
        check(source.truth(truth, atoms, arithmetic) == PolynomialFormula.TRUE)
        val originalObjective = PolynomialFormula.normalize(PolynomialRealFormula.Atom(
            objective, PolynomialRelation.EQ, PolynomialExpression.Constant.of(BigFraction.ZERO.num),
        ), arithmetic).atoms.single().polynomial
        check(algebraic.compare(
            algebraic.evaluate(originalObjective, coordinates), algebraic.rational(bound.value),
        ) == 0)
        arithmetic.step()
        return ConvexPolynomialOptimum(formula, objective, assignment, optimum, bools)
    }
    return null
}

internal fun polynomialQuadraticModel(
    formula: PolynomialRealFormula,
    objective: PolynomialExpression,
    dimension: Int,
    bools: Map<Int, Boolean>,
    arithmetic: PolynomialArithmetic,
    control: QuadraticControl,
): QuadraticModel? {
    control.matrix(dimension)
    val rows = affineRows(formula, dimension, bools, arithmetic) ?: return null
    val polynomial = PolynomialFormula.normalize(PolynomialRealFormula.Atom(
        objective, PolynomialRelation.EQ, PolynomialExpression.Constant.of(BigFraction.ZERO.num),
    ), arithmetic).atoms.single().polynomial
    val linear = MutableList(dimension) { BigFraction.ZERO }
    val hessian = List(dimension) { MutableList(dimension) { BigFraction.ZERO } }
    for (term in polynomial.terms) {
        arithmetic.step()
        val variables = term.monomial.variables
        val degree = variables.sumOf { term.monomial.exponent(it).toLong() }
        if (degree > 2 || variables.any { it >= dimension }) return null
        when {
            degree == 1L -> linear[variables.single()] += term.coefficient
            degree == 2L && variables.size == 1 -> hessian[variables.single()][variables.single()] +=
                term.coefficient * BigFraction.ofLong(2)
            degree == 2L -> {
                val (i, j) = variables
                hessian[i][j] += term.coefficient
                hessian[j][i] += term.coefficient
            }
        }
    }
    return QuadraticModel(linear, hessian, polynomial.coefficient(Monomial.ONE), rows)
}

private fun affineRows(
    formula: PolynomialRealFormula,
    dimension: Int,
    bools: Map<Int, Boolean>,
    arithmetic: PolynomialArithmetic,
): List<QuadraticRow>? {
    val pending = ArrayDeque<PolynomialRealFormula>()
    pending += formula
    val rows = mutableListOf<QuadraticRow>()
    while (pending.isNotEmpty()) {
        arithmetic.step()
        when (val node = pending.removeLast()) {
            is PolynomialRealFormula.And -> pending.addAll(node.operands)
            is PolynomialRealFormula.Constant -> if (!node.value) return null
            is PolynomialRealFormula.BooleanVariable -> if (bools[node.id.index] != true) return null
            is PolynomialRealFormula.Not -> {
                val variable = node.operand as? PolynomialRealFormula.BooleanVariable ?: return null
                if (bools[variable.id.index] != false) return null
            }
            is PolynomialRealFormula.Or -> return null
            is PolynomialRealFormula.Atom -> {
                val relation = when (node.relation) {
                    PolynomialRelation.EQ -> QuadraticRowRelation.EQ
                    PolynomialRelation.LE, PolynomialRelation.GE -> QuadraticRowRelation.LE
                    PolynomialRelation.LT, PolynomialRelation.GT -> QuadraticRowRelation.LT
                    PolynomialRelation.NE -> return null
                }
                var polynomial = PolynomialFormula.normalize(node, arithmetic).atoms.single().polynomial
                if (node.relation == PolynomialRelation.GE || node.relation == PolynomialRelation.GT) {
                    polynomial = arithmetic.negate(polynomial)
                }
                val coefficients = affineCoefficients(polynomial, dimension, arithmetic) ?: return null
                rows += QuadraticRow(coefficients, polynomial.coefficient(Monomial.ONE).negated(), relation)
            }
        }
    }
    return rows
}

private fun affineCoefficients(
    polynomial: Polynomial,
    dimension: Int,
    arithmetic: PolynomialArithmetic,
): List<BigFraction>? {
    val coefficients = MutableList(dimension) { BigFraction.ZERO }
    for (term in polynomial.terms) {
        arithmetic.step()
        val variables = term.monomial.variables
        if (variables.isEmpty()) continue
        if (variables.size != 1 || term.monomial.exponent(variables.single()) != 1) return null
        val id = variables.single()
        if (id >= dimension) return null
        coefficients[id] += term.coefficient
    }
    return coefficients
}
