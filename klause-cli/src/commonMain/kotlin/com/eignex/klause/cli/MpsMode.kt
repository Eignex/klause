package com.eignex.klause.cli

import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.MpsCompiled
import com.eignex.klause.formats.mps.MpsLoweringException
import com.eignex.klause.formats.mps.toProblem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.solver.pipeline.SourceProblemRoute
import com.eignex.klause.solver.pipeline.pipelineRoute
import kotlin.math.abs
import kotlin.math.floor

/**
 * MPS (Mathematical Programming System) MIP front-end (`.mps`). Parses the instance and lowers it to
 * klause's hybrid model (see [com.eignex.klause.formats.mps.toProblem]: integer columns become CP search
 * variables, float columns become LP-only continuous variables the simplex resolves). An open integer
 * model is routed to a complete supported theory pipeline or rejected at load.
 * Emits an `o <cost>` line per improving incumbent, then a final `s SATISFIABLE` / `s OPTIMUM FOUND` /
 * `s UNBOUNDED` / `s UNSATISFIABLE` / `s UNKNOWN` and a `v name=value` line. `-s` statistics are `c`
 * comment lines.
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
            val objective = compiled.objective?.toLinearObjective()
            return when (val route = compiled.model.pipelineRoute(objective, compiled.maximize)) {
                is SourceProblemRoute.Finite -> linearSolvable(
                    route.problem,
                    objective,
                    compiled.maximize,
                    render,
                )

                is SourceProblemRoute.OpenTheory -> {
                    if (compiled.model.numIntVars == 0 && compiled.model.numRealVars != 0) {
                        unsupportedOpenMpsModel()
                    }
                    if (compiled.objective?.realCoefficients?.any { it != 0.0 } == true) {
                        throw MpsLoweringException("open MPS optimization over a continuous objective is unsupported")
                    }
                    openTheorySolvable(route.request) { assignment -> renderMpsOpenModel(compiled, assignment) }
                }

                is SourceProblemRoute.UnsupportedOpen -> unsupportedOpenMpsModel()
            }
        }

        override fun output(common: CommonOptions): OutputProtocol =
            MpsOutput(objectiveScale, objectiveErrorBound, hasInnerConstraintApproximation)
    }
}

private fun unsupportedOpenMpsModel(): Nothing =
    throw MpsLoweringException("open MPS models require a supported theory pipeline")

/** Render an MPS solution line: `v name=value` per column, a continuous column shown as its LP value. */
internal fun renderMpsModel(compiled: MpsCompiled, s: Sample): String = buildString {
    append("v")
    for (col in compiled.columns) {
        val value = if (col.real) s.reals[col.id] else s.ints[col.id]
        append(" ${col.name}=$value")
    }
}

/** Render an open-theory witness without depending on the concrete theory that produced it. */
internal fun renderMpsOpenModel(compiled: MpsCompiled, assignment: OpenTheoryAssignment): String = buildString {
    append("v")
    for (col in compiled.columns) {
        val value = if (col.real) assignment.realValue(col.id) else assignment.intValue(col.id)
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

    /** The solver value is on [objectiveScale] like the integral one, so undo the scale and print the
     *  decimal the source states. */
    override fun formatContinuousObjective(objective: Double): String = scaledDecimal(objective, objectiveScale)

    override fun statusLine(verdict: Verdict): String = when (verdict) {
        Verdict.SATISFIABLE, Verdict.BEST_FOUND, Verdict.OPTIMAL ->
            if (verdict == Verdict.OPTIMAL && objectiveErrorBound == null && !hasInnerConstraintApproximation) {
                "s OPTIMUM FOUND"
            } else {
                "s SATISFIABLE"
            }

        Verdict.UNBOUNDED -> "s UNBOUNDED"

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
                Verdict.SATISFIABLE, Verdict.BEST_FOUND, Verdict.OPTIMAL, Verdict.UNBOUNDED ->
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

/** Format an MPS objective whose continuous columns make it non-integral. A value that lands on a whole
 *  number after unscaling prints as one, so a model whose reals happen to resolve integrally reads the
 *  same as a purely discrete one. */
private fun scaledDecimal(value: Double, scale: Long): String {
    require(scale > 0L)
    val unscaled = value / scale
    if (unscaled == floor(unscaled) && abs(unscaled) < MAX_EXACT_WHOLE) return unscaled.toLong().toString()
    return unscaled.toString()
}

/** Largest magnitude a `Double` represents every whole number below; past it the integral shortcut in
 *  [scaledDecimal] would print a rounded neighbour as though it were exact. */
private const val MAX_EXACT_WHOLE = 9.007199254740992E15

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
