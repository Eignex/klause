package com.eignex.klause.solver.optimize

import com.eignex.klause.ast.PbOp
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SatisfyResult
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.TerminationReason
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.ReifiedCardinality
import com.eignex.klause.solver.factor.ReifiedPseudoBoolean
import com.eignex.klause.solver.satisfyUnderAssumptions
import com.eignex.klause.util.IntArrayList

/**
 * Totalizer-encoded core-guided MaxSAT optimiser for **unweighted** problems. Builds
 * one [Problem] up front with:
 *  - per soft, a fresh selector `r_i` and a relaxer clause `(origLit ∨ r_i)`;
 *  - a chain of `n` reified cardinality threshold bits `T_k ↔ (Σ r_i ≥ k)` for
 *    `k = 1..n`.
 *
 * The OLL loop then iterates by **only changing the assumption layer**: at lower bound
 * `lb`, assume `T_{lb+1} = false` (i.e. fewer than `lb+1` selectors are true, equivalent
 * to "cost ≤ lb"). On unsat, `lb++` and switch the assumption to the next threshold bit
 * — no Problem rebuild, no fresh blockers, no per-core cardinalities. Termination is
 * the first `Sat`; at that point `lb` equals the optimum.
 *
 * Trade-off vs the per-core Fu-Malik shape in [CoreGuidedOptimizer]: the up-front bake
 * is more expensive (one [ReifiedCardinality] per threshold), but every iteration after
 * is cheap — one solve under a single-bit assumption. On problems with many cores the
 * amortised cost is lower.
 *
 * **Weighted MaxSAT** is out of scope here; the encoding above only counts selectors
 * uniformly. For weighted, fall back to [CoreGuidedOptimizer] which handles the RC2
 * weight-splitting + stratification — totalizer-with-weights (pseudo-Boolean encoding)
 * is a future extension.
 */
internal class TotalizerOptimizer(val baseProblem: Problem) {

    /** Soft literal that should be true; cost 1 if it ends up false. Weight is fixed at
     *  1 — this optimiser is the unweighted-MaxSAT specialisation. */
    data class Soft(val lit: Int)

    /** Weighted soft literal: cost [weight] (positive integer) if [lit] ends up false. */
    data class WeightedSoft(val lit: Int, val weight: Long) {
        init {
            require(weight > 0) { "weight must be positive, was $weight" }
        }
    }

    sealed interface Result {
        val sample: Sample?
        val lowerBound: Long
        data class Optimal(override val sample: Sample, override val lowerBound: Long) : Result
        data class Infeasible(val reason: String = "hard constraints unsat") : Result {
            override val sample: Sample? = null
            override val lowerBound: Long = 0L
        }
        data class Unknown(val reason: TerminationReason, override val lowerBound: Long) : Result {
            override val sample: Sample? = null
        }
    }

