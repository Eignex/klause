package com.eignex.klause.cli

import com.eignex.klause.formats.opb.Opb
import com.eignex.klause.lowering.opb.toProblem
import com.eignex.klause.solver.Sample

/**
 * OPB/WBO pseudo-Boolean front-end (`.opb`, `.wbo`). Satisfaction (`SAT`), optimisation (`min:`),
 * and WBO soft-constraint (weighted-violation) instances are handled; the objective is always a
 * minimisation. Emits the PB-competition output protocol: an `o <cost>` line per improving
 * incumbent, a final `s SATISFIABLE` / `s OPTIMUM FOUND` / `s UNSATISFIABLE` / `s UNKNOWN` status,
 * and a `v <literals>` line listing each variable `xi` (1-based) as `xi` when true, `-xi` when
 * false. `-s` statistics are emitted as `c` comment lines.
 */
internal object OpbMode : CliMode {
    override val names = listOf("opb", "pb", "wbo")
    override val extensions = listOf("opb", "wbo")
    override fun newSession(): ModeSession = Session()

    private class Session : ModeSession {
        override fun flags(): List<FlagSpec> = emptyList()

        override fun load(path: String, common: CommonOptions): Solvable {
            val parsed = Opb.parse(openFileSource(path)).toProblem()
            cliLogger(common.verbose).v {
                "parsed ${fileName(path)}: bool=${parsed.problem.numBoolVars} factors=${parsed.problem.numFactors}"
            }
            // Only the declared x1..xN belong in the `v` line — not the Tseitin/soft indicators that
            // [Problem.numBoolVars] also counts.
            val render: (Sample) -> String = { s -> renderPbModel(parsed.numDeclaredVars, s) }
            // OPB objectives are minimisations; there is no maximisation form.
            return linearSolvable(
                parsed.problem,
                parsed.objective,
                maximize = false,
                render,
                boolFolds = parsed.boolFolds,
            )
        }

        override fun output(common: CommonOptions): OutputProtocol = OpbOutput()
    }
}

/** Render a PB-competition `v <literals>` line: each variable `xi` (1-based) as `xi` when true,
 *  `-xi` when false. */
internal fun renderPbModel(numVars: Int, s: Sample): String = buildString {
    append("v")
    for (v in 0 until numVars) append(if (s.bools[v]) " x${v + 1}" else " -x${v + 1}")
}

/** PB-competition output protocol. Incumbent `o` costs stream live; the final `s` status and the
 *  `v` literal line are emitted together at completion, after the best solution is known. */
internal class OpbOutput : BufferedBestOutput() {
    override val commentPrefix: String = "c"
    override val streamObjective: Boolean = true

    override fun statusLine(verdict: Verdict): String = when (verdict) {
        Verdict.SATISFIABLE, Verdict.BEST_FOUND -> "s SATISFIABLE"
        Verdict.OPTIMAL -> "s OPTIMUM FOUND"
        Verdict.UNSATISFIABLE -> "s UNSATISFIABLE"
        Verdict.UNKNOWN -> "s UNKNOWN"
    }

    // Clause-learning counters are irrelevant to the PB search shape reported here.
    override fun keepStat(key: String): Boolean = key !in PB_OMITTED_KEYS

    private companion object {
        private val PB_OMITTED_KEYS = setOf("learned", "relearned")
    }
}
