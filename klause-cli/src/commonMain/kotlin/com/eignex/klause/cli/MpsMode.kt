package com.eignex.klause.cli

import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.lowering.mps.MpsCompiled
import com.eignex.klause.lowering.mps.MpsLoweringException
import com.eignex.klause.lowering.mps.toProblem
import com.eignex.klause.solver.pipeline.ProblemPipeline
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.pipeline.sourceRoute
import com.eignex.klause.theory.lia.GeneralLiaAssignment
import com.eignex.klause.theory.qflra.ExactLiraAssignment

/**
 * MPS (Mathematical Programming System) MIP front-end (`.mps`). Parses the instance and lowers it to
 * klause's hybrid model (see [com.eignex.klause.lowering.mps.toProblem]: integer columns become CP search
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
        private var objectiveErrorBound: Double? = null
        private var hasInnerConstraintApproximation = false

        override fun flags(): List<FlagSpec> = emptyList()

        override fun load(path: String, common: CommonOptions): Solvable {
            val compiled = Mps.parse(openFileSource(path)).toProblem()
            objectiveScale = compiled.objectiveScale
            objectiveErrorBound = compiled.objectiveErrorBound
            hasInnerConstraintApproximation = compiled.hasInnerConstraintApproximation
            cliLogger(common.verbose).v {
                "parsed ${fileName(path)}: int=${compiled.model.numIntVars} " +
                    "factors=${compiled.model.factors.size} float-cols=${compiled.floatColumns} " +
                    "objScale=${compiled.objectiveScale}"
            }
            val render: (Sample) -> String = { s -> renderMpsModel(compiled, s) }
            val pipeline = compiled.model.sourceRoute()
            when (pipeline) {
                ProblemPipeline.UNSUPPORTED_OPEN, ProblemPipeline.EXACT_LRA ->
                    throw MpsLoweringException("open MPS models require a supported theory pipeline")

                // A mixed open model decides through the exact core. Its objective is minimized by the
                // same integral descent the pure-integer routes use when it weights only integer columns;
                // one weighting a continuous column has no next value to step to and is declined.
                ProblemPipeline.EXACT_LIRA -> {
                    val objective = compiled.objective
                    if (objective != null && objective.realCoefficients.any { it != 0.0 }) {
                        throw MpsLoweringException("open MPS optimization over a continuous objective is unsupported")
                    }
                    return exactLiraSolvable(compiled.model, objective, compiled.maximize) { assignment ->
                        renderMpsExactLiraModel(compiled, assignment)
                    }
                }

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

        override fun output(common: CommonOptions): OutputProtocol =
            MpsOutput(objectiveScale, objectiveErrorBound, hasInnerConstraintApproximation)
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

/** Render an exact mixed witness: an integer column at full precision, a continuous one as its exact
 *  rational value rather than a rounding of it. */
internal fun renderMpsExactLiraModel(compiled: MpsCompiled, assignment: ExactLiraAssignment): String = buildString {
    append("v")
    for (col in compiled.columns) {
        val value = if (col.real) assignment.reals[col.id].toString() else assignment.ints[col.id].toString()
        append(" ${col.name}=$value")
    }
}

/** MPS output protocol (PB-competition-style `s`/`o`/`v`). */
internal class MpsOutput(
    private val objectiveScale: Long = 1L,
    private val objectiveErrorBound: Double? = null,
    private val hasInnerConstraintApproximation: Boolean = false,
) : BufferedBestOutput() {
    private var bestObjective: Long? = null

    override fun onSolutionObjective(objective: Long?) {
        if (objective != null) bestObjective = objective
    }

    override val commentPrefix: String = "c"
    override val streamObjective: Boolean = true

    override fun formatObjective(objective: Long): String = scaledDecimal(objective, objectiveScale)

    override fun statusLine(verdict: Verdict): String = when (verdict) {
        Verdict.SATISFIABLE, Verdict.BEST_FOUND, Verdict.OPTIMAL ->
            if (verdict == Verdict.OPTIMAL && objectiveErrorBound == null && !hasInnerConstraintApproximation) {
                "s OPTIMUM FOUND"
            } else {
                "s SATISFIABLE"
            }

        Verdict.UNSATISFIABLE -> if (hasInnerConstraintApproximation) "s UNKNOWN" else "s UNSATISFIABLE"

        Verdict.UNKNOWN -> "s UNKNOWN"
    }

    override fun keepStat(key: String): Boolean = true

    override fun verdictReason(verdict: Verdict): String? {
        val approximation = objectiveErrorBound?.let {
            if (verdict == Verdict.OPTIMAL && !hasInnerConstraintApproximation) {
                "objective approximation error <= $it; retained objective is optimal"
            } else {
                "objective approximation error <= $it"
            }
        }
        val constraintQualification = if (hasInnerConstraintApproximation) {
            when (verdict) {
                Verdict.SATISFIABLE, Verdict.BEST_FOUND, Verdict.OPTIMAL ->
                    "satisfying assignment passed the inner constraint approximation"

                Verdict.UNSATISFIABLE -> "inner constraint approximation is infeasible; source boundary is unresolved"

                Verdict.UNKNOWN -> "inner constraint approximation leaves the source boundary unresolved"
            }
        } else {
            null
        }
        val cause = super.verdictReason(verdict)
        return listOfNotNull(approximation, constraintQualification, cause).joinToString("; ").ifEmpty { null }
    }
}

/** Format an MPS retained-objective value whose solver coefficients were scaled by [scale]. */
private fun scaledDecimal(value: Long, scale: Long): String {
    require(scale > 0L)
    if (scale == 1L || value % scale == 0L) return (value / scale).toString()
    val digits = scale.toString().length - 1
    val negative = value < 0L
    val whole = (value / scale).toString().removePrefix("-")
    val fraction = (value % scale).toString().removePrefix("-").padStart(digits, '0').trimEnd('0')
    return "${if (negative) "-" else ""}$whole.$fraction"
}
