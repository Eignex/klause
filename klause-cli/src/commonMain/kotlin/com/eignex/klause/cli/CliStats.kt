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
    out += "lpBackjumps" to "${stats.lpBackjumps.sum.toLong()}"
    out += "lpLagrangianPruned" to "${lagrangian.toLong()}"
    out += "lpEnergeticPruned" to "${energetic.toLong()}"
    out += "lpMs" to "${stats.lpMs}"
    if (stats.rootLpBound.isFinite()) out += "lpRootBound" to round4(stats.rootLpBound)
    return out
}

/** Round to four decimals for the rate / bound lines; integral values render without a fraction. */
private fun round4(x: Double): String {
    val r = round(x * 10000.0) / 10000.0
    return if (r == r.toLong().toDouble()) "${r.toLong()}" else "$r"
}
