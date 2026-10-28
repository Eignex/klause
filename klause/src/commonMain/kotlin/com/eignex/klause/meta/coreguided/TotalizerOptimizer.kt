package com.eignex.klause.meta.coreguided

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.result.SatisfyResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.result.satisfyUnderAssumptions
import com.eignex.klause.util.MutableIntIntMap

/**
 * Threshold-chain core-guided MaxSAT optimiser for **unweighted** problems. Builds
 * one `Problem` up front with:
 *  - per soft, a fresh selector `r_i` and a relaxer clause `(origLit ∨ r_i)`;
 *  - a chain of `n` reified cardinality threshold bits `T_k ↔ (Σ r_i ≥ k)` for
 *    `k = 1..n`.
 *
 * The loop then iterates by **only changing the assumption layer**: at lower bound
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
 * The encoding counts selectors uniformly; [minimizeWeighted] and [CoreGuidedOptimizer]
 * handle weights.
 */
internal class TotalizerOptimizer(val baseProblem: BakedProblem) {

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

    fun minimize(softs: List<Soft>, params: BacktrackParams = BacktrackPresets.conflictDriven()): Result {
        if (softs.isEmpty()) {
            return Oll.solveHardOnly(
                baseProblem,
                params,
                onSat = { Result.Optimal(it, 0L) },
                onUnsat = { Result.Infeasible() },
                onUnknown = { Result.Unknown(it, 0L) },
            )
        }
        val n = softs.size
        var nextBoolId = baseProblem.numBoolVars
        val selectors = IntArray(n) { nextBoolId++ }
        // T_0 is trivially true and not materialised.
        val thresholds = IntArray(n) { nextBoolId++ }

        val factors = ArrayList<Factor>(baseProblem.factors.size + n + n)
        for (f in baseProblem.factors) factors.add(f)
        for (i in 0 until n) {
            factors.add(Oll.relaxerClause(softs[i].lit, selectors[i]))
        }
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
            settings = baseProblem.settings,
            semantics = baseProblem.semantics,
            numBoolVars = nextBoolId,
            numIntVars = baseProblem.numIntVars,
            intDomains = baseProblem.rootIntDomains(),
            factors = factors,
            modelBounds = baseProblem.intBounds,
        )
        val solver = BacktrackSolver(problem.bake())
        val costSofts = softs.map { Oll.Soft(it.lit) }

