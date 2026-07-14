package com.eignex.klause.cli

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.MpsCompiled
import com.eignex.klause.formats.mps.toProblem
import com.eignex.klause.solver.Sample

/**
 * MPS (Mathematical Programming System) MIP front-end (`.mps`). Parses the instance and lowers it to
 * klause's integer model (see [com.eignex.klause.formats.mps.toProblem]: integer columns direct,
 * bounded floats bucketed, unbounded floats rejected, unbounded ints clamped to the search range).
 * Emits an `o <cost>` line per improving incumbent, then a final `s SATISFIABLE` / `s OPTIMUM FOUND` /
 * `s UNSATISFIABLE` / `s UNKNOWN` and a `v name=value` line. `-s` statistics are `c` comment lines.
 */
internal object MpsMode : CliMode {
    override val names = listOf("mps")
    override val extensions = listOf("mps")
    override fun newSession(): ModeSession = Session()

    private class Session : ModeSession {
        // Set by load(): true when an unbounded variable was clamped to the finite search range, so a
        // proven optimum/unsat is only valid within that range (see output()).
        private var clamped = false

        override fun flags(): List<FlagSpec> = emptyList()

        override fun load(path: String, common: CommonOptions): Solvable {
            val config = KlauseConfig.current
            val compiled = Mps.parse(readTextFile(path))
                .toProblem(config.unboundedSearchBound, config.floatBuckets, config.floatScale)
            clamped = compiled.clamped
            cliLogger(common.verbose).v {
                "parsed ${fileName(path)}: int=${compiled.problem.numIntVars} " +
                    "factors=${compiled.problem.numFactors} float-cols=${compiled.floatColumns} " +
                    "objScale=${compiled.objectiveScale} clamped=$clamped"
            }
            val render: (Sample) -> String = { s -> renderMpsModel(compiled, s) }
            return linearSolvable(compiled.problem, compiled.objective, compiled.maximize, render)
        }

        override fun output(common: CommonOptions): OutputProtocol = MpsOutput(clamped)
    }
}

/** Render an MPS solution line: `v name=value` per column, a bucketed float shown as its real value. */
internal fun renderMpsModel(compiled: MpsCompiled, s: Sample): String = buildString {
    append("v")
    for ((name, id) in compiled.varNames) {
        val bucketing = compiled.floatBucketings[id]
        val value = if (bucketing != null) bucketing.valueOf(s.ints[id].toInt()) else s.ints[id]
        append(" $name=$value")
    }
}

/**
 * MPS output protocol (PB-competition-style `s`/`o`/`v`). When a variable was clamped to the finite
 * search range, a proven optimum is only optimal within the clamp and an `unsat` only holds within it,
 * so both are softened (to `SATISFIABLE` / `UNKNOWN`) — the honest verdict for the unbounded problem.
 */
internal class MpsOutput(private val clamped: Boolean) : BufferedBestOutput() {
    override val commentPrefix: String = "c"
    override val streamObjective: Boolean = true

    override fun statusLine(verdict: Verdict): String = when (verdict) {
        Verdict.SATISFIABLE, Verdict.BEST_FOUND -> "s SATISFIABLE"
        Verdict.OPTIMAL -> if (clamped) "s SATISFIABLE" else "s OPTIMUM FOUND"
        Verdict.UNSATISFIABLE -> if (clamped) "s UNKNOWN" else "s UNSATISFIABLE"
        Verdict.UNKNOWN -> "s UNKNOWN"
    }

    override fun keepStat(key: String): Boolean = true
}