    fun minimize(softs: List<Soft>, params: BacktrackParams = BacktrackParams()): Result {
        if (softs.isEmpty()) {
            return when (val r = BacktrackSolver(baseProblem).solve(params)) {
                is SolveResult.Sat -> Result.Optimal(r.assignment, 0L)
                is SolveResult.Unsat -> Result.Infeasible()
                is SolveResult.Unknown -> Result.Unknown(r.reason, 0L)
            }
        }
        val n = softs.size
        var nextBoolId = baseProblem.numBoolVars
        val selectors = IntArray(n) { nextBoolId++ }
        // Threshold aux bools T_1..T_n: T_k ↔ (Σ selectors ≥ k). T_0 is trivially true
        // (sum ≥ 0 always); we don't materialise it.
        val thresholds = IntArray(n) { nextBoolId++ }

        val factors = ArrayList<Factor>(baseProblem.factors.size + n + n)
        for (f in baseProblem.factors) factors.add(f)
        // Relaxer clause per soft: (origLit ∨ selector). Setting selector=true relaxes
        // the soft (allowing origLit false); selector=false forces origLit true.
        for (i in 0 until n) {
            factors.add(Clause(intArrayOf(softs[i].lit, Lit.make(selectors[i], positive = true))))
        }
        // Threshold reifications. min=k, max=n encodes "sum ≥ k": the aux bit is true
        // iff the count of relaxations meets or exceeds k.
        val selectorLits = IntArray(n) { Lit.make(selectors[it], positive = true) }
        for (k in 1..n) {
            factors.add(
                ReifiedCardinality(
                    auxBoolVar = thresholds[k - 1],
                    literals = selectorLits,
                    min = k,
                    max = n,
                ),
            )
        }
        val problem = Problem(
            numBoolVars = nextBoolId,
            numIntVars = baseProblem.numIntVars,
            intDomains = baseProblem.intDomains,
            factors = factors,
        )
        val solver = BacktrackSolver(problem)

        var lb = 0L
        while (lb <= n) {
            // Assume the next threshold bit is false — i.e. we forbid (lb+1) or more
            // relaxations, allowing only `lb` of them. When `lb == n`, no threshold to
            // assume (any cost is admissible) → final solve is unconstrained on cost.
            val assumptions = if (lb < n) {
                Assumptions(bools = mapOf(thresholds[lb.toInt()] to false))
            } else {
                Assumptions.None
            }
            when (val r = solver.satisfyUnderAssumptions(assumptions, params)) {
                is SatisfyResult.Sat -> return Result.Optimal(r.sample, lb)

                is SatisfyResult.GloballyUnsat -> return Result.Infeasible()

                is SatisfyResult.Unknown -> return Result.Unknown(r.reason, lb)

                is SatisfyResult.UnsatUnderAssumptions -> {
                    // Core says "more than `lb` softs must be relaxed" — bump and retry.
                    lb++
                }
            }
        }
        // lb > n is impossible (we'd have hit Sat at lb=n; otherwise the hard
        // constraints alone are unsat). Defensive fallback to a final solve so we
        // surface that case as Infeasible rather than looping.
        return Result.Infeasible()
    }

