package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.cut.CutContext
import com.eignex.klause.lp.cut.CutPool
import com.eignex.klause.lp.cut.SourceCut
import com.eignex.klause.lp.cut.orNull
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.CertifiedLpResult
import com.eignex.klause.lp.engine.ComponentLpSolverCapability
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.FarkasRoute
import com.eignex.klause.lp.engine.FloatDeductionCertificate
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.FloatNodeView
import com.eignex.klause.lp.engine.LpCertificationObserver
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpCertifierCost
import com.eignex.klause.lp.engine.LpClaim
import com.eignex.klause.lp.engine.LpDeductionCertificate
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPhaseMetrics
import com.eignex.klause.lp.engine.LpSolvePhase
import com.eignex.klause.lp.engine.LpSolver
import com.eignex.klause.lp.engine.MixedCertificateMeter
import com.eignex.klause.lp.engine.TableauCutSolver
import com.eignex.klause.lp.engine.acceptNullable
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.lp.engine.ceilLong
import com.eignex.klause.lp.engine.certifiedTightObjectiveLowerBound
import com.eignex.klause.lp.engine.certifyLpFarkas
import com.eignex.klause.lp.engine.checkedLpConflict
import com.eignex.klause.lp.engine.checkedLpWitness
import com.eignex.klause.lp.engine.exactBits
import com.eignex.klause.lp.engine.floatFarkasRay
import com.eignex.klause.lp.engine.hasIntegralObjective
import com.eignex.klause.lp.engine.lpConditioning
import com.eignex.klause.lp.engine.newComponentTableauCutSolver
import com.eignex.klause.lp.engine.newPersistentLpSolver
import com.eignex.klause.lp.engine.newTableauCutSolver
import com.eignex.klause.lp.engine.nodeDualCertificate
import com.eignex.klause.lp.engine.observeLpClaim
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.LpAssemblyCancelled
import com.eignex.klause.lp.relaxation.LpExplanation
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LpRoute
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.pollStop
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.CheckedLongOverflowException
import com.eignex.util.numbers.addExact
import com.eignex.util.numbers.mulExact
import kotlin.math.abs
import kotlin.math.nextUp

/**
 * Sound lower bound on a [LinearObjective] given the current partial assignment in
 * [session]. Pinned vars contribute their exact value; unpinned bool vars take the
 * weight (or 0) that makes their contribution smallest; unpinned int vars take the
 * domain endpoint matching the coefficient's sign.
 */
