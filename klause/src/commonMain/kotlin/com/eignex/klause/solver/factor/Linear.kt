package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntArrayList

/** Relational operator for a [Linear] constraint. */
enum class LinearOp {
    /** `≤`. */
    LE,

    /** `=`. */
    EQ,

    /** `≥`. */
    GE,

    /** `≠`. */
    NE,
}

/**
 * `Σ coeffs[i] * intVars[i] ⟨op⟩ bound`. Payload at `intPayload[factorId]` is the current
 * weighted sum, kept in sync incrementally by [applyIntSet]. Repair moves propose, for each
 * variable, the integer value that on its own would put the sum on the right side of [bound],
 * clamped to the variable's domain.
 */
class Linear(
    /** Coefficients, parallel to [vars]. */
    val coeffs: IntArray,
    /** Integer variable ids, parallel to [coeffs]. */
    val vars: IntArray,
    /** Relation between the weighted sum and [bound]. */
    val op: LinearOp,
    /** Right-hand-side bound. */
    val bound: Int,
) : LocalSearchFactor {

    init {
        require(coeffs.size == vars.size) { "coeffs/vars length mismatch" }
        require(coeffs.isNotEmpty()) { "Linear must have at least one term" }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = vars

    private val coeffLookup: CoeffLookup = CoeffLookup.build(vars, coeffs)

    override fun initialize(state: LocalSearchState, factorId: Int) {
        var sum = 0
        for (i in vars.indices) sum += coeffs[i] * state.assignment.intValue(vars[i])
        state.intPayload[factorId] = sum
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = violates(state.intPayload[factorId])

    /** Graded violation: the residual amount by which the sum misses [bound] — `|sum-bound|`
     *  for EQ, `max(0, sum-bound)` for LE, `max(0, bound-sum)` for GE. NE has no natural
     *  magnitude, so it stays binary (1 when `sum == bound`). This is the descent gradient
     *  CBLS needs on tight arithmetic: a move shrinking the residual scores a real improvement
     *  even when it doesn't flip the satisfied/violated status. */
    override fun violationDegree(state: LocalSearchState, factorId: Int): Int = degree(state.intPayload[factorId])

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val coeff = coeffOf(intVar)
        val old = state.assignment.intValue(intVar)
        val sum = state.intPayload[factorId]
        val newSum = sum + coeff * (newValue - old)
        return degree(newSum) - degree(sum)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val coeff = coeffOf(intVar)
        val cur = state.assignment.intValue(intVar)
        val oldSum = state.intPayload[factorId]
        val newSum = oldSum + coeff * (cur - oldValue)
        state.intPayload[factorId] = newSum
        return degree(newSum) - degree(oldSum)
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean =
        propagateLinearBounds(state, coeffs, vars, op, bound.toLong())

    /** Reason set when [propagate] returns false. The conflict comes from exactly one sum
     *  extreme breaching [bound]: `LE` / `EQ`-with-`sumLo>bound` from the lo side (`Σ rLo`),
     *  `GE` / `EQ`-with-`sumHi<bound` from the hi side (`Σ rHi`). Cite only that side's
     *  driving bounds (see [collectLinearDirAntecedents]) — those alone prove infeasibility,
     *  so the nogood is sharper and more reusable than citing both bounds of every var.
     *  `NE` (sum pinned to `bound`) needs both bounds, so it keeps the dense reason. Sound;
     *  analyzer 1UIP minimisation trims any remaining redundancy. */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? {
        if (op == LinearOp.NE) return collectLinearTightenAntecedents(state, vars, excludeIdx = -1, extraLit = 0)
        val range = linearSumRange(state, coeffs, vars) // [sumLo, sumHi]
        val useLo = when (op) {
            LinearOp.LE -> true
            LinearOp.GE -> false
            else -> range[0] > bound.toLong() // EQ: lo side (mins too big) vs hi side
        }
        return collectLinearDirAntecedents(state, coeffs, vars, excludeIdx = -1, extraLit = 0, useLo = useLo)
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val sum = state.intPayload[factorId]
        if (!violates(sum)) return
        if (op == LinearOp.NE) {
            // sum == bound; bump any non-zero-coeff variable by ±1 within its domain. Each
            // single shift changes sum by ±|c_i| ≠ 0 → breaks the equality.
            for (i in vars.indices) {
                val v = vars[i]
                val c = coeffs[i]
                if (c == 0) continue
                val cur = state.assignment.intValue(v)
                val d = state.problem.intDomains[v]
                if (cur > d.min) sink.addChannelingIntSet(state, v, cur - 1)
                if (cur < d.max) sink.addChannelingIntSet(state, v, cur + 1)
            }
            return
        }
        for (i in vars.indices) {
            val v = vars[i]
            val c = coeffs[i]
            if (c == 0) continue
            val cur = state.assignment.intValue(v)
            val sumWithout = sum - c * cur
            val target = snapTarget(c, sumWithout) ?: continue
            val clamped = state.problem.intDomains[v].clamp(target)
            if (clamped != cur) sink.addChannelingIntSet(state, v, clamped)
        }
    }

    /** Self-preserving move suggestions during objective descent. For [LinearOp.EQ] the
     *  natural structured move is a pair-shift `(IntSet(a, va-Δ), IntSet(b, vb+Δ))` chosen
     *  so `coeffs[a]·Δa + coeffs[b]·Δb = 0` — the equality stays satisfied while the
     *  individual var values change. This is the heart of "swap one item between bins"
     *  for exactly-one decompositions and the analogous "shift Δ between two summed
     *  vars" for weighted equalities. For LE/GE we propose unilateral shifts that
     *  consume / restore slack; the engine scores each by objective delta and applies
     *  the best feasibility-preserving one.
     *
     *  Bounded: we sample up to [PAIR_SAMPLE_CAP] random pairs (or all of them when the
     *  factor's arity is small) rather than enumerate Θ(n²) which would dominate the
     *  inner loop on large decomposed instances. */
    override fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (vars.size < 2) return
        when (op) {
            LinearOp.EQ -> proposeEqPairShifts(state, sink)
            LinearOp.LE, LinearOp.GE -> proposeBoundedSlackShifts(state, factorId, sink)
            LinearOp.NE -> { /* no useful structured move — NE is fragile to any change */ }
        }
    }

    /** EQ pair-shift: for each sampled pair (a, b) with non-zero coefficients, propose
     *  the smallest integer Δ such that `coeffs[a]·Δ = -coeffs[b]·Δ'` for some integer
     *  Δ' that fits both vars' domains. Equal-coefficient case (e.g. exactly-one decomp
     *  with all c=1) collapses to a 0/1 value swap. */
    private fun proposeEqPairShifts(state: LocalSearchState, sink: MoveSink) {
        val n = vars.size
        val tryPair = { a: Int, b: Int ->
            val ca = coeffs[a]
            val cb = coeffs[b]
            if (ca != 0 && cb != 0) {
                val va = state.assignment.intValue(vars[a])
                val vb = state.assignment.intValue(vars[b])
                // For each Δ ∈ {-1, +1}, the matching Δb is -ca/cb · Δa; if integer and in
                // domain on both sides, propose the compound. Covers the common pure-bool
                // exactly-one decomposition (ca=cb=1, swap 0/1).
                for (delta in intArrayOf(-1, 1)) {
                    if (cb == 0) continue
                    val needNumerator = -ca * delta
                    if (needNumerator % cb != 0) continue
                    val deltaB = needNumerator / cb
                    val newA = va + delta
                    val newB = vb + deltaB
                    if (newA == va && newB == vb) continue
                    val domA = state.problem.intDomains[vars[a]]
                    val domB = state.problem.intDomains[vars[b]]
                    if (newA !in domA || newB !in domB) continue
                    sink.addCompound(
                        listOf(
                            com.eignex.klause.solver.Move.IntSet(vars[a], newA),
                            com.eignex.klause.solver.Move.IntSet(vars[b], newB),
                        ),
                    )
                }
            }
        }
        // Exhaustive on small arity; sampled on large.
        if (n * (n - 1) / 2 <= PAIR_SAMPLE_CAP) {
            for (a in 0 until n) for (b in a + 1 until n) tryPair(a, b)
        } else {
            val rng = state.rng
            var tried = 0
            while (tried < PAIR_SAMPLE_CAP) {
                val a = rng.nextInt(n)
                val b = rng.nextInt(n)
                if (a != b) tryPair(a, b)
                tried++
            }
        }
    }

    /** LE/GE slack-aware shifts: when slack > 0, propose individual var moves whose
     *  direction consumes some slack. For LE: `sum ≤ bound` has slack `bound - sum`;
     *  we can increase any var with positive coefficient or decrease any with negative
     *  coefficient by up to floor(slack / |c|). For GE: symmetric. Single-var moves —
     *  not pairs — since the inequality direction lets us absorb the delta in slack. */
    private fun proposeBoundedSlackShifts(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val curSum = state.intPayload[factorId]
        val slack = when (op) {
            LinearOp.LE -> bound - curSum
            LinearOp.GE -> curSum - bound
            else -> 0
        }
        if (slack <= 0) return
        for (i in vars.indices) {
            val c = coeffs[i]
            if (c == 0) continue
            val v = vars[i]
            val cur = state.assignment.intValue(v)
            val absC = if (c < 0) -c else c
            val maxStep = slack / absC
            if (maxStep <= 0) continue
            val dom = state.problem.intDomains[v]
            // For LE: positive c → decrease v (frees more slack), negative c → increase v.
            // For GE: opposite. Either way, the move stays feasible because |Δ·c| ≤ slack.
            val direction = when (op) {
                LinearOp.LE -> if (c > 0) -1 else 1
                LinearOp.GE -> if (c > 0) 1 else -1
                else -> 0
            }
            val target = cur + direction * maxStep
            val clamped = dom.clamp(target)
            if (clamped != cur) sink.addChannelingIntSet(state, v, clamped)
        }
    }

    private fun violates(sum: Int): Boolean = when (op) {
        LinearOp.LE -> sum > bound
        LinearOp.EQ -> sum != bound
        LinearOp.GE -> sum < bound
        LinearOp.NE -> sum == bound
    }

    /** Graded violation magnitude (0 when satisfied). The raw residual is run through
     *  [compressViolation] so a large coeff·domain residual neither overflows nor dominates
     *  the global cost — exact near feasibility, log-compressed far from it. */
    private fun degree(sum: Int): Int = when (op) {
        LinearOp.LE -> compressViolation(sum.toLong() - bound)

        LinearOp.GE -> compressViolation(bound.toLong() - sum)

        LinearOp.EQ -> {
            val d = sum.toLong() - bound
            compressViolation(if (d < 0) -d else d)
        }

        LinearOp.NE -> if (sum == bound) 1 else 0
    }

    private fun coeffOf(intVar: Int): Int = coeffLookup.coeffOf(intVar)

    private fun snapTarget(coeff: Int, sumWithout: Int): Int? {
        val numerator = bound - sumWithout
        return when (op) {
            LinearOp.EQ -> if (numerator % coeff == 0) numerator / coeff else null

            LinearOp.LE -> if (coeff > 0) floorDiv(numerator, coeff) else ceilDiv(numerator, coeff)

            LinearOp.GE -> if (coeff > 0) ceilDiv(numerator, coeff) else floorDiv(numerator, coeff)

            // NE: target is "any value such that the sum is not [bound]". Closest non-bound
            // value to current works: shift the var by 1 in either direction (clamped). Caller
            // re-clamps to domain; if the shifted value re-creates the bound exactly, the
            // factor will re-fire and the next repair pass tries the other direction.
            LinearOp.NE -> null // proposeRepairMoves below handles NE explicitly.
        }
    }

    private fun floorDiv(a: Int, b: Int): Int {
        val q = a / b
        val r = a % b
        return if (r != 0 && (r xor b) < 0) q - 1 else q
    }

    private companion object {
        /** Cap on randomly-sampled (a, b) pairs in [proposeEqPairShifts]. Total proposals
         *  bound matters for engine throughput — each scored move costs a state.apply +
         *  revert. 32 is enough to land useful candidates on the dominant decomposed-CP
         *  shape (sum-of-bools = constant) without dominating descent step cost. */
        const val PAIR_SAMPLE_CAP: Int = 32
    }

    private fun ceilDiv(a: Int, b: Int): Int {
        val q = a / b
        val r = a % b
        return if (r != 0 && (r xor b) >= 0) q + 1 else q
    }
}

/**
 * Compose LCG-style antecedents for an int-bound tightening on `vars[excludeIdx]` driven by
 * the `Σ coeffs · vars ⟨op⟩ bound` constraint. Unions:
 *   - [extraLit] (e.g. the reif var's false-form for [ReifiedLinear]),
 *   - the [PropagationState.intMinAntecedents] / [PropagationState.intMaxAntecedents] of
 *     every other var in the constraint.
 *
 * Each int fact's antecedents transitively trace back to the bool decisions that established
 * it; the analyzer's 1UIP loop resolves through them. Returns `null` when nothing was
 * recorded (no extraLit, all other vars' int antecedents unset).
 */

/**
 * Hole-aware antecedent collection for propagators that prune interior values (AllDifferent,
 * GlobalCardinality, Regular, AllDifferentExceptZero, Inverse, Member). Cites:
 *   - bound atoms when current min/max are tighter than initial,
 *   - `[v ≠ value]` atom-lits for every value in the original domain that's currently
 *     excluded from the live domain.
 *
 * Together these literals describe the exact filtered domains the propagator reasoned over,
 * so the resulting conflict clause is a Hall-style reason rather than a bound-only one.
 * Allocates `atomVarEq` atoms on demand for each cited hole. Returns `null` when nothing is
 * tighter than the original (caller falls back to default antecedents).
 */
internal fun collectHoleAndBoundAntecedents(state: PropagationState, vars: IntArray): IntArray? {
    val seen = HashSet<Int>()
    val out = IntArrayList()
    // Sweep-prefix tightening: collect *only* antecedents tied to decision levels > 0 when
    // any such exist in scope. A var with `intLevel[v] <= 0` was tightened at root level —
    // its restriction is a global fact that the resolution analyzer would minimize out
    // anyway. Pre-stripping shrinks the seed clause and saves analyzer cycles.
    //
    // Fallback: if every var in scope is at root level (currentLevel == 0 conflict, or all
    // restrictions are unit-propagated globals), keep everything — the level-0 reason is
    // the only seed available and is needed for unsat-core construction.
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
            val lit = com.eignex.klause.solver.Lit.make(state.atomVarGe(v, d.min), false)
            if (seen.add(lit)) out.add(lit)
        }
        if (d.max < orig.max) {
            val lit = com.eignex.klause.solver.Lit.make(state.atomVarLe(v, d.max), false)
            if (seen.add(lit)) out.add(lit)
        }
        val lo = maxOf(d.min, orig.min)
        val hi = minOf(d.max, orig.max)
        for (value in lo..hi) {
            if (value in orig && value !in d) {
                val lit = state.atomLitNe(v, value)
                if (seen.add(lit)) out.add(lit)
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
 * domain — root-level bounds are global facts the analyzer minimises out). The opposite-side
 * bounds and zero-coefficient vars play no part in the inequality, so omitting them yields a
 * strictly more general nogood without changing soundness — the cited bounds alone prove the
 * deduction. Sharper than [collectLinearTightenAntecedents] (which cited both bounds of every
 * var), so learned clauses generalise and prune across more of the search.
 */
internal fun collectLinearDirAntecedents(
    state: PropagationState,
    coeffs: IntArray,
    vars: IntArray,
    excludeIdx: Int,
    extraLit: Int,
    useLo: Boolean,
): IntArray? {
    val seen = HashSet<Int>()
    val out = IntArrayList()
    if (extraLit != 0) {
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
        if (c == 0) continue
        val v = vars[j]
        if (anyAboveRoot && state.intLevel[v] <= 0) continue
        // Which bound of this var feeds the driving sum side?
        val citeMin = if (useLo) c > 0 else c < 0
        val d = state.intDomains[v]
        val orig = state.problem.intDomains[v]
        if (citeMin) {
            if (d.min > orig.min) {
                val lit = com.eignex.klause.solver.Lit.make(state.atomVarGe(v, d.min), false)
                if (seen.add(lit)) out.add(lit)
            }
        } else {
            if (d.max < orig.max) {
                val lit = com.eignex.klause.solver.Lit.make(state.atomVarLe(v, d.max), false)
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
): IntArray? {
    val seen = HashSet<Int>()
    val out = IntArrayList()
    if (extraLit != 0) {
        out.add(extraLit)
        seen.add(extraLit)
    }
    // Same sweep-prefix tightening as [collectHoleAndBoundAntecedents]: drop level-0 vars
    // when at least one non-excluded var has been tightened above the root level.
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
            val lit = com.eignex.klause.solver.Lit.make(state.atomVarGe(v, d.min), false)
            if (seen.add(lit)) out.add(lit)
        }
        if (d.max < orig.max) {
            val lit = com.eignex.klause.solver.Lit.make(state.atomVarLe(v, d.max), false)
            if (seen.add(lit)) out.add(lit)
        }
    }
    if (out.size == 0) return null
    return out.toIntArray()
}

/**
 * Shared bounds-propagation routine for `Σ coeffs[i] * vars[i] ⟨op⟩ bound`. Used by [Linear]
 * directly and by [ReifiedLinear] when its aux Boolean is pinned. Returns `false` iff the
 * domains became jointly infeasible. [extraLit] is an optional context literal (typically the
 * reif aux's false-form) appended to every int-tighten's antecedents.
 */
internal fun propagateLinearBounds(
    state: PropagationState,
    coeffs: IntArray,
    vars: IntArray,
    op: LinearOp,
    bound: Long,
    extraLit: Int = 0,
): Boolean {
    val n = vars.size
    val rLo = LongArray(n)
    val rHi = LongArray(n)
    var sumLo = 0L
    var sumHi = 0L
    for (i in 0 until n) {
        val d = state.intDomains[vars[i]]
        val c = coeffs[i].toLong()
        val a = c * d.min
        val b = c * d.max
        if (a <= b) {
            rLo[i] = a
            rHi[i] = b
        } else {
            rLo[i] = b
            rHi[i] = a
        }
        sumLo += rLo[i]
        sumHi += rHi[i]
    }
    when (op) {
        LinearOp.LE -> if (sumLo > bound) return false
        LinearOp.GE -> if (sumHi < bound) return false
        LinearOp.EQ -> if (sumLo > bound || sumHi < bound) return false
        LinearOp.NE -> if (sumLo == bound && sumHi == bound) return false
    }
    if (op == LinearOp.NE) {
        for (i in 0 until n) {
            val c = coeffs[i].toLong()
            if (c == 0L) continue
            val otherLo = sumLo - rLo[i]
            val otherHi = sumHi - rHi[i]
            if (otherLo != otherHi) continue
            val rhs = bound - otherLo
            if (rhs % c != 0L) continue
            val forbidden = rhs / c
            if (forbidden < Int.MIN_VALUE || forbidden > Int.MAX_VALUE) continue
            val v = vars[i]
            val ant = collectLinearTightenAntecedents(state, vars, i, extraLit)
            if (!state.excludeIntValue(v, forbidden.toInt(), ant)) return false
        }
        return true
    }
    for (i in 0 until n) {
        val c = coeffs[i].toLong()
        if (c == 0L) continue
        val v = vars[i]
        val otherLo = sumLo - rLo[i]
        val otherHi = sumHi - rHi[i]
        if (op == LinearOp.LE || op == LinearOp.EQ) {
            // Upper-bound tighten driven by the lo side (Σ rLo of the other vars).
            val slack = bound - otherLo
            val ant = collectLinearDirAntecedents(state, coeffs, vars, i, extraLit, useLo = true)
            if (c > 0) {
                if (!tightenMaxClamped(state, v, floorDivLong(slack, c), ant)) return false
            } else {
                if (!tightenMinClamped(state, v, ceilDivLong(slack, c), ant)) return false
            }
        }
        if (op == LinearOp.GE || op == LinearOp.EQ) {
            // Lower-bound tighten driven by the hi side (Σ rHi of the other vars).
            val needed = bound - otherHi
            val ant = collectLinearDirAntecedents(state, coeffs, vars, i, extraLit, useLo = false)
            if (c > 0) {
                if (!tightenMinClamped(state, v, ceilDivLong(needed, c), ant)) return false
            } else {
                if (!tightenMaxClamped(state, v, floorDivLong(needed, c), ant)) return false
            }
        }
    }
    return true
}

/**
 * Range `[sumLo, sumHi]` reachable by `Σ coeffs[i] * vars[i]` given current domains. Used by
 * reified factors to decide whether the body of a linear comparison is forced one way or the
 * other.
 */
internal fun linearSumRange(state: PropagationState, coeffs: IntArray, vars: IntArray): LongArray {
    var lo = 0L
    var hi = 0L
    for (i in vars.indices) {
        val d = state.intDomains[vars[i]]
        val c = coeffs[i].toLong()
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

private fun tightenMinClamped(state: PropagationState, v: Int, newMin: Long, ant: IntArray? = null): Boolean = when {
    newMin > Int.MAX_VALUE -> false
    newMin < Int.MIN_VALUE -> true
    else -> state.tightenIntMin(v, newMin.toInt(), ant)
}

private fun tightenMaxClamped(state: PropagationState, v: Int, newMax: Long, ant: IntArray? = null): Boolean = when {
    newMax < Int.MIN_VALUE -> false
    newMax > Int.MAX_VALUE -> true
    else -> state.tightenIntMax(v, newMax.toInt(), ant)
}

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
