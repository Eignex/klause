package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.arithmetic.polynomial.ceilInteger
import com.eignex.klause.arithmetic.polynomial.exactInteger
import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.objective.evaluateExact
import com.eignex.klause.solver.objective.openPolynomial
import com.eignex.klause.solver.result.QuadraticCertificate
import com.eignex.klause.solver.result.QuadraticClaim
import com.eignex.klause.solver.result.QuadraticIncumbent
import com.eignex.klause.solver.result.QuadraticObjectiveBound
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.search.BooleanBranching
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecisionBudget
import com.eignex.klause.solver.search.SearchRunEvent
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SearchSolveParams
import com.eignex.klause.theory.qfnia.QuadraticIntegerComponent
import com.eignex.klause.theory.qfnia.QuadraticIntegerNode
import com.eignex.klause.theory.qfnia.checkNonlinearSource
import com.eignex.klause.theory.qfnia.integerBounds
import com.eignex.klause.theory.qfnia.integerSeeds
import com.eignex.klause.theory.qfnra.PolynomialCondition
import com.eignex.klause.theory.qfnra.QfNraAssignment
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.minus
import com.eignex.klause.util.toLongExact
import com.eignex.klause.util.unaryMinus
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlin.random.Random

/**
 * Optimize a quadratic objective over exact open integer and real source coordinates.
 * Shared search splits at arbitrary-precision integer thresholds; heuristic neighborhoods supply incumbents only.
 */
fun OpenTheoryPipeline.minimize(
    request: NonlinearIntegerTheoryRequest,
    objective: QuadraticObjectiveSpec,
    params: NonlinearIntegerTheoryParams = NonlinearIntegerTheoryParams(),
    engine: FiniteEngine = FiniteEngine.MIXED,
    sense: ObjectiveSense = ObjectiveSense.MINIMIZE,
): QuadraticOptimizationExecution {
    val native = QuadraticNativeScope(params.polynomial)
    var result: QuadraticOptimizationResult = QuadraticOptimizationResult.Unknown(
        null, native.reason(), sense, SolveStats.EMPTY,
    )
    if (!native.stopped) {
        result = QuadraticIntegerOptimization(request.problem, objective, params, engine, sense, native).execute()
    }
    return QuadraticOptimizationExecution(
        result, native.polynomialStats, native.numericalWork, native.work, native.resource,
    )
}

private class QuadraticIntegerProofScope(
    val source: NonlinearIntegerProblem,
    val objective: QuadraticObjectiveSpec,
    val sense: ObjectiveSense,
    val condition: PolynomialCondition,
)

