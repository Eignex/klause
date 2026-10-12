package com.eignex.klause.cli

import com.eignex.klause.formats.smtlib.SmtLibAdmission
import com.eignex.klause.formats.smtlib.SmtLibInput
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.SourceProblemRoute
import com.eignex.klause.solver.pipeline.algebraicRealValue
import com.eignex.klause.solver.pipeline.pipelineRequest
import com.eignex.klause.solver.pipeline.pipelineRoute
import com.eignex.klause.solver.result.LpStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.theory.qfnra.ExactNraAssignment
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.plus
import com.eignex.klause.util.times
import com.eignex.klause.util.unaryMinus
import kotlin.time.TimeSource

/**
 * SMT-LIB 2 front-end (`.smt2` / `.smt`; linear arithmetic and polynomial reals). Emits the SMT-LIB convention: a
 * `sat` / `unsat` / `unknown` status line, followed (when sat) by a `(get-model)`-style
 * `(define-fun …)` block. `-s` statistics are emitted as `;` comment lines.
 */
internal object SmtLibMode : CliMode {
    override val names = listOf("smtlib", "smt", "smt2")
    override val extensions = listOf("smt2", "smt")
    override fun newSession(): ModeSession = Session()

    private class Session : ModeSession {
        private var latestSourceObjective: BigFraction? = null

        override fun flags(): List<FlagSpec> = emptyList()

        override fun load(path: String, common: CommonOptions): Solvable {
            val config = common.config
            latestSourceObjective = null
            val admissionStart = TimeSource.Monotonic.markNow()
            val stop = Cancellation { common.deadlineAtMs?.let { nowMillis() >= it } == true }
            val admitted = SmtLibAdmission.parse(
                { openFileSource(path) },
                config.unboundedIntLo,
                config.unboundedIntHi,
                settings = config.problemSettings(),
                cancellation = stop,
            )
            val parsed = when (admitted) {
                is SmtLibInput.Linear -> admitted.problem
                is SmtLibInput.Polynomial -> {
                    common.takeOpenBoundProof()
                    val source = admitted.problem
                    val bools = source.boolVarNames.mapKeys { smtSourceSymbol(it.key) }
                    val reals = source.realVarNames.mapKeys { smtSourceSymbol(it.key) }
                    return polynomialSolvable(
                        source.pipelineRequest(),
                        { assignment -> renderOpenTheoryModel(emptyMap(), bools, reals, assignment) },
                        admissionStart.elapsedNow().inWholeMilliseconds,
                    )
                }

                is SmtLibInput.NonlinearInteger -> {
                    common.takeOpenBoundProof()
                    val source = admitted.problem
                    val ints = source.intVarNames.mapKeys { smtSourceSymbol(it.key) }
                    val bools = source.boolVarNames.mapKeys { smtSourceSymbol(it.key) }
                    val reals = source.realVarNames.mapKeys { smtSourceSymbol(it.key) }
                    return nonlinearIntegerSolvable(
                        source.pipelineRequest(),
                        { assignment -> renderOpenTheoryModel(ints, bools, reals, assignment) },
                        admissionStart.elapsedNow().inWholeMilliseconds,
                    )
                }

                is SmtLibInput.Quadratic -> {
                    common.takeOpenBoundProof()
                    return quadraticSmtSolvable(admitted, admissionStart.elapsedNow().inWholeMilliseconds)
                }

                SmtLibInput.Interrupted -> return interruptedSolvable(
                    if (stop()) TerminationReason.Timeout else TerminationReason.Cancelled,
                    admissionStart.elapsedNow().inWholeMilliseconds,
                )
            }
            cliLogger(common.verbose).v {
                "parsed ${fileName(path)}: bool=${parsed.model.numBoolVars} int=${parsed.model.numIntVars} " +
                    "real=${parsed.model.numRealVars} factors=${parsed.model.factors.size}"
            }
            val ints = parsed.intVarNames
            val bools = parsed.boolVarNames
            val reals = parsed.realVarNames
            val minimizedObjective = parsed.objective?.toLinearObjective()
            val maximize = parsed.sense == ObjectiveSense.MAXIMIZE
            val objective = if (maximize) minimizedObjective?.negated() else minimizedObjective
            val render: (Sample) -> String = { sample ->
                latestSourceObjective = objective?.evaluateExact(sample)
                renderModel(ints, bools, reals, sample)
            }
            var routingLpStats = LpStats()
            val routingStart = TimeSource.Monotonic.markNow()
            val route = parsed.model.pipelineRoute(
                objective,
                parsed.sense == ObjectiveSense.MAXIMIZE,
                routePureRealToTheory = true,
                routeLinearToTheory = objective?.requiresExactRoute(parsed.model) == true,
                boundCancellation = common.routingCancellation(),
                onLpStats = { routingLpStats = it },
            )
            val routingElapsedMs = routingStart.elapsedNow().inWholeMilliseconds
            common.logRoute(route, routingElapsedMs)
            return when (route) {
                is SourceProblemRoute.PolynomialTheory -> polynomialSolvable(
                    route, { assignment -> renderOpenTheoryModel(ints, bools, reals, assignment) }, routingElapsedMs,
                )
                is SourceProblemRoute.Finite -> linearSolvable(
                    route.problem,
                    minimizedObjective,
                    maximize,
                    render,
                    routingLpStats = routingLpStats,
                    routingElapsedMs = routingElapsedMs,
                )

                // An objective the descent can minimize goes to it; any other leaves the theory to local search.
                is SourceProblemRoute.OpenTheory ->
                    if (route.request.objective == null || OpenTheoryPipeline.canMinimize(route.request)) {
                        openTheorySolvable(
                            route.request,
                            { assignment -> renderOpenTheoryModel(ints, bools, reals, assignment) },
                            routingLpStats,
                            routingElapsedMs,
                        )
                    } else {
                        openLocalSearchSmt(
                            route.request.model,
                            objective,
                            parsed.sense,
                            ints,
                            bools,
                            reals,
                            routingLpStats,
                            routingElapsedMs,
                        )
                    }

                is SourceProblemRoute.UnsupportedOpen ->
                    openLocalSearchSmt(
                        route.problem,
                        objective,
                        parsed.sense,
                        ints,
                        bools,
                        reals,
                        routingLpStats,
                        routingElapsedMs,
                    )

                SourceProblemRoute.Refuted -> refutedSolvable(routingLpStats, routingElapsedMs)
            }
        }

