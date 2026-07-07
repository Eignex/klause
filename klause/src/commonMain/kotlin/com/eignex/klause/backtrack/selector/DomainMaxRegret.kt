package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationSession
import kotlin.random.Random

/**
 * Largest regret first (MiniZinc's `max_regret`): the free variable with the greatest gap between
 * the two smallest values still in its domain — the cost of *not* taking its best value. Free bools
 * have the fixed regret `1` (domain `{0, 1}`), so an int var with a wider low-end gap outranks them.
 * Ties keep the earliest variable (bools precede ints).
 *
 * Domain-only (no objective), unlike the objective-weighted [MaxRegret] — this is the one the
 * `max_regret` search annotation maps to, since the annotation applies to satisfaction too.
 */
object DomainMaxRegret : VariableSelector {
    override fun pick(session: PropagationSession, rng: Random): VarRef? {
        var best: VarRef? = null
        var bestRegret = Long.MIN_VALUE
        val problem = session.problem
        for (v in 0 until problem.numBoolVars) {
            if (session.boolValue(v) == null && 1L > bestRegret) {
                best = VarRef.Bool(v)
                bestRegret = 1L
            }
        }
        for (v in 0 until problem.numIntVars) {
            val d = session.intDomain(v)
            if (d.size <= 1) continue
            val regret = d.valueAt(1) - d.valueAt(0)
            if (regret > bestRegret) {
                best = VarRef.IntVar(v)
                bestRegret = regret
            }
        }
        return best
    }
}
