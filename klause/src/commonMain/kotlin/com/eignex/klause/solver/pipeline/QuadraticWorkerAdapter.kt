package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.backtrack.CpSatisfactionTraversal
import com.eignex.klause.backtrack.SearchOutcome
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.randomValue
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.DeducedRestrictions
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.InstructionSlicedSearch
import com.eignex.klause.solver.ReseedGuard
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.portfolio.PortfolioWorker
import kotlin.time.Duration.Companion.milliseconds
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.EvidenceCertificate
import com.eignex.klause.solver.incumbent.EvidenceKind
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.QuadraticEngineDerivation
import com.eignex.klause.solver.evidence.quadraticEngineInvocation
import com.eignex.klause.solver.result.QuadraticCertificate
import com.eignex.klause.solver.result.QuadraticClaim
import com.eignex.klause.solver.result.QuadraticIncumbent
import com.eignex.klause.solver.result.QuadraticObjectiveBound
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.theory.qfnra.PolynomialCondition
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.toLongExact
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

internal class QuadraticWorkerAdapter(
    private val request: PolynomialTheoryRequest,
    private val model: SharedPolynomialModel,
    private val objective: QuadraticObjectiveSpec,
    private val sense: ObjectiveSense,
    private val params: PolynomialTheoryParams,
    private val native: QuadraticNativeScope,
    private val engine: FiniteEngine,
) : AutoCloseable {
    private val assumptions = quadraticAssumptions(params, engine)
    private val proofScope = request.preparedPlan.quadraticEngineInvocation(objective, sense, assumptions)
    private val identity = ModelIdentity.of(request.preparedPlan.originalSource, objective)
    private val completion = QuadraticBranchCompletion(request, model, objective, sense, params, native)
    private val sink = SolveStatsSink("quadratic").also { it.start() }
    private val finite = model.finite.bake(native.polynomial)
    private val sourceBoolCount = request.problem.sourceBoolCount
    private val sourceRealCount = request.problem.sourceRealCount
    private var incumbent: QuadraticIncumbent? = null
    private var bestLimit: AlgebraicReal? = null
    private var limitAttained = false
    private val branchProofs = mutableListOf<SourceDerivation>()
    private var incomplete = false
    private var interrupted = false
    private var repairPoint: QuadraticIncumbent? = null
    private var rootBound: QuadraticObjectiveBound? = null
    private var rootPrepared = false
    private var traversal: QuadraticTraversalCursor? = null
    private var neighborhood: QuadraticNeighborhoodCursor? = null
    private var neighborhoodPreparationInstructions = 0L
    private var preparationWork = 0L
    private var terminal: QuadraticOptimizationResult? = null
    private var publish: ((QuadraticIncumbent) -> Unit)? = null
    var readIncumbent: () -> QuadraticIncumbent? = { null }
    var onAccepted: ((QuadraticIncumbent) -> Unit)? = null
    private val stats: SolveStats get() = neighborhood?.let { sink.snapshot().mergedWith(it.stats) } ?: sink.snapshot()
    private val repairCompletion = ExactNeighborhoodCompletion { condition, cancellation ->
        repairNeighborhood(condition, cancellation)
    }

    private fun prepare() {
        if (rootPrepared) return
        val before = native.work
        try {
            quadraticBoxBound(model.source, objective, assumptions, sense,
                completion.algebraic.arithmetic::step)?.let {
                val value = completion.algebraic.rational(it.first)
                if (!native.stopped) rootBound = QuadraticObjectiveBound(value, false,
                    certificate(QuadraticEngineDerivation.Box(it.second), QuadraticClaim.Bound))
            }
            rootPrepared = true
        } finally {
            preparationWork += minOf((native.work - before).coerceAtLeast(0), Long.MAX_VALUE - preparationWork)
        }
    }

    fun execute(): QuadraticOptimizationResult = try {
        if (proofScope == null) partial() else {
            prepare()
            when (engine) {
                FiniteEngine.LOCAL_SEARCH -> heuristic(adaptive = false)
                FiniteEngine.ALNS -> heuristic(adaptive = true)
                FiniteEngine.MIXED -> error("mixed execution requires the shared invocation")
                FiniteEngine.FIXED, FiniteEngine.BACKTRACK -> complete()
            }
        }
    } catch (_: PolynomialInterrupted) {
        partial()
    } catch (stop: PolynomialResourceLimit) {
        native.decline(stop.resource)
        partial()
    } catch (_: CancellationException) {
        interrupted = true
        partial()
    } finally {
        close()
    }

    fun worker(arm: Int): PortfolioWorker = if (arm == 0) {
        PortfolioWorker.ofMinimize("bt+exact-quadratic", arm, resumable = { completeHandle() }) { _, _, _, _ ->
            error("quadratic traversal runs by slice")
        }.bindEvidence(identity)
    } else {
        PortfolioWorker.ofMinimize(if (arm == 2) "alns+exact-quadratic" else "ls+exact-quadratic", arm,
            countsInstructions = true, resumable = { neighborhoodHandle(arm == 2) }) { _, _, _, _ ->
            error("quadratic neighborhood runs by slice")
        }.bindEvidence(identity, provesResults = false)
    }

    private fun completeHandle(): QuadraticPortfolioSearch = object : QuadraticPortfolioSearch, ReseedGuard {
        override val canReseed: Boolean get() = false
        override val completion: QuadraticOptimizationResult? get() = terminal
        override val isDone: Boolean get() = terminal != null
        override val stats: SolveStats get() = this@QuadraticWorkerAdapter.stats
        override val work: Long get() = preparationWork + minOf(traversal?.work ?: 0L,
            Long.MAX_VALUE - preparationWork)
        override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long,
            onIncumbent: (MinimizeResult.WithSample) -> Unit): MinimizeResult? = run(onIncumbent) {
            advanceComplete(global, sliceMillis, sliceNodes)
        }
        override fun close() = this@QuadraticWorkerAdapter.close()
    }

    private fun neighborhoodHandle(adaptive: Boolean): QuadraticPortfolioSearch =
        object : QuadraticPortfolioSearch, InstructionSlicedSearch, ReseedGuard {
            override val canReseed: Boolean get() = false
            override val completion: QuadraticOptimizationResult? get() = terminal
            override val isDone: Boolean get() = terminal != null
            override val stats: SolveStats get() = this@QuadraticWorkerAdapter.stats
            override val work: Long get() = neighborhoodPreparationInstructions + minOf(neighborhood?.work ?: 0L,
                Long.MAX_VALUE - neighborhoodPreparationInstructions)
            override val chargedInstructions: Long get() = work
            override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long,
                onIncumbent: (MinimizeResult.WithSample) -> Unit): MinimizeResult? =
                runInstructionSlice(global, sliceMillis, sliceNodes, onIncumbent)
            override fun runInstructionSlice(global: Cancellation, sliceMillis: Long, sliceInstructions: Long,
                onIncumbent: (MinimizeResult.WithSample) -> Unit): MinimizeResult? = run(onIncumbent) {
                val cursor = neighborhood ?: kotlin.run {
                    val before = native.work
                    try {
                        neighborhood(adaptive)
                    } finally {
                        neighborhoodPreparationInstructions += minOf(
                            quadraticNativeInstructions((native.work - before).coerceAtLeast(0)),
                            Long.MAX_VALUE - neighborhoodPreparationInstructions,
                        )
                    }
                }
                val stop = native.polynomial or params.strategy.localSearch.cancellation or global
                val segment = stop or if (sliceMillis == Long.MAX_VALUE) Cancellation.Never
                    else Cancellation.after(sliceMillis.milliseconds)
                val done = cursor.advance(segment,
                    if (sliceInstructions < 0) Long.MAX_VALUE else sliceInstructions, global)
                if (done || stop()) terminal = partial()
            }
            override fun close() = this@QuadraticWorkerAdapter.close()
        }

    private fun run(onIncumbent: (MinimizeResult.WithSample) -> Unit, advance: () -> Unit): MinimizeResult? {
        terminal?.let { return scalar(it) }
        check(!closed) { "quadratic worker is closed" }
        publish = { onIncumbent(quadraticScalar(it, sense, stats)) }
        try {
            advance()
        } catch (_: PolynomialInterrupted) {
            terminal = partial()
        } catch (stop: PolynomialResourceLimit) {
            native.decline(stop.resource)
            terminal = partial()
        } catch (_: CancellationException) {
            interrupted = true
            terminal = partial()
        } finally {
            publish = null
        }
        return terminal?.let(::scalar)
    }

    private fun scalar(result: QuadraticOptimizationResult): MinimizeResult =
        (result as? QuadraticOptimizationResult.WithIncumbent)?.let { quadraticScalar(it.incumbent, sense, stats) }
            ?: MinimizeResult.Unknown(TerminationReason.BudgetExhausted, stats)

    private var closed = false
    override fun close() {
        if (closed) return
        closed = true
        try { traversal?.close() } finally {
            try { neighborhood?.close() } finally { sink.stop() }
        }
    }

    private fun better(left: AlgebraicReal, right: AlgebraicReal): Boolean =
        completion.algebraic.compare(left, right).let { if (sense == ObjectiveSense.MINIMIZE) it < 0 else it > 0 }

    private fun accept(candidate: QuadraticIncumbent, heuristic: Boolean = false) {
        if (incumbent == null || better(candidate.value, requireNotNull(incumbent).value)) {
            candidate.sample.witnessCertificate = EvidenceCertificate.verified(identity, EvidenceKind.Witness)
            incumbent = candidate
            onAccepted?.invoke(candidate)
            publish?.invoke(candidate)
            val approximate = candidate.value.rational?.toDouble()
                ?: (candidate.value.lowerBound.toDouble() / 2 + candidate.value.upperBound.toDouble() / 2)
            sink.ls.recordIncumbent(approximate, 0.0, sink.elapsedMs())
            val listener = if (heuristic) params.strategy.localSearch.onEvent else params.strategy.backtrack.onEvent
            listener?.invoke(SearchEvent.Incumbent(approximate))
        }
    }

    private fun complete(): QuadraticOptimizationResult {
        while (terminal == null && !native.stopped) advanceComplete(Cancellation.Never, Long.MAX_VALUE, -1)
        return terminal ?: partial()
    }

    private fun advanceComplete(global: Cancellation, millis: Long, nodes: Long) {
        if (proofScope == null || native.stopped) { terminal = partial(); return }
        prepare()
        readIncumbent()?.let { accept(it) }
        val stop = params.strategy.backtrack.cancellation or native.polynomial or global
        val cursor = traversal ?: QuadraticTraversalCursor(finite, params.strategy.backtrack.copy(
            assumptions = assumptions, cancellation = params.strategy.backtrack.cancellation or native.polynomial,
            minHammingDistance = 0, recentWindow = 0,
        ), sink, { native.work }) { sample, cancellation ->
            completion.check(sample, condition(sample), cancellation)
        }.also { traversal = it }
        val event = cursor.advance(global, millis, nodes) { result ->
            when (result) {
                is QuadraticBranchResult.Optimal -> record(result.incumbent, result.limit, true, result.proof)
                is QuadraticBranchResult.Limit -> record(result.incumbent, result.limit, false, result.proof)
                is QuadraticBranchResult.Unbounded -> {
                    accept(result.incumbent)
                    if (!stop()) terminal = QuadraticOptimizationResult.Unbounded(requireNotNull(incumbent),
                        certificate(result.proof, QuadraticClaim.Unbounded), sense, stats)
                }
                is QuadraticBranchResult.Infeasible -> branchProofs += result.proof
                QuadraticBranchResult.Unknown -> incomplete = true
            }
            terminal == null
        }
        if (terminal != null) return
        if (event is SearchOutcome.Exhausted && !event.indeterminate && !incomplete && !stop()) {
            terminal = completed(stop)
        } else if (event != null || stop()) terminal = partial()
    }

    private fun completed(stop: Cancellation): QuadraticOptimizationResult {
        val proofs = QuadraticEngineDerivation.Branches(branchProofs)
        val limit = bestLimit
        if (limit == null) return QuadraticOptimizationResult.Infeasible(
            certificate(proofs, QuadraticClaim.Infeasible), sense, stats,
        )
        val point = requireNotNull(incumbent)
        val bound = QuadraticObjectiveBound(limit, !limitAttained, certificate(proofs, QuadraticClaim.Bound))
        return if (limitAttained) {
            check(completion.algebraic.compare(point.value, limit) == 0)
            if (stop()) partial() else QuadraticOptimizationResult.Optimal(
                point, bound, certificate(proofs, QuadraticClaim.Optimal), sense, stats,
            )
        } else if (stop()) partial() else QuadraticOptimizationResult.UnattainedLimit(
            point, bound, certificate(proofs, QuadraticClaim.UnattainedLimit), sense, stats,
        )
    }

    private fun record(point: QuadraticIncumbent, limit: AlgebraicReal, attained: Boolean, proof: SourceDerivation) {
        accept(point)
        branchProofs += proof
        val current = bestLimit
        if (current == null || better(limit, current)) {
            bestLimit = limit
            limitAttained = attained
        } else if (completion.algebraic.compare(limit, current) == 0) {
            limitAttained = limitAttained || attained
        }
    }

    private fun certificate(proof: SourceDerivation, claim: QuadraticClaim): QuadraticCertificate =
        QuadraticCertificate.engine(identity, requireNotNull(proofScope), claim, proof)

    private fun partial(): QuadraticOptimizationResult {
        val worker = if (engine == FiniteEngine.LOCAL_SEARCH || engine == FiniteEngine.ALNS) {
            params.strategy.localSearch.cancellation
        } else params.strategy.backtrack.cancellation
        val reason = if (!native.stopped && (interrupted || worker())) TerminationReason.Cancelled else native.reason()
        return incumbent?.let {
            QuadraticOptimizationResult.BestFound(it, rootBound, reason, sense, stats)
        } ?: QuadraticOptimizationResult.Unknown(rootBound, reason, sense, stats)
    }

    private fun condition(
        sample: Sample,
        lower: Map<Int, BigFraction> = emptyMap(),
        upper: Map<Int, BigFraction> = emptyMap(),
    ): PolynomialCondition {
        val pins = model.integerColumns.map { (id, column) ->
            column to BigFraction.ofLong(sample.intValue(id))
        }.toMap()
        return PolynomialCondition(
            (0 until model.source.numBoolVars).associateWith(sample::boolValue), lower + pins, upper + pins,
        )
    }

    private fun neighborhood(adaptive: Boolean): QuadraticNeighborhoodCursor {
        neighborhood?.let { return it }
        prepare()
        val controls = params.strategy.localSearch
        val stop = controls.cancellation or native.polynomial
        val centers = completion.pointFixings(completion.algebraic.arithmetic::step)
        val maximum = if (adaptive) params.strategy.alnsIterations.toLong() else Long.MAX_VALUE
        val allowance = QuadraticNeighborhoodAllowance(maximum,
            minOf(controls.maxFlips, controls.maxInstructions ?: Long.MAX_VALUE), controls.nodeBudget)
        val total = sourceBoolCount + model.source.numIntVars + sourceRealCount
        return QuadraticNeighborhoodCursor(total, adaptive, controls.randomSeed ?: 0L, allowance, stop,
            { native.work }, propose = { released, radius, random ->
                readIncumbent()?.let { accept(it, heuristic = true) }
                val current = incumbent?.sample
                val pins = mutableMapOf<Int, Boolean>()
                val lower = mutableMapOf<Int, BigFraction>()
                val upper = mutableMapOf<Int, BigFraction>()
                for (id in 0 until sourceBoolCount) {
                    if (id !in released) {
                        pins[id] = assumptions.boolValueOrNull(id) ?: current?.boolValue(id) ?: random.nextBoolean()
                    }
                }
                for (id in 0 until model.source.numIntVars) {
                    if (sourceBoolCount + id !in released) {
                        val point = assumptions.intValueOrNull(id) ?: current?.intValue(id)
                            ?: model.source.finiteIntDomain(id).randomValue(random)
                        val value = BigFraction.ofLong(point)
                        lower[id] = value
                        upper[id] = value
                    }
                }
                val realStart = sourceBoolCount + model.source.numIntVars
                val scopedCenters = completion.conditionedPointFixings(pins + assumptions.bools) {
                    if (stop()) throw PolynomialInterrupted()
                    completion.algebraic.arithmetic.step()
                }
                for (id in 0 until sourceRealCount) {
                    if (current != null && released.any { it < realStart }) continue
                    val value = current?.algebraicReals?.get(id)
                    val center = scopedCenters[id] ?: centers[id]
                        ?: BigFraction.ofDouble(model.source.realLower[id])?.takeIf { it > BigFraction.ZERO }
                        ?: BigFraction.ofDouble(model.source.realUpper[id])?.takeIf { it < BigFraction.ZERO }
                        ?: BigFraction.ZERO
                    val spread = if (current == null || realStart + id in released) radius else BigFraction.ZERO
                    lower[model.source.numIntVars + id] = (value?.lowerBound ?: center) - spread
                    upper[model.source.numIntVars + id] = (value?.upperBound ?: center) + spread
                }
                ExactNeighborhoodCondition(pins, lower, upper)
            }, completion = repairCompletion, accept = {
                val point = requireNotNull(repairPoint)
                val improved = incumbent == null || better(point.value, requireNotNull(incumbent).value)
                accept(point, heuristic = true)
                improved
            }).also { neighborhood = it }
    }

    private fun heuristic(adaptive: Boolean): QuadraticOptimizationResult {
        val cursor = neighborhood(adaptive)
        cursor.advance(params.strategy.localSearch.cancellation or native.polynomial, Long.MAX_VALUE)
        return partial()
    }

    private fun repairNeighborhood(
        neighborhood: ExactNeighborhoodCondition,
        cancellation: Cancellation,
    ): ExactNeighborhoodResult {
        repairPoint = null
        var repair = assumptions
        for ((id, value) in neighborhood.bools) {
            if (assumptions.boolValueOrNull(id)?.let { it != value } == true) return ExactNeighborhoodResult.Refuted
            repair = repair.withBool(id, value)
        }
        for (id in 0 until model.source.numIntVars) {
            val value = neighborhood.lower[id]
            if (value != null && value == neighborhood.upper[id]) {
                val finite = value.num.toLongExact()
                check(BigFraction.ofLong(finite) == value)
                if (assumptions.intValueOrNull(id)?.let { it != finite } == true) return ExactNeighborhoodResult.Refuted
                repair = repair.withInt(id, finite)
            }
        }
        val lower = neighborhood.lower.filterKeys { it >= model.source.numIntVars }
            .mapKeys { it.key - model.source.numIntVars }
        val upper = neighborhood.upper.filterKeys { it >= model.source.numIntVars }
            .mapKeys { it.key - model.source.numIntVars }
        val controls = params.strategy.backtrack.copy(
            assumptions = repair, cancellation = cancellation, minHammingDistance = 0, recentWindow = 0,
            nodeBudget = params.strategy.localSearch.nodeBudget ?: params.strategy.backtrack.nodeBudget,
        )
        val cursor = CpSatisfactionTraversal(finite, controls, sink, LpSolveContext.Production)
        try {
            while (!cancellation()) {
                when (val event = cursor.next()) {
                    is SearchOutcome.Found -> {
                        val result = completion.check(event.sample, condition(event.sample, lower, upper), cancellation)
                        val point = when (result) {
                            is QuadraticBranchResult.Optimal -> result.incumbent
                            is QuadraticBranchResult.Limit -> result.incumbent
                            is QuadraticBranchResult.Unbounded -> result.incumbent
                            is QuadraticBranchResult.Infeasible -> null
                            QuadraticBranchResult.Unknown -> return ExactNeighborhoodResult.Undecided
                        }
                        if (point != null) {
                            if (repairPoint == null || better(point.value, requireNotNull(repairPoint).value)) {
                                repairPoint = point
                            }
                        }
                    }
                    is SearchOutcome.Exhausted -> return repairPoint?.let {
                        ExactNeighborhoodResult.Witness(it.sample)
                    } ?: if (event.indeterminate) ExactNeighborhoodResult.Undecided else ExactNeighborhoodResult.Refuted
                    SearchOutcome.BudgetCapped, null -> return ExactNeighborhoodResult.Undecided
                }
            }
            return ExactNeighborhoodResult.Undecided
        } finally {
            cursor.close()
        }
    }

}

