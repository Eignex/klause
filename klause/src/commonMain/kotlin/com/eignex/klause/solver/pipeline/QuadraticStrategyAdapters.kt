package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
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
import com.eignex.klause.solver.incumbent.ModelIdentity
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
import kotlin.math.ceil
import kotlin.random.Random

private class QuadraticProofScope(
    val request: PolynomialTheoryRequest,
    val objective: QuadraticObjectiveSpec,
    val sense: ObjectiveSense,
    val assumptions: Assumptions,
)

internal class QuadraticStrategyAdapters(
    private val request: PolynomialTheoryRequest,
    private val model: SharedPolynomialModel,
    private val objective: QuadraticObjectiveSpec,
    private val sense: ObjectiveSense,
    private val params: PolynomialTheoryParams,
    private val native: QuadraticNativeScope,
    private val engine: FiniteEngine,
) {
    private val assumptions = snapshot(effectiveAssumptions())
    private val proofScope = QuadraticProofScope(request, objective, sense, assumptions)
    private val identity = ModelIdentity.of(model.source, proofScope)
    private val completion = QuadraticBranchCompletion(request, model, objective, sense, params, native)
    private val sink = SolveStatsSink("quadratic").also { it.start() }
    private val finite = model.finite.bake(native.polynomial)
    private val random = Random(params.strategy.localSearch.randomSeed ?: 0L)
    private var incumbent: QuadraticIncumbent? = null
    private var bestLimit: AlgebraicReal? = null
    private var limitAttained = false
    private val branchProofs = mutableListOf<Any>()
    private var incomplete = false
    private var repairPoint: QuadraticIncumbent? = null
    private var rootBound: QuadraticObjectiveBound? = null
    private val repairCompletion = ExactNeighborhoodCompletion { condition, cancellation ->
        repairNeighborhood(condition, cancellation)
    }

    fun execute(): QuadraticOptimizationResult = try {
        quadraticBoxBound(model.source, objective, assumptions, sense, completion.algebraic.arithmetic::step)?.let {
            val value = completion.algebraic.rational(it.first)
            if (!native.stopped) {
                rootBound = QuadraticObjectiveBound(value, false, certificate(it.second, QuadraticClaim.Bound))
            }
        }
        when (engine) {
            FiniteEngine.LOCAL_SEARCH -> heuristic(adaptive = false)
            FiniteEngine.ALNS -> heuristic(adaptive = true)
            FiniteEngine.MIXED -> {
                heuristic(adaptive = true, rounds = 2, share = 0.25)
                complete()
            }
            FiniteEngine.FIXED, FiniteEngine.BACKTRACK -> complete()
        }
    } catch (_: PolynomialInterrupted) {
        partial()
    } finally {
        sink.stop()
    }

    private fun better(left: AlgebraicReal, right: AlgebraicReal): Boolean =
        completion.algebraic.compare(left, right).let { if (sense == ObjectiveSense.MINIMIZE) it < 0 else it > 0 }

    private fun accept(candidate: QuadraticIncumbent, heuristic: Boolean = false) {
        if (incumbent == null || better(candidate.value, requireNotNull(incumbent).value)) {
            incumbent = candidate
            val approximate = candidate.value.rational?.toDouble() ?: Double.NaN
            sink.ls.recordIncumbent(approximate, 0.0, sink.elapsedMs())
            val listener = if (heuristic) params.strategy.localSearch.onEvent else params.strategy.backtrack.onEvent
            listener?.invoke(SearchEvent.Incumbent(approximate))
        }
    }

    private fun complete(): QuadraticOptimizationResult {
        if (native.stopped) return partial()
        val stop = params.strategy.backtrack.cancellation or native.polynomial
        val controls = params.strategy.backtrack.copy(
            assumptions = assumptions,
            cancellation = stop, minHammingDistance = 0, recentWindow = 0,
        )
        val cursor = CpSatisfactionTraversal(finite, controls, sink, LpSolveContext.Production)
        var exhausted = false
        try {
            while (!stop()) {
                when (val event = cursor.next()) {
                    is SearchOutcome.Found -> {
                        val result = completion.check(event.sample, condition(event.sample), stop)
                        when (result) {
                            is QuadraticBranchResult.Optimal -> record(
                                result.incumbent, result.limit, true, result.proof,
                            )
                            is QuadraticBranchResult.Limit -> record(
                                result.incumbent, result.limit, false, result.proof,
                            )
                            is QuadraticBranchResult.Unbounded -> {
                                accept(result.incumbent)
                                if (stop()) return partial()
                                return QuadraticOptimizationResult.Unbounded(
                                    result.incumbent, certificate(result.proof, QuadraticClaim.Unbounded),
                                    sense, sink.snapshot(),
                                )
                            }
                            is QuadraticBranchResult.Infeasible -> branchProofs += result.proof
                            QuadraticBranchResult.Unknown -> incomplete = true
                        }
                    }
                    is SearchOutcome.Exhausted -> {
                        exhausted = !event.indeterminate
                        break
                    }
                    SearchOutcome.BudgetCapped, null -> break
                }
            }
        } finally {
            cursor.close()
        }
        if (!exhausted || incomplete || stop()) return partial()
        val proofs = branchProofs.toList()
        val limit = bestLimit
        if (limit == null) return QuadraticOptimizationResult.Infeasible(
            certificate(proofs, QuadraticClaim.Infeasible), sense, sink.snapshot(),
        )
        val point = requireNotNull(incumbent)
        val bound = QuadraticObjectiveBound(limit, !limitAttained, certificate(proofs, QuadraticClaim.Bound))
        return if (limitAttained) {
            check(completion.algebraic.compare(point.value, limit) == 0)
            if (stop()) partial() else QuadraticOptimizationResult.Optimal(
                point, bound, certificate(proofs, QuadraticClaim.Optimal), sense, sink.snapshot(),
            )
        } else if (stop()) partial() else QuadraticOptimizationResult.UnattainedLimit(
            point, bound, certificate(proofs, QuadraticClaim.UnattainedLimit), sense, sink.snapshot(),
        )
    }

    private fun record(point: QuadraticIncumbent, limit: AlgebraicReal, attained: Boolean, proof: Any) {
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

    private fun certificate(proof: Any, claim: QuadraticClaim): QuadraticCertificate =
        QuadraticCertificate.verified(identity, proofScope, proof, claim)

    private fun partial(): QuadraticOptimizationResult {
        val worker = if (engine == FiniteEngine.LOCAL_SEARCH || engine == FiniteEngine.ALNS) {
            params.strategy.localSearch.cancellation
        } else params.strategy.backtrack.cancellation
        val reason = if (!native.stopped && worker()) TerminationReason.Cancelled else native.reason()
        return incumbent?.let {
            QuadraticOptimizationResult.BestFound(it, rootBound, reason, sense, sink.snapshot())
        } ?: QuadraticOptimizationResult.Unknown(rootBound, reason, sense, sink.snapshot())
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

    private fun heuristic(
        adaptive: Boolean,
        rounds: Long = Long.MAX_VALUE,
        share: Double = 1.0,
    ): QuadraticOptimizationResult {
        val controls = params.strategy.localSearch
        val maximum = minOf(rounds, if (adaptive) params.strategy.alnsIterations.toLong() else Long.MAX_VALUE)
        val instructionLimit = minOf(controls.maxFlips, controls.maxInstructions ?: Long.MAX_VALUE)
        val start = native.work
        val allowance = params.maximumWork?.let { ((it - start).coerceAtLeast(0) * share).toLong() }
        val slice = Cancellation { allowance?.let { native.work - start >= it } == true }
        val stop = native.polynomial or controls.cancellation.shorten(share) or slice
        var radius = BigFraction.ONE
        var moves = 0L
        var instructions = 0L
        var stalls = 0L
        val weights = DoubleArray(3) { 1.0 }
        while (!stop() && moves < maximum && instructions < instructionLimit &&
            controls.nodeBudget?.exhausted() != true
        ) {
            val total = model.source.numBoolVars + model.source.numIntVars + model.source.numRealVars
            val operator = if (!adaptive) 0 else random.nextInt(weights.size).let { draw ->
                if (random.nextDouble() < weights[draw] / weights.maxOrNull()!!) draw else 2
            }
            val amount = when (operator) { 0 -> 1; 1 -> maxOf(1, total / 2); else -> total }
            val released = if (moves == 0L) emptySet()
                else (0 until total).shuffled(random).take(amount).toSet()
            val current = incumbent?.sample
            val pins = mutableMapOf<Int, Boolean>()
            val lower = mutableMapOf<Int, BigFraction>()
            val upper = mutableMapOf<Int, BigFraction>()
            for (id in 0 until model.source.numBoolVars) {
                if (id !in released) {
                    pins[id] = assumptions.boolValueOrNull(id) ?: current?.boolValue(id) ?: random.nextBoolean()
                }
            }
            for (id in 0 until model.source.numIntVars) {
                if (model.source.numBoolVars + id !in released) {
                    val point = assumptions.intValueOrNull(id) ?: current?.intValue(id)
                        ?: model.source.finiteIntDomain(id).randomValue(random)
                    val value = BigFraction.ofLong(point)
                    lower[id] = value
                    upper[id] = value
                }
            }
            val realStart = model.source.numBoolVars + model.source.numIntVars
            for (id in 0 until model.source.numRealVars) {
                val value = current?.algebraicReals?.get(id)
                val center = BigFraction.ofDouble(model.source.realLower[id])?.takeIf { it > BigFraction.ZERO }
                    ?: BigFraction.ofDouble(model.source.realUpper[id])?.takeIf { it < BigFraction.ZERO }
                    ?: BigFraction.ZERO
                val spread = if (current == null || realStart + id in released) radius else BigFraction.ZERO
                lower[model.source.numIntVars + id] = (value?.lowerBound ?: center) - spread
                upper[model.source.numIntVars + id] = (value?.upperBound ?: center) + spread
            }
            val before = native.work
            val repaired = repairCompletion.check(ExactNeighborhoodCondition(pins, lower, upper), stop)
            val found = repairPoint
            val refuted = repaired is ExactNeighborhoodResult.Refuted
            moves++
            val cost = 1L + ceil((native.work - before).coerceAtLeast(0) * 0.8).toLong()
            instructions += minOf(cost, Long.MAX_VALUE - instructions)
            controls.nodeBudget?.spendMoves(cost)
            val improved = found?.let { candidate ->
                incumbent == null || better(candidate.value, requireNotNull(incumbent).value)
            } == true
            found?.let { accept(it, heuristic = true) }
            sink.ls.recordCompletion(refuted, found == null && !refuted)
            if (improved) radius = BigFraction.ONE else { radius += radius; stalls++ }
            if (adaptive) weights[operator] = weights[operator] * 0.8 + if (improved) 1.0 else 0.2
            sink.ls.recordWork(moves, 0, stalls)
            if (total == 0) break
        }
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

    private fun effectiveAssumptions(): Assumptions {
        val cp = params.strategy.backtrack.assumptions.mergedWith(params.strategy.assumptions)
        val ls = params.strategy.localSearch.assumptions.mergedWith(params.strategy.assumptions)
        if (engine == FiniteEngine.MIXED) {
            require(cp.bools == ls.bools && cp.ints == ls.ints && sameDeductions(cp.deductions, ls.deductions)) {
                "quadratic mixed workers require the same invocation assumptions"
            }
        }
        return if (engine == FiniteEngine.LOCAL_SEARCH || engine == FiniteEngine.ALNS) ls else cp
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
}
