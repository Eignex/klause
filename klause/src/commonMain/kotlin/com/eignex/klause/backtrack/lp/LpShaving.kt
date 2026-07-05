package com.eignex.klause.backtrack.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.lp.LpOverflowException
import com.eignex.klause.lp.RevisedSimplex
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.safeObjectiveLowerBound
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.LongHashSet
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Objective shaving: raise the proven lower bound on an ascending
 * (minimized) [objectiveVar] by probing. Assume `objectiveVar ≤ v` in a fresh root session and, if the
 * sound propagation + LP relaxation proves that infeasible — `pruneNode` run with an *infinite*
 * incumbent, so it fires only on a genuine (Farkas-certified / propagation) infeasibility, never on
 * bound dominance — conclude `objectiveVar ≥ v + 1` and step `v` up. Returns the improved bound (the
 * largest `v + 1` proven), or null when nothing shaves or the objective is not a single ascending
 * variable. **Sound:** every raise is backed by a proof that all lower values are infeasible; the caller
 * tightens the search's objective lower bound to the returned value. Bounded by [SHAVE_MAX_ITERS] and
 * [token].
 */
internal fun LpEngine.shaveObjectiveLb(objectiveVar: Int, ascending: Boolean, token: Cancellation): Int? {
    if (!ascending || objectiveVar < 0 || lpRelaxer == null) return null
    val root = PropagationSession(problem)
    if (root.isUnsatAtRoot) return null
    var candidate = root.intDomain(objectiveVar).min
    var improved: Int? = null
    var iters = 0
    while (iters++ < SHAVE_MAX_ITERS && !token()) {
        val s = PropagationSession(problem)
        if (s.isUnsatAtRoot) break
        // objectiveVar ≤ candidate infeasible (propagation Unsat, or LP infeasible at an infinite bound)
        // ⇒ every solution has objectiveVar ≥ candidate + 1.
        val infeasible = s.implyIntAtMost(objectiveVar, candidate) is PropagationResult.Unsat ||
            pruneNode(s, Double.POSITIVE_INFINITY, objectiveVar, ascending)
        if (!infeasible) break
        candidate += 1
        improved = candidate
    }
    return improved
}

/** A proven-tighter domain for an integer variable from variable shaving: `varId ∈ [lo, hi]`. */
internal class ShavedBound(val varId: Int, val lo: Int, val hi: Int)

/**
 * Variable shaving: tighten integer variables' domain bounds
 * by probing. For each variable assume `v ≤ lo` (resp. `v ≥ hi`) and, if the sound propagation + LP
 * relaxation proves that infeasible (`pruneNode` at an *infinite* incumbent, firing only on genuine
 * infeasibility), raise `lo` (lower `hi`). Returns the variables whose declared bounds shaved inward,
 * for the caller to apply to the root. **Sound:** each tightening is backed by a proof that the
 * shaved-off values are infeasible. Bounded by [SHAVE_MAX_ITERS] total probes across all variables.
 */
internal fun LpEngine.shaveVariableBounds(token: Cancellation): List<ShavedBound> {
    if (lpRelaxer == null) return emptyList()
    val root = PropagationSession(problem)
    if (root.isUnsatAtRoot) return emptyList()
    val out = ArrayList<ShavedBound>()
    var probes = 0
    for (v in 0 until problem.numIntVars) {
        if (probes >= SHAVE_MAX_ITERS || token()) break
        val d = root.intDomain(v)
        var lo = d.min
        var hi = d.max
        if (lo >= hi) continue
        while (lo < hi && probes++ < SHAVE_MAX_ITERS && !token() && infeasibleUnder(v, lo, atMost = true)) lo += 1
        while (hi > lo && probes++ < SHAVE_MAX_ITERS && !token() && infeasibleUnder(v, hi, atMost = false)) hi -= 1
        if (lo != d.min || hi != d.max) out.add(ShavedBound(v, lo, hi))
    }
    return out
}

