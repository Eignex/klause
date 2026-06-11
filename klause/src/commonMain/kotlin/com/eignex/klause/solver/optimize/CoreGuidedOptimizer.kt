package com.eignex.klause.solver.optimize

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.result.SatisfyResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.result.satisfyUnderAssumptions
import com.eignex.klause.util.IntArrayList

/**
 * Core-guided MaxSAT optimiser — Fu-Malik MSU3 (unweighted) extended to RC2 (weighted +
 * stratified). Iteratively calls [satisfyUnderAssumptions] on a sliding assumption set;
 * each unsat core triggers a "relax-one-of-these-softs" cardinality constraint and bumps
 * the cost lower bound by the core's min weight. Termination is `Sat` under the current
 * assumption set, at which point the lower bound equals the cost of the returned sample.
 *
 * Per-soft state across cores:
 *  - Each soft gets one fresh **initial selector** `r_i` baked into its relaxer clause
 *    `(origLit ∨ r_i)` before the loop. Initial assumption is `¬r_i`.
 *  - Each unsat core *removes* the assumptions `{¬r_i}` for its members from the active
 *    set (the softs become "spent" — their selectors are now free, constrained only by
 *    the per-core ExactlyOne below), allocates fresh per-core blockers `{b_K}`, extends
 *    each cored soft's relaxer clause with its core's blocker, and adds the cardinality
 *    `ExactlyOne(b_K) = 1` — forcing exactly one relaxation per core.
 *  - RC2 weight-split: when a cored soft's weight strictly exceeds the core's `wMin`,
 *    the residual `(weight − wMin)` is shed as a fresh independent soft with its own
 *    initial selector; the residual is scheduled into the stratum queue so it gets
 *    revisited when the threshold drops.
 *
 * The Problem is rebuilt each iteration because the immutable-Problem contract prevents
 * clause replacement (a relaxer clause's blocker list grows from `(s ∨ r)` to
 * `(s ∨ r ∨ b₁)` to `(s ∨ r ∨ b₁ ∨ b₂)` as a soft gets re-cored). Bake cost grows
 * linearly with cores; a session-level "add factor at runtime" API is the natural
 * follow-up if this becomes the bottleneck.
 */
internal class CoreGuidedOptimizer(val baseProblem: Problem) {

    /**
     * One soft constraint: literal that *should* be true, with positive integer weight
     * paid if it ends up false. Uses [Lit] encoding (`(var shl 1) or polarityBit`);
     * `Lit.make(varId, positive = true)` penalises a Boolean being false,
     * `Lit.make(varId, positive = false)` penalises it being true.
     */
    data class Soft(val lit: Int, val weight: Long = 1L) {
        init {
            require(weight > 0) { "Soft weight must be positive, was $weight" }
        }
    }

    sealed interface Result {
        val sample: Sample?
        val lowerBound: Long
        val coresFound: Int
        data class Optimal(override val sample: Sample, override val lowerBound: Long, override val coresFound: Int) :
            Result
        data class Infeasible(override val coresFound: Int) : Result {
            override val sample: Sample? = null
            override val lowerBound: Long = 0L
        }
        data class Unknown(
            val reason: TerminationReason,
            override val coresFound: Int,
            override val lowerBound: Long,
        ) : Result {
            override val sample: Sample? = null
        }
    }

    /** Live per-soft state. `initialSelector` is the freshly-allocated `r_i` baked into
     *  the relaxer clause; `extraBlockers` holds blockers added by each subsequent core
     *  that touched this soft. The relaxer clause is `(origLit ∨ r_i ∨ b₁ ∨ … ∨ bₖ)`,
     *  rebuilt each iteration. `spent` flips to true once any core has consumed this
     *  soft — its `¬r_i` assumption is then dropped from the active set. */
    private class Working(
        val origLit: Int,
        var weight: Long,
        val initialSelector: Int,
        val extraBlockers: IntArrayList = IntArrayList(),
        var spent: Boolean = false,
    ) {
        fun relaxerClause(): Clause {
            // Pre-first-core: `(origLit ∨ r_i)` with `¬r_i` assumed acts as the
            // "soft must hold" constraint. Once spent, drop r_i: the per-core blockers
            // are now the only relaxation channel, gated by the ExactlyOne constraints
            // that accumulate across cores. Keeping r_i in the clause after the soft
            // is spent would leave it as a free relaxation switch (it has no other
            // constraint), letting the SAT solver satisfy the relaxer without honouring
            // any soft — the cost lower bound stays correct but the recovered sample
            // doesn't reflect the optimum (cost-bound test relied on this, sample test
            // didn't).
            val size = (if (spent) 1 else 2) + extraBlockers.size
            val arr = IntArray(size)
            arr[0] = origLit
            var i = 1
            if (!spent) {
                arr[i++] = Lit.make(initialSelector, positive = true)
            }
            for (j in 0 until extraBlockers.size) arr[i++] = Lit.make(extraBlockers[j], positive = true)
            return Clause(arr)
        }
    }

