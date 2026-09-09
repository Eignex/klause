package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.cut.CutContext
import com.eignex.klause.lp.cut.CutPool
import com.eignex.klause.lp.cut.CutSeparator
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.CertifiedLpResult
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.FarkasRoute
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.IntegerCertificate
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpSolver
import com.eignex.klause.lp.engine.PersistentLpSolver
import com.eignex.klause.lp.engine.TableauCutSolver
import com.eignex.klause.lp.engine.acceptNullable
import com.eignex.klause.lp.engine.certifiedTightObjectiveLowerBound
import com.eignex.klause.lp.engine.certifyLpFarkas
import com.eignex.klause.lp.engine.checkedLpConflict
import com.eignex.klause.lp.engine.checkedLpWitness
import com.eignex.klause.lp.engine.exactShift
import com.eignex.klause.lp.engine.integerCertify
import com.eignex.klause.lp.engine.lpConditioning
import com.eignex.klause.lp.engine.newPersistentLpSolver
import com.eignex.klause.lp.engine.newTableauCutSolver
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.LpExplanation
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.simplex.exact.RationalFeasibility
import com.eignex.klause.simplex.exact.rationalOutcome
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LpRoute
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.Int128
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.addExact
import com.eignex.klause.util.mulExact
import com.eignex.klause.util.subExact
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.round

/**
 * Sound lower bound on a [LinearObjective] given the current partial assignment in
 * [session]. Pinned vars contribute their exact value; unpinned bool vars take the
 * weight (or 0) that makes their contribution smallest; unpinned int vars take the
 * domain endpoint matching the coefficient's sign.
 */
internal fun LpEngine.linearLowerBound(obj: LinearObjective, session: PropagationSession): Long = try {
    var total = addExact(obj.constant, boolLowerBoundPart(obj, session))
    val sp = session.problem
    val ni = minOf(sp.numIntVars, obj.intCoefficients.size)
    for (i in 0 until ni) {
        val c = obj.intCoefficients[i]
        if (c == 0L) continue
        val d = session.intDomain(i)
        total = addExact(total, mulExact(c, if (c >= 0L) d.min else d.max))
    }
    total
} catch (_: CheckedLongOverflowException) {
    // A wrapped accumulation could overshoot the incumbent and prune wrongly; no bound is the
    // sound fallback.
    Long.MIN_VALUE
}

/**
 * The `Σ_b contribution(b)` bool part of [linearLowerBound]. Maintained incrementally on the reversible
 * trail once installed at the root — an O(1) read instead of the O(numBoolVars) rescan that dominated
 * CPU on large pseudo-Boolean optimization; identical value either way. Before the root install is
 * possible (not at level 0), it rescans directly. Throws [CheckedLongOverflowException] via [addExact] on the
 * rescan path so the caller falls back to no bound.
 */
private fun LpEngine.boolLowerBoundPart(obj: LinearObjective, session: PropagationSession): Long {
    if (session.installObjectiveBoolBound(obj.boolWeights)) return session.objectiveBoolLowerBound()
    var sum = 0L
    val nb = minOf(session.problem.numBoolVars, obj.boolWeights.size)
    for (b in 0 until nb) {
        val w = obj.boolWeights[b]
        val v = session.boolValue(b)
        sum = addExact(
            sum,
            when {
                v == true -> w
                v == false -> 0L
                w < 0L -> w
                else -> 0L
            },
        )
    }
    return sum
}

/** The smallest value `≥ lb` congruent to `r` modulo `g` (`g ≥ 1`, `0 ≤ r < g`). When `lb` already
 *  has residue `r` it is returned unchanged. */
internal fun roundUpToResidue(lb: Long, g: Long, r: Long): Long = lb + (r - lb).mod(g)

/** A budgeted [TableauCutSolver] over [model]. The simplex always uses Devex pricing, the Harris
 *  two-pass ratio test, the bound-flipping long step and basis equilibration — all correctness-neutral
 *  (they change only the pivot path / conditioning, never the certified optimum). */
internal fun LpEngine.dualSimplex(model: LpModel, cancellation: Cancellation): TableauCutSolver {
    requireOpen()
    return newTableauCutSolver(
        model,
        cancellation,
        iterationLimit = nodePivotBudget(),
        workLimit = nodeWorkBudget(),
        trackDegeneracy = adaptiveWork,
        factory = solveContext.engineFactory,
    )
}

/**
 * The node bound's engine and its solve.
 *
 * A rebound relaxation shares its matrix and objective with the last node's, so the kept engine's basis
 * and LU factorization are still valid and the dual simplex resumes from them — the expensive half of a
 * node solve, skipped. Anything else (the first node, a rebuilt or cut-augmented relaxation) builds a
 * fresh engine, which then becomes the kept one; [PersistentLpSolver.rebind] decides which case this is
 * and cannot mistake them, since it tests object identity of the matrix and cost arrays.
 *
 * [warm] is used only on the fresh path: a kept engine already has that basis seated, and better, has it
 * factorized.
 */
@Suppress("TooGenericExceptionCaught") // replacement cleanup must preserve arbitrary solve and close failures
private fun LpEngine.solveNode(
    model: LpModel,
    warm: Basis?,
    cancellation: Cancellation,
): Pair<LpSolver, FloatLpResult?> {
    nodeSimplex?.let { kept ->
        if (kept.rebind(model, cancellation)) return kept to kept.resolveBounds()
    }
    val fresh = newPersistentLpSolver(
        model,
        cancellation,
        iterationLimit = nodePivotBudget(),
        workLimit = nodeWorkBudget(),
        trackDegeneracy = adaptiveWork,
        factory = solveContext.engineFactory,
    )
    val displaced = nodeSimplex
    try {
        displaced?.close()
    } catch (closeFailure: Throwable) {
        nodeSimplex = null
        try {
            fresh.close()
        } catch (freshCloseFailure: Throwable) {
            closeFailure.addSuppressed(freshCloseFailure)
        }
        throw closeFailure
    }
    nodeSimplex = fresh
    return try {
        fresh to fresh.solve(warm)
    } catch (failure: Throwable) {
        nodeSimplex = null
        try {
            fresh.close()
        } catch (closeFailure: Throwable) {
            failure.addSuppressed(closeFailure)
        }
        throw failure
    }
}

