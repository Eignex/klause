package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.IntDomain

/**
 * The shared scan behind the four domain-metric variable selectors ([SmallestDomain],
 * [LargestDomain], [SmallestLowerBound], [LargestUpperBound]). Walks every free variable and
 * keeps the one whose score is the most extreme in [maximize]'s direction: a free bool scores
 * [boolScore], a free int (domain size > 1) scores [intScore] of its domain. Determined
 * variables — assigned bools and singleton int domains — never branch, so they are skipped.
 * Ties keep the earliest candidate, with bools preceding ints, matching each selector's
 * documented tie-break.
 */
internal fun pickByDomainMetric(
    session: PropagationSession,
    maximize: Boolean,
    boolScore: Long,
    intScore: (IntDomain) -> Long,
): VarRef? {
    var best: VarRef? = null
    var bestScore = if (maximize) Long.MIN_VALUE else Long.MAX_VALUE
    val problem = session.problem
    for (v in 0 until problem.numBoolVars) {
        if (session.boolValue(v) == null && improves(boolScore, bestScore, maximize)) {
            best = VarRef.Bool(v)
            bestScore = boolScore
        }
    }
    for (v in 0 until problem.numIntVars) {
        val d = session.intDomain(v)
        if (d.size <= 1) continue
        val score = intScore(d)
        if (improves(score, bestScore, maximize)) {
            best = VarRef.IntVar(v)
            bestScore = score
        }
    }
    return best
}

/** True when [candidate] beats [best] in [maximize]'s direction (strict, so ties keep the earlier). */
private fun improves(candidate: Long, best: Long, maximize: Boolean): Boolean =
    if (maximize) candidate > best else candidate < best