    fun minimize(softs: List<Soft>, params: BacktrackParams = BacktrackParams(), stratify: Boolean = true): Result {
        if (softs.isEmpty()) {
            return Oll.solveHardOnly(
                baseProblem,
                params,
                onSat = { Result.Optimal(it, 0L, 0) },
                onUnsat = { Result.Infeasible(0) },
                onUnknown = { Result.Unknown(it, 0, 0L) },
            )
        }

        // Original softs in the shared cost vocabulary, used to verify/recover the final
        // sample's true cost (#80). Weights are split across cores below, so the genuine
        // per-soft cost can only be measured against these originals, not the workings.
        val costSofts = softs.map { Oll.Soft(it.lit, it.weight) }
        var nextBoolId = baseProblem.numBoolVars
        val workings = ArrayList<Working>().apply {
            for (s in softs) add(Working(s.lit, s.weight, initialSelector = nextBoolId++))
        }
        val exactly1Lits = ArrayList<IntArray>()
        var lb = 0L
        var cores = 0

        val strata: ArrayDeque<Long> = if (stratify) {
            ArrayDeque(softs.map { it.weight }.distinct().sortedDescending())
        } else {
            ArrayDeque<Long>().apply { add(1L) }
        }
        var threshold: Long = strata.removeFirst()

        while (true) {
            val activeIdx = collectActive(workings, threshold)
            if (activeIdx.isEmpty()) {
                if (strata.isNotEmpty()) {
                    threshold = strata.removeFirst()
                    continue
                }
                return finalSolve(baseProblem, workings, exactly1Lits, nextBoolId, params, costSofts, lb, cores)
            }
            val problem = buildProblem(baseProblem, workings, exactly1Lits, nextBoolId)
            val assumptions = buildAssumptions(workings, activeIdx)
            val solver = BacktrackSolver(problem)
            when (val r = solver.satisfyUnderAssumptions(assumptions, params)) {
                is SatisfyResult.Sat -> {
                    if (strata.isEmpty()) return optimal(r.sample, costSofts, lb, cores, params)
                    threshold = strata.removeFirst()
                }

                is SatisfyResult.GloballyUnsat -> return Result.Infeasible(cores)

                is SatisfyResult.Unknown -> return Result.Unknown(r.reason, cores, lb)

                is SatisfyResult.UnsatUnderAssumptions -> {
                    cores++
                    val coreSoftIdx = projectCoreToSofts(workings, activeIdx, r.core)
                    if (coreSoftIdx.isEmpty()) return Result.Infeasible(cores)
                    val wMin = coreSoftIdx.minOf { workings[it].weight }
                    lb += wMin
                    val freshBlockers = IntArrayList()
                    val newSplits = ArrayList<Working>()
                    for (idx in coreSoftIdx) {
                        val w = workings[idx]
                        if (w.weight > wMin) {
                            // RC2 split: shed (w − wMin) as an independent fresh soft so
                            // its cost stays accounted for in later rounds. Schedule
                            // the residual weight into the stratum queue so we revisit
                            // it once the current stratum's heavier cores settle.
                            val residual = Working(
                                origLit = w.origLit,
                                weight = w.weight - wMin,
                                initialSelector = nextBoolId++,
                            )
                            newSplits.add(residual)
                            insertStratum(strata, threshold, w.weight - wMin)
                            w.weight = wMin
                        }
                        val b = nextBoolId++
                        w.extraBlockers.add(b)
                        freshBlockers.add(b)
                        // Mark this soft "spent": its initialSelector ¬r_i assumption
                        // is dropped from the active set; the soft can be relaxed via
                        // its per-core blocker now subject to the ExactlyOne.
                        w.spent = true
                    }
                    workings.addAll(newSplits)
                    val cardLits = IntArray(freshBlockers.size) {
                        Lit.make(freshBlockers[it], positive = true)
                    }
                    exactly1Lits.add(cardLits)
                }
            }
        }
    }

