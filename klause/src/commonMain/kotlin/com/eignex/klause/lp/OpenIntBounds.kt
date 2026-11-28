package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpCertificationObserver
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpNeighborhood
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.certifiedTightVariableBound
import com.eignex.klause.lp.engine.columnNeighborhood
import com.eignex.klause.lp.engine.rowIndex
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.arrays.emptyDoubleArray
import com.eignex.util.numbers.CheckedLongOverflowException
import com.eignex.util.numbers.addExact
import com.eignex.util.numbers.mulExact
import com.eignex.util.numbers.subExact
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

// A `null` side is genuinely open (±∞).
internal class OpenIntBounds(val lo: Long?, val hi: Long?)

// `bounds` never carries a crossed pair: a crossing is reported through `refuted` and the pair left at the
// singleton where the sides met, so every consumer still reads a valid domain.
internal class TightenedIntBounds(val bounds: Array<OpenIntBounds>, val refuted: Boolean = false)

/**
 * Optimization-based bound tightening: closes open integer sides from the LP relaxation's extremes. A side
 * the LP leaves unbounded stays `null` for the caller to clamp.
 *
 * Sound over genuine ±∞ because open sides enter the LP open (a probe-flagged free upper via
 * [LpBuilder.addFreeVar], an open lower as the split `x = x⁺ − x⁻`), so this must run before finite
 * domains are committed. An interval prefilter closes the sides a single row implies first. The LP is built
 * once and each remaining side re-solves it with a single ±1 cost, so the basis stays primal-feasible and
 * factorizations are reused. Each LP bound is derived over the prefiltered bounds only.
 *
 * Gleixner, Berthold, Müller and Weltge, "Three enhancements for optimization-based bound tightening",
 * J. Global Optimization 67 (2017), https://doi.org/10.1007/s10898-016-0450-4.
 */
internal fun tightenOpenIntBounds(
    bounds: Array<OpenIntBounds>,
    constraints: List<Linear>,
    cancellation: ExecutionControl = ExecutionControl.Never,
    realConstraints: List<Linear> = emptyList(),
    realLower: DoubleArray = emptyDoubleArray(),
    realUpper: DoubleArray = emptyDoubleArray(),
    observer: LpCertificationObserver? = null,
    context: LpSolveContext = LpSolveContext.Production,
): TightenedIntBounds {
    val n = bounds.size
    // The prefilter tightens these real boxes in place, outward-rounded; the LP pass starts from them.
    var maxReal = realLower.size
    for (f in realConstraints) for (rv in f.realVars) if (rv + 1 > maxReal) maxReal = rv + 1
    val rLo = DoubleArray(maxReal) { realLower.getOrNull(it) ?: Double.NEGATIVE_INFINITY }
    val rUp = DoubleArray(maxReal) { realUpper.getOrNull(it) ?: Double.POSITIVE_INFINITY }
    val prefiltered = fbbtTightenOpenIntBounds(bounds, constraints, cancellation, realConstraints, rLo, rUp)
    if (prefiltered.refuted) return prefiltered
    val work = prefiltered.bounds
    if (work.none { it.lo == null || it.hi == null }) return prefiltered
    if (cancellation()) return prefiltered

    val rx = openRelaxation(work, constraints, realConstraints, rLo, rUp)
    val builder = rx.builder
    val posCol = rx.posCol
    val negCol = rx.negCol
    val base = try {
        builder.build(Sense.MINIMIZE)
    } catch (_: CheckedLongOverflowException) {
        return prefiltered
    }

    // The full basis factorization is one uninterruptible call, superlinear in rows. A whole-row subset is
    // a pure relaxation, so neighborhood bounds stay valid on the full model.
    if (base.m > OBBT_MAX_LP_ROWS) {
        return TightenedIntBounds(
            tightenByNeighborhoodProbes(base, work, posCol, negCol, cancellation, observer, context),
        )
    }

    var solves = 0
    LpProbeSolver(base, cancellation, observer, context).use { probes ->
        for (v in 0 until n) {
            if (cancellation() || solves >= OBBT_MAX_SIDE_SOLVES) break
            val cur = work[v]
            if (cur.lo != null && cur.hi != null) continue
            var newHi = cur.hi
            var newLo = cur.lo
            for (maximize in booleanArrayOf(true, false)) {
                if (if (maximize) cur.hi != null else cur.lo != null) continue
                solves++
                cancellation.charge(LP_CERTIFY_WORK_WEIGHT * certifyUnits(base))
                val probe = probes.probe(posCol[v], maximize, negCol[v])
                if (probe != null) {
                    val (model, result) = probe
                    // Minimizing an open-below variable probes the growth of x⁻.
                    val probeCol = if (maximize || negCol[v] < 0) posCol[v] else negCol[v]
                    val bound = model.certifiedTightVariableBound(
                        result,
                        v,
                        maximize,
                        base.probeClampedHi[probeCol],
                        observer,
                        context.certificationPolicy,
                    )
                    if (maximize) newHi = bound else newLo = bound
                }
            }
            work[v] = probedBounds(cur, newLo, newHi)
        }
    }
    return TightenedIntBounds(work)
}

