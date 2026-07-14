package com.eignex.klause.backtrack.lp

import com.eignex.klause.backtrack.CUT_POOL_ROUNDS
import com.eignex.klause.backtrack.GOMORY_CUTS_PER_ROUND
import com.eignex.klause.backtrack.SEARCH_CUT_ROUNDS
import com.eignex.klause.backtrack.selector.VarRef
import com.eignex.klause.lp.Basis
import com.eignex.klause.lp.FloatLpResult
import com.eignex.klause.lp.IntegerCertificate
import com.eignex.klause.lp.LpModel
import com.eignex.klause.lp.LpOverflowException
import com.eignex.klause.lp.RevisedSimplex
import com.eignex.klause.lp.VarStatus
import com.eignex.klause.lp.addExact
import com.eignex.klause.lp.cut.Cut
import com.eignex.klause.lp.cut.CutContext
import com.eignex.klause.lp.cut.CutPool
import com.eignex.klause.lp.cut.CutSeparator
import com.eignex.klause.lp.integerCertify
import com.eignex.klause.lp.integerDualLowerBoundCeil
import com.eignex.klause.lp.integerFarkasRay
import com.eignex.klause.lp.mulExact
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.LpExplanation
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.lp.safeObjectiveLowerBound
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
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
        total = addExact(total, mulExact(c, if (c >= 0L) d.min else d.max))
    }
    total
} catch (_: LpOverflowException) {
    // A wrapped accumulation could overshoot the incumbent and prune wrongly; no bound is the
    // sound fallback.
    Long.MIN_VALUE
}

/** The smallest value `≥ lb` congruent to `r` modulo `g` (`g ≥ 1`, `0 ≤ r < g`). When `lb` already
 *  has residue `r` it is returned unchanged. */
internal fun roundUpToResidue(lb: Long, g: Long, r: Long): Long = lb + (r - lb).mod(g)

/** A [RevisedSimplex] over [model]. The simplex always uses Devex pricing, the Harris two-pass ratio
 *  test, the bound-flipping long step and basis equilibration — all correctness-neutral (they change
 *  only the pivot path / conditioning, never the certified optimum). */
internal fun LpEngine.dualSimplex(model: LpModel, cancellation: Cancellation): RevisedSimplex =
    RevisedSimplex(model, cancellation)

/** One branch decision on the path from the root in [lbTreeSearch]: pin/bound [varId]. */
private class LbDecision(val isBool: Boolean, val varId: Int, val lower: Boolean, val bound: Long)

/** An open node in [lbTreeSearch]: its decisions from the root and the LP bound used to order it. */
private class LbNode(val decisions: List<LbDecision>, val bound: Double)

