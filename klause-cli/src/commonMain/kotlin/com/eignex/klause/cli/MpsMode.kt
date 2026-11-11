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
import com.eignex.klause.util.Cancellation
import com.eignex.util.io.openTextFile
import com.eignex.util.io.useSource
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.fitsLong
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
            val parsed = openTextFile(path).useSource { Mps.parse(it) }
            return common.routingPreparationCancellation().use { stop ->
                val source = parsed.toLinearSourceProblem(overrides, stop) ?: return interruptedSolvable(
                    if (stop()) TerminationReason.Timeout else TerminationReason.Cancelled,
                    start.elapsedNow().inWholeMilliseconds,
                )
                println("c numericalSemantics=${mpsNumericalSemantics(source.semantics)}")
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
    private val exactSourceWitness: () -> Boolean? = { null },
    private val effectiveDeclarations: Boolean = false,
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

    override fun onSolutionObjective(objective: Long?) {
        if (objective != null) bestObjective = objective
    }

    override val commentPrefix: String = "c"
    override val streamObjective: Boolean = true

    override fun formatObjective(objective: Long): String = objective.toString()

    override fun formatContinuousObjective(objective: Double): String = wholeOrDecimal(objective)

    override fun formatExactObjective(objective: BigFraction): String = exactMpsNumber(objective)

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
            when {
                best == null -> "s UNKNOWN"
                verdict == Verdict.OPTIMAL -> "s OPTIMUM FOUND"
                else -> "s SATISFIABLE"
            }

        Verdict.UNBOUNDED -> when {
            exactSourceWitness() != false -> "s UNBOUNDED"
            best != null -> "s SATISFIABLE"
            else -> "s UNKNOWN"
        }

        Verdict.UNSATISFIABLE -> "s UNSATISFIABLE"

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
        } else "lpRootBound"
        val scoped = pairs.map { (name, value) -> (if (name == "lpRootBound") key else name) to value }
        return if (pairs.any { it.first == "lpRootBound" }) {
            scoped + ("${key}Scale" to (qualified?.objectiveScale?.toString() ?: "1"))
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
        if (verdict != Verdict.OPTIMAL || best == null) return null
        val status = when (exactSourceWitness()) {
            true -> "attained-optimum"
            false -> "tolerance-optimum"
            null -> return null
        }
        return "optimizationStatus=$status"
    }

    override fun verdictReason(verdict: Verdict): String? {
        val cause = super.verdictReason(verdict)
        linearSource?.let { return linearSourceReason(it, cause) }
        val witnessQualification = when (exactSourceWitness()) {
            true -> "exact $sourceScope MPS source witness"
            false -> "MPS source tolerance witness"
            null -> null
        }
        val declarationQualification = if (effectiveDeclarations) {
            "MPS source scope=effective declaration defaults"
        } else null
        return listOfNotNull(declarationQualification, witnessQualification, cause)
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

/** Format an MPS objective whose continuous columns make it non-integral. A whole value prints as one, so a
 *  model whose reals happen to resolve integrally reads the same as a purely discrete one. */
private fun wholeOrDecimal(value: Double): String =
    if (value == floor(value) && abs(value) < MAX_EXACT_WHOLE) value.toLong().toString() else value.toString()

/** Largest magnitude a `Double` represents every whole number below; past it the integral shortcut in
 *  [wholeOrDecimal] would print a rounded neighbour as though it were exact. */
private const val MAX_EXACT_WHOLE = 9.007199254740992E15
