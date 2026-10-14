package com.eignex.klause.backtrack.lp

import com.eignex.klause.backtrack.snapshotAssignment
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpSolver
import com.eignex.klause.lp.engine.newTableauCutSolver
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.lp.rootDomainOf
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.IntArrayList
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round
import kotlin.random.Random
import kotlin.time.TimeSource

internal fun LpEngine.lpRoundingProbe(
    objective: LinearObjective,
    cancellation: Cancellation,
    initial: Sample? = null,
    accept: (Sample) -> Sample? = { it },
): Sample? {
    val started = TimeSource.Monotonic.markNow()
    var outcome = "RELAXATION_DECLINED"
    val session = PropagationSession(problem, cancellation)
    try {
        if (session.isUnsatAtRoot) {
            outcome = "DISCRETE_ROOT_REJECTED"
            return null
        }
        val relaxation = CpToLpRelaxation(problem, objective).build(session)
        if (relaxation.model.n == 0) {
            outcome = "EMPTY_RELAXATION"
            return null
        }
        val result = try {
            solvePrimalRelaxation(relaxation.model, cancellation, primal = false)
        } catch (_: CheckedLongOverflowException) {
            return null
        } ?: return null
        val primal = result.primal
        val seen = HashSet<String>()
        fun propose(rounding: LpRounding, seed: Sample? = null): Sample? {
            if (cancellation()) return null
            val attempt = if (rounding == LpRounding.NEAREST && seed == null) session else {
                PropagationSession(problem, cancellation)
            }
            try {
                if (attempt.isUnsatAtRoot) return null
                val sample = pinToward(attempt, relaxation, rounding) { col ->
                    if (col !in relaxation.colVarId.indices) {
                        null
                    } else if (seed == null) {
                        primal.getOrNull(col)
                    } else {
                        val variable = relaxation.colVarId[col]
                        if (relaxation.colIsBool[col]) {
                            if (seed.boolValue(variable)) 1.0 else 0.0
                        } else seed.intValue(variable).toDouble()
                    }
                }
                if (attempt.fixpointCancelled || cancellation()) {
                    outcome = "CANCELLED"
                    return null
                }
                if (sample == null) return null
                outcome = "PROPOSED"
                if (!seen.add(pumpKey(sample.ints, sample.bools))) return null
                return accept(sample)
            } finally {
                if (attempt !== session) primalPropagationWork += attempt.work
            }
        }
        outcome = "PINNING_DECLINED"
        propose(LpRounding.NEAREST)?.let { return it }
        if (problem.numRealVars > 0) {
            val seed = initial?.takeIf {
                it.numIntVars == problem.numIntVars && it.numBoolVars == problem.numBoolVars
            }
            if (seed != null) {
                propose(LpRounding.NEAREST, seed)?.let { return it }
            }
            propose(LpRounding.UP)?.let { return it }
            propose(LpRounding.DOWN)?.let { return it }
        }
        return null
    } finally {
        primalPropagationWork += session.work
        sink.lp.observePrimalPhase("PRIMAL_ROUNDING", outcome, started.elapsedNow().inWholeNanoseconds)
    }
}

/**
 * Pin every variable toward the value [targetOf] gives its LP column — the LP point for the rounding
 * probe, the rounded target for the feasibility pump — most-confident first and with the two-sided
 * fallback of [pinIntTowardLp], then snapshot. Every variable ends pinned to a single value, so a
 * non-null result covers the discrete assignment; null when a variable conflicts both ways.
 * Continuous feasibility remains the caller's completion duty.
 */
internal fun LpEngine.pinToward(
    session: PropagationSession,
    relaxation: LpRelaxation,
    rounding: LpRounding = LpRounding.NEAREST,
    targetOf: (col: Int) -> Double?,
): Sample? {
    val intOrder = (0 until problem.numIntVars).sortedBy { v ->
        targetOf(relaxation.intColOf[v])?.let { abs(it - round(it)) } ?: Double.MAX_VALUE
    }
    for (v in intOrder) {
        val d = session.intDomain(v)
        if (d.min == d.max) continue // already fixed by propagation
        if (!pinIntTowardLp(session, v, targetOf(relaxation.intColOf[v]), d.min, d.max, rounding)) return null
    }
    val boolOrder = (0 until problem.numBoolVars).sortedByDescending { b ->
        targetOf(relaxation.boolColOf[b])?.let { abs(it - 0.5) } ?: -1.0
    }
    for (b in boolOrder) {
        if (session.boolValue(b) != null) continue
        val target = targetOf(relaxation.boolColOf[b]) ?: 0.0
        val first = when (rounding) {
            LpRounding.NEAREST -> target >= 0.5
            LpRounding.UP -> target > 0.0
            LpRounding.DOWN -> target >= 1.0
        }
        if (session.pinBool(b, first) !is PropagationResult.Unsat) continue
        if (session.pinBool(b, !first) is PropagationResult.Unsat) return null
    }
    return snapshotAssignment(session)
}

internal enum class LpRounding { NEAREST, UP, DOWN }