internal fun LpEngine.linearLowerBound(obj: LinearObjective, session: PropagationSession): Long = try {
    var total = addExact(obj.constant, boolLowerBoundPart(obj, session))
    val sp = session.problem
    val ni = minOf(sp.numIntVars, obj.intCoefficientValues.size)
    for (i in 0 until ni) {
        val c = obj.intCoefficientValues[i]
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
 * trail once installed at the root — an O(1) read instead of an O(numBoolVars) rescan, which matters on
 * large pseudo-Boolean optimization. Before the root install is possible (not at level 0), it rescans
 * directly. Throws [CheckedLongOverflowException] via [addExact] on the rescan path so the caller falls
 * back to no bound.
 */
private fun LpEngine.boolLowerBoundPart(obj: LinearObjective, session: PropagationSession): Long {
    if (session.installObjectiveBoolBound(obj.boolWeightValues)) return session.objectiveBoolLowerBound()
    var sum = 0L
    val nb = minOf(session.problem.numBoolVars, obj.boolWeightValues.size)
    for (b in 0 until nb) {
        val w = obj.boolWeightValues[b]
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
internal fun LpEngine.dualSimplex(
    model: LpModel,
    cancellation: ExecutionControl,
    workLimit: Long = nodeWorkBudget(),
): TableauCutSolver {
    requireOpen()
    if (params.lpPlan.componentOwnership) return newComponentTableauCutSolver(
        model, cancellation, nodePivotBudget(), workLimit, adaptiveWork, solveContext.engineFactory, pricingOptions,
    )
    return newTableauCutSolver(
        model,
        cancellation,
        iterationLimit = nodePivotBudget(),
        workLimit = workLimit,
        trackDegeneracy = adaptiveWork,
        pricing = pricingOptions,
        factory = solveContext.engineFactory,
    )
}

/*
 * Source nodes use their retained scoped owner. An external model installs separate exact authority;
 * models without that authority use a bounded fresh solve.
 * Warm hints apply only to a fresh solve; a retained scoped owner already has its basis factorized.
 */
// Replacement cleanup must preserve arbitrary solve and close failures.
@Suppress("TooGenericExceptionCaught", "ThrowsCount")
internal fun LpEngine.solveNode(
    model: LpModel,
    warm: Basis?,
    cancellation: ExecutionControl,
    continuationWorkAllowance: (() -> Long?)? = null,
): Pair<LpSolver, FloatLpResult?>? {
    nodeUsesTrail = cpAdapter.currentModel === model
    if (nodeUsesTrail) return propagator.solveFloat(warm, cancellation, continuationWorkAllowance)
    model.authoritativeModel()?.let { exact ->
        nodeUsesTrail = true
        cpAdapter.localModel()
        if (!propagator.install(model, exact)) return null
        return propagator.solveFloat(warm, cancellation, continuationWorkAllowance)
    }
    noteNodeOverhead(model.extent() * LpNodeOverhead.SETUP)
    val fresh = newPersistentLpSolver(
        model,
        cancellation,
        iterationLimit = nodePivotBudget(),
        workLimit = nodeQueryWorkBudget() ?: nodeWorkBudget(),
        trackDegeneracy = adaptiveWork,
        pricing = pricingOptions,
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
    observeSparseTrial(solver)
    noteSolveOps(solver.lastWorkOps)
}

/** Outcome of one node LP pass: whether to prune, the basis to warm-start children from, and an
 *  optional learned nogood (null when learning is off or the prune has no expressible reason). */
internal class LpNodeOutcome(val prune: Boolean, val basis: Basis?, val explanation: IntArray? = null)

/*
 * Bound pending LP clause storage and deduplicate across restarts. Entries retain whether the
 * persistent CP session already contains the clause so restart sharing does not register it twice.
 * Draining clears the pending batch; retained keys prevent a later node from queuing it again.
 */
internal class LpNogoodPool(private val cap: Int = 4096) {
    class Entry(val literals: IntArray, val registered: Boolean)

    private val seen = HashSet<String>()
    private val pending = ArrayList<Entry>()

    fun add(nogood: IntArray, registered: Boolean = false): Boolean {
        if (nogood.isEmpty() || seen.size >= cap) return false
        val key = nogood.sorted().joinToString(",")
        if (!seen.add(key)) return false
        pending.add(Entry(nogood, registered))
        return true
    }

    fun drain(): List<Entry> {
        if (pending.isEmpty()) return emptyList()
        val out = ArrayList(pending)
        pending.clear()
        return out
    }
}

/**
 * LP-relaxation bounding, reduced-cost fixing and infeasibility pruning for one search node,
 * over the sparse revised-simplex pipeline. Builds the relaxation, solves it in float, and prunes when
 * it is infeasible (exact Farkas certificate) or its safe objective bound reaches the incumbent; also
 * propagates the objective variable and fixes reduced-cost-dominated variables. A 64-bit overflow
 * during the relaxation build falls back to [sparseCertifiedPrune].
 */
@Suppress("LongParameterList")
internal fun LpEngine.lpBoundAndFix(
    relaxer: CpToLpRelaxation,
    session: PropagationSession,
    bound: Double,
    sink: SolveStatsSink,
    objectiveVar: Int,
    objectiveAscending: Boolean,
    cancellation: ExecutionControl,
    hints: LpHintSink? = null,
    learn: Boolean = false,
    warm: Basis? = null,
    cutsAllowed: Boolean = false,
): LpNodeOutcome = try {
    beginCutAttribution(LpRoute.NODE)
    sink.lp.cutLifecycle.families = cutPool.cuts().mapTo(HashSet()) { it.family }
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
} catch (_: LpAssemblyCancelled) {
    LpNodeOutcome(false, null)
} finally {
    sink.lp.clockStop()
    sink.lp.cutLifecycle.families = emptySet()
}

/**
 * Fold the pooled global cuts the LP point [res] violates into the node relaxation and re-solve.
 * [CutPool.select] ranks the pool by efficacy (normalised violation) at [res], drops the
 * cuts the point already satisfies, and keeps a mutually-orthogonal subset — so only cuts that actually
 * move this point are loaded, bounding the per-node cut count by efficacy rather than the whole pool.
 * The scoped owner loads selected rows in source coordinates and retains its basis. A failed solve
 * keeps the preceding immutable model and its result; infeasibility requires an exact certificate.
 */
private fun LpEngine.foldSelectedCuts(
    session: PropagationSession,
    base: LpRelaxation,
    res: FloatLpResult,
    cancellation: ExecutionControl,
    sink: SolveStatsSink,
    learn: Boolean,
): LpCutOutcome {
    if (cutPool.size == 0) return LpCutOutcome.Solved(base, res)
    base.sourceMap?.let(cutPool::remap)
    cutPool.observe(res.primal)
    cutPool.retainMostActive()
    val selected = cutPool.select(res.primal, objectiveCoefficients(base.model), cutPool.maxCuts)
    return solveSelectedCuts(session, LpCutOutcome.Solved(base, res), selected, cancellation, sink, learn)
}

private sealed interface LpCutOutcome {
    class Solved(val relaxation: LpRelaxation, val result: FloatLpResult) : LpCutOutcome
    class Pruned(val explanation: IntArray?) : LpCutOutcome
}

private fun LpEngine.solveSelectedCuts(
    session: PropagationSession,
    previous: LpCutOutcome.Solved,
    selected: List<Cut>,
    cancellation: ExecutionControl,
    sink: SolveStatsSink,
    learn: Boolean,
): LpCutOutcome {
    if (selected.isEmpty() || cancellation.pollStop()) return previous
    val base = previous.relaxation
    val families = selected.mapTo(HashSet()) { it.family }
    sink.lp.cutLifecycle.families = sink.lp.cutLifecycle.families + families
    val tightened = sink.lp.observeCutBuild(selected.size) {
        sink.lp.cutLifecycle.measureShared("ASSEMBLY") { cpAdapter.cutRelaxation(base, session, selected) }
    }
        ?: return previous
    activateCutAttribution(tightened)
    if (tightened.model === base.model) return previous
    val attempt = try {
        solveNode(tightened.model, null, cancellation)
    } finally {
        sink.lp.observeSolve()
        sink.lp.observeEngineCost(LpRoute.NODE, propagator.lastMetrics)
        noteSolveOps(propagator.lastMetrics.workOps)
    }
    if (attempt == null || cancellation.pollStop()) return previous
    attempt.second?.let {
        observeCutFill(it.luMaxFill, tightened.model.m)
        return LpCutOutcome.Solved(tightened, it)
    }
    if (lpCounterResults.read(tightened.model, solveContext.certificationPolicy)?.witness != null) return previous
    val floatRay = attempt.first.infeasibleRay ?: return previous
    val ray = infeasibilityRay(tightened.model, floatRay, sink) ?: return previous
    sink.lp.observeInfeasiblePrune()
    observeCutRayUse(tightened, ray)
    LpClaim.CONTRADICTION.consumed(sink.lp.certificationObserver(LpRoute.NODE))
    return LpCutOutcome.Pruned(if (learn) learnedClause(tightened, ray, session, sink) else null)
}

private fun LpHintSink.recordPoint(relaxation: LpRelaxation, result: FloatLpResult) {
    record(relaxation, result.primal, result.duals)
    if (result.optimal) recordObjective(relaxation, result.objective)
}

/**
 * Cheap sound prune + objective-bound propagation for the LP path: float revised simplex for the duals,
 * then the tighter of the Neumaier–Shcherbina safe bound and the certificate's integer-multiplier one
 * ([certifiedTightObjectiveLowerBound]) — both O(nnz), so the per-node cost is bounded and `-t` is honored.
 * Prunes when the relaxation is infeasible (exact Farkas certificate) or that bound reaches the
 * incumbent, tightens an ascending objective variable up to its ceiling (with the reduced-cost reason
 * when learning, otherwise reason-less), and fixes reduced-cost-dominated variables off their bounds.
 * Any solver failure keeps the node.
 *
 * Safe bound: A. Neumaier and O. Shcherbina, "Safe bounds in linear and mixed-integer linear
 * programming", Mathematical Programming 99, 2004. https://doi.org/10.1007/s10107-003-0433-3
 */
@Suppress("LongParameterList")
internal fun LpEngine.sparseSafePrune(
    relaxer: CpToLpRelaxation,
    session: PropagationSession,
    bound: Double,
    sink: SolveStatsSink,
    cancellation: ExecutionControl,
    objectiveVar: Int,
    objectiveAscending: Boolean,
    hints: LpHintSink? = null,
    learn: Boolean = false,
    warm: Basis? = null,
    cutsAllowed: Boolean = false,
): LpNodeOutcome {
    if (cancellation.pollStop()) return LpNodeOutcome(false, null)
    // Gated residual fast path (pure-real satisfaction models): re-solve the persistent, structurally
    // node-invariant gated model with its kept LU factorization — a feasible node (the common case
    // along a dive) costs a few dual pivots instead of a fresh build + factorization. Any other
    // outcome falls through to the per-node build below. Sound as a filter: the gated rows' inactive
    // forms are implied by the box, so the gated and active models have the same feasible set, and a
    // feasible verdict deduces nothing beyond "keep" — hence only when there is no incumbent to prune
    // against and no objective to propagate.
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
            val gatedRay = filter.simplex.infeasibleRay?.copyOf()
            if (gatedRay != null && !cancellation.pollStop()) {
                for (i in 0 until gatedModel.m) if (!filter.enforced[i]) gatedRay[i] = 0.0
                val ray = infeasibilityRay(gatedModel, gatedRay, sink)
                if (ray != null) {
                    sink.lp.observeInfeasiblePrune()
                    LpClaim.CONTRADICTION.consumed(sink.lp.certificationObserver(LpRoute.NODE))
                    hints?.recordInfeasible()
                    val clause = if (learn) {
                        learnedClause(filter.relaxation, ray, session, sink)
                    } else {
                        null
                    }
                    return LpNodeOutcome(true, null, clause)
                }
            }
        }
    }
    val relaxation = nodeRelaxation(relaxer, session) ?: return LpNodeOutcome(false, null)
    activateCutAttribution(relaxation)
    if (cancellation.pollStop()) return LpNodeOutcome(false, null)
    if (relaxation.model.n == 0 && relaxation.model.m == 0 && cutPool.size == 0) return LpNodeOutcome(false, null)
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
    if (!numericalDeductions && model.rowStrict.any { it }) {
        strictSourcePrune(relaxation, session, bound, sink, cancellation, learn)?.let { return it }
    }
    val attempted = solveNode(model, warm, cancellation)
    if (attempted == null) {
        sink.lp.observeEngineCost(LpRoute.NODE, propagator.lastMetrics)
        noteSolveOps(propagator.lastMetrics.workOps)
        observeNodePause()
        return LpNodeOutcome(false, null)
    }
    val (simplex, floatResult) = attempted
    // Read the cost off the solver rather than the result: a solve that terminates dual-unbounded
    // returns none, and those are the solves that prune — costing only the ones that return a result
    // would drop the most valuable work from the average.
    if (nodeUsesTrail) {
        sink.lp.observeEngineCost(LpRoute.NODE, propagator.lastMetrics)
        observeSparseTrial(simplex)
        noteSolveOps(propagator.lastMetrics.workOps)
    } else {
        observeSolveCost(sink, simplex)
    }
    if (cancellation.pollStop()) return LpNodeOutcome(false, null)
    // Candidate points supply degeneracy feedback; a missing point requires an explicit termination policy.
    floatResult?.let {
        observeNodeWork(it.optimal, simplex.lastDegenerateColumns, simplex.lastColumns, model.m)
    }
    if (floatResult == null) observeNodeWorkStop(simplex)
    val result = floatResult ?: run {
        if (pruneWithComponentBound(relaxation, simplex, bound, sink, cancellation)) {
            return LpNodeOutcome(true, null)
        }
        if (lpCounterResults.read(model, solveContext.certificationPolicy)?.witness != null) {
            return LpNodeOutcome(false, null)
        }
        // Infeasibility prune: a dual-unbounded termination is only a *candidate* infeasibility —
        // confirm its ray before pruning ([infeasibilityRay]). Any other failure (non-convergence /
        // singular) keeps the node.
        val ray = simplex.infeasibleRay?.let { infeasibilityRay(model, it, sink) }
        if (ray != null) {
            observeCutRayUse(relaxation, ray)
            sink.lp.observeInfeasiblePrune()
            LpClaim.CONTRADICTION.consumed(sink.lp.certificationObserver(LpRoute.NODE))
            hints?.recordInfeasible()
            // With learning, the Farkas ray becomes a bound-atom nogood for a 1UIP backjump;
            // null (auxiliary column / unbacked non-global row / constraint-only) prunes reason-less.
            val clause = if (learn) learnedClause(relaxation, ray, session, sink) else null
            return LpNodeOutcome(true, null, clause)
        }
        return LpNodeOutcome(false, null)
    }
    if (session.decisionLevel == 0) recordRootQueryBound(relaxation, result)
    sink.lp.observeLuFill(result.luMaxFill, result.luMaxDensity, result.luMaxDim)
    // The scoped owner carries appended-row bases itself; the hint is only for external fresh solves.
    var optimalBasis: Basis? = result.basis
    // Row projections exist only as exact certificates.
    val rowMeter = if (!numericalDeductions && problem.numRealVars > 0 &&
        (problem.numIntVars > 0 || problem.numBoolVars > 0)
    ) MixedCertificateMeter(cancellation) else null
    val canPrune = bound.isFinite()
    val canPropagate = objectiveVar >= 0 && objectiveAscending
    val objectiveCeiling = if (canPrune) null else singleObjectiveCeiling(relaxation, cancellation)
    val canFix = canPrune || objectiveCeiling != null
    if (!canFix && !canPropagate) {
        if (rowMeter != null && applyMixedRowBounds(relaxation, session, sink, learn, cancellation, rowMeter)) {
            return LpNodeOutcome(true, null)
        }
        if (cancellation.pollStop()) return LpNodeOutcome(false, optimalBasis)
        val constant = cutPool.constantContradiction()
        if (constant == null) {
            hints?.recordPoint(relaxation, result)
            return LpNodeOutcome(false, optimalBasis)
        }
        val outcome = solveSelectedCuts(
            session, LpCutOutcome.Solved(relaxation, result), listOf(constant), cancellation, sink, learn,
        )
        if (cancellation.pollStop()) return LpNodeOutcome(false, null)
        return when (outcome) {
            is LpCutOutcome.Pruned -> {
                hints?.recordInfeasible()
                LpNodeOutcome(true, null, outcome.explanation)
            }
            is LpCutOutcome.Solved -> {
                hints?.recordPoint(outcome.relaxation, outcome.result)
                LpNodeOutcome(false, outcome.result.basis)
            }
        }
    }
    // Pooled global cuts the LP point violates are folded in first (see foldSelectedCuts). Then, at a
    // gated shallow node, separation tightens the relaxation further: global cuts are persisted into
    // the pool (descendants inherit them); node-local cuts retain their complete source proof and
    // follow the owner's row trail. The bound, certificate and reduced-cost fixing below read the
    // tightened immutable relaxation.
    val cutOutcome = foldSelectedCuts(session, relaxation, result, cancellation, sink, learn)
    if (cutOutcome is LpCutOutcome.Pruned) {
        hints?.recordInfeasible()
        return LpNodeOutcome(true, null, cutOutcome.explanation)
    }
    val cutSolved = cutOutcome as LpCutOutcome.Solved
    if (cancellation.pollStop()) return LpNodeOutcome(false, null)
    var boundRel = cutSolved.relaxation
    var boundRes = cutSolved.result
    if (cutsAllowed && session.decisionLevel in 1..params.lpPlan.cutSearchMaxDepth &&
        lpSeparators.isNotEmpty() && cutSeparationSupported(boundRel, cancellation, root = false)
    ) {
        val localCuts = ArrayList<SourceCut>()
        var rounds = 0
        while (rounds++ < SEARCH_CUT_ROUNDS && !cancellation.pollStop()) {
            val ctx = CutContext(problem, boundRel, boundRes.primal, session)
            // Per-separator gating: skip a family the gate has disabled for being unproductive, and
            // credit each family it does run with whether it produced a violated cut this round.
            val fresh = ArrayList<Cut>()
            for (i in lpSeparators.indices) {
                if (!lpSeparatorGate.shouldRun(i)) {
                    sink.lp.cutLifecycle.event(lpSeparators[i].family, "APPLICABILITY", "GATED")
                    continue
                }
                val produced = constructCuts(lpSeparators[i], ctx)
                lpSeparatorGate.record(i, produced.isNotEmpty())
                fresh.addAll(produced)
            }
            sink.lp.observeCutAccounting(fresh.size, 0, 0)
            if (fresh.isEmpty()) break
            recordSearchCuts(fresh, boundRes.primal, boundRel, session)
            for (c in fresh) {
                if (!c.global) {
                    SourceCut.fromCut(c, boundRel).orNull()?.let(localCuts::add)
                }
            }
            val selectedCuts = cutPool.select(
                boundRes.primal,
                objectiveCoefficients(boundRel.model),
                cutPool.maxCuts,
            ) + localCuts.mapNotNull { source -> boundRel.sourceMap?.let { source.toCut(it).orNull() } }
            val previous = LpCutOutcome.Solved(boundRel, boundRes)
            val outcome = solveSelectedCuts(session, previous, selectedCuts, cancellation, sink, learn)
            when (outcome) {
                is LpCutOutcome.Pruned -> {
                    hints?.recordInfeasible()
                    return LpNodeOutcome(true, null, outcome.explanation)
                }
                is LpCutOutcome.Solved -> {
                    if (outcome.relaxation.model === boundRel.model) break
                    boundRel = outcome.relaxation
                    boundRes = outcome.result
                }
            }
        }
    }
    // The certificate backs the prune bound, the learnable objective-bound reason and the reduced-cost fixing.
    // An exact one is computed once when any of them needs it; a singular/unbounded certify yields null and
    // each falls back to its cheap certificate-less path, which is sound. A numerical one is the bound itself.
    if (cancellation.pollStop()) return LpNodeOutcome(false, null)
    hints?.recordPoint(boundRel, boundRes)
    if (cancellation.pollStop()) return LpNodeOutcome(false, null)
    activateCutAttribution(boundRel)
    val additionalCertificates = ArrayList<LpDeductionCertificate>(2)
    val nodeView = if (numericalDeductions) FloatNodeView(boundRel.model) else null
    val numerical = nodeView?.let {
        FloatDeductionCertificate.of(boundRel.model, boundRes.duals, sink.lp.certificationObserver(LpRoute.NODE), it)
    }
    val cert = if (numericalDeductions) {
        // The objective box and half the LP duals fix columns the LP duals leave free, as the exact alternatives do.
        if (canFix) for (share in doubleArrayOf(0.0, 0.5)) {
            val duals = DoubleArray(boundRes.duals.size) { boundRes.duals[it] * share }
            FloatDeductionCertificate.of(boundRel.model, duals, node = checkNotNull(nodeView))
                ?.let(additionalCertificates::add)
        }
        numerical
    } else if ((learn && canPropagate) || canFix) {
        val observer = sink.lp.certificationObserver(LpRoute.NODE)
        val componentObserver = object : LpCertificationObserver by observer {
            override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) {
                observer.observe(certifier, success, cost)
                if (cost is LpCertifierCost.Metered) noteNodeOverhead(cost.work)
            }
        }
        val component = (simplex as? ComponentLpSolverCapability)?.nodeDeductions(
            boundRes.duals, componentObserver, solveContext.certificationPolicy, cancellation,
            withAlternatives = canFix, sharedMeter = rowMeter,
        )?.takeIf { it.primary.belongsTo(boundRel.model) }
        if (component != null) additionalCertificates.addAll(component.alternatives)
        solveContext.certificationPolicy.acceptNullable(
            LpCertifier.INTEGER,
            component?.primary ?: nodeDualCertificate(
                boundRel.model, boundRes.duals, cancellation, sink.lp.certificationObserver(LpRoute.NODE),
                eliminateContinuousObjective = !canPrune && objectiveCeiling != null,
                onAdditionalCertificate = if (canFix) {
                    { additionalCertificates.add(it) }
                } else null,
                sharedMeter = rowMeter,
                preferredColumns = if (rowMeter != null && canFix && session.decisionLevel > 0) {
                    narrowedRowColumns(boundRel, session, cancellation)
                } else null,
            ),
        ).also {
            // The node path is where certification actually happens; the certified wrapper is not on it.
            sink.lp.observeCertification(
                certified = it != null,
                continuousDecline = it == null && boundRel.model.hasContinuous,
                numericDecline = it == null && !boundRel.model.hasContinuous,
                rationalFallback = it?.usesRationalArithmetic() == true,
                rows = boundRel.model.m,
            )
        }
    } else {
        null
    }
    observeCutUse(boundRel, cert, "BINDING_CERTIFICATE")
    if (cancellation.pollStop()) return LpNodeOutcome(false, null)
    val rationalCutoff = if (canPrune &&
        (cert?.let { permitsIntegralObjectiveCutoff(it) } == true || !boundRel.model.hasIntegralObjective())
    ) {
        BigFraction.ofDouble(bound.nextUp())
    } else {
        null
    }
    if (rationalCutoff != null && cert != null) {
        val lower = cert.exactObjectiveBound()
        val words = (maxOf(Long.SIZE_BITS, exactBits(listOf(lower, rationalCutoff))) + 63L) / 64L
        noteNodeOverhead(4L * words * words)
        val full = lower + BigFraction.ofLong(boundRel.objectiveConstant)
        val integralCutoff = if (permitsIntegralObjectiveCutoff(cert)) enclosingCutoff(bound)?.ceilLong() else null
        if ((integralCutoff != null && full.ceilLong()?.let { it >= integralCutoff } == true) ||
            full >= rationalCutoff
        ) {
            sink.lp.observePrune()
            observeCutUse(boundRel, cert, "BOUND_PRUNE")
            LpClaim.BOUND.consumed(sink.lp.certificationObserver(LpRoute.NODE))
            return LpNodeOutcome(true, null)
        }
    }
    // Neither the float safe bound nor the certificate's integer-multiplier bound dominates the other,
    // so the prune decides on the tighter of the two rather than on the float bound alone.
    val lower = if (numericalDeductions) numerical?.lowerBound else certifiedTightObjectiveLowerBound(
        boundRel.model.alsoCharged(this),
        boundRes.duals,
        cert,
        sink.lp.certificationObserver(LpRoute.NODE),
        solveContext.certificationPolicy,
    )
    if (lower == null) {
        val prune = rowMeter != null &&
            applyMixedRowBounds(boundRel, session, sink, learn, cancellation, rowMeter, relaxer) { optimalBasis = null }
        return LpNodeOutcome(prune, if (prune) null else optimalBasis)
    }
    val full = checkNotNull(BigFraction.ofDouble(lower)) + BigFraction.ofLong(boundRel.objectiveConstant)
    val sourceRange = sourceObjectiveRange(boundRel)
    val cutoff = if (canPrune) sourceObjectiveCutoff(boundRel, bound, sourceRange) else null
    if (cutoff != null && full >= cutoff) {
        observeAcceptedCutBoundUse(boundRel, cert, boundRes.duals, cutoff, "BOUND_PRUNE")
        sink.lp.observePrune()
        LpClaim.BOUND.consumed(sink.lp.certificationObserver(LpRoute.NODE))
        return LpNodeOutcome(true, null)
    }
    // Objective-bound propagation: the integer objective is ≥ ceil(LP lower bound). With learning,
    // propagate the exact certified bound — the only one the reduced-cost reason proves — and attach that
    // reason so an Unsat tightening backjumps; otherwise tighten to ceil of the combined bound,
    // reason-less (a sound conflict-analysis leaf). Every bound here only under-estimates the optimum,
    // so either floor ≤ the true optimum.
    if (canPropagate && sourceRange != null) {
        val exactFloor = if (learn && cert != null) {
            cert.objectiveBoundCeil(boundRel.objectiveConstant)
        } else {
            null
        }
        val sourceLower = exactFloor?.let(BigFraction::ofLong) ?: full
        val lpFloor = boundRel.objectiveVariableLowerBound(objectiveVar, sourceLower)
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
            val reason = if (learn && cert != null && exactFloor != null && rounded == lpFloor) {
                LpExplanation.objectiveBoundReason(boundRel, cert, session, sink.lp.certificationObserver(LpRoute.NODE))
                    .also { sink.observeReason("OBJECTIVE", it != null) }
            } else {
                null
            }
            val previousLower = session.intDomain(objectiveVar).min
            val res = if (reason != null) {
                session.implyIntAtLeastWithReason(objectiveVar, rounded, reason)
            } else {
                session.implyIntAtLeast(objectiveVar, rounded)
            }
            if (rounded > previousLower) {
                observeAcceptedCutBoundUse(boundRel, cert, boundRes.duals, sourceLower, "OBJECTIVE_PROPAGATION")
            }
            if (res is PropagationResult.Unsat) {
                sink.lp.observePrune()
                LpClaim.BOUND.consumed(sink.lp.certificationObserver(LpRoute.NODE))
                return LpNodeOutcome(true, null)
            }
        }
    }
    // A certified Lagrangian bounds every point below the source cutoff without requiring
    // an attained LP optimum. A declared objective ceiling also bounds feasible points before an incumbent.
    if (canFix && cert != null &&
        !cancellation.pollStop() &&
        observeNodeFixing(sink, LpSolvePhase.NODE_PRIMARY_FIXING) {
            applySparseReducedCostFixing(boundRel, cert, session, bound, sink, objectiveVar,
                objectiveAscending, learn, cancellation, objectiveCeiling)
        }
    ) {
        return LpNodeOutcome(true, null)
    }
    if (canFix && cert != null) for ((index, candidate) in additionalCertificates.withIndex()) {
        if (cancellation.pollStop()) return LpNodeOutcome(false, optimalBasis)
        val additional = solveContext.certificationPolicy.acceptNullable(LpCertifier.INTEGER, candidate) ?: continue
        val phase = if (index == 0) LpSolvePhase.NODE_SECONDARY_FIXING else LpSolvePhase.NODE_TERTIARY_FIXING
        if (observeNodeFixing(sink, phase) {
                applySparseReducedCostFixing(boundRel, additional, session, bound, sink, objectiveVar,
                    objectiveAscending, learn, cancellation, objectiveCeiling)
            }) {
            return LpNodeOutcome(true, null)
        }
    }
    if (rowMeter != null &&
        applyMixedRowBounds(boundRel, session, sink, learn, cancellation, rowMeter, relaxer) { optimalBasis = null }
    ) {
        return LpNodeOutcome(true, null)
    }
    return LpNodeOutcome(false, optimalBasis)
}

private fun LpEngine.pruneWithComponentBound(
    relaxation: LpRelaxation,
    solver: LpSolver,
    cutoff: Double,
    sink: SolveStatsSink,
    cancellation: ExecutionControl,
): Boolean {
    val component = solver as? ComponentLpSolverCapability ?: return false
    if (!cutoff.isFinite() || cancellation.pollStop() || numericalDeductions) return false
    noteNodeOverhead(relaxation.model.extent() * LpNodeOverhead.EXACT)
    if (lpWorkAllowanceSpent() || cancellation.pollStop()) return false
    val observer = sink.lp.certificationObserver(LpRoute.NODE)
    val charged = object : LpCertificationObserver by observer {
        override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) {
            observer.observe(certifier, success, cost)
            if (cost is LpCertifierCost.Metered) noteNodeOverhead(cost.work)
        }
    }
    val checked = observeLpClaim(LpClaim.BOUND, charged) {
        component.exactBound(it, solveContext.certificationPolicy)
    } ?: return false
    if (cancellation.pollStop()) return false
    val full = checked.value + BigFraction.ofLong(relaxation.objectiveConstant)
    val threshold = sourceObjectiveCutoff(relaxation, cutoff, sourceObjectiveRange(relaxation)) ?: return false
    if (full < threshold) return false
    sink.lp.observePrune()
    LpClaim.BOUND.consumed(charged)
    return true
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
    cancellation: ExecutionControl,
): LpNodeOutcome {
    if (!bound.isFinite()) return LpNodeOutcome(false, null) // no incumbent to prune against
    // Cut-free recovery: this path is reached because the cut-augmented build overflowed, and cuts are a
    // common overflow source, so the base relaxation (no cuts) is what yields a sound — if looser — bound.
    val relaxation = nodeRelaxation(relaxer, session) ?: return LpNodeOutcome(false, null)
    if (relaxation.model.n == 0 && relaxation.model.m == 0) return LpNodeOutcome(false, null)
    sink.lp.observeSolve()
    noteNodeOverhead(relaxation.model.extent() * LpNodeOverhead.SETUP)
    val recoverySimplex = dualSimplex(
        relaxation.model, cancellation, workLimit = nodeQueryWorkBudget() ?: nodeWorkBudget(),
    )
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
    if (cancellation.pollStop()) return LpNodeOutcome(false, null) // honor the deadline before the exact certify
    // Both bounds are sound for any duals and neither dominates, so the larger wins: the
    // integer-multiplier bound carries no rounding margin, the float one never declines. A null (neither
    // available) keeps the node.
    val lb = objectiveLowerBound(
        relaxation.model, result.duals, sink.lp.certificationObserver(LpRoute.NODE), charged = true,
    )
        ?: return LpNodeOutcome(false, null)
    val cutoff = sourceObjectiveCutoff(relaxation, bound) ?: return LpNodeOutcome(false, null)
    val full = checkNotNull(BigFraction.ofDouble(lb)) + BigFraction.ofLong(relaxation.objectiveConstant)
    return if (full >= cutoff) {
        sink.lp.observePrune()
        LpClaim.BOUND.consumed(sink.lp.certificationObserver(LpRoute.NODE))
        LpNodeOutcome(true, null)
    } else {
        LpNodeOutcome(false, null)
    }
}

