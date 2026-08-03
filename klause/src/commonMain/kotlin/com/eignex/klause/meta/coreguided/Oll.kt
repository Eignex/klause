package com.eignex.klause.meta.coreguided

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.MutableIntIntMap

/**
 * Shared OLL (relax-by-selector) scaffolding for the core-guided MaxSAT drivers
 * [CoreGuidedOptimizer] (Fu-Malik / RC2) and [TotalizerOptimizer] (totalizer / PB
 * threshold chain). Both used to reimplement the same selector-per-soft, relaxer-clause
 * `(origLit ∨ selector)`, project-core-to-softs and `lb += wMin` bookkeeping with subtly
 * different shapes — and the spent-soft sample-vs-bound mismatch (#80) lived in one driver
 * and not the other precisely because they had drifted. Centralising the scaffolding here
 * keeps them from diverging again.
 */
internal object Oll {

    /** One soft constraint: [lit] should be true; pay [weight] (positive) if it ends up
     *  false. The shared cost vocabulary both drivers translate their inputs into. */
    data class Soft(val lit: Int, val weight: Long = 1L) {
        init {
            require(weight > 0) { "Soft weight must be positive, was $weight" }
        }
    }

    /** Base case shared by every driver: with no softs the cost is 0, so the answer is
     *  just whether the hard constraints are satisfiable. */
    inline fun <R> solveHardOnly(
        base: Problem,
        params: BacktrackParams,
        onSat: (Sample) -> R,
        onUnsat: () -> R,
        onUnknown: (TerminationReason) -> R,
    ): R = when (val r = BacktrackSolver(base.bake()).solve(params)) {
        is SolveResult.Sat -> onSat(r.assignment)
        is SolveResult.Unsat -> onUnsat()
        is SolveResult.Unknown -> onUnknown(r.reason)
    }

    /** Relaxer clause `(origLit ∨ selector)`: assuming `selector = false` (the standard
     *  initial assumption) pins `origLit` true; letting the selector float lets the soft be
     *  relaxed. */
    fun relaxerClause(origLit: Int, selector: Int): Clause =
        Clause(intArrayOf(origLit, Lit.make(selector, positive = true)))

    /** Project an assumption-[core] back to soft indices: the softs whose selector the core
     *  pins to `false` (matching the "soft satisfied ⇒ selector assumed false" shape).
     *  [selectorToSoft] maps each selector var id to its soft index. */
    fun projectCoreToSofts(core: Assumptions, selectorToSoft: MutableIntIntMap): IntArray {
        val out = IntArrayList()
        for (i in core.boolKeys.indices) {
            val softIdx = selectorToSoft.getOrDefault(core.boolKeys[i], -1)
            if (softIdx < 0) continue
            // Only selectors the core pins to `false` correspond to a satisfied-soft
            // assumption the core refuted.
            if (!core.boolValues[i]) out.add(softIdx)
        }
        return out.toIntArray()
    }

    /** True soft cost of [sample]: the summed weight of every soft in [softs] whose literal
     *  the sample leaves unsatisfied. */
    fun softCost(sample: Sample, softs: List<Soft>): Long {
        var cost = 0L
        for (s in softs) {
            if (!Lit.evaluate(s.lit, sample.bools[Lit.variable(s.lit)])) cost += s.weight
        }
        return cost
    }

    /**
     * Given an OLL witness [sample] proved optimal at lower bound [lb], return a sample
     * whose *true* soft cost equals [lb] (#80). The core-guided relaxer machinery can hand
     * back a witness whose true cost exceeds [lb] — a spent soft may be relaxed "for free"
     * by a per-core blocker forced true for another core's sake, so the recovered
     * assignment violates more softs than the reported bound. Callers that read the
     * assignment (rather than only the cost) then see a non-optimal sample.
     *
     * If the witness already costs exactly [lb] it is returned unchanged. Otherwise we
     * re-solve the hard problem under an explicit cap on the true cost,
     * `Σ wᵢ·¬softLitᵢ ≤ lb`, which is feasible (lb is the proven optimum, so a cost-`lb`
     * model exists) and forces any recovered sample to a cost of exactly [lb].
     *
     * Falls back to the original witness when the cost cap can't be represented as an
     * [Int]-weight [PseudoBoolean] (a weight or [lb] exceeding [Int.MAX_VALUE]).
     */
    fun recoverOptimalSample(
        base: Problem,
        softs: List<Soft>,
        sample: Sample,
        lb: Long,
        params: BacktrackParams,
    ): Sample {
        if (softCost(sample, softs) == lb) return sample
        if (lb > Int.MAX_VALUE) return sample
        for (s in softs) if (s.weight > Int.MAX_VALUE) return sample

        val weights = LongArray(softs.size) { softs[it].weight }
        val negLits = IntArray(softs.size) { Lit.negate(softs[it].lit) }
        val factors = ArrayList<Factor>(base.factors.size + 1)
        for (f in base.factors) factors.add(f)
        factors.add(PseudoBoolean(weights = weights, literals = negLits, op = PbOp.LE, bound = lb))
        val problem = Problem(
            numBoolVars = base.numBoolVars,
            numIntVars = base.numIntVars,
            intDomains = base.intDomains,
            factors = factors,
        )
        return (BacktrackSolver(problem.bake()).solve(params) as? SolveResult.Sat)?.assignment ?: sample
    }
}