/** Bounds transfer soundly because a neighborhood drops whole rows only (see [LpNeighborhood]). */
private fun tightenByNeighborhoodProbes(
    base: LpModel,
    work: Array<OpenIntBounds>,
    posCol: IntArray,
    negCol: IntArray,
    cancellation: ExecutionControl,
    observer: LpCertificationObserver?,
    context: LpSolveContext,
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
        LpProbeSolver(nb.model, cancellation, observer, context).use { probes ->
            for (maximize in booleanArrayOf(true, false)) {
                if (if (maximize) cur.hi != null else cur.lo != null) continue
                solves++
                cancellation.charge(LP_CERTIFY_WORK_WEIGHT * certifyUnits(nb.model))
                val probe = probes.probe(p, maximize, q)
                if (probe != null) {
                    val (model, result) = probe
                    val probeCol = if (maximize || q < 0) p else q
                    val bound = model.certifiedTightVariableBound(
                        result,
                        p,
                        maximize,
                        nb.model.probeClampedHi[probeCol],
                        observer,
                        context.certificationPolicy,
                    )
                    if (maximize) newHi = bound else newLo = bound
                }
            }
        }
        work[v] = probedBounds(cur, newLo, newHi)
    }
    return work
}

// A crossed pair would assert a refutation, and an LP probe is not the evidence for one, so a crossing
// probe bound is dropped and its side left open.
private fun probedBounds(cur: OpenIntBounds, newLo: Long?, newHi: Long?): OpenIntBounds =
    if (newLo == null || newHi == null || newLo <= newHi) {
        OpenIntBounds(newLo, newHi)
    } else {
        OpenIntBounds(if (cur.lo != null) newLo else null, if (cur.hi != null) newHi else null)
    }

// Presolve-time OBBT may run with no deadline, and each re-solve refactorizes on a large model.
private const val OBBT_MAX_SIDE_SOLVES = 128

// Above this the initial factorization alone is an uninterruptible multi-minute call.
private const val OBBT_MAX_LP_ROWS = 5_000

// Small enough for an instant factorization, large enough to span a variable's local bounding structure.
private const val OBBT_NEIGHBORHOOD_ROWS = 512

// A long dependency chain could take many passes; the residual is left to the LP pass.
private const val FBBT_MAX_PASSES = 16

// Bounds the overrun on a wide model without the poll showing up next to the row work.
private const val FBBT_CANCEL_POLL = 64