// Exact solve for a relaxation with strict rows: prunes on an exact infeasibility verdict or a lower bound
// reaching the cutoff; null defers to the float path.
private fun LpEngine.strictSourcePrune(
    relaxation: LpRelaxation,
    session: PropagationSession,
    bound: Double,
    sink: SolveStatsSink,
    cancellation: ExecutionControl,
    learn: Boolean,
): LpNodeOutcome? {
    val model = relaxation.model
    val exact = model.authoritativeModel() ?: return null
    if (cancellation.pollStop()) return LpNodeOutcome(false, null)
    nodeUsesTrail = true
    if (cpAdapter.currentModel !== model) {
        if (!propagator.install(model, exact)) return null
        cpAdapter.localModel()
    }
    val result = try {
        propagator.solve(cancellation, claim = LpClaim.BOUND)
    } finally {
        sink.lp.observeEngineCost(LpRoute.NODE, propagator.lastMetrics)
        noteSolveOps(propagator.lastMetrics.workOps)
    } ?: return null
    if (cancellation.pollStop()) return LpNodeOutcome(false, null)
    result.witness?.let { witness ->
        checkedLpWitness(model, witness.primal)?.let {
            lpCounterResults.remember(
                model,
                CertifiedLpResult(null, null, it, null, null, false, { null }),
                solveContext.certificationPolicy,
            )
        }
    }
    if (result.verdict == com.eignex.klause.lp.engine.LpVerdict.INFEASIBLE) {
        // Every original row and live bound remains a premise when transformed support is unavailable.
        val conflict = result.rationalConflict
        if (conflict != null && !checkedLpConflict(model, conflict)) return null
        sink.lp.observeInfeasiblePrune()
        LpClaim.CONTRADICTION.consumed(sink.lp.certificationObserver(LpRoute.NODE))
        return LpNodeOutcome(
            true,
            null,
            if (learn) {
                LpExplanation.premiseClauseForRows(relaxation, IntArray(model.m) { it }, session)
            } else {
                null
            },
        )
    }
    val lower = result.lowerBound
    val cutoff = if (sourceObjectiveRange(relaxation) != null) enclosingCutoff(bound) else null
    if (lower != null && cutoff != null && lower + BigFraction.ofLong(relaxation.objectiveConstant) >= cutoff) {
        sink.lp.observePrune()
        LpClaim.BOUND.consumed(sink.lp.certificationObserver(LpRoute.NODE))
        return LpNodeOutcome(true, null)
    }
    return null
}

