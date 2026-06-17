package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.lp.Basis
import com.eignex.klause.solver.lp.CpToLpRelaxation
import com.eignex.klause.solver.lp.Cut
import com.eignex.klause.solver.lp.CutContext
import com.eignex.klause.solver.lp.CutSeparator
import com.eignex.klause.solver.lp.DualSimplex
import com.eignex.klause.solver.lp.ExactBasisCertifier
import com.eignex.klause.solver.lp.FloatSimplex
import com.eignex.klause.solver.lp.LpExplanation
import com.eignex.klause.solver.lp.LpModel
import com.eignex.klause.solver.lp.LpOverflowException
import com.eignex.klause.solver.lp.LpRelaxation
import com.eignex.klause.solver.lp.LpSolution
import com.eignex.klause.solver.lp.LpStatus
import com.eignex.klause.solver.lp.RevisedSimplex
import com.eignex.klause.solver.lp.VarStatus
import com.eignex.klause.solver.lp.addExact
import com.eignex.klause.solver.lp.mulExact
import com.eignex.klause.solver.lp.safeObjectiveLowerBound
import com.eignex.klause.solver.lp.subExact
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.propagation.PropagationResult
import com.eignex.klause.solver.propagation.PropagationSession
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.BigRational
import com.eignex.klause.util.IntArrayList
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.round

/**
 * Sound lower bound on a [LinearObjective] given the current partial assignment in
 * [session]. Pinned vars contribute their exact value; unpinned bool vars take the
 * weight (or 0) that makes their contribution smallest; unpinned int vars take the
 * domain endpoint matching the coefficient's sign.
 */
