package com.eignex.klause.lp.bounding

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpSolver
import com.eignex.klause.lp.engine.acceptNullable
import com.eignex.klause.lp.engine.floatFarkasRay
import com.eignex.klause.lp.engine.integerFarkasRay
import com.eignex.klause.lp.engine.newTableauCutSolver
import com.eignex.klause.lp.relaxation.LpAssemblyCancelled
import com.eignex.klause.lp.relaxation.RootDomains
import com.eignex.klause.lp.rootDomainOf
import com.eignex.klause.propagation.PropagationProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.collections.IntHashSet
import com.eignex.util.collections.LongHashSet
import com.eignex.util.numbers.CheckedLongOverflowException
import kotlin.math.ceil
import kotlin.math.floor

// Probes use `pruneNode` at an infinite incumbent, so they fire only on genuine infeasibility, never on
// bound dominance. Returns the largest proven `v + 1`, or null when nothing shaves.
internal fun LpEngine.shaveObjectiveLb(objectiveVar: Int, ascending: Boolean, token: ExecutionControl): Long? {
    if (!ascending || objectiveVar < 0 || lpRelaxer == null) return null
    val root = PropagationSession(problem)
    if (root.isUnsatAtRoot) return null
    var candidate = root.intDomain(objectiveVar).min
    var improved: Long? = null
    var iters = 0
    while (iters++ < SHAVE_MAX_ITERS && !token()) {
        val s = PropagationSession(problem)
        if (s.isUnsatAtRoot) break
        val infeasible = s.implyIntAtMost(objectiveVar, candidate) is PropagationResult.Unsat ||
            pruneNode(s, Double.POSITIVE_INFINITY, objectiveVar, ascending)
        if (!infeasible) break
        candidate += 1
        improved = candidate
    }
    return improved
}

internal class ShavedBound(val varId: Int, val lo: Long, val hi: Long)

// Same probe as shaveObjectiveLb per variable side; SHAVE_MAX_ITERS bounds the total across variables.
internal fun LpEngine.shaveVariableBounds(token: ExecutionControl): List<ShavedBound> {
    if (lpRelaxer == null) return emptyList()
    val root = PropagationSession(problem, token)
    if (root.isUnsatAtRoot) return emptyList()
    val out = ArrayList<ShavedBound>()
    var probes = 0
    for (v in 0 until problem.numIntVars) {
        if (probes >= SHAVE_MAX_ITERS || token()) break
        val d = root.intDomain(v)
        var lo = d.min
        var hi = d.max
        if (lo >= hi) continue
        while (lo < hi && probes++ < SHAVE_MAX_ITERS && !token() &&
            infeasibleUnder(v, lo, atMost = true, token = token)
        ) {
            lo += 1
        }
        while (hi > lo && probes++ < SHAVE_MAX_ITERS && !token() &&
            infeasibleUnder(v, hi, atMost = false, token = token)
        ) {
            hi -= 1
        }
        if (lo != d.min || hi != d.max) out.add(ShavedBound(v, lo, hi))
    }
    return out
}

/**
 * A candidate `a·x ≤ b` is redundant when a safe upper bound on `a·x` over the constraints still kept is at
 * most `b` (Neumaier and Shcherbina, "Safe bounds in linear and mixed-integer linear programming", Math.
 * Program. 2004, https://doi.org/10.1007/s10107-003-0433-3); `≥` uses the safe minimum and `=` needs both.
 * Judging each drop against the kept constraints means two mutually implied rows are never both removed.
 * The maximisation needs the primal pass ([LpSolver.solvePrimal]); the dual simplex cannot optimise it.
 */
internal fun LpEngine.redundantConstraints(token: ExecutionControl): List<Int> {
    if (lpRelaxer == null) return emptyList()
    val removed = IntHashSet()
    var probes = 0
    for (i in problem.factors.indices) {
        if (probes >= SHAVE_MAX_ITERS || token()) break
        val f = problem.factors[i]
        if (f !is Linear || f.op == LinearOp.NE) continue
        val row = f.integerConstants ?: continue
        probes++
        val kept = problem.factors.filterIndexed { idx, _ -> idx != i && !removed.contains(idx) }
        // The source rewrite keeps the real columns, declared value sets and open-side marks that a kept
        // row may address; restating the integer boxes alone would drop them.
        val others = problem.withFactors(kept.toTypedArray())
        val a = LongArray(problem.numIntVars)
        for (k in f.vars.indices) a[f.vars[k]] += row.coeff(k)
        val b = row.bound.toDouble()
        val redundant = when (f.op) {
            LinearOp.LE -> safeMax(others, a, token)?.let { it <= b } ?: false

            LinearOp.GE -> safeMin(others, a, token)?.let { it >= b } ?: false

            LinearOp.EQ -> {
                val mx = safeMax(others, a, token)
                mx != null && mx <= b && (safeMin(others, a, token)?.let { it >= b } ?: false)
            }
        }
        if (redundant) removed.add(i)
    }
    return removed.toIntArray().also(IntArray::sort).toList()
}

