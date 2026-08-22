package com.eignex.klause.cli

import com.eignex.klause.backtrack.GeneralLiaAssignment
import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.formats.ObjectiveSense
import com.eignex.klause.formats.smtlib.IntDigitColumns
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.formats.smtlib.UnsupportedSmtException
import com.eignex.klause.solver.ProblemPipeline
import com.eignex.klause.solver.Sample

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
                searchBound = config.unboundedSearchBound,
            )
            cliLogger(common.verbose).v {
                "parsed ${fileName(path)}: bool=${parsed.model.numBoolVars} int=${parsed.model.numIntVars} " +
                    "real=${parsed.model.numRealVars} factors=${parsed.model.factors.size}"
            }
            val ints = parsed.intVarNames
            val bools = parsed.boolVarNames
            val reals = parsed.realVarNames
            val render: (Sample) -> String = { s -> renderModel(ints, bools, reals, s, parsed.intDigits) }
            when (parsed.sourcePipeline) {
                ProblemPipeline.UNSUPPORTED_OPEN ->
                    throw UnsupportedSmtException(
                        "open integer bounds require supported difference or General LIA coverage",
                    )

                ProblemPipeline.DIFFERENCE_THEORY, ProblemPipeline.GENERAL_LIA -> {
                    if (parsed.objective != null) {
                        val theory = if (parsed.sourcePipeline == ProblemPipeline.DIFFERENCE_THEORY) {
                            "difference-theory"
                        } else {
                            "General LIA"
                        }
                        throw UnsupportedSmtException("open $theory optimization is unsupported")
                    }
                    return if (parsed.sourcePipeline == ProblemPipeline.DIFFERENCE_THEORY) {
                        differenceTheorySolvable(parsed.model, render)
                    } else {
                        generalLiaSolvable(parsed.model) { assignment ->
                            renderGeneralLiaModel(ints, bools, reals, assignment)
                        }
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
internal fun renderModel(
    ints: Map<String, Int>,
    bools: Map<String, Int>,
    reals: Map<String, Int>,
    s: Sample,
    intDigits: Map<Int, IntDigitColumns> = emptyMap(),
): String = buildString {
    append("(\n")
    for ((name, id) in ints) append("  (define-fun $name () Int ${intValue(id, s, intDigits)})\n")
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

/** A declared integer's value: off its digit columns when it was lowered onto them, off the variable
 *  otherwise. */
private fun intValue(id: Int, s: Sample, intDigits: Map<Int, IntDigitColumns>): String =
    intDigits[id]?.decimalIn(s.ints) ?: s.ints[id].toString()

/** SMT-LIB output protocol: `sat`/`unsat`/`unknown` + the buffered model on sat. When [clamp] is set
 *  (by the presolve-phase deferred bounding), an `unsat` is only `unsat` within the finite solver range —
 *  the sound verdict for the original (unbounded) problem is `unknown`, so it is reported as such, unless
 *  the refutation can be re-derived without the box ([ClampFlag.refutationIsBoxFree]). */
internal class SmtLibOutput(private val clamp: ClampFlag = ClampFlag()) : BufferedBestOutput() {
    override val commentPrefix: String = ";"

    override fun statusLine(verdict: Verdict): String = when (verdict) {
        Verdict.SATISFIABLE, Verdict.OPTIMAL, Verdict.BEST_FOUND -> "sat"
        Verdict.UNSATISFIABLE -> if (clamp.clamped && !clamp.refutationIsBoxFree()) "unknown" else "unsat"
        Verdict.UNKNOWN -> "unknown"
    }

    // The two roads to `unknown` want opposite responses from the caller: a refutation the box blocked is
    // very likely an unsat waiting on real bounds, while an exhausted budget just wants a longer one.
    override fun verdictReason(verdict: Verdict): String? = when {
        verdict == Verdict.UNSATISFIABLE && clamp.clamped && !clamp.refutationIsBoxFree() ->
            "refuted inside the clamped search range, not over the model's own"

        else -> super.verdictReason(verdict)
    }

    // Deliberately lean block: SMT-LIB comments carry only the headline search counters.
    override fun keepStat(key: String): Boolean = key in SMT_SEARCH_KEYS

    private companion object {
        private val SMT_SEARCH_KEYS = setOf("nodes", "failures", "propagations")
    }
}
