package com.eignex.klause.factor.arithmetic.internals

import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.lp.Int128
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.util.EmptyLongArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.IntIntMap

internal fun initLinearSum(state: LocalSearchState, factorId: Int, coeffs: LongArray, vars: IntArray) {
    var sum = 0L
    for (i in vars.indices) sum += coeffs[i] * state.assignment.intValue(vars[i])
    state.longPayload[factorId] = sum
}

/**
 * O(1) coefficient lookup over `vars`/`coeffs` for the LS linear invariants: a var→index map built
 * once per invariant. Move scoring queries a coefficient per candidate move per containing row, so
 * a per-query row scan turns ultra-wide rows (tens of thousands of terms) into deadline-starving
 * move picks; the map makes each query constant work. A variable outside the row yields 0,
 * so callers may ask about any variable.
 */
internal class LinearCoeffIndex(private val coeffs: LongArray, vars: IntArray) {
    private val index = IntIntMap.build(vars, IntArray(vars.size) { it }, absent = -1)

    fun coeffOf(intVar: Int): Long {
        val i = index[intVar]
        return if (i < 0) 0L else coeffs[i]
    }
}

/**
 * Hole-aware antecedent collection for propagators that prune interior values (AllDifferent,
 * GlobalCardinality, Regular, AllDifferentExceptZero, Inverse, Member, AllEqual). Cites:
 *   - bound atoms when current min/max are tighter than initial,
 *   - positive `[v == value]` atom-lits (the negation of the `v ≠ value` premise) for every
 *     value in the original domain that's currently excluded from the live domain.
 *
 * Together these literals describe the exact filtered domains the propagator reasoned over,
 * so the resulting conflict clause is a Hall-style reason rather than a bound-only one.
 * Allocates `atomVarEq` atoms on demand for each cited hole. Returns `null` when nothing is
 * tighter than the original (caller falls back to default antecedents).
 */
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
        if (state.intLevel[v] > 0) {
            anyAboveRoot = true
            break
        }
    }
    for (v in vars) {
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
        // Cite [v == value] for each originally-present value now carved out of the live domain within
        // its bounds. Enumerate whichever side is smaller: a survivor set over a wide span has span-many
        // holes but few members, so walking holes would be O(span) — iterate the original's members
        // instead. Both paths visit the same values ascending, so the cited literal set is identical.
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

/**
 * Direction-aware LCG antecedents for a linear deduction. A deduction (a bound tighten, or
 * the whole-constraint conflict) is driven by exactly one of the two reachable sum extremes:
 *   - **lo side** (`Σ rLo`, used by `LE` upper-bounds and the `sumLo > bound` conflict):
 *     each var's contribution is `c·min` for `c>0` (cite `[v ≥ min]`) or `c·max` for `c<0`
 *     (cite `[v ≤ max]`).
 *   - **hi side** (`Σ rHi`, used by `GE` lower-bounds and the `sumHi < bound` conflict):
 *     mirror — `c·max` for `c>0` (cite `[v ≤ max]`), `c·min` for `c<0` (cite `[v ≥ min]`).
 *
 * Only the literals on the driving side are cited (and only when tighter than the original
 * domain — root-level bounds are global facts the analyzer minimises out). Sharper than
 * [collectLinearTightenAntecedents] (which cites both bounds of every var), so learned clauses
 * generalise and prune across more of the search.
 */
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
        if (j == excludeIdx) continue
        if (state.intLevel[vars[j]] > 0) {
            anyAboveRoot = true
            break
        }
    }
    for (j in vars.indices) {
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
        if (j == excludeIdx) continue
        if (state.intLevel[vars[j]] > 0) {
            anyAboveRoot = true
            break
        }
    }
    for (j in vars.indices) {
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

private const val LINEAR_SHARED_REASON_ARITY = 32

/**
 * A single O(arity) reason citing every variable's **start-of-call** driving bound on [useLo]'s
 * sum-side, reconstructed from the pre-tighten contributions [rLo]/[rHi].
 */
internal fun collectLinearStartBoundAntecedents(
    state: PropagationState,
    coeffs: LongArray,
    vars: IntArray,
    rLo: LongArray,
    rHi: LongArray,
    useLo: Boolean,
    extraLit: Int,
    includeExtraLit: Boolean = false,
): IntArray? {
    val out = IntArrayList()
    if (includeExtraLit) out.add(extraLit)
    for (j in vars.indices) {
        val c = coeffs[j]
        if (c == 0L) continue
        val v = vars[j]
        val startMin = if (c > 0) rLo[j] / c else rHi[j] / c
        val startMax = if (c > 0) rHi[j] / c else rLo[j] / c
        val citeMin = if (useLo) c > 0 else c < 0
        if (citeMin) {
            if (startMin <= state.rootDomains[v].min) continue
            out.add(Lit.make(state.atomVarGe(v, startMin), false))
        } else {
            if (startMax >= state.rootDomains[v].max) continue
            out.add(Lit.make(state.atomVarLe(v, startMax), false))
        }
    }
    if (out.size == 0) return null
    return out.toIntArray()
}

/**
 * Exact 128-bit feasibility check for a linear row whose 64-bit bound arithmetic overflowed.
 * Detects definite violation only — no tightening — so wide-domain (and fully pinned) states are
 * still rejected exactly rather than silently passed; the missed tightenings re-run once the
 * domains narrow back into 64-bit range. Returns `false` iff the row is definitely infeasible.
 */
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
    // Shift both extremes by -bound so feasibility reduces to sign tests.
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

/** True iff `a * b` wraps 64-bit range. Both-magnitudes-below-2^31 short-circuits before the
 *  division so the propagation hot loop pays two xors, not an idiv, on ordinary domains. */
private fun mulOverflows(a: Long, b: Long): Boolean {
    if (((a xor (a shr 63)) or (b xor (b shr 63))) ushr 31 == 0L) return false
    if (a == 0L || b == 0L) return false
    if (b == -1L) return a == Long.MIN_VALUE
    return (a * b) / b != a
}

/** True iff `a + b` wraps 64-bit range. */
private fun addOverflows(a: Long, b: Long): Boolean = ((a xor (a + b)) and (b xor (a + b))) < 0L

/** True iff `a - b` wraps 64-bit range. */
private fun subOverflows(a: Long, b: Long): Boolean = ((a xor b) and (a xor (a - b))) < 0L

/**
 * Shared bounds-propagation routine for `Σ coeffs[i] * vars[i] ⟨op⟩ bound`. Used by `Linear`
 * directly and by `ReifiedLinear` when its aux Boolean is pinned. Returns `false` iff the
 * domains became jointly infeasible.
 *
 * Overflow-safe on wide (up to full-`Long`) domains: a coefficient-times-bound product that wraps
 * skips the factor's propagation for the round, and a directional sum that wraps disables only that
 * side's infeasibility test and tightenings. Wrapped arithmetic must never *strengthen* a bound or
 * declare a conflict — either would be unsound — so every overflow degrades to weaker propagation.
 * Bound-split search stays complete regardless: the skipped pruning re-runs on narrower domains.
 */
internal fun propagateLinearBounds(
    state: PropagationState,
    coeffs: LongArray,
    vars: IntArray,
    op: LinearOp,
    bound: Long,
    extraLit: Int = 0,
    includeExtraLit: Boolean = false,
): Boolean {
    val n = vars.size
    val wide = n > LINEAR_SHARED_REASON_ARITY
    val rLo = if (wide) LongArray(n) else EmptyLongArray
    val rHi = if (wide) LongArray(n) else EmptyLongArray
    var sumLo = 0L
    var sumHi = 0L
    var loOverflow = false
    var hiOverflow = false
    for (i in 0 until n) {
        val d = state.intDomains[vars[i]]
        val c = coeffs[i]
        if (mulOverflows(c, d.min) || mulOverflows(c, d.max)) {
            return linearFeasible128(state, coeffs, vars, op, bound)
        }
        val a = c * d.min
        val b = c * d.max
        val lo = if (a <= b) a else b
        val hi = if (a <= b) b else a
        loOverflow = loOverflow || addOverflows(sumLo, lo)
        hiOverflow = hiOverflow || addOverflows(sumHi, hi)
        sumLo += lo
        sumHi += hi
        if (wide) {
            rLo[i] = lo
            rHi[i] = hi
        }
    }
    if (loOverflow || hiOverflow) {
        // The wrapped side's infeasibility test is meaningless in 64-bit; re-check exactly so a
        // violated row (in particular at a fully pinned leaf) is still rejected.
        if (!linearFeasible128(state, coeffs, vars, op, bound)) return false
    }
    when (op) {
        LinearOp.LE -> if (!loOverflow && sumLo > bound) return false
        LinearOp.GE -> if (!hiOverflow && sumHi < bound) return false
        LinearOp.EQ -> if ((!loOverflow && sumLo > bound) || (!hiOverflow && sumHi < bound)) return false
        LinearOp.NE -> if (!loOverflow && !hiOverflow && sumLo == bound && sumHi == bound) return false
    }
    // At the root (level 0) a bound move is a permanent fact whose antecedents are never consumed:
    // conflict analysis runs only above the root, and it drops level-0 literals by their level rather
    // than resolving through their reason. Collecting the reason there is pure waste — and on a wide
    // domain a slow-converging linear bound narrows ~1 per round for O(span) rounds, each round citing
    // (and materializing) a fresh order atom whose sorted-index insert is O(n): O(span²) overall, which
    // hangs the root bake. Skip it at the root, leaving the actual bound tightening untouched.
    val rootFact = state.currentLevel == 0
    if (op == LinearOp.NE) {
        if (loOverflow || hiOverflow) return true
        for (i in 0 until n) {
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
            if (rhs % c != 0L) continue
            val forbidden = rhs / c
            if (forbidden < Int.MIN_VALUE || forbidden > Int.MAX_VALUE) continue
            val ant = if (rootFact) {
                null
            } else {
                collectLinearTightenAntecedents(state, vars, i, extraLit, includeExtraLit = includeExtraLit)
            }
            if (!state.excludeIntValue(v, forbidden, ant)) return false
        }
        return true
    }
    var loBase: IntArray? = null
    var loBaseBuilt = false
    var hiBase: IntArray? = null
    var hiBaseBuilt = false
    fun loReason(i: Int): IntArray? {
        if (rootFact) return null
        if (!wide) {
            return collectLinearDirAntecedents(
                state,
                coeffs,
                vars,
                i,
                extraLit,
                includeExtraLit = includeExtraLit,
                useLo = true,
            )
        }
        if (!loBaseBuilt) {
            loBase = collectLinearStartBoundAntecedents(
                state,
                coeffs,
                vars,
                rLo,
                rHi,
                useLo = true,
                extraLit = extraLit,
                includeExtraLit = includeExtraLit,
            )
            loBaseBuilt = true
        }
        return loBase
    }
    fun hiReason(i: Int): IntArray? {
        if (rootFact) return null
        if (!wide) {
            return collectLinearDirAntecedents(
                state,
                coeffs,
                vars,
                i,
                extraLit,
                includeExtraLit = includeExtraLit,
                useLo = false,
            )
        }
        if (!hiBaseBuilt) {
            hiBase = collectLinearStartBoundAntecedents(
                state,
                coeffs,
                vars,
                rLo,
                rHi,
                useLo = false,
                extraLit = extraLit,
                includeExtraLit = includeExtraLit,
            )
            hiBaseBuilt = true
        }
        return hiBase
    }
    for (i in 0 until n) {
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
                if (!tightenMaxClamped(state, v, t, loReason(i))) return false
            } else {
                if (!tightenMinClamped(state, v, ceilDivLong(slack0, c), loReason(i))) return false
            }
        }
        if ((op == LinearOp.GE || op == LinearOp.EQ) &&
            !hiOverflow && !subOverflows(sumHi, hiTerm) && !subOverflows(bound, sumHi - hiTerm)
        ) {
            val needed = bound - (sumHi - hiTerm)
            if (c > 0) {
                val t = ceilDivLong(needed, c)
                if (!tightenMinClamped(state, v, t, hiReason(i))) return false
            } else {
                if (!tightenMaxClamped(state, v, floorDivLong(needed, c), hiReason(i))) return false
            }
        }
    }
    return true
}

