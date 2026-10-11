package com.eignex.klause.cli

import com.eignex.klause.solver.result.LpCertifierRouteStats
import com.eignex.klause.solver.result.LpCertifierStats
import com.eignex.klause.solver.result.LpContinuationStats
import com.eignex.klause.solver.result.LpRouteSolveStats
import com.eignex.klause.solver.result.LpStats
import com.eignex.klause.solver.result.SmtStats
import com.eignex.klause.solver.result.SolveStats
import kotlin.math.round

/**
 * LP-relaxation success metrics for `-s`, as ordered `key`/`value` pairs each mode prints with its
 * own comment prefix. Returns empty when no LP-family technique ran (so non-LP solves print nothing
 * extra), keyed off [LpStats.solves] plus the standalone Lagrangian / energetic prunes and the
 * component splits (which the certify paths record without a node solve).
 *
 * The headline measure is the prune *rate* (`lpPruneRate = lpPruned / lpSolves`) against the cost
 * (`lpPivotsPerSolve`, `lpMs`): a relaxation earns its place only if it prunes often enough to repay
 * the pivots it spends. [LpStats.rootBound] versus the reported objective is the integrality
 * gap — the direct measure of how tight the relaxation is. The technique split (`lpInfeasible`,
 * `lpBoundPruned`, `lpLagrangianPruned`, `lpEnergeticPruned`, `lpBackjumps`) attributes the wins as
 * far as the engine can soundly separate them; cuts/hull columns feed the same `lpPruned` bound and
 * so cannot be split per node — `lpCuts` reports their volume instead. Every emitted key is
 * `lp`-prefixed so the block is unambiguously LP even in the flat `key=value` stat stream.
 */
