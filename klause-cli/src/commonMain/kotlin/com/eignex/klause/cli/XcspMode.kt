package com.eignex.klause.cli

import com.eignex.klause.formats.ObjectiveSense
import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.solver.Sample

/**
 * XCSP3 front-end (`.xml` / `.xcsp` / `.xcsp3`). Emits the XCSP3 competition output protocol:
 * an `o <cost>` line per improving incumbent, a final `s SATISFIABLE` / `s OPTIMUM FOUND` /
 * `s UNSATISFIABLE` / `s UNKNOWN` status, and a `v <instantiation>` line with named values.
 * `-s` statistics are emitted as `c` comment lines.
 */
internal object Xcsp3Mode : CliMode {
    override val names = listOf("xcsp3", "xcsp")
    override val extensions = listOf("xml", "xcsp", "xcsp3")
    override fun newSession(): ModeSession = Session()

    private class Session : ModeSession {
        override fun flags(): List<FlagSpec> = emptyList()

        override fun load(path: String, common: CommonOptions): Solvable {
            // Parsing only reads: the base bake runs as presolve step 0, bounded by the presolve deadline.
            val parsed = Xcsp3.parse(readTextFile(path))
            cliLogger(common.verbose).v {
                "parsed ${fileName(path)}: bool=${parsed.problem.numBoolVars} int=${parsed.problem.numIntVars} " +
                    "factors=${parsed.problem.numFactors}"
            }
            val names = parsed.intVarNames
            val render: (Sample) -> String = { s -> renderInstantiation(names, s) }
            return linearSolvable(
                parsed.problem,
                parsed.objective,
                parsed.sense == ObjectiveSense.MAXIMIZE,
                render,
                parsed.definedVars,
            )
        }

        override fun output(common: CommonOptions): OutputProtocol = XcspOutput()
    }
}

/** Render an XCSP3 competition `v <instantiation>` line: the declared variables (array cells
 *  as `id[i]`) in declaration order paired with their solution values. */
internal fun renderInstantiation(names: Map<String, Int>, s: Sample): String {
    val list = names.keys.joinToString(" ")
    val values = names.values.joinToString(" ") { s.ints[it].toString() }
    return "v <instantiation> <list> $list </list> <values> $values </values> </instantiation>"
}

/** XCSP3 competition output protocol. Incumbent `o` costs stream live; the final `s` status
 *  and `v` instantiation are emitted together at completion, after the best solution is known. */
internal class XcspOutput : BufferedBestOutput() {
    override val commentPrefix: String = "c"
    override val streamObjective: Boolean = true

    override fun statusLine(verdict: Verdict): String = when (verdict) {
        Verdict.SATISFIABLE, Verdict.BEST_FOUND -> "s SATISFIABLE"
        Verdict.OPTIMAL -> "s OPTIMUM FOUND"
        Verdict.UNSATISFIABLE -> "s UNSATISFIABLE"
        Verdict.UNKNOWN -> "s UNKNOWN"
    }

    // Deliberately omits the clause-learning counters; XCSP comments track search shape only.
    override fun keepStat(key: String): Boolean = key !in XCSP_OMITTED_KEYS

    private companion object {
        private val XCSP_OMITTED_KEYS = setOf("learned", "relearned")
    }
}
