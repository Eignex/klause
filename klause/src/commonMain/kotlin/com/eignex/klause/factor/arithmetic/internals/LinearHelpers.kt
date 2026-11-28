package com.eignex.klause.factor.arithmetic.internals

import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.propagation.LAZY_PAYLOAD
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.boundAt
import com.eignex.klause.propagation.boundEstablishment
import com.eignex.klause.propagation.boundEstablishmentLevel
import com.eignex.klause.propagation.lazyReasonSlots
import com.eignex.util.arrays.argsortByIntKey
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet
import com.eignex.util.numbers.Int128
import kotlin.math.abs

internal fun initLinearSum(state: LocalSearchState, factorId: Int, coeffs: LongArray, vars: IntArray) {
    var sum = 0L
    for (i in vars.indices) sum += coeffs[i] * state.assignment.intValue(vars[i])
    state.longPayload[factorId] = sum
}

// For propagators that prune interior values. Cites bounds tighter than the root and `[v == value]` for every
// excluded root value, so the reason describes the filtered domains exactly rather than only their bounds. When
// any variable sits above the root, only those are cited. Null when nothing is tighter than the root.
internal fun collectHoleAndBoundAntecedents(
    state: PropagationState,
    vars: IntArray,
    extraLit: Int = 0,
    includeExtraLit: Boolean = false,
): IntArray? {
    val seen = IntHashSet(vars.size * 2)
    val out = IntArrayList()
    if (includeExtraLit) {
        out.add(extraLit)
        seen.add(extraLit)
    }
    var anyAboveRoot = false
    for (v in vars) {
        state.work++
        if (state.intLevel[v] > 0) {
            anyAboveRoot = true
            break
        }
    }
    for (v in vars) {
        state.work++
        if (anyAboveRoot && state.intLevel[v] <= 0) continue
        val d = state.intDomains[v]
        val orig = state.rootDomains[v]
        if (d.min > orig.min) {
            val lit = Lit.make(state.atomVarGe(v, d.min), false)
            if (seen.add(lit)) out.add(lit)
        }
        if (d.max < orig.max) {
            val lit = Lit.make(state.atomVarLe(v, d.max), false)
            if (seen.add(lit)) out.add(lit)
        }
        val lo = maxOf(d.min, orig.min)
        val hi = minOf(d.max, orig.max)
        // Enumerate the smaller side: walking the holes of a sparse root domain is O(span). Both paths visit
        // the same values in ascending order.
        val members = orig.spanOrNull(d.holeCount)
        if (members != null) {
            members.forEach { value ->
                if (value in lo..hi && value !in d) {
                    val lit = Lit.make(state.atomVarEq(v, value), true)
                    if (seen.add(lit)) out.add(lit)
                }
            }
        } else {
            d.forEachHoleInRange(lo, hi) { value ->
                if (value in orig) {
                    val lit = Lit.make(state.atomVarEq(v, value), true)
                    if (seen.add(lit)) out.add(lit)
                }
            }
        }
    }
    if (out.size == 0) return null
    return out.toIntArray()
}

// A linear deduction is driven by one sum extreme, so only the bounds on that side are cited, and only when
// tighter than the root. Sharper than [collectLinearTightenAntecedents], so learned clauses generalise further.
internal fun collectLinearDirAntecedents(
    state: PropagationState,
    coeffs: LongArray,
    vars: IntArray,
    excludeIdx: Int,
    extraLit: Int,
    includeExtraLit: Boolean = false,
    useLo: Boolean,
): IntArray? {
    val seen = IntHashSet(vars.size * 2)
    val out = IntArrayList()
    if (includeExtraLit) {
        out.add(extraLit)
        seen.add(extraLit)
    }
    var anyAboveRoot = false
    for (j in vars.indices) {
        state.work++
        if (j == excludeIdx) continue
        if (state.intLevel[vars[j]] > 0) {
            anyAboveRoot = true
            break
        }
    }
    for (j in vars.indices) {
        state.work++
        if (j == excludeIdx) continue
        val c = coeffs[j]
        if (c == 0L) continue
        val v = vars[j]
        if (anyAboveRoot && state.intLevel[v] <= 0) continue
        val citeMin = if (useLo) c > 0 else c < 0
        val d = state.intDomains[v]
        val orig = state.rootDomains[v]
        if (citeMin) {
            if (d.min > orig.min) {
                val lit = Lit.make(state.atomVarGe(v, d.min), false)
                if (seen.add(lit)) out.add(lit)
            }
        } else {
            if (d.max < orig.max) {
                val lit = Lit.make(state.atomVarLe(v, d.max), false)
                if (seen.add(lit)) out.add(lit)
            }
        }
    }
    if (out.size == 0) return null
    return out.toIntArray()
}