/** Pin [v] to the rounded LP value (or [lo] when it has no LP column), falling back to the other side
 *  of the LP value on a conflict. False only when both candidates wipe out — a conflicting pin reverts
 *  the session to its pre-pin state, so the fallback resumes cleanly. */
private fun pinIntTowardLp(
    session: PropagationSession,
    v: Int,
    lp: Double?,
    lo: Long,
    hi: Long,
    rounding: LpRounding,
): Boolean {
    if (lp == null) return session.pinInt(v, lo) !is PropagationResult.Unsat
    val target = when (rounding) {
        LpRounding.NEAREST -> round(lp)
        LpRounding.UP -> ceil(lp)
        LpRounding.DOWN -> floor(lp)
    }
    val first = target.toLong().coerceIn(lo, hi)
    if (session.pinInt(v, first) !is PropagationResult.Unsat) return true
    val second = when {
        lp >= first && first < hi -> first + 1L
        lp < first && first > lo -> first - 1L
        else -> first
    }
    if (second == first) return false
    return session.pinInt(v, second) !is PropagationResult.Unsat
}

/**
 * Feasibility pump on the primal pass: when root rounding ([lpRoundingProbe]) fails to
 * land a feasible point, alternate between an LP solution and its integer rounding, each round
 * re-solving the relaxation under an L1-distance objective that pulls the LP toward the current
 * rounding, then retrying the rounding. The distance objective is exact (linear) over variables whose
 * rounded target sits at a declared bound — every Boolean and any integer rounded to its bound — and
 * omits interior integer targets, whose `|x − t|` is not linear; this only steers the search, never
 * affects soundness (a returned [Sample] is a fully-pinned, propagation-feasible assignment the caller
 * still re-evaluates). Stops at the first rounding admitted by [accept] or the round cap. A repeated rounding
 * (cycle) triggers a Fischetti–Glover–Lodi perturbation ([perturbRounding]) — flip the most-fractional
 * coordinates and keep pumping — up to [PUMP_MAX_RESTARTS] times before giving up. Re-solves prefer the
 * primal pass ([LpSolver.solvePrimal], phase-1 included) and fall back to the dual
 * [LpSolver.solve]. [initial] supplies only the first discrete target; its real coordinates and
 * claimed objective supply no admission authority.
 */
internal fun LpEngine.lpFeasibilityPump(
    objective: LinearObjective,
    cancellation: Cancellation,
    initial: Sample? = null,
    accept: (Sample) -> Sample? = { it },
): Sample? {
    val seed = initial?.takeIf { it.numIntVars == problem.numIntVars && it.numBoolVars == problem.numBoolVars }
    var solved = solveRelaxation(objective, cancellation) ?: return null
    val seen = HashSet<String>()
    val rng = Random(params.randomSeed ?: 0L)
    var restarts = 0
    repeat(PUMP_ROUNDS) { step ->
        if (cancellation()) return null
        val (relaxation, primal) = solved
        val intTarget = LongArray(problem.numIntVars) { v ->
            val col = relaxation.intColOf[v]
            val d = problem.rootDomainOf(v)
            if (step == 0 && seed != null) seed.intValue(v).coerceIn(d.min, d.max) else {
                if (col in 0 until primal.size) round(primal[col]).toLong().coerceIn(d.min, d.max) else d.min
            }
        }
        val boolTarget = BooleanArray(problem.numBoolVars) { b ->
            val col = relaxation.boolColOf[b]
            if (step == 0 && seed != null) seed.boolValue(b) else col in 0 until primal.size && primal[col] >= 0.5
        }
        if (!seen.add(pumpKey(intTarget, boolTarget))) {
            // Cycle: perturb the rounding (flip the most-fractional coordinates) and keep pumping, rather
            // than giving up. Steering only — a returned [Sample] is still re-checked downstream.
            if (++restarts > PUMP_MAX_RESTARTS) return null
            val flips = PUMP_MIN_FLIP + rng.nextInt(PUMP_MIN_FLIP + 1)
            perturbRounding(relaxation, primal, intTarget, boolTarget, flips, rng)
            seen.add(pumpKey(intTarget, boolTarget))
        }
        val started = TimeSource.Monotonic.markNow()
        val session = PropagationSession(problem, cancellation)
        var outcome = "PINNING_DECLINED"
        try {
            if (!session.isUnsatAtRoot) {
                val sample = pinToward(session, relaxation) { col ->
                    targetOfCol(relaxation, intTarget, boolTarget, col)
                }
                if (sample != null && !session.fixpointCancelled && !cancellation()) {
                    outcome = "PROPOSED"
                    accept(sample)?.let { return it }
                }
            }
        } finally {
            primalPropagationWork += session.work
            if (session.fixpointCancelled) outcome = "CANCELLED"
            sink.lp.observePrimalPhase("PRIMAL_PUMP_ROUND", outcome, started.elapsedNow().inWholeNanoseconds)
        }
        val distance = distanceObjective(relaxation, intTarget, boolTarget) ?: return null // nothing to pump
        solved = solveRelaxation(distance, cancellation) ?: return null
    }
    return null
}

