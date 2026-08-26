package com.eignex.klause.cli

import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.MpsCompiled
import com.eignex.klause.formats.mps.MpsFormatException
import com.eignex.klause.formats.mps.toProblem
import com.eignex.klause.solver.ProblemPipeline
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.sourceRoute
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
        private var objectiveScale = 1L

        override fun flags(): List<FlagSpec> = emptyList()

        override fun load(path: String, common: CommonOptions): Solvable {
            val compiled = Mps.parse(openFileSource(path)).toProblem()
            objectiveScale = compiled.objectiveScale
            cliLogger(common.verbose).v {
                "parsed ${fileName(path)}: int=${compiled.model.numIntVars} " +
                    "factors=${compiled.model.factors.size} float-cols=${compiled.floatColumns} " +
                    "objScale=${compiled.objectiveScale}"
            }
            val render: (Sample) -> String = { s -> renderMpsModel(compiled, s) }
            val pipeline = compiled.model.sourceRoute()
            when (pipeline) {
                ProblemPipeline.UNSUPPORTED_OPEN, ProblemPipeline.EXACT_LRA, ProblemPipeline.EXACT_LIRA ->
                    throw MpsFormatException("open MPS models require a supported theory pipeline")

                ProblemPipeline.DIFFERENCE_THEORY, ProblemPipeline.GENERAL_LIA -> {
                    // An objective enters the open route as a row bounding it, which is outside the
                    // difference fragment, so an optimizing model takes General LIA either way.
                    return if (pipeline == ProblemPipeline.DIFFERENCE_THEORY && compiled.objective == null) {
                        differenceTheorySolvable(compiled.model, render)
                    } else {
                        generalLiaSolvable(
                            compiled.model,
                            compiled.objective,
                            compiled.maximize,
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

        override fun output(common: CommonOptions): OutputProtocol = MpsOutput(objectiveScale)
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
internal class MpsOutput(private val objectiveScale: Long = 1L) : BufferedBestOutput() {
    private var bestObjective: Long? = null

    override fun onSolutionObjective(objective: Long?) {
        if (objective != null) bestObjective = objective
    }

    override val commentPrefix: String = "c"
    override val streamObjective: Boolean = true

    override fun formatObjective(objective: Long): String = scaledDecimal(objective, objectiveScale)

    override fun statusLine(verdict: Verdict): String = when (verdict) {
        Verdict.SATISFIABLE, Verdict.BEST_FOUND -> "s SATISFIABLE"
        Verdict.OPTIMAL -> "s OPTIMUM FOUND"
        Verdict.UNSATISFIABLE -> "s UNSATISFIABLE"
        Verdict.UNKNOWN -> "s UNKNOWN"
    }

    override fun keepStat(key: String): Boolean = true
}

/** Format the exact decimal value of an MPS objective whose solver coefficients were scaled by [scale]. */
private fun scaledDecimal(value: Long, scale: Long): String {
    require(scale > 0L)
    if (scale == 1L || value % scale == 0L) return (value / scale).toString()
    val digits = scale.toString().length - 1
    val negative = value < 0L
    val whole = (value / scale).toString().removePrefix("-")
    val fraction = (value % scale).toString().removePrefix("-").padStart(digits, '0').trimEnd('0')
    return "${if (negative) "-" else ""}$whole.$fraction"
}