// [collectLinearDirAntecedents] with each bound weakened as far as the deduction allows. [slack] is how far the
// driving side may loosen before the deduction stops following; it is spent latest level first, so one conflict
// refutes every value it rules out instead of one value per conflict. A bound loosened to its root value drops out.
// Below the conflict level any weaker atom that still holds serves; at it, a literal may be resolved, so only the
// decision's bound loosens.
internal fun collectLinearLiftedAntecedents(
    state: PropagationState,
    coeffs: LongArray,
    vars: IntArray,
    useLo: Boolean,
    slack: Long,
    excludeIdx: Int = -1,
    extraLit: Int = 0,
    includeExtraLit: Boolean = false,
): IntArray? {
    val n = vars.size
    val bound = LongArray(n)
    val level = IntArray(n)
    val lift = BooleanArray(n)
    val aboveRoot = BooleanArray(n)
    for (j in 0 until n) {
        state.work++
        val v = vars[j]
        val citeMin = if (useLo) coeffs[j] > 0 else coeffs[j] < 0
        val d = state.intDomains[v]
        bound[j] = if (citeMin) d.min else d.max
        level[j] = if (citeMin) state.intMinLevel[v] else state.intMaxLevel[v]
        aboveRoot[j] = state.intLevel[v] > 0
        lift[j] = liftable(state, v, citeMin, level[j])
    }
    return liftedAntecedents(
        state,
        coeffs,
        vars,
        useLo,
        slack,
        excludeIdx,
        extraLit,
        includeExtraLit,
        bound,
        level,
        lift,
        aboveRoot,
    )
}

// The [collectLinearLiftedAntecedents] reason for a deduction recorded by [linearLazyReason], rebuilt from the
// bounds as they stood at undo-log position [atTrail] and level [atLevel].
internal fun explainLinearBound(
    state: PropagationState,
    coeffs: LongArray,
    vars: IntArray,
    payload: IntArray,
    atTrail: Int,
    atLevel: Int,
): IntArray? {
    val useLo = payload[1] != 0
    val excludeIdx = payload[0]
    val n = vars.size
    val bound = LongArray(n)
    val level = IntArray(n)
    val lift = BooleanArray(n)
    val aboveRoot = BooleanArray(n)
    val decisions = state.levelToDecisionVar
    val numBools = state.problem.numBoolVars
    for (j in 0 until n) {
        state.work++
        if (j == excludeIdx || coeffs[j] == 0L) continue
        val v = vars[j]
        val citeMin = if (useLo) coeffs[j] > 0 else coeffs[j] < 0
        val root = state.rootDomains[v]
        val b = state.boundAt(v, citeMin, atTrail)
        bound[j] = b
        if (if (citeMin) b <= root.min else b >= root.max) continue
        val l = state.boundEstablishmentLevel(v, b, citeMin) ?: 0
        level[j] = l
        aboveRoot[j] = l > 0
        // As [liftable], against the deduction's level: below it any weaker atom serves; at it, only a decision.
        // Only a level decided on this very variable can be one, so only then is the move's reason built.
        val decided = l in 1..decisions.size && decisions[l - 1] == numBools + v &&
            state.boundEstablishment(v, b, citeMin)?.reason == null
        lift[j] = l > 0 && (l < atLevel || decided)
    }
    return liftedAntecedents(
        state,
        coeffs,
        vars,
        useLo,
        slack = (payload[2].toLong() shl 32) or (payload[3].toLong() and 0xFFFFFFFFL),
        excludeIdx = excludeIdx,
        extraLit = payload[4],
        includeExtraLit = payload[5] != 0,
        bound = bound,
        level = level,
        lift = lift,
        aboveRoot = aboveRoot,
    )
}