/**
 * Charge one counted solve's cost to [SolveStatsSink], reading it off the engine rather than the result.
 *
 * Every `observeSolve` pairs with exactly one of these, so the per-solve rates divide counts that cover
 * the same set of solves. Taking the numbers from a [FloatLpResult] cannot do that: a dual-unbounded or
 * non-convergent solve has no result to read, and on a model that prunes often those are most of them.
 */
private fun LpEngine.observeSolveCost(sink: SolveStatsSink, solver: LpSolver) {
    sink.lp.observeEngineCost(LpRoute.NODE, solver.lastMetrics)
    noteSolveOps(solver.lastWorkOps)
}

/** Outcome of one node LP pass: whether to prune, the basis to warm-start children from, and an
 *  optional learned nogood (the sparse path is reason-less, so it is null). */
internal class LpNodeOutcome(val prune: Boolean, val basis: Basis?, val explanation: IntArray? = null)

/**
 * Bounded, deduplicating buffer of LP-learned nogoods awaiting registration at the next restart.
 * Dedup is by sorted-literal key so a region pruned repeatedly is learned once; the cap bounds memory
 * between restarts. [drain] returns and clears the pending batch but keeps the seen-set so a flushed
 * clause is not re-queued.
 */
internal class LpNogoodPool(private val cap: Int = 4096) {
    private val seen = HashSet<String>()
    private val pending = ArrayList<IntArray>()

    fun add(nogood: IntArray) {
        if (nogood.isEmpty() || seen.size >= cap) return
        val key = nogood.sorted().joinToString(",")
        if (seen.add(key)) pending.add(nogood)
    }

    fun drain(): List<IntArray> {
        if (pending.isEmpty()) return emptyList()
        val out = ArrayList(pending)
        pending.clear()
        return out
    }
}

/**
 * LP-relaxation bounding, reduced-cost fixing and infeasibility pruning for one search node,
 * over the sparse revised-simplex pipeline (the only LP path). Builds the relaxation, solves it in
 * float, and prunes when it is infeasible (exact Farkas certificate) or its safe objective bound
 * reaches the incumbent; also propagates the objective variable and fixes reduced-cost-dominated
 * variables. Determinant overflow during the relaxation build keeps the node soundly.
 */
@Suppress("LongParameterList")
internal fun LpEngine.lpBoundAndFix(
    relaxer: CpToLpRelaxation,
    session: PropagationSession,
    bound: Double,
    sink: SolveStatsSink,
    objectiveVar: Int,
    objectiveAscending: Boolean,
    cancellation: Cancellation,
    hints: LpHintSink? = null,
    learn: Boolean = false,
    warm: Basis? = null,
    cutsAllowed: Boolean = false,
): LpNodeOutcome = try {
    sink.lp.observeNodePass()
    sink.lp.clockStart()
    sparseSafePrune(
        relaxer, session, bound, sink, cancellation, objectiveVar, objectiveAscending, hints, learn, warm,
        cutsAllowed,
    )
} catch (_: CheckedLongOverflowException) {
    // A coefficient overflow in the relaxation build loses the bound; recover a sound one via the
    // integer-multiplier 128-bit certification. A failure just keeps the node.
    sparseCertifiedPrune(relaxer, session, bound, sink, cancellation)
} finally {
    sink.lp.clockStop()
}

/**
 * Fold the pooled global cuts the LP point [res] violates into the node relaxation and re-solve.
 * [CutPool.select] ranks the pool by efficacy (normalised violation) at [res], drops the
 * cuts the point already satisfies, and keeps a mutually-orthogonal subset — so only cuts that actually
 * move this point are loaded, bounding the per-node cut count by efficacy rather than the whole pool.
 * Returns the tightened `(relaxation, result)` when a cut subset re-solves, else [base]/[res] unchanged
 * (empty pool, nothing violated, an overflowing build, or a failed re-solve). Sound: the selected cuts
 * are a subset of the globally-valid pool, so the augmented relaxation excludes no feasible point.
 */
private fun LpEngine.foldSelectedCuts(
    relaxer: CpToLpRelaxation,
    session: PropagationSession,
    base: LpRelaxation,
    res: FloatLpResult,
    cancellation: Cancellation,
    sink: SolveStatsSink,
): Pair<LpRelaxation, FloatLpResult> {
    if (cutPool.size == 0) return base to res
    cutPool.observe(res.primal)
    cutPool.retainMostActive()
    val selected = cutPool.select(res.primal, objectiveCoefficients(base.model), cutPool.maxCuts)
    if (selected.isEmpty()) return base to res
    val tightened = try {
        sink.lp.observeCutBuild(selected.size) { relaxer.build(session, selected) }
    } catch (_: CheckedLongOverflowException) {
        return base to res // overflow in the cut-augmented build: keep the prior (sound) relaxation
    }
    val cutSimplex = dualSimplex(tightened.model, cancellation)
    val r = try {
        cutSimplex.solve()
    } finally {
        sink.lp.observeSolve()
        try {
            observeSolveCost(sink, cutSimplex)
        } finally {
            cutSimplex.close()
        }
    }
    if (r == null) return base to res
    return tightened to r
}

