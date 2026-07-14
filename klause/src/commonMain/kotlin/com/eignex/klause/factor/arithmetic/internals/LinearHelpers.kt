package com.eignex.klause.factor.arithmetic.internals

import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.lp.gcdLong
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.solver.Lit
import com.eignex.klause.util.EmptyLongArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet

internal fun initLinearSum(state: LocalSearchState, factorId: Int, coeffs: LongArray, vars: IntArray) {
    var sum = 0L
    for (i in vars.indices) sum += coeffs[i] * state.assignment.intValue(vars[i])
    state.longPayload[factorId] = sum
}

/**
 * Look up the coefficient for [intVar] in [vars]/[coeffs]. Returns 0 if [intVar] is not found.
 * O(n) scan used by interface default methods — the hot path in the concrete classes uses
 * [com.eignex.klause.factor.CoeffLookup] for O(1) access.
 */
internal fun findCoeff(coeffs: LongArray, vars: IntArray, intVar: Int): Long {
    for (i in vars.indices) if (vars[i] == intVar) return coeffs[i]
    return 0L
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
        val orig = state.problem.intDomains[v]
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
        if (orig.size.toLong() <= d.holeCount) {
            orig.forEach { value ->
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
 * [collectLinearTightenAntecedents] (which cited both bounds of every var), so learned clauses
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
        val orig = state.problem.intDomains[v]
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
        val orig = state.problem.intDomains[v]
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
            if (startMin <= state.problem.intDomains[v].min) continue
            out.add(Lit.make(state.atomVarGe(v, startMin), false))
        } else {
            if (startMax >= state.problem.intDomains[v].max) continue
            out.add(Lit.make(state.atomVarLe(v, startMax), false))
        }
    }
    if (out.size == 0) return null
    return out.toIntArray()
}

/**
 * Shared bounds-propagation routine for `Σ coeffs[i] * vars[i] ⟨op⟩ bound`. Used by `Linear`
 * directly and by `ReifiedLinear` when its aux Boolean is pinned. Returns `false` iff the
 * domains became jointly infeasible.
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
    for (i in 0 until n) {
        val d = state.intDomains[vars[i]]
        val c = coeffs[i]
        val a = c * d.min
        val b = c * d.max
        val lo = if (a <= b) a else b
        val hi = if (a <= b) b else a
        sumLo += lo
        sumHi += hi
        if (wide) {
            rLo[i] = lo
            rHi[i] = hi
        }
    }
    when (op) {
        LinearOp.LE -> if (sumLo > bound) return false
        LinearOp.GE -> if (sumHi < bound) return false
        LinearOp.EQ -> if (sumLo > bound || sumHi < bound) return false
        LinearOp.NE -> if (sumLo == bound && sumHi == bound) return false
    }
    // At the root (level 0) a bound move is a permanent fact whose antecedents are never consumed:
    // conflict analysis runs only above the root, and it drops level-0 literals by their level rather
    // than resolving through their reason. Collecting the reason there is pure waste — and on a wide
    // domain a slow-converging linear bound narrows ~1 per round for O(span) rounds, each round citing
    // (and materializing) a fresh order atom whose sorted-index insert is O(n): O(span²) overall, the
    // #18 root-bake hang. Skip it at the root, leaving the actual bound tightening untouched.
    val rootFact = state.currentLevel == 0
    // `Σ cᵢ·xᵢ = bound` ranges over exactly the multiples of `gcd(cᵢ)`, so an integer solution exists
    // only when that gcd divides `bound` — independent of the (possibly very wide) variable bounds, so
    // it short-circuits the O(span) narrowing toward the empty domain (e.g. `3x + 3y = 1`). It is
    // loop-invariant (coeffs/bound are fixed), so a feasible equality always passes: running it on
    // every search propagation would be pure hot-path overhead. Do it only at the root, which catches
    // the infeasible case once — before any narrowing.
    if (rootFact && op == LinearOp.EQ) {
        var g = 0L
        for (i in 0 until n) g = gcdLong(g, coeffs[i])
        if (g > 1L && bound % g != 0L) return false
    }
    if (op == LinearOp.NE) {
        for (i in 0 until n) {
            val c = coeffs[i]
            if (c == 0L) continue
            val v = vars[i]
            val d = state.intDomains[v]
            val a = c * d.min
            val b = c * d.max
            val otherLo = sumLo - (if (a <= b) a else b)
            val otherHi = sumHi - (if (a <= b) b else a)
            if (otherLo != otherHi) continue
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
        val otherLo = sumLo - (if (a <= b) a else b)
        val otherHi = sumHi - (if (a <= b) b else a)
        if (op == LinearOp.LE || op == LinearOp.EQ) {
            val slack0 = bound - otherLo
            if (c > 0) {
                val t = floorDivLong(slack0, c)
                if (!tightenMaxClamped(state, v, t, loReason(i))) return false
            } else {
                if (!tightenMinClamped(state, v, ceilDivLong(slack0, c), loReason(i))) return false
            }
        }
        if (op == LinearOp.GE || op == LinearOp.EQ) {
            val needed = bound - otherHi
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
        val a = c * d.min
        val b = c * d.max
        if (a <= b) {
            lo += a
            hi += b
        } else {
            lo += b
            hi += a
        }
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
