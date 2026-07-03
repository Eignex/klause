package com.eignex.klause.backtrack.lp

import com.eignex.klause.backtrack.snapshotAssignment
import com.eignex.klause.lp.LpOverflowException
import com.eignex.klause.lp.RevisedSimplex
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.IntArrayList
import kotlin.math.abs
import kotlin.math.round
import kotlin.random.Random

/**
 * LP-rounding primal heuristic (#287) on the sparse revised-simplex path: solve the root relaxation,
 * round each variable's fractional LP value to the nearest in-domain integer, and propagate. Returns a
 * complete assignment when rounding-then-propagation reaches a fixpoint without a wipeout, else null.
 * Sound by construction — the result is a candidate that the caller re-evaluates against the objective
 * and only keeps if feasible-and-improving; a bad rounding just yields null or a worse incumbent.
 *
 * Two refinements over rounding each variable once in declaration order. Variables are pinned in order
 * of the LP's confidence — least-fractional integers and most-decisive Booleans first — so the values
 * the relaxation is surest about propagate first and steer the rest. And when a rounding conflicts the
 * other side of the LP value is tried before giving up, recovering assignments a one-shot round drops.
 * Every variable still ends pinned to a single value, so a returned snapshot is a complete feasible
 * point (the caller does not re-check feasibility); a variable that conflicts both ways yields null.
 */
internal fun LpEngine.lpRoundingProbe(objective: LinearObjective, cancellation: Cancellation): Sample? {
    val session = PropagationSession(problem)
    if (session.isUnsatAtRoot) return null
    val relaxation = CpToLpRelaxation(problem, objective).build(session)
    if (relaxation.model.n == 0) return null
    val result = try {
        dualSimplex(relaxation.model, cancellation).solve()
    } catch (_: LpOverflowException) {
        return null
    } ?: return null
    val primal = result.primal
    return pinToward(session, relaxation) { col -> if (col in 0 until primal.size) primal[col] else null }
}

/**
 * Pin every variable toward the value [targetOf] gives its LP column — the LP point for the rounding
 * probe, the rounded target for the feasibility pump — most-confident first and with the two-sided
 * fallback of [pinIntTowardLp], then snapshot. Every variable ends pinned to a single value, so a
 * non-null result is a complete feasible assignment; null when a variable conflicts both ways.
 */
internal fun LpEngine.pinToward(
    session: PropagationSession,
    relaxation: LpRelaxation,
    targetOf: (col: Int) -> Double?,
): Sample? {
    val intOrder = (0 until problem.numIntVars).sortedBy { v ->
        targetOf(relaxation.intColOf[v])?.let { abs(it - round(it)) } ?: Double.MAX_VALUE
    }
    for (v in intOrder) {
        val d = session.intDomain(v)
        if (d.min == d.max) continue // already fixed by propagation
        if (!pinIntTowardLp(session, v, targetOf(relaxation.intColOf[v]), d.min, d.max)) return null
    }
    val boolOrder = (0 until problem.numBoolVars).sortedByDescending { b ->
        targetOf(relaxation.boolColOf[b])?.let { abs(it - 0.5) } ?: -1.0
    }
    for (b in boolOrder) {
        if (session.boolValue(b) != null) continue
        val first = (targetOf(relaxation.boolColOf[b]) ?: 0.0) >= 0.5
        if (session.pinBool(b, first) !is PropagationResult.Unsat) continue
        if (session.pinBool(b, !first) is PropagationResult.Unsat) return null
    }
    return snapshotAssignment(session)
}

/** Pin [v] to the rounded LP value (or [lo] when it has no LP column), falling back to the other side
 *  of the LP value on a conflict. False only when both candidates wipe out — a conflicting pin reverts
 *  the session to its pre-pin state, so the fallback resumes cleanly. */
private fun pinIntTowardLp(session: PropagationSession, v: Int, lp: Double?, lo: Int, hi: Int): Boolean {
    if (lp == null) return session.pinInt(v, lo) !is PropagationResult.Unsat
    val first = round(lp).toInt().coerceIn(lo, hi)
    if (session.pinInt(v, first) !is PropagationResult.Unsat) return true
    val second = (if (lp >= first) first + 1 else first - 1).coerceIn(lo, hi)
    if (second == first) return false
    return session.pinInt(v, second) !is PropagationResult.Unsat
}

