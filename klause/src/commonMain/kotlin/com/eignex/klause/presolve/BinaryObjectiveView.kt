package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
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
        fun create(
            problem: BakedProblem,
            objective: LinearObjective,
            allowMixedObjective: Boolean = false,
        ): BinaryObjectiveView? {
            if (problem.numRealVars > 0 || problem.semantics.isApproximate ||
                objective.realCoefficientValues.any { it != 0.0 } ||
                objective.boolWeightValues.size > problem.numBoolVars ||
                objective.intCoefficientValues.none { it != 0L }
            ) return null
            val ranges = ColumnRanges.of(problem.rootIntDomainsInPlace)
            var hasBinaryCost = false
            var hasNonbinaryCost = false
            for (v in objective.intCoefficientValues.indices) {
                if (v % 256 == 0 && problem.cancellation()) return null
                if (objective.intCoefficientValues[v] == 0L) continue
                if (v >= problem.numIntVars) return null
                if (ranges.isClosed(v) && ranges.min(v) >= 0L && ranges.max(v) <= 1L) {
                    hasBinaryCost = true
                } else {
                    if (!allowMixedObjective) return null
                    hasNonbinaryCost = true
                }
            }
            if (!hasBinaryCost) return null
            val literals = IntArray(problem.numIntVars) { Lit.NONE }
            for ((index, factor) in problem.factors.withIndex()) {
                if (index % 256 == 0 && problem.cancellation()) return null
                val literal = factor.binaryChannelLiteral()
                if (literal != Lit.NONE) literals[factor.intVars.single()] = literal
            }
            val weights = MutableList(problem.numBoolVars) { bigIntOf(objective.boolWeightValues.getOrElse(it) { 0L }) }
            val factors = problem.factors.toMutableList()
            (problem.rootDeductions as? PropagationResult.Implied)?.forEachBool { id, value ->
                factors.add(Clause(intArrayOf(Lit.make(id, value))))
            }
            var constant = bigIntOf(objective.constant)
            val retainedCosts = objective.intCoefficientValues.copyOf()
            var changed = false
            for (v in objective.intCoefficientValues.indices) {
                val coefficient = bigIntOf(objective.intCoefficientValues[v])
                if (v % 256 == 0 && problem.cancellation()) return null
                if (objective.intCoefficientValues[v] == 0L) continue
                if (hasNonbinaryCost &&
                    (!ranges.isClosed(v) || ranges.min(v) < 0L || ranges.max(v) > 1L)
                ) continue
                if (ranges.max(v) == 0L) {
                    retainedCosts[v] = 0L
                    changed = true
                    continue
                }
                val shifted = constant + coefficient
                if (ranges.min(v) == 1L && shifted.fitsLong()) {
                    constant = shifted
                    retainedCosts[v] = 0L
                    changed = true
                    continue
                }
                val literal = literals[v]
                val id = if (literal == Lit.NONE) -1 else Lit.variable(literal)
                val positive = literal != Lit.NONE && Lit.isPositive(literal)
                val merged = when {
                    id < 0 -> coefficient
                    positive -> weights[id] + coefficient
                    else -> weights[id] - coefficient
                }
                if (id >= 0 && merged.fitsLong() && (positive || shifted.fitsLong())) {
                    weights[id] = merged
                    if (!positive) constant = shifted
                } else {
                    // Mixed objectives reuse existing channels without adding another representation.
                    if (hasNonbinaryCost) continue
                    // A fresh positive channel retains the source coefficient when signed reuse would overflow.
                    factors.add(ReifiedLinear(weights.size, longArrayOf(1), intArrayOf(v), LinearOp.EQ, 1))
                    weights.add(coefficient)
                }
                retainedCosts[v] = 0L
                changed = true
            }
            if (!changed || problem.cancellation()) return null
            val projected = objective.copy(
                boolWeights = LongArray(weights.size) { weights[it].toLongExact() },
                intCoefficients = retainedCosts,
                constant = constant.toLongExact(),
            )
            return BinaryObjectiveView(
                factors,
                if (hasNonbinaryCost) projected else projected.captureBinaryIntegerView(problem, objective),
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