@Suppress("LongParameterList")
internal fun linearLazyReason(
    state: PropagationState,
    factorId: Int,
    excludeIdx: Int,
    useLo: Boolean,
    slack: Long,
    extraLit: Int,
    includeExtraLit: Boolean,
): IntArray {
    val out = state.lazyReasonSlots(LINEAR_PAYLOAD_SIZE, factorId)
    out[LAZY_PAYLOAD] = excludeIdx
    out[LAZY_PAYLOAD + 1] = if (useLo) 1 else 0
    out[LAZY_PAYLOAD + 2] = (slack ushr 32).toInt()
    out[LAZY_PAYLOAD + 3] = slack.toInt()
    out[LAZY_PAYLOAD + 4] = extraLit
    out[LAZY_PAYLOAD + 5] = if (includeExtraLit) 1 else 0
    return out
}

private const val LINEAR_PAYLOAD_SIZE = 6

// Per term: its cited [bound], the [level] that established it, whether it may be cited weaker ([lift]), and
// whether the variable sits above the root ([aboveRoot]).
@Suppress("LongParameterList")
private fun liftedAntecedents(
    state: PropagationState,
    coeffs: LongArray,
    vars: IntArray,
    useLo: Boolean,
    slack: Long,
    excludeIdx: Int,
    extraLit: Int,
    includeExtraLit: Boolean,
    bound: LongArray,
    level: IntArray,
    lift: BooleanArray,
    aboveRoot: BooleanArray,
): IntArray? {
    val cited = citedTerms(state, coeffs, vars, useLo, excludeIdx, bound, aboveRoot)
    // Latest level first, since only those bounds could be resolved. Without slack the order is moot.
    val order = if (slack > 0) argsortByIntKey(cited.size) { -level[cited[it]] } else IntArray(cited.size) { it }
    val seen = IntHashSet(order.size * 2)
    val out = IntArrayList()
    if (includeExtraLit) {
        out.add(extraLit)
        seen.add(extraLit)
    }
    var remaining = slack
    for (k in order) {
        state.work++
        val j = cited[k]
        val c = coeffs[j]
        val v = vars[j]
        val citeMin = if (useLo) c > 0 else c < 0
        val b = bound[j]
        val orig = state.rootDomains[v]
        val room = if (citeMin) b - orig.min else orig.max - b
        val step = if (remaining > 0 && lift[j]) minOf(remaining / abs(c), room) else 0L
        remaining -= step * abs(c)
        if (step == room) continue
        val lit = boundLiteralLiftedBy(state, v, citeMin, b, step)
        if (seen.add(lit)) out.add(lit)
    }
    if (out.size == 0) return null
    return out.toIntArray()
}

// The terms a reason cites: every one off its root bound on the cited side, and only those above the root when any
// is. Its own method, as is [boundLiteralLiftedBy], so the lifting loop compiles small.
@Suppress("LongParameterList")
private fun citedTerms(
    state: PropagationState,
    coeffs: LongArray,
    vars: IntArray,
    useLo: Boolean,
    excludeIdx: Int,
    bound: LongArray,
    aboveRoot: BooleanArray,
): IntArrayList {
    var anyAboveRoot = false
    for (j in vars.indices) {
        state.work++
        if (j != excludeIdx && aboveRoot[j]) {
            anyAboveRoot = true
            break
        }
    }
    val cited = IntArrayList()
    for (j in vars.indices) {
        state.work++
        if (j == excludeIdx) continue
        val c = coeffs[j]
        if (c == 0L) continue
        val v = vars[j]
        if (anyAboveRoot && !aboveRoot[j]) continue
        val citeMin = if (useLo) c > 0 else c < 0
        val orig = state.rootDomains[v]
        if (if (citeMin) bound[j] > orig.min else bound[j] < orig.max) cited.add(j)
    }
    return cited
}

