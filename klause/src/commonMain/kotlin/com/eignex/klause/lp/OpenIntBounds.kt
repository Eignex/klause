package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.util.EmptyDoubleArray
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * A variable's integer bounds for [tightenOpenIntBounds]: a `null` side is genuinely ±∞ (open), a `Long`
 * side is a finite bound. A fully-bounded variable has both sides set.
 */
internal class OpenIntBounds(val lo: Long?, val hi: Long?)

/**
 * What [tightenOpenIntBounds] derived: the tightened [bounds], and whether the tightening refuted the
 * system instead of bounding it.
 *
 * [bounds] never carries a crossed pair. A variable whose two sides meet is reported through [refuted] and
 * its own pair left at the singleton where they met, so every consumer still reads a valid domain.
 */
internal class TightenedIntBounds(val bounds: Array<OpenIntBounds>, val refuted: Boolean = false)

/**
 * Optimization-based bound tightening (OBBT): close open (±∞) integer-variable sides to sound finite
 * bounds from the LP relaxation of [constraints]. A variable open above takes the relaxation's maximum
 * of itself as a valid upper bound — the relaxation contains every integer solution — and open below
 * takes its minimum. A side the LP leaves unbounded (its optimum only reaches the free-column frontier)
 * stays open (`null`) for the caller to clamp.
 *
 * The tightening is sound over genuine ±∞ because an open side enters the LP genuinely open — open
 * above as a probe-flagged free upper ([LpBuilder.addFreeVar]), open below as the zero-shift split
 * `x = x⁺ − x⁻` — so a derived bound holds over the true unbounded region, not a pre-clamped box.
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
 * @param realConstraints mixed integer/real rows joined through LP-only continuous columns, so a
 *   variable defined only through a real row (a floor definition, a `to_real` bridge) is still
 *   boundable; strict rows enter non-strict (a relaxation — every derived bound stays sound).
 * @param realLower per-real-variable declared lower bounds (`-inf` for open); indexed by real id.
 * @param realUpper per-real-variable declared upper bounds (`+inf` for open); indexed by real id.
 * @return fresh bounds with every provable open side closed, or the prefilter's refutation of the system.
 */
