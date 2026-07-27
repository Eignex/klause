package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.solver.Cancellation

/**
 * A variable's integer bounds for [tightenOpenIntBounds]: a `null` side is genuinely ±∞ (open), a `Long`
 * side is a finite bound. A fully-bounded variable has both sides set.
 */
internal class OpenIntBounds(val lo: Long?, val hi: Long?)

/**
 * Optimization-based bound tightening (OBBT): close open (±∞) integer-variable sides to sound finite
 * bounds from the LP relaxation of [constraints]. A variable open above takes the relaxation's maximum
 * of itself as a valid upper bound — the relaxation contains every integer solution — and open below
 * takes its minimum. A side the LP leaves unbounded (its optimum only reaches the free-column frontier)
 * stays open (`null`) for the caller to clamp.
 *
 * The tightening is sound over genuine ±∞ because an open side enters the LP as a real free column
 * ([LpBuilder.addFreeVar]): a derived bound holds over the true unbounded region, not a pre-clamped box.
 * This is why it must run before a [com.eignex.klause.solver.Problem]'s finite
 * [com.eignex.klause.solver.IntDomain]s are committed — once a side is clamped the "genuinely infinite"
 * information is gone. Only [LinearOp.LE]/[LinearOp.GE]/[LinearOp.EQ] constraints enter; any other
 * relation is skipped (dropping a constraint only loosens the relaxation, never unsound).
 *
 * A cheap [feasibility-based prefilter][fbbtTightenOpenIntBounds] runs first: interval bound propagation
 * closes every side a single row already implies (`O(nnz)` per pass, no LP), so those cost no LP solve
 * and the LP-solving pass only handles the sides that genuinely need the global relaxation.
 *
 * That LP relaxation is built **once** and every remaining open side is a re-solve of that one model with
 * a single ±1 cost swapped onto its column ([LpModel.withSingleColumnObjective]) — the matrix, rows and
 * bounds never change, so the previous optimal basis stays primal-feasible and the primal simplex
 * warm-starts from it ([LpSolver.solvePrimal]) in a few pivots rather than refactorizing a freshly-built
 * model per side. Each side's LP bound is derived over the prefiltered (but not further-OBBT-tightened)
 * bounds, so — unlike a sequential pass that feeds each closed side into later solves — an LP bound is
 * never sharpened by an earlier LP bound; every bound is still individually sound (the relaxation contains
 * every solution), only potentially looser, which never removes a feasible point.
 *
 * @param bounds current per-variable bounds, indexed by variable id.
 * @param constraints the linear constraints over those variable ids (an objective is not a constraint;
 *   the caller excludes it).
 * @param cancellation polled between variables so a long presolve can bail early — also threaded into each
 *   solve, so a single overlong LP re-solve is cut off too.
 * @return a fresh bounds array with every provable open side closed.
 */
internal fun tightenOpenIntBounds(
    bounds: Array<OpenIntBounds>,
    constraints: List<Linear>,
    cancellation: Cancellation = Cancellation.Never,
): Array<OpenIntBounds> {
    val n = bounds.size
    // Cheap feasibility-based prefilter first: interval propagation closes every side a single row already
    // implies, so those cost no LP solve; the LP-solving pass below only handles what survives.
    val work = fbbtTightenOpenIntBounds(bounds, constraints, cancellation)
    if (work.none { it.lo == null || it.hi == null }) return work // nothing open left for the LP pass

    // Column j is variable j (added in id order); a genuine open side is a free column. Objective is zero
    // — each solve swaps in its own single-column cost.
    val builder = LpBuilder()
    for (v in 0 until n) {
        val b = work[v]
        if (b.lo != null && b.hi != null) builder.addVar(b.lo, b.hi) else builder.addFreeVar(b.lo, b.hi)
    }
    for (f in constraints) {
        val rel = when (f.op) {
            LinearOp.LE -> Relation.LE
            LinearOp.GE -> Relation.GE
            LinearOp.EQ -> Relation.EQ
            else -> continue
        }
        builder.addRow(IntArray(f.vars.size) { f.vars[it] }, f.coeffs.copyOf(), rel, f.bound)
    }
    val base = try {
        builder.build(Sense.MINIMIZE)
    } catch (_: LpOverflowException) {
        return work // cannot relax; leave every open side to the caller's clamp
    }

    var warm: Basis? = null
    var prevCol = -1
    for (v in 0 until n) {
        if (cancellation()) break
        val cur = work[v]
        if (cur.lo != null && cur.hi != null) continue
        var newHi = cur.hi
        var newLo = cur.lo
        // maximize x_v bounds the open upper side; minimize bounds the open lower side.
        for (maximize in booleanArrayOf(true, false)) {
            if (if (maximize) cur.hi != null else cur.lo != null) continue
            val model = base.withSingleColumnObjective(v, if (maximize) -1L else 1L, prevCol)
            prevCol = v
            val result = try {
                newLpSolver(model, cancellation).solvePrimal(warm)
            } catch (_: LpOverflowException) {
                null
            }
            if (result != null) {
                warm = result.basis
                val bound = model.tightVariableBound(result, v, maximize)
                if (maximize) newHi = bound else newLo = bound
            }
        }
        work[v] = OpenIntBounds(newLo, newHi)
    }
    return work
}