/**
 * Cheap sound prune + objective-bound propagation for the LP path: float revised simplex for the duals,
 * then the tighter of the Neumaier–Shcherbina safe bound and the certificate's integer-multiplier one
 * ([certifiedTightObjectiveLowerBound]) — both O(nnz), so the per-node cost is bounded and `-t` is honored.
 * Prunes when the relaxation is infeasible (exact Farkas certificate) or that bound reaches the
 * incumbent, tightens an ascending objective variable up to its ceiling (reason-less, a sound
 * conflict-analysis leaf), and fixes reduced-cost-dominated variables off their bounds. Any solver
 * failure keeps the node.
 */
@Suppress("LongParameterList")
internal fun LpEngine.sparseSafePrune(
    relaxer: CpToLpRelaxation,
    session: PropagationSession,
    bound: Double,
    sink: SolveStatsSink,
    cancellation: Cancellation,
    objectiveVar: Int,
    objectiveAscending: Boolean,
    hints: LpHintSink? = null,
    learn: Boolean = false,
    warm: Basis? = null,
    cutsAllowed: Boolean = false,
): LpNodeOutcome {
    // Gated residual fast path (pure-real satisfaction models): re-solve the persistent, structurally
    // node-invariant gated model with its kept LU factorization — a feasible node (the common case
    // along a dive) costs a few dual pivots instead of a fresh build + factorization. Any other
    // outcome (candidate infeasibility, cancellation, a bailed solve) falls through to the per-node
    // pin-consulting build below, whose certificates and magnitudes are unchanged. Sound as a filter:
    // the gated rows' inactive forms are implied by the box, so the gated and active models have the
    // same feasible set, and a feasible verdict deduces nothing the sat path needs beyond "keep" —
    // which is why the path is taken only when there is no incumbent to prune against and no
    // objective to propagate (the satisfaction check).
    val satisfactionOnly = !bound.isFinite() && !(objectiveVar >= 0 && objectiveAscending)
    if (satisfactionOnly) {
        gatedResidual(session)?.let { filter ->
            // Unchanged enforcement after a feasible solve: the gated model is a pure function of the
            // enforcement, so this node's verdict is the memoized one — no solve at all.
            if (filter.lastFeasible && filter.enforced.contentEquals(filter.lastEnforced)) {
                return LpNodeOutcome(false, null)
            }
            filter.lastFeasible = false
            val gatedModel = filter.relaxation.model
            sink.lp.observeSolve()
            val gatedDv = gatedModel.doubleView
            var gatedStrictSaved: DoubleArray? = null
            if (gatedDv != null && gatedModel.rowStrict.any { it }) {
                gatedStrictSaved = gatedDv.rhs.copyOf()
                for (i in 0 until gatedModel.m) {
                    if (gatedModel.rowStrict[i]) gatedDv.rhs[i] -= STRICT_FILTER_EPS * (1.0 + abs(gatedDv.rhs[i]))
                }
            }
            val gatedResult = try {
                filter.simplex.resolveGated(filter.enforced)
            } finally {
                try {
                    if (gatedStrictSaved != null && gatedDv != null) gatedStrictSaved.copyInto(gatedDv.rhs)
                } finally {
                    observeSolveCost(sink, filter.simplex)
                }
            }
            if (gatedResult != null) {
                filter.enforced.copyInto(filter.lastEnforced)
                filter.lastFeasible = true
                return LpNodeOutcome(false, null)
            }
            // Certify the candidate infeasibility on the gated model directly: restricted to the enforced
            // rows (zeroing the rest just picks another candidate ray — the certificate is checked
            // exactly), a Farkas proof here IS one over the active submodel, so the common refutation
            // prunes without ever building the per-node model. Strictness-only conflicts (no non-strict
            // certificate exists) still fall through to the exact rational path below.
            val gatedRay = filter.simplex.infeasibleRay
            if (gatedRay != null) {
                for (i in 0 until gatedModel.m) if (!filter.enforced[i]) gatedRay[i] = 0.0
                val ray = solveContext.certificationPolicy.acceptNullable(
                    LpCertifier.EXACT_FARKAS,
                    certifyLpFarkas(gatedModel, gatedRay, onRoute = {
                        sink.lp.observeFarkasRoute(
                            it == FarkasRoute.RECONSTRUCTED,
                            it == FarkasRoute.EXACT_BASIS,
                            it == FarkasRoute.ROUNDED,
                            it == FarkasRoute.NONE,
                        )
                    }, observer = sink.lp.certificationObserver(LpRoute.NODE)),
                )
                if (ray != null) {
                    sink.lp.observeInfeasiblePrune()
                    val clause = if (learn) {
                        LpExplanation.infeasibilityClause(filter.relaxation, ray, session)
                    } else {
                        null
                    }
                    return LpNodeOutcome(true, null, clause)
                }
            }
        }
    }
    val relaxation = nodeRelaxation(relaxer, session)
    if (relaxation.model.n == 0) return LpNodeOutcome(false, null)
    sink.lp.observeSolve()
    val model = relaxation.model
    // Measured only at the root: the pass is O(nnz), and the matrix a node solves is the root's, so a
    // deeper reading would repeat it for the same answer.
    if (session.decisionLevel == 0) {
        val spread = lpConditioning(model)
        sink.lp.observeRootConditioning(
            session.decisionLevel,
            spread.minValue,
            spread.maxValue,
            spread.rowRatio,
        )
    }
    // The float LP relaxes strict rows to non-strict, so a node infeasible only through strictness
    // looks feasible here and survives to an expensive leaf. Perturb each strict row's rhs inward by a
    // small relative epsilon for the float solve only — a heuristic filter, restored before any
    // certification so every proof is against the asserted model. A perturbed-infeasible node that the
    // integer Farkas certificate cannot confirm (strictness carries no non-strict certificate) is
    // decided by the exact strict-aware rational simplex.
    val dv = model.doubleView
    var strictSaved: DoubleArray? = null
    if (dv != null && model.rowStrict.any { it }) {
        strictSaved = dv.rhs.copyOf()
        for (i in 0 until model.m) if (model.rowStrict[i]) dv.rhs[i] -= STRICT_FILTER_EPS * (1.0 + abs(dv.rhs[i]))
    }
    // Always solve: an infeasible relaxation prunes the node regardless of incumbent or objective.
    val (simplex, floatResult) = try {
        solveNode(model, warm, cancellation)
    } finally {
        if (strictSaved != null && dv != null) strictSaved.copyInto(dv.rhs)
    }
    // Read the cost off the solver rather than the result: a solve that terminates dual-unbounded
    // returns none, and those are the solves that prune — costing only the ones that return a result
    // would drop the most valuable work from the average.
    observeSolveCost(sink, simplex)
    // Feed the budget from the solve itself. A solve that produced no result at all was infeasible or
    // bailed numerically, which says nothing about how much budget the next one deserves.
    floatResult?.let {
        observeNodeWork(it.optimal, simplex.lastDegenerateColumns, simplex.lastColumns, model.m)
    }
    val result = floatResult ?: run {
        if (lpCounterResults.read(model, solveContext.certificationPolicy)?.witness != null) {
            return LpNodeOutcome(false, null)
        }
        // Infeasibility prune: a dual-unbounded termination is only a *candidate* infeasibility —
        // confirm it with an exact Farkas certificate before pruning (the float ray alone is not sound).
        // Any other failure (non-convergence / singular) keeps the node.
        val floatRay = simplex.infeasibleRay
        val ray = if (floatRay != null) {
            solveContext.certificationPolicy.acceptNullable(
                LpCertifier.EXACT_FARKAS,
                certifyLpFarkas(model, floatRay, onRoute = {
                    sink.lp.observeFarkasRoute(
                        it == FarkasRoute.RECONSTRUCTED,
                        it == FarkasRoute.EXACT_BASIS,
                        it == FarkasRoute.ROUNDED,
                        it == FarkasRoute.NONE,
                    )
                }, observer = sink.lp.certificationObserver(LpRoute.NODE)),
            )
        } else {
            null
        }
        if (ray != null) {
            sink.lp.observeInfeasiblePrune()
            // With learning, the Farkas ray becomes a bound-atom nogood for a 1UIP backjump;
            // null (auxiliary column / unbacked non-global row / constraint-only) prunes reason-less.
            val clause = if (learn) LpExplanation.infeasibilityClause(relaxation, ray, session) else null
            return LpNodeOutcome(true, null, clause)
        }
        if (strictSaved != null && !cancellation()) {
            val outcome = rationalOutcome(model, cancellation).also {
                sink.lp.certificationObserver(LpRoute.NODE).observe(
                    LpCertifier.RATIONAL,
                    it.feasibility != RationalFeasibility.UNKNOWN,
                )
            }
            val acceptedOutcome = solveContext.certificationPolicy.acceptNullable(
                LpCertifier.RATIONAL,
                outcome.takeIf { it.feasibility != RationalFeasibility.UNKNOWN },
            )
            if (acceptedOutcome?.feasibility == RationalFeasibility.FEASIBLE) {
                val witness = acceptedOutcome.exactWitness?.let { shifted ->
                    checkedLpWitness(model, shifted.mapIndexed { j, value -> value + model.exactShift(j) })
                }
                if (witness != null) {
                    lpCounterResults.remember(
                        model,
                        CertifiedLpResult(null, null, witness, null, null, false, { null }),
                        solveContext.certificationPolicy,
                    )
                }
            }
            if (acceptedOutcome?.feasibility == RationalFeasibility.INFEASIBLE &&
                acceptedOutcome.conflict?.let { checkedLpConflict(model, it) } == true
            ) {
                sink.lp.observeInfeasiblePrune()
                // No integer ray exists for a strictness-only conflict; cite the rational decider's
                // load-bearing rows (their premises plus touched integer bound atoms), falling back to
                // every active row's premises when the row set is unavailable.
                val clause = if (learn) {
                    acceptedOutcome.rows?.let { LpExplanation.premiseClauseForRows(relaxation, it, session) }
                        ?: activePremiseClause(relaxation, session)
                } else {
                    null
                }
                return LpNodeOutcome(true, null, clause)
            }
        }
        return LpNodeOutcome(false, null)
    }
    sink.lp.observeLuFill(result.luMaxFill, result.luMaxDensity)
    // LP-guided branching: record the fractional primal + reduced costs so the descent can order
    // branch values toward the LP point and pick reduced-cost-impactful fractional variables. Purely
    // advisory — it never changes feasibility or the optimum.
    hints?.record(relaxation, result.primal, result.duals)
    // The optimal basis is cached by the caller and reused to warm-start this node's children.
    // It is the basis of the un-tightened persistent relaxation, which the children re-solve.
    val optimalBasis = result.basis
    val canPrune = bound.isFinite()
    val canPropagate = objectiveVar >= 0 && objectiveAscending
    if (!canPrune && !canPropagate) {
        return LpNodeOutcome(false, optimalBasis) // feasible, nothing more to deduce
    }
    // During-search separation: at a gated shallow node, tighten this node's relaxation with the
    // cuts its LP point violates. Global cuts are persisted into the pool (descendants inherit them);
    // node-local cuts tighten only this solve, so they never leak to a sibling and the bound stays sound.
    // The bound, certificate and reduced-cost fixing below read the tightened relaxation; the cached
    // warm-start basis stays the cut-free one for the children.
    // The pooled global cuts this node's LP point violates are folded in first: the
    // most-effective, mutually-orthogonal subset chosen by CutPool.select, re-solved once. A subset of
    // globally-valid cuts only tightens the bound, and selecting against the live point loads just the
    // cuts that move it — bounding the per-node cut count by efficacy instead of the whole pool.
    val (cutRel, cutRes) = foldSelectedCuts(relaxer, session, relaxation, result, cancellation, sink)
    var boundRel = cutRel
    var boundRes = cutRes
    if (cutsAllowed && session.decisionLevel in 1..params.lpPlan.cutSearchMaxDepth &&
        lpSeparators.isNotEmpty()
    ) {
        val localCuts = ArrayList<Cut>()
        var rounds = 0
        while (rounds++ < SEARCH_CUT_ROUNDS && !cancellation()) {
            val ctx = CutContext(problem, boundRel, boundRes.primal, session)
            // Per-separator gating: skip a family the gate has disabled for being unproductive, and
            // credit each family it does run with whether it produced a violated cut this round.
            val fresh = ArrayList<Cut>()
            for (i in lpSeparators.indices) {
                if (!lpSeparatorGate.shouldRun(i)) continue
                val produced = lpSeparators[i].separate(ctx)
                lpSeparatorGate.record(i, produced.isNotEmpty())
                fresh.addAll(produced)
            }
            sink.lp.observeCutAccounting(fresh.size, 0, 0)
            if (fresh.isEmpty()) break
            recordSearchCuts(fresh, boundRes.primal) // persist the global cuts into the pool
            for (c in fresh) if (!c.global) localCuts.add(c)
            val selectedCuts = cutPool.select(
                boundRes.primal,
                objectiveCoefficients(boundRel.model),
                cutPool.maxCuts,
            ) + localCuts
            val tightened = try {
                sink.lp.observeCutBuild(selectedCuts.size) { relaxer.build(session, selectedCuts) }
            } catch (_: CheckedLongOverflowException) {
                break // overflow in the cut-augmented build: keep the prior (sound) relaxation
            }
            val roundSimplex = dualSimplex(tightened.model, cancellation)
            val r = try {
                roundSimplex.solve()
            } finally {
                sink.lp.observeSolve()
                try {
                    observeSolveCost(sink, roundSimplex)
                } finally {
                    roundSimplex.close()
                }
            }
            if (r == null) break
            boundRel = tightened
            boundRes = r
        }
    }
    // The exact basis-certificate backs the prune bound, the learnable objective-bound reason and the
    // reduced-cost fixing. Compute it once when any of them needs it; a singular/unbounded certify
    // yields null and each falls back to its cheap certificate-less path, which is sound.
    val cert = if ((learn && canPropagate) || canPrune) {
        solveContext.certificationPolicy.acceptNullable(
            LpCertifier.INTEGER,
            integerCertify(boundRel.model, boundRes.duals, observer = sink.lp.certificationObserver(LpRoute.NODE)),
        ).also {
            // The node path is where certification actually happens; the certified wrapper is not on it.
            sink.lp.observeCertification(
                certified = it != null,
                continuousDecline = it == null && boundRel.model.hasContinuous,
                numericDecline = it == null && !boundRel.model.hasContinuous,
                rationalFallback = false,
                rows = boundRel.model.m,
            )
        }
    } else {
        null
    }
    // Neither the float safe bound nor the certificate's integer-multiplier bound dominates the other,
    // so the prune decides on the tighter of the two rather than on the float bound alone.
    val lower = certifiedTightObjectiveLowerBound(
        boundRel.model,
        boundRes.duals,
        cert,
        sink.lp.certificationObserver(LpRoute.NODE),
        solveContext.certificationPolicy,
    )
        ?: return LpNodeOutcome(false, optimalBasis)
    val full = lower + boundRel.objectiveConstant.toDouble()
    if (canPrune && full >= bound) {
        sink.lp.observePrune()
        return LpNodeOutcome(true, null)
    }
    // Objective-bound propagation: the integer objective is ≥ ceil(LP lower bound). With learning,
    // propagate the exact certified bound — the only one the reduced-cost reason proves — and attach that
    // reason so an Unsat tightening backjumps; otherwise tighten to ceil of the combined bound,
    // reason-less (a sound conflict-analysis leaf). Every bound here only under-estimates the optimum,
    // so either floor ≤ the true optimum.
    if (canPropagate && full.isFinite()) {
        val exactFloor = if (learn && cert != null) {
            cert.objectiveBoundCeil(boundRel.objectiveConstant)
        } else {
            null
        }
        val lpFloor = exactFloor ?: ceil(full).takeIf { it in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble() }
            ?.toLong()
        // Round the bound up to the objective variable's achievable residue (`v ≡ r mod g` from its
        // defining equality): a tighter, still-sound cutoff. A strict lift cannot be witnessed by the
        // reduced-cost reason (the modular premise is not in it), so it is imposed reason-less — a sound
        // conflict-analysis leaf — while an unchanged bound keeps the certified reason.
        val mod = objectiveModulus?.takeIf { it.first == objectiveVar }
        val rounded = if (lpFloor != null && mod != null) {
            roundUpToResidue(lpFloor, mod.second, mod.third)
        } else {
            lpFloor
        }
        if (rounded != null) {
            val reason = if (learn && cert != null && rounded == lpFloor) {
                LpExplanation.objectiveBoundReason(boundRel, cert, session)
            } else {
                null
            }
            val res = if (reason != null) {
                session.implyIntAtLeastWithReason(objectiveVar, rounded, reason)
            } else {
                session.implyIntAtLeast(objectiveVar, rounded)
            }
            if (res is PropagationResult.Unsat) {
                sink.lp.observePrune()
                return LpNodeOutcome(true, null)
            }
        }
    }
    // Reduced-cost fixing on the exact certified Lagrangian and reduced costs needs a finite incumbent
    // for the improving gap, but not an attained LP optimum: the certificate inequality holds for any
    // rounded dual vector.
    if (canPrune && cert != null &&
        applySparseReducedCostFixing(
            boundRel,
            cert,
            session,
            bound,
            sink,
            objectiveVar,
            objectiveAscending,
            learn,
        )
    ) {
        return LpNodeOutcome(true, null)
    }
    return LpNodeOutcome(false, optimalBasis)
}

