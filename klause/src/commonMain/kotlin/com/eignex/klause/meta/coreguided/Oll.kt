package com.eignex.klause.meta.coreguided

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.MutableIntIntMap

// Shared by the core-guided drivers so they agree on relaxer clauses, core projection and cost recovery.
internal object Oll {

    // [weight] is paid when [lit] ends up false.
    data class Soft(val lit: Int, val weight: Long = 1L) {
        init {
            require(weight > 0) { "Soft weight must be positive, was $weight" }
        }
    }

    inline fun <R> solveHardOnly(
        base: BakedProblem,
        params: BacktrackParams,
        onSat: (Sample) -> R,
        onUnsat: () -> R,
        onUnknown: (TerminationReason) -> R,
    ): R = when (val r = BacktrackSolver(base).solve(params)) {
        is SolveResult.Sat -> onSat(r.assignment)
        is SolveResult.Unsat -> onUnsat()
        is SolveResult.Unknown -> onUnknown(r.reason)
    }

    fun relaxerClause(origLit: Int, selector: Int): Clause =
        Clause(intArrayOf(origLit, Lit.make(selector, positive = true)))

    fun projectCoreToSofts(core: Assumptions, selectorToSoft: MutableIntIntMap): IntArray {
        val out = IntArrayList()
        for (i in core.boolKeys.indices) {
            val softIdx = selectorToSoft.getOrDefault(core.boolKeys[i], -1)
            if (softIdx < 0) continue
            // Only a selector pinned `false` is a satisfied-soft assumption the core refuted.
            if (!core.boolValues[i]) out.add(softIdx)
        }
        return out.toIntArray()
    }

    fun softCost(sample: Sample, softs: List<Soft>): Long {
        var cost = 0L
        for (s in softs) {
            if (!Lit.evaluate(s.lit, sample.boolValue(Lit.variable(s.lit)))) cost += s.weight
        }
        return cost
    }

    // A witness can cost more than the proven [lb]: a spent soft may be relaxed by a blocker forced true for another
    // core. Such a witness is re-solved under the cap `Σ wᵢ·¬softLitᵢ ≤ lb`, falling back to it when the cap does
    // not fit an Int-weight pseudo-Boolean.
    fun recoverOptimalSample(
        base: BakedProblem,
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
            settings = base.settings,
            semantics = base.semantics,
            numBoolVars = base.numBoolVars,
            numIntVars = base.numIntVars,
            intDomains = base.rootIntDomains(),
            factors = factors,
            modelBounds = base.intBounds,
        )
        return (BacktrackSolver(problem.bake()).solve(params) as? SolveResult.Sat)?.assignment ?: sample
    }
}