/**
 * Factor indices of [Linear] `≤` constraints the LP relaxation proves redundant — implied by the *other*
 * constraints, so dropping them preserves the solution set. For each candidate `a·x ≤ b`, the relaxation
 * of the others (a fresh problem omitting this one and any already dropped) is maximised over `a·x`; a
 * Neumaier–Shcherbina *safe upper bound* on that maximum at or below `b` certifies redundancy. The safe
 * bound over-estimates the true maximum, so `≤ b` guarantees no feasible point can violate the constraint
 * — sound; a too-loose bound only misses a removal, never makes a wrong one. Sound regardless of the
 * folded domains: the harvested problem keeps them (shaving only tightens), so a dropped row's effect
 * persists in the bounds. Sequential: each drop is judged against the constraints still kept, so two
 * mutually-implied constraints are never both removed (implication is transitive). Bounded by
 * [SHAVE_MAX_ITERS] and [token].
 *
 * The maximisation uses the primal pass ([RevisedSimplex.solvePrimal], phase-1 included): the dual simplex
 * cannot optimise this `−a·x` objective (it leaves the dual-feasible start, so [RevisedSimplex.solve]
 * returns the trivial point). A failed/unbounded primal solve just keeps the constraint.
 */
internal fun LpEngine.redundantConstraints(token: Cancellation): List<Int> {
    if (lpRelaxer == null) return emptyList()
    val removed = LinkedHashSet<Int>()
    var probes = 0
    for (i in problem.factors.indices) {
        if (probes >= SHAVE_MAX_ITERS || token()) break
        val f = problem.factors[i]
        if (f !is Linear || f.op == LinearOp.NE) continue
        probes++
        val kept = problem.factors.filterIndexed { idx, _ -> idx != i && idx !in removed }
        val others = Problem(problem.numBoolVars, problem.numIntVars, problem.intDomains.copyOf(), kept)
        val a = LongArray(problem.numIntVars)
        for (k in f.vars.indices) a[f.vars[k]] += f.coeffs[k].toLong()
        val b = f.bound.toDouble()
        // `≤ b` is redundant when the others' max of a·x is already ≤ b; `≥ b` when their min is ≥ b; an
        // `=` only when both hold. Safe bounds (over-/under-estimates) keep it sound — a loose bound just
        // misses a removal. Each drop is judged against the kept set, so two mutually-implied rows are
        // never both removed (implication is transitive).
        val redundant = when (f.op) {
            LinearOp.LE -> safeMax(others, a, token)?.let { it <= b } ?: false

            LinearOp.GE -> safeMin(others, a, token)?.let { it >= b } ?: false

            LinearOp.EQ -> {
                val mx = safeMax(others, a, token)
                mx != null && mx <= b && (safeMin(others, a, token)?.let { it >= b } ?: false)
            }

            else -> false
        }
        if (redundant) removed.add(i)
    }
    return removed.toList()
}

/**
 * Linear equalities the LP proves implied — a two-term difference `±(x − y)` pinned by the relaxation to
 * a single integer `c`. Returns them as [Linear] `= c` factors for the affine-elimination pass to fold
 * out (substituting `x` for `y + c`), shrinking the variable space, not just the constraint set. Sound:
 * the safe min/max bracket the real range, so a single integer inside it means every integer-feasible
 * point shares that value. Candidates are existing two-term unit-difference rows (so the pair is already
 * coupled — no `O(n²)` pair scan); each pair is probed once. Bounded by [SHAVE_MAX_ITERS] and [token].
 */
internal fun LpEngine.impliedEqualities(token: Cancellation): List<Linear> {
    if (lpRelaxer == null) return emptyList()
    val out = ArrayList<Linear>()
    val probed = LongHashSet()
    var probes = 0
    for (f in problem.factors) {
        if (probes >= SHAVE_MAX_ITERS || token()) break
        if (f !is Linear || f.op == LinearOp.EQ || f.vars.size != 2) continue
        val c0 = f.coeffs[0]
        val c1 = f.coeffs[1]
        if (!((c0 == 1 && c1 == -1) || (c0 == -1 && c1 == 1))) continue // a unit difference ±(v0 − v1)
        val v0 = f.vars[0]
        val v1 = f.vars[1]
        if (v0 == v1 || !probed.add((minOf(v0, v1).toLong() shl Int.SIZE_BITS) or maxOf(v0, v1).toLong())) continue
        probes++
        val e = LongArray(problem.numIntVars)
        e[v0] = c0.toLong()
        e[v1] = c1.toLong()
        val lo = safeMin(problem, e, token) ?: continue
        val hi = safeMax(problem, e, token) ?: continue
        // The difference is an integer in [lo, hi]; when exactly one integer fits, it is pinned to it.
        val cLo = ceil(lo - EQ_PIN_TOL)
        if (cLo != floor(hi + EQ_PIN_TOL) || !cLo.isFinite()) continue
        out.add(Linear(intArrayOf(c0, c1), intArrayOf(v0, v1), LinearOp.EQ, cLo.toInt()))
    }
    return out
}

