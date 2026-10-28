package com.eignex.klause.meta.coreguided

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.PROPAGATION_WORK_PER_NODE
import com.eignex.klause.backtrack.PreparingSatisfaction
import com.eignex.klause.backtrack.ResumableSatisfaction
import com.eignex.klause.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.presolve.SeparableObjectiveView
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.RootWorkMeter
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
import com.eignex.klause.solver.validation.composedFixpoint
import com.eignex.klause.solver.withLifetime
import com.eignex.klause.solver.withSourceSemantics
import com.eignex.klause.solver.isClausal
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.abs
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
import com.eignex.klause.util.times
import com.eignex.klause.util.toDouble
import kotlin.math.nextDown
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Resumable core-guided minimization of a Boolean objective by OLL soft cardinality constraints
 * (A. Morgado, C. Dodaro and J. Marques-Silva, "Core-Guided MaxSAT with Soft Cardinality Constraints",
 * CP 2014, https://doi.org/10.1007/978-3-319-10428-7_41): each core raises the lower bound by its minimum
 * weight and adds a reified `≥ 2` threshold over its literals as a new soft, whose successor threshold
 * enters once it is spent.
 *
 * A finite integer cost `c·x` is the sum of the gaps between consecutive root values that `x` crosses away
 * from its cheapest end, so each gap is a soft threshold literal `[x ≥ v]` (or `[x ≤ v]` when `c < 0`)
 * weighted by `|c|` times the gap. Only the first unspent threshold of each column is materialized; a
 * latent successor implies its frontier.
 */
internal class CoreGuidedSearch(
    private val base: BakedProblem,
    private val objective: LinearObjective,
    private val params: BacktrackParams,
    private val stratify: Boolean = false,
    private val maximumWork: Long? = null,
    private val maximumMillis: Long? = null,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val coreRefinement: CoreRefinementMode = CoreRefinementMode.NONE,
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
    private val initialFactorCount = factors.size
    private val weights = LinkedHashMap<Int, BigInt>()
    private val tails = HashMap<Int, Tail>()
    private var nextBool = base.numBoolVars
    private var costs = objective
    private var costsPrepared = false
    private val objectiveTerms get() = costs.boolWeightValues.size + costs.intCoefficientValues.size
    private var initialIndex = 0
    private var lower = cheapest(base, objective)
    private var threshold: BigInt? = null
    private var query: ResumableSolve? = null
    private var queryModel: BakedProblem? = null
    private var queryPins: Assumptions? = null
    private var resumableOpening = false
    private var objectivePreparation: Iterator<Unit>? = null
    private var corePreparation: Iterator<Unit>? = null
    private var queryPreparation: Iterator<Unit>? = null
    private var transitionPreparation: Iterator<Unit>? = null
    private var preparationToken: Cancellation = Cancellation.Never
    private var preparationVisits = factors.size.toLong() + objectiveTerms
    internal val preparationWork: Long get() = preparationVisits
    internal var thresholdVisits = 0L
        private set
    internal var queryCount = 0L
        private set
    internal var verificationVisits = 0L
        private set
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
    private var coreLiterals = IntArray(0)
    private var completedCores = 0L
    private var largestCore = 0

    private sealed interface Tail

    private class ThresholdTail(val literals: IntArray, val minimum: Int, val weight: BigInt) : Tail

    private class FrontierTail(val variable: Int, val coefficient: Long, val reached: Long) : Tail

    init {
        require(maximumWork == null || maximumWork >= 0L) { "core work limit must be nonnegative" }
        require(maximumMillis == null || maximumMillis >= 0L) { "core time limit must be nonnegative" }
        require(admits(base, objective)) { "core-guided search needs a finite Boolean or integer objective" }
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
        get() = !costsPrepared || objectivePreparation != null || initialIndex < objectiveTerms || core != null ||
            transitionPreparation != null ||
            (query as? PreparingSatisfaction)?.preparationPending == true ||
            (query as? ResumableSatisfaction)?.preparationPending == true ||
            ((queryPreparation != null || queryModel != null || queryPins != null) && query == null)

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
        val sliceDuration = minOf(sliceMillis, remainingMillis).milliseconds
        val deadline = TimeSource.Monotonic.markNow() + sliceDuration
        val segment = object : Cancellation {
            override fun isCancelled(): Boolean =
                deadline.hasPassedNow() || (activeSlice?.elapsedNow() ?: Duration.ZERO) >= sliceDuration
            override fun deadline() = deadline
        }
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
            override fun isCancelled(): Boolean = token() || segment() || allowanceExhausted ||
                (allowance >= 0L && work - start >= allowance)
            override fun deadline() = listOfNotNull(token.deadline(), deadline).minOrNull()
            override fun workMeter(): RootWorkMeter = meter
        }
        activeSlice = timeSource.markNow()
        preparationToken = preparation
        try {
            return advance(token, preparation, segment, start, allowance, onIncumbent)
        } finally {
            activeTime += checkNotNull(activeSlice).elapsedNow()
            activeSlice = null
            preparationToken = Cancellation.Never
            if (offered >= 0L) workDebt = addWork(workDebt, (work - start - offered).coerceAtLeast(0L))
        }
    }

    private fun advance(
        token: Cancellation,
        preparation: Cancellation,
        segment: Cancellation,
        start: Long,
        allowance: Long,
        onIncumbent: (MinimizeResult.WithSample) -> Unit,
    ): MinimizeResult? {
        val deadline = requireNotNull(segment.deadline())
        while (!preparation()) {
            terminal?.let { return it }
            if (!costsPrepared) {
                costsPrepared = true
                SeparableObjectiveView.create(base, objective, preparation)?.let {
                    costs = it.objective
                    lower = cheapest(base, costs)
                }
                if (preparation()) continue
            }
            if (objectivePreparation != null || initialIndex < objectiveTerms) {
                val builder = objectivePreparation ?: prepareObjective().also { objectivePreparation = it }
                if (!advancePreparation(builder, preparation)) continue
                objectivePreparation = null
            }
            transitionPreparation?.let { builder ->
                if (advancePreparation(builder, preparation)) transitionPreparation = null
            }
            if (transitionPreparation != null || terminal != null) continue
            if (core != null) {
                val builder = corePreparation ?: extendCore().also { corePreparation = it }
                if (!advancePreparation(builder, preparation)) continue
                corePreparation = null
            }
            if (token() || segment()) return null
            val incumbent = best
            if (incumbent != null && bestValue == lowerBound) {
                return finish(MinimizeResult.Optimal(incumbent, requireNotNull(bestValue).toDouble(), stats))
            }
            if (allowance >= 0L && work - start >= allowance) {
                return if (allowanceExhausted) finish(stopped(TerminationReason.BudgetExhausted)) else null
            }
            if (query == null && (queryPins == null || queryPreparation != null)) {
                val builder = queryPreparation ?: prepareQuery().also { queryPreparation = it }
                if (!advancePreparation(builder, preparation)) continue
                queryPreparation = null
            }
            if (preparation()) continue
            val handle = query ?: try {
                openQuery(preparation.withLifetime(lifetime)).also { query = it }
            } catch (_: SearchInitializationCancelled) {
                return if (allowanceExhausted) finish(stopped(TerminationReason.BudgetExhausted)) else null
            }
            val remaining = if (allowance < 0L) -1L else (allowance - (work - start)).coerceAtLeast(0L)
            val millis = (deadline - TimeSource.Monotonic.markNow()).inWholeMilliseconds.coerceAtLeast(0L)
            val traversalToken = token or Cancellation.until(deadline) or segment or Cancellation { allowanceExhausted }
            val result = handle.runSlice(traversalToken.withLifetime(lifetime), millis, remaining)
            if (result == null) {
                return if (allowanceExhausted) finish(stopped(TerminationReason.BudgetExhausted)) else null
            }
            val decisionLimit = minOf(params.maxDecisions, params.maxInstructions ?: Long.MAX_VALUE)
            val decisions = addWork(completedStats.search.nodes.sum.toLong(), handle.stats.search.nodes.sum.toLong())
            val quotaExhausted = params.nodeBudget?.allowance?.exhausted() == true || decisions >= decisionLimit
            val paused = result is SolveResult.Unknown &&
                (result.reason == TerminationReason.Cancelled || result.reason == TerminationReason.BudgetExhausted) &&
                segment() && !token() && !quotaExhausted
            val refutationModel = if (result is SolveResult.Unsat &&
                coreRefinement == CoreRefinementMode.DELETION
            ) queryModel else null
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
                    when (val verified = composedFixpoint(
                        base, Candidate(sample, Unit), traversalToken, params.assumptions,
                        onWork = { units ->
                            recordPreparation(units)
                            verificationVisits = addWork(verificationVisits, units)
                        },
                    )) {
                        is Verification.Rejected -> throw UnsoundnessException(verified.reason)
                        is Verification.Indeterminate -> return if (allowanceExhausted) {
                            finish(stopped(TerminationReason.BudgetExhausted))
                        } else null
                        is Verification.Accepted -> Unit
                    }
                    val value = objective.evaluateExact(sample)
                    if (bestValue == null || value < requireNotNull(bestValue)) {
                        best = sample
                        bestValue = value
                        onIncumbent(
                            MinimizeResult.BestFound(sample, value.toDouble(), TerminationReason.BudgetExhausted)
                                .withSourceSemantics(base.semantics),
                        )
                    }
                    if (value == lowerBound) return finish(MinimizeResult.Optimal(sample, value.toDouble(), stats))
                    val current = requireNotNull(threshold)
                    queryPreparation = sequence {
                        threshold = maximumWeight(current)
                        check(threshold != null) { "core objective identity differs from source objective" }
                        yieldAll(prepareQuery())
                    }.iterator()
                }

                is SolveResult.Unsat -> {
                    transitionPreparation = prepareCore(result.assumptionCore, refutationModel)
                }
            }
        }
        return if (allowanceExhausted) finish(stopped(TerminationReason.BudgetExhausted)) else null
    }

    private fun prepareCore(assumptions: Assumptions?, model: BakedProblem?): Iterator<Unit> = sequence {
        val projected = IntArrayList()
        for (literal in querySofts) {
            recordPreparation(1L)
            if (assumptions == null ||
                assumptions.boolValueOrNull(Lit.variable(literal)) == !Lit.isPositive(literal)
            ) projected.add(literal)
            yield(Unit)
        }
        recordPreparation(projected.size.toLong())
        val original = projected.toIntArray()
        val selected = model?.let {
            CoreRefinement.refine(it, original, params.assumptions, preparationToken, ::recordPreparation)
        } ?: original
        yield(Unit)
        if (selected.isEmpty()) {
            finish(MinimizeResult.Infeasible(stats = stats))
            return@sequence
        }
        var minimum = requireNotNull(weights[selected[0]])
        for (literal in selected) {
            recordPreparation(1L)
            val weight = requireNotNull(weights[literal])
            if (weight < minimum) minimum = weight
            yield(Unit)
        }
        recordPreparation(selected.size.toLong())
        coreWeight = minimum
        core = selected
        coreLiterals = selected.copyOf()
        factors.add(Clause(coreLiterals))
        lower += coreWeight
        completedCores++
        largestCore = maxOf(largestCore, selected.size)
        publishLowerBound()
    }.iterator()

    private fun prepareObjective(): Iterator<Unit> = sequence {
        while (initialIndex < objectiveTerms) {
            val id = initialIndex++
            recordPreparation(1L)
            yield(Unit)
            if (id >= costs.boolWeightValues.size) {
                val variable = id - costs.boolWeightValues.size
                val coefficient = costs.intCoefficientValues[variable]
                if (coefficient == 0L) continue
                val domain = base.rootIntDomain(variable)
                appendFrontier(variable, coefficient, if (coefficient > 0L) domain.min else domain.max)
                continue
            }
            val weight = costs.boolWeightValues[id]
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
        threshold = if (stratify) maximumWeight() else BIG_ONE
    }.iterator()

    private fun extendCore(): Iterator<Unit> = sequence {
        val selected = requireNotNull(core)
        if (selected.size > 1) appendThreshold(coreLiterals, 2, coreWeight)
        yield(Unit)
        for (id in selected) {
            recordPreparation(1L)
            val residual = requireNotNull(weights[id]) - coreWeight
            if (residual == BIG_ZERO) {
                weights.remove(id)
                when (val tail = tails.remove(id)) {
                    is ThresholdTail -> appendThreshold(tail.literals, tail.minimum, tail.weight)
                    is FrontierTail -> appendFrontier(tail.variable, tail.coefficient, tail.reached)
                    null -> Unit
                }
            } else {
                weights[id] = residual
            }
            yield(Unit)
        }
        val current = threshold ?: coreWeight
        val maximum = maximumWeight() ?: coreWeight
        threshold = if (current < maximum) current else maximum
        core = null
    }.iterator()

    private fun appendThreshold(literals: IntArray, minimum: Int, weight: BigInt) {
        val aux = nextBool++
        val literal = Lit.make(aux, true)
        factors.add(ReifiedCardinality(aux, literals, min = minimum, max = literals.size))
        weights[literal] = weight
        recordPreparation(1L + literals.size)
        // A latent higher threshold implies its frontier; spending the frontier exposes its successor.
        if (minimum < literals.size) tails[literal] = ThresholdTail(literals, minimum + 1, weight)
    }

    private fun appendFrontier(variable: Int, coefficient: Long, reached: Long) {
        val domain = base.rootIntDomain(variable)
        val ascending = coefficient > 0L
        if (if (ascending) reached >= domain.max else reached <= domain.min) return
        val next = if (ascending) domain.higher(reached) else domain.lower(reached)
        val selector = nextBool++
        val op = if (ascending) LinearOp.GE else LinearOp.LE
        factors.add(ReifiedLinear(selector, longArrayOf(1), intArrayOf(variable), op, next))
        val literal = Lit.make(selector, true)
        weights[literal] = bigIntOf(coefficient).abs() * (bigIntOf(next) - bigIntOf(reached)).abs()
        tails[literal] = FrontierTail(variable, coefficient, next)
        recordPreparation(3L)
    }

    private suspend fun SequenceScope<Unit>.maximumWeight(below: BigInt? = null): BigInt? {
        var maximum: BigInt? = null
        for (weight in weights.values) {
            recordPreparation(1L)
            thresholdVisits = addWork(thresholdVisits, 1L)
            if ((below == null || weight < below) && (maximum == null || weight > maximum)) maximum = weight
            yield(Unit)
        }
        return maximum
    }

    private fun advancePreparation(builder: Iterator<Unit>, token: Cancellation): Boolean {
        while (!token()) {
            if (!builder.hasNext()) return true
            builder.next()
        }
        return false
    }

    private fun prepareQuery(): Iterator<Unit> = sequence {
        val literals = IntArrayList()
        val pins = LinkedHashMap<Int, Boolean>()
        for ((literal, weight) in weights) {
            recordPreparation(1L)
            thresholdVisits = addWork(thresholdVisits, 1L)
            if (weight >= (threshold ?: BIG_ZERO)) {
                literals.add(literal)
                pins[Lit.variable(literal)] = !Lit.isPositive(literal)
                recordPreparation(1L)
            }
            yield(Unit)
        }
        recordPreparation(literals.size.toLong() + pins.size + params.assumptions.numBools +
            params.assumptions.numInts)
        querySofts = literals.toIntArray()
        queryPins = params.assumptions.mergedWith(Assumptions(bools = pins))
        yieldAll(prepareQueryConstruction())
    }.iterator()

    private fun prepareQueryConstruction(): Iterator<Unit> = sequence {
        recordPreparation(nextBool.toLong() + base.numIntVars)
        yield(Unit)
        for (factor in factors) {
            recordPreparation(1L + factor.structuralKeyWeight)
            yield(Unit)
        }
    }.iterator()

    private fun openQuery(token: Cancellation): ResumableSolve {
        val baked = queryModel ?: bakeQuery().also { queryModel = it }
        return try {
            val queryParams = params.copy(
                    assumptions = requireNotNull(queryPins),
                    cancellation = token,
                    propagationCancelFloor = 0,
                    maxDecisions = (
                        minOf(params.maxDecisions, params.maxInstructions ?: Long.MAX_VALUE) -
                            completedStats.search.nodes.sum.toLong()
                    ).coerceAtLeast(0L),
                    clauseExchange = null,
                    cutExchange = null,
                )
            val projected = coreRefinement == CoreRefinementMode.REASONS
            val solver = BacktrackSolver(baked)
            if (!resumableOpening && params.nativeSat != false && baked.isClausal()) {
                ResumableSatisfaction(solver, queryParams, ownsWorkDebt = false, projectAssumptionCore = projected)
            } else {
                PreparingSatisfaction(solver, queryParams.copy(nativeSat = false), projectAssumptionCore = projected)
            }.also { queryCount = addWork(queryCount, 1L) }
        } catch (failure: SearchInitializationCancelled) {
            settleQuery(failure.stats, failure.work)
            // A native opening is atomic; after a stop the retained general preparer guarantees progress.
            resumableOpening = true
            queryPreparation = prepareQueryConstruction()
            throw failure
        }
    }

    private fun bakeQuery(): BakedProblem {
        // Residual assumptions do not change the immutable source projection or its root deductions.
        if (nextBool == base.numBoolVars && factors.size == initialFactorCount) return base
        recordPreparation(factors.size.toLong() + base.numIntVars)
        return BakedProblem.fromAugmentedRoot(base, nextBool, factors.toTypedArray())
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
        if (!keepModel) {
            queryModel = null
            queryPins = null
            resumableOpening = false
        } else {
            resumableOpening = true
            queryPreparation = prepareQueryConstruction()
        }
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
        val qualified = result.withSourceSemantics(base.semantics)
        terminal = qualified
        close()
        return qualified
    }

    override fun close() {
        if (closed) return
        finishQuery()
        queryModel = null
        queryPins = null
        objectivePreparation = null
        corePreparation = null
        queryPreparation = null
        transitionPreparation = null
        tails.clear()
        weights.clear()
        factors.clear()
        core = null
        coreLiterals = IntArray(0)
        closed = true
    }

    companion object {
        fun scalarProofs(problem: BakedProblem, objective: LinearObjective): Boolean {
            var magnitude = objective.boolWeightValues.fold(bigIntOf(objective.constant).abs()) { total, weight ->
                total + bigIntOf(weight).abs()
            }
            for (v in objective.intCoefficientValues.indices) {
                val coefficient = objective.intCoefficientValues[v]
                if (coefficient == 0L) continue
                if (v >= problem.numIntVars) return false
                val domain = problem.rootIntDomain(v)
                val low = bigIntOf(domain.min).abs()
                val high = bigIntOf(domain.max).abs()
                val extreme = if (low > high) low else high
                magnitude += bigIntOf(coefficient).abs() * extreme
            }
            return magnitude <= bigIntOf(9_007_199_254_740_992L)
        }

        /** Boolean objectives, which curated pools admit. */
        fun eligible(problem: BakedProblem, objective: LinearObjective): Boolean =
            admits(problem, objective) && objective.intCoefficientValues.all { it == 0L }

        /**
         * Boolean objectives and exact integer costs over columns whose source bounds are closed on both
         * sides, so no frontier starts from or walks toward an endpoint the finite projection invented.
         */
        fun admits(problem: BakedProblem, objective: LinearObjective): Boolean =
            problem.numRealVars == 0 && objective.boolWeightValues.size <= problem.numBoolVars &&
                objective.realCoefficientValues.all { it == 0.0 } &&
                objective.intCoefficientValues.indices.all { v ->
                    objective.intCoefficientValues[v] == 0L || exactColumn(problem, v)
                }

        private fun exactColumn(problem: BakedProblem, v: Int): Boolean = v < problem.numIntVars &&
            !problem.semantics.isApproximate && problem.intBounds.hasLower(v) && problem.intBounds.hasUpper(v)

        private fun cheapest(problem: BakedProblem, objective: LinearObjective): BigInt {
            var value = objective.boolWeightValues.fold(bigIntOf(objective.constant)) { total, weight ->
                if (weight < 0L) total + bigIntOf(weight) else total
            }
            for (v in objective.intCoefficientValues.indices) {
                val coefficient = objective.intCoefficientValues[v]
                if (coefficient == 0L || v >= problem.numIntVars) continue
                val domain = problem.rootIntDomain(v)
                value += bigIntOf(coefficient) * bigIntOf(if (coefficient > 0L) domain.min else domain.max)
            }
            return value
        }
    }
}

private fun addWork(left: Long, right: Long): Long =
    if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