/** Inward relative rhs perturbation applied to strict rows for the gated float filter solve. */
private const val STRICT_FILTER_EPS = 1e-7

/** [this] model, with an exact pass over it charged to [engine]: the certificates below work in rationals. */
internal fun LpModel.alsoCharged(engine: LpEngine): LpModel =
    also { engine.noteNodeOverhead(extent() * LpNodeOverhead.EXACT) }

/** Whether this engine's deductions consume float results within tolerances rather than exact certificates. */
internal val LpEngine.numericalDeductions: Boolean get() = solveContext.numericalDeductions

/** A lower bound on [model]'s source objective under [duals]: numerical within tolerances, else certified. */
internal fun LpEngine.objectiveLowerBound(
    model: LpModel,
    duals: DoubleArray,
    observer: LpCertificationObserver?,
    charged: Boolean = false,
): Double? = if (numericalDeductions) {
    FloatDeductionCertificate.of(model, duals)?.lowerBound
} else {
    certifiedTightObjectiveLowerBound(
        if (charged) model.alsoCharged(this) else model, duals, observer, solveContext.certificationPolicy,
    )
}

/**
 * The infeasibility proof [floatRay] supports on [model]: numerical within tolerances, else an exact Farkas ray. An
 * empty numerical ray refutes the node without a combination a learned clause can cite.
 */
