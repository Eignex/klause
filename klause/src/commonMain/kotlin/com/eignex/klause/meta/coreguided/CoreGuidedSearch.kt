package com.eignex.klause.meta.coreguided

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.PROPAGATION_WORK_PER_NODE
import com.eignex.klause.backtrack.composedFixpoint
import com.eignex.klause.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.ReseedGuard
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.ResumableSolve
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SearchInitializationCancelled
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.lifetimeCancellation
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.CoreGuidedStats
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.result.UnsoundnessException
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.abs
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
import com.eignex.klause.util.toDouble
import kotlin.math.nextDown
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

internal class CoreGuidedSearch(
    private val base: BakedProblem,
    private val objective: LinearObjective,
    private val params: BacktrackParams,
    private val stratify: Boolean = false,
) : ResumableSearch,
    ReseedGuard {
    private val lifetime = params.cancellation.lifetimeCancellation()
    private val factors = ArrayList<Factor>().apply {
        addAll(base.factors)
        (base.rootDeductions as? PropagationResult.Implied)?.forEachBool { id, value ->
            add(Clause(intArrayOf(Lit.make(id, value))))
        }
    }
    private val weights = LinkedHashMap<Int, BigInt>()
    private var nextBool = base.numBoolVars
    private var initialIndex = 0
    private var lower = objective.boolWeightValues.fold(bigIntOf(objective.constant)) { value, weight ->
        if (weight < 0L) value + bigIntOf(weight) else value
    }
    private var threshold: BigInt? = null
    private var query: ResumableSolve? = null
    private var querySofts = IntArray(0)
    private var completedStats = SolveStats.EMPTY
    private var completedWork = 0L
    private var completedPropagation = 0L
    private var workDebt = 0L
    private var best: Sample? = null
    private var bestValue: BigFraction? = null
    private var terminal: MinimizeResult? = null
    private var closed = false
    private var core: IntArray? = null
    private var coreWeight = BIG_ZERO
    private var coreThreshold = 2
    private var completedCores = 0L
    private var largestCore = 0

    init {
        require(eligible(base, objective)) { "core-guided search needs a finite Boolean objective" }
    }

    internal val lowerBound: BigFraction get() = BigFraction.of(lower, BIG_ONE)
    override val isDone: Boolean get() = terminal != null
    override val stats: SolveStats get() = completedStats.mergedWith(query?.stats ?: SolveStats.EMPTY).copy(
        coreGuided = CoreGuidedStats(completedCores, largestCore, lower),
    )
    override val work: Long get() {
        val live = query?.stats?.search?.propagationWork?.sum?.toLong() ?: 0L
        val carried = (completedPropagation % PROPAGATION_WORK_PER_NODE + live % PROPAGATION_WORK_PER_NODE) /
            PROPAGATION_WORK_PER_NODE
        return completedWork + (query?.work ?: 0L) + carried
    }
    override val canReseed: Boolean get() = false
    override val preparationPending: Boolean get() = initialIndex < objective.boolWeightValues.size || core != null

    override fun runSlice(
        global: Cancellation,
        sliceMillis: Long,
        sliceNodes: Long,
        onIncumbent: (MinimizeResult.WithSample) -> Unit,
    ): MinimizeResult? {
        terminal?.let { return it }
        check(!closed) { "search is closed" }
        val token = lifetime or global
        if (token()) return null
        if (base.rootDeductions is PropagationResult.Unsat) {
            return finish(MinimizeResult.Infeasible(stats = stats))
        }
        val deadline = TimeSource.Monotonic.markNow() + sliceMillis.milliseconds
        val start = work
        val repaid = if (sliceNodes < 0L) 0L else minOf(workDebt, sliceNodes)
        workDebt -= repaid
        val allowance = if (sliceNodes < 0L) -1L else sliceNodes - repaid
        if (allowance == 0L) return null
        while (!token() && !deadline.hasPassedNow()) {
            if (initialIndex < objective.boolWeightValues.size) {
                prepareObjective()
                if (preparationPending) continue
                threshold = if (stratify) maximumWeight() else BIG_ONE
            }
            if (core != null) {
                extendCore()
                if (core != null) continue
                publishLowerBound()
            }
            if (token() || deadline.hasPassedNow()) return null
            val incumbent = best
            if (incumbent != null && bestValue == lowerBound) {
                return finish(MinimizeResult.Optimal(incumbent, requireNotNull(bestValue).toDouble(), stats))
            }
            if (allowance >= 0L && work - start >= allowance) return null
            val beforeQuery = work
            val handle = query ?: try {
                openQuery(token).also { query = it }
            } catch (_: SearchInitializationCancelled) {
                return null
            }
            val remaining = if (allowance < 0L) -1L else (allowance - (beforeQuery - start)).coerceAtLeast(0L)
            val millis = (deadline - TimeSource.Monotonic.markNow()).inWholeMilliseconds.coerceAtLeast(0L)
            val result = handle.runSlice(token, millis, remaining)
            if (result == null) return null
            if (allowance >= 0L) workDebt = (work - start - allowance).coerceAtLeast(0L)
            finishQuery()
            when (result) {
                is SolveResult.Unknown -> return finish(stopped(result.reason))

                is SolveResult.Sat -> {
                    val sample = Sample(
                        BooleanArray(base.numBoolVars) { result.assignment.boolValue(it) },
                        result.assignment.ints,
                    )
                    when (val verified = composedFixpoint(base, Candidate(sample, Unit), token, params.assumptions)) {
                        is Verification.Rejected -> throw UnsoundnessException(verified.reason)
                        is Verification.Indeterminate -> return null
                        is Verification.Accepted -> Unit
                    }
                    val value = objective.evaluateExact(sample)
                    if (bestValue == null || value < requireNotNull(bestValue)) {
                        best = sample
                        bestValue = value
                        onIncumbent(
                            MinimizeResult.BestFound(sample, value.toDouble(), TerminationReason.BudgetExhausted),
                        )
                    }
                    if (value == lowerBound) return finish(MinimizeResult.Optimal(sample, value.toDouble(), stats))
                    val current = requireNotNull(threshold)
                    threshold = maximumWeight(current)
                    check(threshold != null) { "core objective identity differs from source objective" }
                }

                is SolveResult.Unsat -> {
                    val assumptions = result.assumptionCore
                    val selected = if (assumptions == null) {
                        querySofts
                    } else {
                        querySofts.filter {
                        assumptions.boolValueOrNull(it) == false
                    }.toIntArray()
                    }
                    if (selected.isEmpty()) return finish(MinimizeResult.Infeasible(stats = stats))
                    coreWeight = selected.map { requireNotNull(weights[it]) }.reduce { a, b -> if (a < b) a else b }
                    core = selected
                    coreThreshold = 2
                    // A refuted core implies at least one violation. Its remaining count is represented
                    // exactly by the higher threshold bits, including when a prior threshold is re-cored.
                    factors.add(Clause(IntArray(selected.size) { Lit.make(selected[it], true) }))
                }
            }
        }
        return null
    }

    private fun prepareObjective() {
        val end = minOf(objective.boolWeightValues.size, initialIndex + 256)
        while (initialIndex < end) {
            val id = initialIndex++
            val weight = objective.boolWeightValues[id]
            if (weight == 0L) continue
            val exact = bigIntOf(weight)
            val preferred = Lit.make(id, weight < 0L)
            val selector = nextBool++
            factors.add(Oll.relaxerClause(preferred, selector))
            factors.add(Clause(intArrayOf(Lit.negate(preferred), Lit.make(selector, false))))
            weights[selector] = exact.abs()
        }
    }

    private fun extendCore() {
        val selected = requireNotNull(core)
        val literals = IntArray(selected.size) { Lit.make(selected[it], true) }
        // One threshold per checkpoint keeps a large core's encoding resumable.
        if (coreThreshold <= selected.size) {
            val aux = nextBool++
            factors.add(ReifiedCardinality(aux, literals, min = coreThreshold++, max = selected.size))
            weights[aux] = coreWeight
            return
        }
        for (id in selected) {
            val residual = requireNotNull(weights[id]) - coreWeight
            if (residual == BIG_ZERO) weights.remove(id) else weights[id] = residual
        }
        lower += coreWeight
        completedCores++
        largestCore = maxOf(largestCore, selected.size)
        val current = threshold ?: coreWeight
        val maximum = maximumWeight() ?: coreWeight
        threshold = if (current < maximum) current else maximum
        core = null
    }

    private fun maximumWeight(below: BigInt? = null): BigInt? {
        var maximum: BigInt? = null
        for (weight in weights.values) {
            if (below != null && weight >= below) continue
            if (maximum == null || weight > maximum) maximum = weight
        }
        return maximum
    }

    private fun openQuery(token: Cancellation): ResumableSolve {
        querySofts = weights.filterValues { it >= (threshold ?: BIG_ZERO) }.keys.toIntArray()
        val softPins = Assumptions(bools = querySofts.associateWith { false })
        val problem = Problem(
            settings = base.settings,
            numBoolVars = nextBool,
            numIntVars = base.numIntVars,
            intDomains = base.rootIntDomains(),
            factors = factors,
            modelBounds = base.intBounds,
        ).bake(token)
        return try {
            BacktrackSolver(problem).resumableSolve(
                params.copy(
                assumptions = params.assumptions.mergedWith(softPins),
                cancellation = token,
                maxDecisions = (
                    minOf(params.maxDecisions, params.maxInstructions ?: Long.MAX_VALUE) -
                    completedStats.search.nodes.sum.toLong()
                ).coerceAtLeast(0L),
                clauseExchange = null,
                cutExchange = null,
            )
            )
        } catch (failure: SearchInitializationCancelled) {
            completedStats = completedStats.mergedWith(failure.stats)
            completedWork += failure.work
            throw failure
        }
    }

    private fun publishLowerBound() {
        var value = lower.toDouble()
        if (!value.isFinite()) return
        if (requireNotNull(BigFraction.ofDouble(value)) > lowerBound) value = value.nextDown()
        params.objectiveLowerBoundSink?.invoke(value)
    }

    private fun finishQuery() {
        val handle = query ?: return
        handle.close()
        completedStats = completedStats.mergedWith(handle.stats)
        val visits = handle.stats.search.propagationWork.sum.toLong()
        val carried = (completedPropagation % PROPAGATION_WORK_PER_NODE + visits % PROPAGATION_WORK_PER_NODE) /
            PROPAGATION_WORK_PER_NODE
        completedPropagation += visits
        completedWork += handle.work + carried
        query = null
    }

    private fun stopped(reason: TerminationReason): MinimizeResult = best?.let {
        MinimizeResult.BestFound(it, requireNotNull(bestValue).toDouble(), reason, stats)
    } ?: MinimizeResult.Unknown(reason, stats)

    private fun finish(result: MinimizeResult): MinimizeResult {
        terminal = result
        close()
        return result
    }

    override fun close() {
        if (closed) return
        finishQuery()
        closed = true
    }

    companion object {
        fun scalarProofs(objective: LinearObjective): Boolean {
            val magnitude = objective.boolWeightValues.fold(bigIntOf(objective.constant).abs()) { value, weight ->
                value + bigIntOf(weight).abs()
            }
            return magnitude <= bigIntOf(9_007_199_254_740_992L)
        }

        fun eligible(problem: BakedProblem, objective: LinearObjective): Boolean =
            problem.numRealVars == 0 && objective.boolWeightValues.size <= problem.numBoolVars &&
                objective.intCoefficientValues.all { it == 0L } &&
                objective.realCoefficientValues.all { it == 0.0 }
    }
}