/** Safe (Neumaier–Shcherbina) lower bound on `min(coeffs·x)` over [prob]'s base relaxation, or null when
 *  it is empty / infeasible / unbounded / fails. Uses the primal pass — the dual simplex cannot optimise
 *  an arbitrary objective (it leaves its dual-feasible start). */
private fun LpEngine.safeMin(prob: Problem, coeffs: LongArray, token: Cancellation): Double? {
    val session = PropagationSession(prob)
    if (session.isUnsatAtRoot) return null
    val relaxation = CpToLpRelaxation(prob, LinearObjective(intCoefficients = coeffs)).build(session)
    if (relaxation.model.n == 0) return null
    val result = try {
        dualSimplex(relaxation.model, token).solvePrimal()
    } catch (_: LpOverflowException) {
        return null
    } ?: return null
    val safe = safeObjectiveLowerBound(relaxation.model, result.duals) ?: return null
    return safe + relaxation.objectiveConstant.toDouble()
}

/** Safe upper bound on `max(coeffs·x)` over [prob] — `max(c·x) = −min(−c·x)`, so [safeMin] of the negated
 *  objective negated back. Null on the same failure cases. */
private fun LpEngine.safeMax(prob: Problem, coeffs: LongArray, token: Cancellation): Double? =
    safeMin(prob, LongArray(coeffs.size) { -coeffs[it] }, token)?.let { -it }

/** Whether the root relaxation is provably infeasible — the LP relaxation has no real point at an
 *  infinite incumbent, so `pruneNode` fires only on a genuine, certified infeasibility (the same sound
 *  oracle [shaveVariableBounds] leans on). A true result proves the whole problem has no solution. Bake
 *  propagation already reports its own infeasibility, so a root already Unsat is left to that path. */
internal fun LpEngine.rootInfeasible(token: Cancellation): Boolean {
    if (lpRelaxer == null || token()) return false
    val s = PropagationSession(problem)
    if (s.isUnsatAtRoot) return false
    return pruneNode(s, Double.POSITIVE_INFINITY, -1, true)
}

/** The built root relaxation's columns, rows and nonzeros, plus a per-solve cost proxy. */
internal class RootRelaxationSize(val cols: Int, val rows: Int, val nnz: Int) {
    /** A sparse revised-simplex solve runs ~`O(rows)` iterations, each touching ~`nnz`, over a tableau of
     *  ~`rows·(cols + rows)`; the harvest pays one such solve per shave / redundancy probe (up to
     *  [SHAVE_MAX_ITERS] of each), so this proxy is what its total cost scales with. */
    val cost: Long get() = rows.toLong() * (cols + rows) + nnz
}

/** Size of [LpEngine]'s root relaxation, or null when none is built / the root is already infeasible /
 *  the build overflows — used to gate the harvest's per-candidate probes on the real LP dimensions. */
internal fun LpEngine.rootRelaxationSize(): RootRelaxationSize? {
    val relaxer = lpRelaxer ?: return null
    val session = PropagationSession(problem)
    if (session.isUnsatAtRoot) return null
    val model = try {
        relaxer.build(session).model
    } catch (_: LpOverflowException) {
        return null
    }
    return RootRelaxationSize(model.n, model.m, model.csc.colPtr[model.n])
}

/** Whether a fresh root with `v ≤ bound` (or `v ≥ bound` when not [atMost]) is provably infeasible —
 *  propagation Unsat, or an LP infeasibility at an infinite incumbent (so `pruneNode` fires only on a
 *  genuine infeasibility). Sound: a true result proves every solution lies strictly past [bound]. */
private fun LpEngine.infeasibleUnder(v: Int, bound: Int, atMost: Boolean): Boolean {
    val s = PropagationSession(problem)
    if (s.isUnsatAtRoot) return false
    val assumed = if (atMost) s.implyIntAtMost(v, bound) else s.implyIntAtLeast(v, bound)
    return assumed is PropagationResult.Unsat || pruneNode(s, Double.POSITIVE_INFINITY, -1, true)
}

/** Max upward probes for objective shaving before it stops (each probe is one propagation + LP solve). */
private const val SHAVE_MAX_ITERS = 64

/** Slack on the safe min/max bracket when counting integers inside it — widening it only drops removals. */
private const val EQ_PIN_TOL = 1e-6