// Two-term unit differences `±(x − y)` whose safe min/max bracket holds exactly one integer `c`, returned as
// `= c` rows for affine elimination. Candidates are existing unit-difference rows, avoiding an O(n²) pair
// scan.
internal fun LpEngine.impliedEqualities(token: ExecutionControl): List<Linear> {
    if (lpRelaxer == null) return emptyList()
    val out = ArrayList<Linear>()
    val probed = LongHashSet()
    var probes = 0
    for (f in problem.factors) {
        if (probes >= SHAVE_MAX_ITERS || token()) break
        if (f !is Linear || f.op == LinearOp.EQ || f.vars.size != 2) continue
        val row = f.integerConstants ?: continue
        val c0 = row.coeff(0)
        val c1 = row.coeff(1)
        if (!((c0 == 1L && c1 == -1L) || (c0 == -1L && c1 == 1L))) continue
        val v0 = f.vars[0]
        val v1 = f.vars[1]
        if (v0 == v1 || !probed.add((minOf(v0, v1).toLong() shl Int.SIZE_BITS) or maxOf(v0, v1).toLong())) continue
        probes++
        val e = LongArray(problem.numIntVars)
        e[v0] = c0
        e[v1] = c1
        val lo = safeMin(problem, e, token) ?: continue
        val hi = safeMax(problem, e, token) ?: continue
        val cLo = ceil(lo - EQ_PIN_TOL)
        if (cLo != floor(hi + EQ_PIN_TOL) || !cLo.isFinite()) continue
        out.add(Linear(longArrayOf(c0, c1), intArrayOf(v0, v1), LinearOp.EQ, cLo.toLong()))
    }
    return out
}

// Uses the primal pass: the dual simplex cannot optimise an arbitrary objective from its dual-feasible start.
private fun LpEngine.safeMin(prob: Problem, coeffs: LongArray, token: ExecutionControl): Double? {
    val selected = projections?.forEngineView(prob)
    val session = PropagationSession(if (selected == null) PropagationProblem(prob)
    else PropagationProblem(prob, selected))
    if (session.isUnsatAtRoot) return null
    val relaxation = try {
        primalRelaxer(LinearObjective(intCoefficients = coeffs), prob, selected)
            .build(session, cancellation = token)
    } catch (_: LpAssemblyCancelled) {
        return null
    }
    if (relaxation.model.n == 0) return null
    val simplex = dualSimplex(relaxation.model, token)
    val result = try {
        simplex.solvePrimal()
    } catch (_: CheckedLongOverflowException) {
        null
    } finally {
        try {
            observeRootSolve(simplex)
        } finally {
            simplex.close()
        }
    }
    result ?: return null
    val lower = objectiveLowerBound(relaxation.model, result.duals, rootCertificationObserver()) ?: return null
    return lower + relaxation.objectiveConstant.toDouble()
}

private fun LpEngine.safeMax(prob: Problem, coeffs: LongArray, token: ExecutionControl): Double? =
    safeMin(prob, LongArray(coeffs.size) { -coeffs[it] }, token)?.let { -it }

// Builds from the declared domains without the O(span) bake, for pre-bake OBBT on wide domains.
private fun LpEngine.safeMinNoBake(coeffs: LongArray, token: ExecutionControl): Double? {
    val relaxation = try {
        primalRelaxer(LinearObjective(intCoefficients = coeffs)).build(RootDomains(problem), cancellation = token)
    } catch (_: CheckedLongOverflowException) {
        return null
    } catch (_: LpAssemblyCancelled) {
        return null
    }
    if (relaxation.model.n == 0) return null
    val simplex = dualSimplex(relaxation.model, token)
    val result = try {
        simplex.solvePrimal()
    } catch (_: CheckedLongOverflowException) {
        null
    } finally {
        try {
            observeRootSolve(simplex)
        } finally {
            simplex.close()
        }
    }
    result ?: return null
    val lower = objectiveLowerBound(relaxation.model, result.duals, rootCertificationObserver()) ?: return null
    return lower + relaxation.objectiveConstant.toDouble()
}

private fun LpEngine.safeMaxNoBake(coeffs: LongArray, token: ExecutionControl): Double? =
    safeMinNoBake(LongArray(coeffs.size) { -coeffs[it] }, token)?.let { -it }

