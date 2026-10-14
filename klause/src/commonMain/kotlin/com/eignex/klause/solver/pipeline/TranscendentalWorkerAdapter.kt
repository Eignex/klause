package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.TranscendentalExpression
import com.eignex.klause.ir.TranscendentalFormula
import com.eignex.klause.ir.TranscendentalProblem
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.ReseedGuard
import com.eignex.klause.solver.ResumableSolve
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.incumbent.CompletedEnginePublication
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.EvidenceCertificate
import com.eignex.klause.solver.result.RunStats
import com.eignex.klause.solver.result.SearchStats
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecisionBudget
import com.eignex.klause.solver.search.SearchNodeDisposition
import com.eignex.klause.solver.search.SearchNodePolicy
import com.eignex.klause.solver.search.SearchRun
import com.eignex.klause.solver.search.SearchRunEvent
import com.eignex.klause.solver.search.SearchRunObserver
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SearchSolveParams
import com.eignex.klause.theory.qfnra.PolynomialCondition
import com.eignex.klause.theory.transcendental.TranscendentalCheckResult
import com.eignex.klause.theory.transcendental.TranscendentalCondition
import com.eignex.klause.theory.transcendental.TranscendentalSearchComponent
import com.eignex.klause.theory.transcendental.TranscendentalTheoryService
import com.eignex.klause.theory.transcendental.TranscendentalWitness
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.toLongExact
import com.eignex.kumulant.stat.summary.SumResult
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

