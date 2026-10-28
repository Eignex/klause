package com.eignex.klause.cli

import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.MpsModel
import com.eignex.klause.formats.mps.checkMpsWitness
import com.eignex.klause.formats.mps.mpsSemantics
import com.eignex.klause.formats.mps.toLinearSourceProblem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.pipeline.FiniteSolveOutcome
import com.eignex.klause.solver.pipeline.LinearSourceConclusion
import com.eignex.klause.solver.pipeline.LinearSourceOutcome
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceExecutionResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.validation.LinearSourceWitness
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.fitsLong
import kotlin.math.abs
import kotlin.math.floor
import kotlin.time.TimeSource

/**
 * MPS (Mathematical Programming System) front-end (`.mps`). Captures original numerical declarations,
 * rows and objective once and registers them with the shared source invocation.
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
            val start = TimeSource.Monotonic.markNow()
            outputState = MpsOutputState()
            val overrides = common.sourceOverrides(mpsSemantics)
            val parsed = openFileSource(path).useSource { Mps.parse(it) }
            return common.routingPreparationCancellation().use { stop ->
                val source = parsed.toLinearSourceProblem(overrides, stop) ?: return interruptedSolvable(
                    if (stop()) TerminationReason.Timeout else TerminationReason.Cancelled,
                    start.elapsedNow().inWholeMilliseconds,
                )
                val state = MpsOutputState(
                    source.semantics.declared.undeclaredIntegers != source.semantics.effective.undeclaredIntegers ||
                        source.semantics.declared.undeclaredReals != source.semantics.effective.undeclaredReals,
                )
                outputState = state
                val reconstruct = parsed.fitsMpsReconstructionBudget(stop)
                linearSourceSolvable(
                    SourceAttachment(source, common.config.problemSettings()),
                    common,
                    { witness ->
                        state.exactSourceWitness = witness.exact
                        renderMpsSourceWitness(witness, reconstruct, stop)
                    },
                    start.elapsedNow().inWholeMilliseconds,
                )
            }
        }

        override fun output(common: CommonOptions): OutputProtocol {
            val state = outputState
            return MpsOutput(
                exactSourceWitness = { state.exactSourceWitness },
                effectiveDeclarations = state.effectiveDeclarations,
            )
        }
    }
}

private class MpsOutputState(val effectiveDeclarations: Boolean = false) {
    var exactSourceWitness: Boolean? = null
}

private fun MpsModel.fitsMpsReconstructionBudget(stop: Cancellation): Boolean {
    if (variables.size > 4096) return false
    var remaining = 32768L - variables.size - objective.indices.size
    if (remaining < 0L) return false
    for (row in constraints) {
        stop.charge(1L)
        if (stop()) return false
        remaining -= row.indices.size.toLong() + 1L
        if (remaining < 0L) return false
    }
    return true
}

internal fun renderMpsSourceWitness(
    witness: LinearSourceWitness,
    reconstruct: Boolean,
    cancellation: Cancellation = Cancellation.Never,
): String {
    val variables = witness.source.variables
    val original = witness.values
    val values = if (reconstruct && original.size <= 4096) {
        checkedIntegralMpsSource(witness, original, cancellation)?.values ?: original
    } else original
    return buildString {
        append("v")
        for (index in variables.indices) append(" ${variables[index].name}=${exactMpsNumber(values[index])}")
    }
}

private fun checkedIntegralMpsSource(
    witness: LinearSourceWitness,
    original: List<BigFraction>,
    stop: Cancellation,
): LinearSourceWitness? {
    if (stop()) return null
    val variables = witness.source.variables
    var changed = false
    val candidate = original.mapIndexed { index, value ->
        stop.charge(1L)
        if (stop()) return null
        when {
            variables[index].integer || value.isZero -> value
            abs(value.toDouble()) <= 1e-12 -> BigFraction.ZERO.also { changed = true }
            value.den == BIG_ONE && value.num.fitsLong() -> value
            else -> return null
        }
    }
    if (!changed) return null
    val checked = witness.source.checkMpsWitness(candidate, stop) ?: return null
    return checked.takeIf { it.exact && it.objective == witness.objective }
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
    private val claimsCoverTolerance: Boolean = true,
    private val acceptedObjectiveBound: BigFraction? = null,
) : BufferedBestOutput() {
    private var bestObjective: Long? = null
    private var linearSource: LinearSourceOutcome? = null
    private var nativeOptimumExact = false

    override fun onLinearSourceResult(result: SourceExecutionResult) {
        val qualified = result.linearSource ?: return
        linearSource = qualified
        nativeOptimumExact = if (result is SourceExecutionResult.Finite) {
            (result.result.outcome as? FiniteSolveOutcome.Completed)?.bestSample?.let {
                it.numRealVars == 0 || it.exactReals != null
            } == true
        } else qualified.nativeConclusion == LinearSourceConclusion.OPTIMAL
    }

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

    override fun statusLine(verdict: Verdict): String = if (linearSource != null) {
        when (verdict) {
            Verdict.OPTIMAL -> if (best == null) "s UNKNOWN" else "s OPTIMUM FOUND"
            Verdict.SATISFIABLE, Verdict.BEST_FOUND -> if (best == null) "s UNKNOWN" else "s SATISFIABLE"
            Verdict.UNBOUNDED -> if (best == null) "s UNKNOWN" else "s UNBOUNDED"
            Verdict.UNSATISFIABLE -> "s UNSATISFIABLE"
            Verdict.UNKNOWN -> "s UNKNOWN"
        }
    } else when (verdict) {
        Verdict.SATISFIABLE, Verdict.BEST_FOUND, Verdict.OPTIMAL ->
            if (best == null) {
                "s UNKNOWN"
            } else if (verdict == Verdict.OPTIMAL && sourceWitnessAccepted() &&
                ((sourceExact && claimsCoverTolerance) || attainsAcceptedObjectiveBound())
            ) {
                "s OPTIMUM FOUND"
            } else {
                "s SATISFIABLE"
            }

        Verdict.UNBOUNDED -> when {
            proofExact && sourceWitnessAccepted() &&
                (exactSourceWitness() == true ||
                    (claimsCoverTolerance && exactSourceWitness() == null)) -> "s UNBOUNDED"
            best != null -> "s SATISFIABLE"
            else -> "s UNKNOWN"
        }

        Verdict.UNSATISFIABLE -> if (proofExact && claimsCoverTolerance) "s UNSATISFIABLE" else "s UNKNOWN"

        Verdict.UNKNOWN -> "s UNKNOWN"
    }

    override fun lpStatisticPairs(stats: SolveStats): List<Pair<String, String>> {
        val pairs = super.lpStatisticPairs(stats)
        val qualified = linearSource
        val key = if (qualified != null) when {
            !qualified.nativeClaimsUnrestricted -> "restrictedModelLpRootBound"
            qualified.nominalSourceExact && qualified.claimsCoverTolerance -> "lpRootBound"
            qualified.nominalSourceExact -> "nominalSourceLpRootBound"
            else -> "workingModelLpRootBound"
        } else when {
            claimsCoverTolerance -> "lpRootBound"
            proofExact -> "nominalSourceLpRootBound"
            else -> "workingModelLpRootBound"
        }
        val scoped = pairs.map { (name, value) -> (if (name == "lpRootBound") key else name) to value }
        return if (pairs.any { it.first == "lpRootBound" }) {
            scoped + ("${key}Scale" to (qualified?.objectiveScale?.toString() ?: "$objectiveScale"))
        } else pairs
    }

    override fun keepStat(key: String): Boolean = true

    override fun completionMetadata(verdict: Verdict): String? {
        linearSource?.let { source ->
            if (best == null) return null
            if (verdict == Verdict.OPTIMAL && source.conclusion == LinearSourceConclusion.OPTIMAL) {
                val attained = source.incumbent?.objective?.let { it == source.acceptedObjectiveBound } == true ||
                    (source.nominalSourceExact && source.claimsCoverTolerance && source.incumbent?.exact == true &&
                        nativeOptimumExact)
                val status = if (source.numericalClaims && !attained) "tolerance-optimum" else "attained-optimum"
                return "optimizationStatus=$status"
            }
            if (source.nativeConclusion != LinearSourceConclusion.OPTIMAL) return null
            val status = if (source.nominalSourceExact && source.incumbent?.exact == true && nativeOptimumExact) {
                "attained-optimum"
            } else "tolerance-optimum"
            return "${source.nativeScope()}OptimizationStatus=$status"
        }
        if (verdict != Verdict.OPTIMAL || best == null || !sourceWitnessAccepted()) return null
        val attainedBound = attainsAcceptedObjectiveBound()
        val status = if (attainedBound) "attained-optimum" else when (exactSourceWitness()) {
            true -> if (proofExact && exactSourceOptimum() == true) "attained-optimum" else "tolerance-optimum"
            false -> "tolerance-optimum"
            null -> return null
        }
        val key = when {
            (claimsCoverTolerance && sourceExact) || attainedBound -> "optimizationStatus"
            proofExact -> "nominalSourceOptimizationStatus"
            else -> "workingModelOptimizationStatus"
        }
        return "$key=$status"
    }

    override fun verdictReason(verdict: Verdict): String? {
        val cause = super.verdictReason(verdict)
        linearSource?.let { return linearSourceReason(it, cause) }
        val sourceQualification = sourceDifference?.let { "lowered model differs from MPS source at $it" }
        val witnessQualification = when (exactSourceWitness()) {
            true -> "exact $sourceScope MPS source witness"
            false -> "MPS source tolerance witness"
            null -> null
        }
        val declarationQualification = if (effectiveDeclarations) {
            "MPS source scope=effective declaration defaults"
        } else null
        val claimQualification = if (claimsCoverTolerance) null else when (verdict) {
            Verdict.UNSATISFIABLE -> if (proofExact) {
                "nominal refutation; tolerance-accepted feasibility unproved"
            } else "working-model refutation; tolerance-accepted feasibility unproved"
            Verdict.OPTIMAL -> if (attainsAcceptedObjectiveBound()) null else
                "${if (proofExact) "nominal" else "working-model"} optimization result; " +
                    "tolerance-accepted optimality unproved"
            else -> null
        }
        return listOfNotNull(declarationQualification, claimQualification, sourceQualification,
            witnessQualification, cause)
            .joinToString("; ").ifEmpty { null }
    }

    private fun linearSourceReason(source: LinearSourceOutcome, cause: String?): String? {
        val declaration = if (effectiveDeclarations) "MPS source scope=effective declaration defaults" else null
        val witness = source.incumbent?.let {
            if (it.exact) "exact $sourceScope MPS source witness" else "MPS source tolerance witness"
        }
        val scope = when {
            !source.nativeClaimsUnrestricted -> "restricted-model"
            source.nominalSourceExact -> "nominal"
            else -> "working-model"
        }
        val qualification = when {
            !source.nativeClaimsUnrestricted && source.nativeConclusion in setOf(
                LinearSourceConclusion.OPTIMAL, LinearSourceConclusion.UNSATISFIABLE,
            ) -> "$scope terminal does not cover the unrestricted source"
            source.numericalClaims && source.nativeConclusion == LinearSourceConclusion.OPTIMAL &&
                source.conclusion != LinearSourceConclusion.OPTIMAL ->
                "$scope optimum lacks a checked consistent terminal source point"
            source.numericalClaims && source.nativeConclusion == LinearSourceConclusion.UNSATISFIABLE &&
                source.conclusion != LinearSourceConclusion.UNSATISFIABLE ->
                "$scope refutation conflicts with an accepted source witness"
            source.nativeConclusion == LinearSourceConclusion.UNSATISFIABLE &&
                source.conclusion != LinearSourceConclusion.UNSATISFIABLE ->
                "$scope refutation; tolerance-accepted feasibility unproved"
            source.nativeConclusion == LinearSourceConclusion.OPTIMAL &&
                source.conclusion != LinearSourceConclusion.OPTIMAL ->
                "$scope optimization result; tolerance-accepted optimality unproved"
            source.nativeConclusion == LinearSourceConclusion.UNBOUNDED &&
                source.conclusion != LinearSourceConclusion.UNBOUNDED ->
                "$scope unboundedness; source recession proof unproved"
            else -> null
        }
        val numerical = if (source.numericalClaims && source.conclusion in setOf(
                LinearSourceConclusion.OPTIMAL, LinearSourceConclusion.UNSATISFIABLE,
            )) "MPS numerical status under configured tolerances" else null
        return listOfNotNull(declaration, numerical, qualification, witness, cause)
            .joinToString("; ").ifEmpty { null }
    }

    private val sourceScope: String get() = if (effectiveDeclarations) "effective" else "original"

    private fun attainsAcceptedObjectiveBound(): Boolean = acceptedObjectiveBound?.let { bound ->
        sourceWitnessAccepted() && sourceObjective() == bound
    } == true
}

private fun LinearSourceOutcome.nativeScope(): String = when {
    !nativeClaimsUnrestricted -> "restrictedModel"
    nominalSourceExact -> "nominalSource"
    else -> "workingModel"
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
    return exactMpsNumber(BigFraction.ofLong(value) * BigFraction.ofLong(scale).reciprocal())
}
