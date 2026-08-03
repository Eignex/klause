package com.eignex.klause.meta.coreguided

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.result.SatisfyResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.result.satisfyUnderAssumptions
import com.eignex.klause.util.MutableIntIntMap

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
        // Threshold aux bools T_1..T_n: T_k ↔ (Σ selectors ≥ k). T_0 is trivially true
        // (sum ≥ 0 always); we don't materialise it.
        val thresholds = IntArray(n) { nextBoolId++ }

        val factors = ArrayList<Factor>(baseProblem.factors.size + n + n)
        for (f in baseProblem.factors) factors.add(f)
        // Relaxer clause per soft: (origLit ∨ selector). Setting selector=true relaxes
        // the soft (allowing origLit false); selector=false forces origLit true.
        for (i in 0 until n) {
            factors.add(Oll.relaxerClause(softs[i].lit, selectors[i]))
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
        val solver = BacktrackSolver(problem.bake())
        val costSofts = softs.map { Oll.Soft(it.lit) }

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
                is SatisfyResult.Sat ->
                    return Result.Optimal(Oll.recoverOptimalSample(baseProblem, costSofts, r.sample, lb, params), lb)

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
     * Weighted MaxSAT via a pseudo-Boolean threshold chain. Each threshold value `k` gets
     * a [ReifiedPseudoBoolean] reifying `aux_k ↔ (Σ wᵢ·rᵢ ≥ k)`; the OLL loop assumes
     * `aux_{lb+1} = false` (cost ≤ lb) and bumps `lb` by the core's min weight each unsat.
     * Termination: SAT under the current threshold-bit assumption.
     *
     * **Lazy thresholds (#91).** Only the `aux_{lb+1}` the loop actually assumes are
     * materialised — one per OLL round as `lb` rises, so `O(#cores)` reifications rather
     * than the `O(totalWeight)` up-front bake the chain would otherwise need. The solver is
     * rebuilt when a fresh threshold is added (lb advances monotonically, so each round
     * needs at most one new reification); the rebuild cost mirrors the per-core rebuild in
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
        val totalWeight = softs.sumOf { it.weight }
        require(totalWeight <= Int.MAX_VALUE) {
            "minimizeWeighted: sum of weights exceeds Int.MAX_VALUE — use CoreGuidedOptimizer for very large weights"
        }
        var nextBoolId = baseProblem.numBoolVars
        val selectors = IntArray(n) { nextBoolId++ }

        // Fixed factors: hard constraints + one relaxer clause per soft. Threshold
        // reifications are appended lazily below as `lb` rises (#91).
        val baseFactors = ArrayList<Factor>(baseProblem.factors.size + n)
        for (f in baseProblem.factors) baseFactors.add(f)
        for (i in 0 until n) baseFactors.add(Oll.relaxerClause(softs[i].lit, selectors[i]))

        val selectorLits = IntArray(n) { Lit.make(selectors[it], positive = true) }
        val weightsInt = IntArray(n) { softs[it].weight.toInt() }
        val selectorToSoft = MutableIntIntMap(n).apply {
            for (i in 0 until n) put(selectors[i], i)
        }
        val costSofts = softs.map { Oll.Soft(it.lit, it.weight) }

        // Materialised threshold reifications keyed by k (= "Σ wᵢrᵢ ≥ k"); `solver` is
        // rebuilt (set null) whenever a new one is appended.
        val thresholdVarForK = MutableIntIntMap()
        val thresholdFactors = ArrayList<Factor>()
        var solver: BacktrackSolver? = null

        // Soft selectors we still assume "= false" (don't relax). When a core mentions
        // some, we drop them — their relaxation can be claimed via the threshold bump.
        val activeSofts = BooleanArray(n) { true }

        var lb = 0L
        while (lb <= totalWeight) {
            val thresholdVar: Int? = if (lb < totalWeight) {
                // Need aux for k = lb+1 ("sum ≥ lb+1"); assuming it false bounds cost ≤ lb.
                val k = (lb + 1).toInt()
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
                    solver = null // a fresh factor + bool var were added — force a rebuild
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
                        numBoolVars = nextBoolId,
                        numIntVars = baseProblem.numIntVars,
                        intDomains = baseProblem.intDomains,
                        factors = factors,
                    ).bake(),
                ).also { solver = it }
            }
            val assumptions = buildWeightedAssumptions(selectors, activeSofts, thresholdVar)
            when (val r = activeSolver.satisfyUnderAssumptions(assumptions, params)) {
                is SatisfyResult.Sat ->
                    return Result.Optimal(Oll.recoverOptimalSample(baseProblem, costSofts, r.sample, lb, params), lb)

                is SatisfyResult.GloballyUnsat -> return Result.Infeasible()

                is SatisfyResult.Unknown -> return Result.Unknown(r.reason, lb)

                is SatisfyResult.UnsatUnderAssumptions -> {
                    // Project the assumption core back to soft indices.
                    val coreSofts = Oll.projectCoreToSofts(r.core, selectorToSoft)
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
}