internal fun lpStatPairs(stats: SolveStats): List<Pair<String, String>> {
    val solves = stats.lp.solves.sum
    val lagrangian = stats.scheduling.lagrangianPruned.sum
    val energetic = stats.scheduling.energeticPruned.sum
    val splits = stats.lp.componentSplits.sum
    val routed = stats.lp.standalonePasses.sum + stats.lp.componentPasses.sum + stats.lp.rootPasses.sum
    if (solves == 0.0 && stats.lp.nodePasses.sum == 0.0 && routed == 0.0 &&
        lagrangian == 0.0 && energetic == 0.0 && splits == 0.0 &&
        stats.lp.basisVerification.calls == 0L && stats.lp.continuation.calls == 0L && stats.lp.phases.isEmpty()
    ) {
        return emptyList()
    }

    val pruned = stats.lp.pruned.sum
    val infeasible = stats.lp.infeasible.sum
    val out = ArrayList<Pair<String, String>>()
    out += "lpSolves" to "${solves.toLong()}"
    out += "lpPruned" to "${pruned.toLong()}"
    out += "lpInfeasible" to "${infeasible.toLong()}"
    out += "lpBoundPruned" to "${(pruned - infeasible).toLong()}"
    if (solves > 0.0) {
        out += "lpPruneRate" to round4(pruned / solves)
        out += "lpPivotsPerSolve" to round4(stats.lp.pivots.sum / solves)
        out += "lpWorkPerSolve" to "${(stats.lp.workOps.sum / solves).toLong()}"
        out += "lpSeededRate" to round4(stats.lp.seeded.sum / solves)
        out += "lpRefactorizationsPerSolve" to round4(stats.lp.refactorizations.sum / solves)
    }
    out += "lpFixed" to "${stats.lp.fixed.sum.toLong()}"
    out += "lpCuts" to "${stats.lp.cuts.sum.toLong()}"
    out += "lpPivots" to "${stats.lp.pivots.sum.toLong()}"
    out += "lpWorkOps" to "${stats.lp.workOps.sum.toLong()}"
    out += "lpOverheadOps" to "${stats.lp.overheadOps.sum.toLong()}"
    out += "lpNodePasses" to "${stats.lp.nodePasses.sum.toLong()}"
    if (stats.lp.nodePasses.sum > 0.0) {
        out += "lpWorkOpsPerNode" to "${(stats.lp.workOps.sum / stats.lp.nodePasses.sum).toLong()}"
    }
    if (stats.lp.standalonePasses.sum > 0.0) {
        out += "lpStandalonePasses" to "${stats.lp.standalonePasses.sum.toLong()}"
        out += "lpStandalonePivots" to "${stats.lp.standalonePivots.sum.toLong()}"
        out += "lpStandaloneWorkOps" to "${stats.lp.standaloneWorkOps.sum.toLong()}"
    }
    if (stats.lp.componentPasses.sum > 0.0) {
        out += "lpComponentPasses" to "${stats.lp.componentPasses.sum.toLong()}"
        out += "lpComponentPivots" to "${stats.lp.componentPivots.sum.toLong()}"
        out += "lpComponentWorkOps" to "${stats.lp.componentWorkOps.sum.toLong()}"
    }
    if (stats.lp.rootPasses.sum > 0.0) {
        out += "lpRootPasses" to "${stats.lp.rootPasses.sum.toLong()}"
        out += "lpRootPivots" to "${stats.lp.rootPivots.sum.toLong()}"
        out += "lpRootWorkOps" to "${stats.lp.rootWorkOps.sum.toLong()}"
    }
    appendRouteSolveStats(out, "Standalone", stats.lp.standaloneRoute)
    appendRouteSolveStats(out, "Component", stats.lp.componentRoute)
    appendRouteSolveStats(out, "Root", stats.lp.rootRoute)
    if (stats.lp.workAllowanceSpent) out += "lpWorkAllowanceSpent" to "1"
    if (stats.lp.demoted) out += "lpDemoted" to "1"
    if (stats.lp.luMaxFill.max.isFinite()) out += "lpLuMaxFill" to round4(stats.lp.luMaxFill.max)
    if (stats.lp.luMaxDensity.max.isFinite()) out += "lpLuMaxDensity" to round4(stats.lp.luMaxDensity.max)
    if (stats.lp.luMaxDim.max.isFinite() && stats.lp.luMaxDim.max > 0.0) {
        out += "lpLuMaxDim" to "${stats.lp.luMaxDim.max.toLong()}"
    }
    // Printed only when nonzero: a solve that meets neither says nothing, so a line appearing at all
    // is the whole signal.
    if (stats.lp.singularRefactorizations.sum > 0.0) {
        out += "lpSingularRefactorizations" to "${stats.lp.singularRefactorizations.sum.toLong()}"
    }
    if (stats.lp.smallPivotBails.sum > 0.0) {
        out += "lpSmallPivotBails" to "${stats.lp.smallPivotBails.sum.toLong()}"
    }
    if (stats.lp.refactorizations.sum > 0.0) {
        out += "lpRefactorInitial" to "${stats.lp.initialRefactorizations.sum.toLong()}"
        out += "lpRefactorWarmStart" to "${stats.lp.warmStartRefactorizations.sum.toLong()}"
        out += "lpRefactorSingularRecovery" to "${stats.lp.singularRecoveryRefactorizations.sum.toLong()}"
        out += "lpRefactorUpdateLimit" to "${stats.lp.updateLimitRefactorizations.sum.toLong()}"
        out += "lpRefactorBackendRequested" to "${stats.lp.backendRequestedRefactorizations.sum.toLong()}"
        out += "lpRefactorReconcileRecovery" to "${stats.lp.reconcileRecoveryRefactorizations.sum.toLong()}"
        out += "lpRefactorNumericalRecovery" to "${stats.lp.numericalRecoveryRefactorizations.sum.toLong()}"
        out += "lpRefactorPrimal" to "${stats.lp.primalRefactorizations.sum.toLong()}"
    }
    // The decline rate: certified against the two causes it can fail for. Printed whenever any
    // certification happened, since a zero decline count is as informative as a nonzero one here.
    val certifyAttempts = stats.lp.certified.sum + stats.lp.certifyDeclinedContinuous.sum +
        stats.lp.certifyDeclinedNumeric.sum
    if (certifyAttempts > 0.0) {
        out += "lpCertified" to "${stats.lp.certified.sum.toLong()}"
        out += "lpCertifyDeclinedContinuous" to "${stats.lp.certifyDeclinedContinuous.sum.toLong()}"
        out += "lpCertifyDeclinedNumeric" to "${stats.lp.certifyDeclinedNumeric.sum.toLong()}"
        out += "lpCertifyRate" to round4(stats.lp.certified.sum / certifyAttempts)
        if (stats.lp.certifyMaxRows.max.isFinite()) {
            out += "lpCertifyMaxRows" to "${stats.lp.certifyMaxRows.max.toLong()}"
        }
    }
    val farkas = stats.lp.farkasReconstructed.sum + stats.lp.farkasExactBasis.sum +
        stats.lp.farkasRounded.sum + stats.lp.farkasNone.sum
    if (farkas > 0.0) {
        out += "lpFarkasAttempts" to "${farkas.toLong()}"
        out += "lpFarkasReconstructed" to "${stats.lp.farkasReconstructed.sum.toLong()}"
        out += "lpFarkasExactBasis" to "${stats.lp.farkasExactBasis.sum.toLong()}"
        out += "lpFarkasRounded" to "${stats.lp.farkasRounded.sum.toLong()}"
        out += "lpFarkasNone" to "${stats.lp.farkasNone.sum.toLong()}"
    }
    if (stats.lp.rationalFallbacks.sum > 0.0) {
        out += "lpRationalFallbacks" to "${stats.lp.rationalFallbacks.sum.toLong()}"
    }
    appendCertifierStats(out, "IntegerCertify", stats.lp.integerCertify)
    appendCertifierStats(out, "SafeObjectiveLowerBound", stats.lp.safeObjectiveLowerBound)
    appendCertifierStats(out, "ExactBasisFeasible", stats.lp.exactBasisFeasible)
    appendCertifierStats(out, "ExactFarkasRay", stats.lp.exactFarkasRay)
    appendCertifierStats(out, "ExactPointFeasible", stats.lp.exactPointFeasible)
    appendCertifierStats(out, "RationalOutcome", stats.lp.rationalOutcome)
    out += continuationStatPairs("lp", stats.lp.continuation)
    for ((phase, metrics) in stats.lp.phases) {
        val prefix = "lpPhase_$phase"
        out += "${prefix}_Calls" to "${metrics.calls}"
        out += "${prefix}_Nanos" to "${metrics.nanos}"
        out += "${prefix}_Work" to "${metrics.work}"
        out += "${prefix}_Pivots" to "${metrics.pivots}"
        out += "${prefix}_Steps" to "${metrics.steps}"
        for ((outcome, count) in metrics.outcomes) out += "${prefix}_$outcome" to "$count"
    }
    val basis = stats.lp.basisVerification
    if (basis.calls > 0L) {
        out += "lpRationalBasisCalls" to "${basis.calls}"
        out += "lpRationalBasisEligible" to "${basis.eligible}"
        out += "lpRationalBasisFactories" to "${basis.factoryCalls}"
        out += "lpRationalBasisBuilds" to "${basis.builds}"
        out += "lpRationalBasisReuse" to "${basis.reuse}"
        out += "lpRationalBasisSolves" to "${basis.solves}"
        out += "lpRationalBasisRestarts" to "${basis.restarts}"
        out += "lpRationalBasisChecks" to "${basis.checks}"
        out += "lpRationalBasisWork" to "${basis.work.values.sum()}"
        out += "lpRationalBasisAllocation" to "${basis.allocation.values.sum()}"
        for ((phase, work) in basis.work) out += "lpRationalBasisWork_$phase" to "$work"
        for ((phase, bytes) in basis.allocation) out += "lpRationalBasisAllocation_$phase" to "$bytes"
        for ((reason, count) in basis.declines) out += "lpRationalBasisDecline_$reason" to "$count"
        for ((terminal, count) in basis.terminalDeclines) out += "lpRationalBasisTerminal_$terminal" to "$count"
        for ((route, count) in basis.routes) out += "lpRationalBasisRoute_$route" to "$count"
    }
    if (stats.lp.warmStartAttempts.sum > 0.0) {
        out += "lpWarmStartAttempts" to "${stats.lp.warmStartAttempts.sum.toLong()}"
        out += "lpWarmStartHits" to "${stats.lp.warmStartHits.sum.toLong()}"
        out += "lpWarmStartHitRate" to round4(stats.lp.warmStartHits.sum / stats.lp.warmStartAttempts.sum)
    }
    if (stats.lp.exactInputAttempts.sum > 0.0) {
        out += "lpExactInputAttempts" to "${stats.lp.exactInputAttempts.sum.toLong()}"
        out += "lpExactInputRejections" to "${stats.lp.exactInputRejections.sum.toLong()}"
    }
    if (stats.lp.cutCandidates.sum > 0.0 || stats.lp.cutSelected.sum > 0.0 || stats.lp.cutActive.max.isFinite()) {
        out += "lpCutCandidates" to "${stats.lp.cutCandidates.sum.toLong()}"
        out += "lpCutSelected" to "${stats.lp.cutSelected.sum.toLong()}"
        if (stats.lp.cutActive.max.isFinite()) {
            out += "lpCutActive" to "${stats.lp.cutActive.max.toLong()}"
        }
    }
    if (stats.lp.rootReducedCostFixes.sum > 0.0) {
        out += "lpRootReducedCostFixes" to "${stats.lp.rootReducedCostFixes.sum.toLong()}"
    }
    if (stats.lp.rootMatrixMinValue.isFinite()) {
        // Full precision, not [round4]: a coefficient below 1e-4 is exactly the one worth seeing, and
        // rounding it reports the badly scaled matrix as a zero.
        out += "lpRootMatrixMin" to "${stats.lp.rootMatrixMinValue}"
        out += "lpRootMatrixMax" to "${stats.lp.rootMatrixMaxValue}"
        out += "lpRootRowRatio" to "${stats.lp.rootRowRatio}"
    }
    if (splits > 0.0) {
        out += "lpComponentSplits" to "${splits.toLong()}"
        out += "lpComponentBlocksMax" to "${stats.lp.componentBlocks.max.toLong()}"
    }
    out += "lpBackjumps" to "${stats.lp.backjumps.sum.toLong()}"
    out += "lpLagrangianPruned" to "${lagrangian.toLong()}"
    out += "lpEnergeticPruned" to "${energetic.toLong()}"
    out += "lpMs" to "${stats.lp.ms}"
    if (stats.lp.rootBound.isFinite()) out += "lpRootBound" to round4(stats.lp.rootBound)
    return out
}

