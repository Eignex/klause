package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.RepairSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.time.Duration.Companion.milliseconds

internal class MonotoneClausePrimal private constructor(
    private val solver: BacktrackSolver,
    private val objective: LinearObjective,
    private val params: BacktrackParams,
    private val lifetime: Cancellation,
    private val variables: List<Int>,
    private val occurrences: Array<List<Clause>>,
    private var literalWork: Long,
) : AutoCloseable {
    private var accepted = variables.fold(params.assumptions) { pins, variable -> pins.withBool(variable, true) }
    private var index = 0
    private var repair: RepairSearch? = null
    private var bestValue = Double.POSITIVE_INFINITY
    private var closed = false

    val work: Long get() = (repair?.work ?: 0L) + literalWork / PROPAGATION_WORK_PER_NODE
    val isDone: Boolean get() = closed || index == variables.size || lifetime() || work >= 100_000L

    fun advance(): Sample? {
        if (isDone || params.cancellation()) return null
        val search = repair ?: solver.openRepair(
            LinearObjective(),
            BacktrackPresets.conflictDriven(params.randomSeed, cancellation = params.cancellation or lifetime).copy(
                assumptions = params.assumptions,
                nativeSat = params.nativeSat,
                phaseSaving = false,
            ),
        ).also { repair = it }
        while (!isDone && !params.cancellation()) {
            val variable = variables[index]
            val trial = accepted.withBool(variable, false)
            val candidate = search.repair(
                trial,
                20_000L,
                Double.POSITIVE_INFINITY,
                lifetime or Cancellation.after(250.milliseconds),
            )
            // A slice ending says nothing about this trial; retry it when the caller re-arms the slice.
            if (candidate == null && params.cancellation() && !lifetime()) return null
            index++
            if (candidate != null) {
                accepted = trial
                val polished = polish(candidate)
                val value = objective.evaluate(polished)
                if (value < bestValue) {
                    bestValue = value
                    return polished
                }
            }
        }
        return null
    }

    private fun polish(candidate: Sample): Sample {
        val values = candidate.bools.copyOf()
        for (variable in variables) {
            if (params.cancellation() || lifetime()) break
            if (values[variable] && occurrences[variable].all { clause ->
                    clause.literals.any { literal ->
                        literalWork++
                        Lit.variable(literal) != variable && Lit.evaluate(literal, values[Lit.variable(literal)])
                    }
                }
            ) values[variable] = false
        }
        return Sample(values, candidate.ints)
    }

    override fun close() {
        if (closed) return
        closed = true
        repair?.close()
    }

    companion object {
        fun create(
            solver: BacktrackSolver,
            objective: LinearObjective,
            params: BacktrackParams,
            lifetime: Cancellation,
        ): MonotoneClausePrimal? {
            val problem = solver.problem
            if (problem.numIntVars != 0 || problem.numRealVars != 0 ||
                objective.boolWeights.any { it < 0L } || objective.intCoefficients.any { it != 0L }
            ) return null
            val variables = objective.boolWeights.indices.filter { objective.boolWeights[it] > 0L }
                .sortedWith(compareByDescending<Int> { objective.boolWeights[it] }.thenByDescending { it })
            if (variables.isEmpty() || variables.size > 64) return null
            if (variables.any { variable ->
                    variable >= problem.numBoolVars || params.assumptions.boolValueOrNull(variable) != null
                }
            ) return null
            val occurrences = Array(problem.numBoolVars) { mutableListOf<Clause>() }
            var literalWork = 0L
            for (factor in problem.factors) {
                if (params.cancellation()) return null
                val clause = factor as? Clause ?: return null
                for (literal in clause.literals) {
                    literalWork++
                    val variable = Lit.variable(literal)
                    if ((objective.boolWeights.getOrNull(variable) ?: 0L) > 0L) occurrences[variable].add(clause)
                    if (!Lit.isPositive(literal) &&
                        (objective.boolWeights.getOrNull(Lit.variable(literal)) ?: 0L) > 0L
                    ) return null
                }
            }
            return MonotoneClausePrimal(
                solver, objective, params, lifetime, variables,
                Array(occurrences.size) { occurrences[it] }, literalWork,
            )
        }
    }
}
