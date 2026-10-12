package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialResource
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.remap
import com.eignex.klause.ir.requireVariableCounts
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter

internal class PolynomialObjectiveEvidence(
    val formula: PolynomialRealFormula,
    val objective: PolynomialExpression,
    val boolCount: Int,
    val realCount: Int,
    roots: List<AlgebraicReal>,
    val firstFeasibleCell: Long,
) {
    private val capturedRoots = roots.toList()
    val roots: List<AlgebraicReal> get() = capturedRoots.toList()
}

internal sealed interface PolynomialObjectiveResult {
    val stats: QfNraStats

    data class Optimum(
        val value: AlgebraicReal,
        val assignment: QfNraAssignment,
        val evidence: PolynomialObjectiveEvidence,
        override val stats: QfNraStats,
    ) : PolynomialObjectiveResult

    data class Infimum(
        val value: AlgebraicReal,
        val incumbent: QfNraAssignment,
        val evidence: PolynomialObjectiveEvidence,
        override val stats: QfNraStats,
    ) : PolynomialObjectiveResult

    data class Unbounded(
        val incumbent: QfNraAssignment,
        val evidence: PolynomialObjectiveEvidence,
        override val stats: QfNraStats,
    ) : PolynomialObjectiveResult

    data class Infeasible(override val stats: QfNraStats) : PolynomialObjectiveResult
    data class Unknown(
        val reason: QfNraReason,
        override val stats: QfNraStats,
        val resource: PolynomialResource? = null,
    ) : PolynomialObjectiveResult
}

internal fun solvePolynomialObjectiveImage(
    formula: PolynomialRealFormula,
    objective: PolynomialExpression,
    boolCount: Int,
    realCount: Int,
    params: QfNraParams = QfNraParams(),
    condition: PolynomialCondition = PolynomialCondition(),
): PolynomialObjectiveResult {
    val work = ObjectiveImageWork(params)
    val arithmetic = PolynomialArithmetic(work, params.polynomialLimits)
    val algebraic = AlgebraicArithmetic(arithmetic)
    return try {
        arithmetic.step()
        require(realCount < Int.MAX_VALUE)
        val conditioned = condition.apply(formula, boolCount, realCount, arithmetic::step)
        PolynomialRealFormula.Atom(objective, PolynomialRelation.EQ, objective)
            .requireVariableCounts(boolCount, realCount, arithmetic::step)
        conditioned.requireVariableCounts(boolCount, realCount, arithmetic::step)
        val originalObjective = PolynomialFormula.normalize(PolynomialRealFormula.Atom(
            objective, PolynomialRelation.EQ, PolynomialExpression.Constant.of(BigFraction.ZERO.num),
        ), arithmetic).atoms.single().polynomial
        val remap = VarRemap(
            IntArray(boolCount) { arithmetic.step(); it }, intArrayOf(),
            IntArray(realCount) { arithmetic.step(); it + 1 },
        )
        val imageVariable = PolynomialExpression.Variable(SourceRealId(0))
        val shiftedObjective = objective.remap(remap)
        val image = PolynomialRealFormula.And(listOf(
            conditioned.remap(remap),
            PolynomialRealFormula.Atom(imageVariable, PolynomialRelation.EQ, shiftedObjective),
        ))
        arithmetic.step()
        val normalized = PolynomialFormula.normalize(image, arithmetic)
        val projection = PolynomialProjection.compute(normalized.atoms.map { it.polynomial }, realCount + 1, arithmetic)
        work.projected = projection.polynomialCount
        val roots = objectiveRoots(projection, algebraic, arithmetic)
        var result: PolynomialObjectiveResult? = null
        for (cell in 0..2L * roots.size) {
            arithmetic.step()
            val root = (cell / 2).toInt()
            val value = if (cell and 1L != 0L) roots[root] else algebraic.rational(
                algebraic.sampleBetween(roots.getOrNull(root - 1), roots.getOrNull(root)),
            )
            val query = PolynomialRealFormula.And(listOf(
                image, algebraicFixing(imageVariable, value, arithmetic::step),
            ))
            val fixing = value.rational?.let {
                PolynomialCondition(lower = mapOf(0 to it), upper = mapOf(0 to it))
            } ?: PolynomialCondition()
            val checked = solveQfNra(query, boolCount, realCount + 1, params.copy(
                cancellation = work, maximumWork = null,
            ), fixing)
            work.projected = sum(work.projected, checked.stats.projectedPolynomials)
            when (checked) {
                is QfNraResult.Unknown -> {
                    work.lifted = sum(work.lifted, checked.stats.liftedCells)
                    result = PolynomialObjectiveResult.Unknown(checked.reason, work.snapshot(), checked.resource)
                    break
                }
                is QfNraResult.Unsatisfiable -> work.lifted = sum(work.lifted, checked.stats.liftedCells)
                is QfNraResult.Satisfiable -> {
                    work.lifted = sum(work.lifted, checked.stats.liftedCells)
                    check(algebraic.compare(checked.assignment.realValue(0), value) == 0)
                    val assignment = QfNraAssignment(checked.assignment.bools, checked.assignment.reals.drop(1))
                    val coordinates = (0 until realCount).associateWith {
                        arithmetic.step()
                        assignment.realValue(it)
                    }
                    val original = PolynomialFormula.normalize(conditioned, arithmetic)
                    val sourceAtoms = IntArray(original.atoms.size) { index ->
                        arithmetic.step()
                        val atom = original.atoms[index]
                        if (atom.holds(algebraic.sign(algebraic.evaluate(atom.polynomial, coordinates)))) {
                            PolynomialFormula.TRUE
                        } else PolynomialFormula.FALSE
                    }
                    val sourceBools = IntArray(boolCount) {
                        arithmetic.step()
                        if (assignment.boolValue(it)) PolynomialFormula.TRUE else PolynomialFormula.FALSE
                    }
                    check(original.truth(sourceBools, sourceAtoms, arithmetic) == PolynomialFormula.TRUE)
                    check(algebraic.compare(algebraic.evaluate(originalObjective, coordinates), value) == 0)
                    val evidence = PolynomialObjectiveEvidence(
                        conditioned, objective, boolCount, realCount, roots, cell,
                    )
                    arithmetic.step()
                    result = when {
                        cell and 1L != 0L -> PolynomialObjectiveResult.Optimum(
                            value, assignment, evidence, work.snapshot(),
                        )
                        cell == 0L -> PolynomialObjectiveResult.Unbounded(assignment, evidence, work.snapshot())
                        else -> PolynomialObjectiveResult.Infimum(
                            roots[root - 1], assignment, evidence, work.snapshot(),
                        )
                    }
                    break
                }
            }
        }
        arithmetic.step()
        when (result) {
            is PolynomialObjectiveResult.Optimum -> result.copy(stats = work.snapshot())
            is PolynomialObjectiveResult.Infimum -> result.copy(stats = work.snapshot())
            is PolynomialObjectiveResult.Unbounded -> result.copy(stats = work.snapshot())
            is PolynomialObjectiveResult.Infeasible -> result.copy(stats = work.snapshot())
            is PolynomialObjectiveResult.Unknown -> result.copy(stats = work.snapshot())
            null -> PolynomialObjectiveResult.Infeasible(work.snapshot())
        }
    } catch (_: PolynomialInterrupted) {
        PolynomialObjectiveResult.Unknown(
            if (work.limitReached) QfNraReason.WorkLimit else QfNraReason.Interrupted, work.snapshot(),
        )
    } catch (stop: PolynomialResourceLimit) {
        PolynomialObjectiveResult.Unknown(QfNraReason.ResourceLimit, work.snapshot(), stop.resource)
    }
}