/**
 * Reduced-cost fixing from one [IntegerCertificate], over exact scaled integers. For any column with
 * nonzero reduced cost, moving Δ integer steps from that certificate term's minimizing endpoint raises
 * its Lagrangian by `|reducedCost|·Δ`. Any incumbent-beating solution has source objective
 * `≤ ⌈bound⌉ − 1`, so the column can move at most the exact unrounded certificate gap divided by
 * `|reducedCost|`. With [learn] each integer fixing carries the LP dual-decomposition reason (the other
 * support columns' seated bounds + the incumbent bound + any dual-weighted non-global row's premises);
 * when the reason is inexpressible the fixing falls back to a reason-less level-local tightening (a
 * conflict-analysis leaf). Returns true if a reduction empties a domain (the node is then pruned).
 */
@Suppress("LongParameterList", "CyclomaticComplexMethod")
internal fun LpEngine.applySparseReducedCostFixing(
    relaxation: LpRelaxation,
    cert: IntegerCertificate,
    session: PropagationSession,
    bound: Double,
    sink: SolveStatsSink,
    objectiveVar: Int = -1,
    objectiveAscending: Boolean = true,
    learn: Boolean = false,
): Boolean {
    if (!bound.isFinite() || bound <= Long.MIN_VALUE.toDouble() || bound >= Long.MAX_VALUE.toDouble()) return false
    val improvingMax = ceil(bound).toLong() - 1L // greatest integer objective strictly below the incumbent
    val sourceConstant = relaxation.objectiveConstant
    if (!cert.improvingGapNonNegative(improvingMax, sourceConstant)) return false
    val reasonSupport = if (learn && objectiveVar >= 0 && objectiveAscending) {
        reducedCostFixingReasons(relaxation, cert, session, objectiveVar, improvingMax)
    } else {
        null
    }
    val canLearn = reasonSupport != null
    for (col in relaxation.colVarId.indices) {
        val varId = relaxation.colVarId[col]
        if (varId < 0) continue // auxiliary column — no CP variable to fix
        val isBool = relaxation.colIsBool[col]
        if (isBool && session.boolValue(varId) != null) continue
        val liveMin: Long
        val liveMax: Long
        if (isBool) {
            liveMin = 0L
            liveMax = 1L
        } else {
            val d = session.intDomain(varId)
            liveMin = d.min
            liveMax = d.max
        }
        if (liveMin == liveMax) continue
        val sign = cert.reducedCostSign(col)
        val dMax = cert.fixSteps(col, improvingMax, sourceConstant) ?: continue
        val res = when {
            // A positive reduced cost is minimized at the certificate model's lower endpoint.
            sign > 0 -> {
                val hi = try {
                    addExact(relaxation.model.loShift[col], dMax)
                } catch (_: CheckedLongOverflowException) {
                    continue
                }
                if (hi >= liveMax) continue
                when {
                    isBool -> session.implyBool(varId, false)
                    canLearn -> session.implyIntAtMostWithReason(varId, hi, reasonSupport.reasonFor(col))
                    else -> session.implyIntAtMost(varId, hi)
                }
            }

            // A negative reduced cost is minimized at the certificate model's upper endpoint.
            sign < 0 -> {
                val lo = try {
                    val certificateMax = addExact(relaxation.model.loShift[col], relaxation.model.upper[col])
                    subExact(certificateMax, dMax)
                } catch (_: CheckedLongOverflowException) {
                    continue
                }
                if (lo <= liveMin) continue
                when {
                    isBool -> session.implyBool(varId, true)
                    canLearn -> session.implyIntAtLeastWithReason(varId, lo, reasonSupport.reasonFor(col))
                    else -> session.implyIntAtLeast(varId, lo)
                }
            }

            else -> continue
        }
        if (res is PropagationResult.Unsat) {
            sink.lp.observePrune()
            return true
        }
        sink.lp.observeFix()
        if (session.decisionLevel == 0) sink.lp.observeRootReducedCostFixes(1)
    }
    return false
}