// Feasibility-based bound tightening: interval propagation to a fixpoint, the prefilter that spares the LP
// pass every side a single row implies. Mixed real rows propagate in outward-rounded double intervals,
// tightening `rLo`/`rUp` in place. A crossing is claimed as a refutation only for a pure-integer system:
// outward rounding keeps a derived bound implied but is not the evidence to assert `unsat`.
private fun fbbtTightenOpenIntBounds(
    bounds: Array<OpenIntBounds>,
    constraints: List<Linear>,
    cancellation: ExecutionControl,
    realRows: List<Linear> = emptyList(),
    rLo: DoubleArray = emptyDoubleArray(),
    rUp: DoubleArray = emptyDoubleArray(),
): TightenedIntBounds {
    val n = bounds.size
    val b = MutableIntBounds(n)
    for (i in 0 until n) {
        bounds[i].lo?.let { b.setLo(i, it) }
        bounds[i].hi?.let { b.setHi(i, it) }
    }
    // [Linear] canonicalises GE to LE, and NE is not an interval bound.
    val rows = constraints.filter { it.op == LinearOp.LE || it.op == LinearOp.EQ }
    val mixed = realRows.filter { it.op == LinearOp.LE || it.op == LinearOp.GE || it.op == LinearOp.EQ }

    // Only a wholly exact propagation may assert the refutation.
    fun result() = TightenedIntBounds(
        Array(n) { OpenIntBounds(b.loOrNull(it), b.hiOrNull(it)) },
        refuted = b.crossed && mixed.isEmpty(),
    )
    if (rows.isEmpty() && mixed.isEmpty()) return result()

    var pass = 0
    var changed = true
    // One pass can outrun the budget on a wide model, so poll per row batch. Stopping early keeps the
    // tightenings made so far, which is sound.
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
            cancellation.charge(FBBT_WORK_WEIGHT * (1L + f.vars.size))
            val row = f.integerConstants ?: continue
            val coeffs = row.coeffs // materialising accessor: read once per row, never per direction
            if (propagateRow(coeffs, f.vars, row.bound, sign = 1L, b = b)) changed = true
            if (f.op == LinearOp.EQ && propagateRow(coeffs, f.vars, row.bound, sign = -1L, b = b)) changed = true
        }
        for (f in mixed) {
            if (spent || b.crossed || budgetSpent()) break
            cancellation.charge(FBBT_WORK_WEIGHT * (1L + f.vars.size + f.realVars.size))
            if (f.op != LinearOp.GE && propagateRealRow(f, sign = 1.0, b = b, rLo = rLo, rUp = rUp)) changed = true
            if (f.op != LinearOp.LE && propagateRealRow(f, sign = -1.0, b = b, rLo = rLo, rUp = rUp)) changed = true
        }
    }
    return result()
}

// A setter that moves a side past the opposite one records [crossed] instead of storing a crossed pair.
internal interface IntervalBounds {
    val crossed: Boolean
    fun loOpen(i: Int): Boolean
    fun hiOpen(i: Int): Boolean
    fun loVal(i: Int): Long
    fun hiVal(i: Int): Long
    fun setLo(i: Int, v: Long)
    fun setHi(i: Int, v: Long)
}

private class MutableIntBounds(n: Int) : IntervalBounds {
    private val loVal = LongArray(n)
    private val hiVal = LongArray(n)
    private val loOpen = BooleanArray(n) { true }
    private val hiOpen = BooleanArray(n) { true }

    // A crossed pair is collapsed to the point where the sides met, so a consumer still gets a domain it
    // can build a column over.
    private var isCrossed = false
    override val crossed: Boolean get() = isCrossed

    override fun loOpen(i: Int) = loOpen[i]
    override fun hiOpen(i: Int) = hiOpen[i]
    override fun loVal(i: Int) = loVal[i]
    override fun hiVal(i: Int) = hiVal[i]
    fun loOrNull(i: Int): Long? = if (loOpen[i]) null else loVal[i]
    fun hiOrNull(i: Int): Long? = if (hiOpen[i]) null else hiVal[i]

    override fun setLo(i: Int, v: Long) {
        val crossing = !hiOpen[i] && v > hiVal[i]
        if (crossing) isCrossed = true
        loVal[i] = if (crossing) hiVal[i] else v
        loOpen[i] = false
    }

    override fun setHi(i: Int, v: Long) {
        val crossing = !loOpen[i] && v < loVal[i]
        if (crossing) isCrossed = true
        hiVal[i] = if (crossing) loVal[i] else v
        hiOpen[i] = false
    }
}

