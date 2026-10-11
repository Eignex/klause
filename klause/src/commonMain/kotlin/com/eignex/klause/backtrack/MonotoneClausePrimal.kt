package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SearchInitializationCancelled
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import kotlin.time.Duration.Companion.milliseconds
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
    private val activeBudgetMillis: Long,
    private var activeMillis: Long,
) : AutoCloseable {
    private var accepted = variables.fold(params.assumptions) { pins, variable -> pins.withBool(variable, true) }
    private var index = 0
    private var repair: ResumableMinimize? = null
    private var completedWork = 0L
    private var trial: Assumptions? = null
    private var pure: List<Int> = emptyList()
    private var trialSample: Sample? = null
    private var bestValue = Double.POSITIVE_INFINITY
    private var activeStart: TimeSource.Monotonic.ValueTimeMark? = null
    private val phaseToken = object : Cancellation {
        override fun isCancelled(): Boolean = lifetime() ||
            activeMillis + (activeStart?.elapsedNow()?.inWholeMilliseconds ?: 0L) >= activeBudgetMillis
        override fun deadline() = lifetime.deadline()
    }
    private var closed = false
    private var polishing: Sample? = null
    private var polishingVariables: List<Int> = emptyList()
    private var polishingIndex = 0
    private var polishingPureCount = 0

    var trials: Long = 0L
        private set
    var models: Long = 0L
        private set
    var proposals: Long = 0L
        private set

    var rejected: Long = 0L
        private set

    var infeasible: Long = 0L
        private set

    var incomplete: Long = 0L
        private set

    val work: Long get() = completedWork + (repair?.work ?: 0L) + literalWork / PROPAGATION_WORK_PER_NODE
    val isDone: Boolean
        get() = closed || (polishing == null && (index == variables.size || phaseToken()))

    fun advance(sliceMillis: Long = 2_500L, sliceNodes: Long = -1L): Sample? {
        val start = TimeSource.Monotonic.markNow()
        activeStart = start
        try {
            return advanceBody(sliceMillis, sliceNodes)
        } finally {
            activeMillis += start.elapsedNow().inWholeMilliseconds
            activeStart = null
        }
    }

    private fun advanceBody(sliceMillis: Long, sliceNodes: Long): Sample? {
        if (lifetime()) return null
        if (polishing != null) {
            finishPolishing()?.let { return it }
            if (polishing != null) return null
        }
        if (params.cancellation() || isDone) return null
        val startWork = work
        val start = TimeSource.Monotonic.markNow()
        val search = repair ?: try {
            ResumableMinimize(
                solver, LinearObjective(),
                BacktrackPresets.satOptimized(
                    params.randomSeed, inprocess = false, cancellation = phaseToken,
                ).copy(
                    assumptions = params.assumptions, nativeSat = params.nativeSat,
                    pbLearning = false, nodeBudget = params.nodeBudget, targetPhasing = false,
                    valueSelector = IndomainMin,
                ),
                rebindable = true,
            ).also { repair = it }
        } catch (cancelled: SearchInitializationCancelled) {
            completedWork += cancelled.work
            close()
            return null
        }
        while (!isDone && !params.cancellation()) {
            if (trial == null) {
                trials++
                val next = accepted.withBool(variables[index], false)
                val simplified = purePins(next)
                trial = next
                pure = simplified.second
                search.rebind(simplified.first, Long.MAX_VALUE)
            }
            val nodes = if (sliceNodes < 0L) -1L else (sliceNodes - (work - startWork)).coerceAtLeast(0L)
            val millis = (sliceMillis - start.elapsedNow().inWholeMilliseconds).coerceAtLeast(0L)
            val terminal = search.runSlice(phaseToken, millis, nodes) { trialSample = it.sample }
            if (terminal == null && trialSample == null) return null
            index++
            val completedTrial = checkNotNull(trial)
            trial = null
            val sample = trialSample ?: (terminal as? MinimizeResult.WithSample)?.sample
            trialSample = null
            if (sample == null) {
                if (terminal is MinimizeResult.Infeasible) infeasible++ else incomplete++
                continue
            }
            models++
            accepted = completedTrial
            polishing = Sample(sample.bools.copyOf(), sample.ints)
            polishingVariables = pure + variables
            polishingIndex = 0
            polishingPureCount = pure.size
            finishPolishing()?.let { return it }
            if (polishing != null) return null
        }
        return null
    }

    private fun purePins(trial: Assumptions): Pair<Assumptions, List<Int>> {
        val pins = trial.bools.toMutableMap()
        val pure = mutableListOf<Int>()
        while (!params.cancellation() && !phaseToken()) {
            val polarity = residualPolarity(pins) ?: break
            val next = polarity.indices.filter { polarity[it] == 1 || polarity[it] == 2 }
            if (next.isEmpty()) break
            for (variable in next) pins[variable] = polarity[variable] == 1
            pure.addAll(next)
        }
        return trial.mergedWith(Assumptions(bools = pins)) to pure
    }

    private fun residualPolarity(pins: Map<Int, Boolean>): IntArray? {
        val polarity = IntArray(solver.problem.numBoolVars)
        for (clause in clauses) {
            if (params.cancellation() || phaseToken()) return null
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
        return polarity
    }

    private fun finishPolishing(): Sample? {
        val candidate = checkNotNull(polishing)
        val values = candidate.bools
        val budget = lifetime or Cancellation.after(100.milliseconds)
        while (polishingIndex < polishingVariables.size) {
            if (budget()) return null
            val pureVariable = polishingIndex < polishingPureCount
            val variable = polishingVariables[polishingIndex++]
            if ((pureVariable || values[variable]) && occurrences[variable].all { clause ->
                    clause.literals.any { literal ->
                        literalWork++
                        Lit.variable(literal) != variable && Lit.evaluate(literal, values[Lit.variable(literal)])
                    }
                }
            ) values[variable] = !values[variable]
        }
        polishing = null
        val value = objective.evaluate(candidate)
        return if (value < bestValue) {
            bestValue = value
            proposals++
            candidate
        } else {
            null
        }
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
            activeBudgetMillis: Long = 5_000L,
        ): MonotoneClausePrimal? {
            val start = TimeSource.Monotonic.markNow()
            val problem = solver.problem
            if (problem.numIntVars != 0 || problem.numRealVars != 0 ||
                objective.boolWeights.any { it < 0L } || objective.intCoefficients.any { it != 0L }
            ) return null
            val rootPins = (problem.rootDeductions as? PropagationResult.Implied)?.toAssumptions() ?: Assumptions.None
            val scopedParams = params.copy(assumptions = rootPins.mergedWith(params.assumptions))
            val costVariables = objective.boolWeights.indices.filter { objective.boolWeights[it] > 0L }
                .sortedWith(compareByDescending<Int> { objective.boolWeights[it] }.thenByDescending { it })
            if (costVariables.isEmpty() || costVariables.size > 64) return null
            if (costVariables.any { variable ->
                    variable >= problem.numBoolVars || params.assumptions.boolValueOrNull(variable) != null
                }
            ) return null
            val variables = costVariables.filter { rootPins.boolValueOrNull(it) == null }
            if (variables.isEmpty()) return null
            val occurrences = Array(problem.numBoolVars) { mutableListOf<Clause>() }
            var literalWork = 0L
            val clauses = mutableListOf<Clause>()
            for (factor in problem.factors) {
                if (lifetime() || start.elapsedNow().inWholeMilliseconds >= activeBudgetMillis) return null
                val clause = factor as? Clause ?: return null
                clauses.add(clause)
                for (literal in clause.literals) {
                    literalWork++
                    val variable = Lit.variable(literal)
                    if (variable >= problem.numBoolVars) return null
                    occurrences[variable].add(clause)
                    if (!Lit.isPositive(literal) &&
                        (objective.boolWeights.getOrNull(Lit.variable(literal)) ?: 0L) > 0L
                    ) return null
                }
            }
            return MonotoneClausePrimal(
                solver, objective, scopedParams, lifetime, variables,
                Array(occurrences.size) { occurrences[it] }, clauses, literalWork,
                activeBudgetMillis, start.elapsedNow().inWholeMilliseconds,
            )
        }
    }
}
