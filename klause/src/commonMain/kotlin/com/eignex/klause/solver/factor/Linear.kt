package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Move.IntSet
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet

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
 * variable, the integer value that on its own would put the sum on the right side of `bound`,
 * clamped to the variable's domain.
 */
class Linear private constructor(
    terms: CoalescedTerms,
    /** Relation between the weighted sum and `bound`. */
    val op: LinearOp,
    /** Right-hand-side bound. */
    val bound: Int,
) : Factor {

    /** Integer variable ids, parallel to [coeffs]; each variable appears at most once. */
    val vars: IntArray = terms.vars

    /** Coefficients, parallel to [vars]. */
    val coeffs: IntArray = terms.coeffs

    /**
     * `Σ coeffs[i] * vars[i] ⟨op⟩ bound`. Duplicate variables are coalesced (their coefficients
     * summed) so the local-search payload stays consistent regardless of caller (issue #84).
     */
    constructor(coeffs: IntArray, vars: IntArray, op: LinearOp, bound: Int) :
        this(coalesceLinearTerms(vars, coeffs), op, bound)

    init {
        require(coeffs.isNotEmpty()) { "Linear must have at least one term" }
    }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = Linear(coeffs, vars.remapVars(intMap), op, bound)

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = vars

    private val coeffLookup: CoeffLookup = CoeffLookup.build(vars, coeffs)

    override fun initialize(state: LocalSearchState, factorId: Int) {
        var sum = 0L
        for (i in vars.indices) sum += coeffs[i].toLong() * state.assignment.intValue(vars[i])
        state.longPayload[factorId] = sum
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = violates(state.longPayload[factorId])

    /** Graded violation: the residual amount by which the sum misses `bound` — `|sum-bound|`
     *  for EQ, `max(0, sum-bound)` for LE, `max(0, bound-sum)` for GE. NE has no natural
     *  magnitude, so it stays binary (1 when `sum == bound`). This is the descent gradient
     *  CBLS needs on tight arithmetic: a move shrinking the residual scores a real improvement
     *  even when it doesn't flip the satisfied/violated status. */
    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        degree(state.longPayload[factorId], state.violationSoftCap)

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val coeff = coeffOf(intVar)
        val old = state.assignment.intValue(intVar)
        val sum = state.longPayload[factorId]
        val newSum = sum + coeff.toLong() * (newValue - old)
        return degree(newSum, state.violationSoftCap) - degree(sum, state.violationSoftCap)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val coeff = coeffOf(intVar)
        val cur = state.assignment.intValue(intVar)
        val oldSum = state.longPayload[factorId]
        val newSum = oldSum + coeff.toLong() * (cur - oldValue)
        state.longPayload[factorId] = newSum
        return degree(newSum, state.violationSoftCap) - degree(oldSum, state.violationSoftCap)
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean =
        propagateLinearBounds(state, coeffs, vars, op, bound.toLong())

    /** Reason set when [propagate] returns false. The conflict comes from exactly one sum
     *  extreme breaching `bound`: `LE` / `EQ`-with-`sumLo>bound` from the lo side (`Σ rLo`),
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
        // Conflict: the driving extreme breaches `bound`; slack = how far it can fall back and
        // still breach (sumLo > bound ⇒ sumLo-bound-1; sumHi < bound ⇒ bound-sumHi-1).
        val slack = if (useLo) range[0] - bound.toLong() - 1 else bound.toLong() - range[1] - 1
        return collectLinearRelaxedAntecedents(
            state,
            coeffs,
            vars,
            excludeIdx = -1,
            slack = slack,
            useLo = useLo,
            extraLit = 0,
        )
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val sum = state.longPayload[factorId]
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
            val sumWithout = sum - c.toLong() * cur
            val target = snapTarget(c, sumWithout) ?: continue
            val clamped = state.problem.intDomains[v].clampLong(target)
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
                            IntSet(vars[a], newA),
                            IntSet(vars[b], newB),
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
        val curSum = state.longPayload[factorId]
        val slack = when (op) {
            LinearOp.LE -> bound - curSum
            LinearOp.GE -> curSum - bound
            else -> 0L
        }
        if (slack <= 0L) return
        for (i in vars.indices) {
            val c = coeffs[i]
            if (c == 0) continue
            val v = vars[i]
            val cur = state.assignment.intValue(v)
            val absC = if (c < 0) -c.toLong() else c.toLong()
            val maxStep = slack / absC
            if (maxStep <= 0L) continue
            val dom = state.problem.intDomains[v]
            // For LE: positive c → decrease v (frees more slack), negative c → increase v.
            // For GE: opposite. Either way, the move stays feasible because |Δ·c| ≤ slack.
            val direction = when (op) {
                LinearOp.LE -> if (c > 0) -1 else 1
                LinearOp.GE -> if (c > 0) 1 else -1
                else -> 0
            }
            val target = cur + direction * maxStep
            val clamped = dom.clampLong(target)
            if (clamped != cur) sink.addChannelingIntSet(state, v, clamped)
        }
    }

    /** Violated iff the relation does not hold. Graded magnitude comes from [linearDegree];
     *  both delegate to the shared [linearHolds] / [linearResidual] math (issue #100) so the
     *  `Long` running sum is interpreted in exactly one place. */
    private fun violates(sum: Long): Boolean = !linearHolds(sum, op, bound)

    private fun degree(sum: Long, softCap: Int): Int = linearDegree(sum, op, bound, softCap)

    private fun coeffOf(intVar: Int): Int = coeffLookup.coeffOf(intVar)

    /** Repair snap toward satisfaction. `NE` is handled explicitly in [proposeRepairMoves]
     *  (it early-returns before this loop), so the `wantHolds = true` path never hits the
     *  `NE` branch here. */
    private fun snapTarget(coeff: Int, sumWithout: Long): Long? =
        snapLinearTarget(op, bound, coeff, sumWithout, wantHolds = true)

    private companion object {
        /** Cap on randomly-sampled (a, b) pairs in [proposeEqPairShifts]. Total proposals
         *  bound matters for engine throughput — each scored move costs a state.apply +
         *  revert. 32 is enough to land useful candidates on the dominant decomposed-CP
         *  shape (sum-of-bools = constant) without dominating descent step cost. */
        const val PAIR_SAMPLE_CAP: Int = 32
    }
}

/*
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
internal fun collectHoleAndBoundAntecedents(state: PropagationState, vars: IntArray): IntArray? {
    val seen = IntHashSet(vars.size * 2) // pre-sized to the literal count to avoid rehash-grow during fill
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
            val lit = Lit.make(state.atomVarGe(v, d.min), false)
            if (seen.add(lit)) out.add(lit)
        }
        if (d.max < orig.max) {
            val lit = Lit.make(state.atomVarLe(v, d.max), false)
            if (seen.add(lit)) out.add(lit)
        }
        val lo = maxOf(d.min, orig.min)
        val hi = minOf(d.max, orig.max)
        // Iterate only the values actually carved out of `d` in `[lo, hi]` (O(holes), not
        // O(span)): each such value was in `orig` and is now excluded, so it is a search-time
        // hole premise. A value that is also a hole of `orig` was never present, so guard on
        // `value in orig`.
        d.forEachHoleInRange(lo, hi) { value ->
            if (value in orig) {
                // Antecedent literals are the implication-clause form: the *negation* of each
                // currently-true premise, so each is currently false — exactly like the bound
                // entries above (¬[v ≥ min] / ¬[v ≤ max]). The hole premise is `v ≠ value`, so
                // the clause literal is the positive eq atom `[v == value]`. Storing the premise
                // itself (atomLitNe, currently true) would invert the recorded implication for
                // any consumer that reads the stored polarity directly.
                val lit = Lit.make(state.atomVarEq(v, value), true)
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
    val seen = IntHashSet(vars.size * 2) // pre-sized to the literal count to avoid rehash-grow during fill
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

/**
 * Weakest-bound relaxation of a [Linear] direction-aware reason — used for both the
 * whole-constraint **conflict** ([excludeIdx] = -1) and a per-variable bound **tighten**
 * ([excludeIdx] = the deduced var, which is omitted from the cited set). Same direction-aware
 * seed as [collectLinearDirAntecedents] (only the driving sum-side's bounds), but each cited
 * bound is relaxed toward its loosest value that still proves the deduction, distributing
 * [slack] (the room the driving sum has before the deduction would change) across the vars.
 *
 * A relaxed bound `[v ≥ k']` (k' below the current min) is cited as a **leaf** at the level its
 * min *first* reached k' (`minLevelForGe`) — strictly below the current level, so the analyzer
 * never resolves through it and the historical antecedent is irrelevant. The looser cited
 * bounds make the eventual learned clause strictly more general / reusable.
 *
 * Soundness: relaxing a cited bound moves the driving sum toward `bound` by `|c|·(loosening)`;
 * the total is capped at [slack], so the relaxed bounds still imply the original deduction
 * (conflict: sum still breaches `bound`; tighten: the rounded bound on the deduced var is
 * unchanged). [slack] MUST be a sound *under*-estimate of the true room — over-estimating drops
 * feasible solutions. Vars at their root bound are global facts (cited nothing); vars whose full
 * relaxation can't fit the remaining slack keep their current tight bound (existing behaviour,
 * real antecedent). [extraLit], when non-zero, is prepended (the reif aux context literal).
 *
 * Falls back to [collectLinearDirAntecedents] when `vars` repeats a variable (the per-entry
 * slack accounting assumes distinct vars).
 */
internal fun collectLinearRelaxedAntecedents(
    state: PropagationState,
    coeffs: IntArray,
    vars: IntArray,
    excludeIdx: Int,
    slack: Long,
    useLo: Boolean,
    extraLit: Int,
): IntArray? {
    // The per-var relaxation treats each entry independently; duplicate vars would double-spend
    // slack or under-cite, so defer to the unrelaxed direction-aware reason in that case.
    run {
        val seenVar = IntHashSet(vars.size)
        for (j in vars.indices) {
            if (j == excludeIdx) continue
            if (!seenVar.add(vars[j])) {
                return collectLinearDirAntecedents(state, coeffs, vars, excludeIdx, extraLit, useLo)
            }
        }
    }
    val currentLevel = state.currentLevel
    var remaining = if (slack < 0) 0 else slack
    val seen = IntHashSet(vars.size * 2) // pre-sized to the literal count to avoid rehash-grow during fill
    val out = IntArrayList()
    if (extraLit != 0) {
        out.add(extraLit)
        seen.add(extraLit)
    }
    for (j in vars.indices) {
        if (j == excludeIdx) continue
        val c = coeffs[j]
        if (c == 0) continue
        val v = vars[j]
        val absC = if (c < 0) -c.toLong() else c.toLong()
        val citeMin = if (useLo) c > 0 else c < 0
        if (citeMin) {
            val rootMin = state.problem.intDomains[v].min
            val curMin = state.intDomains[v].min
            if (curMin <= rootMin) continue // at root → global fact, cite nothing
            val kBelow = state.minBelowLevel(v, currentLevel) // loosest min at a level < current
            val cost = absC * (curMin - kBelow)
            if (cost <= remaining) {
                remaining -= cost
                if (kBelow > rootMin) {
                    val lit = Lit.make(state.atomBoundLeafIfNew(v, 0, kBelow, state.minLevelForGe(v, kBelow)), false)
                    if (seen.add(lit)) out.add(lit)
                } // else relaxed all the way to root → global fact, cite nothing
            } else {
                val lit = Lit.make(state.atomVarGe(v, curMin), false) // can't afford; keep tight bound
                if (seen.add(lit)) out.add(lit)
            }
        } else {
            val rootMax = state.problem.intDomains[v].max
            val curMax = state.intDomains[v].max
            if (curMax >= rootMax) continue
            val kAbove = state.maxAboveLevel(v, currentLevel)
            val cost = absC * (kAbove - curMax)
            if (cost <= remaining) {
                remaining -= cost
                if (kAbove < rootMax) {
                    val lit = Lit.make(state.atomBoundLeafIfNew(v, 1, kAbove, state.maxLevelForLe(v, kAbove)), false)
                    if (seen.add(lit)) out.add(lit)
                }
            } else {
                val lit = Lit.make(state.atomVarLe(v, curMax), false)
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
    val seen = IntHashSet(vars.size * 2) // pre-sized to the literal count to avoid rehash-grow during fill
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

/**
 * Above this arity the per-variable weakest-bound relaxation ([collectLinearRelaxedAntecedents])
 * becomes the dominant cost: a single [propagateLinearBounds] call can tighten O(arity) vars and
 * each relaxation is O(arity), so the reason work is O(arity²) per propagation — and it is pure
 * waste on the large set-partitioning / `int_lin_eq` systems that drive feasibility, which run
 * with essentially no conflicts (the reasons are never consumed). For such wide constraints we
 * fall back to a single shared start-of-call reason ([collectLinearStartBoundAntecedents], O(arity)
 * built once and reused across every tighten in the call). Below the threshold the relaxation is
 * cheap and its sharper, more reusable clauses are worth keeping, so small constraints are
 * unaffected.
 */
private const val LINEAR_SHARED_REASON_ARITY = 32

/**
 * A single O(arity) reason citing every variable's **start-of-call** driving bound on [useLo]'s
 * sum-side, reconstructed from the pre-tighten contributions [rLo]/[rHi] (so it is unaffected by
 * tightenings made earlier in the same [propagateLinearBounds] call). Every deduced bound in that
 * call is `floor`/`ceil` of a sum over exactly these start-of-call bounds, so this set soundly
 * implies all of them, and because the bounds predate every tighten it is acyclic. It cites the
 * deduced var's own start bound too (a harmless extra true literal — the deduction excludes it),
 * which keeps the array shareable across all tightens. Vars at their root bound are global facts
 * and cited nothing; [extraLit], when non-zero, is prepended.
 *
 * This is the wide-constraint counterpart to [collectLinearRelaxedAntecedents]: it drops the
 * weakest-bound relaxation (slightly less general learned clauses) in exchange for O(arity) total
 * instead of O(arity²) per propagation. See [LINEAR_SHARED_REASON_ARITY].
 */
internal fun collectLinearStartBoundAntecedents(
    state: PropagationState,
    coeffs: IntArray,
    vars: IntArray,
    rLo: LongArray,
    rHi: LongArray,
    useLo: Boolean,
    extraLit: Int,
): IntArray? {
    val out = IntArrayList()
    if (extraLit != 0) out.add(extraLit)
    for (j in vars.indices) {
        val c = coeffs[j]
        if (c == 0) continue
        val v = vars[j]
        // Start-of-call bounds recovered from the contribution ranges: rLo/rHi hold c·min and
        // c·max (ordered), so dividing by c (exact) recovers the pre-tighten min/max.
        val startMin = if (c > 0) rLo[j] / c else rHi[j] / c
        val startMax = if (c > 0) rHi[j] / c else rLo[j] / c
        val citeMin = if (useLo) c > 0 else c < 0
        if (citeMin) {
            if (startMin <= state.problem.intDomains[v].min) continue // at root → global fact
            out.add(Lit.make(state.atomVarGe(v, startMin.toInt()), false))
        } else {
            if (startMax >= state.problem.intDomains[v].max) continue
            out.add(Lit.make(state.atomVarLe(v, startMax.toInt()), false))
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
    // Wide constraints (set-partitioning / large int_lin_eq) use one shared start-of-call reason
    // per sum-side, built once and reused across every tighten — O(arity) total instead of the
    // relaxation's O(arity²). Narrow constraints keep the sharper per-var relaxation. See
    // [LINEAR_SHARED_REASON_ARITY] / [collectLinearStartBoundAntecedents].
    val wide = n > LINEAR_SHARED_REASON_ARITY
    var loBase: IntArray? = null
    var loBaseBuilt = false
    var hiBase: IntArray? = null
    var hiBaseBuilt = false
    fun loReason(i: Int, relax: Long): IntArray? {
        if (!wide) return collectLinearRelaxedAntecedents(state, coeffs, vars, i, relax, useLo = true, extraLit)
        if (!loBaseBuilt) {
            loBase = collectLinearStartBoundAntecedents(state, coeffs, vars, rLo, rHi, useLo = true, extraLit)
            loBaseBuilt = true
        }
        return loBase
    }
    fun hiReason(i: Int, relax: Long): IntArray? {
        if (!wide) return collectLinearRelaxedAntecedents(state, coeffs, vars, i, relax, useLo = false, extraLit)
        if (!hiBaseBuilt) {
            hiBase = collectLinearStartBoundAntecedents(state, coeffs, vars, rLo, rHi, useLo = false, extraLit)
            hiBaseBuilt = true
        }
        return hiBase
    }
    for (i in 0 until n) {
        val c = coeffs[i].toLong()
        if (c == 0L) continue
        val v = vars[i]
        val otherLo = sumLo - rLo[i]
        val otherHi = sumHi - rHi[i]
        if (op == LinearOp.LE || op == LinearOp.EQ) {
            // Upper-bound tighten driven by the lo side (Σ rLo of the other vars). The deduced
            // bound is `floor(slack0 / c)`; the lo-driving bounds may loosen until that floor
            // would increment — `c·(t+1)−1−slack0 ∈ [0, c−1]` is that (sound) room. c<0 uses 0.
            val slack0 = bound - otherLo
            if (c > 0) {
                val t = floorDivLong(slack0, c)
                val relax = c * (t + 1) - 1 - slack0
                if (!tightenMaxClamped(state, v, t, loReason(i, relax))) return false
            } else {
                if (!tightenMinClamped(state, v, ceilDivLong(slack0, c), loReason(i, 0L))) return false
            }
        }
        if (op == LinearOp.GE || op == LinearOp.EQ) {
            // Lower-bound tighten driven by the hi side (Σ rHi of the other vars). Symmetric
            // room: `needed − c·(t−1) − 1 ∈ [0, c−1]` for the ceil. c<0 uses 0.
            val needed = bound - otherHi
            if (c > 0) {
                val t = ceilDivLong(needed, c)
                val relax = needed - c * (t - 1) - 1
                if (!tightenMinClamped(state, v, t, hiReason(i, relax))) return false
            } else {
                if (!tightenMaxClamped(state, v, floorDivLong(needed, c), hiReason(i, 0L))) return false
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