/** Upper bound on the number of full propagation passes; bounds only ever tighten, so the loop reaches a
 *  fixpoint in finite steps, but a long dependency chain could take many passes — cap it and leave any
 *  residual to the LP pass. */
private const val FBBT_MAX_PASSES = 16

/**
 * Feasibility-based bound tightening (FBBT): propagate [constraints] as interval bounds to a fixpoint,
 * closing open sides a single row already implies — the cheap `O(nnz)`-per-pass prefilter for
 * [tightenOpenIntBounds], so a side bounded by simple propagation needs no LP solve. For a row
 * `Σ aⱼ·xⱼ ≤ b` the extreme of the other terms is isolated against each coefficient: with the row's
 * minimum activity `A = Σ min(aⱼ·xⱼ)`, variable `k` satisfies `aₖ·xₖ ≤ b − (A − min(aₖ·xₖ))`, giving an
 * upper bound when `aₖ > 0` and a lower bound when `aₖ < 0`; an equality also propagates its `≥ b`
 * direction (the row negated). A term whose relevant side is open makes that side of the activity `−∞`:
 * with two or more such terms the row implies nothing, with exactly one only that term can be bounded.
 * Every derived bound holds at every solution (it is implied by the row), so a closed side is sound — a
 * genuine bound, never a clamp. Integer variables floor an upper / ceil a lower candidate. Any overflow
 * in the interval arithmetic skips that tightening. Iterated to a fixpoint up to [FBBT_MAX_PASSES].
 */
private fun fbbtTightenOpenIntBounds(
    bounds: Array<OpenIntBounds>,
    constraints: List<Linear>,
    cancellation: Cancellation,
): Array<OpenIntBounds> {
    val n = bounds.size
    val b = MutableIntBounds(n)
    for (i in 0 until n) {
        bounds[i].lo?.let { b.setLo(i, it) }
        bounds[i].hi?.let { b.setHi(i, it) }
    }
    // Only LE/EQ rows propagate — [Linear] canonicalises GE to LE, and NE is not an interval bound.
    val rows = constraints.filter { it.op == LinearOp.LE || it.op == LinearOp.EQ }
    if (rows.isEmpty()) return bounds

    var pass = 0
    var changed = true
    while (changed && pass < FBBT_MAX_PASSES) {
        if (cancellation()) break
        changed = false
        pass++
        for (f in rows) {
            if (propagateRow(f.coeffs, f.vars, f.bound, sign = 1L, b = b)) changed = true
            // An equality also bounds from below: `Σ aⱼ·xⱼ ≥ b`, i.e. `−Σ aⱼ·xⱼ ≤ −b`.
            if (f.op == LinearOp.EQ && propagateRow(f.coeffs, f.vars, f.bound, sign = -1L, b = b)) changed = true
        }
    }
    return Array(n) { OpenIntBounds(b.loOrNull(it), b.hiOrNull(it)) }
}