internal fun tightenOpenIntBounds(
    bounds: Array<OpenIntBounds>,
    constraints: List<Linear>,
    cancellation: Cancellation = Cancellation.Never,
    realConstraints: List<Linear> = emptyList(),
    realLower: DoubleArray = EmptyDoubleArray,
    realUpper: DoubleArray = EmptyDoubleArray,
): TightenedIntBounds {
    val n = bounds.size
    // Working real-variable bounds the prefilter tightens alongside the integers (outward-rounded, so
    // always sound); the LP pass then starts from the tighter real boxes too.
    var maxReal = realLower.size
    for (f in realConstraints) for (rv in f.realVars) if (rv + 1 > maxReal) maxReal = rv + 1
    val rLo = DoubleArray(maxReal) { realLower.getOrNull(it) ?: Double.NEGATIVE_INFINITY }
    val rUp = DoubleArray(maxReal) { realUpper.getOrNull(it) ?: Double.POSITIVE_INFINITY }
    // Cheap feasibility-based prefilter first: interval propagation closes every side a single row already
    // implies, so those cost no LP solve; the LP-solving pass below only handles what survives.
    val prefiltered = fbbtTightenOpenIntBounds(bounds, constraints, cancellation, realConstraints, rLo, rUp)
    if (prefiltered.refuted) return prefiltered // the system has no solution; there is nothing to bound
    val work = prefiltered.bounds
    if (work.none { it.lo == null || it.hi == null }) return prefiltered // nothing open left for the LP pass
    if (cancellation()) return prefiltered // the prefilter consumed the budget; don't start a factorization

    val rx = openRelaxation(work, constraints, realConstraints, rLo, rUp)
    val builder = rx.builder
    val posCol = rx.posCol
    val negCol = rx.negCol
    val base = try {
        builder.build(Sense.MINIMIZE)
    } catch (_: LpOverflowException) {
        return prefiltered // cannot relax; leave every open side to the caller's clamp
    }

    // Past the full-model row cap, each probe restricts to the row-capped neighborhood of its
    // column pair instead: the initial factorization of the full basis is one uninterruptible call
    // whose cost grows superlinearly with the row count, while a whole-row subset is a pure relaxation
    // — every neighborhood bound is valid on the full model, so large instances keep their locally
    // derivable finite bounds instead of falling to the clamp.
    if (base.m > OBBT_MAX_LP_ROWS) {
        return TightenedIntBounds(tightenByNeighborhoodProbes(base, work, posCol, negCol, cancellation))
    }

    var warm: Basis? = null
    var prevPos = -1
    var prevNeg = -1
    var solves = 0
    for (v in 0 until n) {
        if (cancellation() || solves >= OBBT_MAX_SIDE_SOLVES) break
        val cur = work[v]
        if (cur.lo != null && cur.hi != null) continue
        var newHi = cur.hi
        var newLo = cur.lo
        // maximize x_v bounds the open upper side; minimize bounds the open lower side.
        for (maximize in booleanArrayOf(true, false)) {
            if (if (maximize) cur.hi != null else cur.lo != null) continue
            solves++
            val model = base.withSingleColumnObjective(
                posCol[v],
                if (maximize) -1L else 1L,
                prevPos,
                negCol = negCol[v],
                prevNegCol = prevNeg,
            )
            prevPos = posCol[v]
            prevNeg = negCol[v]
            val result = try {
                newLpSolver(model, cancellation).solvePrimal(warm)
            } catch (_: LpOverflowException) {
                null
            }
            if (result != null) {
                warm = result.basis
                // The open direction's probe: the upper probe of x⁺ when maximizing, of x⁻ (whose growth
                // is x's descent) when minimizing an open-below variable.
                val probeCol = if (maximize || negCol[v] < 0) posCol[v] else negCol[v]
                val bound = model.tightVariableBound(result, v, maximize, model.probeClampedHi[probeCol])
                if (maximize) newHi = bound else newLo = bound
            }
        }
        work[v] = probedBounds(cur, newLo, newHi)
    }
    return TightenedIntBounds(work)
}

/**
 * The oversized-model LP pass: per open side, extract the [OBBT_NEIGHBORHOOD_ROWS]-capped
 * [columnNeighborhood] of the variable's column pair from [base] and probe on that sub-model. Each
 * probe's factorization is bounded by the neighborhood cap regardless of the full model's size, and
 * [cancellation] is honoured between probes. Bounds transfer soundly because the neighborhood drops
 * whole rows only (see [LpNeighborhood]); a bound the neighborhood leaves at the probe frontier stays
 * open, exactly as on the full model.
 */
private fun tightenByNeighborhoodProbes(
    base: LpModel,
    work: Array<OpenIntBounds>,
    posCol: IntArray,
    negCol: IntArray,
    cancellation: Cancellation,
): Array<OpenIntBounds> {
    val rowIndex = base.rowIndex()
    var solves = 0
    for (v in work.indices) {
        if (cancellation() || solves >= OBBT_MAX_SIDE_SOLVES) break
        val cur = work[v]
        if (cur.lo != null && cur.hi != null) continue
        val seeds = if (negCol[v] >= 0) intArrayOf(posCol[v], negCol[v]) else intArrayOf(posCol[v])
        val nb = base.columnNeighborhood(seeds, OBBT_NEIGHBORHOOD_ROWS, rowIndex)
        val p = nb.colOf(posCol[v])
        val q = if (negCol[v] >= 0) nb.colOf(negCol[v]) else -1
        var newHi = cur.hi
        var newLo = cur.lo
        for (maximize in booleanArrayOf(true, false)) {
            if (if (maximize) cur.hi != null else cur.lo != null) continue
            solves++
            val model = nb.model.withSingleColumnObjective(p, if (maximize) -1L else 1L, prevCol = -1, negCol = q)
            val result = try {
                newLpSolver(model, cancellation).solvePrimal(null)
            } catch (_: LpOverflowException) {
                null
            }
            if (result != null) {
                val probeCol = if (maximize || q < 0) p else q
                val bound = model.tightVariableBound(result, p, maximize, model.probeClampedHi[probeCol])
                if (maximize) newHi = bound else newLo = bound
            }
        }
        work[v] = probedBounds(cur, newLo, newHi)
    }
    return work
}

