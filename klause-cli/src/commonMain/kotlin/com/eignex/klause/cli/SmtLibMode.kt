package com.eignex.klause.cli

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.formats.smtlib.UnsupportedSmtException
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.ProblemSpec
import com.eignex.klause.lowering.smtlib.SmtLib
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.pipeline.ProblemPipeline
import com.eignex.klause.solver.pipeline.componentPlan
import com.eignex.klause.solver.pipeline.sourceRoute
import com.eignex.klause.solver.pipeline.supportsExactLra
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
            val pipeline = if (parsed.model.supportsExactLra()) {
                ProblemPipeline.EXACT_LRA
            } else {
                parsed.model.sourceRoute()
            }
            when (pipeline) {
                ProblemPipeline.UNSUPPORTED_OPEN ->
                    throw UnsupportedSmtException(unsupportedOpenReason(parsed.model, ints))

                ProblemPipeline.DIFFERENCE_THEORY, ProblemPipeline.GENERAL_LIA, ProblemPipeline.EXACT_LRA,
                ProblemPipeline.EXACT_LIRA,
                -> {
                    if (parsed.objective != null) {
                        val theory = when (pipeline) {
                            ProblemPipeline.DIFFERENCE_THEORY -> "difference-theory"
                            ProblemPipeline.GENERAL_LIA -> "General LIA"
                            ProblemPipeline.EXACT_LRA -> "exact LRA"
                            ProblemPipeline.EXACT_LIRA -> "exact LIRA"
                            else -> error("finite and unsupported pipelines do not reach open optimization")
                        }
                        throw UnsupportedSmtException("open $theory optimization is unsupported")
                    }
                    return when (pipeline) {
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

/**
 * Why an open model has no route, naming the column and the constraint that demanded it be finite.
 *
 * A model is declined whole, but the cause is one column and one factor: the column carries no bound CP
 * can index, and the factor is one no theory holds. Naming both says what to change — bound that column,
 * or state a decomposition for that constraint the theories can take — where naming neither leaves a
 * user to guess which of their constraints is the unsupported one.
 */
internal fun unsupportedOpenReason(model: ProblemSpec, names: Map<String, Int>): String {
    // Builds the plan a second time, after `sourceRoute` built one to reach this branch. Deliberate:
    // the run ends here either way, and threading the plan out of routing would put a cost on every
    // model to save one on a rejected one.
    val unplaceable = model.componentPlan().unplaceable
        ?: return "open integer bounds require supported difference, General LIA, or exact LIRA coverage"
    val column = names.entries.firstOrNull { it.value == unplaceable.column }?.key
        ?: "integer column ${unplaceable.column}"
    val kind = unplaceable.factorKind ?: "a constraint"
    return "$column has no bound to search over and $kind needs one; " +
        "bound it, or state this constraint as rows a theory can hold"
}
