package com.eignex.klause.presolve

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.util.IntHashSet

internal object Probing {

    /**
     * Probing as a fixpoint presolve pass. For each free Boolean candidate it tentatively pins the
     * literal, runs the engine's own [Problem.propagate], and harvests only deductions that hold in
     * **every** solution, so the problem's satisfiability and optimum are untouched:
     *  - **failed literal**: pinning `v = true` propagates to a conflict, so `v` is false in every
     *    solution — emit the unit clause `!v` (mirror for the negative polarity). Soundness rests
     *    entirely on the conflict: `propagate` is sound-but-incomplete, so an Unsat is a genuine
     *    proof of infeasibility, never a false positive.
     *  - **common bound**: a bound that propagation implies under *both* polarities holds
     *    unconditionally (the variable is true or false in any solution, and both cases force it).
     *    Every solution lies in one case or the other, so the valid deduction is their *union* —
     *    `min` of the two implied lower bounds, `max` of the two implied upper bounds. Taking the
     *    tighter bound of the pair instead would discard the solutions of the weaker case.
     *
     * Discovered binary implications are deliberately **not** persisted: there is no implication
     * store to hold them, and inventing one is out of scope. They are simply dropped.
     *
     * Idempotent: a derived unit already present is not re-added and a bound already at least as
     * tight is not re-applied, so the round engine reaches a fixpoint instead of churning. Bounded
     * by [maxCandidates] free Booleans per invocation (in id order) so a model with very many
     * Booleans cannot blow up presolve time; the round engine re-enters the pass after other passes
     * fire, and a later round picks up where bumping units shifted the free set.
     */
    fun probe(problem: Problem, maxCandidates: Int, cancellation: Cancellation): PassDelta {
        val pinned = IntHashSet()
        for (f in problem.factors) {
            if (f is Clause && f.literals.size == 1) pinned.add(Lit.variable(f.literals[0]))
        }

        val units = ArrayList<Factor>()
        val domains = problem.intDomains.copyOf()
        var domainsChanged = false
        var probed = 0
        var v = 0
        while (v < problem.numBoolVars && probed < maxCandidates) {
            if (cancellation()) break
            if (v in pinned) {
                v++
                continue
            }
            probed++
            val tryTrue = problem.propagate(Assumptions.None.withBool(v, true), cancellation)
            if (tryTrue is PropagationResult.Unsat) {
                units.add(Clause(intArrayOf(Lit.make(v, false))))
                v++
                continue
            }
            val tryFalse = problem.propagate(Assumptions.None.withBool(v, false), cancellation)
            if (tryFalse is PropagationResult.Unsat) {
                units.add(Clause(intArrayOf(Lit.make(v, true))))
                v++
                continue
            }
            domainsChanged = harvestCommonBounds(
                tryTrue as PropagationResult.Implied,
                tryFalse as PropagationResult.Implied,
                problem,
                domains,
            ) || domainsChanged
            v++
        }

        if (units.isEmpty() && !domainsChanged) return PassDelta()
        return PassDelta(addedFactors = units, domains = if (domainsChanged) domains else null)
    }

    /** Fold the intersection of the bounds implied under each polarity into [domains]; return whether
     *  any domain was strictly tightened. A bound implied under both `v = true` and `v = false` holds
     *  in every solution, so the tighter side of the lower bounds and the looser-clamping side of the
     *  upper bounds are both globally valid. */
    private fun harvestCommonBounds(
        whenTrue: PropagationResult.Implied,
        whenFalse: PropagationResult.Implied,
        problem: Problem,
        domains: Array<IntDomain>,
    ): Boolean {
        var changed = false
        for (i in 0 until problem.numIntVars) {
            val d = domains[i]
            val loTrue = impliedMin(whenTrue, i)
            val loFalse = impliedMin(whenFalse, i)
            if (loTrue != null && loFalse != null) {
                val common = minOf(loTrue, loFalse)
                if (common > d.min) {
                    domains[i] = domains[i].withMinAtLeast(common)
                    changed = true
                }
            }
            val hiTrue = impliedMax(whenTrue, i)
            val hiFalse = impliedMax(whenFalse, i)
            if (hiTrue != null && hiFalse != null) {
                val common = maxOf(hiTrue, hiFalse)
                if (common < domains[i].max) {
                    domains[i] = domains[i].withMaxAtMost(common)
                    changed = true
                }
            }
        }
        return changed
    }

    /** Lower bound `propagate` implied for int [i] — a singleton pin counts as a lower bound at the
     *  pinned value — or `null` when this polarity implied nothing about [i]'s lower bound. */
    private fun impliedMin(implied: PropagationResult.Implied, i: Int): Long? =
        implied.intValueOrNull(i) ?: implied.intMinOrNullCompat(i)

    /** Upper bound `propagate` implied for int [i]; mirror of [impliedMin]. */
    private fun impliedMax(implied: PropagationResult.Implied, i: Int): Long? =
        implied.intValueOrNull(i) ?: implied.intMaxOrNullCompat(i)
}