/** One certificate's reusable support for local reduced-cost deductions under a named cutoff. */
internal class ReducedCostFixingReasons(
    private val supportCols: IntArray,
    private val supportLits: IntArray,
    private val incumbentLit: Int,
) {
    /** Reason for [col], excluding its own minimizing endpoint because that is the value being bounded. */
    fun reasonFor(col: Int): IntArray {
        val out = IntArrayList(supportCols.size + 1)
        for (k in supportCols.indices) if (supportCols[k] != col) out.add(supportLits[k])
        out.add(incumbentLit)
        return out.toIntArray()
    }
}

/**
 * Build the complete reusable premise set for one certificate: all dual-weighted row premises, every
 * structural column's minimizing endpoint with nonzero reduced cost (including basic columns), and the
 * source-objective cutoff. For a single affine source objective `a·x + c`, the cutoff is converted to
 * the exact variable atom `x ≤ ⌊(improvingMax − c) / a⌋`; that atom must already hold. Null means some
 * premise cannot be named; the deduction may still be applied locally without a reusable reason.
 */
internal fun reducedCostFixingReasons(
    relaxation: LpRelaxation,
    cert: IntegerCertificate,
    session: PropagationSession,
    objectiveVar: Int,
    improvingMax: Long,
): ReducedCostFixingReasons? {
    val objectiveCol = relaxation.intColOf.getOrNull(objectiveVar) ?: return null
    if (objectiveCol !in relaxation.colVarId.indices || relaxation.colVarId[objectiveCol] != objectiveVar ||
        relaxation.colIsBool[objectiveCol]
    ) {
        return null
    }
    val objectiveCoefficient = relaxation.model.cost[objectiveCol]
    if (objectiveCoefficient <= 0L || relaxation.model.cost.indices.any {
            it != objectiveCol && relaxation.model.cost[it] != 0L
        }
    ) {
        return null
    }
    val cutoffNumerator = Int128().also { it.addLong(improvingMax) }
    val sourceConstant = Int128().also { it.addLong(relaxation.objectiveConstant) }
    cutoffNumerator.subtract(sourceConstant)
    val objectiveVarMax = cutoffNumerator.floorDivPositive(objectiveCoefficient) ?: return null
    if (session.intDomain(objectiveVar).max > objectiveVarMax) return null
    val supportCols = IntArrayList()
    val supportLits = IntArrayList()
    val seen = IntHashSet()
    val premLits = IntArrayList()
    if (!LpExplanation.addDualRowPremiseLits(premLits, seen, relaxation, cert, session)) return null
    for (k in 0 until premLits.size) {
        supportCols.add(-1) // row premise: part of every fixing's reason, never excluded
        supportLits.add(premLits[k])
    }
    for (col in relaxation.colVarId.indices) {
        val sign = cert.reducedCostSign(col)
        if (sign == 0) continue
        val lit = LpExplanation.premiseLit(relaxation, session, col, lowerSide = sign > 0)
        if (lit == LpExplanation.PREMISE_AUX) return null
        if (lit == LpExplanation.PREMISE_NONE || !seen.add(lit)) continue
        supportCols.add(col)
        supportLits.add(lit)
    }
    return ReducedCostFixingReasons(
        supportCols.toIntArray(),
        supportLits.toIntArray(),
        session.boundLeLit(objectiveVar, objectiveVarMax, positive = false),
    )
}

