package com.eignex.klause.cli

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.formats.smtlib.UnsupportedSmtException
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.solver.pipeline.SourceProblemRoute
import com.eignex.klause.solver.pipeline.UnplaceableColumn
import com.eignex.klause.solver.pipeline.pipelineRoute

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
            return when (
                val route = parsed.model.pipelineRoute(
                    objective,
                    parsed.sense == ObjectiveSense.MAXIMIZE,
                    routePureRealToTheory = true,
                )
            ) {
                is SourceProblemRoute.Finite -> linearSolvable(
                    route.problem,
                    objective,
                    parsed.sense == ObjectiveSense.MAXIMIZE,
                    render,
                )

                is SourceProblemRoute.OpenTheory -> {
                    if (parsed.objective != null) {
                        throw UnsupportedSmtException("open theory optimization is unsupported")
                    }
                    openTheorySolvable(route.request) { assignment ->
                        renderOpenTheoryModel(ints, bools, reals, assignment)
                    }
                }

                is SourceProblemRoute.UnsupportedOpen ->
                    throw UnsupportedSmtException(unsupportedOpenReason(route.unplaceable, ints))
            }
        }

        override fun output(common: CommonOptions): OutputProtocol = SmtLibOutput()
    }
}

/** Render an SMT-LIB `(get-model)`-style model: one `(define-fun name () Sort value)` per
 *  declared variable. Real values come from the leaf LP solve. */
internal fun renderModel(ints: Map<String, Int>, bools: Map<String, Int>, reals: Map<String, Int>, s: Sample): String =
    buildString {
        append("(\n")
        for ((name, id) in ints) append("  (define-fun $name () Int ${s.ints[id]})\n")
        for ((name, id) in bools) append("  (define-fun $name () Bool ${s.bools[id]})\n")
        for ((name, id) in reals) {
            val v = if (id < s.reals.size) s.reals[id] else 0.0
            append("  (define-fun $name () Real $v)\n")
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
    for ((name, id) in ints) append("  (define-fun $name () Int ${assignment.intValue(id)})\n")
    for ((name, id) in bools) append("  (define-fun $name () Bool ${assignment.boolValue(id)})\n")
    for ((name, id) in reals) append("  (define-fun $name () Real ${assignment.realValue(id)})\n")
    append(")")
}

/** SMT-LIB output protocol: `sat`/`unsat`/`unknown` + the buffered model on sat. */
internal class SmtLibOutput : BufferedBestOutput() {
    override val commentPrefix: String = ";"

    override fun statusLine(verdict: Verdict): String = when (verdict) {
        Verdict.SATISFIABLE, Verdict.OPTIMAL, Verdict.BEST_FOUND -> "sat"
        Verdict.UNSATISFIABLE -> "unsat"
        Verdict.UNKNOWN -> "unknown"
    }

    // Deliberately lean block: SMT-LIB comments carry only the headline search counters.
    override fun keepStat(key: String): Boolean = key in SMT_SEARCH_KEYS

    private companion object {
        private val SMT_SEARCH_KEYS = setOf(
            "nodes", "failures", "propagations",
            "openBoolDecisions", "openIntDecisions", "openTheoryDecisions", "openTheoryChecks",
            "openLiaRowVisits", "openCancellationPolls", "openWork", "openLearned", "openRelearned",
            "openRestarts", "openReductions", "openDropped", "openRetained", "openPeakRetained",
            "openLearnedWatchVisits",
        )
    }
}

/**
 * Why an open model has no route, naming the column and the constraint that demanded it be finite.
 *
 * A model is declined whole, but the cause is one column and one factor: the column carries no bound CP
 * can index, and the factor is one no theory holds. Naming both says what to change — bound that column,
 * or state a decomposition for that constraint the theories can take — where naming neither leaves a
 * user to guess which of their constraints is the unsupported one.
 */
internal fun unsupportedOpenReason(unplaceable: UnplaceableColumn?, names: Map<String, Int>): String {
    val detail = unplaceable
        ?: return "open integer bounds require supported difference, General LIA, or exact LIRA coverage"
    val column = names.entries.firstOrNull { it.value == detail.column }?.key
        ?: "integer column ${detail.column}"
    val kind = detail.factorKind ?: "a constraint"
    return "$column has no bound to search over and $kind needs one; " +
        "bound it, or state this constraint as rows a theory can hold"
}
