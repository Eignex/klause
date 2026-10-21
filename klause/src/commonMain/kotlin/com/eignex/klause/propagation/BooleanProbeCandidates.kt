package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.PollStride

internal fun Problem.freeBooleanProbeCandidates(maxCandidates: Int, cancellation: Cancellation): IntArray {
    if (maxCandidates <= 0 || cancellation()) return intArrayOf()
    val live = BooleanArray(numBoolVars)
    val pinned = BooleanArray(numBoolVars)
    val poll = PollStride()
    fun stopped(): Boolean {
        cancellation.charge(10L)
        return (cancellation.workMeter() != null || poll.due()) && cancellation()
    }
    for (factor in factors) {
        if (stopped()) return intArrayOf()
        for (variable in factor.boolVars) {
            if (stopped()) return intArrayOf()
            live[variable] = true
        }
        if (factor is Clause && factor.literals.size == 1 && factor.allLiteralsBool(numBoolVars)) {
            pinned[Lit.variable(factor.literals[0])] = true
        }
    }
    var hasCandidate = false
    for (variable in live.indices) {
        if (stopped()) return intArrayOf()
        if (live[variable] && !pinned[variable]) hasCandidate = true
    }
    if (!hasCandidate || cancellation()) return intArrayOf()
    val root = if (this is BakedProblem) rootDeductions else propagate(cancellation = cancellation)
    if (root !is PropagationResult.Implied || cancellation()) return intArrayOf()
    val candidates = IntArrayList()
    for (variable in live.indices) {
        if (stopped()) break
        if (!live[variable] || pinned[variable] || root.boolValueOrNull(variable) != null) continue
        candidates.add(variable)
        if (candidates.size == maxCandidates) break
    }
    return candidates.toIntArray()
}