internal fun quadraticAssumptions(params: PolynomialTheoryParams, engine: FiniteEngine): Assumptions {
    val cp = params.strategy.backtrack.assumptions.mergedWith(params.strategy.assumptions)
    val ls = params.strategy.localSearch.assumptions.mergedWith(params.strategy.assumptions)
    if (engine == FiniteEngine.MIXED) {
        require(cp.bools == ls.bools && cp.ints == ls.ints && sameDeductions(cp.deductions, ls.deductions)) {
            "quadratic mixed workers require the same invocation assumptions"
        }
    }
    return snapshot(if (engine == FiniteEngine.LOCAL_SEARCH || engine == FiniteEngine.ALNS) ls else cp)
}

private fun sameDeductions(left: DeducedRestrictions, right: DeducedRestrictions): Boolean =
    left.intMinKeys.contentEquals(right.intMinKeys) && left.intMinValues.contentEquals(right.intMinValues) &&
        left.intMaxKeys.contentEquals(right.intMaxKeys) && left.intMaxValues.contentEquals(right.intMaxValues) &&
        left.intHoleVarIds.contentEquals(right.intHoleVarIds) &&
        left.intHoleValues.contentEquals(right.intHoleValues) &&
        left.intSetKeys.contentEquals(right.intSetKeys) && left.intSetOffsets.contentEquals(right.intSetOffsets) &&
        left.intSetValues.contentEquals(right.intSetValues)

private fun snapshot(value: Assumptions): Assumptions {
    val d = value.deductions
    return Assumptions(
        value.boolKeys.copyOf(), value.boolValues.copyOf(), value.intKeys.copyOf(), value.intValues.copyOf(),
        DeducedRestrictions(
            d.intMinKeys.copyOf(), d.intMinValues.copyOf(), d.intMaxKeys.copyOf(), d.intMaxValues.copyOf(),
            d.intHoleVarIds.copyOf(), d.intHoleValues.copyOf(), d.intSetKeys.copyOf(),
            d.intSetOffsets.copyOf(), d.intSetValues.copyOf(),
        ),
    )
}
