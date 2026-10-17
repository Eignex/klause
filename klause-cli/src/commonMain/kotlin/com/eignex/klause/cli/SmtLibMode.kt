package com.eignex.klause.cli

import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.formats.smtlib.smtLibSemantics
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.solver.pipeline.algebraicRealValue
import com.eignex.klause.solver.pipeline.symbolicRealValue
import com.eignex.klause.solver.result.LinearOptimizationResult
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.plus
import com.eignex.klause.util.times
import com.eignex.klause.util.unaryMinus
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/**
 * SMT-LIB 2 front-end (`.smt2` / `.smt`; linear arithmetic and polynomial reals). Emits the SMT-LIB convention: a
 * `sat` / `unsat` / `unknown` status line, followed (when sat) by a `(get-model)`-style
 * `(define-fun …)` block. `-s` statistics are emitted as `;` comment lines.
 */
internal object SmtLibMode : CliMode {
    override val names = listOf("smtlib", "smt", "smt2")
    override val extensions = listOf("smt2", "smt")
    override fun newSession(): ModeSession = Session()

    private class Session : ModeSession {
        private var latestSourceObjective: BigFraction? = null

        override fun flags(): List<FlagSpec> = emptyList()

        override fun load(path: String, common: CommonOptions): Solvable {
            val config = common.config
            val overrides = common.sourceOverrides(smtLibSemantics)
            latestSourceObjective = null
            val admissionStart = TimeSource.Monotonic.markNow()
            val stop = Cancellation { common.deadlineAtMs?.let { nowMillis() >= it } == true }
            val parsed = try {
                SmtLib.parse(
                    { openFileSource(path) },
                    settings = config.problemSettings(),
                    cancellation = stop,
                    semanticOverrides = overrides,
                )
            } catch (_: CancellationException) {
                return interruptedSolvable(
                    if (stop()) TerminationReason.Timeout else TerminationReason.Cancelled,
                    admissionStart.elapsedNow().inWholeMilliseconds,
                )
            }
            val ints = parsed.intVarNames
            val bools = parsed.boolVarNames
            val reals = parsed.realVarNames
            cliLogger(common.verbose).v {
                "parsed ${fileName(path)}: bool=${bools.size} int=${ints.size} real=${reals.size}"
            }
            val objective = parsed.objective?.toLinearObjective()
            return attachedSourceSolvable(
                parsed.source,
                common,
                { assignment -> renderOpenTheoryModel(ints, bools, reals, assignment) },
                admissionStart.elapsedNow().inWholeMilliseconds,
                parsed.quadraticObjective,
                parsed.sense,
                objective = objective,
                renderFinite = { sample ->
                    latestSourceObjective = objective?.evaluateExact(sample)
                    renderModel(ints, bools, reals, sample)
                },
            )
        }

        override fun output(common: CommonOptions): OutputProtocol = SmtLibOutput { latestSourceObjective }
    }
}

/** Render an SMT-LIB `(get-model)`-style model: one `(define-fun name () Sort value)` per
 *  declared variable. Real values are the sample's certified exact reals. */
internal fun renderModel(ints: Map<String, Int>, bools: Map<String, Int>, reals: Map<String, Int>, s: Sample): String =
    buildString {
        append("(\n")
        for ((name, id) in ints) append("  (define-fun $name () Int ${smtInteger(s.intValue(id).toString())})\n")
        for ((name, id) in bools) append("  (define-fun $name () Bool ${s.boolValue(id)})\n")
        val exactReals = if (reals.isEmpty()) {
            emptyList()
        } else {
            requireNotNull(s.exactReals) {
                "SMT model has no certified real values"
            }
        }
        for ((name, id) in reals) {
            append("  (define-fun $name () Real ${smtReal(exactReals[id].toString())})\n")
        }
        append(")")
    }

