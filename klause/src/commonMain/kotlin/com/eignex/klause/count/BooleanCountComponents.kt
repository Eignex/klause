package com.eignex.klause.count

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_TWO
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.IntDisjointSet
import com.eignex.klause.util.isZero
import com.eignex.klause.util.times

internal class BooleanCountComponents private constructor(
    private val source: BakedProblem,
    private val components: List<Component>,
    private val freeMass: BigInt,
) {
    private class Component(val size: Int, val clauses: List<Clause>, val projection: IntArray)

    fun run(config: ExactCountConfig, budget: CountCheckBudget): Sequence<ExactCountInterval> = sequence {
        val suffix = Array(components.size + 1) { BIG_ONE }
        for (i in components.indices.reversed()) {
            suffix[i] = booleanMass(components[i].projection.size) * suffix[i + 1]
        }
        var completed = ExactCountInterval(freeMass, freeMass)
        yield(completed * ExactCountInterval(BIG_ZERO, suffix[0]))
        for ((index, component) in components.withIndex()) {
            if (!budget.available) break
            val problem = Problem(
                numBoolVars = component.size,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = component.clauses,
                settings = source.settings,
                semantics = source.semantics,
            ).bake()
            val localConfig = config.copy(samplingSet = component.projection, intSamplingSet = null)
            var local = ExactCountInterval(BIG_ZERO, booleanMass(component.projection.size))
            for (interval in AnytimeCounter.traverse(problem, localConfig, budget)) {
                local = interval
                val product = completed * interval
                // Unvisited components are existentially unknown, even with an empty projection.
                val remaining = if (index == components.lastIndex) {
                    ExactCountInterval(BIG_ONE, BIG_ONE)
                } else {
                    ExactCountInterval(BIG_ZERO, suffix[index + 1])
                }
                val combined = product * remaining
                yield(combined)
                if (combined.upper.isZero()) return@sequence
            }
            completed *= local
        }
    }

    companion object {
        fun partition(problem: BakedProblem, config: ExactCountConfig): BooleanCountComponents? {
            val providers = problem.projectionProviders
            if (problem.numFactors < 2 || problem.numIntVars != 0 || problem.numRealVars != 0 ||
                (providers != null && providers !== FactorProjectionProviders.Default) ||
                problem.hasSymmetryBreaking || problem.rootDeductions is PropagationResult.Unsat
            ) return null
            val projection = config.samplingSet
                ?: if (config.intSamplingSet == null) problem.allBoolVars() else intArrayOf()
            if (projection.isEmpty() || config.intSamplingSet?.isNotEmpty() == true ||
                projection.toSet().size != projection.size ||
                projection.any { it !in 0 until problem.numBoolVars }
            ) return null
            if (problem.factors.any { it !is Clause || !it.allLiteralsBool(problem.numBoolVars) }) return null

            val fixed = arrayOfNulls<Boolean>(problem.numBoolVars)
            (problem.rootDeductions as? PropagationResult.Implied)?.let { deductions ->
                for (i in deductions.boolKeys.indices) fixed[deductions.boolKeys[i]] = deductions.boolValues[i]
            }
            val sets = IntDisjointSet(problem.numBoolVars)
            val active = BooleanArray(problem.numBoolVars)
            val residual = ArrayList<IntArray>()
            for (factor in problem.factors) {
                val clause = factor as Clause
                // Inspect the whole clause before joining: a later true literal discharges every edge.
                if (clause.tautological || clause.literals.any { lit ->
                        fixed[Lit.variable(lit)]?.let { Lit.evaluate(lit, it) } == true
                    }
                ) continue
                val literals = clause.literals.filter { fixed[Lit.variable(it)] == null }.toIntArray()
                if (literals.isEmpty()) return null
                val first = Lit.variable(literals[0])
                for (literal in literals) {
                    val variable = Lit.variable(literal)
                    active[variable] = true
                    sets.union(first, variable)
                }
                residual.add(literals)
            }
            val groups = sets.groups().filter { active[it[0]] }
            if (groups.size < 2) return null

            val selected = BooleanArray(problem.numBoolVars)
            for (variable in projection) selected[variable] = true
            val localIds = IntArray(problem.numBoolVars)
            val groupIds = IntArray(problem.numBoolVars)
            val projections = ArrayList<IntArray>()
            for ((index, group) in groups.withIndex()) {
                for ((localId, variable) in group.withIndex()) {
                    localIds[variable] = localId
                    groupIds[variable] = index
                }
                projections.add(group.filter { selected[it] }.map { localIds[it] }.toIntArray())
            }
            val clauses = List(groups.size) { ArrayList<Clause>() }
            for (literals in residual) {
                clauses[groupIds[Lit.variable(literals[0])]].add(Clause(IntArray(literals.size) {
                    val literal = literals[it]
                    Lit.make(localIds[Lit.variable(literal)], Lit.isPositive(literal))
                }))
            }
            val components = groups.indices.map { Component(groups[it].size, clauses[it], projections[it]) }
            val free = projection.count { fixed[it] == null && !active[it] }
            return BooleanCountComponents(problem, components, booleanMass(free))
        }

        private fun booleanMass(size: Int): BigInt {
            var mass = BIG_ONE
            repeat(size) { mass *= BIG_TWO }
            return mass
        }
    }
}