private fun objectiveRoots(
    projection: PolynomialProjection,
    algebraic: AlgebraicArithmetic,
    arithmetic: PolynomialArithmetic,
): List<AlgebraicReal> {
    val roots = mutableListOf<AlgebraicReal>()
    for (polynomial in projection.polynomials(0)) {
        arithmetic.step()
        val specialized = algebraic.specialize(polynomial, 0, emptyMap())
        if (!specialized.isZero) roots.addAll(algebraic.roots(specialized))
    }
    roots.sortWith(Comparator { left, right -> algebraic.compare(left, right) })
    val distinct = mutableListOf<AlgebraicReal>()
    for (root in roots) {
        arithmetic.step()
        if (distinct.isEmpty() || algebraic.compare(distinct.last(), root) != 0) distinct += root
    }
    return distinct
}

internal fun algebraicFixing(
    variable: PolynomialExpression,
    value: AlgebraicReal,
    checkpoint: () -> Unit = {},
): PolynomialRealFormula {
    checkpoint()
    fun constant(number: BigFraction): PolynomialExpression = PolynomialExpression.Constant.of(number.num, number.den)
    fun atom(relation: PolynomialRelation, number: BigFraction): PolynomialRealFormula =
        PolynomialRealFormula.Atom(variable, relation, constant(number))
    value.rational?.let { return atom(PolynomialRelation.EQ, it) }
    val polynomial = PolynomialExpression.Sum(value.definingCoefficients.mapIndexed { index, coefficient ->
        checkpoint()
        PolynomialExpression.Product(listOf(constant(coefficient), PolynomialExpression.Power(variable, index)))
    })
    return PolynomialRealFormula.And(listOf(
        PolynomialRealFormula.Atom(polynomial, PolynomialRelation.EQ, constant(BigFraction.ZERO)),
        atom(PolynomialRelation.GT, value.lowerBound), atom(PolynomialRelation.LT, value.upperBound),
    ))
}

private class ObjectiveImageWork(private val params: QfNraParams) : Cancellation, WorkMeter {
    var projected: Long = 0
    var lifted: Long = 0
    private var spent: Long = 0
    val limitReached: Boolean get() = params.maximumWork?.let { spent >= it } == true
    override fun isCancelled(): Boolean = limitReached || params.cancellation()
    override fun deadline() = params.cancellation.deadline()
    override fun workMeter(): WorkMeter = this
    override fun charge(units: Long) {
        require(units >= 0)
        spent = sum(spent, units)
        params.cancellation.charge(units)
    }
    fun snapshot(): QfNraStats = QfNraStats(projected, lifted, spent)
}

private fun sum(left: Long, right: Long): Long = left + minOf(right, Long.MAX_VALUE - left)
