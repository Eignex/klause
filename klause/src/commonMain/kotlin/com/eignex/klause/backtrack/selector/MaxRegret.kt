package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.math.abs
import kotlin.random.Random

/**
 * Max-regret variable selection for optimisation. The *regret* of a variable is the
 * difference between the best-case and worst-case contribution that branching choices on
 * it can make to the objective:
 *  - bool var `b` with weight `w`: regret = |w|.
 *  - int var `v` with coefficient `c` and domain `[lo..hi]`: regret = |c| · (hi - lo).
 *
 * Picks the unpinned variable with the maximum regret. Branching where the objective is
 * most sensitive lets the engine drive the upper bound down (or lower bound up) fastest —
 * a standard Choco / OR-tools default for `minimize`. When every remaining variable has
 * regret 0 (singleton or zero coefficient), delegates to [base] so the search makes
 * progress on feasibility too.
 *
 * Pair with [IndomainBest] for a complete objective-aware (var, value) strategy.
 */
internal class MaxRegret(private val objective: LinearObjective, private val base: VariableSelector = SmallestDomain) :
    VariableSelector {

    override fun pick(session: PropagationSession, rng: Random): VarRef? {
        val problem = session.problem
        var best: VarRef? = null
        var bestRegret = 0L
        for (v in 0 until problem.numBoolVars) {
            if (session.boolValue(v) != null) continue
            val w = if (v < objective.boolWeights.size) objective.boolWeights[v] else 0L
            val r = abs(w)
            if (r > bestRegret) {
                bestRegret = r
                best = VarRef.Bool(v)
            }
        }
        for (v in 0 until problem.numIntVars) {
            val d = session.intDomain(v)
            if (d.size <= 1) continue
            val c = if (v < objective.intCoefficients.size) objective.intCoefficients[v] else 0L
            val r = abs(c) * (d.max - d.min)
            if (r > bestRegret) {
                bestRegret = r
                best = VarRef.IntVar(v)
            }
        }
        return best ?: base.pick(session, rng)
    }

    override fun onConflict(varRef: VarRef) = base.onConflict(varRef)
    override fun onConflict(varRef: VarRef, unsat: PropagationResult.Unsat) = base.onConflict(varRef, unsat)
    override fun onCommit(varRef: VarRef) = base.onCommit(varRef)
    override fun onPropagation(implied: PropagationResult.Implied) = base.onPropagation(implied)
    override fun onRestart() = base.onRestart()
    override fun onSolution(snapshot: Sample) = base.onSolution(snapshot)
    override val tracksUnassign: Boolean get() = base.tracksUnassign
    override fun onUnassign(varRef: VarRef) = base.onUnassign(varRef)
}
