package com.eignex.klause.cli

import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.MpsCompiled
import com.eignex.klause.formats.mps.MpsLoweringException
import com.eignex.klause.formats.mps.MpsSourceWitness
import com.eignex.klause.formats.mps.mpsSemantics
import com.eignex.klause.formats.mps.toExactSourceProblem
import com.eignex.klause.formats.mps.toProblem
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.pipeline.NumericalLinearRequest
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.solver.pipeline.SourceProblemRoute
import com.eignex.klause.solver.result.LpStats
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.parseBigInt
import kotlin.math.abs
import kotlin.math.floor
import kotlin.time.TimeSource

/**
 * MPS (Mathematical Programming System) MIP front-end (`.mps`). Parses the instance and lowers it to
 * klause's hybrid model (see [com.eignex.klause.formats.mps.toProblem]: integer columns become CP search
 * variables, float columns become LP-only continuous variables the simplex resolves). An open integer
 * model uses numerical relaxations and source-certified integer branching.
 * Emits an `o <cost>` line per improving incumbent, then a final `s SATISFIABLE` / `s OPTIMUM FOUND` /
 * `s UNBOUNDED` / `s UNSATISFIABLE` / `s UNKNOWN` and a `v name=value` line. `-s` statistics are `c`
 * comment lines.
 */
internal object MpsMode : CliMode {
    override val names = listOf("mps")
    override val extensions = listOf("mps")
    override fun newSession(): ModeSession = Session()

    private class Session : ModeSession {
        private var outputState = MpsOutputState()

        override fun flags(): List<FlagSpec> = emptyList()