/**
 * Best-bound (best-first) tree-search subsolver. Explores the
 * branch-and-bound tree expanding the open node with the smallest LP relaxation bound first, diving
 * toward integer-feasible leaves to find good incumbents fast — the complement of depth-first search.
 * Each node re-derives a fresh session from its root decisions, solves the node LP for an ordering
 * bound and a fractional point, and branches on the most-fractional structural variable. A leaf whose
 * LP point is integral is realized through [pinToward] (propagation-checked) into an incumbent.
 *
 * Purely a primal heuristic: it returns only fully-pinned, propagation-feasible incumbents (the caller
 * re-evaluates), and dropping a node only forgoes exploring it — so this never affects soundness or the
 * optimum, exactly like the feasibility pump. Bounded by [LB_TREE_BUDGET] node expansions and a
 * frontier cap; returns the best incumbent found, or null.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
internal fun LpEngine.lbTreeSearch(objective: LinearObjective, cancellation: Cancellation): Sample? {
    val relaxer = lpRelaxer ?: return null
    var best: Sample? = null
    var bestObj = Double.POSITIVE_INFINITY
    val frontier = ArrayList<LbNode>()
    frontier.add(LbNode(emptyList(), Double.NEGATIVE_INFINITY))
    var expansions = 0
    while (frontier.isNotEmpty() && expansions < LB_TREE_BUDGET && !cancellation()) {
        var bi = 0 // pop the open node with the smallest bound (best-first)
        for (i in 1 until frontier.size) if (frontier[i].bound < frontier[bi].bound) bi = i
        val node = frontier.removeAt(bi)
        if (node.bound >= bestObj) continue // already dominated by the incumbent
        expansions++
        val session = PropagationSession(problem)
        if (session.isUnsatAtRoot) continue
        if (node.decisions.any { applyLbDecision(session, it) is PropagationResult.Unsat }) continue
        // The cut-free base relaxation suffices for this primal dive — the harvested cuts only tighten the
        // ordering bound, never the feasibility of a realized incumbent (which pinToward re-checks).
        val relaxation = nodeRelaxation(relaxer, session)
        if (relaxation.model.n == 0) continue
        val result = dualSimplex(relaxation.model, cancellation).solve() ?: continue // infeasible / unknown ⇒ drop
        if (result.objective >= bestObj) continue
        val frac = mostFractionalCol(relaxation, result.primal)
        if (frac == null) {
            // Integer LP point: realize it as an incumbent (pinToward propagates + checks feasibility).
            pinToward(session, relaxation) { col -> if (col in result.primal.indices) result.primal[col] else null }
                ?.let { s ->
                    val obj = objective.evaluate(s)
                    if (obj < bestObj) {
                        best = s
                        bestObj = obj
                    }
                }
            continue
        }
        val (v, isBool, f) = frac
        if (isBool) {
            frontier.add(LbNode(node.decisions + LbDecision(true, v, false, 0L), result.objective))
            frontier.add(LbNode(node.decisions + LbDecision(true, v, false, 1L), result.objective))
        } else {
            frontier.add(LbNode(node.decisions + LbDecision(false, v, false, floor(f).toLong()), result.objective))
            frontier.add(LbNode(node.decisions + LbDecision(false, v, true, ceil(f).toLong()), result.objective))
        }
        while (frontier.size > LB_TREE_FRONTIER_CAP) { // bound memory: drop the worst (highest-bound) node
            var wi = 0
            for (i in 1 until frontier.size) if (frontier[i].bound > frontier[wi].bound) wi = i
            frontier.removeAt(wi)
        }
    }
    return best
}

/** Apply an [LbDecision] to [session], returning the propagation result (Unsat ⇒ the node is dead). */
private fun applyLbDecision(session: PropagationSession, d: LbDecision): PropagationResult = when {
    d.isBool -> session.implyBool(d.varId, d.bound == 1L)
    d.lower -> session.implyIntAtLeast(d.varId, d.bound)
    else -> session.implyIntAtMost(d.varId, d.bound)
}

/** The structural column whose LP value is furthest from an integer, as `(varId, isBool, value)`, or
 *  null when every CP-backed structural column is integral (an integer LP point). */
private fun mostFractionalCol(relaxation: LpRelaxation, primal: DoubleArray): Triple<Int, Boolean, Double>? {
    var best: Triple<Int, Boolean, Double>? = null
    var bestFrac = LB_TREE_FRAC_TOL
    for (col in relaxation.colVarId.indices) {
        val v = relaxation.colVarId[col]
        if (v < 0 || col >= primal.size) continue
        val lp = primal[col]
        val frac = abs(lp - round(lp))
        if (frac > bestFrac) {
            bestFrac = frac
            best = Triple(v, relaxation.colIsBool[col], lp)
        }
    }
    return best
}

/**
 * Reduced-cost-average branching: pick the unassigned variable with the highest LP branch score
 * (reduced-cost pseudo-cost × fractionality, [LpHints.branchScore]), or null when LP branching is off,
 * the LP gives no fractional signal, or the residual problem is too wide to scan. Purely advisory — the
 * descent falls back to the configured `VariableSelector` on null, and any chosen variable is a sound
 * branch, so search stays complete and correct regardless. `O(unassigned)` per call, capped.
 */
internal fun LpEngine.lpBranchPick(session: PropagationSession, hints: LpHints?): VarRef? {
    hints ?: return null
    if (problem.numBoolVars + problem.numIntVars > LP_BRANCH_SCAN_CAP) return null // too wide ⇒ delegate
    var best: VarRef? = null
    var bestScore = LP_BRANCH_MIN_SCORE
    for (b in 0 until problem.numBoolVars) {
        if (session.boolValue(b) != null) continue
        val s = hints.branchScore(VarRef.Bool(b))
        if (!s.isNaN() && s > bestScore) {
            bestScore = s
            best = VarRef.Bool(b)
        }
    }
    for (i in 0 until problem.numIntVars) {
        if (session.intDomain(i).size <= 1) continue
        val s = hints.branchScore(VarRef.IntVar(i))
        if (!s.isNaN() && s > bestScore) {
            bestScore = s
            best = VarRef.IntVar(i)
        }
    }
    return best
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
    cancellation: Cancellation,
    hints: LpHintSink? = null,
    learn: Boolean = false,
    warm: Basis? = null,
    cutsAllowed: Boolean = false,
): LpNodeOutcome = try {
    sink.lp.clockStart()
    sparseSafePrune(
        relaxer, session, bound, sink, cancellation, objectiveVar, objectiveAscending, hints, learn, warm,
        cutsAllowed,
    )
} catch (_: LpOverflowException) {
    // A coefficient overflow in the relaxation build loses the bound; recover a sound one via the
    // integer-multiplier 128-bit certification. A failure just keeps the node.
    sparseCertifiedPrune(relaxer, session, bound, sink, cancellation)
} finally {
    sink.lp.clockStop()
}