// Optimisation-based bound tightening from the declared domains: one LP solve per bound, independent of
// the domain span, where the root bake would grind a wide domain down one step per round.
internal fun LpEngine.rootLpBoundsNoBake(token: ExecutionControl, minimumSpan: Long = 0L): List<ShavedBound> {
    if (lpRelaxer == null || token()) return emptyList()
    val n = problem.numIntVars
    val out = ArrayList<ShavedBound>()
    var done = 0
    for (v in widestFirst(n, minimumSpan)) {
        if (done >= OBBT_MAX_VARS || token()) break
        val d = problem.rootDomainOf(v)
        if (d.min >= d.max) continue
        done++
        val coeffs = LongArray(n).also { it[v] = 1L }
        val lo = safeMinNoBake(coeffs, token)?.let { ceil(it - EQ_PIN_TOL).toLong() }?.coerceAtLeast(d.min) ?: d.min
        val hi = safeMaxNoBake(coeffs, token)?.let { floor(it + EQ_PIN_TOL).toLong() }?.coerceAtMost(d.max) ?: d.max
        // An empty domain is rootLpInfeasibleNoBake's verdict to make.
        if (lo <= hi && (lo > d.min || hi < d.max)) out.add(ShavedBound(v, lo, hi))
    }
    return out
}

// Widest first, so the capped OBBT solves land where a bound is worth proving rather than on whichever
// variables come first. A domain whose width overflows Long sorts first.
private fun LpEngine.widestFirst(n: Int, minimumSpan: Long): List<Int> {
    val width = LongArray(n) { v ->
        val d = problem.rootDomainOf(v)
        val span = d.max - d.min
        if (span < 0L) Long.MAX_VALUE else span
    }
    return (0 until n).filter { width[it] > minimumSpan }.sortedByDescending { width[it] }
}

// A root already Unsat under propagation is left to the bake's own report.
internal fun LpEngine.rootInfeasible(token: ExecutionControl): Boolean {
    if (lpRelaxer == null || token()) return false
    val s = PropagationSession(problem)
    if (s.isUnsatAtRoot) return false
    return pruneNode(s, Double.POSITIVE_INFINITY, -1, true)
}

// Built from the declared domains, so it skips the O(span) bake a PropagationSession runs on construction.
internal fun LpEngine.rootLpInfeasibleNoBake(token: ExecutionControl): Boolean {
    val relaxer = lpRelaxer ?: return false
    if (token()) return false
    val model = try {
        relaxer.build(RootDomains(problem), cancellation = token).model
    } catch (_: CheckedLongOverflowException) {
        return false
    } catch (_: LpAssemblyCancelled) {
        return false
    }
    if (model.n == 0) return false
    val simplex = newTableauCutSolver(
        model,
        token,
        factory = solveContext.engineFactory,
        pricing = pricingOptions,
    )
    return simplex.use {
        // Only a dual-unbounded ray that survives Farkas checking proves infeasibility.
        val result = try {
            simplex.solve()
        } finally {
            observeRootSolve(simplex)
        }
        if (result != null) return@use false
        val floatRay = simplex.infeasibleRay ?: return@use false
        if (numericalDeductions) return@use floatFarkasRay(model, floatRay, solveContext.refutationSlack) != null
        solveContext.certificationPolicy.acceptNullable(
            LpCertifier.EXACT_FARKAS,
            integerFarkasRay(model, floatRay, observer = rootCertificationObserver()),
        ) != null
    }
}

internal class RootRelaxationSize(val cols: Int, val rows: Int, val nnz: Int) {
    // Per-solve cost proxy the harvest's probe total scales with.
    val cost: Long get() = rows.toLong() * (cols + rows) + nnz
}

internal fun LpEngine.rootRelaxationSize(): RootRelaxationSize? {
    val relaxer = lpRelaxer ?: return null
    val session = PropagationSession(problem)
    if (session.isUnsatAtRoot) return null
    val model = try {
        relaxer.build(session, cancellation = params.cancellation).model
    } catch (_: CheckedLongOverflowException) {
        return null
    } catch (_: LpAssemblyCancelled) {
        return null
    }
    return RootRelaxationSize(model.n, model.m, model.csc.colPtr[model.n])
}

private fun LpEngine.infeasibleUnder(v: Int, bound: Long, atMost: Boolean, token: ExecutionControl): Boolean {
    val s = PropagationSession(problem, token)
    if (s.isUnsatAtRoot || s.fixpointCancelled || token()) return false
    val assumed = if (atMost) s.implyIntAtMost(v, bound) else s.implyIntAtLeast(v, bound)
    return assumed is PropagationResult.Unsat || pruneNode(s, Double.POSITIVE_INFINITY, -1, true)
}

// Probe cap for each shaving, redundancy and implied-equality pass.
private const val SHAVE_MAX_ITERS = 64

// Caps pre-bake OBBT cost (two LP solves per variable); the root bake and harvest cover the rest.
private const val OBBT_MAX_VARS = 64

// Widening this slack only drops removals.
private const val EQ_PIN_TOL = 1e-6
