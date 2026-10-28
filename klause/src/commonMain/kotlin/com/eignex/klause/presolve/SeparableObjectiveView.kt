package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.addExact
import com.eignex.klause.util.mulExact

internal class SeparableObjectiveView private constructor(
    val problem: BakedProblem,
    val sourceObjective: LinearObjective,
    val objective: LinearObjective,
) {
    companion object {
        fun create(
            problem: BakedProblem,
            objective: LinearObjective,
            cancellation: Cancellation = problem.cancellation,
        ): SeparableObjectiveView? {
            if (problem.semantics.isApproximate || problem.numRealVars != 0 ||
                problem.numIntVars > 4096 || objective.intCoefficientValues.size > problem.numIntVars ||
                objective.boolWeightValues.size > minOf(4096, problem.numBoolVars) ||
                objective.realCoefficientValues.size > 4096 || objective.intCoefficientValues.isEmpty()
            ) return null
            cancellation.charge((objective.boolWeightValues.size + objective.intCoefficientValues.size +
                objective.realCoefficientValues.size).toLong())
            if (cancellation() || objective.singleIntObjective() == null) return null
            return try {
                val expanded = Expansion(problem, objective, cancellation).expand() ?: return null
                SeparableObjectiveView(problem, objective, expanded)
            } catch (_: CheckedLongOverflowException) {
                null
            } catch (_: ExpansionStopped) {
                null
            }
        }
    }
}

private class Expansion(
    private val problem: BakedProblem,
    private val source: LinearObjective,
    private val cancellation: Cancellation,
) {
    private val definitions = HashMap<Int, MutableList<Linear>>()
    private val selected = HashMap<Int, Linear?>()
    private val coefficients = HashMap<Int, Long>()
    private val active = HashSet<Int>()
    private val rows = HashSet<Linear>()
    private var constant = source.constant
    private var visits = source.boolWeightValues.size + source.intCoefficientValues.size +
        source.realCoefficientValues.size
    private var changed = false

    fun expand(): LinearObjective? {
        for (factor in problem.factors) {
            visit()
            val row = factor as? Linear ?: continue
            if (row.op != LinearOp.EQ) continue
            val constants = row.integerConstants ?: continue
            for (index in row.vars.indices) {
                visit()
                if (constants.coeff(index) == 1L || constants.coeff(index) == -1L) {
                    definitions.getOrPut(row.vars[index]) { ArrayList() }.add(row)
                }
            }
        }
        for (variable in source.intCoefficientValues.indices) {
            visit()
            val coefficient = source.intCoefficientValues[variable]
            if (coefficient != 0L) {
                selectDefinitions(variable)
                expand(variable, coefficient, 0)
            }
        }
        if (!changed) return null
        visit(2 * problem.numIntVars + source.boolWeightValues.size + source.realCoefficientValues.size)
        val result = LongArray(problem.numIntVars)
        for ((variable, coefficient) in coefficients) result[variable] = coefficient
        return source.copy(intCoefficients = result, constant = constant)
    }

    private fun selectDefinitions(root: Int) {
        val frontier = ArrayDeque<Pair<Int, Int>>()
        val queued = HashSet<Int>()
        frontier.addLast(root to 0)
        queued.add(root)
        while (frontier.isNotEmpty()) {
            visit()
            val (variable, depth) = frontier.removeFirst()
            val definition = definitions[variable]?.firstOrNull {
                visit()
                it !in rows
            }
            selected[variable] = definition
            if (definition == null) continue
            if (depth >= 16) stop()
            rows.add(definition)
            val constants = checkNotNull(definition.integerConstants)
            for (index in definition.vars.indices) {
                visit()
                val partner = definition.vars[index]
                if (partner != variable && constants.coeff(index) != 0L && queued.add(partner)) {
                    frontier.addLast(partner to depth + 1)
                }
            }
        }
    }

    private fun expand(variable: Int, coefficient: Long, depth: Int) {
        visit()
        if (variable !in 0 until problem.numIntVars ||
            !problem.intBounds.hasLower(variable) || !problem.intBounds.hasUpper(variable) ||
            coefficient == Long.MIN_VALUE || !active.add(variable)
        ) stop()
        val definition = selected[variable]
        if (definition == null) {
            val merged = addExact(coefficients[variable] ?: 0L, coefficient)
            if (merged == Long.MIN_VALUE) stop()
            if (merged == 0L) coefficients.remove(variable) else coefficients[variable] = merged
            if (coefficients.size > 256) stop()
        } else {
            if (depth >= 16) stop()
            changed = true
            val constants = checkNotNull(definition.integerConstants)
            visit(definition.vars.size)
            val pivot = definition.vars.indexOf(variable)
            val multiplier = mulExact(coefficient, constants.coeff(pivot))
            constant = addExact(constant, mulExact(multiplier, constants.bound))
            if (constant == Long.MIN_VALUE) stop()
            for (index in definition.vars.indices) {
                visit()
                if (index == pivot) continue
                val term = mulExact(mulExact(multiplier, constants.coeff(index)), -1L)
                if (term != 0L) expand(definition.vars[index], term, depth + 1)
            }
        }
        active.remove(variable)
    }

    private fun visit(units: Int = 1) {
        visits += units
        cancellation.charge(units.toLong())
        if (visits > 32768 || cancellation()) throw ExpansionStopped()
    }

    private fun stop(): Nothing = throw ExpansionStopped()
}

private class ExpansionStopped : RuntimeException()
