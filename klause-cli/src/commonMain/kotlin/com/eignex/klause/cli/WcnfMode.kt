package com.eignex.klause.cli

import com.eignex.klause.formats.dimacs.Dimacs
import com.eignex.klause.solver.Sample

/**
 * DIMACS **WCNF** (MaxSAT) front-end (`.wcnf`). Soft clauses are relaxed with a fresh indicator and
 * the total weight of falsified soft clauses is minimised, so both new-format (`h` hard clauses,
 * `<weight>` soft) and old-format (`p wcnf … <top>`) instances are handled. Emits the MaxSAT-Evaluation
 * protocol: an `o <cost>` line per improving incumbent, a final `s OPTIMUM FOUND` / `s SATISFIABLE`
 * / `s UNSATISFIABLE` / `s UNKNOWN` status, and a `v` line giving each original variable's value as a
 * `0`/`1` bit-string (relaxation variables are not reported). `-s` statistics are `c` comment lines.
 */
internal object WcnfMode : CliMode {
    override val names = listOf("wcnf", "maxsat")
    override val extensions = listOf("wcnf")
    override fun newSession(): ModeSession = Session()

    private class Session : ModeSession {
        override fun flags(): List<FlagSpec> = emptyList()

        override fun load(path: String, common: CommonOptions): Solvable {
            val parsed = Dimacs.parseWcnf(readTextFile(path))
            cliLogger(common.verbose).v {
                "parsed ${fileName(path)}: vars=${parsed.numOriginalBoolVars} " +
                    "soft=${parsed.problem.numBoolVars - parsed.numOriginalBoolVars} factors=${parsed.problem.numFactors}"
            }
            val render: (Sample) -> String = { s -> renderWcnfModel(parsed.numOriginalBoolVars, s) }
            // MaxSAT minimises the weight of falsified soft clauses.
            return linearSolvable(parsed.problem, parsed.objective, maximize = false, render)
        }

        override fun output(common: CommonOptions): OutputProtocol = WcnfOutput()
    }
}

/** Render a MaxSAT-Evaluation `v` line: `v` followed by one `0`/`1` bit per original variable (1-based). */
internal fun renderWcnfModel(numOriginalVars: Int, s: Sample): String = buildString {
    append("v ")
    for (v in 0 until numOriginalVars) append(if (s.bools[v]) '1' else '0')
}

/** MaxSAT-Evaluation output protocol. Incumbent `o` costs stream live; the final `s` status and `v`
 *  model line are emitted together at completion, after the best solution is known. */
internal class WcnfOutput : BufferedBestOutput() {
    override val commentPrefix: String = "c"
    override val streamObjective: Boolean = true

    override fun statusLine(verdict: Verdict): String = when (verdict) {
        Verdict.SATISFIABLE, Verdict.BEST_FOUND -> "s SATISFIABLE"
        Verdict.OPTIMAL -> "s OPTIMUM FOUND"
        Verdict.UNSATISFIABLE -> "s UNSATISFIABLE"
        Verdict.UNKNOWN -> "s UNKNOWN"
    }

    // Clause-learning counters are irrelevant to the MaxSAT search shape reported here.
    override fun keepStat(key: String): Boolean = key !in MAXSAT_OMITTED_KEYS

    private companion object {
        private val MAXSAT_OMITTED_KEYS = setOf("learned", "relearned")
    }
}