internal fun BacktrackSolver.linearLowerBound(obj: LinearObjective, session: PropagationSession): Long = try {
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

/** Outcome of one node LP pass: whether to prune, the basis to warm-start children from, and
 *  the solved pre-cut tableau for the cheaper seeded reload ([DualSimplex.solve]'s
 *  `seedTableau`) — same caching condition as the basis. */
internal class LpNodeOutcome(
    val prune: Boolean,
    val basis: Basis?,
    val explanation: IntArray? = null,
    val tableau: DualSimplex? = null,
)

/**
 * Bounded, deduplicating buffer of LP-learned Farkas nogoods (#247) awaiting registration at the
 * next restart. Dedup is by sorted-literal key so a region pruned repeatedly is learned once; the
 * cap bounds memory between restarts (a learned clause that matters most has the lowest LBD anyway,
 * and the forgetting pass governs the live DB). [drain] returns and clears the pending batch but
 * keeps the seen-set so a flushed clause is not re-queued.
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
 * LP-rounding primal heuristic (#287): solve the root relaxation and round its fractional point
 * into a feasible assignment by pinning each variable toward its LP value, propagating between
 * pins. A complete conflict-free pass is a feasible incumbent — propagation enforces every factor,
 * so the snapshot is sound by construction. Returns null when the LP is not optimal, a pin
 * conflicts (single pass, no backtracking), or a rounded value is not in the live domain.
 *
 * A bake-time root conflict must be checked explicitly: the bake fixpoint stops at the first
 * conflict, which can leave every variable "already pinned" — the pin loop then never observes
 * the Unsat and would snapshot a factor-violating assignment as a feasible incumbent.
 */
internal fun BacktrackSolver.lpRoundingProbe(objective: LinearObjective, cancellation: Cancellation): Sample? {
    val session = PropagationSession(problem)
    if (session.isUnsatAtRoot) return null
    val relaxation = CpToLpRelaxation(problem, objective).build(session)
    if (relaxation.model.n == 0) return null
    val solution = try {
        DualSimplex(relaxation.model, cancellation = cancellation).solve()
    } catch (_: LpOverflowException) {
        return null
    }
    if (solution.status != LpStatus.OPTIMAL) return null
    for (v in 0 until problem.numIntVars) {
        val d = session.intDomain(v)
        if (d.min == d.max) continue // already fixed by propagation
        val col = relaxation.intColOf[v]
        val target = if (col >= 0) round(solution.primal(col)).toInt().coerceIn(d.min, d.max) else d.min
        if (session.pinInt(v, target) is PropagationResult.Unsat) return null
    }
    for (b in 0 until problem.numBoolVars) {
        if (session.boolValue(b) != null) continue
        val col = relaxation.boolColOf[b]
        val target = col >= 0 && solution.primal(col) >= 0.5
        if (session.pinBool(b, target) is PropagationResult.Unsat) return null
    }
    return snapshotAssignment(session)
}

/** True when the relaxation's rounded objective bound is at least the incumbent. The checked
 *  add matters: a silent wrap on extreme data could flip into a false prune, and the enclosing
 *  overflow handler already treats a throw as "no bound". */
internal fun BacktrackSolver.boundPrunes(solution: LpSolution, relaxation: LpRelaxation, bound: Double): Boolean {
    if (!bound.isFinite()) return false
    val lpBound = addExact(solution.objectiveLowerBoundCeil(), relaxation.objectiveConstant)
    return lpBound.toDouble() >= bound
}

/**
 * Persistent global cut pool: separate the structural separators at the root once and
 * return their cuts. Every root deduction holds at every solution, so root-separated cuts are
 * globally valid — a root Hall / cover / assignment / subtour cut stays a valid (if weaker)
 * bound at every tighter descendant — and re-adding them at every node avoids re-separating
 * them. They are re-tagged [Cut.global] accordingly (the separators can only prove globality
 * against declared domains, not against root-propagated ones). Gomory cuts are excluded (they
 * come from the live tableau in the per-node loop and are only locally valid). Each re-solve
 * warm-starts from the previous round's basis extended with the new cut slacks, like the
 * per-node cut loop.
 */
internal fun BacktrackSolver.harvestRootCuts(
    relaxer: CpToLpRelaxation,
    session: PropagationSession,
    separators: List<CutSeparator>,
    cancellation: Cancellation = Cancellation.Never,
): List<Cut> {
    if (separators.isEmpty() || session.isUnsatAtRoot) return emptyList()
    val pool = HashSet<String>()
    val cuts = ArrayList<Cut>()
    try {
        var relaxation = relaxer.build(session)
        if (relaxation.model.n == 0) return emptyList()
        var simplex = DualSimplex(relaxation.model, cancellation = cancellation)
        var solution = simplex.solve()
        var prevRows = relaxation.model.m
        var round = 0
        while (round++ < CUT_POOL_ROUNDS && solution.status == LpStatus.OPTIMAL && !cancellation()) {
            val prevBasis = solution.basis
            val prevSimplex = simplex
            val ctx = CutContext(problem, relaxation, solution, session)
            val fresh = separators.flatMap { it.separate(ctx) }
                .filter { pool.add(it.key()) }
                .map { if (it.global) it else Cut(it.cols, it.coeffs, it.rel, it.rhs, global = true) }
            if (fresh.isEmpty()) break
            cuts.addAll(fresh)
            relaxation = relaxer.build(session, cuts)
            simplex = DualSimplex(relaxation.model, cancellation = cancellation)
            solution = simplex
                .solve(extendBasisWithSlacks(prevBasis, relaxation.model, prevRows), prevSimplex)
            prevRows = relaxation.model.m
        }
    } catch (_: LpOverflowException) {
        return cuts // keep whatever stayed within 64-bit determinants — still globally valid
    }
    return cuts
}

/**
 * LP-relaxation bounding (#20), cut generation (#22) and reduced-cost fixing (#21): build and
 * solve one exact integer LP relaxation of the live problem, optionally strengthen it with cuts,
 * then either prune this node or tighten its domains. Prunes when the relaxation is infeasible or
 * its objective bound — rounded up, since the true objective is integral — is at least the
 * incumbent. Catches determinant overflow and keeps the node soundly (a missing bound only loses
 * pruning, never correctness).
 */
internal fun BacktrackSolver.lpBoundAndFix(
    relaxer: CpToLpRelaxation,
    session: PropagationSession,
    bound: Double,
    sink: SolveStatsSink,
    warmBasis: Basis?,
    params: BacktrackParams,
    separators: List<CutSeparator>,
    hints: LpHints?,
    objectiveVar: Int,
    objectiveAscending: Boolean,
    globalCuts: List<Cut>,
    seedTableau: DualSimplex?,
    cancellation: Cancellation,
): LpNodeOutcome = try {
    sink.lpClockStart()
    if (params.lpSparsePrimary) {
        // Over the dense-tableau cap (#602): take the bound-only sparse pipeline directly, never
        // allocating the dense tableau. Uses the cheap O(nnz) Neumaier–Shcherbina safe bound (not the
        // O(m³) exact certify) so the per-node cost is bounded and the `-t` deadline is honored.
        sparseSafePrune(relaxer, session, bound, globalCuts, sink, cancellation, objectiveVar, objectiveAscending)
    } else {
        lpBoundAndFixUnsafe(
            relaxer, session, bound, sink, warmBasis, params, separators, hints,
            objectiveVar, objectiveAscending, globalCuts, seedTableau, cancellation,
        )
    }
} catch (_: LpOverflowException) {
    // Determinant growth (large cut coefficients especially, #18) can exceed 64 bits. Instead of
    // dropping the bound, recover a sound one via the float revised simplex + exact BigInt
    // basis-certification pipeline (#567); a missing bound or reduction only loses pruning, never
    // soundness, so a null/failed pipeline just keeps the node.
    if (params.lpSparseBound) {
        sparseCertifiedPrune(relaxer, session, bound, globalCuts, sink, cancellation)
    } else {
        LpNodeOutcome(false, null)
    }
} finally {
    sink.lpClockStop()
}

/**
 * Cheap sound prune + objective-bound propagation for the over-cap sparse-primary path (#602/#562):
 * float revised simplex for the duals, then the O(nnz) Neumaier–Shcherbina safe bound — no exact
 * BigInt certify, so the per-node cost is bounded and `-t` is honored (the simplex itself polls
 * cancellation). Prunes when the safe bound (+ the relaxation's objective constant) reaches the
 * incumbent, and — independent of any incumbent — tightens an ascending objective variable up to
 * `ceil(safe bound)` (#281). That tightening is applied **reason-less** (a sound, level-local leaf
 * for conflict analysis, exactly as [lpBoundAndFixUnsafe] does when a reason is withheld); the exact
 * reduced-cost reason on the revised basis is the next slice of #705. Any solver failure keeps the node.
 */
@Suppress("LongParameterList")
internal fun BacktrackSolver.sparseSafePrune(
    relaxer: CpToLpRelaxation,
    session: PropagationSession,
    bound: Double,
    globalCuts: List<Cut>,
    sink: SolveStatsSink,
    cancellation: Cancellation,
    objectiveVar: Int,
    objectiveAscending: Boolean,
): LpNodeOutcome {
    val relaxation = relaxer.build(session, globalCuts)
    if (relaxation.model.n == 0) return LpNodeOutcome(false, null)
    sink.observeLpSolve()
    // Always solve: an infeasible relaxation prunes the node regardless of incumbent or objective.
    val simplex = RevisedSimplex(relaxation.model, cancellation)
    val result = simplex.solve() ?: run {
        // Infeasibility prune (#705 slice 3): a dual-unbounded termination is only a *candidate*
        // infeasibility — confirm it with an exact Farkas certificate before pruning (the float ray
        // alone is not sound). Any other failure (non-convergence / singular) keeps the node.
        val basis = simplex.infeasibleBasis
        if (basis != null &&
            ExactBasisCertifier.certifiesInfeasible(relaxation.model, basis, simplex.infeasibleRow)
        ) {
            sink.observeLpInfeasiblePrune()
            return LpNodeOutcome(true, null)
        }
        return LpNodeOutcome(false, null)
    }
    val canPrune = bound.isFinite()
    val canPropagate = objectiveVar >= 0 && objectiveAscending
    if (!canPrune && !canPropagate) return LpNodeOutcome(false, null) // feasible, nothing more to deduce
    val safe = safeObjectiveLowerBound(relaxation.model, result.duals) ?: return LpNodeOutcome(false, null)
    val full = safe + relaxation.objectiveConstant.toDouble()
    if (canPrune && full >= bound) {
        sink.observeLpPrune()
        return LpNodeOutcome(true, null)
    }
    // Objective-bound propagation (#281): the integer objective is ≥ ceil(safe bound). The safe bound
    // only under-estimates, so ceil(full) ≤ the true optimum — a sound lower bound. Reason-less, so an
    // Unsat tightening prunes this node (a conflict-analysis leaf).
    if (canPropagate && full.isFinite()) {
        val lpFloor = ceil(full)
        if (lpFloor in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() &&
            session.implyIntAtLeast(objectiveVar, lpFloor.toInt()) is PropagationResult.Unsat
        ) {
            sink.observeLpPrune()
            return LpNodeOutcome(true, null)
        }
    }
    // Reduced-cost fixing (#21) on the exact certified reduced costs — needs a finite incumbent for
    // the improving gap, so it runs only when pruning is possible. The exact certify is the per-node
    // cost the sparse path pays for fixing parity with the dense path (#705 slice 2).
    if (canPrune) {
        val cert = ExactBasisCertifier.certify(relaxation.model, result.basis)
        if (cert != null && applySparseReducedCostFixing(relaxation, cert, result.basis, session, bound, sink)) {
            return LpNodeOutcome(true, null)
        }
    }
    return LpNodeOutcome(false, null)
}

/**
 * Reduced-cost fixing (#21) for the sparse path, from the exact [ExactBasisCertifier.Certificate].
 * Mirrors [applyReducedCostFixing] but over exact rationals, and **reason-less** — a sound
 * level-local tightening that conflict analysis treats as a leaf (the learnable reduced-cost reason
 * on the revised basis is a later #705 slice). At the LP optimum a nonbasic column sits at a bound;
 * moving it Δ integer steps raises the objective by `|reducedCost|·Δ`, and any incumbent-beating
 * solution has objective `≤ ⌈bound⌉ − 1`, so the column can move at most
 * `floor((improvingMax − lpOptimum) / |reducedCost|)` steps before it alone overshoots. Returns true
 * if a reduction empties a domain (the node is then infeasible and pruned).
 */
internal fun BacktrackSolver.applySparseReducedCostFixing(
    relaxation: LpRelaxation,
    cert: ExactBasisCertifier.Certificate,
    basis: Basis,
    session: PropagationSession,
    bound: Double,
    sink: SolveStatsSink,
): Boolean {
    val improvingMax = ceil(bound).toLong() - 1L // best objective that still beats the incumbent
    val slack = BigRational.of(improvingMax) - cert.objective // exact gap; ≥ 0 (node not bound-pruned)
    if (slack.signum() < 0) return false
    val status = basis.status
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
                if (isBool) session.implyBool(varId, false) else session.implyIntAtMost(varId, (liveMin + dMax).toInt())
            }

            // At upper bound: reducedCost ≤ 0; symmetric, tighten the lower bound.
            VarStatus.AT_UPPER -> {
                if (dj.signum() >= 0) continue
                val dMaxBig = (slack / (BigRational.ZERO - dj)).floor()
                if (dMaxBig >= BigInt.of(span)) continue
                val dMax = dMaxBig.toLongOrNull() ?: continue
                if (isBool) session.implyBool(varId, true) else session.implyIntAtLeast(varId, (liveMax - dMax).toInt())
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
 * Sound objective lower bound from the float revised simplex + exact BigInt basis-certification,
 * used when the exact `Long` path overflowed (#567). Prunes when the certified bound (plus the
 * relaxation's objective constant) reaches the incumbent. Any failure (no incumbent, empty
 * relaxation, non-convergence, singular basis, unbounded Lagrangian) keeps the node — sound.
 */
internal fun BacktrackSolver.sparseCertifiedPrune(
    relaxer: CpToLpRelaxation,
    session: PropagationSession,
    bound: Double,
    globalCuts: List<Cut>,
    sink: SolveStatsSink,
    cancellation: Cancellation,
): LpNodeOutcome {
    if (!bound.isFinite()) return LpNodeOutcome(false, null) // no incumbent to prune against
    val relaxation = relaxer.build(session, globalCuts)
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

/** The LP relaxation's objective value (optimum + constant) when optimal, else NaN — the value
 *  [SolveStatsSink.observeRootLpBound] records as the root bound. */
private fun lpObjectiveOf(solution: LpSolution, relaxation: LpRelaxation): Double =
    if (solution.status == LpStatus.OPTIMAL) solution.objectiveValue + relaxation.objectiveConstant else Double.NaN

/**
 * The root-node LP relaxation objective (with the harvested [globalCuts]) on the undecided problem,
 * or NaN when the relaxation is empty / not optimal / overflows. Solved once before search, so no
 * decision tightens a domain and the value is a sound *global* lower bound on the objective — the
 * baseline for the integrality-gap measurement surfaced in `-s`. Search bounds only from level 1
 * down, so without this one-shot solve the true root bound would never be recorded.
 */
internal fun BacktrackSolver.rootLpRelaxationBound(
    relaxer: CpToLpRelaxation,
    globalCuts: List<Cut>,
    cancellation: Cancellation = Cancellation.Never,
    sparse: Boolean = false,
): Double = try {
    val relaxation = relaxer.build(PropagationSession(problem), globalCuts)
    if (relaxation.model.n == 0) {
        Double.NaN
    } else if (sparse) {
        // The over-cap sparse model has no dense tableau for [DualSimplex]; take the revised simplex +
        // Neumaier–Shcherbina safe bound — the same sound bound the per-node sparse prune reports.
        val result = RevisedSimplex(relaxation.model, cancellation).solve()
        val safe = result?.let { safeObjectiveLowerBound(relaxation.model, it.duals) }
        if (safe != null) safe + relaxation.objectiveConstant.toDouble() else Double.NaN
    } else {
        lpObjectiveOf(DualSimplex(relaxation.model, cancellation = cancellation).solve(), relaxation)
    }
} catch (_: LpOverflowException) {
    Double.NaN
}

/** The Farkas nogood for an infeasible node LP (#247), or null when learning is off / no
 *  certificate. The clause is over absolute bound atoms, so it is globally valid and registered
 *  lazily at a restart (where its literals are no longer all-false). */
internal fun BacktrackSolver.lpExplanation(
    params: BacktrackParams,
    relaxation: LpRelaxation,
    solution: LpSolution,
    session: PropagationSession,
): IntArray? = if (params.lpLearn) LpExplanation.infeasibilityClause(relaxation, solution, session) else null

/**
 * Extend a basis optimal for the [prevRows]-row relaxation to the cut-augmented [model] — same
 * structural columns, the first [prevRows] rows unchanged — by seating each newly appended cut
 * row's slack as basic. The structural and prior-slack statuses carry over verbatim, so the
 * basis stays dual-feasible and the dual re-solve resumes near the optimum. Returns null if the
 * prior basis does not match the pre-cut shape (then the re-solve cold-starts; same optimum).
 */
internal fun BacktrackSolver.extendBasisWithSlacks(prev: Basis, model: LpModel, prevRows: Int): Basis? {
    val n = model.n
    val newRows = model.m
    if (newRows < prevRows || prev.basicVars.size != prevRows || prev.status.size != n + prevRows) {
        return null
    }
    val basicVars = IntArray(newRows)
    prev.basicVars.copyInto(basicVars)
    val status = Array(model.numVars) { VarStatus.BASIC }
    prev.status.copyInto(status)
    for (i in prevRows until newRows) {
        basicVars[i] = n + i // the new row i's slack column
        status[n + i] = VarStatus.BASIC
    }
    return Basis(basicVars, status)
}

@Suppress("LongParameterList")
internal fun BacktrackSolver.lpBoundAndFixUnsafe(
    relaxer: CpToLpRelaxation,
    session: PropagationSession,
    bound: Double,
    sink: SolveStatsSink,
    warmBasis: Basis?,
    params: BacktrackParams,
    separators: List<CutSeparator>,
    hints: LpHints?,
    objectiveVar: Int,
    objectiveAscending: Boolean,
    globalCuts: List<Cut>,
    seedTableau: DualSimplex?,
    cancellation: Cancellation,
): LpNodeOutcome {
    var relaxation = relaxer.build(session, globalCuts)
    if (relaxation.model.n == 0) return LpNodeOutcome(false, null) // empty relaxation
    sink.observeLpSolve()
    var simplex = DualSimplex(relaxation.model, cancellation = cancellation)
    // Float fast-path (#18): with no parent basis to warm from, a quick double-precision solve
    // supplies a candidate basis for the exact solver to certify. Sound regardless — the exact
    // solve re-optimizes to the true bound, and a bad/singular basis just cold-starts. The
    // seeded tableau reload (when compatible) supersedes both.
    val startBasis =
        warmBasis ?: if (params.lpFloatWarmStart) FloatSimplex(relaxation.model, cancellation).basis() else null
    var solution = simplex.solve(startBasis, seedTableau)
    if (simplex.lastSolveSeeded) sink.observeLpSeeded()
    sink.observeLpPivots(solution.pivots)
    sink.observeRootLpBound(session.decisionLevel, lpObjectiveOf(solution, relaxation))
    // Warm-start children from the initial (pre-cut) basis and tableau: cut rows vary per node,
    // but the base model structure is identical across nodes, so only this state transfers.
    val warmCache = if (solution.status == LpStatus.OPTIMAL) solution.basis else null
    val nodeTableau = if (solution.status == LpStatus.OPTIMAL) simplex else null
    // The most recent same-shape optimal simplex inside this node, seeding the fixpoint
    // re-solves (the cut rounds grow the row count, so they seed-fail fast and use the
    // extended-basis path instead).
    var lastSimplex = nodeTableau

    when (solution.status) {
        LpStatus.INFEASIBLE -> {
            sink.observeLpInfeasiblePrune()
            return LpNodeOutcome(true, null, lpExplanation(params, relaxation, solution, session))
        }

        LpStatus.UNBOUNDED -> return LpNodeOutcome(false, null)

        LpStatus.OPTIMAL ->
            if (boundPrunes(solution, relaxation, bound)) {
                sink.observeLpPrune()
                return LpNodeOutcome(true, warmCache, tableau = nodeTableau)
            }
    }

    // Cut rounds (#22): separate violated cuts from the LP point and re-solve. Cuts only append
    // rows (structural columns are unchanged), so with lpWarmCuts the previous round's optimal
    // basis — extended with the new rows' slacks — warm-starts the dual re-solve. Cuts are valid,
    // so infeasibility under them prunes. The cut list and warm-start state outlive the loop:
    // the fixpoint re-solves below keep the cuts (they stay valid as the box only shrinks) and
    // resume from the same basis.
    val cuts = ArrayList<Cut>()
    var prevBasis = warmCache // last optimal basis, extended each round to warm the re-solve
    var prevRows = relaxation.model.m // row count whose slacks `prevBasis` already covers
    if (separators.isNotEmpty()) {
        val pool = HashSet<String>()
        var round = 0
        // The root relaxation bounds the whole tree, so close it harder there (#285).
        val maxRounds = if (session.decisionLevel == 0) {
            maxOf(params.lpRootCutRounds, params.lpCutRounds)
        } else {
            params.lpCutRounds
        }
        // #565 staleness baseline: the LP bound before any cuts, to measure each round's gain against.
        var lastObj = lpObjectiveOf(solution, relaxation)
        while (round++ < maxRounds) {
            // #565 budget: stop before separating once this node's live cut pool is full.
            val room = params.lpMaxCutsPerNode - cuts.size
            if (room <= 0) break
            val ctx = CutContext(problem, relaxation, solution, session)
            // Structure-based separators run on the LP point; Gomory cuts come from the tableau.
            val separated = separators.flatMap { it.separate(ctx) }
            val gomory =
                if (params.lpCuts && params.lpGomory) simplex.gomoryCuts(GOMORY_CUTS_PER_ROUND) else emptyList()
            val mir =
                if (params.lpCuts && params.lpMir) simplex.mirCuts(GOMORY_CUTS_PER_ROUND) else emptyList()
            val deduped = (separated + gomory + mir).filter { pool.add(it.key()) }
            if (deduped.isEmpty()) break
            // Trim the round to the remaining budget so one node's pool can never blow past the cap.
            val fresh = if (deduped.size > room) deduped.subList(0, room) else deduped
            cuts.addAll(fresh)
            sink.observeLpCuts(fresh.size)
            relaxation = relaxer.build(session, globalCuts + cuts)
            simplex = DualSimplex(relaxation.model, cancellation = cancellation)
            val warmStart = if (params.lpWarmCuts && prevBasis != null) {
                extendBasisWithSlacks(prevBasis, relaxation.model, prevRows)
            } else {
                null
            }
            solution = simplex.solve(warmStart, lastSimplex)
            if (simplex.lastSolveSeeded) sink.observeLpSeeded()
            sink.observeLpPivots(solution.pivots)
            sink.observeRootLpBound(session.decisionLevel, lpObjectiveOf(solution, relaxation))
            prevBasis = if (solution.status == LpStatus.OPTIMAL) solution.basis else null
            prevRows = relaxation.model.m
            if (solution.status == LpStatus.OPTIMAL) lastSimplex = simplex
            if (solution.status == LpStatus.INFEASIBLE) {
                sink.observeLpInfeasiblePrune()
                return LpNodeOutcome(
                    true,
                    warmCache,
                    lpExplanation(params, relaxation, solution, session),
                    tableau = nodeTableau,
                )
            }
            if (solution.status != LpStatus.OPTIMAL) break
            if (boundPrunes(solution, relaxation, bound)) {
                sink.observeLpPrune()
                return LpNodeOutcome(true, warmCache, tableau = nodeTableau)
            }
            // #565 staleness: once a round stops moving the LP bound (diminishing returns), stop
            // separating and give the time back to search — more cuts here would not prune.
            if (params.lpCutMinGain > 0.0) {
                val newObj = lpObjectiveOf(solution, relaxation)
                val gain = if (newObj.isFinite() && lastObj.isFinite()) newObj - lastObj else Double.POSITIVE_INFINITY
                lastObj = newObj
                if (gain.isFinite() && gain < params.lpCutMinGain * maxOf(1.0, abs(lastObj))) break
            }
        }
    }

    // Apply the LP's domain deductions and, with lpFixpoint (#283), drive the LP and propagation
    // to a joint fixpoint: re-solve and re-apply while a round keeps tightening domains (detected
    // via the session's propagation counter), capped at [LP_FIXPOINT_ITERS]. Each deduction is
    // independently sound, so iterating them is sound; cut separation is not repeated (it ran
    // above). With lpFixpoint off this is a single pass, identical to the prior behaviour.
    var iter = 0
    while (true) {
        // LP-guided value ordering (#246): record the current fractional primal for diving.
        if (solution.status == LpStatus.OPTIMAL) hints?.record(relaxation, solution)

        val before = session.propagationCount
        // Objective dual-bound propagation (#281): push the LP lower bound onto a single-variable
        // minimisation objective, with the reduced-cost certificate as the learnable reason when
        // it is expressible. When the reason is withheld (an auxiliary column or a node-local
        // row carries dual weight), the bound itself still holds at this node, so it is applied
        // as a reason-less, level-local tightening — a leaf for conflict analysis, like the
        // reason-less reduced-cost fixings.
        if (params.lpObjectiveBound && objectiveVar >= 0 && objectiveAscending &&
            solution.status == LpStatus.OPTIMAL
        ) {
            val lpFloor = addExact(solution.objectiveLowerBoundCeil(), relaxation.objectiveConstant)
            if (lpFloor in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                val reason = LpExplanation.objectiveBoundReason(relaxation, solution, session)
                val res = if (reason != null) {
                    session.implyIntAtLeastWithReason(objectiveVar, lpFloor.toInt(), reason)
                } else {
                    session.implyIntAtLeast(objectiveVar, lpFloor.toInt())
                }
                if (res is PropagationResult.Unsat) {
                    sink.observeLpPrune()
                    return LpNodeOutcome(true, warmCache, tableau = nodeTableau)
                }
            }
        }
        // Reduced-cost fixing (#21/#282) on the cut-strengthened solution; needs a finite gap.
        val prune = bound.isFinite() && solution.status == LpStatus.OPTIMAL &&
            applyReducedCostFixing(
                relaxation,
                solution,
                session,
                bound,
                sink,
                params,
                objectiveVar,
                objectiveAscending,
            )
        if (prune) return LpNodeOutcome(true, warmCache, tableau = nodeTableau)

        // Stop unless the joint fixpoint is enabled, this round tightened a domain, and budget remains.
        if (!params.lpFixpoint || session.propagationCount == before || ++iter >= LP_FIXPOINT_ITERS) {
            return LpNodeOutcome(false, warmCache, tableau = nodeTableau)
        }
        // Re-solve on the tightened domains and loop. The pool cuts AND this node's local cuts
        // stay in the model — fixing/propagation only shrinks the node's box, inside which the
        // local cuts remain valid — and the re-solve warm-starts from the last optimal basis
        // (identical row layout), so the re-optimisation costs a few dual pivots, not a cold solve.
        relaxation = relaxer.build(session, globalCuts + cuts)
        if (relaxation.model.n == 0) return LpNodeOutcome(false, warmCache, tableau = nodeTableau)
        simplex = DualSimplex(relaxation.model, cancellation = cancellation)
        val fixWarm = if (params.lpWarmCuts && prevBasis != null) {
            extendBasisWithSlacks(prevBasis, relaxation.model, prevRows)
        } else {
            null
        }
        // Same row layout as the previous round, so the seeded reload applies directly; the
        // extended basis stays as the fallback.
        solution = simplex.solve(fixWarm, lastSimplex)
        if (simplex.lastSolveSeeded) sink.observeLpSeeded()
        prevBasis = if (solution.status == LpStatus.OPTIMAL) solution.basis else null
        prevRows = relaxation.model.m
        if (solution.status == LpStatus.OPTIMAL) lastSimplex = simplex
        sink.observeLpPivots(solution.pivots)
        sink.observeRootLpBound(session.decisionLevel, lpObjectiveOf(solution, relaxation))
        when (solution.status) {
            LpStatus.INFEASIBLE -> {
                sink.observeLpInfeasiblePrune()
                return LpNodeOutcome(
                    true,
                    warmCache,
                    lpExplanation(params, relaxation, solution, session),
                    tableau = nodeTableau,
                )
            }

            LpStatus.UNBOUNDED -> return LpNodeOutcome(false, warmCache, tableau = nodeTableau)

            LpStatus.OPTIMAL -> if (boundPrunes(solution, relaxation, bound)) {
                sink.observeLpPrune()
                return LpNodeOutcome(true, warmCache, tableau = nodeTableau)
            }
        }
    }
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
internal fun BacktrackSolver.applyReducedCostFixing(
    relaxation: LpRelaxation,
    solution: LpSolution,
    session: PropagationSession,
    bound: Double,
    sink: SolveStatsSink,
    params: BacktrackParams,
    objectiveVar: Int,
    objectiveAscending: Boolean,
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
    // Learnable reasons for each fixing (#282): a fixing of column `col` is justified by the LP's
    // dual decomposition under the OTHER support columns' seated bounds — including the objective
    // variable's own seated bound when it carries a reduced cost — plus the incumbent bound
    // `objVar ≤ improvingMax`, plus the recorded validity premises of any non-global row carrying
    // dual weight (those justify the decomposition itself, so they are never excluded per-column).
    // Expressible only when: there is a single-var minimisation objective whose live upper bound
    // is already ≤ improvingMax (so the incumbent atom holds); every dual-weighted non-global row
    // has recorded premises (see [LpExplanation]); and no support premise sits on an auxiliary
    // column. Otherwise the fixings stay reason-less level-local tightenings, which conflict
    // analysis treats as leaves.
    var learn = params.lpLearn && objectiveVar >= 0 && objectiveAscending &&
        improvingMax in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() &&
        session.intDomain(objectiveVar).max.toLong() <= improvingMax
    val supportCols = IntArrayList()
    val supportLits = IntArrayList()
    if (learn) {
        val seen = HashSet<Int>()
        val premLits = IntArrayList()
        if (LpExplanation.addDualRowPremiseLits(premLits, seen, relaxation, solution, session)) {
            for (k in 0 until premLits.size) {
                supportCols.add(-1) // row premise: part of every fixing's reason, never excluded
                supportLits.add(premLits[k])
            }
            for (c in relaxation.colVarId.indices) {
                if (status[c] == VarStatus.BASIC) continue
                val dNum = solution.reducedCostNumerator[c]
                if (dNum == 0L) continue
                // The premise side follows the reduced cost's sign, not the seat name — a
                // collapsed (pinned) column's recorded seat is arbitrary. See
                // [LpExplanation.premiseLit].
                val lit = LpExplanation.premiseLit(relaxation, session, c, lowerSide = dNum > 0L)
                if (lit == LpExplanation.PREMISE_AUX) {
                    learn = false
                    break
                }
                if (lit == LpExplanation.PREMISE_NONE || !seen.add(lit)) continue
                supportCols.add(c)
                supportLits.add(lit)
            }
        } else {
            learn = false
        }
    }
    val incumbentLit = if (learn) session.boundLeLit(objectiveVar, improvingMax.toInt(), positive = false) else 0

    // Reason for fixing `col`: every support column's seated-bound negation except col's own, plus
    // the incumbent objective bound. (col's own bound is the variable moving, not a premise.)
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
        if (varId < 0) continue // auxiliary column (e.g. circuit arc) — no CP variable to fix
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
                } else if (learn) {
                    session.implyIntAtMostWithReason(varId, (liveMin + dMax).toInt(), reasonFor(col))
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
                } else if (learn) {
                    session.implyIntAtLeastWithReason(varId, (liveMax - dMax).toInt(), reasonFor(col))
                } else {
                    session.implyIntAtLeast(varId, (liveMax - dMax).toInt())
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