private fun boundLiteralLiftedBy(state: PropagationState, v: Int, citeMin: Boolean, b: Long, step: Long): Int =
    if (citeMin) {
        Lit.make(state.atomVarGe(v, b - step), false)
    } else {
        Lit.make(state.atomVarLe(v, b + step), false)
    }

private fun liftable(state: PropagationState, v: Int, citeMin: Boolean, level: Int): Boolean {
    if (level <= 0) return false
    if (level < state.currentLevel) return true
    val reason = if (citeMin) state.intMinReason[v] else state.intMaxReason[v]
    val decisions = state.levelToDecisionVar
    return reason < 0 && decisions.size == level && decisions[level - 1] == state.problem.numBoolVars + v
}

internal fun collectLinearTightenAntecedents(
    state: PropagationState,
    vars: IntArray,
    excludeIdx: Int,
    extraLit: Int,
    includeExtraLit: Boolean = false,
): IntArray? {
    val seen = IntHashSet(vars.size * 2)
    val out = IntArrayList()
    if (includeExtraLit) {
        out.add(extraLit)
        seen.add(extraLit)
    }
    var anyAboveRoot = false
    for (j in vars.indices) {
        state.work++
        if (j == excludeIdx) continue
        if (state.intLevel[vars[j]] > 0) {
            anyAboveRoot = true
            break
        }
    }
    for (j in vars.indices) {
        state.work++
        if (j == excludeIdx) continue
        val v = vars[j]
        if (anyAboveRoot && state.intLevel[v] <= 0) continue
        val d = state.intDomains[v]
        val orig = state.rootDomains[v]
        if (d.min > orig.min) {
            val lit = Lit.make(state.atomVarGe(v, d.min), false)
            if (seen.add(lit)) out.add(lit)
        }
        if (d.max < orig.max) {
            val lit = Lit.make(state.atomVarLe(v, d.max), false)
            if (seen.add(lit)) out.add(lit)
        }
    }
    if (out.size == 0) return null
    return out.toIntArray()
}

// Detects definite violation only, so an overflowing state is still rejected exactly; tightening resumes once
// the domains narrow back into 64 bits.
private fun linearFeasible128(
    state: PropagationState,
    coeffs: LongArray,
    vars: IntArray,
    op: LinearOp,
    bound: Long,
): Boolean {
    val lo = Int128()
    val hi = Int128()
    for (i in vars.indices) {
        state.work++
        val d = state.intDomains[vars[i]]
        val c = coeffs[i]
        if (c >= 0L) {
            lo.addProduct(c, d.min)
            hi.addProduct(c, d.max)
        } else {
            lo.addProduct(c, d.max)
            hi.addProduct(c, d.min)
        }
    }
    lo.addProduct(-1L, bound)
    hi.addProduct(-1L, bound)
    if (lo.overflow || hi.overflow) return true
    val signLo = int128Sign(lo)
    val signHi = int128Sign(hi)
    return when (op) {
        LinearOp.LE -> signLo <= 0
        LinearOp.GE -> signHi >= 0
        LinearOp.EQ -> signLo <= 0 && signHi >= 0
        LinearOp.NE -> !(signLo == 0 && signHi == 0)
    }
}

private fun int128Sign(v: Int128): Int = when {
    v.hi < 0L -> -1
    v.hi == 0L && v.lo == 0L -> 0
    else -> 1
}

