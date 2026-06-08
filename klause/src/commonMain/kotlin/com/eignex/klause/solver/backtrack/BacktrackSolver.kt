package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Objective
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SearchEvent
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.SolveStatsSink
import com.eignex.klause.solver.Solver
import com.eignex.klause.solver.TerminationReason
import com.eignex.klause.solver.UnsatCore
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.lp.AllDifferentSeparator
import com.eignex.klause.solver.lp.AssignmentObjectiveCut
import com.eignex.klause.solver.lp.Basis
import com.eignex.klause.solver.lp.CpToLpRelaxation
import com.eignex.klause.solver.lp.Cut
import com.eignex.klause.solver.lp.CutContext
import com.eignex.klause.solver.lp.CutSeparator
import com.eignex.klause.solver.lp.DualSimplex
import com.eignex.klause.solver.lp.FloatSimplex
import com.eignex.klause.solver.lp.LagrangianBound
import com.eignex.klause.solver.lp.LpOverflowException
import com.eignex.klause.solver.lp.LpRelaxation
import com.eignex.klause.solver.lp.LpSolution
import com.eignex.klause.solver.lp.LpStatus
import com.eignex.klause.solver.lp.VarStatus
import com.eignex.klause.solver.lp.addExact
import com.eignex.klause.solver.lp.mulExact
import com.eignex.klause.solver.lp.subExact
import com.eignex.klause.solver.projectSeedConflictToAssumptions
import com.eignex.klause.solver.propagation.ConflictAnalyzer
import com.eignex.klause.solver.propagation.ConflictAnalyzer.AnalysisResult.Learned
import com.eignex.klause.solver.propagation.PropagationResult
import com.eignex.klause.solver.propagation.PropagationSession
import com.eignex.klause.solver.propagation.TIER_CORE
import com.eignex.klause.solver.propagation.TIER_LOCAL
import com.eignex.klause.solver.propagation.TIER_MID
import com.eignex.klause.solver.propagation.TIER_UNSET
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.MutableLongIntMap
import com.eignex.kumulant.math.splitmix64
import kotlin.math.ceil
import kotlin.random.Random

/**
 * Complete depth-first search over a [Problem]'s assignment space, driven by propagation
 * via [PropagationSession]. Variable selection and value selection are plug-in heuristics
 * via [BacktrackParams.variableHeuristic] / [BacktrackParams.valueHeuristic] — same split
 * MiniZinc uses for `solve :: int_search(vars, var_strategy, value_strategy, complete)`.
 *
 *  - [solve] — first witness as [SolveResult.Sat], [SolveResult.Unsat] when the tree is
 *    fully explored, [SolveResult.Unknown] on [BacktrackParams.maxDecisions] exhaustion.
 *  - [samples] — yields every SAT leaf reached during traversal (each one distinct).
 *  - [enumerate] — same as [samples] plus the rolling-window Hamming-distance filter.
 *  - [minimize] — enumerates feasible assignments and returns the lowest-scoring one.
 *    Complete but exponential.
 *
 *  Complete enumeration on `n` unpinned bools walks up to `2^n` branches. Use
 *  [BacktrackParams.maxDecisions] to cap exploration on large problems.
 */