/**
 * Feasibility pump (#52) on the primal pass: when single-shot rounding ([lpRoundingProbe]) fails to
 * land a feasible point, alternate between an LP solution and its integer rounding, each round
 * re-solving the relaxation under an L1-distance objective that pulls the LP toward the current
 * rounding, then retrying the rounding. The distance objective is exact (linear) over variables whose
 * rounded target sits at a declared bound — every Boolean and any integer rounded to its bound — and
 * omits interior integer targets, whose `|x − t|` is not linear; this only steers the search, never
 * affects soundness (a returned [Sample] is a fully-pinned, propagation-feasible assignment the caller
 * still re-evaluates). Stops at the first feasible rounding or the round cap. A repeated rounding
 * (cycle) triggers a Fischetti–Glover–Lodi perturbation ([perturbRounding]) — flip the most-fractional
 * coordinates and keep pumping — up to [PUMP_MAX_RESTARTS] times before giving up. Re-solves prefer the
 * primal pass ([RevisedSimplex.solvePrimal], phase-1 included) and fall back to the dual
 * [RevisedSimplex.solve].
 */
internal fun LpEngine.lpFeasibilityPump(objective: LinearObjective, cancellation: Cancellation): Sample? {
    var solved = solveRelaxation(objective, cancellation) ?: return null
    val seen = HashSet<String>()
    val rng = Random(params.randomSeed ?: 0L)
    var restarts = 0
    repeat(PUMP_ROUNDS) {
        if (cancellation()) return null
        val (relaxation, primal) = solved
        val intTarget = IntArray(problem.numIntVars) { v ->
            val col = relaxation.intColOf[v]
            val d = problem.intDomains[v]
            if (col in 0 until primal.size) round(primal[col]).toInt().coerceIn(d.min, d.max) else d.min
        }
        val boolTarget = BooleanArray(problem.numBoolVars) { b ->
            val col = relaxation.boolColOf[b]
            col in 0 until primal.size && primal[col] >= 0.5
        }
        if (!seen.add(pumpKey(intTarget, boolTarget))) {
            // Cycle: perturb the rounding (flip the most-fractional coordinates) and keep pumping, rather
            // than giving up. Steering only — a returned [Sample] is still re-checked downstream.
            if (++restarts > PUMP_MAX_RESTARTS) return null
            val flips = PUMP_MIN_FLIP + rng.nextInt(PUMP_MIN_FLIP + 1)
            perturbRounding(relaxation, primal, intTarget, boolTarget, flips)
            seen.add(pumpKey(intTarget, boolTarget))
        }
        val session = PropagationSession(problem)
        if (!session.isUnsatAtRoot) {
            val sample = pinToward(session, relaxation) { col -> targetOfCol(relaxation, intTarget, boolTarget, col) }
            if (sample != null) return sample // rounding realized into a feasible incumbent
        }
        val distance = distanceObjective(relaxation, intTarget, boolTarget) ?: return null // nothing to pump
        solved = solveRelaxation(distance, cancellation) ?: return null
    }
    return null
}

/** The dedup key of a feasibility-pump rounding (its full integer + Boolean target tuple). */
private fun pumpKey(intTarget: IntArray, boolTarget: BooleanArray): String =
    intTarget.joinToString(",") + "|" + boolTarget.joinToString(",")

/**
 * Fischetti–Glover–Lodi perturbation: flip the [count] most-fractional structural coordinates of a
 * pump rounding **in place** — a Boolean flips its bit, an integer rounds the other way (toward the LP
 * value, clamped to its domain) — so a cycled pump escapes to a fresh rounding. Pure steering: the pump
 * only proposes roundings that [pinToward] re-realizes and the caller re-evaluates, so this never
 * affects soundness.
 */