/** Working per-variable bounds for [fbbtTightenOpenIntBounds] in primitive arrays (no `Long?` boxing): a
 *  side is open (`±∞`) when its `*Open` flag is set, else its value is in `*Val`. */
private class MutableIntBounds(n: Int) {
    private val loVal = LongArray(n)
    private val hiVal = LongArray(n)
    private val loOpen = BooleanArray(n) { true }
    private val hiOpen = BooleanArray(n) { true }

    fun loOpen(i: Int) = loOpen[i]
    fun hiOpen(i: Int) = hiOpen[i]
    fun loVal(i: Int) = loVal[i]
    fun hiVal(i: Int) = hiVal[i]
    fun loOrNull(i: Int): Long? = if (loOpen[i]) null else loVal[i]
    fun hiOrNull(i: Int): Long? = if (hiOpen[i]) null else hiVal[i]

    fun setLo(i: Int, v: Long) {
        loVal[i] = v
        loOpen[i] = false
    }

    fun setHi(i: Int, v: Long) {
        hiVal[i] = v
        hiOpen[i] = false
    }
}

/** Propagate the row `Σ (sign·coeffs(k))·vars(k) ≤ sign·bound` into [b], returning whether any bound
 *  tightened. Overflow in the exact activity arithmetic aborts this row (returns `false`). */
@Suppress("ReturnCount", "LoopWithTooManyJumpStatements")
private fun propagateRow(
    coeffs: LongArray,
    vars: IntArray,
    bound: Long,
    sign: Long,
    b: MutableIntBounds,
): Boolean = try {
    val effBound = mulExact(sign, bound)
    // Minimum activity Σ min(aⱼ·xⱼ) over finite terms, plus how many terms are −∞ (open on the min side).
    var minActivity = 0L
    var numInf = 0
    var infIdx = -1
    for (idx in coeffs.indices) {
        val a = mulExact(sign, coeffs[idx])
        if (a == 0L) continue
        val v = vars[idx]
        if (if (a > 0L) b.loOpen(v) else b.hiOpen(v)) {
            numInf++
            infIdx = idx
            if (numInf > 1) return false // ≥2 unbounded terms ⇒ the row implies nothing
        } else {
            minActivity = addExact(minActivity, mulExact(a, if (a > 0L) b.loVal(v) else b.hiVal(v)))
        }
    }
    var changed = false
    for (idx in coeffs.indices) {
        val a = mulExact(sign, coeffs[idx])
        if (a == 0L) continue
        val v = vars[idx]
        val restMin = when {
            numInf == 0 -> subExact(minActivity, mulExact(a, if (a > 0L) b.loVal(v) else b.hiVal(v)))
            infIdx == idx -> minActivity // this term was the sole −∞; the rest is finite
            else -> continue // the sole −∞ is a different term ⇒ no bound for this one
        }
        val num = subExact(effBound, restMin)
        if (a > 0L) {
            val cand = floorDivSafe(num, a) ?: continue
            if (b.hiOpen(v) || cand < b.hiVal(v)) {
                b.setHi(v, cand)
                changed = true
            }
        } else {
            val cand = ceilDivSafe(num, a) ?: continue
            if (b.loOpen(v) || cand > b.loVal(v)) {
                b.setLo(v, cand)
                changed = true
            }
        }
    }
    changed
} catch (_: LpOverflowException) {
    false
}

/** `⌊a / b⌋`, or null on the one non-representable case (`Long.MIN_VALUE / −1`). */
private fun floorDivSafe(a: Long, b: Long): Long? = if (a == Long.MIN_VALUE && b == -1L) null else a.floorDiv(b)

/** `⌈a / b⌉` = `⌊a / b⌋ + (a not divisible by b ? 1 : 0)`, or null on overflow. */
private fun ceilDivSafe(a: Long, b: Long): Long? {
    if (a == Long.MIN_VALUE && b == -1L) return null
    val q = a.floorDiv(b)
    return if (a % b != 0L) (if (q == Long.MAX_VALUE) null else q + 1L) else q
}