/**
 * Range `[sumLo, sumHi]` reachable by `Σ coeffs[i] * vars[i]` given current domains.
 */
internal fun linearSumRange(state: PropagationState, coeffs: LongArray, vars: IntArray): LongArray {
    var lo = 0L
    var hi = 0L
    for (i in vars.indices) {
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

// Domains are 64-bit, so a bound beyond the 32-bit range is a valid tightening, not an infeasibility;
// the mutator itself is a no-op when the bound doesn't constrain the current domain.
private fun tightenMinClamped(state: PropagationState, v: Int, newMin: Long, ant: IntArray? = null): Boolean =
    state.tightenIntMin(v, newMin, ant)

private fun tightenMaxClamped(state: PropagationState, v: Int, newMax: Long, ant: IntArray? = null): Boolean =
    state.tightenIntMax(v, newMax, ant)

/** floor(a / b) with correct handling of negative operands. */
internal fun floorDivLong(a: Long, b: Long): Long {
    val q = a / b
    return if (a % b != 0L && (a xor b) < 0L) q - 1 else q
}

/** ceil(a / b) with correct handling of negative operands. */
internal fun ceilDivLong(a: Long, b: Long): Long {
    val q = a / b
    return if (a % b != 0L && (a xor b) >= 0L) q + 1 else q
}