/**
 * [cur]'s sides updated with the probe results [newLo]/[newHi], dropping a probed bound that crosses the
 * variable's other side.
 *
 * A crossed pair states an empty domain, which is a refutation of the whole system — and a bound read off
 * an LP probe is not the evidence to assert one. Discarding it leaves the side open for the caller's clamp,
 * which is merely weaker, never wrong.
 */
private fun probedBounds(cur: OpenIntBounds, newLo: Long?, newHi: Long?): OpenIntBounds =
    if (newLo == null || newHi == null || newLo <= newHi) {
        OpenIntBounds(newLo, newHi)
    } else {
        OpenIntBounds(if (cur.lo != null) newLo else null, if (cur.hi != null) newHi else null)
    }

/** Add the LP column(s) for real variable [rv]: a single column when bounded below, else the same
 *  zero-shift split pair the integer columns use, recorded in [realPos]/[realNeg]. */
private fun addRealColumns(
    builder: LpBuilder,
    rv: Int,
    realLower: DoubleArray,
    realUpper: DoubleArray,
    realPos: HashMap<Int, Int>,
    realNeg: HashMap<Int, Int>,
) {
    val lo = realLower.getOrNull(rv)?.takeIf { it.isFinite() }
    val hi = realUpper.getOrNull(rv)?.takeIf { it.isFinite() }
    if (lo != null) {
        realPos[rv] = builder.addRealVar(lo, hi)
        return
    }
    val pos = if (hi != null && hi >= 0.0) builder.addRealVar(0.0, hi) else builder.addRealVar(0.0, null)
    val neg = builder.addRealVar(0.0, null)
    realPos[rv] = pos
    realNeg[rv] = neg
    if (hi != null && hi < 0.0) {
        builder.addRealRow(intArrayOf(pos, neg), doubleArrayOf(1.0, -1.0), Relation.LE, hi)
    }
}

/** Cap on the LP-solving pass's per-side solves: presolve-time OBBT runs with no deadline, and each
 *  re-solve refactorizes on a large model — sides past the cap stay open for the caller's clamp. */
private const val OBBT_MAX_SIDE_SOLVES = 128

/** Row cap for the full-model LP pass: above this the initial factorization alone is an
 *  uninterruptible multi-minute call, so the pass switches to per-probe neighborhood sub-models
 *  ([tightenByNeighborhoodProbes]) instead of paying it. */
private const val OBBT_MAX_LP_ROWS = 5_000

/** Row cap of each neighborhood probe on the oversized path: small enough that a probe's from-scratch
 *  factorization is effectively instant, large enough to span the local bounding structure a
 *  variable's bound usually rides on. */
private const val OBBT_NEIGHBORHOOD_ROWS = 512

/** Upper bound on the number of full propagation passes; bounds only ever tighten, so the loop reaches a
 *  fixpoint in finite steps, but a long dependency chain could take many passes — cap it and leave any
 *  residual to the LP pass. */
private const val FBBT_MAX_PASSES = 16

/** Rows between cancellation polls inside one FBBT pass — often enough to bound the overrun on a wide
 *  model, rare enough that the poll never shows up next to the O(width) row work. */
