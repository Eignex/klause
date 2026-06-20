package com.eignex.klause.solver.backtrack.lp

import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.backtrack.CUT_POOL_ROUNDS
import com.eignex.klause.solver.backtrack.GOMORY_CUTS_PER_ROUND
import com.eignex.klause.solver.backtrack.SEARCH_CUT_ROUNDS
import com.eignex.klause.solver.backtrack.snapshotAssignment
import com.eignex.klause.solver.lp.Basis
import com.eignex.klause.solver.lp.ExactBasisCertifier
import com.eignex.klause.solver.lp.LpOverflowException
import com.eignex.klause.solver.lp.RevisedSimplex
import com.eignex.klause.solver.lp.VarStatus
import com.eignex.klause.solver.lp.addExact
import com.eignex.klause.solver.lp.cut.Cut
import com.eignex.klause.solver.lp.cut.CutContext
import com.eignex.klause.solver.lp.cut.CutPool
import com.eignex.klause.solver.lp.cut.CutSeparator
import com.eignex.klause.solver.lp.mulExact
import com.eignex.klause.solver.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.solver.lp.relaxation.LpExplanation
import com.eignex.klause.solver.lp.relaxation.LpRelaxation
import com.eignex.klause.solver.lp.safeObjectiveLowerBound
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.propagation.PropagationResult
import com.eignex.klause.solver.propagation.PropagationSession
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.BigRational
import com.eignex.klause.util.IntArrayList
import kotlin.math.ceil
import kotlin.math.round

/**
 * Sound lower bound on a [LinearObjective] given the current partial assignment in
 * [session]. Pinned vars contribute their exact value; unpinned bool vars take the
 * weight (or 0) that makes their contribution smallest; unpinned int vars take the
 * domain endpoint matching the coefficient's sign.
 */
