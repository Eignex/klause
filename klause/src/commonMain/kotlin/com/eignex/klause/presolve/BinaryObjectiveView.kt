package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
import com.eignex.klause.util.toLongExact

internal class BinaryObjectiveView(
    val factors: List<Factor>,
    val objective: LinearObjective,
    val numBoolVars: Int,
) {
    companion object {
        fun create(problem: BakedProblem, objective: LinearObjective): BinaryObjectiveView? {
            if (problem.semantics.isApproximate || objective.realCoefficientValues.any { it != 0.0 } ||
                objective.boolWeightValues.size > problem.numBoolVars ||
                objective.intCoefficientValues.none { it != 0L }
            ) return null
            val ranges = ColumnRanges.of(problem.rootIntDomainsInPlace)
            for (v in objective.intCoefficientValues.indices) {
                if (objective.intCoefficientValues[v] == 0L) continue
                if (v >= problem.numIntVars || !ranges.isClosed(v) || ranges.min(v) < 0L || ranges.max(v) > 1L) {
                    return null
                }
            }
            val literals = IntArray(problem.numIntVars) { Lit.NONE }
            for (factor in problem.factors) {
                val literal = factor.binaryChannelLiteral()
                if (literal != Lit.NONE) literals[factor.intVars.single()] = literal
            }
            val weights = MutableList(problem.numBoolVars) { bigIntOf(objective.boolWeightValues.getOrElse(it) { 0L }) }
            val factors = problem.factors.toMutableList()
            var constant = bigIntOf(objective.constant)
            for (v in objective.intCoefficientValues.indices) {
                val coefficient = bigIntOf(objective.intCoefficientValues[v])
                if (objective.intCoefficientValues[v] == 0L) continue
                if (ranges.min(v) == ranges.max(v)) {
                    if (ranges.min(v) == 1L) constant += coefficient
                    continue
                }
                var literal = literals[v]
                if (literal == Lit.NONE) {
                    literal = Lit.make(weights.size, true)
                    factors.add(ReifiedLinear(weights.size, longArrayOf(1), intArrayOf(v), LinearOp.EQ, 1))
                    weights.add(bigIntOf(0))
                }
                val id = Lit.variable(literal)
                if (Lit.isPositive(literal)) {
                    weights[id] += coefficient
                } else {
                    constant += coefficient
                    weights[id] -= coefficient
                }
            }
            if (!constant.fitsLong() || weights.any { !it.fitsLong() }) return null
            return BinaryObjectiveView(
                factors,
                objective.copy(
                    boolWeights = LongArray(weights.size) { weights[it].toLongExact() },
                    intCoefficients = LongArray(objective.intCoefficientValues.size),
                    constant = constant.toLongExact(),
                ),
                weights.size,
            )
        }
    }
}

internal fun Factor.binaryChannelLiteral(): Int {
    val channel = this as? ReifiedLinear ?: return Lit.NONE
    val row = channel.integerConstants ?: return Lit.NONE
    if (channel.vars.size != 1) return Lit.NONE
    fun holds(value: Long): Boolean = when (channel.op) {
        LinearOp.LE -> value <= row.bound
        LinearOp.GE -> value >= row.bound
        LinearOp.EQ -> value == row.bound
        LinearOp.NE -> value != row.bound
    }
    val atZero = holds(0L)
    val atOne = holds(row.coeff(0))
    return if (atZero == atOne) Lit.NONE else Lit.make(channel.auxBoolVar, atOne)
}