/** The dedup key of a feasibility-pump rounding (its full integer + Boolean target tuple). */
private fun pumpKey(intTarget: LongArray, boolTarget: BooleanArray): String =
    intTarget.joinToString(",") + "|" + boolTarget.joinToString(",")

/**
 * Fischetti–Glover–Lodi perturbation: flip the [count] most-fractional structural coordinates of a
 * pump rounding **in place** — a Boolean flips its bit, an integer rounds the other way (toward the LP
 * value, clamped to its domain) — so a cycled pump escapes to a fresh rounding. An integral
 * distance optimum uses seeded live coordinates when fractional support is empty. Pure steering: the pump
 * only proposes roundings that [pinToward] re-realizes and the caller re-evaluates, so this never
 * affects soundness.
 */
private fun LpEngine.perturbRounding(
    relaxation: LpRelaxation,
    primal: DoubleArray,
    intTarget: LongArray,
    boolTarget: BooleanArray,
    count: Int,
    rng: Random,
) {
    // Live structural columns by fractionality |primal − target|, descending.
    val cols = IntArrayList()
    val live = IntArrayList()
    val fracs = ArrayList<Double>()
    for (col in relaxation.colVarId.indices) {
        if (col >= primal.size || relaxation.colVarId[col] < 0 || relaxation.model.fixed(col)) continue
        val variable = relaxation.colVarId[col]
        if (!relaxation.colIsBool[col] && problem.rootDomainOf(variable).valueCount <= 1L) continue
        live.add(col)
        val t = targetOfCol(relaxation, intTarget, boolTarget, col) ?: continue
        val f = abs(primal[col] - t)
        if (f > PUMP_FRAC_TOL) {
            cols.add(col)
            fracs.add(f)
        }
    }
    // An integral distance optimum can repeat a feasible point the consumer declined.
    val order = if (cols.size == 0) {
        (0 until live.size).shuffled(rng).map { live[it] }
    } else (0 until cols.size).sortedByDescending { fracs[it] }.map { cols[it] }
    for (rank in 0 until minOf(count, order.size)) {
        val col = order[rank]
        val v = relaxation.colVarId[col]
        if (relaxation.colIsBool[col]) {
            boolTarget[v] = !boolTarget[v]
        } else {
            val d = problem.rootDomainOf(v)
            val cur = intTarget[v]
            intTarget[v] = when {
                primal[col] >= cur && cur < d.max -> cur + 1L
                cur > d.min -> cur - 1L
                cur < d.max -> cur + 1L
                else -> cur
            }
        }
    }
}

/** Build [obj]'s relaxation at the root and solve it, preferring the primal pass (phase-1 included) and
 *  falling back to the dual solve; null on a trivial/overflowing/failed relaxation. */
private fun LpEngine.solveRelaxation(
    obj: LinearObjective,
    cancellation: Cancellation,
): Pair<LpRelaxation, DoubleArray>? {
    val session = PropagationSession(problem, cancellation)
    try {
        if (session.isUnsatAtRoot) return null
        val relaxation = CpToLpRelaxation(problem, obj).build(session)
        if (relaxation.model.n == 0) return null
        val result = try {
            solvePrimalRelaxation(relaxation.model, cancellation, primal = true)
                ?: solvePrimalRelaxation(relaxation.model, cancellation, primal = false)
        } catch (_: CheckedLongOverflowException) {
            return null
        } ?: return null
        return relaxation to result.primal
    } finally {
        primalPropagationWork += session.work
    }
}

private fun LpEngine.solvePrimalRelaxation(
    model: LpModel,
    cancellation: Cancellation,
    primal: Boolean,
): FloatLpResult? {
    requireOpen()
    if (cancellation()) return null
    val started = TimeSource.Monotonic.markNow()
    val simplex = newTableauCutSolver(model, cancellation,
        workLimit = rootPrimalWorkBudget(), factory = solveContext.engineFactory, pricing = pricingOptions)
    var outcome = "FAILED"
    try {
        val result = if (primal) simplex.solvePrimal() else simplex.solve()
        outcome = if (result?.optimal == true) "OPTIMAL" else {
            val reason = simplex.lastTermination?.name ?: "OTHER"
            if (result == null) "NO_POINT_$reason" else "NON_OPTIMAL_$reason"
        }
        return result?.takeIf { it.optimal }
    } finally {
        try {
            observeRootSolve(simplex)
        } finally {
            try {
                simplex.close()
            } finally {
                val stage = if (primal) "PRIMAL_LP_PRIMAL" else "PRIMAL_LP_DUAL"
                sink.lp.observePrimalPhase(stage, outcome, started.elapsedNow().inWholeNanoseconds)
            }
        }
    }
}

/** The rounded target of structural column [col] as a double, or null when it backs no live variable. */
private fun LpEngine.targetOfCol(
    relaxation: LpRelaxation,
    intTarget: LongArray,
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
    intTarget: LongArray,
    boolTarget: BooleanArray,
): LinearObjective? {
    var any = false
    val intCoef = LongArray(problem.numIntVars) { v ->
        if (relaxation.intColOf[v] < 0) return@LongArray 0L
        val d = problem.rootDomainOf(v)
        when {
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
