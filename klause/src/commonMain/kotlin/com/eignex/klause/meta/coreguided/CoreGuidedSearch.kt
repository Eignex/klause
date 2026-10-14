package com.eignex.klause.meta.coreguided

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.PROPAGATION_WORK_PER_NODE
import com.eignex.klause.backtrack.ResumableSatisfaction
import com.eignex.klause.backtrack.composedFixpoint
import com.eignex.klause.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.RootWorkMeter
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.FactorProjectionProviders
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
import com.eignex.klause.solver.withLifetime
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
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal class CoreGuidedSearch(
    private val base: BakedProblem,
    private val objective: LinearObjective,
    private val params: BacktrackParams,
    private val stratify: Boolean = false,
    private val maximumWork: Long? = null,
    private val maximumMillis: Long? = null,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : ResumableSearch,
    ReseedGuard {
    private val opened = timeSource.markNow()
    private var activeTime = Duration.ZERO
    private var activeSlice: TimeMark? = null
    private val lifetime = params.cancellation.lifetimeCancellation()
    private val factors = ArrayList<Factor>().apply {
        addAll(base.factors)
        (base.rootDeductions as? PropagationResult.Implied)?.forEachBool { id, value ->
            add(Clause(intArrayOf(Lit.make(id, value))))
        }
    }
    private val weights = LinkedHashMap<Int, BigInt>()
    private val tails = HashMap<Int, ThresholdTail>()
    private var nextBool = base.numBoolVars
    private var initialIndex = 0
    private var lower = objective.boolWeightValues.fold(bigIntOf(objective.constant)) { value, weight ->
        if (weight < 0L) value + bigIntOf(weight) else value
    }
    private var threshold: BigInt? = null
    private var query: ResumableSolve? = null
    private var queryModel: BakedProblem? = null
    private var preparationVisits = factors.size.toLong() + objective.boolWeightValues.size
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
    private var coreCursor = 0
    private var coreLiterals = IntArray(0)
    private var coreEncoded = false
    private var completedCores = 0L
    private var largestCore = 0

    private class ThresholdTail(val literals: IntArray, val minimum: Int, val weight: BigInt)

    init {
        require(maximumWork == null || maximumWork >= 0L) { "core work limit must be nonnegative" }
        require(maximumMillis == null || maximumMillis >= 0L) { "core time limit must be nonnegative" }
        require(eligible(base, objective)) { "core-guided search needs a finite Boolean objective" }
        activeTime = opened.elapsedNow()
    }

    internal val lowerBound: BigFraction get() = BigFraction.of(lower, BIG_ONE)
    override val isDone: Boolean get() = terminal != null
    override val stats: SolveStats get() = completedStats.mergedWith(query?.stats ?: SolveStats.EMPTY).copy(
        coreGuided = CoreGuidedStats(completedCores, largestCore, lower, maximumWork, maximumMillis),
    )
    override val work: Long get() {
        val live = query?.stats?.search?.propagationWork?.sum?.toLong() ?: 0L
        val carried = (completedPropagation % PROPAGATION_WORK_PER_NODE + live % PROPAGATION_WORK_PER_NODE +
            preparationVisits % PROPAGATION_WORK_PER_NODE) / PROPAGATION_WORK_PER_NODE
        return addWork(addWork(completedWork, query?.work ?: 0L),
            addWork(preparationVisits / PROPAGATION_WORK_PER_NODE, carried))
    }
    private val allowanceExhausted: Boolean get() = maximumWork?.let { work >= it } == true ||
        maximumMillis?.let { activeTime + (activeSlice?.elapsedNow() ?: Duration.ZERO) >= it.milliseconds } == true
    override val initialWork: Long = work
    override val canReseed: Boolean get() = false
    override val preparationPending: Boolean
        get() = initialIndex < objective.boolWeightValues.size || core != null ||
            (queryModel != null && query == null)

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
        if (allowanceExhausted) return finish(stopped(TerminationReason.BudgetExhausted))
        val remainingMillis = maximumMillis?.let {
            (it.milliseconds - activeTime).inWholeMilliseconds.coerceAtLeast(0L)
        } ?: sliceMillis
        val deadline = TimeSource.Monotonic.markNow() + minOf(sliceMillis, remainingMillis).milliseconds
        val start = work
        val repaid = if (sliceNodes < 0L) 0L else minOf(workDebt, sliceNodes)
        workDebt -= repaid
        val offered = if (sliceNodes < 0L) -1L else sliceNodes - repaid
        val remainingWork = maximumWork?.let { (it - work).coerceAtLeast(0L) }
        val allowance = if (remainingWork == null) offered else if (offered < 0L) remainingWork
        else minOf(offered, remainingWork)
        if (allowance == 0L) return null
        val preparation = object : Cancellation {
            private val meter = object : RootWorkMeter {
                override fun construction(entries: Long) = charge(entries)
                override fun propagation(units: Long) = charge(units)
                override fun charge(units: Long) = recordPreparation(units)
            }
            override fun isCancelled(): Boolean = token() || deadline.hasPassedNow() || allowanceExhausted ||
                (allowance >= 0L && work - start >= allowance)
            override fun deadline() = listOfNotNull(token.deadline(), deadline).minOrNull()
            override fun workMeter(): RootWorkMeter = meter
        }
        activeSlice = timeSource.markNow()
        try {
            return advance(token, preparation, deadline, start, allowance, onIncumbent)
        } finally {
            activeTime += checkNotNull(activeSlice).elapsedNow()
            activeSlice = null
            if (offered >= 0L) workDebt = addWork(workDebt, (work - start - offered).coerceAtLeast(0L))
        }
    }

    private fun advance(
        token: Cancellation,
        preparation: Cancellation,
        deadline: ComparableTimeMark,
        start: Long,
        allowance: Long,
        onIncumbent: (MinimizeResult.WithSample) -> Unit,
    ): MinimizeResult? {
        while (!preparation()) {
            if (initialIndex < objective.boolWeightValues.size) {
                prepareObjective()
                if (initialIndex < objective.boolWeightValues.size) continue
                threshold = if (stratify) maximumWeight() else BIG_ONE
            }
            if (core != null) {
                extendCore()
                if (core != null) continue
            }
            if (token() || deadline.hasPassedNow()) return null
            val incumbent = best
            if (incumbent != null && bestValue == lowerBound) {
                return finish(MinimizeResult.Optimal(incumbent, requireNotNull(bestValue).toDouble(), stats))
            }
            if (allowance >= 0L && work - start >= allowance) {
                return if (allowanceExhausted) finish(stopped(TerminationReason.BudgetExhausted)) else null
            }
            val handle = query ?: try {
                openQuery(preparation.withLifetime(lifetime)).also { query = it }
            } catch (_: SearchInitializationCancelled) {
                return if (allowanceExhausted) finish(stopped(TerminationReason.BudgetExhausted)) else null
            }
            val remaining = if (allowance < 0L) -1L else (allowance - (work - start)).coerceAtLeast(0L)
            val millis = (deadline - TimeSource.Monotonic.markNow()).inWholeMilliseconds.coerceAtLeast(0L)
            val result = handle.runSlice(preparation.withLifetime(lifetime), millis, remaining)
            if (result == null) {
                return if (allowanceExhausted) finish(stopped(TerminationReason.BudgetExhausted)) else null
            }
            val paused = result is SolveResult.Unknown && result.reason == TerminationReason.Cancelled && !token()
            finishQuery(keepModel = paused)
            if (paused) {
                return if (allowanceExhausted) finish(stopped(TerminationReason.BudgetExhausted)) else null
            }
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
                    recordPreparation(querySofts.size.toLong())
                    val selected = if (assumptions == null) {
                        querySofts
                    } else {
                        querySofts.filter {
                            assumptions.boolValueOrNull(Lit.variable(it)) == !Lit.isPositive(it)
                        }.toIntArray()
                    }
                    if (selected.isEmpty()) return finish(MinimizeResult.Infeasible(stats = stats))
                    recordPreparation(3L * selected.size)
                    coreWeight = selected.map { requireNotNull(weights[it]) }.reduce { a, b -> if (a < b) a else b }
                    core = selected
                    coreCursor = 0
                    coreEncoded = false
                    coreLiterals = selected.copyOf()
                    factors.add(Clause(coreLiterals))
                    lower += coreWeight
                    completedCores++
                    largestCore = maxOf(largestCore, selected.size)
                    publishLowerBound()
                    best?.let { sample ->
                        if (bestValue == lowerBound) {
                            return finish(MinimizeResult.Optimal(sample, requireNotNull(bestValue).toDouble(), stats))
                        }
                    }
                }
            }
        }
        return if (allowanceExhausted) finish(stopped(TerminationReason.BudgetExhausted)) else null
    }

    private fun prepareObjective() {
        val end = minOf(objective.boolWeightValues.size, initialIndex + 256)
        while (initialIndex < end) {
            val id = initialIndex++
            recordPreparation(1L)
            val weight = objective.boolWeightValues[id]
            if (weight == 0L) continue
            val exact = bigIntOf(weight)
            val preferred = Lit.make(id, weight < 0L)
            val violation = if (params.assumptions.isFrozenBool(id)) {
                // Private assumptions must not overwrite a caller's source pin.
                val selector = nextBool++
                factors.add(Oll.relaxerClause(preferred, selector))
                factors.add(Clause(intArrayOf(Lit.negate(preferred), Lit.make(selector, false))))
                recordPreparation(6L)
                Lit.make(selector, true)
            } else Lit.negate(preferred)
            weights[violation] = exact.abs()
        }
    }

    private fun extendCore() {
        val selected = requireNotNull(core)
        if (!coreEncoded) {
            coreEncoded = true
            if (selected.size > 1) appendThreshold(coreLiterals, 2, coreWeight)
            return
        }
        val end = minOf(selected.size, coreCursor + 256)
        while (coreCursor < end) {
            val id = selected[coreCursor++]
            recordPreparation(1L)
            val residual = requireNotNull(weights[id]) - coreWeight
            if (residual == BIG_ZERO) {
                weights.remove(id)
                tails.remove(id)?.let { appendThreshold(it.literals, it.minimum, it.weight) }
            } else {
                weights[id] = residual
            }
        }
        if (coreCursor < selected.size) return
        val current = threshold ?: coreWeight
        val maximum = maximumWeight() ?: coreWeight
        threshold = if (current < maximum) current else maximum
        core = null
    }

    private fun appendThreshold(literals: IntArray, minimum: Int, weight: BigInt) {
        val aux = nextBool++
        val literal = Lit.make(aux, true)
        factors.add(ReifiedCardinality(aux, literals, min = minimum, max = literals.size))
        weights[literal] = weight
        recordPreparation(1L + literals.size)
        // A latent higher threshold implies its frontier; spending the frontier exposes its successor.
        if (minimum < literals.size) tails[literal] = ThresholdTail(literals, minimum + 1, weight)
    }

    private fun maximumWeight(below: BigInt? = null): BigInt? {
        var maximum: BigInt? = null
        for (weight in weights.values) {
            recordPreparation(1L)
            if (below != null && weight >= below) continue
            if (maximum == null || weight > maximum) maximum = weight
        }
        return maximum
    }

    private fun openQuery(token: Cancellation): ResumableSolve {
        recordPreparation(weights.size.toLong())
        querySofts = weights.filterValues { it >= (threshold ?: BIG_ZERO) }.keys.toIntArray()
        recordPreparation(querySofts.size.toLong())
        val softPins = Assumptions(bools = querySofts.associate { Lit.variable(it) to !Lit.isPositive(it) })
        val baked = queryModel ?: bakeQuery(token).also { queryModel = it }
        return try {
            ResumableSatisfaction(BacktrackSolver(baked),
                params.copy(
                    assumptions = params.assumptions.mergedWith(softPins),
                    cancellation = token,
                    propagationCancelFloor = 0,
                    maxDecisions = (
                        minOf(params.maxDecisions, params.maxInstructions ?: Long.MAX_VALUE) -
                            completedStats.search.nodes.sum.toLong()
                    ).coerceAtLeast(0L),
                    clauseExchange = null,
                    cutExchange = null,
                ),
                ownsWorkDebt = false,
            )
        } catch (failure: SearchInitializationCancelled) {
            settleQuery(failure.stats, failure.work)
            throw failure
        }
    }

    private fun bakeQuery(token: Cancellation): BakedProblem {
        val problem = Problem(
            settings = base.settings,
            numBoolVars = nextBool,
            numIntVars = base.numIntVars,
            intDomains = base.rootIntDomains(),
            factors = factors,
            modelBounds = base.intBounds,
        )
        val providers = base.projectionProviders
        return if (providers == null || providers == FactorProjectionProviders.Default) {
            problem.bake(token)
        } else {
            problem.bake(requireNotNull(base.factorProjections).withAppendedFactors(problem), token)
        }
    }

    private fun recordPreparation(units: Long) {
        preparationVisits = addWork(preparationVisits, units)
    }

    private fun publishLowerBound() {
        var value = lower.toDouble()
        if (!value.isFinite()) return
        if (requireNotNull(BigFraction.ofDouble(value)) > lowerBound) value = value.nextDown()
        params.objectiveLowerBoundSink?.invoke(value)
    }

    private fun finishQuery(keepModel: Boolean = false) {
        val handle = query ?: return
        handle.close()
        settleQuery(handle.stats, handle.work)
        query = null
        if (!keepModel) queryModel = null
    }

    private fun settleQuery(queryStats: SolveStats, queryWork: Long) {
        completedStats = completedStats.mergedWith(queryStats)
        val visits = queryStats.search.propagationWork.sum.toLong()
        val carried = (completedPropagation % PROPAGATION_WORK_PER_NODE + visits % PROPAGATION_WORK_PER_NODE) /
            PROPAGATION_WORK_PER_NODE
        completedPropagation = addWork(completedPropagation, visits)
        completedWork = addWork(completedWork, addWork(queryWork, carried))
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
        queryModel = null
        tails.clear()
        weights.clear()
        factors.clear()
        core = null
        coreLiterals = IntArray(0)
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

private fun addWork(left: Long, right: Long): Long =
    if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