/**
 * Fold the pooled global cuts the LP point [res] violates into the node relaxation and re-solve
 * (#40 / D8). [CutPool.select] ranks the pool by efficacy (normalised violation) at [res], drops the
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
    val selected = cutPool.select(res.primal, cutPool.maxCuts)
    if (selected.isEmpty()) return base to res
    val tightened = try {
        relaxer.build(session, selected)
    } catch (_: LpOverflowException) {
        return base to res // overflow in the cut-augmented build: keep the prior (sound) relaxation
    }
    val r = dualSimplex(tightened.model, cancellation).solve() ?: return base to res
    sink.lp.observeSolve()
    return tightened to r
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
    sink: SolveStatsSink,
    cancellation: Cancellation,
    objectiveVar: Int,
    objectiveAscending: Boolean,
    hints: LpHintSink? = null,
    learn: Boolean = false,
    warm: Basis? = null,
    cutsAllowed: Boolean = false,
): LpNodeOutcome {
    val relaxation = nodeRelaxation(relaxer, session)
    if (relaxation.model.n == 0) return LpNodeOutcome(false, null)
    sink.lp.observeSolve()
    // Always solve: an infeasible relaxation prunes the node regardless of incumbent or objective.
    val simplex = dualSimplex(relaxation.model, cancellation)
    val result = simplex.solve(warm) ?: run {
        // Infeasibility prune (#705): a dual-unbounded termination is only a *candidate* infeasibility —
        // confirm it with an exact Farkas certificate before pruning (the float ray alone is not sound).
        // Any other failure (non-convergence / singular) keeps the node.
        val floatRay = simplex.infeasibleRay
        val ray = if (floatRay != null) integerFarkasRay(relaxation.model, floatRay) else null
        if (ray != null) {
            sink.lp.observeInfeasiblePrune()
            // With learning, the Farkas ray becomes a bound-atom nogood (#247) for a 1UIP backjump;
            // null (auxiliary column / unbacked non-global row / constraint-only) prunes reason-less.
            val clause = if (learn) LpExplanation.infeasibilityClause(relaxation, ray, session) else null
            return LpNodeOutcome(true, null, clause)
        }
        return LpNodeOutcome(false, null)
    }
    sink.lp.observePivots(result.pivots)
    sink.lp.observeLuFill(result.luMaxFill, result.luMaxDensity)
    // LP-guided branching (#287): record the fractional primal + reduced costs so the descent can order
    // branch values toward the LP point and pick reduced-cost-impactful fractional variables. Purely
    // advisory — it never changes feasibility or the optimum.
    hints?.record(relaxation, result.primal, result.duals)
    // The optimal basis is cached by the caller and reused to warm-start this node's children (#705).
    // It is the basis of the un-tightened persistent relaxation, which the children re-solve.
    val optimalBasis = result.basis
    val canPrune = bound.isFinite()
    val canPropagate = objectiveVar >= 0 && objectiveAscending
    if (!canPrune && !canPropagate) {
        return LpNodeOutcome(false, optimalBasis) // feasible, nothing more to deduce
    }
    // During-search separation (#41): at a gated shallow node, tighten this node's relaxation with the
    // cuts its LP point violates. Global cuts are persisted into the pool (descendants inherit them);
    // node-local cuts tighten only this solve, so they never leak to a sibling and the bound stays sound.
    // The bound, certificate and reduced-cost fixing below read the tightened relaxation; the cached
    // warm-start basis stays the cut-free one for the children.
    // The pooled global cuts this node's LP point violates are folded in first (#40 / D8): the
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
            // Per-separator gating (#59): skip a family the gate has disabled for being unproductive, and
            // credit each family it does run with whether it produced a violated cut this round.
            val fresh = ArrayList<Cut>()
            for (i in lpSeparators.indices) {
                if (!lpSeparatorGate.shouldRun(i)) continue
                val produced = lpSeparators[i].separate(ctx)
                lpSeparatorGate.record(i, produced.isNotEmpty())
                fresh.addAll(produced)
            }
            if (fresh.isEmpty()) break
            recordSearchCuts(fresh, boundRes.primal) // persist the global cuts into the pool
            for (c in fresh) if (!c.global) localCuts.add(c)
            val tightened = try {
                relaxer.build(session, cutPool.select(boundRes.primal, cutPool.maxCuts) + localCuts)
            } catch (_: LpOverflowException) {
                break // overflow in the cut-augmented build: keep the prior (sound) relaxation
            }
            val r = dualSimplex(tightened.model, cancellation).solve() ?: break
            sink.lp.observeSolve()
            boundRel = tightened
            boundRes = r
        }
    }
    val safe = safeObjectiveLowerBound(boundRel.model, boundRes.duals) ?: return LpNodeOutcome(false, optimalBasis)
    val full = safe + boundRel.objectiveConstant.toDouble()
    if (canPrune && full >= bound) {
        sink.lp.observePrune()
        return LpNodeOutcome(true, null)
    }
    // The exact basis-certificate backs both the learnable objective-bound reason (#281/#705) and the
    // reduced-cost fixing. Compute it once when either needs it; a singular/unbounded certify yields
    // null and both fall back to the cheap reason-less paths, which is sound.
    val cert = if ((learn && canPropagate) || canPrune) {
        integerCertify(boundRel.model, boundRes.duals)
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
 * Reduced-cost fixing (#21/#282) from the [IntegerCertificate], over exact scaled integers. At the LP
 * optimum a nonbasic column sits at a bound; moving it Δ integer steps raises the
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
    cert: IntegerCertificate,
    basis: Basis,
    session: PropagationSession,
    bound: Double,
    sink: SolveStatsSink,
    objectiveVar: Int = -1,
    objectiveAscending: Boolean = true,
    learn: Boolean = false,
): Boolean {
    val improvingMax = ceil(bound).toLong() - 1L // best objective that still beats the incumbent
    if (!cert.improvingGapNonNegative(improvingMax)) return false // gap ≥ 0 (node not bound-pruned)
    val status = basis.status
    // Learnable reason support (#282): a fixing of column `col` is justified
    // by the OTHER support columns' seated bounds (premise side = reduced-cost sign) + the incumbent
    // bound `objVar ≤ improvingMax` + the validity premises of any dual-weighted non-global row.
    // Expressible only with a single-var ascending objective whose live upper bound already meets the
    // incumbent atom, all dual-weighted non-global rows premise-backed, and no support on an aux column.
    var canLearn = learn && objectiveVar >= 0 && objectiveAscending &&
        session.intDomain(objectiveVar).max <= improvingMax
    val supportCols = IntArrayList()
    val supportLits = IntArrayList()
    if (canLearn) {
        val seen = IntHashSet()
        val premLits = IntArrayList()
        if (LpExplanation.addDualRowPremiseLits(premLits, seen, relaxation, cert, session)) {
            for (k in 0 until premLits.size) {
                supportCols.add(-1) // row premise: part of every fixing's reason, never excluded
                supportLits.add(premLits[k])
            }
            for (c in relaxation.colVarId.indices) {
                if (status[c] == VarStatus.BASIC) continue
                val sign = cert.reducedCostSign(c)
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
    val incumbentLit = if (canLearn) session.boundLeLit(objectiveVar, improvingMax, positive = false) else 0

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
            liveMin = d.min
            liveMax = d.max
        }
        if (liveMin == liveMax) continue
        val span = liveMax - liveMin
        val res = when (st) {
            // At lower bound: reducedCost ≥ 0; it can rise at most floor(gap / d) steps.
            VarStatus.AT_LOWER -> {
                if (cert.reducedCostSign(col) <= 0) continue
                val dMax = cert.fixSteps(col, improvingMax) ?: continue // overflow ⇒ skip (sound)
                if (dMax >= span) continue
                val hi = liveMin + dMax
                when {
                    isBool -> session.implyBool(varId, false)
                    canLearn -> session.implyIntAtMostWithReason(varId, hi, reasonFor(col))
                    else -> session.implyIntAtMost(varId, hi)
                }
            }

            // At upper bound: reducedCost ≤ 0; symmetric, tighten the lower bound.
            VarStatus.AT_UPPER -> {
                if (cert.reducedCostSign(col) >= 0) continue
                val dMax = cert.fixSteps(col, improvingMax) ?: continue
                if (dMax >= span) continue
                val lo = liveMax - dMax
                when {
                    isBool -> session.implyBool(varId, true)
                    canLearn -> session.implyIntAtLeastWithReason(varId, lo, reasonFor(col))
                    else -> session.implyIntAtLeast(varId, lo)
                }
            }

            VarStatus.BASIC -> continue
        }
        if (res is PropagationResult.Unsat) {
            sink.lp.observePrune()
            return true
        }
        sink.lp.observeFix()
    }
    return false
}

/**
 * Sound objective lower bound from the float revised simplex + integer-multiplier 128-bit
 * certification, used when the cheap safe-bound path overflowed during the relaxation build. Prunes when
 * the certified bound (plus the relaxation's objective constant) reaches the incumbent. Any failure
 * keeps the node.
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
    val result = dualSimplex(relaxation.model, cancellation).solve() ?: return LpNodeOutcome(false, null)
    if (cancellation()) return LpNodeOutcome(false, null) // honor the deadline before the exact certify
    // The integer-multiplier 128-bit bound: round the float duals and evaluate the Lagrangian exactly.
    // Sound by construction (a valid lower bound for any integer multipliers); includes the model's
    // lo-shift constant. A null (no finite bound) keeps the node.
    val lb = integerDualLowerBoundCeil(relaxation.model, result.duals)
        ?: return LpNodeOutcome(false, null)
    val full = try {
        addExact(lb, relaxation.objectiveConstant)
    } catch (_: LpOverflowException) {
        return LpNodeOutcome(false, null)
    }
    return if (full.toDouble() >= bound) {
        sink.lp.observePrune()
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
        val result = dualSimplex(relaxation.model, cancellation).solve()
        val safe = result?.let { safeObjectiveLowerBound(relaxation.model, it.duals) }
        if (safe != null) safe + relaxation.objectiveConstant.toDouble() else Double.NaN
    }
} catch (_: LpOverflowException) {
    Double.NaN
}

/**
 * The **true** root LP optimum (the float simplex objective, plus the relaxation's objective constant),
 * or NaN when the relaxation is empty / not optimal / overflows. Unlike [rootLpRelaxationBound] this is
 * the raw LP value, not the Neumaier–Shcherbina safe under-estimate — so it reflects how much a hull
 * actually tightens the relaxation, which the safe bound can miss. Used only to compare relaxation
 * variants in [LpEngine.pruneIneffectiveHulls], never as a sound prune bound.
 */