class BacktrackSolver(override val problem: Problem) :
    Solver<BacktrackParams>,
    Optimizer<BacktrackParams> {

    override fun solve(params: BacktrackParams): SolveResult {
        val sink = SolveStatsSink(backend = "backtrack")
        sink.start()
        for (outcome in driveSearch(params, sink = sink)) {
            sink.stop()
            val stats = sink.snapshot()
            return when (outcome) {
                is SearchOutcome.Found -> SolveResult.Sat(outcome.sample, stats)

                is SearchOutcome.Exhausted -> SolveResult.Unsat(
                    core = outcome.core,
                    stats = stats,
                    assumptionCore = projectTouchedToAssumptions(params.assumptions, outcome.touchedAssumptionLevels),
                )

                SearchOutcome.BudgetCapped -> {
                    sink.timedOut = true
                    SolveResult.Unknown(TerminationReason.BudgetExhausted, sink.snapshot())
                }
            }
        }
        sink.stop()
        return SolveResult.Unsat(stats = sink.snapshot())
    }

    /**
     * Independent random samples ("with replacement", per the [com.eignex.klause.solver.Solver.samples]
     * contract). Each yield kicks off a fresh DFS from root on a new [PropagationSession]
     * with a per-call RNG seed; no engine state carries between yields, so subsequent
     * yields are statistically independent given the random heuristic defaults.
     *
     * **Reproducibility.** With a fixed [BacktrackParams.randomSeed] the per-call seeds
     * are derived by a deterministic LCG advance, so the same parent seed produces the
     * same sequence of samples across runs. This is reproducibility, not correlation —
     * the per-call seeds are independent random draws as far as the search is concerned.
     *
     * **Duplicates.** The sequence does **not** filter duplicates. For a problem with N
     * feasible models, the same model may be yielded multiple times; the distribution
     * across yields is determined by the heuristics. For distinct samples use [enumerate]
     * (complete + DFS-ordered) or `samples(p).distinct().take(n)` (random + distinct,
     * uses memory linear in yielded count).
     *
     * **Termination.** The sequence is **infinite for any feasible problem** — callers
     * must bound it with `.take(n)` or `.takeWhile(...)`. It terminates early only when:
     *  - a run returns [SolveResult.Unsat] — the entire search tree exhausts without a
     *    SAT (the problem is infeasible); or
     *  - a run returns [SolveResult.Unknown] — [BacktrackParams.maxDecisions] elapsed
     *    before any SAT was found on that run.
     */
    override fun samples(params: BacktrackParams): Sequence<Sample> = sequence {
        var seed = params.randomSeed ?: Random.Default.nextLong()
        while (true) {
            val perCall = params.copy(randomSeed = seed)
            when (val r = solveOnce(perCall)) {
                is SolveResult.Sat -> yield(r.assignment)
                is SolveResult.Unsat -> return@sequence
                is SolveResult.Unknown -> return@sequence
            }
            // LCG advance for reproducibility: same parent seed → same per-call seed
            // sequence → same sample sequence. The per-call seeds drive the heuristics'
            // random choices; from the search's perspective they're independent draws.
            seed = seed * 6364136223846793005L + 1442695040888963407L
        }
    }

    private fun solveOnce(params: BacktrackParams): SolveResult {
        for (outcome in driveSearch(params)) {
            return when (outcome) {
                is SearchOutcome.Found -> SolveResult.Sat(outcome.sample)
                is SearchOutcome.Exhausted -> SolveResult.Unsat(outcome.core)
                SearchOutcome.BudgetCapped -> SolveResult.Unknown(TerminationReason.BudgetExhausted)
            }
        }
        return SolveResult.Unsat()
    }

    /**
     * Distinct SAT assignments via single-DFS traversal of the search tree. Complete:
     * given enough budget, every distinct feasible assignment is yielded exactly once.
     * The optional rolling Hamming-distance window adds extra spacing between yields.
     *
     * For *diverse* distinct samples — useful when a small test/verification budget
     * shouldn't be spent on one subtree — call [samples] (which uses random restarts
     * with-replacement) and de-duplicate client-side, e.g. `samples(p).distinct().take(n)`.
     */
    override fun enumerate(params: BacktrackParams): Sequence<Sample> = sequence {
        val window = ArrayDeque<Sample>()
        for (outcome in driveSearch(params)) {
            when (outcome) {
                is SearchOutcome.Found -> {
                    val snap = outcome.sample
                    if (farEnough(snap, window, params.minHammingDistance)) {
                        yield(snap)
                        if (params.recentWindow > 0) {
                            if (window.size >= params.recentWindow) window.removeFirst()
                            window.addLast(snap)
                        }
                    }
                }

                is SearchOutcome.Exhausted, SearchOutcome.BudgetCapped -> return@sequence
            }
        }
    }

    /**
     * Branch-and-bound minimisation. Walks the DFS yielding feasible leaves; each leaf
     * improves the incumbent `bestObj` and tightens a partial-assignment lower bound
     * that the search engine consults on every successful pin to prune the subtree when
     * it provably can't beat the incumbent. The pruning predicate closes over the
     * mutable `bestObj`, so the tightening propagates lazily without explicit
     * communication into the engine.
     *
     * For [LinearObjective] the bound is `Σ_b lb_b(bool) + Σ_i lb_i(int) + constant`,
     * where:
     *  - `lb_b = boolWeights[b]` if `b` is pinned-true, `0` if pinned-false,
     *    `min(0, boolWeights[b])` if unpinned;
     *  - `lb_i = coeff[i] · (coeff ≥ 0 ? dom.min : dom.max)`.
     *
     * Sound: every completion can only *raise* the contribution of unpinned vars from
     * the minimum, so an LB that already equals or exceeds the incumbent guarantees no
     * descendant leaf beats it. For arbitrary [Objective] subtypes the predicate
     * degrades to "never prune," so correctness is preserved at the cost of falling
     * back to full enumeration.
     */
    override fun minimize(objective: Objective, params: BacktrackParams): MinimizeResult =
        improvements(objective, params).last()

    /**
     * Anytime variant of [minimize]: yields one [MinimizeResult.BestFound] per new
     * incumbent discovered, followed by exactly one terminal verdict
     * ([MinimizeResult.Optimal] / [MinimizeResult.Infeasible] / final
     * [MinimizeResult.BestFound] / [MinimizeResult.Unknown]). Same B&B engine as
     * [minimize]; just exposes the search's intermediate bests as they land instead of
     * collapsing them into a single return value.
     */
    override fun improvements(objective: Objective, params: BacktrackParams): Sequence<MinimizeResult> = sequence {
        var best: Sample? = null
        var bestObj = Double.POSITIVE_INFINITY
        // Objective-bound propagation for single-variable objectives (every FlatZinc goal):
        // track the objective variable's value in the current incumbent so the engine can
        // assert `objVar ≤/≥ best ∓ 1` at the root and let the defining constraint propagate.
        val singleObj = (objective as? LinearObjective)?.singleIntObjective()
        var objVarBest: Int? = null
        val sink = SolveStatsSink(backend = "backtrack")
        // LP-relaxation bounding (#20): build the relaxer once; it reads live bounds per node.
        // Only a LinearObjective yields a sound LP objective, so the relaxer is null otherwise.
        val lpRelaxer = if (params.lpBounding && objective is LinearObjective) {
            CpToLpRelaxation(problem, objective, generateCuts = params.lpCuts)
        } else {
            null
        }
        val lpSeparators: List<CutSeparator> = if (params.lpCuts) {
            buildList {
                add(AllDifferentSeparator())
                // Objective-weighted AllDifferent (assignment) cut — the Lagrangian-augmented LP path.
                (objective as? LinearObjective)?.let { obj ->
                    val coef = LongArray(problem.numIntVars) { obj.intCoefficients.getOrElse(it) { 0L } }
                    add(AssignmentObjectiveCut(coef))
                }
            }
        } else {
            emptyList()
        }
        // Lagrangian bound (#23): built once; multipliers persist across nodes (rolling warm start).
        val lagBound = if (params.lagrangian && objective is LinearObjective) {
            LagrangianBound(problem, objective).takeIf { it.applicable }
        } else {
            null
        }
        var lagMultipliers = LongArray(lagBound?.multiplierCount ?: 0)
        var lpCheckCounter = 0
        // Warm-start cache: the most recent LP basis seen at each decision depth. A child at depth D
        // re-optimises from depth D-1's basis (dual-feasible after the branch's bound tightening).
        val lpBasisByDepth = ArrayList<Basis?>()
        val pruneIf: ((PropagationSession) -> Boolean)? = when (objective) {
            is LinearObjective -> { session ->
                // Effective bound = min(local incumbent, external supplier). External bound
                // sharing lets a parallel CP portfolio tighten every worker's pruning past
                // their local incumbent as soon as any worker finds a better one.
                val externalBound = params.objectiveBoundSupplier?.invoke() ?: Double.POSITIVE_INFINITY
                val effectiveBound = if (externalBound < bestObj) externalBound else bestObj
                when {
                    // Cheap separable bound first — a fast filter that often prunes without an LP solve.
                    linearLowerBound(objective, session) >= effectiveBound -> true

                    // Lagrangian bound (cheaper than the LP); updates persisted multipliers as a side
                    // effect and prunes when its bound reaches the incumbent or the subproblem is infeasible.
                    lagBound != null && run {
                        val res = lagBound.computeBound(
                            session,
                            effectiveBound,
                            lagMultipliers,
                            params.lagrangianIterations,
                        )
                        if (res != null) {
                            lagMultipliers = res.multipliers
                            if (res.prune) sink.observeLagrangianPrune()
                            res.prune
                        } else {
                            false
                        }
                    } -> true

                    // Then the LP relaxation, gated by the depth/frequency policy (the solve is the
                    // expensive part of a node, so it does not run at every node).
                    lpRelaxer != null &&
                        session.decisionLevel <= params.lpBoundMaxDepth &&
                        ++lpCheckCounter % params.lpBoundEvery == 0 -> {
                        val depth = session.decisionLevel
                        val warm = if (params.lpWarmStart && depth - 1 in lpBasisByDepth.indices) {
                            lpBasisByDepth[depth - 1]
                        } else {
                            null
                        }
                        val outcome = lpBoundAndFix(
                            lpRelaxer,
                            session,
                            effectiveBound,
                            sink,
                            warm,
                            params,
                            lpSeparators,
                        )
                        if (outcome.basis != null) {
                            while (lpBasisByDepth.size <= depth) lpBasisByDepth.add(null)
                            lpBasisByDepth[depth] = outcome.basis
                        }
                        outcome.prune
                    }

                    else -> false
                }
            }

            else -> null
        }
        // Restarts stay off unless the caller asks: a restart pops to root and re-traverses
        // the bound-pruned tree (the objective bound is a predicate, not a learned clause),
        // which can keep an optimality proof from terminating in budget. With an explicit
        // lubyRestartBase the caller is choosing anytime diversification over proof speed —
        // each incumbent leaves a permanent blocking nogood, so restarts no longer revisit
        // solved leaves.
        sink.start()
        for (outcome in driveSearch(
            params.copy(minHammingDistance = 0, recentWindow = 0),
            pruneIf = pruneIf,
            sink = sink,
            objectiveVar = singleObj?.varId ?: -1,
            objectiveAscending = singleObj?.ascending ?: true,
            objectiveBest = { objVarBest },
        )) {
            when (outcome) {
                is SearchOutcome.Found -> {
                    val o = objective.evaluate(outcome.sample)
                    if (o < bestObj) {
                        bestObj = o
                        best = outcome.sample
                        if (singleObj != null) objVarBest = outcome.sample.ints[singleObj.varId]
                        params.onEvent?.invoke(SearchEvent.Incumbent(o))
                        // Yield each new incumbent eagerly — consumers can react to it
                        // before search continues toward the bound. The reason here is
                        // a hint ("more might come"); the terminal yield carries the
                        // real verdict.
                        yield(MinimizeResult.BestFound(outcome.sample, o, TerminationReason.BudgetExhausted))
                    }
                }

                is SearchOutcome.Exhausted -> {
                    // When an external bound supplier is active, the engine has pruned
                    // subtrees against bounds that may be tighter than the local incumbent.
                    // Its terminal verdict can therefore no longer claim local-Optimal nor
                    // global-Infeasible soundly — the unpruned space proves a property
                    // relative to the shared bound, not absolutely. Downgrade to BestFound
                    // (when a local incumbent exists) or Unknown (when none does); the
                    // calling portfolio can upgrade to Optimal/Infeasible after combining
                    // every worker's verdict.
                    val externalShared = params.objectiveBoundSupplier != null
                    sink.stop()
                    val stats = sink.snapshot()
                    yield(
                        when {
                            externalShared && best != null ->
                                MinimizeResult.BestFound(best, bestObj, TerminationReason.SearchExhausted, stats)

                            externalShared ->
                                MinimizeResult.Unknown(TerminationReason.SearchExhausted, stats)

                            best != null -> MinimizeResult.Optimal(best, bestObj, stats)

                            else -> MinimizeResult.Infeasible(outcome.core, stats)
                        },
                    )
                    return@sequence
                }

                SearchOutcome.BudgetCapped -> {
                    sink.stop()
                    sink.timedOut = true
                    val stats = sink.snapshot()
                    yield(
                        if (best != null) {
                            MinimizeResult.BestFound(best, bestObj, TerminationReason.BudgetExhausted, stats)
                        } else {
                            MinimizeResult.Unknown(TerminationReason.BudgetExhausted, stats)
                        },
                    )
                    return@sequence
                }
            }
        }
        // Sequence drained without a terminal outcome — treat as exhausted.
        sink.stop()
        yield(
            if (best != null) {
                MinimizeResult.Optimal(best, bestObj, sink.snapshot())
            } else {
                MinimizeResult.Infeasible(stats = sink.snapshot())
            },
        )
    }

    /**
     * Sound lower bound on a [LinearObjective] given the current partial assignment in
     * [session]. Pinned vars contribute their exact value; unpinned bool vars take the
     * weight (or 0) that makes their contribution smallest; unpinned int vars take the
     * domain endpoint matching the coefficient's sign.
     */
    private fun linearLowerBound(obj: LinearObjective, session: PropagationSession): Long {
        var total = obj.constant
        val sp = session.problem
        val nb = minOf(sp.numBoolVars, obj.boolWeights.size)
        for (b in 0 until nb) {
            val w = obj.boolWeights[b]
            val v = session.boolValue(b)
            total += when {
                v == true -> w
                v == false -> 0L
                w < 0L -> w
                else -> 0L
            }
        }
        val ni = minOf(sp.numIntVars, obj.intCoefficients.size)
        for (i in 0 until ni) {
            val c = obj.intCoefficients[i]
            if (c == 0L) continue
            val d = session.intDomain(i)
            total += if (c >= 0L) c * d.min else c * d.max
        }
        return total
    }

    /** Outcome of one node LP pass: whether to prune, and the basis to warm-start children from. */
    private class LpNodeOutcome(val prune: Boolean, val basis: Basis?)

    /** True when the relaxation's rounded objective bound is at least the incumbent. */
    private fun boundPrunes(solution: LpSolution, relaxation: LpRelaxation, bound: Double): Boolean {
        if (!bound.isFinite()) return false
        val lpBound = solution.objectiveLowerBoundCeil() + relaxation.objectiveConstant
        return lpBound.toDouble() >= bound
    }

    /**
     * LP-relaxation bounding (#20), cut generation (#22) and reduced-cost fixing (#21): build and
     * solve one exact integer LP relaxation of the live problem, optionally strengthen it with cuts,
     * then either prune this node or tighten its domains. Prunes when the relaxation is infeasible or
     * its objective bound — rounded up, since the true objective is integral — is at least the
     * incumbent. Catches determinant overflow and keeps the node soundly (a missing bound only loses
     * pruning, never correctness).
     */
    private fun lpBoundAndFix(
        relaxer: CpToLpRelaxation,
        session: PropagationSession,
        bound: Double,
        sink: SolveStatsSink,
        warmBasis: Basis?,
        params: BacktrackParams,
        separators: List<CutSeparator>,
    ): LpNodeOutcome = try {
        lpBoundAndFixUnsafe(relaxer, session, bound, sink, warmBasis, params, separators)
    } catch (_: LpOverflowException) {
        // Determinant growth (large cut coefficients especially, #18) can exceed 64 bits. A missing
        // bound or reduction only loses pruning, never soundness — keep the node and move on.
        LpNodeOutcome(false, null)
    }

    private fun lpBoundAndFixUnsafe(
        relaxer: CpToLpRelaxation,
        session: PropagationSession,
        bound: Double,
        sink: SolveStatsSink,
        warmBasis: Basis?,
        params: BacktrackParams,
        separators: List<CutSeparator>,
    ): LpNodeOutcome {
        var relaxation = relaxer.build(session)
        if (relaxation.model.n == 0) return LpNodeOutcome(false, null) // empty relaxation
        var simplex = DualSimplex(relaxation.model)
        // Float fast-path (#18): with no parent basis to warm from, a quick double-precision solve
        // supplies a candidate basis for the exact solver to certify. Sound regardless — the exact
        // solve re-optimizes to the true bound, and a bad/singular basis just cold-starts.
        val startBasis = warmBasis ?: if (params.lpFloatWarmStart) FloatSimplex(relaxation.model).basis() else null
        var solution = simplex.solve(startBasis)
        sink.observeLpPivots(solution.pivots)
        // Warm-start children from the initial (pre-cut) basis: cut rows vary per node, but the base
        // model structure is identical across nodes, so only this basis transfers soundly.
        val warmCache = if (solution.status == LpStatus.OPTIMAL) solution.basis else null

        when (solution.status) {
            LpStatus.INFEASIBLE -> {
                sink.observeLpPrune()
                return LpNodeOutcome(true, null)
            }

            LpStatus.UNBOUNDED -> return LpNodeOutcome(false, null)

            LpStatus.OPTIMAL ->
                if (boundPrunes(solution, relaxation, bound)) {
                    sink.observeLpPrune()
                    return LpNodeOutcome(true, warmCache)
                }
        }

        // Cut rounds (#22): separate violated cuts from the LP point and re-solve. Cuts add rows, so
        // the structure changes — re-solve cold. Cuts are valid, so infeasibility under them prunes.
        if (params.lpCuts && separators.isNotEmpty()) {
            val pool = HashSet<String>()
            val cuts = ArrayList<Cut>()
            var round = 0
            while (round++ < params.lpCutRounds) {
                val ctx = CutContext(problem, relaxation, solution, session)
                // Structure-based separators run on the LP point; Gomory cuts come from the tableau.
                val separated = separators.flatMap { it.separate(ctx) }
                val gomory = if (params.lpGomory) simplex.gomoryCuts(GOMORY_CUTS_PER_ROUND) else emptyList()
                val fresh = (separated + gomory).filter { pool.add(it.key()) }
                if (fresh.isEmpty()) break
                cuts.addAll(fresh)
                sink.observeLpCuts(fresh.size)
                relaxation = relaxer.build(session, cuts)
                simplex = DualSimplex(relaxation.model)
                solution = simplex.solve()
                sink.observeLpPivots(solution.pivots)
                if (solution.status == LpStatus.INFEASIBLE) {
                    sink.observeLpPrune()
                    return LpNodeOutcome(true, warmCache)
                }
                if (solution.status != LpStatus.OPTIMAL) break
                if (boundPrunes(solution, relaxation, bound)) {
                    sink.observeLpPrune()
                    return LpNodeOutcome(true, warmCache)
                }
            }
        }

        // Reduced-cost fixing (#21) on the final, cut-strengthened solution; needs a finite gap.
        val prune = bound.isFinite() && solution.status == LpStatus.OPTIMAL &&
            applyReducedCostFixing(relaxation, solution, session, bound, sink)
        return LpNodeOutcome(prune, warmCache)
    }

    /**
     * Reduced-cost fixing (#21). At the LP optimum a nonbasic variable sits at one of its bounds; to
     * move it `Δ` integer steps off that bound raises the objective by at least `|reducedCost|·Δ`.
     * Any solution improving on the incumbent has objective `≤ ceil(bound) − 1`, so a variable can
     * move at most `floor((improvingMax − lpOpt) / |reducedCost|)` steps — its opposite bound is
     * pulled in by the rest in one shot. All arithmetic is exact over the shared LP denominator, so
     * no tolerance is needed; overflow conservatively skips the column (a missed tightening is sound).
     *
     * Reductions are applied at the current decision level via [PropagationSession.implyIntAtMost] etc.,
     * so they propagate immediately and are undone on backtrack. Returns true if a reduction empties a
     * domain — the node is then infeasible and pruned.
     */
    private fun applyReducedCostFixing(
        relaxation: LpRelaxation,
        solution: LpSolution,
        session: PropagationSession,
        bound: Double,
        sink: SolveStatsSink,
    ): Boolean {
        val den = solution.denominator // > 0
        val improvingMax = ceil(bound).toLong() - 1L // best objective that still beats the incumbent
        // Gap slack in scaled integer units: improvingMax·den − lpObjective(true). Non-negative here
        // because the node was not bound-pruned. Overflow on the scale-up just skips fixing.
        val slack = try {
            val objTrueNum = addExact(solution.objectiveNumerator, mulExact(relaxation.objectiveConstant, den))
            subExact(mulExact(improvingMax, den), objTrueNum)
        } catch (_: LpOverflowException) {
            return false
        }
        if (slack < 0L) return false
        val status = solution.basis.status
        for (col in relaxation.colVarId.indices) {
            val st = status[col]
            if (st == VarStatus.BASIC) continue
            val varId = relaxation.colVarId[col]
            val isBool = relaxation.colIsBool[col]
            val dNum = solution.reducedCostNumerator[col]
            if (isBool && session.boolValue(varId) != null) continue // already pinned
            val liveMin: Long
            val liveMax: Long
            if (isBool) {
                liveMin = 0L
                liveMax = 1L
            } else {
                val d = session.intDomain(varId)
                liveMin = d.min.toLong()
                liveMax = d.max.toLong()
            }
            if (liveMin == liveMax) continue
            val span = liveMax - liveMin
            val res = when (st) {
                // At lower bound: dual feasibility gives reducedCost ≥ 0; it can rise at most
                // floor(slack / d) steps before it alone overshoots the incumbent.
                VarStatus.AT_LOWER -> {
                    if (dNum <= 0L) continue
                    val dMax = slack / dNum
                    if (dMax >= span) continue
                    if (isBool) {
                        session.implyBool(
                            varId,
                            false,
                        )
                    } else {
                        session.implyIntAtMost(varId, (liveMin + dMax).toInt())
                    }
                }

                // At upper bound: reducedCost ≤ 0; symmetric, tighten the lower bound.
                VarStatus.AT_UPPER -> {
                    if (dNum >= 0L) continue
                    val dMax = slack / -dNum
                    if (dMax >= span) continue
                    if (isBool) {
                        session.implyBool(
                            varId,
                            true,
                        )
                    } else {
                        session.implyIntAtLeast(varId, (liveMax - dMax).toInt())
                    }
                }

                else -> continue
            }
            if (res is PropagationResult.Unsat) {
                sink.observeLpPrune()
                return true
            }
            sink.observeLpFix()
        }
        return false
    }

    // ---------------------------------------------------------------------------------------
    // Engine.
    // ---------------------------------------------------------------------------------------

    /** Map touched-seed-level [IntArray] to the subset of [input] assumptions at those
     *  levels. Returns `null` when the input was empty (no assumption layer to
     *  project) or no level was touched (no information). */
    private fun projectTouchedToAssumptions(input: Assumptions, levels: IntArray): Assumptions? {
        if (input.isEmpty || levels.isEmpty()) return null
        // [levels] is already the touched seed-level array; the projection is idempotent over
        // duplicates, so pass it straight through (no dedup set needed).
        return projectSeedConflictToAssumptions(input, levels)
    }

    /** Convert a touched-seed-level set into a sorted-ascending [IntArray], or empty
     *  when there were no touches (or no seed in the first place). */
    private fun touchedToArray(touched: HashSet<Int>?): IntArray {
        if (touched == null || touched.isEmpty()) return IntArray(0)
        val out = touched.toIntArray()
        out.sort()
        return out
    }

    /** Lift a [PropagationResult.Unsat]'s factor-level conflict info to a klause [UnsatCore].
     *  Empty `conflictFactors` (seed-only contradiction, no factor invocation involved)
     *  collapses to `null` — the API contract is "core absent" rather than "core empty",
     *  since an empty core wouldn't be actionable. */
    private fun coreOf(unsat: PropagationResult.Unsat): UnsatCore? = if (unsat.conflictFactors.isEmpty()) {
        null
    } else {
        UnsatCore.of(unsat.conflictFactors)
    }

    private sealed interface SearchOutcome {
        data class Found(val sample: Sample) : SearchOutcome

        /** DFS exhausted without finding a model. [core] is non-null when the exhaustion
         *  was forced by root-level propagation (bake or seed); after a full DFS-tree
         *  walk, no single-factor core explains the result and [core] stays null.
         *  [touchedAssumptionLevels] is the union of seed-level decision levels that
         *  appeared in any conflict's learned-clause decision-level set during the
         *  search — feeds the assumption-core projection in
         *  [com.eignex.klause.solver.satisfyUnderAssumptions]. Empty when no seed was
         *  in play or no conflict referenced a seed level. */
        data class Exhausted(val core: UnsatCore? = null, val touchedAssumptionLevels: IntArray = IntArray(0)) :
            SearchOutcome
        data object BudgetCapped : SearchOutcome
    }

    /**
     * A trail frame for one variable being explored. The value iterator is supplied by the
     * caller's [ValueHeuristic] at node creation; [applyNext] pulls the next value, pushes
     * it into the session, and reports back both the value (so the engine can fire
     * heuristic callbacks scoped to the attempted pair) and the session's propagation
     * response. Returns `null` when the value iterator is exhausted.
     */
    private sealed interface TrailNode {
        val varRef: VarRef
        fun applyNext(session: PropagationSession): ApplyOutcome?
    }

    /** What [TrailNode.applyNext] returns: the actual value pushed (bools encoded as 0/1
     *  so the value heuristic callbacks see the original heuristic-emitted form) plus the
     *  session's [PropagationResult]. */
    private data class ApplyOutcome(val value: Int, val result: PropagationResult)

    private class BoolNode(override val varRef: VarRef.Bool, valueSeq: Sequence<Int>) : TrailNode {
        private val iter = valueSeq.iterator()
        override fun applyNext(session: PropagationSession): ApplyOutcome? {
            if (!iter.hasNext()) return null
            val v = iter.next()
            return ApplyOutcome(v, session.pinBool(varRef.varId, v != 0))
        }
    }

    /**
     * Int decisions branch on a **bound**, not an equality: `v ≤ s` then `v ≥ s+1` (or the
     * reverse). Each branch is a single bound atom, so a conflict it seeds has one literal at
     * its level and 1UIP yields an asserting clause — an equality pin (`v = k`) instead pins
     * two same-level bound atoms that 1UIP cannot collapse, which stalls conflict learning.
     * The split point `s` is the value heuristic's preferred value (clamped into `[min, max-1]`
     * so both children are non-empty); the side holding that preferred value is explored first.
     */
    private class IntNode(override val varRef: VarRef.IntVar, valueSeq: Sequence<Int>) : TrailNode {
        private val preferred: Int = valueSeq.firstOrNull() ?: 0
        private var step = 0
        private var split = 0
        private var lowerFirst = true
        private var resolved = false

        override fun applyNext(session: PropagationSession): ApplyOutcome? {
            if (!resolved) {
                val d = session.intDomain(varRef.varId)
                split = if (preferred >= d.max) d.max - 1 else maxOf(preferred, d.min)
                lowerFirst = preferred <= split
                resolved = true
            }
            val vid = varRef.varId
            return when (step++) {
                0 -> if (lowerFirst) {
                    ApplyOutcome(split, session.pinIntAtMost(vid, split))
                } else {
                    ApplyOutcome(split + 1, session.pinIntAtLeast(vid, split + 1))
                }

                1 -> if (lowerFirst) {
                    ApplyOutcome(split + 1, session.pinIntAtLeast(vid, split + 1))
                } else {
                    ApplyOutcome(split, session.pinIntAtMost(vid, split))
                }

                else -> null
            }
        }
    }

    /**
     * Lazy stream of search outcomes. Each call resumes the DFS from where it last yielded.
     * Engine invariant: `trail` lists nodes whose currently-active value is reflected in
     * `session`'s pushed pins. On Unsat, `session` self-reverts — the engine doesn't
     * popLast in that case.
     */
    private fun driveSearch(
        params: BacktrackParams,
        pruneIf: ((PropagationSession) -> Boolean)? = null,
        sink: SolveStatsSink? = null,
        // Objective-bound propagation (single-variable objectives only). When [objectiveVar]
        // is set, the engine pushes each incumbent's bound onto that variable at the root —
        // `objVar ≤ best-1` for minimise ([objectiveAscending]) or `objVar ≥ best+1` for
        // maximise — as a permanent unit that propagates through the constraint defining the
        // objective. [objectiveBest] returns the objective variable's value in the current
        // incumbent, or null before one is found. Strictly stronger than the passive
        // [pruneIf] lower-bound check, and it bounds non-linear-defined objectives too.
        objectiveVar: Int = -1,
        objectiveAscending: Boolean = true,
        objectiveBest: () -> Int? = { null },
    ): Sequence<SearchOutcome> = sequence {
        if (problem.baked is PropagationResult.Unsat) {
            yield(SearchOutcome.Exhausted(coreOf(problem.baked)))
            return@sequence
        }
        val session = PropagationSession(problem)
        // Number of decision levels seed pushes uses — bool pins first then int pins.
        // Decision levels 1..numSeed correspond to assumptions; levels > numSeed are
        // post-seed DFS decisions.
        val numSeed = params.assumptions.boolKeys.size + params.assumptions.intKeys.size
        val touchedSeedLevels = if (numSeed > 0) HashSet<Int>() else null
        val seedResult = session.seed(params.assumptions)
        if (seedResult is PropagationResult.Unsat) {
            if (touchedSeedLevels != null) {
                for (l in seedResult.conflictLevels) if (l in 1..numSeed) touchedSeedLevels.add(l)
            }
            yield(SearchOutcome.Exhausted(coreOf(seedResult), touchedToArray(touchedSeedLevels)))
            return@sequence
        }
        // Phase-saving: cache the last value committed for each var (across backtracks
        // and restarts). Allocated only when enabled. The `boolPhaseSet` parallel array
        // distinguishes "never committed a value yet" from "saved value happens to be
        // false" — without it the default-false BooleanArray entries would shadow any
        // real saves of false.
        // Boolean phase saving is needed both for plain phase saving and as the fallback
        // polarity source in target phasing's SAVED rephase mode, so allocate it whenever
        // either feature is on. Integer phase saving stays gated on [phaseSaving] alone —
        // target phasing is pure-Boolean and never touches integer value selection.
        val boolPhaseTracking = params.phaseSaving || params.targetPhasing
        val boolPhase: BooleanArray? = if (boolPhaseTracking) BooleanArray(problem.numBoolVars) else null
        val boolPhaseSet: BooleanArray? = if (boolPhaseTracking) BooleanArray(problem.numBoolVars) else null
        val intPhase: IntArray? = if (params.phaseSaving) IntArray(problem.numIntVars) else null
        val intPhaseSet: BooleanArray? = if (params.phaseSaving) BooleanArray(problem.numIntVars) else null
        // Target phasing (#204): the deepest conflict-free Boolean assignment seen so far and
        // a rephasing schedule. [boolTarget]/[boolTargetSet] hold the target phase; the target
        // is refreshed whenever the trail reaches a new maximum depth (a deeper conflict-free
        // prefix). [rephaseMode] selects the current polarity source and rotates every
        // [BacktrackParams.rephaseInterval] conflicts. All persist across restarts.
        val boolTarget: BooleanArray? = if (params.targetPhasing) BooleanArray(problem.numBoolVars) else null
        val boolTargetSet: BooleanArray? = if (params.targetPhasing) BooleanArray(problem.numBoolVars) else null
        var bestTrailSize = -1
        var rephaseMode = REPHASE_TARGET
        var conflictsSinceRephase = 0L
        // Counts conflicts as they happen inside [advance] and rotates the rephase mode when
        // the interval elapses. The mode change takes effect on the next fresh descent — no
        // need to pop to root, since rephasing only reorders which polarity a new decision
        // tries first.
        val onConflictTick: () -> Unit = tick@{
            if (boolTarget == null) return@tick
            conflictsSinceRephase++
            if (conflictsSinceRephase >= params.rephaseInterval) {
                conflictsSinceRephase = 0
                rephaseMode = (rephaseMode + 1) % REPHASE_MODE_COUNT
            }
        }

        val baseSeed: Long = params.randomSeed ?: Random.Default.nextLong()
        val rng = Random(baseSeed)
        // The effective budget tightens the two limits — whichever is smaller wins. This
        // lets a uniform `maxInstructions` work across backends without removing the
        // backend-specific `maxDecisions` knob.
        var decisionsLeft = minOf(params.maxDecisions, params.maxInstructions ?: Long.MAX_VALUE)

        // Failsafe against repeat-learning livelock: count identical re-derivations per
        // clause (order-free literal-set hash). Healthy re-learning happens after
        // forgetting or restarts, but an unbounded streak means the backjump + assert
        // cycle is not progressing — past the threshold those conflicts are handled
        // chronologically. The count surfaces as the `relearned` solve stat under -s.
        val relearnCounts = MutableLongIntMap()
        val relearnTripped: (Learned) -> Boolean = { learned ->
            var h = 0L
            for (lit in learned.literals) h += splitmix64(lit.toLong())
            val n = relearnCounts.addTo(h, 1)
            if (n > 1) sink?.observeRelearn()
            n > RELEARN_FALLBACK_THRESHOLD
        }

        // Outer restart loop. Each iteration is one Luby-bounded DFS run from the root.
        // When `lubyRestartBase` is null the loop runs exactly once with infinite per-run
        // budget — same as the pre-restart behaviour.
        // Assignment of the most recently yielded leaf, pending a blocking nogood. Without it
        // the DFS only steps past a found solution chronologically, and a later backjump that
        // pops those frames re-opens the leaf — the search can then revisit and re-yield it,
        // potentially forever. The nogood spans the full assignment (not the decisions) so the
        // same solution reached through a different decision order is excluded too. It is
        // registered at the root on the next backtrack (or restart) and kept permanently.
        var pendingBlock: Sample? = null
        // Objective-bound propagation: assert the incumbent bound on the objective variable
        // at the root, once per improving value. Returns true iff that makes the root
        // infeasible — the remaining objective space is empty, so the search is exhausted
        // (optimum proven). Must be called only when the session is at the root.
        var lastObjBoundAsserted: Int? = null
        fun assertObjectiveBoundAtRoot(): Boolean {
            if (objectiveVar < 0) return false
            val best = objectiveBest() ?: return false
            val threshold = if (objectiveAscending) best - 1 else best + 1
            if (threshold == lastObjBoundAsserted) return false
            lastObjBoundAsserted = threshold
            return session.assertObjectiveBound(objectiveVar, threshold, atMost = objectiveAscending) is
                PropagationResult.Unsat
        }
        // Glucose-style adaptive restart policy (#198). When enabled it replaces the Luby
        // budget: restarts fire on learned-clause quality (recent LBD vs the long-run average),
        // with trail-size blocking. `restartRequested` is set by the conflict handlers and
        // consumed at the top of the inner loop; the policy's own stats persist across restarts.
        val glucose: GlucoseRestart? = if (params.adaptiveRestart) GlucoseRestart() else null
        var restartRequested = false
        // Vivification (#203) walks the learned DB round-robin across restarts; the cursor
        // persists between restart passes so successive passes cover the whole database.
        val vivifyEnabled = params.vivification && params.assumptions.isEmpty
        var vivifyCursor = 0
        var lubyIdx = 1L
        outer@ while (true) {
            val perRunBudget: Long = if (glucose != null) {
                Long.MAX_VALUE // adaptive restarts drive the schedule; the Luby budget is off
            } else {
                params.lubyRestartBase?.let { base ->
                    // Cap multiplication to avoid overflow on tiny base + huge lubyIdx.
                    val limit = lubyN(lubyIdx)
                    if (limit > Long.MAX_VALUE / base) Long.MAX_VALUE else limit * base
                } ?: Long.MAX_VALUE
            }
            var decisionsThisRun = 0L

            val trail: MutableList<TrailNode> = ArrayList()
            var descend = true
            var cancelCheckCountdown = 0

            inner@ while (true) {
                if (cancelCheckCountdown-- <= 0) {
                    if (params.cancellation()) {
                        yield(SearchOutcome.BudgetCapped)
                        return@sequence
                    }
                    cancelCheckCountdown = CANCEL_CHECK_INTERVAL
                }
                // Restart trigger: Luby budget hit, or the adaptive policy asked to re-pick.
                // Either way pop back to root and restart.
                if (decisionsThisRun >= perRunBudget || restartRequested) {
                    restartRequested = false
                    while (trail.isNotEmpty()) {
                        session.popLast()
                        trail.removeAt(trail.size - 1)
                    }
                    val restartBlock = pendingBlock
                    if (restartBlock != null) {
                        pendingBlock = null
                        if (restartBlock.bools.isNotEmpty() || restartBlock.ints.isNotEmpty()) {
                            // All decisions are popped; register the nogood so the restarted run
                            // cannot re-yield the same leaf. A root-level contradiction here
                            // proves the remaining space empty.
                            val nogood = session.assignmentNogood(restartBlock.bools, restartBlock.ints)
                            val res = session.addLearnedClause(Clause(nogood), lbd = nogood.size, permanent = true)
                            if (res is PropagationResult.Unsat) {
                                yield(
                                    SearchOutcome.Exhausted(
                                        touchedAssumptionLevels = touchedToArray(touchedSeedLevels),
                                    ),
                                )
                                return@sequence
                            }
                        }
                    }
                    if (assertObjectiveBoundAtRoot()) {
                        yield(SearchOutcome.Exhausted(touchedAssumptionLevels = touchedToArray(touchedSeedLevels)))
                        return@sequence
                    }
                    params.variableHeuristic.onRestart()
                    params.valueHeuristic.onRestart()
                    // LCG learned-clause forgetting: at each restart, prune the database
                    // when over [maxLearnedClauses]. Glue clauses (LBD ≤ glueThreshold)
                    // are always retained; among the rest, the lowest-LBD entries are
                    // kept up to the cap.
                    forgetIfOverCap(session, params)
                    // Vivification inprocessing: the trail is at root here, so a bounded slice
                    // of the learned DB can be strengthened against clean assumptions (#203).
                    if (vivifyEnabled) vivifyCursor = vivify(session, params, vivifyCursor)
                    lubyIdx++
                    sink?.observeRestart()
                    params.onEvent?.invoke(SearchEvent.Restart(lubyIdx - 1, decisionsThisRun))
                    continue@outer
                }
                if (descend) {
                    val varRef = params.variableHeuristic.pick(session, rng)
                    if (varRef == null) {
                        val snap = snapshotAssignment(session)
                        // Notify heuristics first so solution-guided variants can snapshot
                        // the incumbent before the engine continues with the next yield.
                        params.variableHeuristic.onSolution(snap)
                        params.valueHeuristic.onSolution(snap)
                        pendingBlock = snap
                        yield(SearchOutcome.Found(snap))
                        descend = false
                        continue@inner
                    }
                    val values = params.valueHeuristic.values(session, varRef, rng)
                    val ordered = applyPhase(
                        varRef, values, boolPhase, boolPhaseSet, intPhase, intPhaseSet,
                        boolTarget, boolTargetSet, rephaseMode, rng,
                    )
                    val node = makeNode(varRef, ordered)
                    val decsBefore = decisionsLeft
                    val out = advance(
                        node,
                        session,
                        params,
                        pruneIf,
                        { decisionsLeft },
                        { decisionsLeft-- },
                        sink,
                        relearnTripped,
                        onConflictTick,
                    )
                    decisionsThisRun += decsBefore - decisionsLeft
                    when (out) {
                        AdvanceOutcome.Success -> {
                            capturePhase(varRef, session, boolPhase, boolPhaseSet, intPhase, intPhaseSet)
                            trail.add(node)
                            sink?.observeNode(trail.size)
                            // Target phasing: a new maximum trail depth is the deepest
                            // conflict-free assignment seen — snapshot it as the target phase.
                            if (boolTarget != null && boolTargetSet != null && trail.size > bestTrailSize) {
                                bestTrailSize = trail.size
                                captureTargetPhase(session, boolTarget, boolTargetSet)
                            }
                        }

                        AdvanceOutcome.Exhausted -> {
                            descend = false
                            continue@inner
                        }

                        AdvanceOutcome.BudgetCapped -> {
                            yield(SearchOutcome.BudgetCapped)
                            return@sequence
                        }

                        is AdvanceOutcome.Backjump -> {
                            sink?.observeFail()
                            sink?.observeLearn()
                            if (touchedSeedLevels != null) {
                                for (l in out.learned.decisionLevels) if (l in 1..numSeed) touchedSeedLevels.add(l)
                            }
                            // Feed the learned clause's LBD and the current depth to the
                            // adaptive restart policy (trail size == decision level here; the
                            // failed pin was self-reverted by the session).
                            if (glucose != null && glucose.recordConflict(out.learned.lbd, trail.size)) {
                                restartRequested = true
                            }
                            // Execute the backjump + learn sequence. On cascading conflict
                            // during assertion, recurse.
                            val term = backjumpAndLearn(
                                out.learned, trail, session, params,
                                boolPhase, boolPhaseSet, intPhase, intPhaseSet, alignFirst = false,
                            )
                            when (term) {
                                BackjumpTerm.Resume -> {
                                    descend = true
                                    continue@inner
                                }

                                BackjumpTerm.Exhausted -> {
                                    yield(
                                        SearchOutcome.Exhausted(
                                            touchedAssumptionLevels = touchedToArray(touchedSeedLevels),
                                        ),
                                    )
                                    return@sequence
                                }

                                BackjumpTerm.Stuck -> {
                                    descend = false
                                    continue@inner
                                }
                            }
                        }
                    }
                } else {
                    val rootBlock = pendingBlock
                    if (rootBlock != null) {
                        // Apply the pending blocking nogood at the root, where it can neither
                        // conflict nor assert mid-trail; a root contradiction proves the
                        // remaining space empty.
                        pendingBlock = null
                        while (trail.isNotEmpty()) {
                            session.popLast()
                            trail.removeAt(trail.size - 1)
                        }
                        val nogood = session.assignmentNogood(rootBlock.bools, rootBlock.ints)
                        if (nogood.isNotEmpty()) {
                            val res = session.addLearnedClause(Clause(nogood), lbd = nogood.size, permanent = true)
                            if (res is PropagationResult.Unsat) {
                                yield(
                                    SearchOutcome.Exhausted(
                                        touchedAssumptionLevels = touchedToArray(touchedSeedLevels),
                                    ),
                                )
                                return@sequence
                            }
                        }
                        if (assertObjectiveBoundAtRoot()) {
                            yield(SearchOutcome.Exhausted(touchedAssumptionLevels = touchedToArray(touchedSeedLevels)))
                            return@sequence
                        }
                        descend = true
                        continue@inner
                    }
                    if (trail.isEmpty()) {
                        yield(
                            SearchOutcome.Exhausted(
                                touchedAssumptionLevels = touchedToArray(touchedSeedLevels),
                            ),
                        )
                        return@sequence
                    }
                    val top = trail.last()
                    session.popLast()
                    val decsBefore = decisionsLeft
                    val out = advance(
                        top,
                        session,
                        params,
                        pruneIf,
                        { decisionsLeft },
                        { decisionsLeft-- },
                        sink,
                        relearnTripped,
                        onConflictTick,
                    )
                    decisionsThisRun += decsBefore - decisionsLeft
                    when (out) {
                        AdvanceOutcome.Success -> {
                            capturePhase(top.varRef, session, boolPhase, boolPhaseSet, intPhase, intPhaseSet)
                            descend = true
                        }

                        AdvanceOutcome.Exhausted -> {
                            trail.removeAt(trail.size - 1)
                        }

                        AdvanceOutcome.BudgetCapped -> {
                            yield(SearchOutcome.BudgetCapped)
                            return@sequence
                        }

                        is AdvanceOutcome.Backjump -> {
                            if (touchedSeedLevels != null) {
                                for (l in out.learned.decisionLevels) if (l in 1..numSeed) touchedSeedLevels.add(l)
                            }
                            if (glucose != null && glucose.recordConflict(out.learned.lbd, trail.size)) {
                                restartRequested = true
                            }
                            // Else-path: session has been popped below trail.last; align
                            // first (trail.removeAt) then proceed to backjump + learn.
                            val term = backjumpAndLearn(
                                out.learned, trail, session, params,
                                boolPhase, boolPhaseSet, intPhase, intPhaseSet, alignFirst = true,
                            )
                            when (term) {
                                BackjumpTerm.Resume -> {
                                    descend = true
                                    continue@inner
                                }

                                BackjumpTerm.Exhausted -> {
                                    yield(
                                        SearchOutcome.Exhausted(
                                            touchedAssumptionLevels = touchedToArray(touchedSeedLevels),
                                        ),
                                    )
                                    return@sequence
                                }

                                BackjumpTerm.Stuck -> {
                                    descend = false
                                    continue@inner
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * If phase-saving is on and a value is cached for [varRef], prepend the cached value
     * to the heuristic's order (and drop it from the rest of the sequence so it isn't
     * tried twice). Otherwise the heuristic's order passes through unchanged.
     */
    private fun applyPhase(
        varRef: VarRef,
        values: Sequence<Int>,
        boolPhase: BooleanArray?,
        boolPhaseSet: BooleanArray?,
        intPhase: IntArray?,
        intPhaseSet: BooleanArray?,
        boolTarget: BooleanArray? = null,
        boolTargetSet: BooleanArray? = null,
        rephaseMode: Int = REPHASE_TARGET,
        rng: Random? = null,
    ): Sequence<Int> = when (varRef) {
        is VarRef.Bool -> {
            val v = varRef.varId
            val savedFirst: Int? = if (boolPhase != null && boolPhaseSet != null && boolPhaseSet[v]) {
                if (boolPhase[v]) 1 else 0
            } else {
                null
            }
            // Target phasing rotates the polarity source; plain phase saving just uses the
            // saved value. The chosen value (if any) is tried first, with the heuristic's
            // order filling the rest.
            val preferred: Int? = if (boolTarget != null && boolTargetSet != null) {
                when (rephaseMode) {
                    // Target: the deepest conflict-free phase, falling back to saved.
                    REPHASE_TARGET -> if (boolTargetSet[v]) (if (boolTarget[v]) 1 else 0) else savedFirst

                    REPHASE_SAVED -> savedFirst

                    REPHASE_TRUE -> 1

                    REPHASE_FALSE -> 0

                    REPHASE_RANDOM -> if ((rng ?: Random.Default).nextBoolean()) 1 else 0

                    else -> savedFirst
                }
            } else {
                savedFirst
            }
            if (preferred != null) sequenceOf(preferred) + values.filter { it != preferred } else values
        }

        is VarRef.IntVar -> {
            if (intPhase != null && intPhaseSet != null && intPhaseSet[varRef.varId]) {
                val saved = intPhase[varRef.varId]
                sequenceOf(saved) + values.filter { it != saved }
            } else {
                values
            }
        }
    }

    /** Record the variable's currently-pinned value for phase-saving. Called after every
     *  successful pin (descent into a node). */
    private fun capturePhase(
        varRef: VarRef,
        session: PropagationSession,
        boolPhase: BooleanArray?,
        boolPhaseSet: BooleanArray?,
        intPhase: IntArray?,
        intPhaseSet: BooleanArray?,
    ) {
        when (varRef) {
            is VarRef.Bool -> {
                if (boolPhase != null && boolPhaseSet != null) {
                    val v = session.boolValue(varRef.varId)
                    if (v != null) {
                        boolPhase[varRef.varId] = v
                        boolPhaseSet[varRef.varId] = true
                    }
                }
            }

            is VarRef.IntVar -> {
                if (intPhase != null && intPhaseSet != null) {
                    val d = session.intDomain(varRef.varId)
                    if (d.min == d.max) {
                        intPhase[varRef.varId] = d.min
                        intPhaseSet[varRef.varId] = true
                    }
                }
            }
        }
    }

    /** Snapshot the current Boolean assignment as the target phase (the deepest conflict-free
     *  prefix). Variables not yet pinned keep their previous target entry — a deeper later
     *  descent will fill them in. */
    private fun captureTargetPhase(session: PropagationSession, boolTarget: BooleanArray, boolTargetSet: BooleanArray) {
        for (v in boolTarget.indices) {
            val value = session.boolValue(v) ?: continue
            boolTarget[v] = value
            boolTargetSet[v] = true
        }
    }

    private fun makeNode(varRef: VarRef, values: Sequence<Int>): TrailNode = when (varRef) {
        is VarRef.Bool -> BoolNode(varRef, values)
        is VarRef.IntVar -> IntNode(varRef, values)
    }

    /**
     * Luby sequence (Luby-Sinclair-Zuckerman 1993). Standard CDCL restart schedule:
     * `1, 1, 2, 1, 1, 2, 4, 1, 1, 2, 1, 1, 2, 4, 8, ...`. Closed form:
     * `lubyN(i) = 2^(k-1)` when `i = 2^k − 1` (i.e. one less than a power of two);
     * otherwise `lubyN(i − 2^(k-1) + 1)` where `k = ⌊log₂(i)⌋ + 1`.
     */
    private fun lubyN(idxIn: Long): Long {
        var i = idxIn
        var k = 1
        // Find smallest k such that 2^k > i.
        while ((1L shl k) <= i) k++
        // Equivalent to the textbook recurrence; iteratively unwound.
        while (true) {
            val pow = 1L shl (k - 1)
            if (i == (pow shl 1) - 1) return pow
            // Otherwise i < (pow << 1) - 1; recurse on (i - pow + 1).
            i = i - pow + 1
            k = 1
            while ((1L shl k) <= i) k++
        }
    }

    /**
     * What [advance] reports back to the search loop. LCG-style non-chronological
     * backjump needs the target level threaded back to the outer loop, hence the
     * sealed type rather than a plain Boolean success / failure.
     */
    private sealed interface AdvanceOutcome {
        /** A value pinned cleanly; commit the node to the trail. */
        data object Success : AdvanceOutcome

        /** Node has no more values; chronological backtrack. */
        data object Exhausted : AdvanceOutcome

        /** Decision budget hit. */
        data object BudgetCapped : AdvanceOutcome

        /** Non-chronological backjump requested. After the engine pops trail to
         *  `learned.backjumpLevel`, it materialises `learned.literals` as a `Clause`,
         *  hands it to [PropagationSession.addLearnedClause], and resumes with the new
         *  clause now constraining future search and unit-propagating the asserting
         *  literal. */
        data class Backjump(val learned: Learned) : AdvanceOutcome
    }

    private fun advance(
        node: TrailNode,
        session: PropagationSession,
        params: BacktrackParams,
        pruneIf: ((PropagationSession) -> Boolean)?,
        decisionsRemaining: () -> Long,
        decrement: () -> Unit,
        sink: SolveStatsSink? = null,
        relearnTripped: ((Learned) -> Boolean)? = null,
        onConflictTick: (() -> Unit)? = null,
    ): AdvanceOutcome {
        while (true) {
            if (decisionsRemaining() <= 0) return AdvanceOutcome.BudgetCapped
            decrement()
            val propsBefore = session.propagationCount
            val outcome = node.applyNext(session) ?: return AdvanceOutcome.Exhausted
            // Count every factor-forced assignment this pin triggered — including the
            // propagation done on the way to a conflict (Unsat returns below).
            sink?.observePropagation(session.propagationCount - propsBefore)
            val r = outcome.result
            if (r is PropagationResult.Unsat) {
                onConflictTick?.invoke()
                // Forward the full conflict reason record so activity-, weight-, and
                // factor-driven heuristics (VSIDS, dom/wdeg) all see exactly what they
                // need without further plumbing.
                params.variableHeuristic.onConflict(node.varRef, r)
                params.valueHeuristic.onConflict(node.varRef, outcome.value)
                // CDB: if the analyzer produced a 1UIP clause with a non-chronological
                // backjump target, signal it up. The engine pops to the backjump level and
                // then persists the clause via [PropagationSession.addLearnedClause] (see
                // [backjumpAndLearn]), so the learned nogood both forces its asserting
                // literal now and constrains all future propagation — not just the one-shot
                // jump-distance prune.
                val learned = r.learnedClause as? ConflictAnalyzer.AnalysisResult.Learned
                // Only take the non-chronological backjump when the clause is a proper
                // 1UIP (asserting) clause — popping to its backjump level then makes it
                // unit and forces the asserting literal. A non-asserting clause (e.g. the
                // two same-level bound atoms an int *equality* decision contributes, which
                // 1UIP cannot collapse) would never become unit, so asserting it is a no-op
                // and the search would re-make the same decision forever. Fall through to
                // chronological within-node value enumeration instead, which is complete.
                // Two guards before taking the backjump: a clause carrying an
                // already-true literal (a kept resolved-atom literal can be) is satisfied,
                // so the assert would be a no-op and the popped frames' untried values
                // lost for nothing; and a clause re-derived identically past the relearn
                // threshold signals a cycle the backjump isn't breaking. Either way the
                // conflict falls through to chronological within-node enumeration.
                if (learned != null &&
                    learned.asserting &&
                    learned.literals.none { session.litTruth(it) == true } &&
                    relearnTripped?.invoke(learned) != true
                ) {
                    return AdvanceOutcome.Backjump(learned)
                }
                continue
            }
            if (pruneIf != null && pruneIf(session)) {
                session.popLast()
                continue
            }
            // ABS-style activity heuristics need the implied set from the just-completed
            // propagation step; only Implied carries those keys.
            if (r is PropagationResult.Implied) {
                params.variableHeuristic.onPropagation(r)
            }
            params.variableHeuristic.onCommit(node.varRef)
            params.valueHeuristic.onCommit(node.varRef, outcome.value)
            return AdvanceOutcome.Success
        }
    }

    /**
     * Apply the LCG forgetting policy on a Luby restart. No-op when
     * [BacktrackParams.maxLearnedClauses] is null or the learned database is already
     * under the cap. Otherwise: glue clauses (LBD ≤ [BacktrackParams.lbdGlueThreshold])
     * are kept, and among non-glue clauses we keep the lowest-LBD ones up to the
     * remaining cap. Implemented as: collect (index, lbd) pairs for non-glue clauses,
     * sort by LBD ascending, take the first `remaining` of them, plus all glue.
     */
    private fun forgetIfOverCap(session: PropagationSession, params: BacktrackParams) {
        val cap = params.maxLearnedClauses ?: return
        val learnedSize = session.learnedClauseCount
        if (learnedSize <= cap) return
        if (params.tieredLearnedDb) {
            forgetTiered(session, params, cap, learnedSize)
            return
        }
        val glueThreshold = params.lbdGlueThreshold
        // Bucket non-glue clauses by LBD and pick the lowest LBDs up to the residual
        // capacity. We do this as: compute LBD per index, sort ascending, and define
        // `keep(i, lbd) = lbd <= glueThreshold || rank(i) < remaining`.
        val nonGlue = ArrayList<IntArray>(learnedSize) // [lbd, index] pairs
        for (i in 0 until learnedSize) {
            val lbd = session.learnedClauseLbd(i)
            if (lbd > glueThreshold && !session.learnedClausePermanent(i)) nonGlue.add(intArrayOf(lbd, i))
        }
        // If all are glue, nothing to forget.
        if (nonGlue.isEmpty()) return
        val glueCount = learnedSize - nonGlue.size
        val remainingCap = (cap - glueCount).coerceAtLeast(0)
        if (nonGlue.size <= remainingCap) return // already under cap
        nonGlue.sortBy { it[0] } // ascending LBD
        val kept = IntHashSet(remainingCap)
        for (k in 0 until remainingCap) kept.add(nonGlue[k][1])
        session.forgetLearnedClauses { idx, lbd ->
            lbd <= glueThreshold || session.learnedClausePermanent(idx) || idx in kept
        }
        val dropped = nonGlue.size - remainingCap
        params.onEvent?.invoke(SearchEvent.LearnedDbSweep(kept = learnedSize - dropped, dropped = dropped))
    }

    /**
     * Three-tier reduction policy (#201). Each learned clause is classified by LBD into a
     * permanent core (LBD ≤ [BacktrackParams.lbdGlueThreshold]), a mid tier
     * (LBD ≤ [BacktrackParams.midLbdThreshold]) and a local tier; tiers persist across
     * reductions. Reuse since the last reduction (the clause detected a conflict or forced a
     * unit, tracked by `PropagationState.noteLearnedUse`) drives promotion and demotion:
     *  - core: always kept;
     *  - mid: always kept this pass, but demoted to local when idle so it can be deleted later;
     *  - local: promoted to mid when reused, otherwise a deletion candidate.
     * Among the local deletion candidates the lowest-LBD ones are kept up to the residual cap
     * and the rest are dropped. Reuse flags are cleared for survivors so the next window
     * measures fresh activity.
     */
    private fun forgetTiered(session: PropagationSession, params: BacktrackParams, cap: Int, learnedSize: Int) {
        val coreThreshold = params.lbdGlueThreshold
        val midThreshold = params.midLbdThreshold
        val locals = ArrayList<IntArray>(learnedSize) // [lbd, index] local deletion candidates
        for (i in 0 until learnedSize) {
            val lbd = session.learnedClauseLbd(i)
            val used = session.learnedClauseUsedSinceReduction(i)
            val entryTier = session.learnedClauseTier(i).let { t ->
                if (t != TIER_UNSET) {
                    t
                } else {
                    when {
                        lbd <= coreThreshold -> TIER_CORE
                        lbd <= midThreshold -> TIER_MID
                        else -> TIER_LOCAL
                    }
                }
            }
            if (session.learnedClausePermanent(i)) {
                session.setLearnedClauseTier(i, entryTier) // permanent clauses are always kept
                continue
            }
            when (entryTier) {
                TIER_CORE -> session.setLearnedClauseTier(i, TIER_CORE)

                // Mid is kept this pass; demote to local when idle so it ages out next time.
                TIER_MID -> session.setLearnedClauseTier(i, if (used) TIER_MID else TIER_LOCAL)

                else -> if (used) {
                    session.setLearnedClauseTier(i, TIER_MID) // promote a reused local clause
                } else {
                    session.setLearnedClauseTier(i, TIER_LOCAL)
                    locals.add(intArrayOf(lbd, i)) // deletion candidate
                }
            }
        }
        val kept = learnedSize - locals.size
        val residualCap = (cap - kept).coerceAtLeast(0)
        if (locals.size <= residualCap) {
            for (i in 0 until learnedSize) session.clearLearnedClauseUsed(i)
            return
        }
        locals.sortBy { it[0] } // ascending LBD: keep the lowest, drop the highest
        val dropSet = IntHashSet(locals.size - residualCap)
        for (k in residualCap until locals.size) dropSet.add(locals[k][1])
        session.forgetLearnedClauses { idx, _ -> idx !in dropSet }
        params.onEvent?.invoke(SearchEvent.LearnedDbSweep(kept = learnedSize - dropSet.size, dropped = dropSet.size))
        // Indices were compacted by the forget; reset every survivor's reuse flag.
        val survivors = session.learnedClauseCount
        for (i in 0 until survivors) session.clearLearnedClauseUsed(i)
    }

    /**
     * Clause vivification inprocessing (#203) — Piette-Hamadi-Saïs 2008. Walks a bounded
     * round-robin slice ([BacktrackParams.vivifyBatch]) of the learned-clause database and
     * strengthens each pure-Boolean, non-permanent clause via [vivifyClause]. Must be called
     * with the session at root (the restart boundary pops the DFS trail first). Strengthened
     * clauses are swapped in by dropping the originals and re-adding the shortened versions;
     * since the re-added clauses are at least binary over root-unassigned variables they don't
     * propagate, so the session is left at root. Returns the advanced cursor for the next pass.
     *
     * Soundness: every clause [vivifyClause] returns is still implied by the formula (a
     * subclause of an implied clause, or a prefix proven implied by propagation), so swapping
     * it in cannot lose models — checked by the learned-clause / witness validation tests.
     */
    private fun vivify(session: PropagationSession, params: BacktrackParams, startCursor: Int): Int {
        val count = session.learnedClauseCount
        if (count == 0) return 0
        val numBool = session.problem.numBoolVars
        val batch = params.vivifyBatch.coerceAtLeast(1)
        val replacements = ArrayList<IntArray>()
        val dropIdx = IntHashSet()
        var cursor = if (startCursor in 0 until count) startCursor else 0
        var examined = 0
        while (examined < batch && examined < count) {
            val idx = cursor
            cursor = (cursor + 1) % count
            examined++
            if (session.learnedClausePermanent(idx)) continue
            val clause = session.learnedClauseAt(idx)
            val lits = clause.literals
            // Pure-Boolean only; nothing to shorten below 3 literals (we never emit units).
            if (lits.size < 3 || !clause.allLiteralsBool(numBool)) continue
            val strengthened = vivifyClause(session, lits) ?: continue
            if (strengthened.size in 2 until lits.size) {
                dropIdx.add(idx)
                replacements.add(strengthened)
            }
        }
        if (replacements.isEmpty()) return cursor
        session.forgetLearnedClauses { i, _ -> i !in dropIdx }
        for (newLits in replacements) session.addLearnedClause(Clause(newLits), lbd = newLits.size)
        // The forget renumbered the database, so resume the round-robin from the start.
        return 0
    }

    /**
     * Vivify one clause with the session at root: walk [lits] asserting the negation of each
     * literal under propagation. A literal already falsified by the earlier negations is
     * dropped (redundant); a literal forced true, or a conflict on asserting its negation,
     * shortens the clause to the literals visited so far. Returns the strengthened literal
     * array, or null when nothing changed. Every tentative pin is reverted before returning,
     * so the session is left exactly as it was found.
     */
    private fun vivifyClause(session: PropagationSession, lits: IntArray): IntArray? {
        val keep = IntArrayList(lits.size)
        var pushed = 0
        var result: IntArray? = null
        for (li in lits) {
            when (session.litTruth(li)) {
                // The earlier negations already force li true ⇒ (kept ∨ li) is implied.
                true -> {
                    keep.add(li)
                    result = keep.toIntArray()
                    break
                }

                // li is already falsified by the earlier negations ⇒ redundant, drop it.
                false -> Unit

                // Undetermined: assert ¬li and keep going.
                null -> {
                    keep.add(li)
                    val r = session.pinBool(Lit.variable(li), !Lit.isPositive(li))
                    if (r is PropagationResult.Unsat) {
                        // ¬(kept) is unsatisfiable ⇒ (kept) is implied.
                        result = keep.toIntArray()
                        break
                    }
                    pushed++
                }
            }
        }
        repeat(pushed) { session.popLast() }
        if (result == null && keep.size < lits.size) result = keep.toIntArray()
        return result
    }

    /** How [backjumpAndLearn] terminated. */
    private enum class BackjumpTerm {
        /** Backjumped, learned clause asserted cleanly. Resume by descending. */
        Resume,

        /** Asserting the learned clause forced a level-0 contradiction; the entire search
         *  space is infeasible. Engine yields [SearchOutcome.Exhausted]. */
        Exhausted,

        /** Cascading conflicts couldn't be resolved further (e.g., assertion reached
         *  level 0 without a useful new clause). Fall back to chronological backtrack. */
        Stuck,
    }

    /**
     * Execute the CDB backjump + clause-learn sequence:
     *   - pop trail + session to `learned.backjumpLevel`;
     *   - materialise `learned.literals` as a [Clause]
     *     and feed it to [PropagationSession.addLearnedClause], which asserts it via
     *     propagation (forcing the asserting literal as a unit pin);
     *   - if the assertion cascades into another conflict, recurse on the new analyzer
     *     result. Bounded to keep the search loop from looping forever on pathological
     *     instances; [BackjumpTerm.Stuck] surfaces to the caller in that case.
     */
    private fun backjumpAndLearn(
        learned: Learned,
        trail: MutableList<TrailNode>,
        session: PropagationSession,
        @Suppress("UNUSED_PARAMETER") params: BacktrackParams,
        @Suppress("UNUSED_PARAMETER") boolPhase: BooleanArray?,
        @Suppress("UNUSED_PARAMETER") boolPhaseSet: BooleanArray?,
        @Suppress("UNUSED_PARAMETER") intPhase: IntArray?,
        @Suppress("UNUSED_PARAMETER") intPhaseSet: BooleanArray?,
        alignFirst: Boolean,
    ): BackjumpTerm {
        if (alignFirst && trail.isNotEmpty()) trail.removeAt(trail.size - 1)
        var current = learned
        // Cap the recursive backjump loop to defend against pathological cycles. Each
        // round strictly reduces the conflict level (the analyzer's backjumpLevel is
        // always < the conflict's current level), so termination is guaranteed in a
        // sane analyzer — the cap is purely defensive.
        repeat(MAX_CASCADING_BACKJUMPS) {
            // A non-asserting clause never becomes unit after the backjump, so it can't
            // force its asserting literal — fall back to chronological backtracking.
            if (!current.asserting) return BackjumpTerm.Stuck
            // Pop trail + session to the backjump level.
            while (trail.size > current.backjumpLevel) {
                session.popLast()
                trail.removeAt(trail.size - 1)
            }
            // Build the Clause and assert it. The clause's literals are non-empty as
            // long as the analyzer produced a UIP (always the case in well-formed
            // calls); if the clause came out empty, fall back to chronological.
            if (current.literals.isEmpty()) return BackjumpTerm.Stuck
            val clause = Clause(current.literals)
            val result = session.addLearnedClause(clause, current.lbd)
            when (result) {
                is PropagationResult.Implied -> return BackjumpTerm.Resume

                is PropagationResult.Unsat -> {
                    // Assertion cascaded into another conflict. The session ran the
                    // analyzer on the new conflict; if a new learned clause came back,
                    // recurse — otherwise we're stuck.
                    val next = result.learnedClause
                        as? Learned
                        ?: return BackjumpTerm.Stuck
                    // If the new backjump target is level 0 and the clause is empty
                    // after that jump, the whole problem is infeasible.
                    if (next.backjumpLevel == 0 && next.literals.isEmpty()) {
                        return BackjumpTerm.Exhausted
                    }
                    current = next
                }
            }
        }
        return BackjumpTerm.Stuck
    }

    private fun snapshotAssignment(session: PropagationSession): Sample {
        val sp = session.problem
        val bools = BooleanArray(sp.numBoolVars) { v -> session.boolValue(v) ?: false }
        val ints = IntArray(sp.numIntVars) { v -> session.intDomain(v).min }
        return Sample(bools, ints)
    }

    private fun farEnough(candidate: Sample, window: ArrayDeque<Sample>, minDistance: Int): Boolean {
        if (minDistance <= 0 || window.isEmpty()) return true
        for (p in window) if (candidate.hammingDistanceTo(p) < minDistance) return false
        return true
    }
    private companion object {
        /** Cancellation is polled this often inside the search loop. Lower = more
         *  responsive; higher = lower overhead. 256 is a few microseconds per check at
         *  worst, and the search stops within a few hundred decisions of a cancel. */
        const val CANCEL_CHECK_INTERVAL: Int = 256

        /** Most Gomory cuts to draw from one tableau per separation round (#22). */
        const val GOMORY_CUTS_PER_ROUND: Int = 8

        /** Cap on cascading CDB backjumps within a single search step. Defensive; under
         *  a well-formed analyzer the loop terminates well before this. */
        const val MAX_CASCADING_BACKJUMPS: Int = 64

        /** After this many identical re-derivations of one clause, its conflicts are
         *  handled chronologically instead of by backjump — a repeat-learning streak this
         *  long means the backjump + assert cycle is not progressing. Generous enough that
         *  healthy re-learning (after forgetting or restarts) never trips it. */
        const val RELEARN_FALLBACK_THRESHOLD: Int = 8

        // Rephasing polarity sources (#204), rotated every `rephaseInterval` conflicts.

        /** Bias toward the deepest conflict-free assignment seen (falls back to saved). */
        const val REPHASE_TARGET: Int = 0

        /** Plain phase saving — the last value committed for the variable. */
        const val REPHASE_SAVED: Int = 1

        /** Force all decisions to try `true` first. */
        const val REPHASE_TRUE: Int = 2

        /** Force all decisions to try `false` first. */
        const val REPHASE_FALSE: Int = 3

        /** Random polarity per decision. */
        const val REPHASE_RANDOM: Int = 4

        /** Number of rephase modes in the rotation. */
        const val REPHASE_MODE_COUNT: Int = 5
    }
}