private const val FBBT_CANCEL_POLL = 64

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
 *
 * Mixed [realRows] propagate jointly through the same fixpoint in outward-rounded double intervals
 * ([propagateRealRow]), tightening the working real boxes [rLo]/[rUp] in place alongside the integer
 * bounds — so a chain like `r = 5/2, n ≤ r < n + 1` closes `n` exactly with no LP at all. Strict rows
 * propagate non-strict (a relaxation, sound).
 *
 * Where a tightening crosses a variable's opposite bound the rows imply an empty domain, so the system has
 * no solution: propagation stops and the result is [TightenedIntBounds.refuted]. That verdict is claimed
 * only for a pure-integer system, where every step was exact — the real rows' outward rounding is a margin
 * generous enough to keep a derived bound implied, but it is not the evidence to assert `unsat`, so a
 * crossing reached through one only collapses the pair and leaves the caller to search it.
 */
private fun fbbtTightenOpenIntBounds(
    bounds: Array<OpenIntBounds>,
    constraints: List<Linear>,
    cancellation: Cancellation,
    realRows: List<Linear> = emptyList(),
    rLo: DoubleArray = EmptyDoubleArray,
    rUp: DoubleArray = EmptyDoubleArray,
): TightenedIntBounds {
    val n = bounds.size
    val b = MutableIntBounds(n)
    for (i in 0 until n) {
        bounds[i].lo?.let { b.setLo(i, it) }
        bounds[i].hi?.let { b.setHi(i, it) }
    }
    // Only LE/EQ rows propagate — [Linear] canonicalises GE to LE, and NE is not an interval bound.
    val rows = constraints.filter { it.op == LinearOp.LE || it.op == LinearOp.EQ }
    val mixed = realRows.filter { it.op == LinearOp.LE || it.op == LinearOp.GE || it.op == LinearOp.EQ }

    // Only a wholly exact propagation may assert the refutation; see the doc comment.
    fun result() = TightenedIntBounds(
        Array(n) { OpenIntBounds(b.loOrNull(it), b.hiOrNull(it)) },
        refuted = b.crossed && mixed.isEmpty(),
    )
    if (rows.isEmpty() && mixed.isEmpty()) return result()

    var pass = 0
    var changed = true
    // A pass is O(Σ row width), so on a wide model a single one outruns the budget and a per-pass poll
    // never gets to fire; the stride below bounds the overrun by a row batch instead. Stopping
    // early keeps the tightenings made so far — sound, since a looser bound removes no solution.
    var polled = 0
    var spent = false
    fun budgetSpent(): Boolean {
        if (polled++ % FBBT_CANCEL_POLL != 0) return false
        spent = cancellation()
        return spent
    }
    while (changed && !spent && !b.crossed && pass < FBBT_MAX_PASSES) {
        changed = false
        pass++
        for (f in rows) {
            if (spent || b.crossed || budgetSpent()) break
            val coeffs = f.coeffs // materialising accessor: read once per row, never per direction
            if (propagateRow(coeffs, f.vars, f.bound, sign = 1L, b = b)) changed = true
            // An equality also bounds from below: `Σ aⱼ·xⱼ ≥ b`, i.e. `−Σ aⱼ·xⱼ ≤ −b`.
            if (f.op == LinearOp.EQ && propagateRow(coeffs, f.vars, f.bound, sign = -1L, b = b)) changed = true
        }
        for (f in mixed) {
            if (spent || b.crossed || budgetSpent()) break
            if (f.op != LinearOp.GE && propagateRealRow(f, sign = 1.0, b = b, rLo = rLo, rUp = rUp)) changed = true
            if (f.op != LinearOp.LE && propagateRealRow(f, sign = -1.0, b = b, rLo = rLo, rUp = rUp)) changed = true
        }
    }
    return result()
}

/** Working per-variable bounds for [fbbtTightenOpenIntBounds] in primitive arrays (no `Long?` boxing): a
 *  side is open (`±∞`) when its `*Open` flag is set, else its value is in `*Val`. */
private class MutableIntBounds(n: Int) {
    private val loVal = LongArray(n)
    private val hiVal = LongArray(n)
    private val loOpen = BooleanArray(n) { true }
    private val hiOpen = BooleanArray(n) { true }

