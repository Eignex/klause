package com.eignex.klause.cli

import com.eignex.klause.solver.result.LpCertifierRouteStats
import com.eignex.klause.solver.result.LpCertifierStats
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
        lagrangian == 0.0 && energetic == 0.0 && splits == 0.0 && stats.lp.basisVerification.calls == 0L
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
    if (stats.lp.wallBackstop) out += "lpWallBackstop" to "1"
    if (stats.lp.demoted) out += "lpDemoted" to "1"
    if (stats.lp.luMaxFill.max.isFinite()) out += "lpLuMaxFill" to round4(stats.lp.luMaxFill.max)
    if (stats.lp.luMaxDensity.max.isFinite()) out += "lpLuMaxDensity" to round4(stats.lp.luMaxDensity.max)
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
    out += "learned" to "${stats.search.learnedClauses.sum.toLong()}"
    out += "relearned" to "${stats.search.relearned.sum.toLong()}"
    if (stats.search.peakDepth.max.isFinite()) out += "peakDepth" to "${stats.search.peakDepth.max.toLong()}"
    return out
}

/** Exact deterministic open-theory accounting pairs for `-s`. */
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
    val sharedChecks = stats.openTheory.openTheoryChecks - privateChecks
    val out = ArrayList<Pair<String, String>>()
    out += "smtPrivateChecks" to "$privateChecks"
    out += "smtSharedChecks" to "$sharedChecks"
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
    out += "smtSimplexAttempts" to "$simplexAttempts"
    out += "smtSimplexAccepted" to "$simplexAccepted"
    out += "smtSimplexDeclined" to "$simplexDeclined"
    out += "smtSimplexMs" to round4(simplexNs / 1_000_000.0)
    out += "smtFrac128Attempts" to "$frac128Attempts"
    out += "smtFrac128Eligible" to "$frac128Eligible"
    out += "smtFrac128Accepted" to "$frac128Accepted"
    out += "smtFrac128Escalations" to "$frac128Escalations"
    out += "smtFrac128OverflowEscalations" to "$frac128OverflowEscalations"
    out += "smtFrac128InputEscalations" to "$frac128InputEscalations"
    out += "smtEscalationMs" to round4(
        escalationNs / 1_000_000.0,
    )
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
    "caRejectedTrueLit" to "${stats.ca.rejectedTrueLit.sum.toLong()}",
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
    if (p.passes.isEmpty() && p.constraintsRemoved == 0 && !p.infeasible) return emptyList()
    val out = ArrayList<Pair<String, String>>()
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