/** Render an exact open-theory model through the pipeline's mode-neutral witness surface. */
internal fun renderOpenTheoryModel(
    ints: Map<String, Int>,
    bools: Map<String, Int>,
    reals: Map<String, Int>,
    assignment: OpenTheoryAssignment,
): String = buildString {
    append("(\n")
    for ((name, id) in ints) append("  (define-fun $name () Int ${smtInteger(assignment.intValue(id))})\n")
    for ((name, id) in bools) append("  (define-fun $name () Bool ${assignment.boolValue(id)})\n")
    for ((name, id) in reals) {
        val value = assignment.symbolicRealValue(id)?.let(::smtSymbolicValue)
            ?: assignment.algebraicRealValue(id)?.let(::smtAlgebraicValue) ?: smtReal(assignment.realValue(id))
        append("  (define-fun $name () Real $value)\n")
    }
    append(")")
}

internal fun smtInteger(value: String): String = if (value.startsWith('-')) "(- ${value.drop(1)})" else value

internal fun smtReal(value: String): String {
    val negative = value.startsWith('-')
    val magnitude = if (negative) value.drop(1) else value
    val parts = magnitude.split('/')
    require(parts.size in 1..2 && parts.all { it.isNotEmpty() && it.all { c -> c.isDigit() || c == '.' } }) {
        "invalid exact real value: $value"
    }
    val realParts = parts.map { if ('.' in it) it else "$it.0" }
    val term = if (realParts.size == 1) realParts[0] else "(/ ${realParts[0]} ${realParts[1]})"
    return if (negative) "(- $term)" else term
}

/** SMT-LIB output protocol: `sat`/`unsat`/`unknown` + the buffered model on sat. */
internal class SmtLibOutput(private val sourceObjective: () -> BigFraction? = { null }) : BufferedBestOutput() {
    override fun formatObjective(objective: Long): String =
        sourceObjective()?.toString() ?: super.formatObjective(objective)

    override fun formatContinuousObjective(objective: Double): String =
        sourceObjective()?.toString() ?: super.formatContinuousObjective(objective)

    private var optimize = false
    private var quadraticResult: QuadraticOptimizationResult? = null
    private var linearResult: LinearOptimizationResult? = null

    override val commentPrefix: String = ";"
    override val streamObjective: Boolean = true
    override val streamFiniteWitness: Boolean = true
    override val objectivePrefix: String = "; objective="

    override fun begin(optimize: Boolean, maximize: Boolean) {
        this.optimize = optimize
        quadraticResult = null
        linearResult = null
    }

    override fun onQuadraticResult(rendered: String?, result: QuadraticOptimizationResult) {
        quadraticResult = result
        if (rendered != null) {
            best = rendered
            (result as? QuadraticOptimizationResult.WithIncumbent)?.let {
                println("; objective=${smtAlgebraicValue(it.incumbent.value)}")
            }
        }
        val bound = when (result) {
            is QuadraticOptimizationResult.Optimal -> result.bound
            is QuadraticOptimizationResult.UnattainedLimit -> result.bound
            is QuadraticOptimizationResult.BestFound -> result.bound
            is QuadraticOptimizationResult.Unknown -> result.bound
            else -> null
        }
        bound?.let {
            val direction = if (result.sense == ObjectiveSense.MINIMIZE) "lower" else "upper"
            println("; objectiveBoundDirection=$direction")
            println("; objectiveBound=${smtAlgebraicValue(it.value)}")
            println("; objectiveBoundStrict=${it.strict}")
        }
    }

    override fun onLinearResult(result: LinearOptimizationResult) {
        linearResult = result
        val bound = when (val claim = result.terminal?.claim) {
            is SourceClaim.Optimal -> SourceClaim.Bound(claim.value)
            is SourceClaim.Limit -> SourceClaim.Bound(claim.value, strict = true)
            else -> result.bound?.claim as? SourceClaim.Bound
        } ?: return
        val direction = if (result.scope.objective?.sense == ObjectiveSense.MINIMIZE) "lower" else "upper"
        println("; objectiveBoundDirection=$direction")
        println("; objectiveBound=${formatArithmeticObjective(bound.value)}")
        println("; objectiveBoundStrict=${bound.strict}")
    }

