package com.eignex.klause.meta.coreguided

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SatisfyResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.result.satisfyUnderAssumptions
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.MutableIntIntMap
import com.eignex.klause.util.addExact

/**
 * Core-guided MaxSAT optimiser: Fu-Malik (Z. Fu and S. Malik, "On Solving the Partial MAX-SAT Problem",
 * SAT 2006, https://doi.org/10.1007/11814948_25) with the weight splitting of WPM1 (C. Ansótegui,
 * M. L. Bonet and J. Levy, "Solving (Weighted) Partial MaxSAT through Satisfiability Testing", SAT 2009,
 * https://doi.org/10.1007/978-3-642-02777-2_39) and weight stratification (C. Ansótegui, M. L. Bonet,
 * J. Gabàs and J. Levy, "Improving SAT-Based Weighted MaxSAT Solvers", CP 2012,
 * https://doi.org/10.1007/978-3-642-33558-7_9). Iteratively calls [satisfyUnderAssumptions] on a sliding
 * assumption set; each unsat core triggers a "relax-one-of-these-softs" cardinality constraint and bumps
 * the cost lower bound by the core's min weight. Termination is `Sat` under the current assumption set,
 * at which point the lower bound equals the cost of the returned sample.
 *
 * Per-soft state across cores:
 *  - Each soft gets one fresh **initial selector** `r_i` baked into its relaxer clause
 *    `(origLit ∨ r_i)` before the loop. Initial assumption is `¬r_i`.
 *  - Each unsat core replaces its selectors, allocates fresh per-core blockers `{b_K}`,
 *    extends each cored soft's relaxer clause with its core's blocker, and adds the
 *    cardinality `ExactlyOne(b_K) = 1`. The extended clauses remain soft, so another
 *    core can admit further relaxation when the first one is insufficient.
 *  - Weight split: when a cored soft's weight strictly exceeds the core's `wMin`,
 *    the residual `(weight − wMin)` is shed as a fresh independent soft with its own
 *    initial selector; the residual is scheduled into the stratum queue so it gets
 *    revisited when the threshold drops.
 *
 * The Problem is rebuilt each iteration because the immutable-Problem contract prevents
 * clause replacement (a relaxer clause's blocker list grows from `(s ∨ r)` to
 * `(s ∨ r ∨ b₁)` to `(s ∨ r ∨ b₁ ∨ b₂)` as a soft gets re-cored). Bake cost grows
 * linearly with cores.
 */
internal class CoreGuidedOptimizer(val baseProblem: BakedProblem) {

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

    private class Working(
        val origLit: Int,
        var weight: Long,
        var initialSelector: Int,
        val extraBlockers: IntArrayList = IntArrayList(),
    ) {
        fun relaxerClause(): Clause {
            val arr = IntArray(2 + extraBlockers.size)
            arr[0] = origLit
            arr[1] = Lit.make(initialSelector, positive = true)
            for (j in 0 until extraBlockers.size) arr[j + 2] = Lit.make(extraBlockers[j], positive = true)
            return Clause(arr)
        }
    }

    internal fun resumable(objective: LinearObjective, params: BacktrackParams): ResumableSearch =
        CoreGuidedSearch(baseProblem, objective, params)

    fun minimize(
        softs: List<Soft>,
        params: BacktrackParams = BacktrackPresets.conflictDriven(),
        stratify: Boolean = true,
    ): Result {
        if (softs.isEmpty()) {
            return Oll.solveHardOnly(
                baseProblem,
                params,
                onSat = { Result.Optimal(it, 0L, 0) },
                onUnsat = { Result.Infeasible(0) },
                onUnknown = { Result.Unknown(it, 0, 0L) },
            )
        }

        // Weight splitting changes the working vocabulary; cost recovery uses the original softs.
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
            val solver = BacktrackSolver(problem.bake())
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
                    lb = addExact(lb, wMin)
                    val freshBlockers = IntArrayList()
                    val newSplits = ArrayList<Working>()
                    for (idx in coreSoftIdx) {
                        val w = workings[idx]
                        if (w.weight > wMin) {
                            // Shed (w − wMin) as an independent fresh soft so its cost stays
                            // accounted for, revisited once its stratum is reached.
                            val residual = Working(
                                origLit = w.origLit,
                                weight = w.weight - wMin,
                                initialSelector = nextBoolId++,
                                extraBlockers = IntArrayList().apply { addAll(w.extraBlockers.toIntArray()) },
                            )
                            newSplits.add(residual)
                            insertStratum(strata, threshold, w.weight - wMin)
                            w.weight = wMin
                        }
                        val b = nextBoolId++
                        w.extraBlockers.add(b)
                        freshBlockers.add(b)
                        w.initialSelector = nextBoolId++
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

    private fun collectActive(workings: List<Working>, threshold: Long): IntArray {
        val out = IntArrayList()
        for (i in workings.indices) {
            val w = workings[i]
            if (w.weight >= threshold) out.add(i)
        }
        return out.toIntArray()
    }

    private fun buildProblem(
        base: BakedProblem,
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
            settings = base.settings,
            semantics = base.semantics,
            numBoolVars = totalBoolVars,
            numIntVars = base.numIntVars,
            intDomains = base.rootIntDomains(),
            factors = factors,
            modelBounds = base.intBounds,
        )
    }

    private fun buildAssumptions(workings: List<Working>, activeIdx: IntArray): Assumptions {
        val bools = HashMap<Int, Boolean>(activeIdx.size)
        for (i in activeIdx) bools[workings[i].initialSelector] = false
        return Assumptions(bools = bools)
    }

    private fun projectCoreToSofts(workings: List<Working>, activeIdx: IntArray, core: Assumptions): IntArray {
        val selectorToSoft = MutableIntIntMap(activeIdx.size)
        for (i in activeIdx) selectorToSoft.put(workings[i].initialSelector, i)
        return Oll.projectCoreToSofts(core, selectorToSoft)
    }

    private fun finalSolve(
        base: BakedProblem,
        workings: List<Working>,
        exactly1s: List<IntArray>,
        totalBoolVars: Int,
        params: BacktrackParams,
        costSofts: List<Oll.Soft>,
        lb: Long,
        cores: Int,
    ): Result {
        val problem = buildProblem(base, workings, exactly1s, totalBoolVars)
        return when (val r = BacktrackSolver(problem.bake()).solve(params)) {
            is SolveResult.Sat -> optimal(r.assignment, costSofts, lb, cores, params)
            is SolveResult.Unsat -> Result.Infeasible(cores)
            is SolveResult.Unknown -> Result.Unknown(r.reason, cores, lb)
        }
    }

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