private fun appendRouteSolveStats(out: MutableList<Pair<String, String>>, name: String, stats: LpRouteSolveStats) {
    if (stats.passes.sum == 0.0) return
    out += "lp${name}WarmStartAttempts" to "${stats.warmStartAttempts.sum.toLong()}"
    out += "lp${name}WarmStartHits" to "${stats.warmStartHits.sum.toLong()}"
    if (stats.warmStartAttempts.sum > 0.0) {
        out += "lp${name}WarmStartHitRate" to round4(stats.warmStartHits.sum / stats.warmStartAttempts.sum)
    }
    val refactorizations = stats.initialRefactorizations.sum + stats.warmStartRefactorizations.sum +
        stats.singularRecoveryRefactorizations.sum + stats.updateLimitRefactorizations.sum +
        stats.backendRequestedRefactorizations.sum + stats.reconcileRecoveryRefactorizations.sum +
        stats.numericalRecoveryRefactorizations.sum + stats.primalRefactorizations.sum
    out += "lp${name}Refactorizations" to "${refactorizations.toLong()}"
    out += "lp${name}RefactorInitial" to "${stats.initialRefactorizations.sum.toLong()}"
    out += "lp${name}RefactorWarmStart" to "${stats.warmStartRefactorizations.sum.toLong()}"
    out += "lp${name}RefactorSingularRecovery" to "${stats.singularRecoveryRefactorizations.sum.toLong()}"
    out += "lp${name}RefactorUpdateLimit" to "${stats.updateLimitRefactorizations.sum.toLong()}"
    out += "lp${name}RefactorBackendRequested" to "${stats.backendRequestedRefactorizations.sum.toLong()}"
    out += "lp${name}RefactorReconcileRecovery" to "${stats.reconcileRecoveryRefactorizations.sum.toLong()}"
    out += "lp${name}RefactorNumericalRecovery" to "${stats.numericalRecoveryRefactorizations.sum.toLong()}"
    out += "lp${name}RefactorPrimal" to "${stats.primalRefactorizations.sum.toLong()}"
    out += "lp${name}SingularRefactorizations" to "${stats.singularRefactorizations.sum.toLong()}"
    out += "lp${name}SmallPivotBails" to "${stats.smallPivotBails.sum.toLong()}"
}