        override fun output(common: CommonOptions): OutputProtocol = SmtLibOutput { latestSourceObjective }
    }
}

private fun quadraticSmtSolvable(input: SmtLibInput.Quadratic, admissionElapsedMs: Long): Solvable {
    val request: QuadraticRequest
    val ints: Map<String, Int>
    val bools: Map<String, Int>
    val reals: Map<String, Int>
    when (val source = input.source) {
        is SmtLibInput.Polynomial -> {
            request = QuadraticRequest.Polynomial(source.problem.pipelineRequest())
            ints = emptyMap()
            bools = source.problem.boolVarNames.mapKeys { smtSourceSymbol(it.key) }
            reals = source.problem.realVarNames.mapKeys { smtSourceSymbol(it.key) }
        }
        is SmtLibInput.NonlinearInteger -> {
            request = QuadraticRequest.Integer(source.problem.pipelineRequest())
            ints = source.problem.intVarNames.mapKeys { smtSourceSymbol(it.key) }
            bools = source.problem.boolVarNames.mapKeys { smtSourceSymbol(it.key) }
            reals = source.problem.realVarNames.mapKeys { smtSourceSymbol(it.key) }
        }
        else -> error("quadratic SMT source snapshot is invalid")
    }
    return quadraticSolvable(request, input.objective, input.sense, { sample ->
        val assignment = OpenTheoryAssignment.Rebuilt(
            OpenTheoryAssignment.ExactNra(ExactNraAssignment(sample.bools, requireNotNull(sample.algebraicReals))),
            sample.bools, Array(sample.numIntVars) { sample.exactIntValue(it) },
        )
        renderOpenTheoryModel(ints, bools, reals, assignment)
    }, admissionElapsedMs)
}

