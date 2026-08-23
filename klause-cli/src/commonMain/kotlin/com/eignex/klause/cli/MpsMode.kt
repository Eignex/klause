package com.eignex.klause.cli

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.MpsCompiled
import com.eignex.klause.formats.mps.MpsFormatException
import com.eignex.klause.formats.mps.toProblem
import com.eignex.klause.solver.ProblemPipeline
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.pipeline
import com.eignex.klause.theory.lia.GeneralLiaAssignment

/**
 * MPS (Mathematical Programming System) MIP front-end (`.mps`). Parses the instance and lowers it to
 * klause's hybrid model (see [com.eignex.klause.formats.mps.toProblem]: integer columns become CP search
 * variables, float columns become LP-only continuous variables the simplex resolves). An open integer
 * model is routed to a complete supported theory pipeline or rejected at load.
 * Emits an `o <cost>` line per improving incumbent, then a final `s SATISFIABLE` / `s OPTIMUM FOUND` /
 * `s UNSATISFIABLE` / `s UNKNOWN` and a `v name=value` line. `-s` statistics are `c` comment lines.
 */
internal object MpsMode : CliMode {
    override val names = listOf("mps")
    override val extensions = listOf("mps")
    override fun newSession(): ModeSession = Session()

    private class Session : ModeSession {
        override fun flags(): List<FlagSpec> = emptyList()

        override fun load(path: String, common: CommonOptions): Solvable {
            val config = KlauseConfig.current
            val compiled = Mps.parse(openFileSource(path))
                .toProblem(config.floatBuckets, config.floatScale)
            cliLogger(common.verbose).v {
                "parsed ${fileName(path)}: int=${compiled.model.numIntVars} " +
                    "factors=${compiled.model.factors.size} float-cols=${compiled.floatColumns} " +
                    "objScale=${compiled.objectiveScale}"
            }
            val render: (Sample) -> String = { s -> renderMpsModel(compiled, s) }
            val pipeline = compiled.model.pipeline()
            when (pipeline) {
                ProblemPipeline.UNSUPPORTED_OPEN, ProblemPipeline.EXACT_LRA, ProblemPipeline.EXACT_LIRA ->
                    throw MpsFormatException("open MPS models require a supported theory pipeline")

                ProblemPipeline.DIFFERENCE_THEORY, ProblemPipeline.GENERAL_LIA -> {
                    if (compiled.objective != null) {
                        val theory = if (pipeline == ProblemPipeline.DIFFERENCE_THEORY) {
                            "difference-theory"
                        } else {
                            "General LIA"
                        }
                        throw MpsFormatException("open $theory optimization is unsupported")
                    }
                    return if (pipeline == ProblemPipeline.DIFFERENCE_THEORY) {
                        differenceTheorySolvable(compiled.model, render)
                    } else {
                        generalLiaSolvable(
                            compiled.model,
                        ) { assignment ->
                            renderMpsGeneralLiaModel(compiled, assignment)
                        }
                    }
                }

                ProblemPipeline.FINITE_CP -> Unit
            }
            return linearSolvable(
                compiled.model.materializeFiniteBounds(),
                compiled.objective,
                compiled.maximize,
                render,
            )
        }

        override fun output(common: CommonOptions): OutputProtocol = MpsOutput()
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

/** Render an exact General LIA witness without narrowing arbitrary-precision values to [Long]. */
internal fun renderMpsGeneralLiaModel(compiled: MpsCompiled, assignment: GeneralLiaAssignment): String = buildString {
    append("v")
    for (col in compiled.columns) {
        check(!col.real) { "General LIA does not admit continuous columns" }
        append(" ${col.name}=${assignment.ints[col.id]}")
    }
}

/** MPS output protocol (PB-competition-style `s`/`o`/`v`). */
internal class MpsOutput : BufferedBestOutput() {
    private var bestObjective: Long? = null

    override fun onSolutionObjective(objective: Long?) {
        if (objective != null) bestObjective = objective
    }

    override val commentPrefix: String = "c"
    override val streamObjective: Boolean = true

    override fun statusLine(verdict: Verdict): String = when (verdict) {
        Verdict.SATISFIABLE, Verdict.BEST_FOUND -> "s SATISFIABLE"
        Verdict.OPTIMAL -> "s OPTIMUM FOUND"
        Verdict.UNSATISFIABLE -> "s UNSATISFIABLE"
        Verdict.UNKNOWN -> "s UNKNOWN"
    }

    override fun keepStat(key: String): Boolean = true
}
