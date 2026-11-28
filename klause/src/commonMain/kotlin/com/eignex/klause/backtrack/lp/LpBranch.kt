package com.eignex.klause.backtrack.lp

import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.VarRef

// The unassigned variable with the highest reduced-cost × fractionality score. Advisory only: on null the
// descent uses the configured selector, and any chosen variable is a sound branch. It lives in the search
// layer because it reads VarRef and the search-owned LpHints; the engine only records LP solutions.
internal fun LpEngine.lpBranchPick(session: PropagationSession, hints: LpHints?): VarRef? {
    hints ?: return null
    if (problem.numBoolVars + problem.numIntVars > LP_BRANCH_SCAN_CAP) return null
    var best: VarRef? = null
    var bestScore = LP_BRANCH_MIN_SCORE
    for (b in 0 until problem.numBoolVars) {
        if (session.boolValue(b) != null) continue
        val s = hints.branchScore(b, isBool = true)
        if (!s.isNaN() && s > bestScore) {
            bestScore = s
            best = VarRef.Bool(b)
        }
    }
    for (i in 0 until problem.numIntVars) {
        if (session.intDomain(i).isFixed) continue
        val s = hints.branchScore(i, isBool = false)
        if (!s.isNaN() && s > bestScore) {
            bestScore = s
            best = VarRef.IntVar(i)
        }
    }
    if (!hints.hasDirectionalCandidates) return best.also { hints.selected(null) }
    return hints.pickVariable(best, LP_BRANCH_MIN_SCORE,
        available = { variable ->
            when (variable) {
                is VarRef.Bool -> session.boolValue(variable.varId) == null
                is VarRef.IntVar -> !session.intDomain(variable.varId).isFixed
            }
        },
        cancelled = { params.cancellation() })
}

// Below this a variable is effectively LP-integral or cost-free, so the configured selector decides.
private const val LP_BRANCH_MIN_SCORE = 1e-9

// The per-decision scan is O(vars).
private const val LP_BRANCH_SCAN_CAP = 8192