internal fun LpEngine.linearLowerBound(obj: LinearObjective, session: PropagationSession): Long = try {
    var total = obj.constant
    val sp = session.problem
    val nb = minOf(sp.numBoolVars, obj.boolWeights.size)
    for (b in 0 until nb) {
        val w = obj.boolWeights[b]
        val v = session.boolValue(b)
        total = addExact(
            total,
            when {
                v == true -> w
                v == false -> 0L
                w < 0L -> w
                else -> 0L
            },
        )
    }
    val ni = minOf(sp.numIntVars, obj.intCoefficients.size)
    for (i in 0 until ni) {
        val c = obj.intCoefficients[i]
        if (c == 0L) continue
        val d = session.intDomain(i)
        total = addExact(total, mulExact(c, if (c >= 0L) d.min.toLong() else d.max.toLong()))
    }
    total
} catch (_: LpOverflowException) {
    // A wrapped accumulation could overshoot the incumbent and prune wrongly; no bound is the
    // sound fallback.
    Long.MIN_VALUE
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
 * LP-relaxation bounding, reduced-cost fixing and infeasibility pruning (#705) for one search node,
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
    globalCuts: List<Cut>,
    cancellation: Cancellation,
    hints: LpHints? = null,
    learn: Boolean = false,
    warm: Basis? = null,
): LpNodeOutcome = try {
    sink.lpClockStart()
    sparseSafePrune(
        relaxer, session, bound, globalCuts, sink, cancellation, objectiveVar, objectiveAscending, hints, learn, warm,
    )
} catch (_: LpOverflowException) {
    // A determinant or coefficient overflow in the relaxation build loses the bound; recover a sound
    // one via the exact BigInt basis-certification pipeline. A failure just keeps the node.
    sparseCertifiedPrune(relaxer, session, bound, globalCuts, sink, cancellation)
} finally {
    sink.lpClockStop()
}

/**
 * Cheap sound prune + objective-bound propagation for the LP path (#705): float revised
 * simplex for the duals, then the O(nnz) Neumaier–Shcherbina safe bound — no exact certify on the
 * common path, so the per-node cost is bounded and `-t` is honored. Prunes when the relaxation is
 * infeasible (exact Farkas certificate) or the safe bound reaches the incumbent, tightens an
 * ascending objective variable up to `ceil(safe bound)` (reason-less, a sound conflict-analysis
 * leaf), and fixes reduced-cost-dominated variables off their bounds. Any solver failure keeps the node.
 */
@Suppress("LongParameterList")
internal fun LpEngine.sparseSafePrune(
    relaxer: CpToLpRelaxation,
    session: PropagationSession,
    bound: Double,
    globalCuts: List<Cut>,
    sink: SolveStatsSink,
    cancellation: Cancellation,
    objectiveVar: Int,
    objectiveAscending: Boolean,
    hints: LpHints? = null,
    learn: Boolean = false,
    warm: Basis? = null,
): LpNodeOutcome {
    val relaxation = nodeRelaxation(relaxer, session, globalCuts)
    if (relaxation.model.n == 0) return LpNodeOutcome(false, null)
    sink.observeLpSolve()
    // Always solve: an infeasible relaxation prunes the node regardless of incumbent or objective.
    val simplex = RevisedSimplex(relaxation.model, cancellation)
    val result = simplex.solve(warm) ?: run {
        // Infeasibility prune (#705): a dual-unbounded termination is only a *candidate* infeasibility —
        // confirm it with an exact Farkas certificate before pruning (the float ray alone is not sound).
        // Any other failure (non-convergence / singular) keeps the node.
        val basis = simplex.infeasibleBasis
        val ray = if (basis != null) {
            ExactBasisCertifier.farkasRay(relaxation.model, basis, simplex.infeasibleRow)
        } else {
            null
        }
        if (ray != null) {
            sink.observeLpInfeasiblePrune()
            // With learning, the Farkas ray becomes a bound-atom nogood (#247) for a 1UIP backjump;
            // null (auxiliary column / unbacked non-global row / constraint-only) prunes reason-less.
            val clause = if (learn) LpExplanation.infeasibilityClause(relaxation, ray, session) else null
            return LpNodeOutcome(true, null, clause)
        }
        return LpNodeOutcome(false, null)
    }
    sink.observeLpPivots(result.pivots)
    sink.observeLpLuFill(result.luMaxFill, result.luMaxDensity)
    // LP-guided branching (#287): record the fractional primal so the descent can order branch values
    // toward the LP point. Purely advisory — it never changes feasibility or the optimum.
    hints?.record(relaxation, result.primal)
    // The optimal basis is cached by the caller and reused to warm-start this node's children (#705).
    // It is the basis of the un-tightened persistent relaxation, which the children re-solve.
    val optimalBasis = result.basis
    val canPrune = bound.isFinite()
    val canPropagate = objectiveVar >= 0 && objectiveAscending
    if (!canPrune && !canPropagate) {
        return LpNodeOutcome(false, optimalBasis) // feasible, nothing more to deduce
    }
    // During-search separation (#41): at a gated shallow node, tighten this node's relaxation with the
    // cuts its LP point violates. Global cuts are persisted into the pool (descendants inherit them via
    // the rebuilt base); node-local cuts tighten only this solve, so they never leak to a sibling and
    // the bound stays sound. The bound, certificate and reduced-cost fixing below read the tightened
    // relaxation; the cached warm-start basis stays the un-tightened one for the children.
    var boundRel = relaxation
    var boundRes = result
    if (params.lpPlan.cuts && session.decisionLevel in 1..params.lpPlan.cutSearchMaxDepth &&
        lpSeparators.isNotEmpty()
    ) {
        val localCuts = ArrayList<Cut>()
        var rounds = 0
        while (rounds++ < SEARCH_CUT_ROUNDS && !cancellation()) {
            val ctx = CutContext(problem, boundRel, boundRes.primal, session)
            val fresh = lpSeparators.flatMap { it.separate(ctx) }
            if (fresh.isEmpty()) break
            recordSearchCuts(fresh, boundRes.primal) // persist the global cuts; invalidate the base
            for (c in fresh) if (!c.global) localCuts.add(c)
            val tightened = try {
                relaxer.build(session, lpGlobalCuts + localCuts)
            } catch (_: LpOverflowException) {
                break // overflow in the cut-augmented build: keep the prior (sound) relaxation
            }
            val r = RevisedSimplex(tightened.model, cancellation).solve() ?: break
            sink.observeLpSolve()
            boundRel = tightened
            boundRes = r
        }
    }
    val safe = safeObjectiveLowerBound(boundRel.model, boundRes.duals) ?: return LpNodeOutcome(false, optimalBasis)
    val full = safe + boundRel.objectiveConstant.toDouble()
    if (canPrune && full >= bound) {
        sink.observeLpPrune()
        return LpNodeOutcome(true, null)
    }
    // The exact basis-certificate backs both the learnable objective-bound reason (#281/#705) and the
    // reduced-cost fixing. Compute it once when either needs it; a singular/unbounded certify yields
    // null and both fall back to the cheap reason-less paths, which is sound.
    val cert = if ((learn && canPropagate) || canPrune) {
        ExactBasisCertifier.certify(boundRel.model, boundRes.basis)
    } else {
        null
    }
    // Objective-bound propagation (#281): the integer objective is ≥ ceil(LP lower bound). With
    // learning, propagate the exact certified bound (tighter than the safe bound) and attach the
    // reduced-cost reason so an Unsat tightening backjumps; otherwise tighten to ceil(safe bound)
    // reason-less (a sound conflict-analysis leaf). The safe bound only under-estimates the optimum,
    // so either floor ≤ the true optimum.
    if (canPropagate && full.isFinite()) {
        val exactFloor = if (learn && cert != null) {
            (cert.objective + BigRational.of(boundRel.objectiveConstant)).ceil().toLongOrNull()
        } else {
            null
        }
        val lpFloor = exactFloor ?: ceil(full).takeIf { it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() }
            ?.toLong()
        if (lpFloor != null && lpFloor in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            val reason = if (learn && cert != null) {
                LpExplanation.objectiveBoundReason(boundRel, cert, session)
            } else {
                null
            }
            val res = if (reason != null) {
                session.implyIntAtLeastWithReason(objectiveVar, lpFloor.toInt(), reason)
            } else {
                session.implyIntAtLeast(objectiveVar, lpFloor.toInt())
            }
            if (res is PropagationResult.Unsat) {
                sink.observeLpPrune()
                return LpNodeOutcome(true, null)
            }
        }
    }
    // Reduced-cost fixing (#21) on the exact certified reduced costs — needs a finite incumbent for
    // the improving gap, so it runs only when pruning is possible.
    if (canPrune && cert != null &&
        applySparseReducedCostFixing(
            boundRel, cert, boundRes.basis, session, bound, sink, objectiveVar, objectiveAscending, learn,
        )
    ) {
        return LpNodeOutcome(true, null)
    }
    return LpNodeOutcome(false, optimalBasis)
}

/**
 * Reduced-cost fixing (#21/#282) from the exact [ExactBasisCertifier.Certificate], over exact
 * rationals. At the LP optimum a nonbasic column sits at a bound; moving it Δ integer steps raises the
 * objective by `|reducedCost|·Δ`, and any incumbent-beating solution has objective `≤ ⌈bound⌉ − 1`, so
 * the column can move at most `floor((improvingMax − lpOptimum) / |reducedCost|)` steps before it alone
 * overshoots. With [learn] each integer fixing carries the LP dual-decomposition reason (the other
 * support columns' seated bounds + the incumbent bound + any dual-weighted non-global row's premises);
 * when the reason is inexpressible the fixing falls back to a reason-less level-local tightening (a
 * conflict-analysis leaf). Returns true if a reduction empties a domain (the node is then pruned).
 */
@Suppress("LongParameterList", "CyclomaticComplexMethod")
internal fun LpEngine.applySparseReducedCostFixing(
    relaxation: LpRelaxation,
    cert: ExactBasisCertifier.Certificate,
    basis: Basis,
    session: PropagationSession,
    bound: Double,
    sink: SolveStatsSink,
    objectiveVar: Int = -1,
    objectiveAscending: Boolean = true,
    learn: Boolean = false,
): Boolean {
    val improvingMax = ceil(bound).toLong() - 1L // best objective that still beats the incumbent
    val slack = BigRational.of(improvingMax) - cert.objective // exact gap; ≥ 0 (node not bound-pruned)
    if (slack.signum() < 0) return false
    val status = basis.status
    // Learnable reason support (#282): a fixing of column `col` is justified
    // by the OTHER support columns' seated bounds (premise side = reduced-cost sign) + the incumbent
    // bound `objVar ≤ improvingMax` + the validity premises of any dual-weighted non-global row.
    // Expressible only with a single-var ascending objective whose live upper bound already meets the
    // incumbent atom, all dual-weighted non-global rows premise-backed, and no support on an aux column.
    var canLearn = learn && objectiveVar >= 0 && objectiveAscending &&
        improvingMax in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() &&
        session.intDomain(objectiveVar).max.toLong() <= improvingMax
    val supportCols = IntArrayList()
    val supportLits = IntArrayList()
    if (canLearn) {
        val seen = HashSet<Int>()
        val premLits = IntArrayList()
        if (LpExplanation.addDualRowPremiseLits(premLits, seen, relaxation, cert, session)) {
            for (k in 0 until premLits.size) {
                supportCols.add(-1) // row premise: part of every fixing's reason, never excluded
                supportLits.add(premLits[k])
            }
            for (c in relaxation.colVarId.indices) {
                if (status[c] == VarStatus.BASIC) continue
                val sign = cert.reducedCost[c].signum()
                if (sign == 0) continue
                val lit = LpExplanation.premiseLit(relaxation, session, c, lowerSide = sign > 0)
                if (lit == LpExplanation.PREMISE_AUX) {
                    canLearn = false
                    break
                }
                if (lit == LpExplanation.PREMISE_NONE || !seen.add(lit)) continue
                supportCols.add(c)
                supportLits.add(lit)
            }
        } else {
            canLearn = false
        }
    }
    val incumbentLit = if (canLearn) session.boundLeLit(objectiveVar, improvingMax.toInt(), positive = false) else 0

    // Reason for fixing `col`: every support column's seated-bound negation except col's own, plus the
    // incumbent objective bound. (col's own bound is the variable moving, not a premise.)
    fun reasonFor(col: Int): IntArray {
        val out = IntArrayList(supportCols.size + 1)
        for (k in 0 until supportCols.size) if (supportCols[k] != col) out.add(supportLits[k])
        out.add(incumbentLit)
        return out.toIntArray()
    }
    for (col in relaxation.colVarId.indices) {
        val st = status[col]
        if (st == VarStatus.BASIC) continue
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
            liveMin = d.min.toLong()
            liveMax = d.max.toLong()
        }
        if (liveMin == liveMax) continue
        val span = liveMax - liveMin
        val dj = cert.reducedCost[col]
        val res = when (st) {
            // At lower bound: reducedCost ≥ 0; it can rise at most floor(slack / d) steps.
            VarStatus.AT_LOWER -> {
                if (dj.signum() <= 0) continue
                val dMaxBig = (slack / dj).floor()
                if (dMaxBig >= BigInt.of(span)) continue
                val dMax = dMaxBig.toLongOrNull() ?: continue // overflow ⇒ skip (sound)
                val hi = (liveMin + dMax).toInt()
                when {
                    isBool -> session.implyBool(varId, false)
                    canLearn -> session.implyIntAtMostWithReason(varId, hi, reasonFor(col))
                    else -> session.implyIntAtMost(varId, hi)
                }
            }

            // At upper bound: reducedCost ≤ 0; symmetric, tighten the lower bound.
            VarStatus.AT_UPPER -> {
                if (dj.signum() >= 0) continue
                val dMaxBig = (slack / (BigRational.ZERO - dj)).floor()
                if (dMaxBig >= BigInt.of(span)) continue
                val dMax = dMaxBig.toLongOrNull() ?: continue
                val lo = (liveMax - dMax).toInt()
                when {
                    isBool -> session.implyBool(varId, true)
                    canLearn -> session.implyIntAtLeastWithReason(varId, lo, reasonFor(col))
                    else -> session.implyIntAtLeast(varId, lo)
                }
            }

            VarStatus.BASIC -> continue
        }
        if (res is PropagationResult.Unsat) {
            sink.observeLpPrune()
            return true
        }
        sink.observeLpFix()
    }
    return false
}

