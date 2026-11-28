package com.eignex.klause.backtrack.selector

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.VarRef

// Ties keep the earliest candidate, bools before ints, matching each selector's documented tie-break.
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
        if (session.needsBoolDecision(v) && improves(boolScore, bestScore, maximize)) {
            best = VarRef.Bool(v)
            bestScore = boolScore
        }
    }
    for (v in 0 until problem.numIntVars) {
        val d = session.intDomain(v)
        if (!session.needsIntDecision(v)) continue
        val score = intScore(d)
        if (improves(score, bestScore, maximize)) {
            best = VarRef.IntVar(v)
            bestScore = score
        }
    }
    return best
}

private fun improves(candidate: Long, best: Long, maximize: Boolean): Boolean =
    if (maximize) candidate > best else candidate < best