private fun LpEngine.perturbRounding(
    relaxation: LpRelaxation,
    primal: DoubleArray,
    intTarget: IntArray,
    boolTarget: BooleanArray,
    count: Int,
) {
    // Live structural columns by fractionality |primal − target|, descending.
    val cols = IntArrayList()
    val fracs = ArrayList<Double>()
    for (col in relaxation.colVarId.indices) {
        if (col >= primal.size || relaxation.colVarId[col] < 0) continue
        val t = targetOfCol(relaxation, intTarget, boolTarget, col) ?: continue
        val f = abs(primal[col] - t)
        if (f > PUMP_FRAC_TOL) {
            cols.add(col)
            fracs.add(f)
        }
    }
    val order = (0 until cols.size).sortedByDescending { fracs[it] }
    for (rank in 0 until minOf(count, order.size)) {
        val col = cols[order[rank]]
        val v = relaxation.colVarId[col]
        if (relaxation.colIsBool[col]) {
            boolTarget[v] = !boolTarget[v]
        } else {
            val d = problem.intDomains[v]
            val cur = intTarget[v]
            intTarget[v] = if (primal[col] >= cur) (cur + 1).coerceAtMost(d.max) else (cur - 1).coerceAtLeast(d.min)
        }
    }
}

/** Build [obj]'s relaxation at the root and solve it, preferring the primal pass (phase-1 included) and
 *  falling back to the dual solve; null on a trivial/overflowing/failed relaxation. */
private fun LpEngine.solveRelaxation(
    obj: LinearObjective,
    cancellation: Cancellation,
): Pair<LpRelaxation, DoubleArray>? {
    val session = PropagationSession(problem)
    if (session.isUnsatAtRoot) return null
    val relaxation = CpToLpRelaxation(problem, obj).build(session)
    if (relaxation.model.n == 0) return null
    val result = try {
        dualSimplex(relaxation.model, cancellation).solvePrimal()
            ?: dualSimplex(relaxation.model, cancellation).solve()
    } catch (_: LpOverflowException) {
        return null
    } ?: return null
    return relaxation to result.primal
}

/** The rounded target of structural column [col] as a double, or null when it backs no live variable. */
private fun LpEngine.targetOfCol(
    relaxation: LpRelaxation,
    intTarget: IntArray,
    boolTarget: BooleanArray,
    col: Int,
): Double? {
    if (col < 0 || col >= relaxation.colVarId.size) return null
    val v = relaxation.colVarId[col]
    return if (relaxation.colIsBool[col]) {
        if (boolTarget[v]) 1.0 else 0.0
    } else {
        intTarget[v].toDouble()
    }
}

/** L1-distance objective pulling the LP toward the rounding: `+1·x` for a target at the lower bound
 *  (minimize the gap above it), `−1·x` for one at the upper bound, and 0 for an interior integer target
 *  (whose `|x − t|` is not linear). Null when no coordinate contributes — nothing left to pump. */
private fun LpEngine.distanceObjective(
    relaxation: LpRelaxation,
    intTarget: IntArray,
    boolTarget: BooleanArray,
): LinearObjective? {
    var any = false
    val intCoef = LongArray(problem.numIntVars) { v ->
        val d = problem.intDomains[v]
        when {
            relaxation.intColOf[v] < 0 -> 0L

            intTarget[v] <= d.min -> {
                any = true
                1L
            }

            intTarget[v] >= d.max -> {
                any = true
                -1L
            }

            else -> 0L
        }
    }
    val boolCoef = LongArray(problem.numBoolVars) { b ->
        if (relaxation.boolColOf[b] < 0) {
            0L
        } else {
            any = true
            if (boolTarget[b]) -1L else 1L
        }
    }
    return if (any) LinearObjective(boolWeights = boolCoef, intCoefficients = intCoef) else null
}

/** Round cap for the feasibility pump before it gives up to search. */
private const val PUMP_ROUNDS = 20

/** Max pump cycles to escape by perturbation before giving up. */
private const val PUMP_MAX_RESTARTS = 8

/** Base count of most-fractional coordinates flipped per perturbation (actual = base + rand[0, base]). */
private const val PUMP_MIN_FLIP = 3

/** Below this `|primal − target|` a coordinate is effectively integral — not worth flipping. */
private const val PUMP_FRAC_TOL = 1e-6