/**
 * Sound objective lower bound from the float revised simplex — the tighter of the safe float and
 * integer-multiplier 128-bit bounds ([certifiedTightObjectiveLowerBound]) — used when the cheap safe-bound path
 * overflowed during the relaxation build. Prunes when that bound (plus the relaxation's objective
 * constant) reaches the incumbent. Any failure keeps the node.
 */
internal fun LpEngine.sparseCertifiedPrune(
    relaxer: CpToLpRelaxation,
    session: PropagationSession,
    bound: Double,
    sink: SolveStatsSink,
    cancellation: Cancellation,
): LpNodeOutcome {
    if (!bound.isFinite()) return LpNodeOutcome(false, null) // no incumbent to prune against
    // Cut-free recovery: this path is reached because the cut-augmented build overflowed, and cuts are a
    // common overflow source, so the base relaxation (no cuts) is what yields a sound — if looser — bound.
    val relaxation = nodeRelaxation(relaxer, session)
    if (relaxation.model.n == 0) return LpNodeOutcome(false, null)
    sink.lp.observeSolve()
    val recoverySimplex = dualSimplex(relaxation.model, cancellation)
    val result = try {
        recoverySimplex.solve()
    } finally {
        try {
            observeSolveCost(sink, recoverySimplex)
        } finally {
            recoverySimplex.close()
        }
    }
    if (result == null) return LpNodeOutcome(false, null)
    if (cancellation()) return LpNodeOutcome(false, null) // honor the deadline before the exact certify
    // Both bounds are sound for any duals and neither dominates, so the larger wins: the
    // integer-multiplier bound carries no rounding margin, the float one never declines. A null (neither
    // available) keeps the node.
    val lb = certifiedTightObjectiveLowerBound(
        relaxation.model,
        result.duals,
        sink.lp.certificationObserver(LpRoute.NODE),
        solveContext.certificationPolicy,
    )
        ?: return LpNodeOutcome(false, null)
    val full = lb + relaxation.objectiveConstant.toDouble()
    return if (full >= bound) {
        sink.lp.observePrune()
        LpNodeOutcome(true, null)
    } else {
        LpNodeOutcome(false, null)
    }
}

