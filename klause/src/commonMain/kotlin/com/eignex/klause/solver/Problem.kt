package com.eignex.klause.solver

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.extractConflictBools
import com.eignex.klause.propagation.extractConflictFactors
import com.eignex.klause.propagation.extractConflictInts
import com.eignex.klause.solver.intdomain.intDomainFromSurvivors
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.MutableIntObjectMap
import com.eignex.klause.util.toSortedLongArray

/**
 * Immutable solver-side problem. Variables come in two id spaces:
 *  - Boolean vars: ids `[0, numBoolVars)`, packed bits in [Assignment].
 *  - Integer vars: ids `[0, numIntVars)`, raw [Int] values in [Assignment].
 *
 * Each integer variable has an [IntDomain] for bounds. Factors mention either or both.
 * Occurrence lists are split per kind so `flip(boolVar)` and `setInt(intVar)` only walk the
 * factors mentioning that specific variable.
 *
 * Float variables, when the schema or front-end uses them, are bucketed to integer
 * variables in the factor system (so [factors] stays pure int+bool).
 */
class Problem(
    /** Number of Boolean variables; ids occupy `[0, numBoolVars)`. */
    val numBoolVars: Int,
    /** Number of integer variables; ids occupy `[0, numIntVars)`. */
    val numIntVars: Int,
    /** Domain (bounds) of each integer variable as declared by the caller. */
    intDomains: Array<IntDomain>,
    /** The constraints over the variables. */
    val factors: Array<Factor>,
    /**
     * Extra root deductions computed outside the kernel — the failed-literal / SAC probing tiers now
     * live in [com.eignex.klause.presolve.RootBaker], which runs them against an already-base-baked
     * [Problem] and feeds the result back here. Merged into the base `propagate(Assumptions.None)`
     * bake before it folds into [intDomains], so the extra pins / bound tightenings / holes become
     * part of [baked] and the problem's own domains. Defaults to empty = base bake only; the kernel
     * never initiates probing itself (that would create a `solver → presolve → solver` cycle).
     */
    seedDeductions: PropagationResult = PropagationResult.Implied.EMPTY,
    /**
     * Cooperative-cancellation token for the construction-time bake ([baked]). Polled on the
     * full-propagation fixpoint and between SAC passes so a `-t` deadline can abort an
     * otherwise-uncancellable bake on a slow propagator over wide domains. The partial bake
     * that results is sound (it only ever tightens). Defaults to [Cancellation.Never], so
     * every consumer that doesn't pass a deadline bakes to completion exactly as before.
     */
    val cancellation: Cancellation = Cancellation.Never,
    /**
     * Per-factor flag marking constraints the model declared as *implied* — MiniZinc's
     * `redundant_constraint` / `symmetry_breaking_constraint` (surfaced via the klause MZN
     * library's annotation). Indexed parallel to [factors]; `null` (the common case) means no
     * factor is implied. Local search seeds these factors a lower initial violation weight so
     * the bulk of redundant / symmetry rows don't dominate the weighted-violation landscape
     * before the structural constraints are satisfied; every other consumer ignores it (the
     * constraints are still posted and propagate normally).
     */
    val impliedFactorMask: BooleanArray? = null,
    /**
     * True when the model declared at least one `symmetry_breaking_constraint`. Presolve uses
     * it to skip its own symmetry breaking ([com.eignex.klause.presolve.PresolvePass.BREAK_SYMMETRIES])
     * by default — stacking klause's automorphism break on top of the model's hand-written one
     * is redundant and the two can interact.
     */
    val hasSymmetryBreaking: Boolean = false,
    /**
     * Presolve pass-view mode. When `true`, this [Problem] is a cheap carrier of `(factors, intDomains)`
     * for a presolve pass that reads only those two: the eager derived structures — [propagators],
     * the occurrence lists, and the root [baked] fold — are deferred to first access and NOT forced at
     * construction, and [intDomains] are taken to be *already folded* (the incremental
     * [com.eignex.klause.presolve.PresolveSession] supplies its re-propagated domains). Off by
     * default: every solver/LS/LP consumer builds a normal [Problem] whose [baked] is forced eagerly at
     * construction exactly as before, so nothing outside presolve sees a behavioural change.
     */
    val preFolded: Boolean = false,
) {
    /**
     * Domain (bounds) of each integer variable, indexed by int var id. A defensive copy of
     * the constructor input, strengthened at construction by folding in the root-level
     * deductions from [baked]: bound tightenings, interior holes and pins derived by one
     * propagation fixpoint over the factors become the problem's own domains. Loosely
     * declared variables (e.g. unbounded ints flattened to machine-int spans) thus present
     * finite domains to every consumer — search engines start from a stronger root and
     * reference backends can represent constraints whose raw reachable ranges would
     * overflow their variable limits.
     *
     * A [preFolded] pass view skips the defensive copy: its domains are already the incremental
     * session's re-propagated array, read (never mutated) by a pass within a single firing and
     * rebuilt by the session on the next change, so sharing it saves an O(numIntVars) copy per firing.
     */
    val intDomains: Array<IntDomain> = if (preFolded) intDomains else intDomains.copyOf()

    /** Propagator objects for the CP engine, one per factor. Factors that have been structurally
     *  split return a dedicated propagator instance from [Factor.asPropagator]; unsplit factors
     *  return themselves. Computed once at construction. */
    val propagators: Array<out Propagator> by lazy(LazyThreadSafetyMode.NONE) {
        Array(factors.size) { factors[it].asPropagator() }
    }

    /** Invariant objects for the LS engine, one per factor. Factors that have been structurally
     *  split return a dedicated invariant instance from [Factor.asInvariant]; unsplit factors
     *  return themselves. Built lazily on first access: only the local-search engine reads them, so
     *  presolve (which rebuilds a [Problem] after every pass) and the backtrack/CP solver never pay
     *  to allocate one invariant per factor — material on a model with hundreds of thousands of
     *  factors. Computed once, then cached. */
    val invariants: Array<out Invariant> by lazy { Array(factors.size) { factors[it].asInvariant() } }

    init {
        require(intDomains.size == numIntVars) {
            "intDomains size ${intDomains.size} != numIntVars $numIntVars"
        }
        require(impliedFactorMask == null || impliedFactorMask.size == factors.size) {
            "impliedFactorMask size ${impliedFactorMask?.size} != factors size ${factors.size}"
        }
    }

    /**
     * Convenience overload taking factors as a [List]. Internally stored as an [Array] for
     * tighter hot-loop iteration; callers building a [MutableList] and then constructing the
     * problem can use this overload without converting first.
     */
    constructor(
        numBoolVars: Int,
        numIntVars: Int,
        intDomains: Array<IntDomain>,
        factors: List<Factor>,
        seedDeductions: PropagationResult = PropagationResult.Implied.EMPTY,
        cancellation: Cancellation = Cancellation.Never,
        impliedFactorMask: BooleanArray? = null,
        hasSymmetryBreaking: Boolean = false,
        preFolded: Boolean = false,
    ) : this(
        numBoolVars = numBoolVars,
        numIntVars = numIntVars,
        intDomains = intDomains,
        factors = Array(factors.size) { factors[it] },
        seedDeductions = seedDeductions,
        cancellation = cancellation,
        impliedFactorMask = impliedFactorMask,
        hasSymmetryBreaking = hasSymmetryBreaking,
        preFolded = preFolded,
    )

    /**
     * Variable → factor occurrence lists, split by engine (CP vs local search) and wakeup mode. The
     * LS-side lists force [invariants] lazily, so a presolve/CP-only problem never builds them. The
     * individual lists below delegate here; access them by name or through this index directly.
     */
    val occurrences: OccurrenceIndex by lazy(LazyThreadSafetyMode.NONE) {
        OccurrenceIndex(numBoolVars, numIntVars, factors, propagators) { invariants }
    }

    /** Deductive occurrence lists over Boolean variables. See [OccurrenceIndex.boolOccurrences]. */
    val boolOccurrences: Array<IntArray> get() = occurrences.boolOccurrences

    /** Deductive occurrence lists over integer variables. See [OccurrenceIndex.intOccurrences]. */
    val intOccurrences: Array<IntArray> get() = occurrences.intOccurrences

    /** Local-search occurrence lists over Boolean variables. See [OccurrenceIndex.lsBoolOccurrences]. */
    val lsBoolOccurrences: Array<IntArray> get() = occurrences.lsBoolOccurrences

    /** Local-search occurrence lists over integer variables. See [OccurrenceIndex.lsIntOccurrences]. */
    val lsIntOccurrences: Array<IntArray> get() = occurrences.lsIntOccurrences

    /** Occurrence-driven Boolean wakeup lists. See [OccurrenceIndex.nonBoolWatcherBoolOccurrences]. */
    val nonBoolWatcherBoolOccurrences: Array<IntArray> get() = occurrences.nonBoolWatcherBoolOccurrences

    /** True iff some factor opts into typed int-domain event wakeup. See [OccurrenceIndex.usesIntEventWatchers]. */
    val usesIntEventWatchers: Boolean get() = occurrences.usesIntEventWatchers

    /** True iff some factor consumes the per-factor dirty-variable delta.
     *  See [OccurrenceIndex.usesIntEventDeltaConsumers]. */
    val usesIntEventDeltaConsumers: Boolean get() = occurrences.usesIntEventDeltaConsumers

    /** Occurrence-driven int wakeup lists. See [OccurrenceIndex.nonIntEventWatcherIntOccurrences]. */
    val nonIntEventWatcherIntOccurrences: Array<IntArray> get() = occurrences.nonIntEventWatcherIntOccurrences

    /** Total number of factors. */
    val numFactors: Int get() = factors.size

    /**
     * Result of running [propagate] once with empty assumptions at construction time, merged with
     * any [seedDeductions] the presolve-lane probing supplied. Caches literals/values forced by the
     * constraints alone — every solver call gets a smaller residual problem with no per-call
     * propagation cost, and trivially-Unsat problems surface here instead of after a full search
     * budget. May be [PropagationResult.Unsat] for trivially-infeasible problems; callers that want
     * fail-fast behavior can check this.
     *
     * The deductions recorded here are also folded back into [intDomains], so the diff is
     * expressed relative to the constructor-input domains rather than the tightened ones.
     * Re-seeding it on an already-tightened domain is a no-op, so existing consumers that
     * replay [baked] as assumptions are unaffected.
     */
    val baked: PropagationResult by lazy(LazyThreadSafetyMode.NONE) {
        mergeBase(propagate(Assumptions.None, cancellation), seedDeductions)
    }

    // Force the root bake and fold its deductions into [intDomains] eagerly — except in [preFolded]
    // pass-view mode, where the domains are already folded and the deferred derived structures stay
    // uncomputed. A non-preFolded [Problem] thus behaves exactly as before (baked + folded at
    // construction); this init access is what forces the otherwise-lazy propagators/occurrences/baked.
    init {
        if (!preFolded) foldIntoDomains(baked)
    }

    /** Folds the root-level int deductions of a successful bake into [intDomains] so the
     *  tightened bounds are part of the problem itself rather than transient solver state.
     *  Bounds are applied before holes so every recorded hole is interior to the final
     *  bounds; pins collapse the domain to a singleton via the same hole-aware paths. */
    private fun foldIntoDomains(result: PropagationResult) {
        if (result !is PropagationResult.Implied) return
        result.forEachInt { v, value ->
            intDomains[v] = intDomains[v].withMinAtLeast(value).withMaxAtMost(value)
        }
        result.forEachIntMin { v, lo -> intDomains[v] = intDomains[v].withMinAtLeast(lo) }
        result.forEachIntMax { v, hi -> intDomains[v] = intDomains[v].withMaxAtMost(hi) }
        // Group the baked holes per variable and exclude each set in one merged pass. Applying a
        // wide hole set one value at a time rebuilds the hole array per value (O(holes^2)) — the
        // construction-time wedge on Element-heavy instances (#599). Holes are interior to the
        // bounds folded above, so excluding them never empties a domain of an Implied bake.
        val holesByVar = MutableIntObjectMap<LongArrayList>()
        result.forEachIntHole { v, value -> holesByVar.getOrPut(v) { LongArrayList() }.add(value) }
        holesByVar.forEach { v, holes ->
            val sorted = holes.toSortedLongArray()
            intDomains[v] = requireNotNull(intDomains[v].excludeValues(sorted)) {
                "baked holes emptied domain $v despite an Implied bake"
            }
        }
        // Wide-but-sparse reductions fold by rebuilding the domain from its survivor set directly —
        // O(survivors), never materializing the O(span) hole set the excludeValues path above would.
        result.forEachIntSet { v, survivors -> intDomains[v] = intDomainFromSurvivors(survivors) }
    }

    /** Merge the presolve-lane [seed] deductions into the kernel's [base] `propagate` bake. An
     *  `Unsat` on either side wins (an infeasible base or a probing-proven contradiction), otherwise
     *  the two implied sets union via [PropagationResult.Implied.merge]. The common no-probe case
     *  passes [PropagationResult.Implied.EMPTY] and returns [base] unchanged. */
    private fun mergeBase(base: PropagationResult, seed: PropagationResult): PropagationResult = when {
        base is PropagationResult.Unsat -> base

        seed is PropagationResult.Unsat -> seed

        // The common no-probe case seeds the shared empty sentinel — return the base bake untouched.
        // A non-sentinel seed can carry bound tightenings / holes with no pins, so `isEmpty` (which only
        // inspects pins) is not a safe skip: merge unconditionally.
        seed === PropagationResult.Implied.EMPTY -> base

        else -> (base as PropagationResult.Implied).merge(seed as PropagationResult.Implied)
    }

    /**
     * Run sound-but-incomplete deductive propagation against [assumptions]. Each factor's
     * [Propagator.propagate] is invoked to fixed point; pins / domain tightenings cascade through
     * the occurrence lists. Returns the literals/values forced *beyond* [assumptions] (disjoint
     * from the input), or [PropagationResult.Unsat] if a contradiction is derived.
     *
     * This is the same routine the solver uses internally at init and at every sample / solve
     * call that carries non-empty assumptions.
     */
    fun propagate(
        assumptions: Assumptions = Assumptions.None,
        cancellation: Cancellation = Cancellation.Never,
    ): PropagationResult {
        val state = PropagationState(this, assumptions)
        if (!state.seeded) {
            val lvls = state.conflictLevels ?: EmptyIntArray
            // Seed contradiction — no factor invocation was the trigger, so the factor
            // set stays empty (the assumption pair was the load-bearing input).
            return PropagationResult.Unsat(
                state.extractConflictBools(lvls),
                state.extractConflictInts(lvls),
                lvls,
            )
        }
        val conflict = state.runToFixpoint(allFactors = true, cancellation = cancellation)
        if (conflict != null) {
            return PropagationResult.Unsat(
                state.extractConflictBools(conflict),
                state.extractConflictInts(conflict),
                conflict,
                state.extractConflictFactors(),
            )
        }

        // Diff against input: only emit newly-forced facts. Iterates vars in ascending
        // id order so the resulting primitive arrays are pre-sorted (no separate sort).
        val bKeys = IntArrayList(initialCapacity = 8)
        val bVals = ArrayList<Boolean>()
        for (v in 0 until numBoolVars) {
            val b = state.boolValues[v] ?: continue
            if (assumptions.boolValueOrNull(v) == b) continue
            bKeys.add(v)
            bVals.add(b)
        }
        val iKeys = IntArrayList(initialCapacity = 8)
        val iVals = LongArrayList(initialCapacity = 8)
        val iMinKeys = IntArrayList(initialCapacity = 8)
        val iMinVals = LongArrayList(initialCapacity = 8)
        val iMaxKeys = IntArrayList(initialCapacity = 8)
        val iMaxVals = LongArrayList(initialCapacity = 8)
        val iHoleIds = IntArrayList(initialCapacity = 8)
        val iHoleVals = LongArrayList(initialCapacity = 8)
        val iSetKeys = IntArrayList(initialCapacity = 4)
        val iSetOffsets = IntArrayList(initialCapacity = 4).also { it.add(0) }
        val iSetVals = LongArrayList(initialCapacity = 8)
        for (v in 0 until numIntVars) {
            val d = state.intDomains[v]
            if (d.min == d.max) {
                if (assumptions.intValueOrNull(v) == d.min) continue
                iKeys.add(v)
                iVals.add(d.min)
                continue
            }
            // A wide-but-sparse reduction (more holes inside [min, max] than surviving values) records
            // its survivor set compactly instead of one interior hole per excluded value: for a domain
            // spanning up to millions but holding a handful of values, per-value holes are O(span) to emit
            // here and to re-apply when seeding. Restricting to the survivors reproduces the identical
            // folded domain. Gated on a span past [KlauseConfig.bitsetThreshold] — the width klause already
            // treats as too wide for a bitset — so narrow reductions keep the plain hole path (their holes
            // are few and cheap, and the hole path carries the pre-existing-seed dedup the survivor path
            // omits). A full contiguous domain has `size == span`, so `size <= holes` excludes it anyway.
            val span = d.max - d.min + 1
            val holeCount = span - d.size
            // The survivor path enumerates every present value, so it is only valid when [size] is a
            // genuine small count. A wide contiguous (or huge-run) domain saturates [size] at Int.MAX —
            // its `holeCount` then looks positive though it is hole-free/few-holed; those fall through to
            // the bound + forEachHole path below (span-independent), never enumerating billions of values.
            if (span > KlauseConfig.current.bitsetThreshold && d.size < Int.MAX_VALUE && d.size <= holeCount) {
                iSetKeys.add(v)
                d.forEach { iSetVals.add(it) }
                iSetOffsets.add(iSetVals.size)
                continue
            }
            // Non-singleton: emit bound tightenings relative to the effective seed bounds.
            val orig = intDomains[v]
            val seedMin = maxOf(orig.min, assumptions.deductions.intMinOrNull(v) ?: Long.MIN_VALUE)
            val seedMax = minOf(orig.max, assumptions.deductions.intMaxOrNull(v) ?: Long.MAX_VALUE)
            if (d.min > seedMin) {
                iMinKeys.add(v)
                iMinVals.add(d.min)
            }
            if (d.max < seedMax) {
                iMaxKeys.add(v)
                iMaxVals.add(d.max)
            }
            // Interior holes: values [d] excludes strictly inside its bounds that [orig] still held.
            // Walk [d]'s actual holes — span-independent for the wide reps — instead of every value
            // in `(d.min, d.max)`: a wide contiguous domain (spans reaching billions here) has none,
            // so this is O(holes) rather than O(max − min), which otherwise dominates the whole bake.
            // Skip values already in the seed assumption's hole set so only newly-derived holes emit.
            d.forEachHole { value ->
                if (value in orig) {
                    var preExisting = false
                    for (i in 0 until assumptions.deductions.intHoleVarIds.size) {
                        if (assumptions.deductions.intHoleVarIds[i] == v &&
                            assumptions.deductions.intHoleValues[i] == value
                        ) {
                            preExisting = true
                            break
                        }
                    }
                    if (!preExisting) {
                        iHoleIds.add(v)
                        iHoleVals.add(value)
                    }
                }
            }
        }
        return PropagationResult.Implied(
            boolKeys = bKeys.toIntArray(),
            boolValues = BooleanArray(bVals.size) { bVals[it] },
            intKeys = iKeys.toIntArray(),
            intValues = iVals.toLongArray(),
            intMinKeys = iMinKeys.toIntArray(),
            intMinValues = iMinVals.toLongArray(),
            intMaxKeys = iMaxKeys.toIntArray(),
            intMaxValues = iMaxVals.toLongArray(),
            intHoleVarIds = iHoleIds.toIntArray(),
            intHoleValues = iHoleVals.toLongArray(),
            intSetKeys = iSetKeys.toIntArray(),
            intSetOffsets = if (iSetKeys.isEmpty()) EmptyIntArray else iSetOffsets.toIntArray(),
            intSetValues = iSetVals.toLongArray(),
        )
    }
}