        override fun load(path: String, common: CommonOptions): Solvable {
            val semanticOverrides = common.sourceOverrides(mpsSemantics)
            val source = Mps.parse(openFileSource(path))
            val settings = common.config.problemSettings()
            val exact = checkNotNull(common.sourceSemantics).effective.feasibilityTolerance == SourceTolerance()
            val lowered = try {
                source.toProblem(settings, semanticOverrides)
            } catch (_: MpsLoweringException) {
                source.toExactSourceProblem(settings, semanticOverrides)
            }
            val exactSource = source.toExactSourceProblem(settings, semanticOverrides)
            val sourceAdmission = NumericalLinearRequest(exactSource.model,
                cancellation = common.routingCancellation()).numericalRowsSupported()
            var compiled = if (!sourceAdmission || (exact && !lowered.sourceExact)) exactSource else lowered
            var routingLpStats = LpStats()
            val routingStart = TimeSource.Monotonic.markNow()
            var numerical = NumericalLinearRequest(compiled.model, compiled.objective?.toLinearObjective(),
                compiled.maximize, common.routingCancellation())
            var route = numerical.finiteRoute { routingLpStats = it }
            if ((route == null || route == SourceProblemRoute.Refuted) && !compiled.sourceExact) {
                compiled = exactSource
                numerical = NumericalLinearRequest(compiled.model, compiled.objective?.toLinearObjective(),
                    compiled.maximize, common.routingCancellation())
                route = numerical.finiteRoute { routingLpStats = it }
            }
            val routingElapsedMs = routingStart.elapsedNow().inWholeMilliseconds
            if (route != null) common.logRoute(route, routingElapsedMs)
            else common.frontendRouting = CliRoutingStats("numerical-mip", routingElapsedMs)
            if (exact) requireExactMpsSource(compiled)
            val state = MpsOutputState(compiled.objectiveScale, compiled.toleranceDifference,
                compiled.sourceExact, compiled.sourceDifference,
                compiled.semantics.declared.undeclaredIntegers != compiled.semantics.effective.undeclaredIntegers ||
                    compiled.semantics.declared.undeclaredReals != compiled.semantics.effective.undeclaredReals)
            outputState = state
            cliLogger(common.verbose).v {
                "parsed ${fileName(path)}: int=${compiled.model.numIntVars} " +
                    "factors=${compiled.model.factors.size} float-cols=${compiled.floatColumns} " +
                    "objScale=${compiled.objectiveScale}"
            }
            val render: (Sample) -> String = { sample ->
                if (sample.exactReals == null && compiled.model.numRealVars > 0) {
                    // A float leaf under tolerance semantics: its values are the doubles themselves.
                    val witness = compiled.sourceWitness(compiled.columns.map { column ->
                        if (column.real) checkNotNull(BigFraction.ofDouble(sample.reals[column.id]))
                        else BigFraction.ofLong(sample.ints[column.id])
                    })
                    state.latestSourceObjective = witness.objective
                    state.exactSourceWitness = witness.exact
                    state.exactSourceOptimum = false
                    renderMpsFloat(compiled, sample)
                } else {
                    val witness = compiled.sourceWitness(sample.ints, sample.exactReals)
                    state.latestSourceObjective = witness.objective
                    state.exactSourceWitness = witness.exact
                    state.exactSourceOptimum = compiled.sourceExact && witness.exact
                    renderMpsWitness(compiled, witness)
                }
            }
            val renderOpen: (OpenTheoryAssignment) -> String = { assignment ->
                val witness = try {
                    compiled.sourceWitness(compiled.columns.map { column ->
                        val value = if (column.real) {
                            assignment.realValue(column.id)
                        } else {
                            assignment.intValue(column.id)
                        }
                        parseOpenMpsNumber(value)
                    })
                } catch (_: IllegalArgumentException) {
                    null
                }
                state.sourceWitnessAccepted = witness != null
                state.latestSourceObjective = witness?.objective
                state.exactSourceWitness = witness?.exact
                state.exactSourceOptimum = witness?.exact == true && compiled.sourceExact
                renderMpsOpenModel(compiled, assignment)
            }
            val objective = compiled.objective?.toLinearObjective()
            val finite = route as? SourceProblemRoute.Finite
            return if (route == SourceProblemRoute.Refuted) {
                refutedSolvable(routingLpStats, routingElapsedMs)
            } else if (finite != null) {
                mpsLinearSolvable(compiled, common.exact, linearSolvable(
                    finite.problem,
                    if (compiled.maximize) objective?.negated() else objective,
                    compiled.maximize,
                    render,
                    routingLpStats = routingLpStats,
                    routingElapsedMs = routingElapsedMs,
                    preparedRoute = finite,
                )).withNumericalCompletion()
            } else {
                Solvable(finite = null, render = render, objectiveValue = null,
                    pipeline = SolvablePipeline.NumericalLinear(
                        NumericalLinearRequest(compiled.model, objective, compiled.maximize), renderOpen),
                    routingLpStats = routingLpStats, routingElapsedMs = routingElapsedMs)
            }
        }

        override fun output(common: CommonOptions): OutputProtocol {
            val state = outputState
            return MpsOutput(
                state.objectiveScale,
                state.toleranceDifference == null && !state.witnessOnly,
                state.toleranceDifference ?: state.sourceDifference,
                { state.latestSourceObjective },
                state.sourceExact && !state.witnessOnly,
                { state.sourceWitnessAccepted },
                { state.exactSourceWitness },
                { _ ->
                    if (state.sourceWitnessAccepted) state.latestSourceObjective?.let(SourceValue::Rational) else null
                },
                exactSourceOptimum = { state.exactSourceOptimum },
                effectiveDeclarations = state.effectiveDeclarations,
            )
        }
    }
}

private class MpsOutputState(
    val objectiveScale: Long = 1L,
    val toleranceDifference: String? = null,
    val sourceExact: Boolean = true,
    val sourceDifference: String? = null,
    val effectiveDeclarations: Boolean = false,
) {
    var latestSourceObjective: BigFraction? = null
    var sourceWitnessAccepted = true
    var witnessOnly = false
    var exactSourceWitness: Boolean? = null
    var exactSourceOptimum: Boolean? = null
}