internal class QuadraticIntegerOptimization(
    private val source: NonlinearIntegerProblem,
    private val objective: QuadraticObjectiveSpec,
    private val params: NonlinearIntegerTheoryParams,
    private val engine: FiniteEngine,
    private val sense: ObjectiveSense,
    private val native: QuadraticNativeScope,
) {
    private val controls = params.polynomial
    private val budget = params.nodeBudget ?: controls.strategy.backtrack.nodeBudget
        ?: controls.strategy.localSearch.nodeBudget
    private val preparation = PolynomialArithmetic(
        native.polynomial, PolynomialLimits(controls.maximumDenseCoefficients, controls.maximumMatrixCells),
    )
    private lateinit var relaxation: QuadraticSourceRelaxation
    private val algebraic: AlgebraicArithmetic get() = relaxation.algebraic
    private lateinit var root: PolynomialCondition
    private lateinit var scope: QuadraticIntegerProofScope
    private lateinit var identity: ModelIdentity
    private val sink = SolveStatsSink("quadratic-integer").also { it.start() }
    private val random = Random(controls.strategy.localSearch.randomSeed ?: 1L)
    private var incumbent: QuadraticIncumbent? = null
    private var rootBound: QuadraticObjectiveBound? = null
    private var limit: AlgebraicReal? = null
    private var attained = false
    private val proofs = mutableListOf<Any>()
    private var interrupted = false
    private var neighborhoodPoint: QuadraticIncumbent? = null
    private var globalUnbounded: Any? = null

    fun execute(): QuadraticOptimizationResult = try {
        preparation.step()
        val expression = objective.openPolynomial(source, preparation::step)
        val canonical = if (sense == ObjectiveSense.MINIMIZE) expression.expression else PolynomialExpression.Product(
            listOf(PolynomialExpression.Constant.of(bigIntOf(-1)), expression.expression),
        )
        relaxation = QuadraticSourceRelaxation(
            expression.formula, canonical, source.boolCount, expression.numericCount, controls, native,
        )
        root = source.integerBounds(assumptions(), relaxation.arithmetic)
        scope = QuadraticIntegerProofScope(source, objective, sense, root)
        identity = ModelIdentity.of(source, scope)
        when (engine) {
            FiniteEngine.LOCAL_SEARCH -> { walk(false); partial() }
            FiniteEngine.ALNS -> { walk(true); partial() }
            FiniteEngine.MIXED -> {
                try {
                    walk(true, rounds = 2, share = 0.25)
                } catch (_: PolynomialInterrupted) {
                    Unit
                } catch (_: CancellationException) {
                    Unit
                }
                complete()
            }
            FiniteEngine.BACKTRACK, FiniteEngine.FIXED -> complete()
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
        sink.stop()
    }

    private fun assumptions(): PolynomialCondition {
        fun pins(local: Boolean): PolynomialCondition {
            val worker = if (local) controls.strategy.localSearch.assumptions
                else controls.strategy.backtrack.assumptions
            val fixed = worker.mergedWith(controls.strategy.assumptions)
            require(fixed.deductions.isEmpty) { "quadratic open integer assumptions require explicit pins" }
            require(fixed.bools.keys.all { it in 0 until source.boolCount }) { "quadratic Boolean pin out of range" }
            val integers = fixed.ints.map { (id, value) ->
                require(id in source.integerCoordinates.indices) { "quadratic integer pin out of range" }
                source.integerCoordinates[id] to BigFraction.ofLong(value)
            }.toMap()
            return PolynomialCondition(fixed.bools.toMap(), integers, integers)
        }
        val local = engine == FiniteEngine.LOCAL_SEARCH || engine == FiniteEngine.ALNS
        val selected = pins(local)
        if (engine == FiniteEngine.MIXED) require(selected == pins(true)) {
            "quadratic mixed workers require the same invocation assumptions"
        }
        return selected
    }

    private fun complete(): QuadraticOptimizationResult {
        val stop = native.polynomial or controls.strategy.backtrack.cancellation or Cancellation {
            budget?.exhausted() == true
        }
        if (stop() || params.maximumCandidates == 0L) return partial()
        val exhausted = traverse(root, stop, global = true)
        globalUnbounded?.let { proof ->
            if (!stop()) return QuadraticOptimizationResult.Unbounded(
                requireNotNull(incumbent), certificate(proof, QuadraticClaim.Unbounded), sense, sink.snapshot(),
            )
        }
        if (!exhausted || stop()) return partial()
        val best = limit
        if (best == null) return QuadraticOptimizationResult.Infeasible(
            certificate(proofs.toList(), QuadraticClaim.Infeasible), sense, sink.snapshot(),
        )
        val bound = QuadraticObjectiveBound(
            sourceValue(best), !attained, certificate(proofs.toList(), QuadraticClaim.Bound),
        )
        if (attained) {
            check(algebraic.compare(requireNotNull(incumbent).value, bound.value) == 0)
            if (stop()) return partial()
            return QuadraticOptimizationResult.Optimal(
                requireNotNull(incumbent), bound, certificate(proofs.toList(), QuadraticClaim.Optimal),
                sense, sink.snapshot(),
            )
        }
        return if (stop()) partial() else QuadraticOptimizationResult.UnattainedLimit(
            requireNotNull(incumbent), bound, certificate(proofs.toList(), QuadraticClaim.UnattainedLimit),
            sense, sink.snapshot(),
        )
    }

    private fun traverse(condition: PolynomialCondition, stop: Cancellation, global: Boolean): Boolean {
        if (stop() || params.maximumCandidates == 0L) return false
        val component = QuadraticIntegerComponent(source, condition, relaxation.arithmetic::step) { node, context ->
            sink.search.observeNode(context.decisionLevel)
            classify(node, context, stop, global)
        }
        val session = SearchSession(
            listOf(component), cancellation = stop, branchers = listOf(component),
            atoms = SearchAtomRegistry(source.boolCount),
        )
        try {
            when (session.initialize()) {
                is ComponentResult.Conflict -> return !stop()
                ComponentResult.Indeterminate -> return false
                ComponentResult.Consistent -> Unit
            }
            val run = session.openRun(
                source.boolCount, SearchSolveParams(maxDecisions = minOf(
                    params.maximumCandidates, controls.strategy.backtrack.maxDecisions,
                    controls.strategy.backtrack.maxInstructions ?: Long.MAX_VALUE,
                )), BooleanBranching.None,
                decisionBudget = SearchDecisionBudget {
                    if (budget?.exhausted() == true) false else { budget?.spend(); true }
                }, nodePolicy = component,
            )
            while (!stop()) {
                when (val event = run.next()) {
                    is SearchRunEvent.Satisfied -> {
                        val node = requireNotNull(event.model.valueOf(component.modelKey))
                        val point = capture(node.assignment)
                        accept(point, heuristic = !global)
                        if (!global && (neighborhoodPoint == null ||
                                better(point.value, requireNotNull(neighborhoodPoint).value))
                        ) {
                            neighborhoodPoint = point
                        }
                        if (global) record(node.value, node.attained, node.proof)
                    }
                    SearchRunEvent.Exhausted -> return !stop()
                    SearchRunEvent.Paused -> {
                        val node = component.outcome as? QuadraticIntegerNode.Unbounded ?: return false
                        accept(capture(node.assignment), heuristic = !global)
                        if (global) globalUnbounded = node.proof
                        return false
                    }
                    is SearchRunEvent.Indeterminate -> return false
                }
            }
            return false
        } finally {
            session.popTo(0)
        }
    }

    private fun classify(
        condition: PolynomialCondition,
        context: SearchContext,
        stop: Cancellation,
        global: Boolean,
    ): QuadraticIntegerNode {
        val result = relaxation.check(condition, stop, mandatory = false)
        if (result is QuadraticRelaxationResult.Infeasible) {
            if (global) proofs += result
            return QuadraticIntegerNode.Empty(result)
        }
        val fixed = source.integerCoordinates.all {
            condition.lower[it] != null && condition.lower[it] == condition.upper[it]
        }
        val assignment = when (result) {
            is QuadraticRelaxationResult.Minimum -> result.assignment
            is QuadraticRelaxationResult.Unbounded -> result.assignment
            else -> null
        }
        if (global && context.decisionLevel == 0 && result is QuadraticRelaxationResult.Minimum && !stop()) {
            rootBound = QuadraticObjectiveBound(
                sourceValue(result.value), !result.attained, certificate(result, QuadraticClaim.Bound),
            )
        }
        val exact = assignment?.let { point ->
            source.integerCoordinates.map { algebraic.exactInteger(point.realValue(it)) }
        }
        val integral = exact?.all { it != null } == true
        if (integral && assignment != null) accept(capture(assignment), heuristic = !global)
        if (result is QuadraticRelaxationResult.Minimum) {
            if (result.attained && integral) return QuadraticIntegerNode.Closed(
                result.assignment, result.value, true, result.proof,
            )
            val best = incumbent?.value?.let { if (sense == ObjectiveSense.MINIMIZE) it else algebraic.negate(it) }
            if (best != null && algebraic.compare(result.value, best) >= 0) {
                if (global) { record(best, true, result.proof); proofs += condition }
                return QuadraticIntegerNode.Pruned(result)
            }
            if (fixed) {
                check(integral) { "fixed source integer completion is fractional" }
                return QuadraticIntegerNode.Closed(result.assignment, result.value, result.attained, result.proof)
            }
        }
        if (result is QuadraticRelaxationResult.Unbounded && fixed && integral) {
            return QuadraticIntegerNode.Unbounded(result.assignment, result.proof)
        }
        val proposed = source.integerCoordinates.associateWith { id ->
            val value = assignment?.realValue(id)?.let(algebraic::ceilInteger)
                ?: condition.lower[id]?.ceilInteger() ?: condition.upper[id]?.let { -it.negated().ceilInteger() }
                ?: bigIntOf(0)
            BigFraction.of(value, BIG_ONE)
        }
        val candidate = relaxation.check(intersect(condition, proposed, proposed), stop, mandatory = false)
        when (candidate) {
            is QuadraticRelaxationResult.Minimum -> {
                accept(capture(candidate.assignment), heuristic = !global)
                if (result is QuadraticRelaxationResult.Unbounded) {
                    relaxation.integerRecession(condition, candidate.assignment, source.integerCoordinates, stop)?.let {
                        return QuadraticIntegerNode.Unbounded(candidate.assignment, it)
                    }
                }
                if (result is QuadraticRelaxationResult.Minimum && integral &&
                    algebraic.compare(candidate.value, result.value) == 0
                ) return QuadraticIntegerNode.Closed(
                    candidate.assignment, result.value, candidate.attained, result.proof to candidate.proof,
                )
            }
            is QuadraticRelaxationResult.Unbounded -> {
                accept(capture(candidate.assignment), heuristic = !global)
                return QuadraticIntegerNode.Unbounded(candidate.assignment, candidate.proof)
            }
            else -> Unit
        }
        if (result is QuadraticRelaxationResult.Unknown || fixed || stop()) {
            if (global && result is QuadraticRelaxationResult.Unknown) result.resource?.let(native::decline)
            return QuadraticIntegerNode.Unknown
        }
        for ((index, id) in source.integerCoordinates.withIndex()) {
            if (condition.lower[id] != null && condition.lower[id] == condition.upper[id]) continue
            val value = requireNotNull(assignment).realValue(id)
            val integer = exact?.get(index)
            if (integer == null) return QuadraticIntegerNode.Split(id, algebraic.ceilInteger(value) - BIG_ONE)
        }
        val id = source.integerCoordinates.first {
            condition.lower[it] == null || condition.lower[it] != condition.upper[it]
        }
        val value = proposed.getValue(id).num
        val threshold = if (condition.lower[id]?.let { it >= proposed.getValue(id) } == true) value else value - BIG_ONE
        return QuadraticIntegerNode.Split(id, threshold)
    }

    private fun capture(assignment: QfNraAssignment): QuadraticIncumbent {
        check(checkNonlinearSource(source.formula, assignment, relaxation.arithmetic)) {
            "quadratic integer source check failed"
        }
        val integers = source.integerCoordinates.map {
            requireNotNull(algebraic.exactInteger(assignment.realValue(it)))
        }
        val reals = source.realCoordinates.map(assignment::realValue)
        val sample = Sample(
            assignment.bools,
            if (integers.all { it.fitsLong() }) LongArray(integers.size) { integers[it].toLongExact() }
                else longArrayOf(),
            DoubleArray(reals.size) { id ->
                val value = reals[id]
                value.rational?.toDouble() ?: (value.lowerBound.toDouble() / 2 + value.upperBound.toDouble() / 2)
            }, exactInts = integers, algebraicReals = reals,
        )
        return QuadraticIncumbent(sample, objective.evaluateExact(sample, algebraic))
    }

    private fun record(value: AlgebraicReal, closedAttained: Boolean, proof: Any) {
        proofs += proof
        val previous = limit
        val comparison = previous?.let { algebraic.compare(value, it) } ?: -1
        if (comparison < 0) { limit = value; attained = closedAttained }
        else if (comparison == 0) attained = attained || closedAttained
    }

    private fun better(left: AlgebraicReal, right: AlgebraicReal): Boolean = algebraic.compare(left, right).let {
        if (sense == ObjectiveSense.MINIMIZE) it < 0 else it > 0
    }

    private fun accept(point: QuadraticIncumbent, heuristic: Boolean) {
        if (incumbent == null || better(point.value, requireNotNull(incumbent).value)) {
            incumbent = point
            val approximate = point.value.rational?.toDouble()
                ?: (point.value.lowerBound.toDouble() / 2 + point.value.upperBound.toDouble() / 2)
            sink.ls.recordIncumbent(approximate, 0.0, sink.elapsedMs())
            val listener = if (heuristic) controls.strategy.localSearch.onEvent else controls.strategy.backtrack.onEvent
            listener?.invoke(SearchEvent.Incumbent(approximate))
        }
    }

    private fun sourceValue(value: AlgebraicReal): AlgebraicReal =
        if (sense == ObjectiveSense.MINIMIZE) value else algebraic.negate(value)
    private fun certificate(proof: Any, claim: QuadraticClaim): QuadraticCertificate =
        QuadraticCertificate.verified(identity, scope, proof, claim)

    private fun partial(): QuadraticOptimizationResult {
        val worker = if (engine == FiniteEngine.LOCAL_SEARCH || engine == FiniteEngine.ALNS) {
            controls.strategy.localSearch.cancellation
        } else controls.strategy.backtrack.cancellation
        val reason = if (interrupted || worker()) TerminationReason.Cancelled else native.reason()
        return incumbent?.let { QuadraticOptimizationResult.BestFound(it, rootBound, reason, sense, sink.snapshot()) }
            ?: QuadraticOptimizationResult.Unknown(rootBound, reason, sense, sink.snapshot())
    }

    private fun intersect(
        condition: PolynomialCondition, lower: Map<Int, BigFraction>, upper: Map<Int, BigFraction>,
    ): PolynomialCondition {
        fun merge(existing: Map<Int, BigFraction>, added: Map<Int, BigFraction>, minimum: Boolean) =
            existing.toMutableMap().also { bounds ->
                for ((id, value) in added) {
                    relaxation.arithmetic.step()
                    val old = bounds[id]
                    if (old == null || if (minimum) value > old else value < old) bounds[id] = value
                }
            }
        return PolynomialCondition(
            condition.bools, merge(condition.lower, lower, true), merge(condition.upper, upper, false),
        )
    }

    private fun walk(adaptive: Boolean, rounds: Long = Long.MAX_VALUE, share: Double = 1.0) {
        val worker = controls.strategy.localSearch
        val maximum = minOf(rounds, params.maximumMoves,
            if (adaptive) controls.strategy.alnsIterations.toLong() else Long.MAX_VALUE)
        val instructionLimit = minOf(worker.maxFlips, worker.maxInstructions ?: Long.MAX_VALUE)
        val start = native.work
        val allowance = controls.maximumWork?.let { ((it - start).coerceAtLeast(0) * share).toLong() }
        val stop = native.polynomial or worker.cancellation.shorten(share) or Cancellation {
            budget?.exhausted() == true || allowance?.let { native.work - start >= it } == true
        }
        if (stop() || maximum == 0L || instructionLimit == 0L) return
        val seeds = source.integerSeeds(stop, relaxation.arithmetic::step)
        var radius = BigFraction.ONE
        var moves = 0L
        var instructions = 0L
        var stalls = 0L
        val weights = DoubleArray(3) { 1.0 }
        val completion = ExactNeighborhoodCompletion { neighborhood, cancellation ->
            neighborhoodPoint = null
            fun coordinate(id: Int): Int = if (id < source.integerCoordinates.size) source.integerCoordinates[id]
                else source.realCoordinates[id - source.integerCoordinates.size]
            val lower = neighborhood.lower.mapKeys { coordinate(it.key) }
            val upper = neighborhood.upper.mapKeys { coordinate(it.key) }
            if (neighborhood.bools.any { (id, value) -> root.bools[id]?.let { it != value } == true }) {
                return@ExactNeighborhoodCompletion ExactNeighborhoodResult.Refuted
            }
            val numeric = intersect(root, lower, upper)
            val condition = PolynomialCondition(root.bools + neighborhood.bools, numeric.lower, numeric.upper)
            traverse(condition, cancellation, global = false)
            neighborhoodPoint?.let { ExactNeighborhoodResult.Witness(it.sample) } ?: ExactNeighborhoodResult.Undecided
        }
        while (!stop() && moves < maximum && instructions < instructionLimit) {
            val before = native.work
            val previous = incumbent
            val intCount = source.integerCoordinates.size
            val total = source.boolCount + intCount + source.realCoordinates.size
            val operator = if (!adaptive) 0 else random.nextInt(weights.size).let { draw ->
                if (random.nextDouble() < weights[draw] / weights.maxOrNull()!!) draw else 2
            }
            val amount = when (operator) { 0 -> 1; 1 -> maxOf(1, total / 2); else -> total }
            val released = if (!adaptive) setOf((moves % total).toInt())
                else (0 until total).shuffled(random).take(amount).toSet()
            val lower = mutableMapOf<Int, BigFraction>()
            val upper = mutableMapOf<Int, BigFraction>()
            val bools = mutableMapOf<Int, Boolean>()
            for (id in 0 until source.boolCount) {
                if (id !in released) {
                    bools[id] = root.bools[id] ?: incumbent?.sample?.boolValue(id) ?: random.nextBoolean()
                }
            }
            for (id in 0 until intCount) {
                val center = incumbent?.sample?.exactIntValue(id)?.let { BigFraction.of(it, BIG_ONE) } ?: seeds[id]
                val spread = if (source.boolCount + id in released) radius else BigFraction.ZERO
                lower[id] = center - spread
                upper[id] = center + spread
            }
            incumbent?.sample?.algebraicReals?.forEachIndexed { id, value ->
                val spread = if (source.boolCount + intCount + id in released) radius else BigFraction.ZERO
                lower[intCount + id] = value.lowerBound - spread
                upper[intCount + id] = value.upperBound + spread
            }
            try {
                completion.check(ExactNeighborhoodCondition(bools, lower, upper), stop)
            } finally {
                moves++
                val cost = 1L + ceil((native.work - before).coerceAtLeast(0) * 0.8).toLong()
                instructions += minOf(cost, Long.MAX_VALUE - instructions)
                budget?.spendMoves(cost)
                sink.ls.recordCompletion(false, neighborhoodPoint == null)
                sink.ls.recordWork(moves, 0, stalls)
            }
            val improved = incumbent !== previous
            if (improved) radius = BigFraction.ONE else { radius += radius; stalls++ }
            if (adaptive) weights[operator] = weights[operator] * 0.8 + if (improved) 1.0 else 0.2
            sink.ls.recordWork(moves, 0, stalls)
        }
    }
}
