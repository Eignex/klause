package com.eignex.klause.cli

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.SourceProblemRoute
import com.eignex.klause.solver.pipeline.pipelineRoute
import com.eignex.klause.solver.result.LpStats
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.plus
import com.eignex.klause.util.times
import com.eignex.klause.util.unaryMinus
import kotlin.time.TimeSource

/**
 * SMT-LIB 2 front-end (`.smt2` / `.smt`; QF_LIA / QF_LRA / QF_LIRA). Emits the SMT-LIB convention: a
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
            val config = KlauseConfig.current
            val parsed = SmtLib.parse(
                openFileSource(path),
                config.unboundedIntLo,
                config.unboundedIntHi,
            )
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
            latestSourceObjective = null
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
        for ((name, id) in ints) append("  (define-fun $name () Int ${smtInteger(s.ints[id].toString())})\n")
        for ((name, id) in bools) append("  (define-fun $name () Bool ${s.bools[id]})\n")
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
    for ((name, id) in reals) append("  (define-fun $name () Real ${smtReal(assignment.realValue(id))})\n")
    append(")")
}

private fun smtInteger(value: String): String = if (value.startsWith('-')) "(- ${value.drop(1)})" else value

private fun smtReal(value: String): String {
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

    override val commentPrefix: String = ";"
    override val streamObjective: Boolean = true
    override val objectivePrefix: String = "; objective="

    override fun begin(optimize: Boolean, maximize: Boolean) {
        this.optimize = optimize
    }

    override fun completionMetadata(verdict: Verdict): String? =
        if (optimize) "optimizationStatus=${verdict.name.lowercase().replace('_', '-')}" else null

    override fun statusLine(verdict: Verdict): String = when (verdict) {
        Verdict.SATISFIABLE, Verdict.OPTIMAL, Verdict.BEST_FOUND, Verdict.UNBOUNDED -> "sat"
        Verdict.UNSATISFIABLE -> "unsat"
        Verdict.UNKNOWN -> "unknown"
    }

    // Deliberately lean block: SMT-LIB comments carry headline search and exact-theory counters.
    override fun keepStat(key: String): Boolean = key in SMT_SEARCH_KEYS || key.startsWith("smt")

    private companion object {
        private val SMT_SEARCH_KEYS = setOf(
            "nodes", "failures", "propagations",
            "openBoolDecisions", "openIntDecisions", "openTheoryDecisions", "openTheoryChecks",
            "openWork", "openLearned", "openRelearned",
            "openRestarts", "openReductions", "openDropped", "openRetained", "openPeakRetained",
            "openLearnedWatchVisits",
            "openAssertingConflicts", "openNonAssertingConflicts", "openReductionNs",
            "openHintDraws", "openHintProduced", "openHintVars", "openHintSteered", "openHintMoves",
        )
    }
}