        var lb = 0L
        while (lb <= n) {
            // At `lb == n` there is no threshold to assume: the final solve is unconstrained on cost.
            val assumptions = if (lb < n) {
                Assumptions(bools = mapOf(thresholds[lb.toInt()] to false))
            } else {
                Assumptions.None
            }
            when (val r = solver.satisfyUnderAssumptions(assumptions, params)) {
                is SatisfyResult.Sat ->
                    return Result.Optimal(Oll.recoverOptimalSample(baseProblem, costSofts, r.sample, lb, params), lb)

                is SatisfyResult.GloballyUnsat -> return Result.Infeasible()

                is SatisfyResult.Unknown -> return Result.Unknown(r.reason, lb)

                is SatisfyResult.UnsatUnderAssumptions -> {
                    lb++
                }
            }
        }
        // Reached only when the assumption-free solve at lb == n still fails: the hard constraints are unsat.
        return Result.Infeasible()
    }

    /**
     * Weighted MaxSAT via a pseudo-Boolean threshold chain. Each threshold value `k` gets
     * a [ReifiedPseudoBoolean] reifying `aux_k ↔ (Σ wᵢ·rᵢ ≥ k)`; the loop assumes
     * `aux_{k} = false` (cost < k). Binary search over the bounded integer cost range
     * raises the lower bound after a refutation and lowers the upper bound after a witness.
     *
     * **Lazy thresholds.** Only the threshold bits the loop actually assumes are
     * materialised — one per round, so logarithmically many reifications rather
     * than the `O(totalWeight)` up-front bake the chain would otherwise need. The solver is
     * rebuilt when a fresh threshold is added; each round needs at most one new
     * reification. The rebuild cost mirrors the per-core rebuild in
     * [CoreGuidedOptimizer] and is far cheaper than baking thousands of unused PB factors.
     */
    fun minimizeWeighted(
        softs: List<WeightedSoft>,
        params: BacktrackParams = BacktrackPresets.conflictDriven(),
    ): Result {
        if (softs.isEmpty()) {
            return Oll.solveHardOnly(
                baseProblem,
                params,
                onSat = { Result.Optimal(it, 0L) },
                onUnsat = { Result.Infeasible() },
                onUnknown = { Result.Unknown(it, 0L) },
            )
        }
        val n = softs.size
        require(softs.all { it.weight <= Int.MAX_VALUE }) {
            "minimizeWeighted: a weight exceeds Int.MAX_VALUE — use CoreGuidedOptimizer for very large weights"
        }
        val totalWeight = softs.sumOf { it.weight }
        require(totalWeight <= Int.MAX_VALUE) {
            "minimizeWeighted: sum of weights exceeds Int.MAX_VALUE — use CoreGuidedOptimizer for very large weights"
        }
        var nextBoolId = baseProblem.numBoolVars
        val selectors = IntArray(n) { nextBoolId++ }

        val baseFactors = ArrayList<Factor>(baseProblem.factors.size + n)
        for (f in baseProblem.factors) baseFactors.add(f)
        for (i in 0 until n) baseFactors.add(Oll.relaxerClause(softs[i].lit, selectors[i]))

        val selectorLits = IntArray(n) { Lit.make(selectors[it], positive = true) }
        val weightsInt = IntArray(n) { softs[it].weight.toInt() }
        val costSofts = softs.map { Oll.Soft(it.lit, it.weight) }

        // Materialised thresholds share their selector vocabulary across bounded-cost queries.
        val thresholdVarForK = MutableIntIntMap()
        val thresholdFactors = ArrayList<Factor>()
        var solver: BacktrackSolver? = null

        var lb = 0L
        var upper = totalWeight
        var best: Sample? = null
        while (lb <= upper) {
            val bound = lb + (upper - lb) / 2L
            val thresholdVar: Int? = if (bound < totalWeight) {
                val k = (bound + 1).toInt()
                val existing = thresholdVarForK.getOrDefault(k, -1)
                if (existing >= 0) {
                    existing
                } else {
                    val aux = nextBoolId++
                    thresholdFactors.add(
                        ReifiedPseudoBoolean(
                            auxBoolVar = aux,
                            weights = LongArray(weightsInt.size) { weightsInt[it].toLong() },
                            literals = selectorLits,
                            op = PbOp.GE,
                            bound = k.toLong(),
                        ),
                    )
                    solver = null
                    thresholdVarForK.put(k, aux)
                    aux
                }
            } else {
                null
            }
            val activeSolver = solver ?: run {
                val factors = ArrayList<Factor>(baseFactors.size + thresholdFactors.size)
                factors.addAll(baseFactors)
                factors.addAll(thresholdFactors)
                BacktrackSolver(
                    Problem(
                        settings = baseProblem.settings,
                        semantics = baseProblem.semantics,
                        numBoolVars = nextBoolId,
                        numIntVars = baseProblem.numIntVars,
                        intDomains = baseProblem.rootIntDomains(),
                        factors = factors,
                        modelBounds = baseProblem.intBounds,
                    ).bake(),
                ).also { solver = it }
            }
            val assumptions = thresholdVar?.let { Assumptions(bools = mapOf(it to false)) } ?: Assumptions.None
            when (val r = activeSolver.satisfyUnderAssumptions(assumptions, params)) {
                is SatisfyResult.Sat -> {
                    best = r.sample
                    upper = Oll.softCost(r.sample, costSofts) - 1L
                }

                is SatisfyResult.GloballyUnsat -> return Result.Infeasible()

                is SatisfyResult.Unknown -> return Result.Unknown(r.reason, lb)

                is SatisfyResult.UnsatUnderAssumptions -> {
                    lb = bound + 1L
                }
            }
        }
        return best?.let { Result.Optimal(it, lb) } ?: Result.Infeasible()
    }
}