private fun parseOpenMpsNumber(value: String): BigFraction {
    val parts = value.split('/')
    return BigFraction.of(parseBigInt(parts[0]), if (parts.size == 1) BIG_ONE else parseBigInt(parts[1]))
}

// The effective source policy checks every floating leaf before publication.
private fun mpsLinearSolvable(compiled: MpsCompiled, exact: Boolean, solvable: Solvable): Solvable = if (exact) {
    solvable
} else {
    solvable.withToleranceCheck { sample ->
        val reals = sample.exactReals
        if (reals == null) {
            compiled.withinTolerance(sample.ints, sample.reals)
        } else {
            try {
                compiled.sourceWitness(sample.ints, reals)
                true
            } catch (_: IllegalArgumentException) {
                false
            }
        }
    }
}

private fun requireExactMpsSource(compiled: MpsCompiled) {
    if (!compiled.sourceExact) {
        throw MpsLoweringException("exact MPS lowering differs from the source at ${compiled.sourceDifference}")
    }
}

internal fun renderMpsFloat(compiled: MpsCompiled, sample: Sample): String {
    checkedIntegralMpsFloat(compiled, sample)?.let { return renderMpsWitness(compiled, it) }
    return buildString {
        append("v")
        for (column in compiled.columns) {
            val value = if (column.real) {
                sample.approximateRealValue(column.id).toString()
            } else {
                sample.intValue(column.id).toString()
            }
            append(" ${column.name}=$value")
        }
    }
}

private fun checkedIntegralMpsFloat(compiled: MpsCompiled, sample: Sample): MpsSourceWitness? {
    if (!compiled.sourceExact || compiled.columns.size > 4096 || sample.reals.size > 4096 ||
        sample.reals.none { it != 0.0 && abs(it) <= 1e-12 }
    ) return null
    val values = sample.reals.map { value ->
        if (!value.isFinite()) return null
        if (abs(value) <= 1e-12) BigFraction.ZERO else {
            val exact = BigFraction.ofDouble(value) ?: return null
            if (exact.den != BIG_ONE || !exact.num.fitsLong()) return null
            exact
        }
    }
    val witness = compiled.sourceWitnessOrNull(sample.ints, values, maxEntries = 32768) ?: return null
    return witness.takeIf { it.exact && it.objective == compiled.sourceObjective(sample.ints, sample.reals) }
}