/**
 * Sound objective lower bound from the float revised simplex + exact BigInt basis-certification, used
 * when the cheap safe-bound path overflowed during the relaxation build. Prunes when the certified
 * bound (plus the relaxation's objective constant) reaches the incumbent. Any failure keeps the node.
 */
internal fun LpEngine.sparseCertifiedPrune(
    relaxer: CpToLpRelaxation,
    session: PropagationSession,
    bound: Double,
    globalCuts: List<Cut>,
    sink: SolveStatsSink,
    cancellation: Cancellation,
): LpNodeOutcome {
    if (!bound.isFinite()) return LpNodeOutcome(false, null) // no incumbent to prune against
    val relaxation = nodeRelaxation(relaxer, session, globalCuts)
    if (relaxation.model.n == 0) return LpNodeOutcome(false, null)
    sink.observeLpSolve()
    val result = RevisedSimplex(relaxation.model, cancellation).solve() ?: return LpNodeOutcome(false, null)
    if (cancellation()) return LpNodeOutcome(false, null) // honor the deadline before the exact certify
    val lb = ExactBasisCertifier.lowerBoundCeil(relaxation.model, result.basis) ?: return LpNodeOutcome(false, null)
    val full = try {
        addExact(lb, relaxation.objectiveConstant)
    } catch (_: LpOverflowException) {
        return LpNodeOutcome(false, null)
    }
    return if (full.toDouble() >= bound) {
        sink.observeLpPrune()
        LpNodeOutcome(true, null)
    } else {
        LpNodeOutcome(false, null)
    }
}

