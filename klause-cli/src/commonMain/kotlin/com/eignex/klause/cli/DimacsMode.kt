package com.eignex.klause.cli

import com.eignex.klause.formats.dimacs.Dimacs
import com.eignex.klause.solver.Sample

/**
 * DIMACS **CNF** front-end (`.cnf`) for Boolean satisfiability. Emits the SAT-competition
 * convention: an `s SATISFIABLE` / `s UNSATISFIABLE` / `s UNKNOWN` status line, and on sat a
 * `v`-prefixed model listing each variable as `i` (true) or `-i` (false), 1-based, terminated
 * by `0`. `-s` statistics are emitted as `c` comment lines. Weighted CNF (MaxSAT) is a distinct
 * format with an objective, handled by [WcnfMode].
 */
internal object DimacsMode : CliMode {
    override val names = listOf("dimacs", "cnf", "sat")
    override val extensions = listOf("cnf")
    override fun newSession(): ModeSession = Session()

    private class Session : ModeSession {
        override fun flags(): List<FlagSpec> = emptyList()

        override fun load(path: String, common: CommonOptions): Solvable {
            val problem = Dimacs.parse(openFileSource(path))
            cliLogger(common.verbose).v {
                "parsed ${fileName(path)}: bool=${problem.numBoolVars} factors=${problem.numFactors}"
            }
            val render: (Sample) -> String = { s -> renderDimacsModel(problem.numBoolVars, s) }
            return linearSolvable(problem, null, false, render)
        }

        override fun output(common: CommonOptions): OutputProtocol = DimacsOutput()
    }
}

/** Render a DIMACS SAT model line: each variable `i` (1-based) as `i` when true, `-i` when
 *  false, terminated by `0`. */
internal fun renderDimacsModel(numVars: Int, s: Sample): String = buildString {
    append("v")
    for (v in 0 until numVars) append(" ${if (s.bools[v]) v + 1 else -(v + 1)}")
    append(" 0")
}

/** DIMACS output protocol: `s SATISFIABLE`/`s UNSATISFIABLE`/`s UNKNOWN` + the buffered model on sat.
 *  Shares the buffered-model plumbing with the other competition front-ends via [BufferedBestOutput]. */
internal class DimacsOutput : BufferedBestOutput() {
    override val commentPrefix: String = "c"

    override fun statusLine(verdict: Verdict): String = when (verdict) {
        Verdict.SATISFIABLE, Verdict.OPTIMAL, Verdict.BEST_FOUND -> "s SATISFIABLE"
        Verdict.UNSATISFIABLE -> "s UNSATISFIABLE"
        Verdict.UNKNOWN -> "s UNKNOWN"
    }

    // SAT competition output carries the full search-counter block.
    override fun keepStat(key: String): Boolean = true
}
