package com.eignex.klause.cli

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.SourceProblemRoute
import com.eignex.klause.solver.pipeline.pipelineRoute
import com.eignex.klause.solver.result.LpStats
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
            val render: (Sample) -> String = { s -> renderModel(ints, bools, reals, s) }
            val objective = parsed.objective?.toLinearObjective()
            var routingLpStats = LpStats()
            val routingStart = TimeSource.Monotonic.markNow()
            val route = parsed.model.pipelineRoute(
                objective,
                parsed.sense == ObjectiveSense.MAXIMIZE,
                routePureRealToTheory = true,
                boundCancellation = common.routingCancellation(),
                onLpStats = { routingLpStats = it },
            )
            val routingElapsedMs = routingStart.elapsedNow().inWholeMilliseconds
            common.logRoute(route, routingElapsedMs)
            return when (route) {
                is SourceProblemRoute.Finite -> linearSolvable(
                    route.problem,
                    objective,
                    parsed.sense == ObjectiveSense.MAXIMIZE,
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

        override fun output(common: CommonOptions): OutputProtocol = SmtLibOutput()
    }
}

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
internal class SmtLibOutput : BufferedBestOutput() {
    override val commentPrefix: String = ";"

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
            "openHintDraws", "openHintProduced", "openHintVars", "openHintSteered", "openHintMoves",
        )
    }
}