    /**
     * Whether a bound was ever set past the variable's opposite side — the rows imply an empty domain.
     *
     * The pair itself is collapsed to the point where the two sides met rather than stored crossed, so a
     * consumer that reads the bounds regardless still gets a domain it can build a column over.
     */
    var crossed = false
        private set

    fun loOpen(i: Int) = loOpen[i]
    fun hiOpen(i: Int) = hiOpen[i]
    fun loVal(i: Int) = loVal[i]
    fun hiVal(i: Int) = hiVal[i]
    fun loOrNull(i: Int): Long? = if (loOpen[i]) null else loVal[i]
    fun hiOrNull(i: Int): Long? = if (hiOpen[i]) null else hiVal[i]

    fun setLo(i: Int, v: Long) {
        val crossing = !hiOpen[i] && v > hiVal[i]
        if (crossing) crossed = true
        loVal[i] = if (crossing) hiVal[i] else v
        loOpen[i] = false
    }

    fun setHi(i: Int, v: Long) {
        val crossing = !loOpen[i] && v < loVal[i]
        if (crossing) crossed = true
        hiVal[i] = if (crossing) loVal[i] else v
        hiOpen[i] = false
    }
}

/** Propagate the row `Σ (sign·coeffs(k))·vars(k) ≤ sign·bound` into [b], returning whether any bound
 *  tightened. Overflow in the exact activity arithmetic aborts this row (returns `false`). */
