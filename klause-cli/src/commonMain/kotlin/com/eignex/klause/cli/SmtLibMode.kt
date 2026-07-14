package com.eignex.klause.cli

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.formats.ObjectiveSense
import com.eignex.klause.formats.smtlib.SmtLibQfLia
import com.eignex.klause.solver.Sample

/**
 * SMT-LIB 2 **QF_LIA** front-end (`.smt2` / `.smt`). Emits the SMT-LIB convention: a
 * `sat` / `unsat` / `unknown` status line, followed (when sat) by a `(get-model)`-style
 * `(define-fun …)` block. `-s` statistics are emitted as `;` comment lines.
 */
internal object SmtLibMode : CliMode {
    override val names = listOf("smtlib", "smt", "smt2")
    override val extensions = listOf("smt2", "smt")
    override fun newSession(): ModeSession = Session()

    private class Session : ModeSession {
        // Set by load(): true when parsing clamped an unbounded integer to the finite range, so an
        // `unsat` verdict must be reported as `unknown` (see output()).
        private var domainsClamped = false

        override fun flags(): List<FlagSpec> = emptyList()

        override fun load(path: String, common: CommonOptions): Solvable {
            // Unbounded SMT ints use the ambient default int range (shared with the FlatZinc front-end).
            val config = KlauseConfig.current
            val parsed = SmtLibQfLia.parse(
                readTextFile(path),
                config.unboundedIntLo,
                config.unboundedIntHi,
                searchBound = config.unboundedSearchBound,
            )
            domainsClamped = parsed.domainsClamped
            cliLogger(common.verbose).v {
                "parsed ${fileName(path)}: bool=${parsed.problem.numBoolVars} int=${parsed.problem.numIntVars} " +
                    "factors=${parsed.problem.numFactors} clamped=$domainsClamped"
            }
            val ints = parsed.intVarNames
            val bools = parsed.boolVarNames
            val render: (Sample) -> String = { s -> renderModel(ints, bools, s) }
            return linearSolvable(parsed.problem, parsed.objective, parsed.sense == ObjectiveSense.MAXIMIZE, render)
        }

        override fun output(common: CommonOptions): OutputProtocol = SmtLibOutput(domainsClamped)
    }
}

/** Render an SMT-LIB `(get-model)`-style model: one `(define-fun name () Sort value)` per
 *  declared variable. */
internal fun renderModel(ints: Map<String, Int>, bools: Map<String, Int>, s: Sample): String = buildString {
    append("(\n")
    for ((name, id) in ints) append("  (define-fun $name () Int ${s.ints[id]})\n")
    for ((name, id) in bools) append("  (define-fun $name () Bool ${s.bools[id]})\n")
    append(")")
}

/** SMT-LIB output protocol: `sat`/`unsat`/`unknown` + the buffered model on sat. When
 *  [domainsClamped] is set, an `unsat` is only `unsat` within the finite solver range — the sound
 *  verdict for the original (unbounded) problem is `unknown`, so it is reported as such. */
internal class SmtLibOutput(private val domainsClamped: Boolean = false) : BufferedBestOutput() {
    override val commentPrefix: String = ";"

    override fun statusLine(verdict: Verdict): String = when (verdict) {
        Verdict.SATISFIABLE, Verdict.OPTIMAL, Verdict.BEST_FOUND -> "sat"
        Verdict.UNSATISFIABLE -> if (domainsClamped) "unknown" else "unsat"
        Verdict.UNKNOWN -> "unknown"
    }

    // Deliberately lean block: SMT-LIB comments carry only the headline search counters.
    override fun keepStat(key: String): Boolean = key in SMT_SEARCH_KEYS

    private companion object {
        private val SMT_SEARCH_KEYS = setOf("nodes", "failures", "propagations")
    }
}