private fun appendCertifierStats(out: MutableList<Pair<String, String>>, name: String, stats: LpCertifierStats) {
    out += "lp${name}Attempts" to "${stats.attempts.sum.toLong()}"
    out += "lp${name}Successes" to "${stats.successes.sum.toLong()}"
    out += "lp${name}Declines" to "${stats.declines.sum.toLong()}"
    if (stats.attempts.sum > 0.0) out += "lp${name}SuccessRate" to round4(stats.successes.sum / stats.attempts.sum)
    appendRouteCertifierStats(out, name, "Node", stats.node)
    appendRouteCertifierStats(out, name, "Standalone", stats.standalone)
    appendRouteCertifierStats(out, name, "Component", stats.component)
    appendRouteCertifierStats(out, name, "Root", stats.root)
}

private fun appendRouteCertifierStats(
    out: MutableList<Pair<String, String>>,
    certifier: String,
    route: String,
    stats: LpCertifierRouteStats,
) {
    out += "lp${certifier}${route}Attempts" to "${stats.attempts.sum.toLong()}"
    out += "lp${certifier}${route}Successes" to "${stats.successes.sum.toLong()}"
    out += "lp${certifier}${route}Declines" to "${stats.declines.sum.toLong()}"
    if (stats.attempts.sum > 0.0) {
        out += "lp${certifier}${route}SuccessRate" to round4(stats.successes.sum / stats.attempts.sum)
    }
}