// How far the other terms' cited side may loosen while `c·x ⟨op⟩ s` still yields the bound [t] on x, in sum
// units: `c·t + |c| − 1 + offset`, where [offset] is `−s` for a `≤` row and `s` for a `≥` row (with [c] negated).
// Zero on overflow, which lifts nothing.
private fun liftBudget(c: Long, t: Long, offset: Long): Long {
    if (mulOverflows(c, t)) return 0L
    val ct = c * t
    val ac = abs(c) - 1
    if (addOverflows(ct, ac)) return 0L
    val head = ct + ac
    if (addOverflows(head, offset)) return 0L
    return (head + offset).coerceAtLeast(0L)
}

// The magnitude check spares the propagation hot loop a division on ordinary domains.
private fun mulOverflows(a: Long, b: Long): Boolean {
    if (((a xor (a shr 63)) or (b xor (b shr 63))) ushr 31 == 0L) return false
    if (a == 0L || b == 0L) return false
    if (b == -1L) return a == Long.MIN_VALUE
    return (a * b) / b != a
}

private fun addOverflows(a: Long, b: Long): Boolean = ((a xor (a + b)) and (b xor (a + b))) < 0L

private fun subOverflows(a: Long, b: Long): Boolean = ((a xor b) and (a xor (a - b))) < 0L

// Wrapped arithmetic must never strengthen a bound or declare a conflict, so overflow only weakens propagation:
// a wrapped product reduces the round to the exact 128-bit check, and a wrapped side skips its own test and
// tightenings. The skipped pruning re-runs on narrower domains.
internal fun propagateLinearBounds(
    state: PropagationState,
    coeffs: LongArray,
    vars: IntArray,
    op: LinearOp,
    bound: Long,
    extraLit: Int = 0,
    includeExtraLit: Boolean = false,
    factorId: Int = -1,
): Boolean {
    val n = vars.size
    var sumLo = 0L
    var sumHi = 0L
    var loOverflow = false
    var hiOverflow = false
    var maxTermWidth = 0L
    var termWidthOverflow = false
    for (i in 0 until n) {
        state.work++
        val d = state.intDomains[vars[i]]
        val c = coeffs[i]
        if (mulOverflows(c, d.min) || mulOverflows(c, d.max)) {
            return linearFeasible128(state, coeffs, vars, op, bound)
        }
        val a = c * d.min
        val b = c * d.max
        val lo = if (a <= b) a else b
        val hi = if (a <= b) b else a
        if (!termWidthOverflow) {
            termWidthOverflow = subOverflows(hi, lo)
            if (!termWidthOverflow && hi - lo > maxTermWidth) maxTermWidth = hi - lo
        }
        loOverflow = loOverflow || addOverflows(sumLo, lo)
        hiOverflow = hiOverflow || addOverflows(sumHi, hi)
        sumLo += lo
        sumHi += hi
    }
    if (loOverflow || hiOverflow) {
        // Re-check exactly so a violated row is still rejected, in particular at a fully pinned leaf.
        if (!linearFeasible128(state, coeffs, vars, op, bound)) return false
    }
    when (op) {
        LinearOp.LE -> if (!loOverflow && sumLo > bound) return false
        LinearOp.GE -> if (!hiOverflow && sumHi < bound) return false
        LinearOp.EQ -> if ((!loOverflow && sumLo > bound) || (!hiOverflow && sumHi < bound)) return false
        LinearOp.NE -> if (!loOverflow && !hiOverflow && sumLo == bound && sumHi == bound) return false
    }
    // Conflict analysis never reads a root reason. Building one is costly: on a wide domain a slow-converging
    // bound narrows about one value per round, each citing a fresh order atom, O(span²) overall.
    val rootFact = state.currentLevel == 0
    if (op == LinearOp.NE) {
        if (loOverflow || hiOverflow) return true
        return excludeNotEqualValues(state, coeffs, vars, bound, sumLo, sumHi, extraLit, includeExtraLit, rootFact)
    }
    if (!loOverflow && !hiOverflow && !termWidthOverflow) {
        val loSlackCoversTerms = op == LinearOp.GE ||
            (!subOverflows(bound, sumLo) && bound - sumLo >= maxTermWidth)
        val hiSlackCoversTerms = op == LinearOp.LE ||
            (!subOverflows(sumHi, bound) && sumHi - bound >= maxTermWidth)
        // Every term can span its full activity range without tightening. Retain the pass's visit debits.
        if (loSlackCoversTerms && hiSlackCoversTerms) {
            for (i in vars.indices) state.work++
            return true
        }
    }
    return tightenLinearTerms(
        state,
        coeffs,
        vars,
        op,
        bound,
        sumLo,
        sumHi,
        loOverflow,
        hiOverflow,
        extraLit,
        includeExtraLit,
        factorId,
    )
}