internal fun LpEngine.infeasibilityRay(model: LpModel, floatRay: DoubleArray, sink: SolveStatsSink): LongArray? {
    if (numericalDeductions) return floatFarkasRay(model, floatRay)
    return solveContext.certificationPolicy.acceptNullable(
        LpCertifier.EXACT_FARKAS,
        certifyLpFarkas(model.alsoCharged(this), floatRay, onRoute = {
            sink.lp.observeFarkasRoute(
                it == FarkasRoute.RECONSTRUCTED,
                it == FarkasRoute.EXACT_BASIS,
                it == FarkasRoute.ROUNDED,
                it == FarkasRoute.NONE,
            )
        }, observer = sink.lp.certificationObserver(LpRoute.NODE)),
    )
}

// Learned-reason and conflict-clause outcomes, so how much learning each certification mode keeps is measurable.
private fun SolveStatsSink.observeDeduction(outcome: String) =
    lp.certificationObserver(LpRoute.NODE).observePhase(LpPhaseMetrics(LpSolvePhase.NODE_DEDUCTION, outcome, 0L))

internal fun SolveStatsSink.observeReason(kind: String, kept: Boolean) =
    observeDeduction("${kind}_REASON_${if (kept) "KEPT" else "WITHHELD"}")

// The learned clause a Farkas ray supports, counted as citable or clause-less.
private fun learnedClause(
    relaxation: LpRelaxation,
    ray: LongArray,
    session: PropagationSession,
    sink: SolveStatsSink,
): IntArray? = LpExplanation.infeasibilityClause(relaxation, ray, session).also {
    sink.observeDeduction(if (it != null) "FARKAS_CITABLE" else "FARKAS_CLAUSELESS")
}