/**
 * The root-node LP relaxation objective (with the harvested [globalCuts]) on the undecided problem,
 * or NaN when the relaxation is empty / not optimal / overflows — the revised simplex +
 * [certifiedTightObjectiveLowerBound], the same sound bound the per-node prune reports. Solved once before search,
 * so the value is a sound *global* lower bound on the objective — the integrality-gap baseline for `-s`.
 */
internal fun LpEngine.rootLpRelaxationBound(
    relaxer: CpToLpRelaxation,
    globalCuts: List<Cut>,
    cancellation: Cancellation = Cancellation.Never,
): Double = try {
    val relaxation = relaxer.build(PropagationSession(problem), globalCuts)
    if (relaxation.model.n == 0) {
        Double.NaN
    } else {
        val simplex = dualSimplex(relaxation.model, cancellation)
        val result = try {
            simplex.solve()
        } finally {
            try {
                observeRootSolve(simplex)
            } finally {
                simplex.close()
            }
        }
        val lower = result?.let {
            certifiedTightObjectiveLowerBound(
                relaxation.model,
                it.duals,
                rootCertificationObserver(),
                solveContext.certificationPolicy,
            )
        }
        if (lower != null) lower + relaxation.objectiveConstant.toDouble() else Double.NaN
    }
} catch (_: CheckedLongOverflowException) {
    Double.NaN
}

/**
 * The **true** root LP optimum (the float simplex objective, plus the relaxation's objective constant),
 * or NaN when the relaxation is empty / not optimal / overflows. Unlike [rootLpRelaxationBound] this is
 * the raw LP value, not a sound under-estimate — so it reflects how much a hull actually tightens the
 * relaxation, which the sound bound can miss. Used only to compare relaxation variants in
 * [LpEngine.pruneIneffectiveHulls], never as a sound prune bound.
 */
internal fun LpEngine.rootLpObjective(
    relaxer: CpToLpRelaxation,
    cancellation: Cancellation = Cancellation.Never,
): Double = try {
    val relaxation = relaxer.build(PropagationSession(problem))
    if (relaxation.model.n == 0) {
        Double.NaN
    } else {
        val simplex = dualSimplex(relaxation.model, cancellation)
        val result = try {
            simplex.solve()
        } finally {
            try {
                observeRootSolve(simplex)
            } finally {
                simplex.close()
            }
        }
        if (result != null) result.objective + relaxation.objectiveConstant.toDouble() else Double.NaN
    }
} catch (_: CheckedLongOverflowException) {
    Double.NaN
}