private fun LinearObjective.requiresExactRoute(model: Problem): Boolean {
    if (realCoefficients.any { it != 0.0 } || boolWeights.any { it != 0L }) return false
    var lower = bigIntOf(constant)
    var upper = lower
    for (v in intCoefficients.indices) {
        val coefficient = intCoefficients[v]
        if (coefficient == 0L) continue
        val lo = model.intBounds.lowerAsBigInteger(v) ?: return true
        val hi = model.intBounds.upperAsBigInteger(v) ?: return true
        val c = bigIntOf(coefficient)
        lower += c * if (coefficient > 0) lo else hi
        upper += c * if (coefficient > 0) hi else lo
    }
    val limit = bigIntOf(EXACT_INTEGER_MAGNITUDE)
    return lower < -limit || upper > limit
}

private const val EXACT_INTEGER_MAGNITUDE = 1L shl 53

// An open model searched by local search alone, minimizing [objective] when there is one.
@Suppress("LongParameterList")
private fun openLocalSearchSmt(
    model: Problem,
    objective: LinearObjective?,
    sense: ObjectiveSense,
    ints: Map<String, Int>,
    bools: Map<String, Int>,
    reals: Map<String, Int>,
    routingLpStats: LpStats,
    routingElapsedMs: Long,
): Solvable {
    val maximize = sense == ObjectiveSense.MAXIMIZE
    return openLocalSearchSolvable(
        model,
        { assignment -> renderOpenTheoryModel(ints, bools, reals, assignment) },
        routingLpStats,
        routingElapsedMs,
        objective = if (maximize) objective?.negated() else objective,
        maximize = maximize,
    )
}

/** Render an SMT-LIB `(get-model)`-style model: one `(define-fun name () Sort value)` per
 *  declared variable. Real values come from the leaf LP solve. */
internal fun renderModel(ints: Map<String, Int>, bools: Map<String, Int>, reals: Map<String, Int>, s: Sample): String =
    buildString {
        append("(\n")
        for ((name, id) in ints) append("  (define-fun $name () Int ${smtInteger(s.intValue(id).toString())})\n")
        for ((name, id) in bools) append("  (define-fun $name () Bool ${s.boolValue(id)})\n")
        val exactReals = if (reals.isEmpty()) {
            emptyList()
        } else {
            requireNotNull(s.exactReals) {
                "SMT model has no certified real values"
            }
        }
        for ((name, id) in reals) {
            append("  (define-fun $name () Real ${smtReal(exactReals[id].toString())})\n")
        }
        append(")")
    }

/** Render an exact open-theory model through the pipeline's mode-neutral witness surface. */
internal fun renderOpenTheoryModel(
    ints: Map<String, Int>,
    bools: Map<String, Int>,
    reals: Map<String, Int>,
    assignment: OpenTheoryAssignment,
): String = buildString {
    append("(\n")
    for ((name, id) in ints) append("  (define-fun $name () Int ${smtInteger(assignment.intValue(id))})\n")
    for ((name, id) in bools) append("  (define-fun $name () Bool ${assignment.boolValue(id)})\n")
    for ((name, id) in reals) {
        val value = assignment.algebraicRealValue(id)?.let(::smtAlgebraicValue) ?: smtReal(assignment.realValue(id))
        append("  (define-fun $name () Real $value)\n")
    }
    append(")")
}

internal fun smtInteger(value: String): String = if (value.startsWith('-')) "(- ${value.drop(1)})" else value

