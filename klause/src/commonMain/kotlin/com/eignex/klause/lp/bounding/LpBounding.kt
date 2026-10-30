package com.eignex.klause.lp.bounding

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.lp.cut.CutContext
import com.eignex.klause.lp.cut.CutPool
import com.eignex.klause.lp.cut.CutSeparator
import com.eignex.klause.lp.cut.SourceCut
import com.eignex.klause.lp.cut.orNull
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.CertifiedLpResult
import com.eignex.klause.lp.engine.ComponentLpSolverCapability
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.CutFamily
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.FarkasRoute
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.LpCertificationObserver
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpCertifierCost
import com.eignex.klause.lp.engine.LpClaim
import com.eignex.klause.lp.engine.LpDeductionCertificate
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPhaseTimer
import com.eignex.klause.lp.engine.LpProjectionStop
import com.eignex.klause.lp.engine.LpRowBound
import com.eignex.klause.lp.engine.LpSolvePhase
import com.eignex.klause.lp.engine.LpSolver
import com.eignex.klause.lp.engine.MixedCertificateMeter
import com.eignex.klause.lp.engine.ReconstructionLimits
import com.eignex.klause.lp.engine.TableauCutSolver
import com.eignex.klause.lp.engine.acceptNullable
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.lp.engine.ceilLong
import com.eignex.klause.lp.engine.certifiedTightObjectiveLowerBound
import com.eignex.klause.lp.engine.certifyLpBoundOnly
import com.eignex.klause.lp.engine.certifyLpFarkas
import com.eignex.klause.lp.engine.checkedLpConflict
import com.eignex.klause.lp.engine.checkedLpWitness
import com.eignex.klause.lp.engine.exactBits
import com.eignex.klause.lp.engine.exactBounds
import com.eignex.klause.lp.engine.exactConstant
import com.eignex.klause.lp.engine.exactCost
import com.eignex.klause.lp.engine.exactShift
import com.eignex.klause.lp.engine.finiteExactInput
import com.eignex.klause.lp.engine.floorLong
import com.eignex.klause.lp.engine.hasIntegralObjective
import com.eignex.klause.lp.engine.lowerBoundDouble
import com.eignex.klause.lp.engine.lpConditioning
import com.eignex.klause.lp.engine.mixedRowBounds
import com.eignex.klause.lp.engine.newComponentTableauCutSolver
import com.eignex.klause.lp.engine.newPersistentLpSolver
import com.eignex.klause.lp.engine.newTableauCutSolver
import com.eignex.klause.lp.engine.nodeDualCertificate
import com.eignex.klause.lp.engine.observeLpClaim
import com.eignex.klause.lp.engine.sourceObjective
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.LpAssemblyCancelled
import com.eignex.klause.lp.relaxation.LpExplanation
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.lp.relaxation.withCpBounds
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.simplex.exact.BigFraction
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
import kotlin.math.nextDown
import kotlin.math.nextUp
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
    cancellation: Cancellation,
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
    cancellation: Cancellation,
): Pair<LpSolver, FloatLpResult?>? {
    nodeUsesTrail = cpAdapter.currentModel === model
    if (nodeUsesTrail) return propagator.solveFloat(warm, cancellation)
    model.authoritativeModel()?.let { exact ->
        nodeUsesTrail = true
        cpAdapter.localModel()
        if (!propagator.install(model, exact)) return null
        return propagator.solveFloat(warm, cancellation)
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
    cancellation: Cancellation,
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
    cancellation: Cancellation,
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
    cancellation: Cancellation,
    sink: SolveStatsSink,
    learn: Boolean,
): LpCutOutcome {
    if (selected.isEmpty() || cancellation()) return previous
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
    if (attempt == null || cancellation()) return previous
    attempt.second?.let {
        observeCutFill(it.luMaxFill, tightened.model.m)
        return LpCutOutcome.Solved(tightened, it)
    }
    if (lpCounterResults.read(tightened.model, solveContext.certificationPolicy)?.witness != null) return previous
    val floatRay = attempt.first.infeasibleRay ?: return previous
    val ray = solveContext.certificationPolicy.acceptNullable(
        LpCertifier.EXACT_FARKAS,
        certifyLpFarkas(tightened.model.alsoCharged(this), floatRay, onRoute = {
            sink.lp.observeFarkasRoute(
                it == FarkasRoute.RECONSTRUCTED, it == FarkasRoute.EXACT_BASIS,
                it == FarkasRoute.ROUNDED, it == FarkasRoute.NONE,
            )
        }, observer = sink.lp.certificationObserver(LpRoute.NODE)),
    ) ?: return previous
    sink.lp.observeInfeasiblePrune()
    observeCutRayUse(tightened, ray)
    LpClaim.CONTRADICTION.consumed(sink.lp.certificationObserver(LpRoute.NODE))
    return LpCutOutcome.Pruned(if (learn) LpExplanation.infeasibilityClause(tightened, ray, session) else null)
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
    cancellation: Cancellation,
    objectiveVar: Int,
    objectiveAscending: Boolean,
    hints: LpHintSink? = null,
    learn: Boolean = false,
    warm: Basis? = null,
    cutsAllowed: Boolean = false,
): LpNodeOutcome {
    if (cancellation()) return LpNodeOutcome(false, null)
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
            if (gatedRay != null && !cancellation()) {
                for (i in 0 until gatedModel.m) if (!filter.enforced[i]) gatedRay[i] = 0.0
                val ray = solveContext.certificationPolicy.acceptNullable(
                    LpCertifier.EXACT_FARKAS,
                    certifyLpFarkas(gatedModel.alsoCharged(this), gatedRay, onRoute = {
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
                    LpClaim.CONTRADICTION.consumed(sink.lp.certificationObserver(LpRoute.NODE))
                    hints?.recordInfeasible()
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
    val relaxation = nodeRelaxation(relaxer, session) ?: return LpNodeOutcome(false, null)
    activateCutAttribution(relaxation)
    if (cancellation()) return LpNodeOutcome(false, null)
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
    if (model.rowStrict.any { it }) {
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
    if (cancellation()) return LpNodeOutcome(false, null)
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
        // confirm it with an exact Farkas certificate before pruning (the float ray alone is not sound).
        // Any other failure (non-convergence / singular) keeps the node.
        val floatRay = simplex.infeasibleRay
        val ray = if (floatRay != null) {
            solveContext.certificationPolicy.acceptNullable(
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
        } else {
            null
        }
        if (ray != null) {
            observeCutRayUse(relaxation, ray)
            sink.lp.observeInfeasiblePrune()
            LpClaim.CONTRADICTION.consumed(sink.lp.certificationObserver(LpRoute.NODE))
            hints?.recordInfeasible()
            // With learning, the Farkas ray becomes a bound-atom nogood for a 1UIP backjump;
            // null (auxiliary column / unbacked non-global row / constraint-only) prunes reason-less.
            val clause = if (learn) LpExplanation.infeasibilityClause(relaxation, ray, session) else null
            return LpNodeOutcome(true, null, clause)
        }
        return LpNodeOutcome(false, null)
    }
    if (session.decisionLevel == 0) recordRootQueryBound(relaxation, result)
    sink.lp.observeLuFill(result.luMaxFill, result.luMaxDensity, result.luMaxDim)
    // LP-guided branching: record the fractional primal + reduced costs so the descent can order
    // branch values toward the LP point and pick reduced-cost-impactful fractional variables. Purely
    // advisory — it never changes feasibility or the optimum.
    hints?.record(relaxation, result.primal, result.duals)
    if (result.optimal && hints != null) {
        hints.recordObjective(relaxation, result.objective)
    }
    // The scoped owner carries appended-row bases itself; the hint is only for external fresh solves.
    var optimalBasis: Basis? = result.basis
    val rowMeter = if (problem.numRealVars > 0 &&
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
        if (cancellation()) return LpNodeOutcome(false, optimalBasis)
        val constant = cutPool.constantContradiction() ?: return LpNodeOutcome(false, optimalBasis)
        return when (val outcome = solveSelectedCuts(
            session, LpCutOutcome.Solved(relaxation, result), listOf(constant), cancellation, sink, learn,
        )) {
            is LpCutOutcome.Pruned -> LpNodeOutcome(true, null, outcome.explanation)
            is LpCutOutcome.Solved -> LpNodeOutcome(false, outcome.result.basis)
        }
    }
    // Pooled global cuts the LP point violates are folded in first (see foldSelectedCuts). Then, at a
    // gated shallow node, separation tightens the relaxation further: global cuts are persisted into
    // the pool (descendants inherit them); node-local cuts retain their complete source proof and
    // follow the owner's row trail. The bound, certificate and reduced-cost fixing below read the
    // tightened immutable relaxation.
    val cutOutcome = foldSelectedCuts(session, relaxation, result, cancellation, sink, learn)
    if (cutOutcome is LpCutOutcome.Pruned) return LpNodeOutcome(true, null, cutOutcome.explanation)
    val cutSolved = cutOutcome as LpCutOutcome.Solved
    if (cancellation()) return LpNodeOutcome(false, null)
    var boundRel = cutSolved.relaxation
    var boundRes = cutSolved.result
    if (cutsAllowed && session.decisionLevel in 1..params.lpPlan.cutSearchMaxDepth &&
        lpSeparators.isNotEmpty() && cutSeparationSupported(boundRel, cancellation, root = false)
    ) {
        val localCuts = ArrayList<SourceCut>()
        var rounds = 0
        while (rounds++ < SEARCH_CUT_ROUNDS && !cancellation()) {
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
            for (c in fresh) if (!c.global) {
                SourceCut.fromCut(c, boundRel).orNull()?.let(localCuts::add)
            }
            val selectedCuts = cutPool.select(
                boundRes.primal,
                objectiveCoefficients(boundRel.model),
                cutPool.maxCuts,
            ) + localCuts.mapNotNull { source -> boundRel.sourceMap?.let { source.toCut(it).orNull() } }
            val previous = LpCutOutcome.Solved(boundRel, boundRes)
            val outcome = solveSelectedCuts(session, previous, selectedCuts, cancellation, sink, learn)
            when (outcome) {
                is LpCutOutcome.Pruned -> return LpNodeOutcome(true, null, outcome.explanation)
                is LpCutOutcome.Solved -> {
                    if (outcome.relaxation.model === boundRel.model) break
                    boundRel = outcome.relaxation
                    boundRes = outcome.result
                }
            }
        }
    }
    // The exact basis-certificate backs the prune bound, the learnable objective-bound reason and the
    // reduced-cost fixing. Compute it once when any of them needs it; a singular/unbounded certify
    // yields null and each falls back to its cheap certificate-less path, which is sound.
    if (cancellation()) return LpNodeOutcome(false, null)
    activateCutAttribution(boundRel)
    val additionalCertificates = ArrayList<LpDeductionCertificate>(2)
    val cert = if ((learn && canPropagate) || canFix) {
        solveContext.certificationPolicy.acceptNullable(
            LpCertifier.INTEGER,
            nodeDualCertificate(
                boundRel.model, boundRes.duals, cancellation, sink.lp.certificationObserver(LpRoute.NODE),
                eliminateContinuousObjective = !canPrune && objectiveCeiling != null,
                onAdditionalCertificate = if (canFix) { { additionalCertificates.add(it) } } else null,
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
    if (cancellation()) return LpNodeOutcome(false, null)
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
    val lower = certifiedTightObjectiveLowerBound(
        boundRel.model.alsoCharged(this),
        boundRes.duals,
        cert,
        sink.lp.certificationObserver(LpRoute.NODE),
        solveContext.certificationPolicy,
    ) ?: run {
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
        !cancellation() &&
        observeNodeFixing(sink, LpSolvePhase.NODE_PRIMARY_FIXING) {
            applySparseReducedCostFixing(boundRel, cert, session, bound, sink, objectiveVar,
                objectiveAscending, learn, cancellation, objectiveCeiling)
        }
    ) {
        return LpNodeOutcome(true, null)
    }
    if (canFix && cert != null) for ((index, candidate) in additionalCertificates.withIndex()) {
        if (cancellation()) return LpNodeOutcome(false, optimalBasis)
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
    cancellation: Cancellation,
): Boolean {
    val component = solver as? ComponentLpSolverCapability ?: return false
    if (!cutoff.isFinite() || cancellation()) return false
    noteNodeOverhead(relaxation.model.extent() * LpNodeOverhead.EXACT)
    if (lpWorkAllowanceSpent() || cancellation()) return false
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
    if (cancellation()) return false
    val full = checked.value + BigFraction.ofLong(relaxation.objectiveConstant)
    val threshold = sourceObjectiveCutoff(relaxation, cutoff, sourceObjectiveRange(relaxation)) ?: return false
    if (full < threshold) return false
    sink.lp.observePrune()
    LpClaim.BOUND.consumed(charged)
    return true
}

@Suppress("LongParameterList")
private fun LpEngine.applyMixedRowBounds(
    relaxation: LpRelaxation,
    session: PropagationSession,
    sink: SolveStatsSink,
    learn: Boolean,
    cancellation: Cancellation,
    meter: MixedCertificateMeter,
    relaxer: CpToLpRelaxation? = null,
    onRefresh: () -> Unit = {},
): Boolean {
    var current = relaxation
    val initialWork = meter.work
    try {
        repeat(if (relaxer == null) 1 else 2) { pass ->
            if (cancellation()) return false
            val affectedColumns = if (session.decisionLevel == 0) null
                else narrowedRowColumns(current, session, cancellation)
            val projected = solveContext.certificationPolicy.acceptNullable(
                LpCertifier.INTEGER,
                mixedRowBounds(
                    current.model, cancellation, sink.lp.certificationObserver(LpRoute.NODE), meter, affectedColumns,
                ),
            ).orEmpty()
            var changed = false
            if (observeNodeFixing(sink, LpSolvePhase.NODE_ROW_FIXING) {
                applyRowBounds(current, projected, session, sink, learn, cancellation) { changed = true }
            }) return true
            if (!changed || relaxer == null || pass == 1 || cancellation()) return false
            meter.reserve(current.model.numVars.toLong() * 16L + 128L)
            onRefresh()
            current = nodeRelaxation(relaxer, session) ?: return false
        }
        return false
    } catch (_: LpProjectionStop) {
        return false
    } finally {
        noteNodeOverhead(meter.work - initialWork)
    }
}

private fun LpEngine.narrowedRowColumns(
    relaxation: LpRelaxation,
    session: PropagationSession,
    cancellation: Cancellation,
): IntArray {
    val columns = IntArrayList()
    noteNodeOverhead(relaxation.colVarId.size.toLong())
    for (column in relaxation.colVarId.indices) {
        if (cancellation()) return intArrayOf()
        val variable = relaxation.colVarId[column]
        if (variable < 0 || relaxation.model.colContinuous[column]) continue
        val narrowed = if (relaxation.colIsBool[column]) {
            session.boolValue(variable) != null
        } else {
            val domain = session.intDomain(variable)
            val declared = problem.intBounds
            !declared.hasLower(variable) || !declared.hasUpper(variable) ||
                domain.min != declared.lower(variable) || domain.max != declared.upper(variable)
        }
        if (narrowed) columns.add(column)
    }
    return columns.toIntArray()
}

@Suppress("CyclomaticComplexMethod")
private fun LpEngine.applyRowBounds(
    relaxation: LpRelaxation,
    bounds: List<LpRowBound>,
    session: PropagationSession,
    sink: SolveStatsSink,
    learn: Boolean,
    cancellation: Cancellation,
    onFix: () -> Unit,
): Boolean {
    for (certificate in bounds) {
        if (cancellation()) return false
        if (!certificate.belongsTo(relaxation.model)) continue
        val column = certificate.column
        val variable = relaxation.colVarId.getOrNull(column) ?: continue
        if (variable < 0 || relaxation.model.colContinuous[column]) continue
        val threshold = certificate.integerBound() ?: continue
        noteNodeOverhead(48L)
        val bool = relaxation.colIsBool[column]
        val pinned = if (bool) session.boolValue(variable) else null
        val minimum = if (bool) { if (pinned == true) 1L else 0L } else session.intDomain(variable).min
        val maximum = if (bool) { if (pinned == false) 0L else 1L } else session.intDomain(variable).max
        if ((certificate.upper && threshold >= maximum) || (!certificate.upper && threshold <= minimum)) continue
        noteNodeOverhead(certificate.supportCount.toLong() * LpNodeOverhead.FIXING)
        val support = LpExplanation.rowBoundReason(relaxation, certificate, session) ?: continue
        val reason = support.takeIf { learn && !bool }
        val result = when {
            bool && ((threshold < minimum && certificate.upper) ||
                (threshold > maximum && !certificate.upper)) -> {
                sink.lp.observePrune()
                LpClaim.BOUND.consumed(sink.lp.certificationObserver(LpRoute.NODE))
                return true
            }
            bool -> session.implyBool(variable, !certificate.upper)
            certificate.upper && reason != null -> session.implyIntAtMostWithReason(variable, threshold, reason)
            certificate.upper -> session.implyIntAtMost(variable, threshold)
            reason != null -> session.implyIntAtLeastWithReason(variable, threshold, reason)
            else -> session.implyIntAtLeast(variable, threshold)
        }
        if (result is PropagationResult.Unsat) {
            sink.lp.observePrune()
            LpClaim.BOUND.consumed(sink.lp.certificationObserver(LpRoute.NODE))
            return true
        }
        if (learn) lpNogoods?.let { pool ->
            val conclusion = when {
                bool -> Lit.make(variable, !certificate.upper)
                certificate.upper -> session.boundLeLit(variable, threshold, positive = true)
                else -> session.boundGeLit(variable, threshold, positive = true)
            }
            noteNodeOverhead(support.size.toLong() + 1L)
            val clause = intArrayOf(conclusion) + support
            if (pool.add(clause, registered = true) &&
                session.addLearnedClause(Clause(clause), lbd = clause.size, permanent = true) is PropagationResult.Unsat
            ) {
                sink.lp.observePrune()
                LpClaim.BOUND.consumed(sink.lp.certificationObserver(LpRoute.NODE))
                return true
            }
        }
        sink.lp.observeFix()
        onFix()
    }
    return false
}

private inline fun observeNodeFixing(sink: SolveStatsSink, phase: LpSolvePhase, body: () -> Boolean): Boolean {
    val timer = LpPhaseTimer(sink.lp.certificationObserver(LpRoute.NODE), phase)
    val before = sink.lp.fixed.read().sum
    var returned = false
    var conflict = false
    try {
        conflict = body()
        returned = true
        return conflict
    } finally {
        val fixes = (sink.lp.fixed.read().sum - before).toInt()
        val outcome = when {
            !returned -> "FAILED"
            conflict -> "CONFLICT"
            fixes > 0 -> "FIXED"
            else -> "NONE"
        }
        timer.finish(outcome, steps = fixes)
    }
}

/**
 * Reduced-cost fixing from one [LpDeductionCertificate] in minimized source objective units. Moving
 * an integral column Δ steps from its minimizing endpoint raises the Lagrangian by `|reducedCost|·Δ`.
 * A verified integral objective uses `ceil(bound) - 1`; other objectives retain an enclosing rational
 * cutoff. A finite single-coordinate objective ceiling supplies a closed feasibility cutoff,
 * including before an incumbent exists. The allowed movement uses the unrounded gap divided by `|reducedCost|`.
 * With [learn] each integer fixing carries the LP dual-decomposition reason (the other
 * support columns' seated bounds + the incumbent bound + any dual-weighted non-global row's premises);
 * when the reason is inexpressible the fixing falls back to a reason-less level-local tightening (a
 * conflict-analysis leaf). Returns true if a reduction empties a domain (the node is then pruned).
 */
@Suppress("LongParameterList", "CyclomaticComplexMethod")
internal fun LpEngine.applySparseReducedCostFixing(
    relaxation: LpRelaxation,
    cert: LpDeductionCertificate,
    session: PropagationSession,
    bound: Double,
    sink: SolveStatsSink,
    objectiveVar: Int = -1,
    objectiveAscending: Boolean = true,
    learn: Boolean = false,
    cancellation: Cancellation = Cancellation.Never,
    objectiveCeiling: BigFraction? = null,
): Boolean {
    if (cancellation() || !cert.belongsTo(relaxation.model, sink.lp.certificationObserver(LpRoute.NODE))) return false
    val sourceRange = sourceObjectiveRange(relaxation)
    val incumbentMax = if (!bound.isFinite()) {
        objectiveCeiling ?: return false
    } else if (sourceRange != null || permitsIntegralObjectiveCutoff(cert)) {
        val ceiling = enclosingCutoff(bound)?.ceilLong() ?: return false
        if (ceiling == Long.MIN_VALUE) return false
        BigFraction.ofLong(ceiling - 1L)
    } else {
        BigFraction.ofDouble(bound.nextUp()) ?: return false
    }
    val improvingMax = objectiveCeiling?.let { if (it < incumbentMax) it else incumbentMax } ?: incumbentMax
    val sourceConstant = relaxation.objectiveConstant
    val integralCutoff = ExactLpNumber.of(improvingMax).exactLong()
    if (integralCutoff != null) {
        if (!cert.improvingGapNonNegative(integralCutoff, sourceConstant)) return false
    } else {
        val lower = cert.exactObjectiveBound()
        val words = (maxOf(Long.SIZE_BITS, exactBits(listOf(improvingMax, lower))) + 63L) / 64L
        noteNodeOverhead(4L * words * words)
        if (improvingMax < lower + BigFraction.ofLong(sourceConstant)) return false
    }
    val rationalGap = if (cert.usesRationalArithmetic() || integralCutoff == null) {
        val lower = cert.exactObjectiveBound()
        val words = (maxOf(Long.SIZE_BITS, exactBits(listOf(improvingMax, lower))) + 63L) / 64L
        noteNodeOverhead(4L * words * words)
        improvingMax - BigFraction.ofLong(sourceConstant) - lower
    } else {
        null
    }
    val reasonSupport = if (learn && objectiveVar >= 0 && objectiveAscending && sourceRange != null) {
        reducedCostFixingReasons(relaxation, cert, session, objectiveVar, checkNotNull(improvingMax.floorLong()),
            sink.lp.certificationObserver(LpRoute.NODE))
    } else {
        null
    }
    val canLearn = reasonSupport != null
    noteNodeOverhead(relaxation.colVarId.size.toLong() * LpNodeOverhead.FIXING)
    var cutUseObserved = false
    for (col in relaxation.colVarId.indices) {
        if (cancellation()) return false
        val varId = relaxation.colVarId[col]
        if (varId < 0 || relaxation.model.colContinuous[col] ||
            relaxation.model.exactState?.model?.column(col)?.integral == false
        ) continue
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
        if (sign == 0) continue
        val bounds = relaxation.model.exactBounds(col)
        val side = (if (sign > 0) bounds.lower else bounds.upper) ?: continue
        val endpoint = ExactLpNumber.of(relaxation.model.exactShift(col) + side.number.value).exactLong() ?: continue
        noteNodeOverhead(cert.rationalFixingWork(col, improvingMax))
        val distance = try {
            if (sign > 0) subExact(liveMax, endpoint) else subExact(endpoint, liveMin)
        } catch (_: CheckedLongOverflowException) {
            null
        }
        if (distance != null && distance >= 0L) {
            val permitted = if (rationalGap != null) {
                val reduced = cert.exactReducedCost(col)
                val cost = if (sign < 0) reduced.negated() else reduced
                cost * BigFraction.ofLong(distance) <= rationalGap
            } else {
                cert.permitsMovement(col, distance, checkNotNull(integralCutoff), sourceConstant)
            }
            if (permitted) continue
        }
        val dMax = if (rationalGap != null) {
            noteNodeOverhead(cert.rationalFixingWork(col, improvingMax))
            cert.fixStepsForGap(col, rationalGap)
        } else {
            cert.fixStepsAtCutoff(col, improvingMax, sourceConstant)
        }
        if (dMax == null) continue
        val res = when {
            // A positive reduced cost is minimized at the certificate model's lower endpoint.
            sign > 0 -> {
                val hi = try {
                    addExact(endpoint, dMax)
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
                    subExact(endpoint, dMax)
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
        if (!cutUseObserved) {
            observeCutUse(relaxation, cert, "REDUCED_COST_PROPAGATION")
            cutUseObserved = true
        }
        if (res is PropagationResult.Unsat) {
            sink.lp.observePrune()
            LpClaim.BOUND.consumed(sink.lp.certificationObserver(LpRoute.NODE))
            return true
        }
        sink.lp.observeFix()
        if (session.decisionLevel == 0) sink.lp.observeRootReducedCostFixes(1)
    }
    return false
}

private fun LpEngine.singleObjectiveCeiling(
    relaxation: LpRelaxation,
    cancellation: Cancellation,
): BigFraction? {
    val model = relaxation.model
    if (!model.finiteExactInput()) return null
    noteNodeOverhead(model.numVars.toLong() * LpNodeOverhead.FIXING)
    var column = -1
    var coefficient = BigFraction.ZERO
    for (j in 0 until model.numVars) {
        if (cancellation()) return null
        val cost = model.exactCost(j)
        if (cost.isZero) continue
        if (column >= 0 || j >= model.n) return null
        column = j
        coefficient = cost
    }
    if (column < 0) return null
    val bounds = model.exactBounds(column)
    val side = (if (coefficient.signum() > 0) bounds.upper else bounds.lower) ?: return null
    val objective = model.exactState?.model?.objective
    val scale = objective?.scale?.value ?: BigFraction.ONE
    val external = objective?.externalConstant?.value ?: BigFraction.ZERO
    val constant = model.exactConstant()
    val bits = exactBits(listOf(coefficient, side.number.value, constant, scale, external))
    if (bits > 128) return null
    val words = (maxOf(Long.SIZE_BITS, bits) + 63L) / 64L
    noteNodeOverhead(12L * words * words)
    val ceiling = (constant + coefficient * side.number.value) * scale.reciprocal() + external +
        BigFraction.ofLong(relaxation.objectiveConstant)
    return ceiling.takeIf { exactBits(listOf(it)) <= 128 }
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
    cert: LpDeductionCertificate,
    session: PropagationSession,
    objectiveVar: Int,
    improvingMax: Long,
    observer: LpCertificationObserver? = null,
): ReducedCostFixingReasons? {
    if (!cert.belongsTo(relaxation.model, observer)) return null
    val objectiveVarMax = relaxation.objectiveVariableValue(objectiveVar, BigFraction.ofLong(improvingMax))
        ?.floorLong() ?: return null
    if (session.intDomain(objectiveVar).max > objectiveVarMax) return null
    val supportCols = IntArrayList()
    val supportLits = IntArrayList()
    val seen = IntHashSet()
    val premLits = IntArrayList()
    if (!LpExplanation.addDualRowPremiseLits(premLits, seen, relaxation, cert, session, observer)) return null
    for (k in 0 until premLits.size) {
        supportCols.add(-1) // row premise: part of every fixing's reason, never excluded
        supportLits.add(premLits[k])
    }
    for (col in relaxation.colVarId.indices) {
        val sign = cert.reducedCostSign(col)
        if (sign == 0) continue
        val premises = LpExplanation.boundPremiseLits(relaxation, session, col, lowerSide = sign > 0) ?: return null
        for (literal in premises) {
            val direct = relaxation.colVarId[col] >= 0
            supportCols.add(if (direct) col else -1)
            supportLits.add(literal)
        }
    }
    return ReducedCostFixingReasons(
        supportCols.toIntArray(),
        supportLits.toIntArray(),
        session.boundLeLit(objectiveVar, objectiveVarMax, positive = false),
    )
}

private fun LpEngine.permitsIntegralObjectiveCutoff(cert: LpDeductionCertificate): Boolean {
    val rows = cert.integralObjectiveRows() ?: return false
    return rows.isNotEmpty() || problem.numRealVars > 0
}

private fun LpEngine.sourceObjectiveRange(relaxation: LpRelaxation): LongRange? {
    val model = relaxation.model
    if (!model.finiteExactInput() || !model.hasIntegralObjective()) return null
    val source = model.exactState?.model
    source?.objective?.let {
        if (it.scale.value != BigFraction.ONE || !it.externalConstant.value.isZero) return null
    }
    if (source == null && model.exactConstant() != BigFraction.ofLong(model.objConstant)) return null
    val objectiveConstant = ExactLpNumber.of(model.exactConstant()).exactLong() ?: return null
    fun costAt(column: Int): Long? = if (source != null) {
        source.objective.cost(column).exactLong()
    } else if (model.doubleView == null) {
        model.cost[column]
    } else {
        model.cost[column].takeIf { model.exactCost(column) == BigFraction.ofLong(it) }
    }
    val constant = Int128().also {
        it.addLong(objectiveConstant)
        it.addLong(relaxation.objectiveConstant)
    }
    for (j in 0 until model.numVars) {
        val cost = costAt(j) ?: return null
        if (cost == 0L) continue
        if (j >= model.n || model.colContinuous[j]) return null
        if (source == null && model.exactShift(j) != BigFraction.ofLong(model.loShift[j])) return null
        val origin = ExactLpNumber.of(model.exactShift(j)).exactLong() ?: return null
        val shift = Int128().also { it.addProduct(cost, origin) }
        constant.subtract(shift)
    }
    val minimum = constant.copy()
    val maximum = constant.copy()
    for (j in 0 until model.n) {
        val cost = costAt(j) ?: return null
        if (cost == 0L) continue
        val source = relaxation.colVarId.getOrNull(j) ?: return null
        val isBool = relaxation.colIsBool.getOrNull(j) ?: return null
        val lower: Long
        val upper: Long
        if (isBool) {
            if (source !in 0 until problem.numBoolVars) return null
            lower = 0L
            upper = 1L
        } else {
            if (source !in 0 until problem.numIntVars) return null
            if (!problem.intBounds.hasLower(source) || !problem.intBounds.hasUpper(source)) return null
            lower = problem.intBounds.lower(source)
            upper = problem.intBounds.upper(source)
        }
        minimum.addProduct(cost, if (cost > 0L) lower else upper)
        maximum.addProduct(cost, if (cost > 0L) upper else lower)
    }
    if (!minimum.fitsLong() || !maximum.fitsLong()) return null
    return minimum.toLong()..maximum.toLong()
}

private fun enclosingCutoff(bound: Double): BigFraction? {
    if (!bound.isFinite()) return null
    // Wide Long incumbents can share a Double bin; its upper neighbor encloses every such source value.
    return BigFraction.ofDouble(if (abs(bound) >= EXACT_INTEGER_DOUBLE_LIMIT) bound.nextUp() else bound)
}

private fun LpEngine.sourceObjectiveCutoff(
    relaxation: LpRelaxation,
    bound: Double,
    sourceRange: LongRange? = sourceObjectiveRange(relaxation),
): BigFraction? {
    if (sourceRange != null) return enclosingCutoff(bound)
    if (!bound.isFinite() || !relaxation.model.finiteExactInput()) return null
    return if (relaxation.model.hasIntegralObjective()) {
        null
    } else {
        // A continuous incumbent can lie above its Double rounding in any bin; the upper neighbor encloses it.
        BigFraction.ofDouble(bound.nextUp())
    }
}

private fun LpRelaxation.objectiveVariableLowerBound(variable: Int, lower: BigFraction): Long? =
    objectiveVariableValue(variable, lower)?.ceilLong()

private fun LpRelaxation.objectiveVariableValue(variable: Int, value: BigFraction): BigFraction? {
    val column = intColOf.getOrNull(variable) ?: return null
    if (column !in 0 until model.n || colVarId[column] != variable || colIsBool[column]) return null
    val coefficient = model.exactCost(column)
    if (coefficient.signum() <= 0 || (0 until model.numVars).any { it != column && !model.exactCost(it).isZero }) {
        return null
    }
    val constant = BigFraction.ofLong(objectiveConstant) +
        model.sourceObjective(model.exactConstant() - coefficient * model.exactShift(column))
    val scale = model.exactState?.model?.objective?.scale?.value ?: BigFraction.ONE
    return (value - constant) * scale * coefficient.reciprocal()
}

private const val EXACT_INTEGER_DOUBLE_LIMIT = 9007199254740992.0

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
    if (cancellation()) return LpNodeOutcome(false, null) // honor the deadline before the exact certify
    // Both bounds are sound for any duals and neither dominates, so the larger wins: the
    // integer-multiplier bound carries no rounding margin, the float one never declines. A null (neither
    // available) keeps the node.
    val lb = certifiedTightObjectiveLowerBound(
        relaxation.model.alsoCharged(this),
        result.duals,
        sink.lp.certificationObserver(LpRoute.NODE),
        solveContext.certificationPolicy,
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
    if (sparseTrialActive && globalCuts.isEmpty()) {
        sparseRootLpRelaxationBound(relaxer, cancellation)
    } else {
        standaloneRootLpRelaxationBound(relaxer, globalCuts, cancellation)
    }
} catch (_: CheckedLongOverflowException) {
    Double.NaN
} catch (_: LpAssemblyCancelled) {
    Double.NaN
}

private fun LpEngine.sparseRootLpRelaxationBound(
    relaxer: CpToLpRelaxation,
    cancellation: Cancellation,
): Double {
    if (sparseTrialRemainingWork() <= 0L || cancellation()) return Double.NaN
    val root = PropagationSession(problem, cancellation)
    val relaxation = nodeRelaxation(relaxer, root) ?: run {
        sink.lp.sparseTrial = sink.lp.sparseTrial + ("RootSourceLayoutDeclined" to 1L)
        cpAdapter.reset()
        if (sparseTrialRemainingWork() <= 0L || cancellation()) return Double.NaN
        sink.lp.sparseTrial = sink.lp.sparseTrial + ("StandaloneRootFallback" to 1L)
        return standaloneRootLpRelaxationBound(relaxer, emptyList(), cancellation)
    }
    if (relaxation.model.n == 0) return Double.NaN
    try {
        while (sparseTrialRemainingWork() > 0L && !cancellation()) {
            val attempt = solveNode(relaxation.model, null, cancellation)
            if (attempt == null) {
                val metrics = propagator.lastMetrics.copy(preparationOps = 0L)
                observeRootWork(metrics, finished = !propagator.floatPaused)
                if (!propagator.floatPaused || metrics.workOps == 0L) return Double.NaN
                sink.lp.sparseTrial = sink.lp.sparseTrial +
                    ("RootPauses" to ((sink.lp.sparseTrial["RootPauses"] ?: 0L) + 1L))
                observeNodePause()
                continue
            }
            // Scoped preparation has already charged its scaling-construction reservation.
            val metrics = if (nodeUsesTrail) propagator.lastMetrics.copy(preparationOps = 0L)
                else attempt.first.lastMetrics
            observeRootSolve(attempt.first, metrics)
            val lower = attempt.second?.let { sparseRootLowerBound(relaxation.model, it, cancellation) }
            retainRootPrimalGuidance(relaxation, attempt.second, cancellation)
            return if (cancellation()) Double.NaN else projectCertifiedLowerBound(relaxation, lower)
        }
        return Double.NaN
    } finally {
        val retained = cpAdapter.retainTrialRoot(root)
        sink.lp.sparseTrial = sink.lp.sparseTrial + ("RootOwnerRetained" to if (retained) 1L else 0L)
    }
}

private fun LpEngine.standaloneRootLpRelaxationBound(
    relaxer: CpToLpRelaxation,
    globalCuts: List<Cut>,
    cancellation: Cancellation,
): Double {
    val relaxation = relaxer.build(PropagationSession(problem), globalCuts, cancellation)
    return if (relaxation.model.n == 0) {
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
            if (sparseTrialActive) {
                sparseRootLowerBound(relaxation.model, it, cancellation)
            } else {
                certifiedTightObjectiveLowerBound(
                    relaxation.model,
                    it.duals,
                    rootCertificationObserver(),
                    solveContext.certificationPolicy,
                )
            }
        }
        retainRootPrimalGuidance(relaxation, result, cancellation)
        projectCertifiedLowerBound(relaxation, lower)
    }
}

private fun LpEngine.sparseRootLowerBound(
    model: LpModel,
    result: FloatLpResult,
    cancellation: Cancellation,
): Double? {
    val remaining = sparseTrialRemainingWork()
    if (remaining <= 0L || cancellation()) return null
    val defaults = ReconstructionLimits()
    val storage = sink.lp.sparseTrial["CheckingReservedBytes"] ?: 0L
    val checked = certifyLpBoundOnly(
        model,
        result.duals,
        defaults.copy(
            maxCoordinates = model.numVars,
            maxEntries = (model.doubleView?.colPtr ?: model.csc.colPtr)[model.n] + model.m,
            maxWork = remaining,
            maxAllocation = minOf(defaults.maxAllocation, storage),
        ),
        cancellation,
        rootCertificationObserver(),
        solveContext.certificationPolicy,
    ).bound ?: return null
    if (cancellation()) return null
    val lower = if (model.hasIntegralObjective()) {
        checked.value.ceilLong()?.let(BigFraction::ofLong) ?: checked.value
    } else checked.value
    return lower.lowerBoundDouble()
}

private fun LpEngine.recordRootQueryBound(relaxation: LpRelaxation, result: FloatLpResult) {
    if (!measuresRootQueries) return
    val lower = certifiedTightObjectiveLowerBound(
        relaxation.model.alsoCharged(this),
        result.duals,
        sink.lp.certificationObserver(LpRoute.NODE),
        solveContext.certificationPolicy,
    )
    if (!params.cancellation()) recordRootLowerBound(projectCertifiedLowerBound(relaxation, lower))
}

private fun LpEngine.projectCertifiedLowerBound(relaxation: LpRelaxation, lower: Double?): Double {
    val full = lower?.let { BigFraction.ofDouble(it) }?.plus(BigFraction.ofLong(relaxation.objectiveConstant))
    val projected = full?.lowerBoundDouble()
    val integral = relaxation.model.hasIntegralObjective()
    return when {
        projected == null -> Double.NaN
        integral && sourceObjectiveRange(relaxation) == null -> Double.NaN
        integral && abs(projected) >= EXACT_INTEGER_DOUBLE_LIMIT -> projected.nextDown()
        else -> projected
    }
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
    val relaxation = relaxer.build(PropagationSession(problem), cancellation = cancellation)
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
} catch (_: LpAssemblyCancelled) {
    Double.NaN
}

/**
 * Harvest a persistent pool of **global** cuts from the root relaxation on the sparse
 * revised-simplex path. Each round solves the (cut-augmented) root LP, separates violated cuts from the
 * LP point, and keeps the fresh ones whose source proof or separator scope is global. A root box may
 * close a source-open side, so root separation alone does not make a cut global. A 64-bit overflow
 * keeps the global cuts already pooled.
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
    beginCutAttribution(LpRoute.ROOT)
    sink.lp.cutLifecycle.families = separators.mapTo(HashSet()) { it.family }.also {
        if (gomory) it.add(CutFamily.GOMORY)
        if (mir) it.add(CutFamily.MIR)
    }
    fun pool(limit: Int = CutPool.DEFAULT_MAX_CUTS) = CutPool(maxCuts = limit,
        observe = { family, stage, outcome -> sink.lp.cutLifecycle.event(family, stage, outcome) })
    var pool = pool()
    try {
        var relaxation = sink.lp.cutLifecycle.measureShared("ASSEMBLY") {
            relaxer.build(session, cancellation = cancellation)
        }
        if (relaxation.model.n == 0) return emptyList()
        if (!cutSeparationSupported(relaxation, cancellation, root = true)) return emptyList()
        if (ordinaryCutSeparation) {
            pool = pool(minOf(CutPool.DEFAULT_MAX_CUTS, relaxation.model.m))
        }
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
            var resultObserved = false
            var round = 0
            while (round++ < CUT_POOL_ROUNDS && !cancellation()) {
                relaxation.sourceMap?.withCpBounds(relaxation.model, session)?.let(pool::remap)
                pool.observe(result.primal)
                resultObserved = true
                val ctx = CutContext(problem, relaxation, result.primal, session)
                // Keep only cuts justified beyond the root box; open source sides can have invented endpoints.
                val structural = separators.flatMap { constructCuts(it, ctx) }.mapNotNull {
                    when {
                        it.provenance != null || it.tableau != null -> mapGlobalCut(it, relaxation)
                        it.global -> it
                        else -> null
                    }
                }
                val gomoryCuts = if (gomory) sink.lp.cutLifecycle.measure(CutFamily.GOMORY, "CONSTRUCTION") {
                    simplex.gomoryCuts(GOMORY_CUTS_PER_ROUND).map { it.withFamily(CutFamily.GOMORY) }
                } else emptyList()
                val mirCuts = if (mir) sink.lp.cutLifecycle.measure(CutFamily.MIR, "CONSTRUCTION") {
                    simplex.mirCuts(GOMORY_CUTS_PER_ROUND).map { it.withFamily(CutFamily.MIR) }
                } else emptyList()
                val mappedTableau = (gomoryCuts + mirCuts).mapNotNull { mapGlobalCut(it, relaxation) }
                val candidates = structural + mappedTableau
                val added = candidates.count { pool.add(it, relaxation) }
                observeRootCutAccounting(candidates.size, 0, 0)
                if (added == 0) break
                if (ordinaryCutSeparation) {
                    pool.observe(result.primal)
                    pool.retainMostActive()
                }
                val selected = pool.cuts()
                sink.lp.cutLifecycle.families = selected.mapTo(HashSet()) { it.family }
                relaxation = observeRootCutBuild(selected.size) {
                    sink.lp.cutLifecycle.measureShared("ASSEMBLY") { relaxer.build(session, selected, cancellation) }
                }
                activateCutAttribution(relaxation)
                val replacement = dualSimplex(relaxation.model, cancellation)
                ownedSimplex = replacement
                sink.lp.cutLifecycle.measureShared("RETIREMENT") { simplex.close() }
                simplex = replacement
                val next = try {
                    simplex.solve()
                } finally {
                    observeRootSolve(simplex)
                }
                if (next == null) break
                observeCutFill(next.luMaxFill, relaxation.model.m)
                result = next
                resultObserved = false
            }
            // Bound the pool the search nodes inherit by per-cut activity (tightness at the final LP point):
            // a large harvest is trimmed to the most-active cuts, the rest evicted (sound — all global).
            if (!resultObserved) pool.observe(result.primal)
            pool.retainMostActive()
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val owned = ownedSimplex
            ownedSimplex = null
            if (primaryFailure == null) {
                sink.lp.cutLifecycle.measureShared("RETIREMENT") { owned?.close() }
            } else {
                try {
                    sink.lp.cutLifecycle.measureShared("RETIREMENT") { owned?.close() }
                } catch (closeFailure: Throwable) {
                    primaryFailure.addSuppressed(closeFailure)
                }
            }
        }
    } catch (_: CheckedLongOverflowException) {
        return pool.cuts() // keep whatever stayed within 64-bit determinants — still globally valid
    } catch (_: LpAssemblyCancelled) {
        return pool.cuts()
    } finally {
        sink.lp.cutLifecycle.families = emptySet()
        sink.lp.cutLifecycle.recipe = LpRoute.NODE
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

/**
 * [RootRelaxationSize.cost] ceiling for per-candidate shave, redundancy and equality probes.
 * Bounds the cumulative cost of these solves during the root harvest.
 */
internal const val LP_HARVEST_MAX_RELAXATION_COST = 250_000L

// Exact solve for a relaxation with strict rows: prunes on an exact infeasibility verdict or a lower bound
// reaching the cutoff; null defers to the float path.
private fun LpEngine.strictSourcePrune(
    relaxation: LpRelaxation,
    session: PropagationSession,
    bound: Double,
    sink: SolveStatsSink,
    cancellation: Cancellation,
    learn: Boolean,
): LpNodeOutcome? {
    val model = relaxation.model
    val exact = model.authoritativeModel() ?: return null
    if (cancellation()) return LpNodeOutcome(false, null)
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
    if (cancellation()) return LpNodeOutcome(false, null)
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
private fun LpModel.alsoCharged(engine: LpEngine): LpModel =
    also { engine.noteNodeOverhead(extent() * LpNodeOverhead.EXACT) }
