package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SearchInitializationCancelled
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.time.TimeSource

internal class MonotoneClausePrimal private constructor(
    private val solver: BacktrackSolver,
    private val objective: LinearObjective,
    private val params: BacktrackParams,
    private val lifetime: Cancellation,
    private val variables: List<Int>,
    private val occurrences: Array<List<Clause>>,
    private val clauses: List<Clause>,
    private var literalWork: Long,
) : AutoCloseable {
    private var accepted = variables.fold(params.assumptions) { pins, variable -> pins.withBool(variable, true) }
    private var index = 0
    private var repair: ResumableMinimize? = null
    private var trial: Assumptions? = null
    private var pure: List<Int> = emptyList()
    private var trialSample: Sample? = null
    private var bestValue = Double.POSITIVE_INFINITY
    private var closed = false

    val work: Long get() = (repair?.work ?: 0L) + literalWork / PROPAGATION_WORK_PER_NODE
    val isDone: Boolean get() = closed || index == variables.size || lifetime() || work >= 100_000L

    fun advance(sliceMillis: Long = 2_500L, sliceNodes: Long = -1L): Sample? {
        if (isDone || params.cancellation()) return null
        val startWork = work
        val start = TimeSource.Monotonic.markNow()
        val search = repair ?: try {
            ResumableMinimize(
                solver, LinearObjective(),
                BacktrackPresets.conflictDriven(params.randomSeed, cancellation = lifetime).copy(
                    assumptions = params.assumptions, nativeSat = params.nativeSat, phaseSaving = false,
                ),
                rebindable = true,
            ).also { repair = it }
        } catch (cancelled: SearchInitializationCancelled) {
            literalWork += cancelled.work * PROPAGATION_WORK_PER_NODE
            close()
            return null
        }
        while (!isDone && !params.cancellation()) {
            if (trial == null) {
                val next = accepted.withBool(variables[index], false)
                val simplified = purePins(next)
                trial = next
                pure = simplified.second
                search.rebind(simplified.first, 20_000L)
            }
            val nodes = if (sliceNodes < 0L) -1L else (sliceNodes - (work - startWork)).coerceAtLeast(0L)
            val millis = (sliceMillis - start.elapsedNow().inWholeMilliseconds).coerceAtLeast(0L)
            val terminal = search.runSlice(lifetime, millis, nodes) { trialSample = it.sample }
            if (terminal == null && trialSample == null) return null
            index++
            val completedTrial = checkNotNull(trial)
            trial = null
            val sample = trialSample
            trialSample = null
            if (sample == null) continue
            accepted = completedTrial
            val polished = polish(sample, pure)
            val value = objective.evaluate(polished)
            if (value < bestValue) {
                bestValue = value
                return polished
            }
        }
        return null
    }

    private fun purePins(trial: Assumptions): Pair<Assumptions, List<Int>> {
        val pins = trial.bools.toMutableMap()
        val polarity = IntArray(solver.problem.numBoolVars)
        for (clause in clauses) {
            if (params.cancellation() || lifetime()) break
            literalWork += clause.literals.size
            if (clause.literals.any { literal ->
                    pins[Lit.variable(literal)]?.let { Lit.evaluate(literal, it) } == true
                }
            ) continue
            for (literal in clause.literals) {
                val variable = Lit.variable(literal)
                if (variable !in pins) polarity[variable] = polarity[variable] or
                    if (Lit.isPositive(literal)) 1 else 2
            }
        }
        val pure = polarity.indices.filter { polarity[it] == 1 || polarity[it] == 2 }
        for (variable in pure) pins[variable] = polarity[variable] == 1
        return trial.mergedWith(Assumptions(bools = pins)) to pure
    }

    private fun polish(candidate: Sample, pure: List<Int>): Sample {
        val values = candidate.bools.copyOf()
        for (variable in pure + variables) {
            if (params.cancellation() || lifetime()) break
            if ((variable in pure || values[variable]) && occurrences[variable].all { clause ->
                    clause.literals.any { literal ->
                        literalWork++
                        Lit.variable(literal) != variable && Lit.evaluate(literal, values[Lit.variable(literal)])
                    }
                }
            ) values[variable] = !values[variable]
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
            val clauses = mutableListOf<Clause>()
            for (factor in problem.factors) {
                if (params.cancellation()) return null
                val clause = factor as? Clause ?: return null
                clauses.add(clause)
                for (literal in clause.literals) {
                    literalWork++
                    val variable = Lit.variable(literal)
                    occurrences[variable].add(clause)
                    if (!Lit.isPositive(literal) &&
                        (objective.boolWeights.getOrNull(Lit.variable(literal)) ?: 0L) > 0L
                    ) return null
                }
            }
            return MonotoneClausePrimal(
                solver, objective, params, lifetime, variables,
                Array(occurrences.size) { occurrences[it] }, clauses, literalWork,
            )
        }
    }
}