// The `≠` pass and the bound-tightening pass are their own methods so each compiles apart from the summing loop: in one
// body every loop entered on-stack recompiled the whole of it.
@Suppress("LongParameterList")
private fun excludeNotEqualValues(
    state: PropagationState,
    coeffs: LongArray,
    vars: IntArray,
    bound: Long,
    sumLo: Long,
    sumHi: Long,
    extraLit: Int,
    includeExtraLit: Boolean,
    rootFact: Boolean,
): Boolean {
    for (i in vars.indices) {
        state.work++
        val c = coeffs[i]
        if (c == 0L) continue
        val v = vars[i]
        val d = state.intDomains[v]
        val a = c * d.min
        val b = c * d.max
        val loTerm = if (a <= b) a else b
        val hiTerm = if (a <= b) b else a
        if (subOverflows(sumLo, loTerm) || subOverflows(sumHi, hiTerm)) continue
        val otherLo = sumLo - loTerm
        val otherHi = sumHi - hiTerm
        if (otherLo != otherHi) continue
        if (subOverflows(bound, otherLo)) continue
        val rhs = bound - otherLo
        val forbidden = integralQuotientOrNull(rhs, c) ?: continue
        val ant = if (rootFact) {
            null
        } else {
            collectLinearTightenAntecedents(state, vars, i, extraLit, includeExtraLit = includeExtraLit)
        }
        if (!state.excludeIntValue(v, forbidden, ant)) return false
    }
    return true
}

@Suppress("LongParameterList")
private fun tightenLinearTerms(
    state: PropagationState,
    coeffs: LongArray,
    vars: IntArray,
    op: LinearOp,
    bound: Long,
    sumLo: Long,
    sumHi: Long,
    loOverflow: Boolean,
    hiOverflow: Boolean,
    extraLit: Int,
    includeExtraLit: Boolean,
    factorId: Int,
): Boolean {
    val rootFact = state.currentLevel == 0
    // A sum's reason costs O(arity) to build, and lifting it materializes order atoms that every later bound move
    // must wake; most are never read, so it is recorded lazily and built only if conflict analysis reaches it,
    // from the bounds the undo log recovers.
    val lazy = state.undoLogging && factorId >= 0
    fun reason(i: Int, useLo: Boolean, budget: Long): IntArray? = when {
        rootFact -> null

        lazy -> linearLazyReason(state, factorId, i, useLo, budget, extraLit, includeExtraLit)

        else -> collectLinearLiftedAntecedents(
            state,
            coeffs,
            vars,
            useLo = useLo,
            slack = budget,
            excludeIdx = i,
            extraLit = extraLit,
            includeExtraLit = includeExtraLit,
        )
    }
    fun loReason(i: Int, budget: Long) = reason(i, useLo = true, budget)
    fun hiReason(i: Int, budget: Long) = reason(i, useLo = false, budget)
    for (i in vars.indices) {
        state.work++
        val c = coeffs[i]
        if (c == 0L) continue
        val v = vars[i]
        val d = state.intDomains[v]
        val a = c * d.min
        val b = c * d.max
        val loTerm = if (a <= b) a else b
        val hiTerm = if (a <= b) b else a
        if ((op == LinearOp.LE || op == LinearOp.EQ) &&
            !loOverflow && !subOverflows(sumLo, loTerm) && !subOverflows(bound, sumLo - loTerm)
        ) {
            val slack0 = bound - (sumLo - loTerm)
            if (c > 0) {
                val t = floorDivLong(slack0, c)
                if (!tightenLinearMax(state, v, t) { loReason(i, liftBudget(c, t, -slack0)) }) return false
            } else {
                val t = ceilDivLong(slack0, c)
                if (!tightenLinearMin(state, v, t) { loReason(i, liftBudget(c, t, -slack0)) }) return false
            }
        }
        if ((op == LinearOp.GE || op == LinearOp.EQ) &&
            !hiOverflow && !subOverflows(sumHi, hiTerm) && !subOverflows(bound, sumHi - hiTerm)
        ) {
            val needed = bound - (sumHi - hiTerm)
            if (c > 0) {
                val t = ceilDivLong(needed, c)
                if (!tightenLinearMin(state, v, t) { hiReason(i, liftBudget(-c, t, needed)) }) return false
            } else {
                val t = floorDivLong(needed, c)
                if (!tightenLinearMax(state, v, t) { hiReason(i, liftBudget(-c, t, needed)) }) return false
            }
        }
    }
    return true
}