    override fun arithmeticWitnessStatus(): String = "sat"

    override fun formatArithmeticObjective(objective: SourceValue): String = when (objective) {
        is SourceValue.Algebraic -> smtAlgebraicValue(objective.value)
        else -> arithmeticObjectiveText(objective)
    }

    override fun completionMetadata(verdict: Verdict): String? {
        if (!optimize) return null
        val status = if (linearResult?.terminal?.claim is SourceClaim.Limit) {
            if (linearResult?.scope?.objective?.sense == ObjectiveSense.MINIMIZE) {
                "unattained-infimum"
            } else {
                "unattained-supremum"
            }
        } else {
            when (val result = quadraticResult) {
            is QuadraticOptimizationResult.UnattainedLimit -> if (result.sense == ObjectiveSense.MINIMIZE) {
                "unattained-infimum"
            } else {
                "unattained-supremum"
            }

            else -> verdict.name.lowercase().replace('_', '-')
        }
        }
        return "optimizationStatus=$status"
    }

    override fun statusLine(verdict: Verdict): String = when (verdict) {
        Verdict.SATISFIABLE, Verdict.OPTIMAL, Verdict.BEST_FOUND, Verdict.UNBOUNDED -> "sat"
        Verdict.UNSATISFIABLE -> "unsat"
        Verdict.UNKNOWN -> "unknown"
    }

    // Deliberately lean block: SMT-LIB comments carry headline search and exact-theory counters.
    override fun keepStat(key: String): Boolean = key in SMT_SEARCH_KEYS || key.startsWith("smt")

    private companion object {
        private val SMT_SEARCH_KEYS = setOf(
            "lsMoves", "lsCompletions", "lsCompletionsRefuted", "lsCompletionsUndecided",
            "nodes", "failures", "propagations", "learned", "learnedMeanSize", "learnedMeanLbd", "relearned",
            "openBoolDecisions", "openIntDecisions", "openTheoryDecisions", "openTheoryChecks",
            "openWork", "openLearned", "openRelearned",
            "openRestarts", "openReductions", "openDropped", "openRetained", "openPeakRetained",
            "openLearnedWatchVisits",
            "openAssertingConflicts", "openNonAssertingConflicts", "openReductionNs",
            "openHintDraws", "openHintProduced", "openHintVars", "openHintSteered", "openHintMoves",
            "openHintNoProvider",
            "openHintIndexAdmitted",
            "openHintIndexSourceLimit",
            "openHintIndexStorageLimit",
            "openHintIndexVisitLimit",
            "openHintIndexInvalidPrimitive",
            "openHintIndexStopped",
            "openHintIndexAborted",
            "openHintCallerConsulted",
            "openHintCallerSelected",
            "openHintComponentConsulted",
            "openHintComponentSelected",
            "openHintPrimitiveSelected",
            "openHintGateSelected",
            "openHintClauseIndexAdmitted",
            "openHintClauseIndexDeclined",
            "openHintClauseConsulted",
            "openHintClauseSelected",
            "openHintClauseDeclined",
            "openHintEarlyPointUnavailable",
            "openHintForwardUncovered",
            "openHintPrimitiveProviderNone",
            "openHintSourceBooleansMax",
            "openHintSourceClausesMax",
            "openHintSourcePrimitiveEntriesMax",
            "openHintRawClausesMax",
            "openHintRawLiteralsMax",
            "openHintRawIncidencesMax",
            "openHintRawVariablesMax",
            "openHintRawLedgerDebits",
            "nraDyadicAttempts", "nraDyadicBases", "nraCoupledWorkDeclines", "nraDyadicPreExtractionDeclines",
        )
    }
}