    /**
     * Weighted MaxSAT via a pseudo-Boolean threshold chain. For each integer threshold
     * `k = 1..totalWeight`, a [ReifiedPseudoBoolean] reifies `aux_k ↔ (Σ wᵢ·rᵢ ≥ k)`;
     * the OLL loop assumes `aux_{lb+1} = false` and bumps `lb` by the core's min weight
     * each unsat. Termination: SAT under the current threshold-bit assumption.
     *
     * **Scope.** The encoding allocates one aux + one PB factor per integer ≤ total
     * weight, so bake cost is `O(totalWeight · |softs|)`. Suitable for moderate weights;
     * for sparse-large-weight instances (e.g. weights in the millions), prefer the
     * Fu-Malik / RC2 path in [CoreGuidedOptimizer], which pays per-core rather than per
     * threshold value.
     */
    fun minimizeWeighted(softs: List<WeightedSoft>, params: BacktrackParams = BacktrackParams()): Result {
        if (softs.isEmpty()) {
            return when (val r = BacktrackSolver(baseProblem).solve(params)) {
                is SolveResult.Sat -> Result.Optimal(r.assignment, 0L)
                is SolveResult.Unsat -> Result.Infeasible()
                is SolveResult.Unknown -> Result.Unknown(r.reason, 0L)
            }
        }
        val n = softs.size
        val totalWeight = softs.sumOf { it.weight }
        require(totalWeight <= Int.MAX_VALUE) {
            "minimizeWeighted: sum of weights exceeds Int.MAX_VALUE — use CoreGuidedOptimizer for very large weights"
        }
        val totalWeightInt = totalWeight.toInt()
        var nextBoolId = baseProblem.numBoolVars
        val selectors = IntArray(n) { nextBoolId++ }
        // Threshold aux bits A_k = "weighted sum ≥ k" for k = 1..totalWeight.
        val thresholds = IntArray(totalWeightInt) { nextBoolId++ }

        val factors = ArrayList<Factor>(baseProblem.factors.size + n + totalWeightInt)
        for (f in baseProblem.factors) factors.add(f)
        for (i in 0 until n) {
            factors.add(Clause(intArrayOf(softs[i].lit, Lit.make(selectors[i], positive = true))))
        }
        val selectorLits = IntArray(n) { Lit.make(selectors[it], positive = true) }
        val weightsInt = IntArray(n) { softs[it].weight.toInt() }
        for (k in 1..totalWeightInt) {
            factors.add(
                ReifiedPseudoBoolean(
                    auxBoolVar = thresholds[k - 1],
                    weights = weightsInt,
                    literals = selectorLits,
                    op = PbOp.GE,
                    bound = k,
                ),
            )
        }
        val problem = Problem(
            numBoolVars = nextBoolId,
            numIntVars = baseProblem.numIntVars,
            intDomains = baseProblem.intDomains,
            factors = factors,
        )
        val solver = BacktrackSolver(problem)

        // Soft selectors we still assume "= false" (don't relax). When a core mentions
        // some, we drop them — their relaxation can be claimed via the threshold bump.
        val activeSofts = BooleanArray(n) { true }
        // Map selector var id → soft index, so we can project an assumption-core's
        // selector lits back to soft indices when computing the per-core min weight.
        val selectorToSoft = HashMap<Int, Int>(n).apply {
            for (i in 0 until n) put(selectors[i], i)
        }

        var lb = 0L
        while (lb <= totalWeight) {
            val assumptions = buildWeightedAssumptions(
                selectors = selectors,
                activeSofts = activeSofts,
                thresholdVar = if (lb < totalWeight) thresholds[lb.toInt()] else null,
            )
            when (val r = solver.satisfyUnderAssumptions(assumptions, params)) {
                is SatisfyResult.Sat -> return Result.Optimal(r.sample, lb)

                is SatisfyResult.GloballyUnsat -> return Result.Infeasible()

                is SatisfyResult.Unknown -> return Result.Unknown(r.reason, lb)

                is SatisfyResult.UnsatUnderAssumptions -> {
                    // Project the assumption core back to soft indices.
                    val coreSofts = projectCoreToSofts(r.core, selectorToSoft)
                    if (coreSofts.isEmpty()) {
                        // Core involves only the threshold bit — bump lb by 1 (we
                        // can't derive a wMin without soft-level info, so the smallest
                        // sound advance is +1).
                        lb += 1
                    } else {
                        // Standard RC2 step: wMin = min weight in the core's softs; the
                        // covered softs are freed (no longer assumed `r_i = false`),
                        // letting them be relaxed via the threshold bump.
                        val wMin = coreSofts.minOf { softs[it].weight }
                        lb += wMin
                        for (idx in coreSofts) activeSofts[idx] = false
                    }
                }
            }
        }
        return Result.Infeasible()
    }

    private fun buildWeightedAssumptions(
        selectors: IntArray,
        activeSofts: BooleanArray,
        thresholdVar: Int?,
    ): Assumptions {
        val bools = HashMap<Int, Boolean>()
        for (i in selectors.indices) if (activeSofts[i]) bools[selectors[i]] = false
        if (thresholdVar != null) bools[thresholdVar] = false
        return Assumptions(bools = bools)
    }

    private fun projectCoreToSofts(core: Assumptions, selectorToSoft: HashMap<Int, Int>): IntArray {
        val out = IntArrayList()
        for (i in core.boolKeys.indices) {
            val id = core.boolKeys[i]
            val softIdx = selectorToSoft[id] ?: continue
            // Only count selectors that the core pins to `false` — that matches our
            // "soft satisfied (selector = false)" assumption shape.
            if (!core.boolValues[i]) out.add(softIdx)
        }
        return out.toIntArray()
    }
}
