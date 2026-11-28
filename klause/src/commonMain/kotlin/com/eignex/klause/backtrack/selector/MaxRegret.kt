package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.search.VarRef
import com.eignex.util.numbers.saturatedDistance
import com.eignex.util.numbers.saturatedMul
import com.eignex.util.numbers.saturatedSub
import kotlin.random.Random

// Regret is the spread of a variable's objective contribution: `|w|` for a bool, `|c|·(hi − lo)` for an
// int. When every remaining regret is 0, [base] decides.
internal class MaxRegret(private val objective: LinearObjective, private val base: VariableSelector = SmallestDomain) :
    VariableSelector {

    override fun fresh() = MaxRegret(objective, base.fresh())

    override fun pick(session: PropagationSession, rng: Random): VarRef? {
        val problem = session.problem
        var best: VarRef? = null
        var bestRegret = 0L
        for (v in 0 until problem.numBoolVars) {
            if (!session.needsBoolDecision(v)) continue
            val w = if (v < objective.boolWeightValues.size) objective.boolWeightValues[v] else 0L
            val r = saturatedDistance(w, 0L)
            if (r > bestRegret) {
                bestRegret = r
                best = VarRef.Bool(v)
            }
        }
        for (v in 0 until problem.numIntVars) {
            val d = session.intDomain(v)
            if (!session.needsIntDecision(v)) continue
            val c = if (v < objective.intCoefficientValues.size) objective.intCoefficientValues[v] else 0L
            val r = saturatedMul(saturatedDistance(c, 0L), saturatedSub(d.max, d.min))
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
