package com.eignex.klause.cli

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.MpsCompiled
import com.eignex.klause.formats.mps.toProblem
import com.eignex.klause.solver.Sample

/**
 * MPS (Mathematical Programming System) MIP front-end (`.mps`). Parses the instance and lowers it to
 * klause's hybrid model (see [com.eignex.klause.formats.mps.toProblem]: integer columns become CP search
 * variables, float columns become LP-only continuous variables the simplex resolves, unbounded ints
 * clamped to the search range).
 * Emits an `o <cost>` line per improving incumbent, then a final `s SATISFIABLE` / `s OPTIMUM FOUND` /
 * `s UNSATISFIABLE` / `s UNKNOWN` and a `v name=value` line. `-s` statistics are `c` comment lines.
 */
internal object MpsMode : CliMode {
    override val names = listOf("mps")
    override val extensions = listOf("mps")
    override fun newSession(): ModeSession = Session()

    private class Session : ModeSession {
        // Shared with the deferred bounding: set once the presolve-phase OBBT decides whether a side fell
        // back to a lossy clamp, and read by output() at status-line time (after solving) — so a proven
        // optimum/unsat over a clamped box is reported honestly.
        private val clamp = ClampFlag()

        override fun flags(): List<FlagSpec> = emptyList()

        override fun load(path: String, common: CommonOptions): Solvable {
            val config = KlauseConfig.current
            val compiled = Mps.parse(openFileSource(path))
                .toProblem(config.unboundedSearchBound, config.floatBuckets, config.floatScale)
            cliLogger(common.verbose).v {
                "parsed ${fileName(path)}: int=${compiled.problem.numIntVars} " +
                    "factors=${compiled.problem.numFactors} float-cols=${compiled.floatColumns} " +
                    "objScale=${compiled.objectiveScale}"
            }
            val render: (Sample) -> String = { s -> renderMpsModel(compiled, s) }
            val base = linearSolvable(compiled.problem, compiled.objective, compiled.maximize, render)
            // Defer OBBT into the presolve phase: compiling only reads, and the LP tightening runs under the
            // presolve budget instead of unbounded at load. The run also decides the clamp verdict. Absent
            // when every integer column is already finite (nothing to bound, never clamped).
            val deferred = compiled.deferredBounds ?: return base
            return base.withDeferredBounds { cancellation ->
                val bounded = deferred.run(cancellation)
                clamp.clamped = bounded.clamped
                if (bounded.openlyInfeasible) {
                    // Refuted over the genuinely open ranges: no solution anywhere, not merely none in
                    // the search box, so the verdict carries no clamp caveat.
                    refutedProblem(compiled.problem)
                } else {
                    compiled.problem.withIntDomains(bounded.domains, bounded.openLo, bounded.openHi)
                }
            }
        }

        override fun output(common: CommonOptions): OutputProtocol = MpsOutput(clamp)
    }
}

/** Render an MPS solution line: `v name=value` per column, a continuous column shown as its LP value. */
internal fun renderMpsModel(compiled: MpsCompiled, s: Sample): String = buildString {
    append("v")
    for (col in compiled.columns) {
        val value = if (col.real) s.reals[col.id] else s.ints[col.id]
        append(" ${col.name}=$value")
    }
}

/**
 * MPS output protocol (PB-competition-style `s`/`o`/`v`). When a variable was clamped to the finite
 * search range, a proven optimum is only optimal within the clamp, so it is softened to `SATISFIABLE`.
 */
internal class MpsOutput(private val clamp: ClampFlag = ClampFlag()) : BufferedBestOutput() {
    override val commentPrefix: String = "c"
    override val streamObjective: Boolean = true

    override fun statusLine(verdict: Verdict): String = when (verdict) {
        Verdict.SATISFIABLE, Verdict.BEST_FOUND -> "s SATISFIABLE"
        Verdict.OPTIMAL -> if (clamp.clamped) "s SATISFIABLE" else "s OPTIMUM FOUND"
        Verdict.UNSATISFIABLE -> "s UNSATISFIABLE"
        Verdict.UNKNOWN -> "s UNKNOWN"
    }

    override fun keepStat(key: String): Boolean = true
}
