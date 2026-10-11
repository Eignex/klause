package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SearchInitializationCancelled
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList
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
    private var reduced: ReducedTrial? = null
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
        if (params.cancellation()) return null
        if (polishing != null) {
            finishPolishing()?.let { return it }
            if (polishing != null) return null
        }
        if (isDone) return null
        val startWork = work
        val start = TimeSource.Monotonic.markNow()
        while (!isDone && !params.cancellation()) {
            if (trial == null) {
                trials++
                val next = accepted.withBool(variables[index], false)
                val simplified = purePins(next)
                val projection = reduce(simplified.first)
                if (projection == null) {
                    if (phaseToken()) return null
                    index++
                    continue
                }
                trial = next
                pure = simplified.second
                reduced = projection
                repair = try {
                    ResumableMinimize(
                        projection.solver, LinearObjective(),
                        BacktrackPresets.satOptimized(params.randomSeed, inprocess = false, cancellation = phaseToken).copy(
                            nativeSat = params.nativeSat, pbLearning = false, nodeBudget = params.nodeBudget,
                            targetPhasing = false, valueSelector = IndomainMin,
                            maxDecisions = maxOf(20_000L, solver.problem.numBoolVars.toLong() * 2L),
                        ),
                        rebindable = true,
                    )
                } catch (cancelled: SearchInitializationCancelled) {
                    completedWork += cancelled.work
                    close()
                    return null
                }
            }
            val nodes = if (sliceNodes < 0L) -1L else (sliceNodes - (work - startWork)).coerceAtLeast(0L)
            val millis = (sliceMillis - start.elapsedNow().inWholeMilliseconds).coerceAtLeast(0L)
            val search = checkNotNull(repair)
            val terminal = search.runSlice(phaseToken, millis, nodes) { trialSample = it.sample }
            if (terminal == null && trialSample == null) return null
            index++
            val completedTrial = checkNotNull(trial)
            trial = null
            val sample = trialSample ?: (terminal as? MinimizeResult.WithSample)?.sample
            trialSample = null
            val lifted = sample?.let { checkNotNull(reduced).lift(it, solver.problem.numBoolVars) }
            retireRepair()
            reduced = null
            if (lifted == null) continue
            models++
            accepted = completedTrial
            polishing = lifted
            polishingVariables = pure + variables
            polishingIndex = 0
            polishingPureCount = pure.size
            finishPolishing()?.let { return it }
            if (polishing != null) return null
        }
        return null
    }

    private class ReducedTrial(
        val solver: BacktrackSolver,
        val pins: Assumptions,
        val variables: IntArray,
    ) {
        fun lift(sample: Sample, sourceSize: Int): Sample {
            val values = BooleanArray(sourceSize)
            pins.forEachBool { variable, value -> values[variable] = value }
            for (i in variables.indices) values[variables[i]] = sample.bools[i]
            return Sample(values, LongArray(0))
        }
    }

    private fun reduce(pins: Assumptions): ReducedTrial? {
        val fixed = pins.bools
        val mapping = IntArray(solver.problem.numBoolVars) { -1 }
        val variables = IntArrayList()
        val residual = mutableListOf<Clause>()
        for (clause in clauses) {
            if (phaseToken()) return null
            literalWork += clause.literals.size
            if (clause.literals.any { literal ->
                    fixed[Lit.variable(literal)]?.let { Lit.evaluate(literal, it) } == true
                }
            ) continue
            val literals = IntArrayList(clause.literals.size)
            for (literal in clause.literals) {
                val variable = Lit.variable(literal)
                if (variable in fixed) continue
                if (mapping[variable] < 0) {
                    mapping[variable] = variables.size
                    variables.add(variable)
                }
                literals.add(Lit.make(mapping[variable], Lit.isPositive(literal)))
            }
            if (literals.size == 0) return null
            residual.add(Clause(literals.toIntArray()))
        }
        val problem = Problem(
            variables.size, 0, emptyArray(), Array<Factor>(residual.size) { residual[it] },
        ).bake(phaseToken)
        if (phaseToken()) return null
        return ReducedTrial(BacktrackSolver(problem, solver.lpSolveContext), pins, variables.toIntArray())
    }

    private fun retireRepair() {
        val search = repair ?: return
        completedWork += search.work
        repair = null
        search.close()
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
        while (polishingIndex < polishingVariables.size) {
            if (params.cancellation()) return null
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
        retireRepair()
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
            val rootPins = (problem.rootDeductions as? PropagationResult.Implied)?.toAssumptions() ?: Assumptions.None
            val scopedParams = params.copy(assumptions = rootPins.mergedWith(params.assumptions))
            if (problem.numIntVars != 0 || problem.numRealVars != 0 ||
                objective.boolWeights.any { it < 0L } || objective.intCoefficients.any { it != 0L }
            ) return null
            val variables = objective.boolWeights.indices.filter { objective.boolWeights[it] > 0L }
                .sortedWith(compareByDescending<Int> { objective.boolWeights[it] }.thenByDescending { it })
            if (variables.isEmpty() || variables.size > 64) return null
            if (variables.any { variable ->
                    variable >= problem.numBoolVars || scopedParams.assumptions.boolValueOrNull(variable) != null
                }
            ) return null
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