/**
 * The root-node LP relaxation objective (with the harvested [globalCuts]) on the undecided problem,
 * or NaN when the relaxation is empty / not optimal / overflows — the revised simplex + Neumaier–
 * Shcherbina safe bound, the same sound bound the per-node prune reports. Solved once before search,
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
        val result = RevisedSimplex(relaxation.model, cancellation).solve()
        val safe = result?.let { safeObjectiveLowerBound(relaxation.model, it.duals) }
        if (safe != null) safe + relaxation.objectiveConstant.toDouble() else Double.NaN
    }
} catch (_: LpOverflowException) {
    Double.NaN
}

/**
 * Harvest a persistent pool of **global** cuts from the root relaxation (#22/#705) on the sparse
 * revised-simplex path. Each round solves the (cut-augmented) root LP, separates violated cuts from the
 * LP point, and keeps the fresh ones; because the separation reads the undecided root domains, every
 * harvested cut is valid at *every* solution of the problem, so it is forced [Cut.global] = true and
 * stays sound when applied at any node. Determinant overflow keeps whatever cuts stayed within 64 bits.
 */
@Suppress("LongParameterList")
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
        var simplex = RevisedSimplex(relaxation.model, cancellation)
        var result = simplex.solve() ?: return emptyList()
        var round = 0
        while (round++ < CUT_POOL_ROUNDS && !cancellation()) {
            val ctx = CutContext(problem, relaxation, result.primal, session)
            // Structural separators read the LP point and factor structure (not the constraint rows), so a
            // cut they separate over the undecided root is valid at every solution — force it global.
            val structural = separators.flatMap { it.separate(ctx) }
                .map { if (it.global) it else Cut(it.cols, it.coeffs, it.rel, it.rhs, global = true) }
            // Gomory/MIR combine rows; tableauCuts already marks one global iff its row weights avoid every
            // non-global (big-M) row. Only the genuinely-global ones may join the tree-wide pool.
            val gomoryCuts = if (gomory) simplex.gomoryCuts(GOMORY_CUTS_PER_ROUND) else emptyList()
            val mirCuts = if (mir) simplex.mirCuts(GOMORY_CUTS_PER_ROUND) else emptyList()
            val added = pool.addAll(structural + (gomoryCuts + mirCuts).filter { it.global })
            if (added == 0) break
            relaxation = relaxer.build(session, pool.cuts())
            simplex = RevisedSimplex(relaxation.model, cancellation)
            result = simplex.solve() ?: break
        }
        // Bound the pool the search nodes inherit by per-cut activity (tightness at the final LP point):
        // a large harvest is trimmed to the most-active cuts, the rest evicted (sound — all global).
        pool.retainMostActive(result.primal)
    } catch (_: LpOverflowException) {
        return pool.cuts() // keep whatever stayed within 64-bit determinants — still globally valid
    }
    return pool.cuts()
}

