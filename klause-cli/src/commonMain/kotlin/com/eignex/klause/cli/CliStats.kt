package com.eignex.klause.cli

import com.eignex.klause.solver.result.LpStats
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
    if (solves == 0.0 && lagrangian == 0.0 && energetic == 0.0 && splits == 0.0) return emptyList()

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
        out += "lpSeededRate" to round4(stats.lp.seeded.sum / solves)
        out += "lpRefactorizationsPerSolve" to round4(stats.lp.refactorizations.sum / solves)
    }
    out += "lpFixed" to "${stats.lp.fixed.sum.toLong()}"
    out += "lpCuts" to "${stats.lp.cuts.sum.toLong()}"
    out += "lpPivots" to "${stats.lp.pivots.sum.toLong()}"
    if (stats.lp.luMaxFill.max.isFinite()) out += "lpLuMaxFill" to round4(stats.lp.luMaxFill.max)
    if (stats.lp.luMaxDensity.max.isFinite()) out += "lpLuMaxDensity" to round4(stats.lp.luMaxDensity.max)
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
internal fun lsStatPairs(stats: SolveStats): List<Pair<String, String>> {
    val moves = stats.ls.moves.sum
    if (stats.run.backend != "ls" && moves == 0.0) return emptyList()

    val out = ArrayList<Pair<String, String>>()
    out += "lsMoves" to "${moves.toLong()}"
    out += "lsRestarts" to "${stats.search.restarts.sum.toLong()}"
    out += "lsStalls" to "${stats.ls.stalls.sum.toLong()}"
    if (stats.run.wallMs > 0L) out += "lsMovesPerSec" to round4(moves / (stats.run.wallMs / 1000.0))
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