// Propagates `Σ (sign·coeffs(k))·vars(k) ≤ sign·bound`; overflow aborts the row.
@Suppress("ReturnCount", "LoopWithTooManyJumpStatements")
internal fun propagateRow(
    coeffs: LongArray,
    vars: IntArray,
    bound: Long,
    sign: Long,
    b: IntervalBounds,
    visit: (() -> Boolean)? = null,
): Boolean =
    try {
        val effBound = mulExact(sign, bound)
        var minActivity = 0L
        var numInf = 0
        var infIdx = -1
        for (idx in coeffs.indices) {
            if (visit != null && !visit()) return false
            val a = mulExact(sign, coeffs[idx])
            if (a == 0L) continue
            val v = vars[idx]
            if (if (a > 0L) b.loOpen(v) else b.hiOpen(v)) {
                numInf++
                infIdx = idx
                if (numInf > 1) return false
            } else {
                minActivity = addExact(minActivity, mulExact(a, if (a > 0L) b.loVal(v) else b.hiVal(v)))
            }
        }
        var changed = false
        for (idx in coeffs.indices) {
            if (visit != null && !visit()) return false
            // A collapsed pair is tighter than the rows imply, so nothing may be derived off it.
            if (b.crossed) break
            val a = mulExact(sign, coeffs[idx])
            if (a == 0L) continue
            val v = vars[idx]
            val restMin = when {
                numInf == 0 -> subExact(minActivity, mulExact(a, if (a > 0L) b.loVal(v) else b.hiVal(v)))

                infIdx == idx -> minActivity

                else -> continue
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
    } catch (_: CheckedLongOverflowException) {
        false
    }

// An unbounded term, or activity past 64 bits, leaves the row possible.
@Suppress("ReturnCount")
internal fun rowRefuted(
    coeffs: LongArray,
    vars: IntArray,
    bound: Long,
    sign: Long,
    b: IntervalBounds,
    visit: (() -> Boolean)? = null,
): Boolean = try {
    var minActivity = 0L
    for (idx in coeffs.indices) {
        if (visit != null && !visit()) return false
        val a = mulExact(sign, coeffs[idx])
        if (a == 0L) continue
        val v = vars[idx]
        if (if (a > 0L) b.loOpen(v) else b.hiOpen(v)) return false
        minActivity = addExact(minActivity, mulExact(a, if (a > 0L) b.loVal(v) else b.hiVal(v)))
    }
    minActivity > mulExact(sign, bound)
} catch (_: CheckedLongOverflowException) {
    false
}

// Generous next to the `2⁻⁵³` unit roundoff so it also covers the `Long`→`Double` conversion of wide bounds.
private const val REAL_EPS = 1e-15

// Beyond this a double no longer resolves integers.
private const val REAL_CAND_MAX = 4.0e18

// Every candidate is widened outward by a margin scaled to the accumulated magnitude before it is
// committed, so a derived bound is always implied by the row.
@Suppress("ReturnCount", "LoopWithTooManyJumpStatements", "CyclomaticComplexMethod", "LongMethod")
private fun propagateRealRow(
    f: Linear,
    sign: Double,
    b: MutableIntBounds,
    rLo: DoubleArray,
    rUp: DoubleArray,
): Boolean {
    val row = f.realConstants ?: return false
    val effBound = sign * row.bound
    if (!effBound.isFinite()) return false
    val terms = f.vars.size + f.realVars.size
    var act = 0.0
    var mag = 0.0
    var numInf = 0
    var infInt = -1
    var infReal = -1
    for (k in f.vars.indices) {
        val a = sign * row.intCoefficients.at(k)
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
        val c = sign * row.realCoefficients.at(j)
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
        if (b.crossed) break
        val a = sign * row.intCoefficients.at(k)
        if (a == 0.0) continue
        val v = f.vars[k]
        val own = if (numInf == 0) {
            a * (if (a > 0.0) b.loVal(v) else b.hiVal(v)).toDouble()
        } else {
            if (infInt != k) continue
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
        val c = sign * row.realCoefficients.at(j)
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

private fun widen(residual: Double, margin: Double): Double = residual + margin + REAL_EPS * abs(residual)

private fun floorDivSafe(a: Long, b: Long): Long? = if (a == Long.MIN_VALUE && b == -1L) null else a.floorDiv(b)

private fun ceilDivSafe(a: Long, b: Long): Long? {
    if (a == Long.MIN_VALUE && b == -1L) return null
    val q = a.floorDiv(b)
    return if (a % b != 0L) (if (q == Long.MAX_VALUE) null else q + 1L) else q
}

internal fun certifyUnits(model: LpModel): Long = model.csc.colPtr[model.n].toLong() + model.m + model.n

// Work units per entry of an exactly certified probe, relative to one simplex op.
internal const val LP_CERTIFY_WORK_WEIGHT = 183L

// Work units per term propagated by the interval prefilter, relative to one simplex op.
private const val FBBT_WORK_WEIGHT = 10L
