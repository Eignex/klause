package com.eignex.klause.backtrack.lp

import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.VarRef

// Search owns variable identities and advisory ranking; the engine only supplies LP hints.
internal fun LpEngine.lpBranchPick(session: PropagationSession, hints: LpHints?): VarRef? {
    hints ?: return null
    if (problem.numBoolVars + problem.numIntVars > LP_BRANCH_SCAN_CAP) return null // too wide ⇒ delegate
    val directionalAvailable = hints.hasDirectionalScores()
    var best: VarRef? = null
    var bestScore = LP_BRANCH_MIN_SCORE
    var runnerUp: VarRef? = null
    var runnerUpScore = LP_BRANCH_MIN_SCORE
    for (b in 0 until problem.numBoolVars) {
        if (session.boolValue(b) != null) continue
        val variable = VarRef.Bool(b)
        if (directionalAvailable) noteNodeOverhead(1L)
        val s = hints.branchScore(variable)
        if (!s.isNaN() && s > bestScore) {
            if (directionalAvailable) {
                runnerUp = best
                runnerUpScore = bestScore
            }
            bestScore = s
            best = variable
        } else if (directionalAvailable && s > runnerUpScore) {
            runnerUpScore = s
            runnerUp = variable
        }
    }
    for (i in 0 until problem.numIntVars) {
        if (session.intDomain(i).isFixed) continue
        val variable = VarRef.IntVar(i)
        if (directionalAvailable) noteNodeOverhead(1L)
        val s = hints.branchScore(variable)
        if (!s.isNaN() && s > bestScore) {
            if (directionalAvailable) {
                runnerUp = best
                runnerUpScore = bestScore
            }
            bestScore = s
            best = variable
        } else if (directionalAvailable && s > runnerUpScore) {
            runnerUpScore = s
            runnerUp = variable
        }
    }
    return if (directionalAvailable) hints.pickDirectional(best, runnerUp) ?: best else best
}

private fun LpHints.pickDirectional(baseline: VarRef?, runnerUp: VarRef?): VarRef? {
    val baselineGain = baseline?.let { directionalScore(it) } ?: Double.NaN
    val runnerUpGain = runnerUp?.let { directionalScore(it) } ?: Double.NaN
    val replace = runnerUpGain > LP_BRANCH_MIN_SCORE &&
        (baselineGain.isNaN() || runnerUpGain > baselineGain)
    val candidate = if (replace) runnerUp else if (baselineGain > LP_BRANCH_MIN_SCORE) baseline else null
    return selected(candidate, baseline, if (replace) runnerUpGain else baselineGain)
}

/** Minimum LP branch score (reduced-cost × fractionality) to override the configured selector; below
 *  this a variable is effectively LP-integral / cost-free, so the configured heuristic decides. */
private const val LP_BRANCH_MIN_SCORE = 1e-9

/** Skip reduced-cost-average branching above this variable count — the per-decision scan is `O(vars)`. */
private const val LP_BRANCH_SCAN_CAP = 8192