internal class TranscendentalWorkerAdapter(
    private val source: TranscendentalProblem,
    private val params: TranscendentalTheoryParams,
    private val engine: FiniteEngine,
    private val binding: EngineInvocation? = null,
) : ExactNeighborhoodCompletion, AutoCloseable {
    private val started = TimeSource.Monotonic.markNow()
    private val external = params.timeout or params.cancellation
    private val nodeBudget = params.nodeBudget ?: params.strategy.backtrack.nodeBudget
        ?: params.strategy.localSearch.nodeBudget
    private var nativeService: TranscendentalTheoryService? = null
    private var closed = false
    private val service: TranscendentalTheoryService get() {
        check(!closed) { "transcendental worker is closed" }
        return nativeService ?: TranscendentalTheoryService(
            source, external, params.maximumWork, params.maximumBits,
            PolynomialLimits(params.maximumDenseCoefficients, params.maximumMatrixCells)).also { nativeService = it }
    }
    private var closedWork = 0L
    private var closedDecline = false
    private val stop = external or Cancellation { service.exhausted || nodeBudget?.exhausted() == true }
    private val root = assumptions()
    private var strategyStats = SolveStats.EMPTY
    private var decisions = 0L

    private fun assumptions(): TranscendentalCondition {
        val backend = when (engine) {
            FiniteEngine.BACKTRACK, FiniteEngine.FIXED -> params.strategy.backtrack.assumptions
            FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS -> params.strategy.localSearch.assumptions
            FiniteEngine.MIXED -> Assumptions.None
        }
        val pins = backend.mergedWith(params.strategy.assumptions)
        val integers = pins.ints.map { (id, value) ->
            require(id in source.integerCoordinates.indices) { "integer assumption id out of range" }
            source.integerCoordinates[id] to BigFraction.ofLong(value)
        }.toMap()
        fun realBounds(values: Map<Int, BigFraction>) = values.mapKeys { (id, _) ->
            require(id in source.realCoordinates.indices) { "real assumption id out of range" }
            source.realCoordinates[id]
        }
        return TranscendentalCondition(
            PolynomialCondition(
                pins.bools, integers + realBounds(params.realLower), integers + realBounds(params.realUpper),
            ),
            params.strictRealLower.map { source.realCoordinates[it] }.toSet(),
            params.strictRealUpper.map { source.realCoordinates[it] }.toSet(),
        )
    }

    fun execute(): TranscendentalTheoryExecution {
        check(!closed) { "transcendental worker is closed" }
        val result = when (engine) {
            FiniteEngine.BACKTRACK, FiniteEngine.FIXED -> search()
            FiniteEngine.LOCAL_SEARCH -> walk(false)
            FiniteEngine.ALNS -> walk(true)
            FiniteEngine.MIXED -> error("mixed execution requires the shared invocation")
        }
        val stats = strategyStats.copy(
            run = RunStats("transcendental-${engine.id}", started.elapsedNow().inWholeMilliseconds, params.timeout()),
            search = strategyStats.search.copy(nodes = SumResult(strategyStats.search.nodes.sum + decisions)),
        )
        val published = when (val finished = result) {
            is OpenTheoryResult.Sat -> finished.copy(stats = stats)
            is OpenTheoryResult.Unsat -> finished.copy(stats = stats)
            else -> OpenTheoryResult.Unknown(reason(), stats)
        }
        return TranscendentalTheoryExecution(published, service.work, service.representationDeclined)
    }

    val nativeWork: Long get() = nativeService?.work ?: closedWork
    val representationDeclined: Boolean get() = nativeService?.representationDeclined ?: closedDecline

    override fun close() {
        if (closed) return
        closed = true
        closedWork = nativeWork
        closedDecline = representationDeclined
        nativeService = null
    }

    fun worker(arm: Int): PortfolioWorker {
        val identity = requireNotNull(binding).model
        val worker = if (arm == 0) {
            PortfolioWorker.ofSolve("bt+exact-transcendental", arm, resumable = { completeHandle() }) { global, _ ->
                completeHandle().use { requireNotNull(it.runSlice(global, Long.MAX_VALUE, -1)) }
            }
        } else exactNeighborhoodWorker(
            if (arm == 2) "alns+exact-transcendental" else "ls+exact-transcendental", arm,
            objective = null, lifetime = external or params.strategy.localSearch.cancellation,
            timeout = params.timeout, exhausted = { service.exhausted || nodeBudget?.exhausted() == true },
            open = { neighborhood(arm == 2) },
        )
        return worker.bindEvidence(identity, provesResults = arm == 0).bindInvocation(requireNotNull(binding))
    }

    internal fun completeHandle(): ResumableSolve {
        check(!closed) { "transcendental worker is closed" }
        return object : ResumableSolve, ReseedGuard {
            override val canReseed: Boolean get() = false
            private var closed = false
            private var result: SolveResult? = null
            private var global = Cancellation.Never
            private var deadline = Cancellation.Never
            private var end = Long.MAX_VALUE
            private val lifetime = stop or params.strategy.backtrack.cancellation or Cancellation { global() }
            private var component: TranscendentalSearchComponent? = null
            private var session: SearchSession? = null
            private var run: SearchRun? = null

            private fun openTraversal(rootUnknown: Boolean = false) {
                component = TranscendentalSearchComponent(source, service, root, params.maximumDepth, rootUnknown)
                session = SearchSession(listOf(requireNotNull(component)),
                    cancellation = lifetime, branchers = listOf(requireNotNull(component)))
                run = requireNotNull(session).openRun(
                    source.boolCount,
                    SearchSolveParams(minOf(params.strategy.backtrack.maxDecisions,
                        params.strategy.backtrack.maxInstructions ?: Long.MAX_VALUE)),
                    decisionBudget = SearchDecisionBudget {
                        if (nodeBudget?.exhausted() == true) false else {
                            nodeBudget?.spend()
                            true
                        }
                    },
                    observer = object : SearchRunObserver {
                        override fun onCommit(
                            decision: com.eignex.klause.solver.search.SearchDecision, decisionLevel: Int,
                        ) {
                            decisions++
                        }
                    },
                    nodePolicy = object : SearchNodePolicy {
                        override fun beforeBranch(context: SearchContext): SearchNodeDisposition =
                            if (deadline() || work >= end) SearchNodeDisposition.Pause else SearchNodeDisposition.Expand
                    },
                ).also { it.pauseBeforeDecision = { deadline() || work >= end } }
            }
            override val isDone: Boolean get() = result != null
            override val stats: SolveStats get() = SolveStats.EMPTY.copy(
                search = SearchStats(nodes = SumResult(decisions.toDouble())))
            override val work: Long get() = nativeWork + minOf(decisions, Long.MAX_VALUE - nativeWork)

            override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long): SolveResult? {
                result?.let { return it }
                check(!closed) { "transcendental traversal is closed" }
                this.global = global
                if (lifetime()) return finish(SolveResult.Unknown(terminationReason(), stats))
                if (sliceNodes == 0L || sliceMillis == 0L) return null
                deadline = if (sliceMillis == Long.MAX_VALUE) Cancellation.Never
                    else Cancellation.after(sliceMillis.milliseconds)
                end = if (sliceNodes < 0) Long.MAX_VALUE else work + minOf(sliceNodes, Long.MAX_VALUE - work)
                return try {
                    if (run == null) {
                        if (source.boolCount == 0) {
                            val checked = service.check(root, lifetime)
                            when (checked) {
                                is TranscendentalCheckResult.Witness -> return finish(SolveResult.Sat(
                                    capture(checked.value), stats))
                                TranscendentalCheckResult.Refuted -> return finish(SolveResult.Unsat(stats = stats))
                                TranscendentalCheckResult.Unknown -> {
                                    if (lifetime()) return finish(SolveResult.Unknown(terminationReason(), stats))
                                    openTraversal(rootUnknown = true)
                                }
                            }
                        } else openTraversal()
                    }
                    when (val event = requireNotNull(run).next()) {
                        is SearchRunEvent.Satisfied -> finish(SolveResult.Sat(
                            capture(requireNotNull(event.model.valueOf(requireNotNull(component).witnessKey))), stats))
                        SearchRunEvent.Exhausted -> finish(SolveResult.Unsat(stats = stats))
                        SearchRunEvent.Paused -> null
                        else -> finish(SolveResult.Unknown(terminationReason(), stats))
                    }
                } finally {
                    this.global = Cancellation.Never
                    deadline = Cancellation.Never
                }
            }
            private fun terminationReason(): TerminationReason =
                if (global() && !stop() && !params.strategy.backtrack.cancellation()) {
                    TerminationReason.Cancelled
                } else reason()

            private fun finish(value: SolveResult): SolveResult = value.also { result = it }
            override fun close() {
                if (closed) return
                closed = true
                run = null
                session = null
                component = null
                global = Cancellation.Never
                deadline = Cancellation.Never
            }
        }
    }

    private fun search(): OpenTheoryResult = completeHandle().use { handle ->
        when (val result = requireNotNull(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1))) {
            is SolveResult.Sat -> OpenTheoryResult.Sat(TranscendentalAssignment(result.assignment), result.stats)
            is SolveResult.Unsat -> OpenTheoryResult.Unsat(result.stats)
            is SolveResult.Unknown -> OpenTheoryResult.Unknown(result.reason, result.stats)
        }
    }

    private fun neighborhood(adaptive: Boolean): ExactNeighborhood {
        val local = params.strategy.localSearch
        val moves = if (adaptive) minOf(local.maxFlips, params.strategy.alnsIterations.toLong()) else local.maxFlips
        return ExactNeighborhood(source.boolCount, initialCenters(), this,
            coordinateValues = { sample -> centers(sample) }, adaptive = adaptive, seed = local.randomSeed ?: 0,
            nodeBudget = nodeBudget, maximumMoves = moves,
            maximumInstructions = local.maxInstructions ?: Long.MAX_VALUE, nativeWork = { service.work })
    }

    private fun walk(adaptive: Boolean): OpenTheoryResult = neighborhood(adaptive).use { neighborhood ->
        neighborhood.advance(stop or params.strategy.localSearch.cancellation, Long.MAX_VALUE)
        val result = neighborhood.solveResult(reason())
        strategyStats = strategyStats.mergedWith(result.stats)
        if (result is SolveResult.Sat) OpenTheoryResult.Sat(TranscendentalAssignment(result.assignment), result.stats)
        else OpenTheoryResult.Unknown(reason(), SolveStats.EMPTY)
    }

    override fun check(condition: ExactNeighborhoodCondition, cancellation: Cancellation): ExactNeighborhoodResult {
        require(condition.cutoff == null) { "transcendental satisfaction has no objective cutoff" }
        val pins = root.rational
        if (condition.bools.any { (id, value) -> pins.bools[id]?.let { it != value } == true }) {
            return ExactNeighborhoodResult.Refuted
        }
        fun intersection(
            current: Map<Int, BigFraction>, fixed: Map<Int, BigFraction>, lower: Boolean,
        ): Map<Int, BigFraction> {
            val result = current.toMutableMap()
            for ((id, value) in fixed) {
                val previous = result[id]
                if (previous == null || if (lower) value > previous else value < previous) result[id] = value
            }
            return result
        }
        val lower = intersection(condition.lower, pins.lower, true)
        val upper = intersection(condition.upper, pins.upper, false)
        val scoped = TranscendentalCondition(PolynomialCondition(condition.bools + pins.bools, lower, upper),
            root.lowerStrict.filter { lower[it] == pins.lower[it] }.toSet(),
            root.upperStrict.filter { upper[it] == pins.upper[it] }.toSet())
        return when (val result = service.check(scoped, cancellation)) {
            is TranscendentalCheckResult.Witness -> ExactNeighborhoodResult.Witness(capture(result.value))
            TranscendentalCheckResult.Refuted -> ExactNeighborhoodResult.Refuted
            TranscendentalCheckResult.Unknown -> ExactNeighborhoodResult.Undecided
        }
    }

    private fun capture(witness: TranscendentalWitness): Sample {
        val integers = witness.integers
        val reals = witness.reals
        val approximate = DoubleArray(reals.size) { id ->
            val value = reals[id]
            value.rational?.toDouble() ?: value.algebraic?.let {
                it.lowerBound.toDouble() / 2 + it.upperBound.toDouble() / 2
            }
                ?: Double.NaN
        }
        val finite = if (integers.all { it.fitsLong() }) {
            LongArray(integers.size) { integers[it].toLongExact() }
        } else longArrayOf()
        return Sample(witness.bools, finite,
            approximate, exactInts = integers, symbolicReals = reals,
            exactReals = reals.mapNotNull { it.rational }.takeIf { it.size == reals.size },
            algebraicReals = reals.mapNotNull { it.algebraic }.takeIf { it.size == reals.size }).also { sample ->
            binding?.let {
                sample.witnessCertificate = EvidenceCertificate.published(
                    CompletedEnginePublication.Witness(it, sample, null))
            }
        }
    }

    private fun centers(sample: Sample): List<BigFraction> {
        val values = MutableList(source.numericCount) { BigFraction.ZERO }
        source.integerCoordinates.forEachIndexed { id, coordinate ->
            values[coordinate] = BigFraction.of(sample.exactIntValue(id), BIG_ONE)
        }
        source.realCoordinates.forEachIndexed { id, coordinate ->
            values[coordinate] = sample.symbolicReals?.get(id)?.rational
                ?: BigFraction.ofDouble(sample.approximateRealValue(id)) ?: BigFraction.ZERO
        }
        return values
    }

    private fun initialCenters(): List<BigFraction> {
        val values = MutableList(source.numericCount) { BigFraction.ZERO }
        val pending = ArrayDeque<TranscendentalFormula>()
        pending.addLast(source.formula)
        while (pending.isNotEmpty() && !stop()) {
            when (val node = pending.removeLast()) {
                is TranscendentalFormula.And -> pending.addAll(node.operands)
                is TranscendentalFormula.Atom -> if (node.relation == PolynomialRelation.EQ) {
                    for ((target, value) in listOf(node.left to node.right, node.right to node.left)) {
                        if (target is TranscendentalExpression.Variable && value is TranscendentalExpression.Constant) {
                            values[target.id.index] = BigFraction.of(value.value.numerator, value.value.denominator)
                        }
                    }
                }
                else -> Unit
            }
        }
        for (id in values.indices) {
            val lower = root.rational.lower[id]
            val upper = root.rational.upper[id]
            if (lower != null && lower == upper) values[id] = lower
            else if (lower != null && values[id] <= lower) values[id] = lower + BigFraction.ONE
            else if (upper != null && values[id] >= upper) values[id] = upper - BigFraction.ONE
        }
        return values
    }

    private fun reason(): TerminationReason = when {
        params.timeout() -> TerminationReason.Timeout
        params.cancellation() -> TerminationReason.Cancelled
        (engine == FiniteEngine.LOCAL_SEARCH || engine == FiniteEngine.ALNS) &&
            params.strategy.localSearch.cancellation() -> TerminationReason.Cancelled
        (engine == FiniteEngine.BACKTRACK || engine == FiniteEngine.FIXED || engine == FiniteEngine.MIXED) &&
            params.strategy.backtrack.cancellation() -> TerminationReason.Cancelled
        else -> TerminationReason.BudgetExhausted
    }
}
