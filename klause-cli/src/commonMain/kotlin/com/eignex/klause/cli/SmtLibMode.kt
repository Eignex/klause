package com.eignex.klause.cli

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.formats.ObjectiveSense
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.formats.smtlib.UnsupportedSmtException
import com.eignex.klause.solver.ProblemPipeline
import com.eignex.klause.solver.Sample
import com.eignex.klause.theory.lia.GeneralLiaAssignment
import com.eignex.klause.theory.qflra.ExactLiraAssignment
import com.eignex.klause.theory.qflra.ExactLraAssignment

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
            when (parsed.sourcePipeline) {
                ProblemPipeline.UNSUPPORTED_OPEN ->
                    throw UnsupportedSmtException(
                        "open integer bounds require supported difference, General LIA, or exact LIRA coverage",
                    )

                ProblemPipeline.DIFFERENCE_THEORY, ProblemPipeline.GENERAL_LIA, ProblemPipeline.EXACT_LRA,
                ProblemPipeline.EXACT_LIRA,
                -> {
                    if (parsed.objective != null) {
                        val theory = when (parsed.sourcePipeline) {
                            ProblemPipeline.DIFFERENCE_THEORY -> "difference-theory"
                            ProblemPipeline.GENERAL_LIA -> "General LIA"
                            ProblemPipeline.EXACT_LRA -> "exact LRA"
                            ProblemPipeline.EXACT_LIRA -> "exact LIRA"
                            else -> error("finite and unsupported pipelines do not reach open optimization")
                        }
                        throw UnsupportedSmtException("open $theory optimization is unsupported")
                    }
                    return when (parsed.sourcePipeline) {
                        ProblemPipeline.DIFFERENCE_THEORY -> differenceTheorySolvable(parsed.model, render)

                        ProblemPipeline.GENERAL_LIA -> generalLiaSolvable(parsed.model) { assignment ->
                            renderGeneralLiaModel(ints, bools, reals, assignment)
                        }

                        ProblemPipeline.EXACT_LRA -> exactLraSolvable(parsed.model) { assignment ->
                            renderExactLraModel(ints, bools, reals, assignment)
                        }

                        ProblemPipeline.EXACT_LIRA -> exactLiraSolvable(parsed.model) { assignment ->
                            renderExactLiraModel(ints, bools, reals, assignment)
                        }

                        else -> error("finite and unsupported pipelines do not reach exact theory routing")
                    }
                }

                ProblemPipeline.FINITE_CP -> Unit
            }
            return linearSolvable(
                parsed.model.materializeFiniteBounds(),
                parsed.objective,
                parsed.sense == ObjectiveSense.MAXIMIZE,
                render,
            )
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

/** Render an exact General LIA model without narrowing its integer values to [Long]. */
internal fun renderGeneralLiaModel(
    ints: Map<String, Int>,
    bools: Map<String, Int>,
    reals: Map<String, Int>,
    assignment: GeneralLiaAssignment,
): String = buildString {
    check(reals.isEmpty()) { "General LIA does not admit real variables" }
    append("(\n")
    for ((name, id) in ints) append("  (define-fun $name () Int ${assignment.ints[id]})\n")
    for ((name, id) in bools) append("  (define-fun $name () Bool ${assignment.bools[id]})\n")
    append(")")
}

/** Render an exact rational QF_LRA model without converting its witness to [Double]. */
internal fun renderExactLraModel(
    ints: Map<String, Int>,
    bools: Map<String, Int>,
    reals: Map<String, Int>,
    assignment: ExactLraAssignment,
): String = buildString {
    check(ints.isEmpty()) { "exact LRA does not admit integer variables" }
    append("(\n")
    for ((name, id) in bools) append("  (define-fun $name () Bool ${assignment.bools[id]})\n")
    for ((name, id) in reals) append("  (define-fun $name () Real ${assignment.reals[id]})\n")
    append(")")
}

/** Render an exact QF_LIRA model without narrowing either half of its witness. */
internal fun renderExactLiraModel(
    ints: Map<String, Int>,
    bools: Map<String, Int>,
    reals: Map<String, Int>,
    assignment: ExactLiraAssignment,
): String = buildString {
    append("(\n")
    for ((name, id) in ints) append("  (define-fun $name () Int ${assignment.ints[id]})\n")
    for ((name, id) in bools) append("  (define-fun $name () Bool ${assignment.bools[id]})\n")
    for ((name, id) in reals) append("  (define-fun $name () Real ${assignment.reals[id]})\n")
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
        private val SMT_SEARCH_KEYS = setOf("nodes", "failures", "propagations")
    }
}
