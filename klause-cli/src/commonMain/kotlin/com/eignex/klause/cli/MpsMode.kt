package com.eignex.klause.cli

import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.MpsCompiled
import com.eignex.klause.formats.mps.MpsLoweringException
import com.eignex.klause.formats.mps.MpsSourceWitness
import com.eignex.klause.formats.mps.toProblem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.solver.pipeline.SourceProblemRoute
import com.eignex.klause.solver.pipeline.pipelineRoute
import com.eignex.klause.solver.result.LpStats
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.math.abs
import kotlin.math.floor
import kotlin.time.TimeSource

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
        private var sourceExact = true
        private var sourceDifference: String? = null
        private var latestSourceWitness: MpsSourceWitness? = null

        override fun flags(): List<FlagSpec> = emptyList()

        override fun load(path: String, common: CommonOptions): Solvable {
            val compiled = Mps.parse(openFileSource(path)).toProblem()
            objectiveScale = compiled.objectiveScale
            objectiveErrorBound = compiled.objectiveErrorBound
            hasInnerConstraintApproximation = compiled.hasInnerConstraintApproximation
            sourceExact = compiled.sourceExact
            sourceDifference = compiled.sourceDifference
            latestSourceWitness = null
            cliLogger(common.verbose).v {
                "parsed ${fileName(path)}: int=${compiled.model.numIntVars} " +
                    "factors=${compiled.model.factors.size} float-cols=${compiled.floatColumns} " +
                    "objScale=${compiled.objectiveScale}"
            }
            val render: (Sample) -> String = { sample ->
                val witness = compiled.sourceWitness(sample.ints, sample.exactReals)
                latestSourceWitness = witness
                renderMpsWitness(compiled, witness)
            }
            val objective = compiled.objective?.toLinearObjective()
            var routingLpStats = LpStats()
            val routingStart = TimeSource.Monotonic.markNow()
            val route = compiled.model.pipelineRoute(
                objective,
                compiled.maximize,
                boundCancellation = common.routingCancellation(),
                onLpStats = { routingLpStats = it },
            )
            val routingElapsedMs = routingStart.elapsedNow().inWholeMilliseconds
            return when (route) {
                is SourceProblemRoute.Finite -> linearSolvable(
                    route.problem,
                    objective,
                    compiled.maximize,
                    render,
                    routingLpStats = routingLpStats,
                    routingElapsedMs = routingElapsedMs,
                )

                is SourceProblemRoute.OpenTheory -> {
                    if (compiled.model.numIntVars == 0 && compiled.model.numRealVars != 0) {
                        unsupportedOpenMpsModel()
                    }
                    if (!compiled.sourceExact) {
                        throw MpsLoweringException(
                            "open MPS source differs from the lowered model at ${compiled.sourceDifference}",
                        )
                    }
                    if (compiled.objective?.realCoefficients?.any { it != 0.0 } == true) {
                        throw MpsLoweringException("open MPS optimization over a continuous objective is unsupported")
                    }
                    openTheorySolvable(
                        route.request,
                        { assignment -> renderMpsOpenModel(compiled, assignment) },
                        routingLpStats,
                        routingElapsedMs,
                    )
                }

                is SourceProblemRoute.UnsupportedOpen -> unsupportedOpenMpsModel()

                SourceProblemRoute.Refuted -> refutedSolvable(routingLpStats, routingElapsedMs)
            }
        }

        override fun output(common: CommonOptions): OutputProtocol = MpsOutput(
            objectiveScale,
            objectiveErrorBound,
            hasInnerConstraintApproximation,
            sourceExact,
            sourceDifference,
            { latestSourceWitness },
        )
    }
}

private fun unsupportedOpenMpsModel(): Nothing =
    throw MpsLoweringException("open MPS models require a supported theory pipeline")

/** Render an MPS solution line: `v name=value` per column, a continuous column shown as its LP value. */
internal fun renderMpsModel(compiled: MpsCompiled, s: Sample): String =
    renderMpsWitness(compiled, compiled.sourceWitness(s.ints, s.exactReals))

private fun renderMpsWitness(compiled: MpsCompiled, witness: MpsSourceWitness): String = buildString {
    append("v")
    for (index in compiled.columns.indices) {
        append(" ${compiled.columns[index].name}=${exactMpsNumber(witness.values[index])}")
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
    private val sourceExact: Boolean = true,
    private val sourceDifference: String? = null,
    private val sourceWitness: () -> MpsSourceWitness? = { null },
) : BufferedBestOutput() {
    private var bestObjective: Long? = null

    override fun onSolutionObjective(objective: Long?) {
        if (objective != null) bestObjective = objective
    }

    override val commentPrefix: String = "c"
    override val streamObjective: Boolean = true

    override fun formatObjective(objective: Long): String =
        sourceWitness()?.let { exactMpsNumber(it.objective) } ?: scaledDecimal(objective, objectiveScale)

    /** The solver value is on [objectiveScale] like the integral one, so undo the scale and print the
     *  decimal the source states. */
    override fun formatContinuousObjective(objective: Double): String =
        sourceWitness()?.let { exactMpsNumber(it.objective) } ?: scaledDecimal(objective, objectiveScale)

    override fun statusLine(verdict: Verdict): String = when (verdict) {
        Verdict.SATISFIABLE, Verdict.BEST_FOUND, Verdict.OPTIMAL ->
            if (verdict == Verdict.OPTIMAL && objectiveErrorBound == null &&
                !hasInnerConstraintApproximation && sourceExact
            ) {
                "s OPTIMUM FOUND"
            } else {
                "s SATISFIABLE"
            }

        Verdict.UNBOUNDED -> when {
            sourceExact -> "s UNBOUNDED"
            best != null -> "s SATISFIABLE"
            else -> "s UNKNOWN"
        }

        Verdict.UNSATISFIABLE -> if (hasInnerConstraintApproximation || !sourceExact) {
            "s UNKNOWN"
        } else {
            "s UNSATISFIABLE"
        }

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
        val sourceQualification = sourceDifference?.let { "lowered model differs from MPS source at $it" }
        return listOfNotNull(approximation, constraintQualification, sourceQualification, cause)
            .joinToString("; ").ifEmpty { null }
    }
}

/** Print terminating rationals as exact decimals and other rationals as reduced fractions. */
private fun exactMpsNumber(value: BigFraction): String {
    val negative = value.signum() < 0
    val magnitude = if (negative) -value.num else value.num
    val whole = magnitude / value.den
    var remainder = magnitude % value.den
    if (remainder == BigInteger.ZERO) return "${if (negative) "-" else ""}$whole"
    val fractional = StringBuilder()
    repeat(64) {
        remainder *= BigInteger.fromLong(10L)
        fractional.append(remainder / value.den)
        remainder %= value.den
        if (remainder == BigInteger.ZERO) {
            return "${if (negative) "-" else ""}$whole.$fractional"
        }
    }
    return value.toString()
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