/** Render an MPS solution line: `v name=value` per column, each value from the exact source witness. */
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
    private val sourceExact: Boolean = true,
    private val sourceDifference: String? = null,
    private val sourceObjective: () -> BigFraction? = { null },
    // An infeasibility or unboundedness proof covers the lowered model only, so it needs the source exactly.
    private val proofExact: Boolean = sourceExact,
    private val sourceWitnessAccepted: () -> Boolean = { true },
    private val exactSourceWitness: () -> Boolean? = { null },
    private val sourceArithmeticValue: ((SourceValue) -> SourceValue?)? = null,
    private val exactSourceOptimum: () -> Boolean? = exactSourceWitness,
    private val effectiveDeclarations: Boolean = false,
) : BufferedBestOutput() {
    private var bestObjective: Long? = null

    override fun acceptsSourceWitness(): Boolean = sourceWitnessAccepted()

    override fun onSolutionObjective(objective: Long?) {
        if (objective != null) bestObjective = objective
    }

    override val commentPrefix: String = "c"
    override val streamObjective: Boolean = true

    override fun formatObjective(objective: Long): String =
        sourceObjective()?.let(::exactMpsNumber) ?: scaledDecimal(objective, objectiveScale)

    /** Print the checked source objective when available; otherwise undo the solver's scale. */
    override fun formatContinuousObjective(objective: Double): String =
        sourceObjective()?.let(::exactMpsNumber) ?: scaledDecimal(objective, objectiveScale)

    override fun formatExactObjective(objective: BigFraction): String =
        exactMpsNumber(sourceObjective() ?: (objective * BigFraction.ofLong(objectiveScale).reciprocal()))

    override fun arithmeticSourceValue(objective: SourceValue): SourceValue? =
        if (sourceArithmeticValue == null) objective else sourceArithmeticValue.invoke(objective)

    override fun arithmeticWitnessStatus(): String? = when (exactSourceWitness()) {
        true -> "c exact $sourceScope MPS source witness"
        false -> "c MPS source tolerance witness"
        null -> null
    }

    override fun formatArithmeticObjective(objective: SourceValue): String = when (objective) {
        is SourceValue.Rational -> exactMpsNumber(objective.value)
        is SourceValue.Algebraic -> objective.value.rational?.let(::exactMpsNumber)
            ?: arithmeticObjectiveText(objective)
        is SourceValue.Extension -> arithmeticObjectiveText(objective)
    }

    override fun statusLine(verdict: Verdict): String = when (verdict) {
        Verdict.SATISFIABLE, Verdict.BEST_FOUND, Verdict.OPTIMAL ->
            if (best == null) {
                "s UNKNOWN"
            } else if (verdict == Verdict.OPTIMAL && sourceExact && sourceWitnessAccepted()) {
                "s OPTIMUM FOUND"
            } else {
                "s SATISFIABLE"
            }

        Verdict.UNBOUNDED -> when {
            proofExact && sourceWitnessAccepted() && exactSourceWitness() != false -> "s UNBOUNDED"
            best != null -> "s SATISFIABLE"
            else -> "s UNKNOWN"
        }

        Verdict.UNSATISFIABLE -> if (proofExact) "s UNSATISFIABLE" else "s UNKNOWN"

        Verdict.UNKNOWN -> "s UNKNOWN"
    }

    override fun lpStatisticPairs(stats: SolveStats): List<Pair<String, String>> {
        val pairs = super.lpStatisticPairs(stats)
        return if (pairs.any { it.first == "lpRootBound" }) {
            pairs + ("lpRootBoundScale" to "$objectiveScale")
        } else pairs
    }

    override fun keepStat(key: String): Boolean = true

    override fun completionMetadata(verdict: Verdict): String? {
        if (verdict != Verdict.OPTIMAL || best == null || !sourceExact || !sourceWitnessAccepted()) return null
        val status = when (exactSourceWitness()) {
            true -> if (proofExact && exactSourceOptimum() == true) "attained-optimum" else "tolerance-optimum"
            false -> "tolerance-optimum"
            null -> return null
        }
        return "optimizationStatus=$status"
    }

    override fun verdictReason(verdict: Verdict): String? {
        val cause = super.verdictReason(verdict)
        val sourceQualification = sourceDifference?.let { "lowered model differs from MPS source at $it" }
        val witnessQualification = when (exactSourceWitness()) {
            true -> "exact $sourceScope MPS source witness"
            false -> "MPS source tolerance witness"
            null -> null
        }
        val declarationQualification = if (effectiveDeclarations) {
            "MPS source scope=effective declaration defaults"
        } else null
        return listOfNotNull(declarationQualification, sourceQualification, witnessQualification, cause)
            .joinToString("; ").ifEmpty { null }
    }

    private val sourceScope: String get() = if (effectiveDeclarations) "effective" else "original"
}

/** Print terminating rationals within 64 decimal places as decimals, and others as fractions. */
private fun exactMpsNumber(value: BigFraction): String {
    val negative = value.signum() < 0
    val magnitude = if (negative) -value.num else value.num
    val whole = magnitude / value.den
    var remainder = magnitude % value.den
    if (remainder == BIG_ZERO) return "${if (negative) "-" else ""}$whole"
    val fractional = StringBuilder()
    repeat(64) {
        remainder *= bigIntOf(10L)
        fractional.append(remainder / value.den)
        remainder %= value.den
        if (remainder == BIG_ZERO) {
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