/**
 * Harvest a persistent pool of **global** cuts from the root relaxation on the sparse
 * revised-simplex path. Each round solves the (cut-augmented) root LP, separates violated cuts from the
 * LP point, and keeps the fresh ones; because the separation reads the undecided root domains, every
 * harvested cut is valid at *every* solution of the problem, so it is forced [Cut.global] = true and
 * stays sound when applied at any node. Determinant overflow keeps whatever cuts stayed within 64 bits.
 */
@Suppress("LongParameterList", "TooGenericExceptionCaught") // root ownership must preserve arbitrary solver failures
internal fun LpEngine.harvestRootCuts(
    relaxer: CpToLpRelaxation,
    session: PropagationSession,
    separators: List<CutSeparator>,
    gomory: Boolean,
    mir: Boolean,
    cancellation: Cancellation = Cancellation.Never,
): List<Cut> {
    if (session.isUnsatAtRoot) return emptyList()
    if (separators.isEmpty() && !gomory && !mir) return emptyList()
    val pool = CutPool()
    try {
        var relaxation = relaxer.build(session)
        if (relaxation.model.n == 0) return emptyList()
        var simplex = dualSimplex(relaxation.model, cancellation)
        var ownedSimplex: TableauCutSolver? = simplex
        var primaryFailure: Throwable? = null
        try {
            val initial = try {
                simplex.solve()
            } finally {
                observeRootSolve(simplex)
            }
            var result = initial ?: return emptyList()
            var round = 0
            while (round++ < CUT_POOL_ROUNDS && !cancellation()) {
                pool.observe(result.primal)
                val ctx = CutContext(problem, relaxation, result.primal, session)
                // Structural separators read the LP point and factor structure (not the constraint rows), so a
                // cut they separate over the undecided root is valid at every solution — force it global.
                val structural = separators.flatMap { it.separate(ctx) }
                    .map { if (it.global) it else Cut(it.cols, it.coeffs, it.rel, it.rhs, global = true) }
                // Gomory/MIR combine rows; tableauCuts already marks one global iff its row weights avoid every
                // non-global (big-M) row. Only the genuinely-global ones may join the tree-wide pool.
                val gomoryCuts = if (gomory) simplex.gomoryCuts(GOMORY_CUTS_PER_ROUND) else emptyList()
                val mirCuts = if (mir) simplex.mirCuts(GOMORY_CUTS_PER_ROUND) else emptyList()
                val candidates = structural + (gomoryCuts + mirCuts).filter { it.global }
                val added = pool.addAll(candidates)
                observeRootCutAccounting(candidates.size, 0, 0)
                if (added == 0) break
                val selected = pool.cuts()
                relaxation = observeRootCutBuild(selected.size) { relaxer.build(session, selected) }
                val replacement = dualSimplex(relaxation.model, cancellation)
                ownedSimplex = replacement
                simplex.close()
                simplex = replacement
                val next = try {
                    simplex.solve()
                } finally {
                    observeRootSolve(simplex)
                }
                if (next == null) break
                result = next
            }
            // Bound the pool the search nodes inherit by per-cut activity (tightness at the final LP point):
            // a large harvest is trimmed to the most-active cuts, the rest evicted (sound — all global).
            pool.retainMostActive()
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val owned = ownedSimplex
            ownedSimplex = null
            if (primaryFailure == null) {
                owned?.close()
            } else {
                try {
                    owned?.close()
                } catch (closeFailure: Throwable) {
                    primaryFailure.addSuppressed(closeFailure)
                }
            }
        }
    } catch (_: CheckedLongOverflowException) {
        return pool.cuts() // keep whatever stayed within 64-bit determinants — still globally valid
    }
    return pool.cuts()
}

/** Most Gomory cuts to draw from one tableau per separation round. */
internal const val GOMORY_CUTS_PER_ROUND: Int = 8

private fun objectiveCoefficients(model: LpModel): DoubleArray = DoubleArray(model.n) { model.costD(it) }

/** Separation rounds when harvesting the persistent root cut pool. */
internal const val CUT_POOL_ROUNDS: Int = 8

/** Separation rounds per during-search node — fewer than the root harvest, since the node solve
 *  repeats deeper in the tree. */
internal const val SEARCH_CUT_ROUNDS: Int = 4

/** [RootRelaxationSize.cost] ceiling above which the harvest skips its shave/redundancy/equality probes:
 *  on a relaxation this large the per-candidate solves dominate the time budget and lose instances the
 *  search would otherwise solve. Calibrated from an mzn-bench A/B with a wide margin — the helped models
 *  measured ≤ ~48k (evilshop 155×155, the largest gain) while the cost regressions were ≥ ~1.6M
 *  (fast-food 501×1048, diameterc-mst 1797×4066), so the gap is two orders of magnitude. */
internal const val LP_HARVEST_MAX_RELAXATION_COST = 250_000L

/** Inward relative rhs perturbation applied to strict rows for the float filter solve. */
private const val STRICT_FILTER_EPS = 1e-7

/** Every active row's premises as one clause — the activating-literal set is jointly infeasible.
 *  Null when some non-global row has no recorded premise or nothing is cited. */
private fun activePremiseClause(relaxation: LpRelaxation, session: PropagationSession): IntArray? {
    val lits = IntArrayList()
    val seen = IntHashSet()
    val rows = IntArray(relaxation.model.m) { it }
    val ok = LpExplanation.addRowPremiseLits(lits, seen, relaxation, rows, session)
    return if (ok && lits.size > 0) lits.toIntArray() else null
}