internal fun LpEngine.rootLpObjective(
    relaxer: CpToLpRelaxation,
    cancellation: Cancellation = Cancellation.Never,
): Double = try {
    val relaxation = relaxer.build(PropagationSession(problem))
    if (relaxation.model.n == 0) {
        Double.NaN
    } else {
        val result = dualSimplex(relaxation.model, cancellation).solve()
        if (result != null) result.objective + relaxation.objectiveConstant.toDouble() else Double.NaN
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
        var simplex = dualSimplex(relaxation.model, cancellation)
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
            simplex = dualSimplex(relaxation.model, cancellation)
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

/** Minimum LP branch score (reduced-cost × fractionality) to override the configured selector; below
 *  this a variable is effectively LP-integral / cost-free, so the configured heuristic decides. */
private const val LP_BRANCH_MIN_SCORE = 1e-9

/** Skip reduced-cost-average branching above this variable count — the per-decision scan is `O(vars)`. */
private const val LP_BRANCH_SCAN_CAP = 8192

/** [RootRelaxationSize.cost] ceiling above which the harvest skips its shave/redundancy/equality probes:
 *  on a relaxation this large the per-candidate solves dominate the time budget and lose instances the
 *  search would otherwise solve. Calibrated from an mzn-bench A/B with a wide margin — the helped models
 *  measured ≤ ~48k (evilshop 155×155, the largest gain) while the cost regressions were ≥ ~1.6M
 *  (fast-food 501×1048, diameterc-mst 1797×4066), so the gap is two orders of magnitude. */
internal const val LP_HARVEST_MAX_RELAXATION_COST = 250_000L

/** Node-expansion budget for the best-bound tree-search subsolver (each expansion is one node LP). */
private const val LB_TREE_BUDGET = 256

/** Cap on the best-bound search frontier; the highest-bound nodes are dropped past it (memory bound). */
private const val LB_TREE_FRONTIER_CAP = 512

/** A structural column's LP value within this of an integer is treated as integral (no branch). */
private const val LB_TREE_FRAC_TOL = 1e-6