@Suppress("ReturnCount", "LoopWithTooManyJumpStatements")
private fun propagateRow(coeffs: LongArray, vars: IntArray, bound: Long, sign: Long, b: MutableIntBounds): Boolean =
    try {
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
            // A collapsed pair is tighter than the rows imply, so nothing may be derived off it.
            if (b.crossed) break
            val a = mulExact(sign, coeffs[idx])
            if (a == 0L) continue
            val v = vars[idx]
            val restMin = when {
                numInf == 0 -> subExact(minActivity, mulExact(a, if (a > 0L) b.loVal(v) else b.hiVal(v)))

                infIdx == idx -> minActivity

                // this term was the sole −∞; the rest is finite
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

/** Conservative per-term relative rounding bound for the real-row interval arithmetic; generous next to
 *  the `2⁻⁵³` unit roundoff so it also covers the `Long`→`Double` conversion of wide integer bounds. */
private const val REAL_EPS = 1e-15

/** Magnitude cap on a derived candidate bound: beyond this a double no longer resolves integers and the
 *  bound is worthless next to the search box anyway. */
private const val REAL_CAND_MAX = 4.0e18

/**
 * Propagate the mixed row `sign·(Σ aₖ·xₖ + Σ cⱼ·rⱼ) ≤ sign·bound` into the integer bounds [b] and the
 * real boxes [rLo]/[rUp], returning whether anything tightened. The double interval arithmetic rounds
 * **outward** — every candidate is widened by a conservative relative margin ([REAL_EPS]-scaled to the
 * accumulated magnitude) before it is committed, so a derived bound is always implied by the row: an
 * integer upper is the floor of an overestimate, an integer lower the ceil of an underestimate, and the
 * real boxes take the widened candidate itself. Mirrors [propagateRow]'s single-open-term rule.
 */
@Suppress("ReturnCount", "LoopWithTooManyJumpStatements", "CyclomaticComplexMethod", "LongMethod")
private fun propagateRealRow(
    f: Linear,
    sign: Double,
    b: MutableIntBounds,
    rLo: DoubleArray,
    rUp: DoubleArray,
): Boolean {
    val effBound = sign * f.realBound
    if (!effBound.isFinite()) return false
    val terms = f.vars.size + f.realVars.size
    // Minimum activity over the finite terms, its magnitude for the rounding margin, and the open count.
    var act = 0.0
    var mag = 0.0
    var numInf = 0
    var infInt = -1
    var infReal = -1
    for (k in f.vars.indices) {
        val a = sign * f.realIntCoeffs[k]
        if (a == 0.0) continue
        if (!a.isFinite()) return false
        val v = f.vars[k]
        val open = if (a > 0.0) b.loOpen(v) else b.hiOpen(v)
        if (open) {
            numInf++
            infInt = k
            if (numInf > 1) return false
        } else {
            val m = a * (if (a > 0.0) b.loVal(v) else b.hiVal(v)).toDouble()
            act += m
            mag += abs(m)
        }
    }
    for (j in f.realVars.indices) {
        val c = sign * f.realCoeffs[j]
        if (c == 0.0) continue
        if (!c.isFinite()) return false
        val rv = f.realVars[j]
        val m = c * (if (c > 0.0) rLo[rv] else rUp[rv])
        if (m == Double.NEGATIVE_INFINITY || m.isNaN()) {
            numInf++
            infReal = j
            if (numInf > 1) return false
        } else {
            act += m
            mag += abs(m)
        }
    }
    val margin = REAL_EPS * (mag + abs(effBound)) * (terms + 4)
    var changed = false
    for (k in f.vars.indices) {
        // A collapsed pair is tighter than the rows imply, so nothing may be derived off it.
        if (b.crossed) break
        val a = sign * f.realIntCoeffs[k]
        if (a == 0.0) continue
        val v = f.vars[k]
        val own = if (numInf == 0) {
            a * (if (a > 0.0) b.loVal(v) else b.hiVal(v)).toDouble()
        } else {
            if (infInt != k) continue // the sole −∞ is a different term ⇒ no bound for this one
            0.0
        }
        val cand = widen(effBound - (act - own), margin) / a
        if (!cand.isFinite() || abs(cand) >= REAL_CAND_MAX) continue
        if (a > 0.0) {
            val hi = floor(cand + REAL_EPS * abs(cand)).toLong()
            if (b.hiOpen(v) || hi < b.hiVal(v)) {
                b.setHi(v, hi)
                changed = true
            }
        } else {
            val lo = ceil(cand - REAL_EPS * abs(cand)).toLong()
            if (b.loOpen(v) || lo > b.loVal(v)) {
                b.setLo(v, lo)
                changed = true
            }
        }
    }
    for (j in f.realVars.indices) {
        if (b.crossed) break
        val c = sign * f.realCoeffs[j]
        if (c == 0.0) continue
        val rv = f.realVars[j]
        val own = if (numInf == 0) {
            c * (if (c > 0.0) rLo[rv] else rUp[rv])
        } else {
            if (infReal != j) continue
            0.0
        }
        val raw = widen(effBound - (act - own), margin) / c
        if (raw.isNaN()) continue
        if (c > 0.0) {
            val cand = raw + REAL_EPS * abs(raw)
            if (cand < rUp[rv]) {
                rUp[rv] = cand
                changed = true
            }
        } else {
            val cand = raw - REAL_EPS * abs(raw)
            if (cand > rLo[rv]) {
                rLo[rv] = cand
                changed = true
            }
        }
    }
    return changed
}

/** Widen the residual upward by [margin] plus its own relative slack — the outward rounding step that
 *  keeps every derived candidate an overestimate of the true residual. */
private fun widen(residual: Double, margin: Double): Double = residual + margin + REAL_EPS * abs(residual)

/** `⌊a / b⌋`, or null on the one non-representable case (`Long.MIN_VALUE / −1`). */
private fun floorDivSafe(a: Long, b: Long): Long? = if (a == Long.MIN_VALUE && b == -1L) null else a.floorDiv(b)

/** `⌈a / b⌉` = `⌊a / b⌋ + (a not divisible by b ? 1 : 0)`, or null on overflow. */
private fun ceilDivSafe(a: Long, b: Long): Long? {
    if (a == Long.MIN_VALUE && b == -1L) return null
    val q = a.floorDiv(b)
    return if (a % b != 0L) (if (q == Long.MAX_VALUE) null else q + 1L) else q
}