/**
 * Local-search telemetry for `-s`, as ordered `key`/`value` pairs. Returns empty unless the LS engine
 * actually ran — keyed off `backend == "ls"` (a pure-LS solve) or `moves > 0` (an LS arm inside a
 * `"mixed"` portfolio), so complete-only solves print nothing here.
 *
 * The MiniZinc statistics schema standardises no runtime LS counters, so these mirror nothing upstream
 * — they are the engine's own progress fingerprint. The headline pair is
 * `lsMoves` against `lsMovesPerSec` (raw throughput) and `lsTimeToBest` against `solveTime` (the anytime
 * profile: how early the best incumbent landed). `lsStalls` over `lsRestarts` shows how much of the
 * search was plateau-thrashing. `lsIncumbentViolation` is 0 once feasible, else the lowest residual cost
 * reached — the only signal of how close an otherwise-UNKNOWN run got. Every key is `ls`-prefixed so the
 * block is unambiguous in the flat stat stream, matching the `lp`-prefix convention.
 */
internal fun lsStatPairs(stats: SolveStats, solveTimeMs: Long): List<Pair<String, String>> {
    val moves = stats.ls.moves.sum
    if (stats.run.backend != "ls" && moves == 0.0) return emptyList()

    val out = ArrayList<Pair<String, String>>()
    out += "lsMoves" to "${moves.toLong()}"
    out += "lsRestarts" to "${stats.search.restarts.sum.toLong()}"
    out += "lsStalls" to "${stats.ls.stalls.sum.toLong()}"
    if (solveTimeMs > 0L) out += "lsMovesPerSec" to round4(moves / (solveTimeMs / 1000.0))
    if (stats.ls.timeToBestMs >= 0L) out += "lsTimeToBest" to round4(stats.ls.timeToBestMs / 1000.0)
    if (stats.ls.incumbentObjective.isFinite()) out += "lsIncumbentObjective" to round4(stats.ls.incumbentObjective)
    if (stats.ls.incumbentViolation.isFinite()) out += "lsIncumbentViolation" to round4(stats.ls.incumbentViolation)
    val completions = stats.ls.completions.sum.toLong()
    if (completions > 0L) {
        out += "lsCompletions" to "$completions"
        out += "lsCompletionsRefuted" to "${stats.ls.completionsRefuted.sum.toLong()}"
        out += "lsCompletionsUndecided" to "${stats.ls.completionsUndecided.sum.toLong()}"
    }
    return out
}

/**
 * Systematic-search counters for `-s`, as ordered `key`/`value` pairs — the backtrack/CDCL block
 * every complete solve reports. Modes that deliberately emit a subset (SMT-LIB, XCSP) filter by
 * key at the call site, so the full group order lives in one place.
 */
internal fun searchStatPairs(stats: SolveStats): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    out += "nodes" to "${stats.search.nodes.sum.toLong()}"
    out += "failures" to "${stats.search.fails.sum.toLong()}"
    out += "restarts" to "${stats.search.restarts.sum.toLong()}"
    out += "propagations" to "${stats.search.propagations.sum.toLong()}"
    out += "propagationWork" to "${stats.search.propagationWork.sum.toLong()}"
    out += "rootPropagationWork" to "${stats.search.rootPropagationWork.sum.toLong()}"
    out += "propagationMs" to "${stats.search.propagationNanos.sum.toLong() / 1_000_000L}"
    out += "rootPropagationMs" to "${stats.search.rootPropagationNanos.sum.toLong() / 1_000_000L}"
    val learned = stats.search.learnedClauses.sum
    out += "learned" to "${learned.toLong()}"
    if (learned > 0.0) {
        out += "learnedMeanSize" to round4(stats.search.learnedLiterals.sum / learned)
        out += "learnedMeanLbd" to round4(stats.search.learnedLbd.sum / learned)
    }
    out += "relearned" to "${stats.search.relearned.sum.toLong()}"
    if (stats.search.peakDepth.max.isFinite()) out += "peakDepth" to "${stats.search.peakDepth.max.toLong()}"
    if (stats.search.rootFixed.max.isFinite()) out += "rootFixed" to "${stats.search.rootFixed.max.toLong()}"
    stats.search.inprocessProbes.sum.toLong().takeIf { it > 0L }?.let { out += "inprocessProbes" to "$it" }
    stats.search.inprocessVisits.sum.toLong().takeIf { it > 0L }?.let { out += "inprocessVisits" to "$it" }
    stats.search.glueClauses.sum.toLong().takeIf { it > 0L }?.let { out += "glueClauses" to "$it" }
    if (stats.search.clausalPrimalStarts.sum > 0.0) {
        out += "clausalPrimalStarts" to "${stats.search.clausalPrimalStarts.sum.toLong()}"
        out += "clausalPrimalTrials" to "${stats.search.clausalPrimalTrials.sum.toLong()}"
        out += "clausalPrimalModels" to "${stats.search.clausalPrimalModels.sum.toLong()}"
        out += "clausalPrimalProposals" to "${stats.search.clausalPrimalProposals.sum.toLong()}"
        out += "clausalPrimalAccepted" to "${stats.search.clausalPrimalAccepted.sum.toLong()}"
        out += "clausalPrimalRejected" to "${stats.search.clausalPrimalRejected.sum.toLong()}"
        out += "clausalPrimalInfeasible" to "${stats.search.clausalPrimalInfeasible.sum.toLong()}"
        out += "clausalPrimalIncomplete" to "${stats.search.clausalPrimalIncomplete.sum.toLong()}"

    }
    return out
}

