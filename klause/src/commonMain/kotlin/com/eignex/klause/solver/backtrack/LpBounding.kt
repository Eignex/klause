package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.lp.Basis
import com.eignex.klause.solver.lp.CpToLpRelaxation
import com.eignex.klause.solver.lp.Cut
import com.eignex.klause.solver.lp.ExactBasisCertifier
import com.eignex.klause.solver.lp.LpOverflowException
import com.eignex.klause.solver.lp.LpRelaxation
import com.eignex.klause.solver.lp.RevisedSimplex
import com.eignex.klause.solver.lp.VarStatus
import com.eignex.klause.solver.lp.addExact
import com.eignex.klause.solver.lp.mulExact
import com.eignex.klause.solver.lp.safeObjectiveLowerBound
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.propagation.PropagationResult
import com.eignex.klause.solver.propagation.PropagationSession
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.BigRational
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
internal fun BacktrackSolver.lpBoundAndFix(
    relaxer: CpToLpRelaxation,
    session: PropagationSession,
    bound: Double,
    sink: SolveStatsSink,
    objectiveVar: Int,
    objectiveAscending: Boolean,
    globalCuts: List<Cut>,
    cancellation: Cancellation,
    hints: LpHints? = null,
): LpNodeOutcome = try {
    sink.lpClockStart()
    sparseSafePrune(relaxer, session, bound, globalCuts, sink, cancellation, objectiveVar, objectiveAscending, hints)
} catch (_: LpOverflowException) {
    // A determinant or coefficient overflow in the relaxation build loses the bound; recover a sound
    // one via the exact BigInt basis-certification pipeline. A failure just keeps the node.
    sparseCertifiedPrune(relaxer, session, bound, globalCuts, sink, cancellation)
} finally {
    sink.lpClockStop()
}

/**
 * Cheap sound prune + objective-bound propagation for the sparse LP path (#602/#705): float revised
 * simplex for the duals, then the O(nnz) Neumaier–Shcherbina safe bound — no exact certify on the
 * common path, so the per-node cost is bounded and `-t` is honored. Prunes when the relaxation is
 * infeasible (exact Farkas certificate) or the safe bound reaches the incumbent, tightens an
 * ascending objective variable up to `ceil(safe bound)` (reason-less, a sound conflict-analysis
 * leaf), and fixes reduced-cost-dominated variables off their bounds. Any solver failure keeps the node.
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
    hints: LpHints? = null,
): LpNodeOutcome {
    val relaxation = relaxer.build(session, globalCuts)
    if (relaxation.model.n == 0) return LpNodeOutcome(false, null)
    sink.observeLpSolve()
    // Always solve: an infeasible relaxation prunes the node regardless of incumbent or objective.
    val simplex = RevisedSimplex(relaxation.model, cancellation)
    val result = simplex.solve() ?: run {
        // Infeasibility prune (#705): a dual-unbounded termination is only a *candidate* infeasibility —
        // confirm it with an exact Farkas certificate before pruning (the float ray alone is not sound).
        // Any other failure (non-convergence / singular) keeps the node.
        val basis = simplex.infeasibleBasis
        if (basis != null &&
            ExactBasisCertifier.certifiesInfeasible(relaxation.model, basis, simplex.infeasibleRow)
        ) {
            sink.observeLpInfeasiblePrune()
            return LpNodeOutcome(true, null)
        }
        return LpNodeOutcome(false, null)
    }
    // LP-guided branching (#287): record the fractional primal so the descent can order branch values
    // toward the LP point. Purely advisory — it never changes feasibility or the optimum.
    hints?.record(relaxation, result.primal)
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
    // the improving gap, so it runs only when pruning is possible.
    if (canPrune) {
        val cert = ExactBasisCertifier.certify(relaxation.model, result.basis)
        if (cert != null && applySparseReducedCostFixing(relaxation, cert, result.basis, session, bound, sink)) {
            return LpNodeOutcome(true, null)
        }
    }
    return LpNodeOutcome(false, null)
}

/**
 * Reduced-cost fixing (#21) from the exact [ExactBasisCertifier.Certificate], over exact rationals
 * and **reason-less** (a sound level-local tightening that conflict analysis treats as a leaf). At
 * the LP optimum a nonbasic column sits at a bound; moving it Δ integer steps raises the objective by
 * `|reducedCost|·Δ`, and any incumbent-beating solution has objective `≤ ⌈bound⌉ − 1`, so the column
 * can move at most `floor((improvingMax − lpOptimum) / |reducedCost|)` steps before it alone
 * overshoots. Returns true if a reduction empties a domain (the node is then infeasible and pruned).
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
 * Sound objective lower bound from the float revised simplex + exact BigInt basis-certification, used
 * when the cheap safe-bound path overflowed during the relaxation build. Prunes when the certified
 * bound (plus the relaxation's objective constant) reaches the incumbent. Any failure keeps the node.
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

/**
 * The root-node LP relaxation objective (with the harvested [globalCuts]) on the undecided problem,
 * or NaN when the relaxation is empty / not optimal / overflows — the revised simplex + Neumaier–
 * Shcherbina safe bound, the same sound bound the per-node prune reports. Solved once before search,
 * so the value is a sound *global* lower bound on the objective — the integrality-gap baseline for `-s`.
 */
internal fun BacktrackSolver.rootLpRelaxationBound(
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
 * LP-rounding primal heuristic (#287) on the sparse revised-simplex path: solve the root relaxation,
 * round each variable's fractional LP value to the nearest in-domain integer, and propagate. Returns a
 * complete assignment when rounding-then-propagation reaches a fixpoint without a wipeout, else null.
 * Sound by construction — the result is a candidate that the caller re-evaluates against the objective
 * and only keeps if feasible-and-improving; a bad rounding just yields null or a worse incumbent.
 */
internal fun BacktrackSolver.lpRoundingProbe(
    objective: LinearObjective,
    cancellation: Cancellation,
): Sample? {
    val session = PropagationSession(problem)
    if (session.isUnsatAtRoot) return null
    val relaxation = CpToLpRelaxation(problem, objective, sparseModel = true).build(session)
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
