package com.eignex.klause.backtrack.lp

import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.VarRef

// Search owns variable selection so the LP engine depends only on the hint-recording sink.
internal fun LpEngine.lpBranchPick(session: PropagationSession, hints: LpHints?): VarRef? {
    hints ?: return null
    if (problem.numBoolVars + problem.numIntVars > LP_BRANCH_SCAN_CAP) return null // too wide ⇒ delegate
    hints.beginPassiveSelection()
    var best: VarRef? = null
    var bestScore = LP_BRANCH_MIN_SCORE
    var directional: VarRef? = null
    var gainScore = LP_BRANCH_MIN_SCORE
    var reference: VarRef? = null
    var referenceScore = LP_BRANCH_MIN_SCORE
    for (b in 0 until problem.numBoolVars) {
        if (session.boolValue(b) != null) continue
        val variable = VarRef.Bool(b)
        val gain = hints.directionalScore(variable)
        if (gain > gainScore) {
            gainScore = gain
            directional = variable
        }
        val shadow = hints.referenceDirectionalScore(variable)
        if (shadow > referenceScore) {
            referenceScore = shadow
            reference = variable
        }
        val s = hints.branchScore(variable)
        if (!s.isNaN() && s > bestScore) {
            bestScore = s
            best = variable
        }
    }
    for (i in 0 until problem.numIntVars) {
        if (session.intDomain(i).isFixed) continue
        val variable = VarRef.IntVar(i)
        val gain = hints.directionalScore(variable)
        if (gain > gainScore) {
            gainScore = gain
            directional = variable
        }
        val shadow = hints.referenceDirectionalScore(variable)
        if (shadow > referenceScore) {
            referenceScore = shadow
            reference = variable
        }
        val s = hints.branchScore(variable)
        if (!s.isNaN() && s > bestScore) {
            bestScore = s
            best = variable
        }
    }
    hints.compareDirectionalRankings(directional, reference, params.cancellation)
    if (directional != null) return hints.selectVariable(directional, best, cancelled = { params.cancellation() })
    hints.selected(null)
    return best
}

// Scores near zero do not justify overriding the fallback selector.
private const val LP_BRANCH_MIN_SCORE = 1e-9

// The fixed scan cap bounds per-branch advisory work.
private const val LP_BRANCH_SCAN_CAP = 8192