/**
 * LP-rounding primal heuristic (#287) on the sparse revised-simplex path: solve the root relaxation,
 * round each variable's fractional LP value to the nearest in-domain integer, and propagate. Returns a
 * complete assignment when rounding-then-propagation reaches a fixpoint without a wipeout, else null.
 * Sound by construction — the result is a candidate that the caller re-evaluates against the objective
 * and only keeps if feasible-and-improving; a bad rounding just yields null or a worse incumbent.
 */
internal fun LpEngine.lpRoundingProbe(objective: LinearObjective, cancellation: Cancellation): Sample? {
    val session = PropagationSession(problem)
    if (session.isUnsatAtRoot) return null
    val relaxation = CpToLpRelaxation(problem, objective).build(session)
    if (relaxation.model.n == 0) return null
    val result = try {
        RevisedSimplex(relaxation.model, cancellation).solve()
    } catch (_: LpOverflowException) {
        return null
    } ?: return null
    val primal = result.primal
    for (v in 0 until problem.numIntVars) {
        val d = session.intDomain(v)
        if (d.min == d.max) continue // already fixed by propagation
        val col = relaxation.intColOf[v]
        val target = if (col >= 0 && col < primal.size) {
            round(primal[col]).toInt().coerceIn(d.min, d.max)
        } else {
            d.min
        }
        if (session.pinInt(v, target) is PropagationResult.Unsat) return null
    }
    for (b in 0 until problem.numBoolVars) {
        if (session.boolValue(b) != null) continue
        val col = relaxation.boolColOf[b]
        val target = col >= 0 && col < primal.size && primal[col] >= 0.5
        if (session.pinBool(b, target) is PropagationResult.Unsat) return null
    }
    return snapshotAssignment(session)
}
