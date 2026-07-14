package com.eignex.klause.backtrack.lp

import com.eignex.klause.backtrack.selector.VarRef
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.propagation.PropagationSession

/**
 * Reduced-cost-average branching: pick the unassigned variable with the highest LP branch score
 * (reduced-cost pseudo-cost × fractionality, [LpHints.branchScore]), or null when LP branching is off,
 * the LP gives no fractional signal, or the residual problem is too wide to scan. Purely advisory — the
 * descent falls back to the configured `VariableSelector` on null, and any chosen variable is a sound
 * branch, so search stays complete and correct regardless. `O(unassigned)` per call, capped.
 *
 * Lives in the search layer (not [LpEngine]'s home) because it reads the backtrack [VarRef] selector type
 * and the search-owned [LpHints]; the engine only records LP solutions into the hint sink.
 */
internal fun LpEngine.lpBranchPick(session: PropagationSession, hints: LpHints?): VarRef? {
    hints ?: return null
    if (problem.numBoolVars + problem.numIntVars > LP_BRANCH_SCAN_CAP) return null // too wide ⇒ delegate
    var best: VarRef? = null
    var bestScore = LP_BRANCH_MIN_SCORE
    for (b in 0 until problem.numBoolVars) {
        if (session.boolValue(b) != null) continue
        val s = hints.branchScore(VarRef.Bool(b))
        if (!s.isNaN() && s > bestScore) {
            bestScore = s
            best = VarRef.Bool(b)
        }
    }
    for (i in 0 until problem.numIntVars) {
        if (session.intDomain(i).size <= 1) continue
        val s = hints.branchScore(VarRef.IntVar(i))
        if (!s.isNaN() && s > bestScore) {
            bestScore = s
            best = VarRef.IntVar(i)
        }
    }
    return best
}

/** Minimum LP branch score (reduced-cost × fractionality) to override the configured selector; below
 *  this a variable is effectively LP-integral / cost-free, so the configured heuristic decides. */
private const val LP_BRANCH_MIN_SCORE = 1e-9

/** Skip reduced-cost-average branching above this variable count — the per-decision scan is `O(vars)`. */
private const val LP_BRANCH_SCAN_CAP = 8192