/**
 * A sequential portfolio's schedule for `-s`: the model's classification as one `profile` pair, then one
 * `arm.<label>` pair per arm with segments run, work and time spent, mean reward, failures, the credit earned by each
 * kind of contribution, and what each sharing channel cost and moved. Empty outside a sequential portfolio.
 */
internal fun portfolioStatPairs(stats: SolveStats): List<Pair<String, String>> {
    val profile = stats.portfolio.profile?.let {
        "profile" to "${it.problemClass} optimizing=${it.optimizing} wide=${it.wide} scheduling=${it.scheduling}"
    }
    val reseeding = stats.portfolio.reseedStaleThreshold?.let { "portfolioReseedStaleThreshold" to "$it" }
    val schedule = stats.portfolio.arms.map { arm ->
        val credit = arm.credit.entries.joinToString("") { (signal, amount) -> " $signal=${round4(amount)}" }
        val sharing = arm.sharing.channels.entries.joinToString("") { (channel, t) ->
            " share$channel=us:${t.nanos / NANOS_PER_MICRO},out:${t.exported},in:${t.imported},dup:${t.duplicates}"
        }
        "arm.${arm.label}" to
            "segments=${arm.segments} work=${arm.work} ms=${arm.millis} reward=${round4(arm.meanReward)} " +
            "failures=${arm.failures} faults=${arm.faults} maxMs=${arm.maxMillis} " +
            "initMs=${arm.initializationMillis} reseeds=${arm.reseeds}" +
            " initWork=${arm.initializationWork} initCancelled=${arm.initializationCancelled}" +
            "$credit$sharing"
    }
    val failures = stats.portfolio.arms.mapNotNull { arm ->
        arm.failure?.let {
            "armFailure.${arm.label}" to
                "{\"armId\":${it.armId},\"phase\":${diagnosticString(it.phase)},\"segment\":${it.segment}," +
                "\"work\":${it.work},\"type\":${diagnosticString(it.type)}," +
                "\"message\":${it.message?.let(::diagnosticString) ?: "null"},\"trace\":${diagnosticString(it.trace)}}"
        }
    }
    return listOfNotNull(profile, reseeding) + schedule + failures
}

private fun diagnosticString(value: String): String = buildString {
    append('"')
    for (char in value) {
        when (char) {
            '"' -> append("\\\"")

            '\\' -> append("\\\\")

            in '\u0000'..'\u001f', '\u007f', '\u2028', '\u2029' -> {
                append("\\u")
                append(char.code.toString(16).padStart(4, '0'))
            }

            else -> append(char)
        }
    }
    append('"')
}

private const val NANOS_PER_MICRO = 1_000L

/** Open-theory work and cost accounting pairs for `-s`. */
internal fun openTheoryStatPairs(stats: SolveStats, solveTimeMs: Long): List<Pair<String, String>> =
    with(stats.openTheory) {
        listOf(
            "openBoolDecisions" to "$openBoolDecisions",
            "openIntDecisions" to "$openIntDecisions",
            "openTheoryDecisions" to "$openTheoryDecisions",
            "openTheoryChecks" to "$openTheoryChecks",
            "openWork" to "$openWork",
        ) + with(stats.openTheoryClauses) {
            listOf(
                "openLearned" to "$learned",
                "openRelearned" to "$relearned",
                "openRestarts" to "$restarts",
                "openReductions" to "$reductions",
                "openDropped" to "$dropped",
                "openRetained" to "$retained",
                "openPeakRetained" to "$peakRetained",
                "openLearnedWatchVisits" to "$watchVisits",
                "openAssertingConflicts" to "$assertingConflicts",
                "openNonAssertingConflicts" to "$nonAssertingConflicts",
                "openReductionNs" to "$reductionNanos",
            )
        } + with(stats.openHints) {
            // Only a run that drew a hint has anything to say about one, so every other open solve reports
            // no hint block rather than a zeroed one.
            if (draws == 0L) {
                emptyList()
            } else {
                listOf(
                    "openHintDraws" to "$draws",
                    "openHintProduced" to "$produced",
                    "openHintVars" to "$hintedVars",
                    "openHintSteered" to "$steeredSplits",
                    "openHintMoves" to "$moves",
                )
            }
        } + smtStatPairs(stats, solveTimeMs)
    }