internal fun linearSumRange(state: PropagationState, coeffs: LongArray, vars: IntArray): LongArray {
    var lo = 0L
    var hi = 0L
    for (i in vars.indices) {
        state.work++
        val d = state.intDomains[vars[i]]
        val c = coeffs[i]
        // A wrapped product or sum weakens the range to the full Long interval: consumers treat the
        // bounds as attainable extremes, so anything narrower than the truth risks a wrong entailment.
        if (mulOverflows(c, d.min) || mulOverflows(c, d.max)) return longArrayOf(Long.MIN_VALUE, Long.MAX_VALUE)
        val a = c * d.min
        val b = c * d.max
        val termLo = if (a <= b) a else b
        val termHi = if (a <= b) b else a
        if (addOverflows(lo, termLo) || addOverflows(hi, termHi)) {
            return longArrayOf(Long.MIN_VALUE, Long.MAX_VALUE)
        }
        lo += termLo
        hi += termHi
    }
    return longArrayOf(lo, hi)
}

private inline fun tightenLinearMin(state: PropagationState, v: Int, newMin: Long, reason: () -> IntArray?): Boolean =
    newMin <= state.intDomains[v].min || state.tightenIntMin(v, newMin, reason())

private inline fun tightenLinearMax(state: PropagationState, v: Int, newMax: Long, reason: () -> IntArray?): Boolean =
    newMax >= state.intDomains[v].max || state.tightenIntMax(v, newMax, reason())

internal fun integralQuotientOrNull(value: Long, divisor: Long): Long? = when {
    divisor == 0L -> null
    value == Long.MIN_VALUE && divisor == -1L -> null
    value % divisor != 0L -> null
    else -> value / divisor
}

internal fun unreachableEqualityReason(
    state: PropagationState,
    variable: Int,
    target: Long?,
    extraLit: Int? = null,
): IntArray? {
    val premise = target?.takeIf { it in state.rootDomains[variable] }?.let { value ->
        val domain = state.intDomains[variable]
        when {
            value < domain.min -> Lit.make(state.atomVarGe(variable, domain.min), false)
            value > domain.max -> Lit.make(state.atomVarLe(variable, domain.max), false)
            else -> Lit.make(state.atomVarEq(variable, value), true)
        }
    }
    return when {
        premise == null -> extraLit?.let { intArrayOf(it) }
        extraLit == null -> intArrayOf(premise)
        else -> intArrayOf(premise, extraLit)
    }
}

internal fun floorDivLong(a: Long, b: Long): Long {
    val q = a / b
    return if (a % b != 0L && (a xor b) < 0L) q - 1 else q
}

internal fun ceilDivLong(a: Long, b: Long): Long {
    val q = a / b
    return if (a % b != 0L && (a xor b) >= 0L) q + 1 else q
}
