package com.eignex.klause.backtrack.lp

import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.VarRef

/**
 * Reduced-cost-average branching: select the highest unassigned LP branch score
 * (reduced-cost pseudo-cost × fractionality, [LpHints.branchScore]), or null when LP branching is off,
 * the LP gives no fractional signal, or the residual problem is too wide to scan. Purely advisory — the
 * descent falls back to the configured `VariableSelector` on null, and any chosen variable is a sound
 * branch, so search stays complete and correct regardless. Reliable passive history compares the
 * winner with the strongest reliable passive challenger. Missing baseline history permits a
 * funded child-value comparison with that challenger below the root;
 * both corresponding child bounds must be nondecreasing. Weaker bounds rank first, with stronger
 * bounds breaking weak ties. Unavailable
 * advice retains the score winner.
 * `O(unassigned)` per call, capped.
 *
 * Lives in the search layer (not [LpEngine]'s home) because it reads the backtrack [VarRef] selector type
 * and the search-owned [LpHints]; the engine only records LP solutions into the hint sink.
 */
internal fun LpEngine.lpBranchPick(session: PropagationSession, hints: LpHints?): VarRef? {
    hints ?: return null
    if (problem.numBoolVars + problem.numIntVars > LP_BRANCH_SCAN_CAP) return null // too wide ⇒ delegate
    val compareHistory = hints.prepareVariableComparison(problem.numBoolVars + problem.numIntVars)
    var best: VarRef? = null
    var bestScore = LP_BRANCH_MIN_SCORE
    var challenger: VarRef? = null
    var challengerScore = LP_BRANCH_MIN_SCORE
    for (b in 0 until problem.numBoolVars) {
        if (session.boolValue(b) != null) continue
        val variable = VarRef.Bool(b)
        val score = hints.branchScore(variable)
        if (score > bestScore) {
            bestScore = score
            best = variable
        }
        val learned = if (compareHistory) hints.directionalScore(variable) else Double.NaN
        if (learned > challengerScore) {
            challengerScore = learned
            challenger = variable
        }
    }
    for (i in 0 until problem.numIntVars) {
        if (session.intDomain(i).isFixed) continue
        val variable = VarRef.IntVar(i)
        val score = hints.branchScore(variable)
        if (score > bestScore) {
            bestScore = score
            best = variable
        }
        val learned = if (compareHistory) hints.directionalScore(variable, session.intDomain(i)) else Double.NaN
        if (learned > challengerScore) {
            challengerScore = learned
            challenger = variable
        }
    }
    if (challenger == null || challenger == best) return best
    val historyProposal = if (compareHistory) {
        hints.compareVariables(best, (best as? VarRef.IntVar)?.let { session.intDomain(it.varId) },
            challenger, (challenger as? VarRef.IntVar)?.let { session.intDomain(it.varId) })
    } else null
    val proposal = historyProposal ?: if (compareHistory && hints.probeVariableChoiceReady) {
        hints.compareProbeVariables(best, (best as? VarRef.IntVar)?.let { session.intDomain(it.varId) },
            challenger, (challenger as? VarRef.IntVar)?.let { session.intDomain(it.varId) },
            reserve = ::reserveProbeWork, estimate = ::directionalValues)
    } else null
    return hints.applyVariableChoice(proposal, best)
}

/** Minimum LP branch score (reduced-cost × fractionality) to override the configured selector; below
 *  this a variable is effectively LP-integral / cost-free, so the configured heuristic decides. */
private const val LP_BRANCH_MIN_SCORE = 1e-9

/** Skip reduced-cost-average branching above this variable count — the per-decision scan is `O(vars)`. */
private const val LP_BRANCH_SCAN_CAP = 8192