/** Exact SMT-theory lane counters, with rates only when their denominator is meaningful. */
private fun smtStatPairs(stats: SolveStats, solveTimeMs: Long): List<Pair<String, String>> = with(stats.smt) {
    if (this == SmtStats()) return emptyList()
    val out = ArrayList<Pair<String, String>>()
    out += continuationStatPairs("smt", continuation)
    out += "smtTheoryChecks" to "${stats.openTheory.openTheoryChecks}"
    if (solveTimeMs > 0L) {
        out += "smtTheoryChecksPerSec" to round4(stats.openTheory.openTheoryChecks / (solveTimeMs / 1000.0))
    }
    out += "smtConflicts" to "$conflicts"
    out += "smtExplainedConflicts" to "$explainedConflicts"
    out += "smtUnexplainedConflicts" to "$unexplainedConflicts"
    out += "smtConflictLiterals" to "$conflictLiterals"
    if (explainedConflicts > 0L) {
        out += "smtLiteralsPerExplainedConflict" to round4(
            conflictLiterals.toDouble() / explainedConflicts,
        )
    }
    out += "smtReductionRequests" to "$reductionRequests"
    out += "smtReductionCacheHits" to "$reductionCacheHits"
    out += "smtReductionAccepted" to "$reductionAccepted"
    out += "smtReductionDeclined" to "$reductionDeclined"
    out += "smtReductionMs" to round4(reductionNs / 1_000_000.0)
    out += "smtSourceLpOperations" to "${sourceLp.operations}"
    out += "smtSourceLpModeledWork" to "${sourceLp.modeledWork}"
    out += "smtSourceLpModeledAllocation" to "${sourceLp.modeledAllocation}"
    out += "smtSourceLpActiveNs" to "${sourceLp.activeNs}"
    out += "smtSourceLpPreparationWork" to "${sourceLp.preparationWork}"
    out += "smtSourceLpFloatWork" to "${sourceLp.floatWork}"
    out += "smtSourceLpContinuationWork" to "${sourceLp.continuationWork}"
    out += "smtSourceLpRefinementWork" to "${sourceLp.refinementWork}"
    out += "smtWitnessCandidates" to "$witnessCandidates"
    out += "smtWitnessAccepted" to "$witnessAccepted"
    out += "smtStrictWitnessCandidates" to "$strictWitnessCandidates"
    out += "smtStrictWitnessAccepted" to "$strictWitnessAccepted"
    out += "smtWideWitnessCandidates" to "$wideWitnessCandidates"
    out += "smtWideWitnessAccepted" to "$wideWitnessAccepted"
    if (witnessCandidates > 0L) {
        out += "smtWitnessAcceptance" to round4(
            witnessAccepted.toDouble() / witnessCandidates,
        )
    }
    if (strictWitnessCandidates > 0L) {
        out += "smtStrictWitnessAcceptance" to round4(
            strictWitnessAccepted.toDouble() / strictWitnessCandidates,
        )
    }
    if (wideWitnessCandidates > 0L) {
        out += "smtWideWitnessAcceptance" to round4(
            wideWitnessAccepted.toDouble() / wideWitnessCandidates,
        )
    }
    return out
}

/** Conflict-analysis diagnostic counters for `-s` — why 1UIP learning was skipped or rejected.
 *  Only the MiniZinc mode reports them today; kept apart from [searchStatPairs] so the other
 *  modes' leaner blocks stay lean. */
internal fun caStatPairs(stats: SolveStats): List<Pair<String, String>> = listOf(
    "caNotApplicable" to "${stats.ca.notApplicable.sum.toLong()}",
    "caNonAsserting" to "${stats.ca.nonAsserting.sum.toLong()}",
)

/** Print [pairs] one per line as `<prefix> key=value` — the shared stat-emission loop each mode
 *  runs with its own comment prefix (`%%%mzn-stat:`, `;`, `c`). */