internal fun smtReal(value: String): String {
    val negative = value.startsWith('-')
    val magnitude = if (negative) value.drop(1) else value
    val parts = magnitude.split('/')
    require(parts.size in 1..2 && parts.all { it.isNotEmpty() && it.all { c -> c.isDigit() || c == '.' } }) {
        "invalid exact real value: $value"
    }
    val realParts = parts.map { if ('.' in it) it else "$it.0" }
    val term = if (realParts.size == 1) realParts[0] else "(/ ${realParts[0]} ${realParts[1]})"
    return if (negative) "(- $term)" else term
}

/** SMT-LIB output protocol: `sat`/`unsat`/`unknown` + the buffered model on sat. */
internal class SmtLibOutput(private val sourceObjective: () -> BigFraction? = { null }) : BufferedBestOutput() {
    override fun formatObjective(objective: Long): String =
        sourceObjective()?.toString() ?: super.formatObjective(objective)

    override fun formatContinuousObjective(objective: Double): String =
        sourceObjective()?.toString() ?: super.formatContinuousObjective(objective)

    private var optimize = false
    private var quadraticResult: QuadraticOptimizationResult? = null

    override val commentPrefix: String = ";"
    override val streamObjective: Boolean = true
    override val objectivePrefix: String = "; objective="

    override fun begin(optimize: Boolean, maximize: Boolean) {
        this.optimize = optimize
        quadraticResult = null
    }

    override fun onQuadraticResult(rendered: String?, result: QuadraticOptimizationResult) {
        quadraticResult = result
        best = rendered
        (result as? QuadraticOptimizationResult.WithIncumbent)?.let {
            println("; objective=${smtAlgebraicValue(it.incumbent.value)}")
        }
        val bound = when (result) {
            is QuadraticOptimizationResult.Optimal -> result.bound
            is QuadraticOptimizationResult.UnattainedLimit -> result.bound
            is QuadraticOptimizationResult.BestFound -> result.bound
            is QuadraticOptimizationResult.Unknown -> result.bound
            else -> null
        }
        bound?.let {
            val direction = if (result.sense == ObjectiveSense.MINIMIZE) "lower" else "upper"
            println("; objectiveBoundDirection=$direction")
            println("; objectiveBound=${smtAlgebraicValue(it.value)}")
            println("; objectiveBoundStrict=${it.strict}")
        }
    }

    override fun completionMetadata(verdict: Verdict): String? {
        if (!optimize) return null
        val status = when (val result = quadraticResult) {
            is QuadraticOptimizationResult.UnattainedLimit -> if (result.sense == ObjectiveSense.MINIMIZE) {
                "unattained-infimum"
            } else "unattained-supremum"
            else -> verdict.name.lowercase().replace('_', '-')
        }
        return "optimizationStatus=$status"
    }

    override fun statusLine(verdict: Verdict): String = when (verdict) {
        Verdict.SATISFIABLE, Verdict.OPTIMAL, Verdict.BEST_FOUND, Verdict.UNBOUNDED -> "sat"
        Verdict.UNSATISFIABLE -> "unsat"
        Verdict.UNKNOWN -> "unknown"
    }

    // Deliberately lean block: SMT-LIB comments carry headline search and exact-theory counters.
    override fun keepStat(key: String): Boolean = key in SMT_SEARCH_KEYS || key.startsWith("smt")

    private companion object {
        private val SMT_SEARCH_KEYS = setOf(
            "lsMoves", "lsCompletions", "lsCompletionsRefuted", "lsCompletionsUndecided",
            "nodes", "failures", "propagations", "learned", "learnedMeanSize", "learnedMeanLbd", "relearned",
            "openBoolDecisions", "openIntDecisions", "openTheoryDecisions", "openTheoryChecks",
            "openWork", "openLearned", "openRelearned",
            "openRestarts", "openReductions", "openDropped", "openRetained", "openPeakRetained",
            "openLearnedWatchVisits",
            "openAssertingConflicts", "openNonAssertingConflicts", "openReductionNs",
            "openHintDraws", "openHintProduced", "openHintVars", "openHintSteered", "openHintMoves",
        )
    }
}
