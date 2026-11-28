package com.eignex.klause.bench.lab

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

// A CENSORED row is accepted but has an arm without a terminal verdict inside its budget.
@Serializable
internal enum class RowState { COMPLETE, CENSORED, INVALID, MISSING }

@Serializable
internal data class AuditedRow(
    val source: String,
    val seed: String?,
    val innerRepeat: String?,
    val outerRepeat: String?,
    val host: String?,
    val receipt: String?,
    val arms: List<String?>,
    val indices: List<Int>,
    val state: RowState,
    val reasons: List<String>,
    val censoredArms: List<String>,
    @Transient val cases: List<AuditedCase> = emptyList(),
)

// unknown counts cases or attempts that retained no value for the scope.
@Serializable
internal data class CostTotal(val sum: Double, val known: Int, val unknown: Int)

// Scopes are never added together: CLI CPU is nested in adapter CPU, solver elapsed in command wall time, and checker
// time is a separate post-commit scope.
@Serializable
internal data class CaseCosts(
    val cases: Int,
    val solverElapsedMs: CostTotal,
    val cliCpuSeconds: CostTotal,
    val adapterCpuSeconds: CostTotal,
    val commandWallMs: CostTotal,
    val checkerElapsedMs: CostTotal,
)

@Serializable
internal data class ArmOutcomes(
    val rows: Int,
    val native: Map<String, Int>,
    val checkedWitnesses: Map<String, Int>,
    val withdrawnByChecker: Int,
    val nativeProofsWithoutIndependentProof: Int,
)

// Costs are cumulative over every row an attempt ran.
@Serializable
internal data class AttemptCosts(
    val executionClass: String,
    val attempts: Int,
    val committed: Int,
    val uncommitted: Int,
    val elapsedMs: CostTotal,
    val cpuSeconds: CostTotal,
    val uncommittedElapsedMs: CostTotal,
    val scopes: List<String>,
)

@Serializable
internal data class AttemptSummary(val classes: List<AttemptCosts>, val committedRows: Int, val errors: List<String>)

// A null parameter value inherits the solver default.
@Serializable
internal data class DeclaredArm(
    val ref: String?,
    val revision: String?,
    val parameters: Map<String, String?>,
    val resolvedPlan: Boolean,
)

@Serializable
internal data class CohortCoverage(
    val source: String,
    val expected: Int,
    val retained: Int,
    val absent: List<String>,
    val unexpected: List<String>,
)

// gaps name the evidence that withholds verified.
@Serializable
internal data class DeclaredCoverage(
    val verified: Boolean,
    val arms: Map<String, DeclaredArm>,
    val cohort: CohortCoverage?,
    val gaps: List<String>,
)

// accepted holds when no row is invalid or missing and the job is consistent; declared reports how much of the
// requested experiment that acceptance covers.
@Serializable
internal data class PairedAuditReport(
    val arms: List<String>,
    val treatments: List<String>,
    val allowedDifferences: List<String>,
    val complete: Int,
    val censored: Int,
    val invalid: Int,
    val missing: Int,
    val accepted: Boolean,
    val declared: DeclaredCoverage,
    val jobErrors: List<String>,
    val outcomes: Map<String, ArmOutcomes>,
    val costs: Map<String, CaseCosts>,
    val retainedCosts: CaseCosts,
    val attempts: AttemptSummary?,
    val rows: List<AuditedRow>,
)