internal fun printStatPairs(prefix: String, pairs: List<Pair<String, String>>) {
    for ((k, v) in pairs) println("$prefix $k=$v")
}

/** Round to four decimals for the rate / bound lines; integral values render without a fraction. */
private fun round4(x: Double): String {
    val r = round(x * 10000.0) / 10000.0
    return if (r == r.toLong().toDouble()) "${r.toLong()}" else "$r"
}

/** Terse presolve stat pairs for `-s`: which passes fired, the net constraint drop, and proven
 *  infeasibility — just enough to show presolve did something and which techniques, kept small so it
 *  doesn't crowd out the solve counters (the verbose readout is `dry-run-presolve`). Empty when
 *  presolve was off or a no-op. */
internal fun presolveStatPairs(stats: SolveStats): List<Pair<String, String>> {
    val p = stats.presolve ?: return emptyList()
    if (p.passes.isEmpty() && p.constraintsRemoved == 0 && !p.infeasible && p.effort == null) return emptyList()
    val out = ArrayList<Pair<String, String>>()
    p.effort?.let { e ->
        out += "presolveEmphasis" to e.emphasis
        out += "presolveAbortFraction" to "${e.abortFraction}"
        out += "presolveMaxRounds" to "${e.maxRounds}"
        out += "presolveProbeBudgetPerVar" to "${e.probeBudgetPerVar}"
        out += "presolveProbeTotalBudget" to "${e.probeTotalBudget}"
        out += "presolvePreparationMs" to "${e.elapsed.inWholeMilliseconds}"
        e.work?.let { out += "presolveWork" to "$it" }
        e.allowance?.let { out += "presolveWorkAllowance" to "$it" }
        e.rounds?.let { out += "presolveRoundEntries" to "$it" }
        e.probeCalls?.let { out += "presolveProbeCalls" to "$it" }
        for ((id, calls) in e.passCalls.entries.sortedBy { it.key }) out += "presolveCalls_$id" to "$calls"
    }
    if (p.passes.isNotEmpty()) out += "presolvePasses" to p.passes.joinToString(",")
    if (p.constraintsRemoved != 0) out += "presolveConstraintsRemoved" to "${p.constraintsRemoved}"
    if (p.infeasible) out += "presolveInfeasible" to "true"
    // The LP harvest's own contribution, broken out so it can be measured apart from the net counts above.
    p.lpHarvest?.let { lp ->
        if (lp.skipped) out += "lpHarvestSkipped" to "true"
        if (lp.rootInfeasible) out += "lpHarvestRootInfeasible" to "true"
        if (lp.boundsShaved != 0) out += "lpHarvestBoundsShaved" to "${lp.boundsShaved}"
        if (lp.objectiveLbRaised) out += "lpHarvestObjectiveLb" to "true"
        if (lp.constraintsRemoved != 0) out += "lpHarvestConstraintsRemoved" to "${lp.constraintsRemoved}"
        if (lp.equalitiesAdded != 0) out += "lpHarvestEqualitiesAdded" to "${lp.equalitiesAdded}"
        if (lp.relaxationNnz != 0) out += "lpHarvestRelaxationNnz" to "${lp.relaxationNnz}"
    }
    return out
}

private fun continuationStatPairs(prefix: String, stats: LpContinuationStats): List<Pair<String, String>> {
    if (stats.calls == 0L) return emptyList()
    return buildList {
        val key = "${prefix}Continuation"
        add("${key}Calls" to "${stats.calls}")
        add("${key}Eligible" to "${stats.eligible}")
        add("${key}Successes" to "${stats.successes}")
        add("${key}Builds" to "${stats.builds}")
        add("${key}Imports" to "${stats.imports}")
        add("${key}Pivots" to "${stats.pivots}")
        add("${key}Repairs" to "${stats.repairs}")
        add("${key}Restarts" to "${stats.restarts}")
        add("${key}Resumes" to "${stats.resumes}")
        add("${key}Invalidations" to "${stats.invalidations}")
        add("${key}Checks" to "${stats.checks}")
        add("${key}Work" to "${stats.work.values.sum()}")
        add("${key}Allocation" to "${stats.allocation.values.sum()}")
        add("${key}Ns" to "${stats.elapsedNs}")
        for ((phase, value) in stats.work) add("${key}Work_$phase" to "$value")
        for ((phase, value) in stats.allocation) add("${key}Allocation_$phase" to "$value")
        for ((terminal, value) in stats.declines) add("${key}Decline_$terminal" to "$value")
    }
}