    private fun insertStratum(strata: ArrayDeque<Long>, currentThreshold: Long, newWeight: Long) {
        if (newWeight <= 0 || newWeight >= currentThreshold) return
        for (i in strata.indices) {
            if (strata[i] == newWeight) return
            if (strata[i] < newWeight) {
                val copy = strata.toMutableList()
                copy.add(i, newWeight)
                strata.clear()
                strata.addAll(copy)
                return
            }
        }
        strata.addLast(newWeight)
    }

    /** Softs that are active at the current weight stratum AND haven't already been
     *  consumed by a core. Spent softs have empty assumptions — their relaxer is now
     *  the per-core ExactlyOne. */
    private fun collectActive(workings: List<Working>, threshold: Long): IntArray {
        val out = IntArrayList()
        for (i in workings.indices) {
            val w = workings[i]
            if (w.spent) continue
            if (w.weight >= threshold) out.add(i)
        }
        return out.toIntArray()
    }

    private fun buildProblem(
        base: Problem,
        workings: List<Working>,
        exactly1s: List<IntArray>,
        totalBoolVars: Int,
    ): Problem {
        val factors = ArrayList<Factor>(base.factors.size + workings.size + exactly1s.size)
        for (f in base.factors) factors.add(f)
        for (w in workings) factors.add(w.relaxerClause())
        for (lits in exactly1s) {
            if (lits.size >= 2) {
                factors.add(Cardinality(lits, min = 1, max = 1))
            } else if (lits.size == 1) {
                // Singleton core: ExactlyOne reduces to "this lit = true", i.e. the
                // relaxation must fire.
                factors.add(Clause(lits))
            }
        }
        return Problem(
            numBoolVars = totalBoolVars,
            numIntVars = base.numIntVars,
            intDomains = base.intDomains,
            factors = factors,
        )
    }

    /** Assumption is `r_i = false` (don't relax) for each not-yet-spent soft above the
     *  current stratum threshold. Spent softs are absent — their fate is controlled by
     *  the ExactlyOne constraints accumulated across prior cores. */
    private fun buildAssumptions(workings: List<Working>, activeIdx: IntArray): Assumptions {
        val bools = HashMap<Int, Boolean>(activeIdx.size)
        for (i in activeIdx) bools[workings[i].initialSelector] = false
        return Assumptions(bools = bools)
    }

    private fun projectCoreToSofts(workings: List<Working>, activeIdx: IntArray, core: Assumptions): IntArray {
        // Map each active soft's initial selector back to its working index, then defer to
        // the shared core projection so both drivers agree on what a core "covers".
        val selectorToSoft = HashMap<Int, Int>(activeIdx.size)
        for (i in activeIdx) selectorToSoft[workings[i].initialSelector] = i
        return Oll.projectCoreToSofts(core, selectorToSoft)
    }

    private fun finalSolve(
        base: Problem,
        workings: List<Working>,
        exactly1s: List<IntArray>,
        totalBoolVars: Int,
        params: BacktrackParams,
        costSofts: List<Oll.Soft>,
        lb: Long,
        cores: Int,
    ): Result {
        val problem = buildProblem(base, workings, exactly1s, totalBoolVars)
        return when (val r = BacktrackSolver(problem).solve(params)) {
            is SolveResult.Sat -> optimal(r.assignment, costSofts, lb, cores, params)
            is SolveResult.Unsat -> Result.Infeasible(cores)
            is SolveResult.Unknown -> Result.Unknown(r.reason, cores, lb)
        }
    }

    /** Wrap a terminal SAT witness as [Result.Optimal] after recovering a sample whose
     *  *true* soft cost equals [lb] (#80): a spent soft can be relaxed for free by another
     *  core's blocker, leaving the raw witness over-relaxed relative to the bound. */
    private fun optimal(
        sample: Sample,
        costSofts: List<Oll.Soft>,
        lb: Long,
        cores: Int,
        params: BacktrackParams,
    ): Result {
        val recovered = Oll.recoverOptimalSample(baseProblem, costSofts, sample, lb, params)
        return Result.Optimal(recovered, lb, cores)
    }
}
