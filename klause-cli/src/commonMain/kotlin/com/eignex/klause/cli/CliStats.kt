package com.eignex.klause.cli

import com.eignex.klause.solver.result.SolveStats
import kotlin.math.round

/**
 * LP-relaxation success metrics for `-s`, as ordered `key`/`value` pairs each mode prints with its
 * own comment prefix. Returns empty when no LP-family technique ran (so non-LP solves print nothing
 * extra), keyed off [SolveStats.lpSolves] plus the standalone Lagrangian / energetic prunes.
 *
 * The headline measure is the prune *rate* (`lpPruneRate = lpPruned / lpSolves`) against the cost
 * (`lpPivotsPerSolve`, `lpMs`): a relaxation earns its place only if it prunes often enough to repay
 * the pivots it spends. [SolveStats.rootLpBound] versus the reported objective is the integrality
 * gap — the direct measure of how tight the relaxation is. The technique split (`lpInfeasible`,
 * `lpBoundPruned`, `lpLagrangianPruned`, `lpEnergeticPruned`, `lpBackjumps`) attributes the wins as
 * far as the engine can soundly separate them; cuts/hull columns feed the same `lpPruned` bound and
 * so cannot be split per node — `lpCuts` reports their volume instead. Every emitted key is
 * `lp`-prefixed so the block is unambiguously LP even in the flat `key=value` stat stream.
 */
internal fun lpStatPairs(stats: SolveStats): List<Pair<String, String>> {
    val solves = stats.lpSolves.sum
    val lagrangian = stats.lagrangianPruned.sum
    val energetic = stats.energeticPruned.sum
    if (solves == 0.0 && lagrangian == 0.0 && energetic == 0.0) return emptyList()

    val pruned = stats.lpPruned.sum
    val infeasible = stats.lpInfeasible.sum
    val out = ArrayList<Pair<String, String>>()
    out += "lpSolves" to "${solves.toLong()}"
    out += "lpPruned" to "${pruned.toLong()}"
    out += "lpInfeasible" to "${infeasible.toLong()}"
    out += "lpBoundPruned" to "${(pruned - infeasible).toLong()}"
    if (solves > 0.0) {
        out += "lpPruneRate" to round4(pruned / solves)
        out += "lpPivotsPerSolve" to round4(stats.lpPivots.sum / solves)
        out += "lpSeededRate" to round4(stats.lpSeeded.sum / solves)
    }
    out += "lpFixed" to "${stats.lpFixed.sum.toLong()}"
    out += "lpCuts" to "${stats.lpCuts.sum.toLong()}"
    out += "lpPivots" to "${stats.lpPivots.sum.toLong()}"
    if (stats.lpLuMaxFill.max.isFinite()) out += "lpLuMaxFill" to round4(stats.lpLuMaxFill.max)
    if (stats.lpLuMaxDensity.max.isFinite()) out += "lpLuMaxDensity" to round4(stats.lpLuMaxDensity.max)
    out += "lpBackjumps" to "${stats.lpBackjumps.sum.toLong()}"
    out += "lpLagrangianPruned" to "${lagrangian.toLong()}"
    out += "lpEnergeticPruned" to "${energetic.toLong()}"
    out += "lpMs" to "${stats.lpMs}"
    if (stats.rootLpBound.isFinite()) out += "lpRootBound" to round4(stats.rootLpBound)
    return out
}

/**
 * Local-search telemetry for `-s`, as ordered `key`/`value` pairs. Returns empty unless the LS engine
 * actually ran — keyed off `backend == "ls"` (a pure-LS solve) or `moves > 0` (an LS arm inside a
 * `"mixed"` portfolio), so complete-only solves print nothing here.
 *
 * yuck emits no runtime LS counters of its own (only the MiniZinc flattener's `flat*` keys), so these
 * mirror no upstream schema — they're the engine's own progress fingerprint. The headline pair is
 * `lsMoves` against `lsMovesPerSec` (raw throughput) and `lsTimeToBest` against `solveTime` (the anytime
 * profile: how early the best incumbent landed). `lsStalls` over `lsRestarts` shows how much of the
 * search was plateau-thrashing. `lsIncumbentViolation` is 0 once feasible, else the lowest residual cost
 * reached — the only signal of how close an otherwise-UNKNOWN run got. Every key is `ls`-prefixed so the
 * block is unambiguous in the flat stat stream, matching the `lp`-prefix convention.
 */
internal fun lsStatPairs(stats: SolveStats): List<Pair<String, String>> {
    val moves = stats.moves.sum
    if (stats.backend != "ls" && moves == 0.0) return emptyList()

    val out = ArrayList<Pair<String, String>>()
    out += "lsMoves" to "${moves.toLong()}"
    out += "lsRestarts" to "${stats.restarts.sum.toLong()}"
    out += "lsStalls" to "${stats.stalls.sum.toLong()}"
    if (stats.wallMs > 0L) out += "lsMovesPerSec" to round4(moves / (stats.wallMs / 1000.0))
    if (stats.timeToBestMs >= 0L) out += "lsTimeToBest" to round4(stats.timeToBestMs / 1000.0)
    if (stats.incumbentObjective.isFinite()) out += "lsIncumbentObjective" to round4(stats.incumbentObjective)
    if (stats.incumbentViolation.isFinite()) out += "lsIncumbentViolation" to round4(stats.incumbentViolation)
    return out
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
    return out
}
