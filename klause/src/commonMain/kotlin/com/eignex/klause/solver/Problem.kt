package com.eignex.klause.solver

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.propagation.ClauseArena
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.extractConflictBools
import com.eignex.klause.propagation.extractConflictFactors
import com.eignex.klause.propagation.extractConflictInts
import com.eignex.klause.solver.intdomain.intDomainFromSurvivors
import com.eignex.klause.util.EmptyDoubleArray
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.MutableIntObjectMap
import com.eignex.klause.util.toSortedLongArray
import kotlin.time.Duration
import kotlin.time.TimeSource

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
open class Problem(
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
     * Skip the defensive copy of [intDomains]: when `true`, the passed array is shared as-is rather than
     * copied. Internal to [BakedProblem]'s already-folded construction (the incremental
     * [com.eignex.klause.presolve.PresolveSession] and the SMT/MPS front-ends supply a re-propagated array
     * read — never mutated — within one firing and rebuilt on the next change, so sharing saves an
     * O([numIntVars]) copy per firing). A raw [Problem] leaves this off and copies, so nothing it is
     * constructed from can alias its domains.
     */
    val sharedDomains: Boolean = false,
    /**
     * Number of LP-only continuous (real) variables; ids occupy `[0, numRealVars)` in a namespace
     * separate from the integer and Boolean ones. A real variable is present in the LP relaxation as a
     * continuous column but absent from CP search — it has no [intDomains] entry, no trail, and is never
     * branched. The simplex resolves it at nodes and leaves (see the LP-only-columns hybrid engine,
     * issue #1232). Zero for the pure integer/Boolean core, which every existing consumer builds.
     */
    val numRealVars: Int = 0,
    /** Lower bound of each real variable (length [numRealVars]); `Double.NEGATIVE_INFINITY` for open. */
    val realLower: DoubleArray = EmptyDoubleArray,
    /** Upper bound of each real variable (length [numRealVars]); `Double.POSITIVE_INFINITY` for open. */
    val realUpper: DoubleArray = EmptyDoubleArray,
    /**
     * Integer variables whose declared domain is genuinely open on the low side, indexed by int var id;
     * `null` (the common case) means every domain is a real declared bound.
     *
     * Search needs a finite box, so a front-end that cannot bound a side closes it at
     * [com.eignex.klause.config.KlauseConfig.unboundedSearchBound] and [intDomains] carries that clamp.
     * The clamp is an artefact, not a constraint: a refutation that leans on it holds only inside the
     * box. This records which sides were invented so the LP relaxation can build the column over its
     * true (open) range — where the simplex reasons soundly — while CP search keeps the finite domain.
     */
    val openIntLo: BooleanArray? = null,
    /** Integer variables genuinely open on the high side; see [openIntLo]. */
    val openIntHi: BooleanArray? = null,
) {
    /**
     * Domain (bounds) of each integer variable, indexed by int var id. On a raw [Problem] these are the
     * declared domains verbatim (a defensive copy of the constructor input); [BakedProblem] strengthens
     * them by folding in the root-level deductions from [baked] — bound tightenings, interior holes and
     * pins derived by one propagation fixpoint over the factors — so search/export consumers of a baked
     * problem start from a stronger, finite root even when the declared domains were loosely bounded.
     *
     * A [sharedDomains] construction skips the defensive copy: the caller supplies an array that is safe
     * to share (read, never mutated, within one presolve firing), saving an O([numIntVars]) copy.
     */
    val intDomains: Array<IntDomain> = if (sharedDomains) intDomains else intDomains.copyOf()

    /** Propagator objects for the CP engine, one per factor. Factors that have been structurally
     *  split return a dedicated propagator instance from [Factor.asPropagator]; unsplit factors
     *  return themselves. Built lazily on first access so presolve (which rebuilds a [Problem] after
     *  every pass) doesn't allocate one propagator per factor. Thread-safe init: parallel portfolio
     *  arms first-touch this concurrently on their own worker threads, so it must publish the array
     *  safely — same as [invariants]. Computed once, then cached. */
    val propagators: Array<out Propagator> by lazy { Array(factors.size) { factors[it].asPropagator() } }

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
        require(realLower.size == numRealVars && realUpper.size == numRealVars) {
            "real bound arrays (${realLower.size}/${realUpper.size}) != numRealVars $numRealVars"
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
        numRealVars: Int = 0,
        realLower: DoubleArray = EmptyDoubleArray,
        realUpper: DoubleArray = EmptyDoubleArray,
        openIntLo: BooleanArray? = null,
        openIntHi: BooleanArray? = null,
    ) : this(
        numBoolVars = numBoolVars,
        numIntVars = numIntVars,
        intDomains = intDomains,
        factors = Array(factors.size) { factors[it] },
        seedDeductions = seedDeductions,
        cancellation = cancellation,
        impliedFactorMask = impliedFactorMask,
        hasSymmetryBreaking = hasSymmetryBreaking,
        numRealVars = numRealVars,
        realLower = realLower,
        realUpper = realUpper,
        openIntLo = openIntLo,
        openIntHi = openIntHi,
    )

    /**
     * A copy with the integer domains replaced — used when a front-end's deferred bounding tightens the
     * open sides after parsing, before the problem flows into presolve. Every other structure (factors,
     * real bounds, implied/symmetry flags) is shared. The result is a raw [Problem] whose root bake is
     * still deferred; must not be called on a [BakedProblem], whose fold this copy would not reproduce.
     *
     * [newOpenLo] / [newOpenHi] record which sides of [newDomains] the bounding invented rather than
     * derived, so the LP relaxation can keep those columns open; `null` leaves the existing marks.
     */
    fun withIntDomains(
        newDomains: Array<IntDomain>,
        newOpenLo: BooleanArray? = null,
        newOpenHi: BooleanArray? = null,
    ): Problem {
        require(this !is BakedProblem) { "withIntDomains is for raw (front-end) problems only" }
        return Problem(
            numBoolVars = numBoolVars,
            numIntVars = numIntVars,
            intDomains = newDomains,
            factors = factors.asList(),
            impliedFactorMask = impliedFactorMask,
            hasSymmetryBreaking = hasSymmetryBreaking,
            numRealVars = numRealVars,
            realLower = realLower,
            realUpper = realUpper,
            openIntLo = newOpenLo ?: openIntLo,
            openIntHi = newOpenHi ?: openIntHi,
        )
    }

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
     * True iff this problem is a pure-Boolean CNF: no integer variables and every factor is a
     * [com.eignex.klause.factor.bool.Clause]. Such a problem never materialises an order-literal
     * atom, so the CDCL core degenerates to classical SAT — the native-SAT lane (#1119 Phase 1)
     * gates on this to run an arena-packed, atom-free BCP loop. Pseudo-Boolean and global-bearing
     * problems fail the gate and stay on the general LCG path. Computed once, then cached. */
    val isNativeSatEligible: Boolean by lazy(LazyThreadSafetyMode.NONE) {
        numIntVars == 0 && numBoolVars > 0 && factors.isNotEmpty() && factors.all { it is Clause }
    }

    /** Arena-packed clauses for the native-SAT lane, valid only when [isNativeSatEligible]. Built on
     *  first access and cached; the general LCG path never touches it, so nothing outside the native
     *  lane pays to construct it. */
    internal val clauseArena: ClauseArena by lazy(LazyThreadSafetyMode.NONE) { ClauseArena.of(this) }

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
        // The root bake skips firing expensive propagators (Table/Mdd/Regular/global/scheduling): their
        // heavy per-state bookkeeping is not built at load and their optional root tightening is deferred
        // to the first search fire, re-derived once on the final post-presolve factors. Only weakens the
        // bake fixpoint (always sound) — cheap bounds/clauses still reach fixpoint at load.
        mergeBase(propagate(Assumptions.None, cancellation, skipExpensiveBake = true), seedDeductions)
    }

    /** Wall time the root bake took on a [BakedProblem]: forcing [baked] (root propagation to fixpoint)
     *  and folding it into [intDomains]. Zero on a raw [Problem] (which never bakes) and on a
     *  [sharedDomains] baked problem (whose domains arrive already folded). Lets a front-end separate parse
     *  cost from bake cost when reporting load time. Set once by [BakedProblem]'s construction. */
    var bakeElapsed: Duration = Duration.ZERO
        protected set

    /**
     * Force the root bake and return the solve-ready [BakedProblem] — the only problem type the solvers,
     * the model counter, sampling and the LP engine accept. Idempotent: returns `this` when already a
     * [BakedProblem]. Otherwise constructs a [BakedProblem] over the same factors, folding the root-bake
     * deductions into its domains. [cancellation] budgets the fold: on a pathologically wide domain the
     * cheap bound-propagation fixpoint can grind, so the presolve pipeline threads its own (budget-capped)
     * cancellation here. A fired budget yields a sound *partial* bake (the fixpoint only ever tightens);
     * the deferred expensive propagators and the search re-derive the rest at the root.
     */
    fun bake(cancellation: Cancellation = this.cancellation): BakedProblem {
        if (this is BakedProblem) return this
        // A raw front-end/builder problem carries no seedDeductions (those come from a presolve rebuild,
        // which constructs its BakedProblem directly), so the bake is the plain base propagation.
        return BakedProblem(
            numBoolVars = numBoolVars,
            numIntVars = numIntVars,
            intDomains = Array(numIntVars) { intDomains[it] },
            factors = factors,
            cancellation = cancellation,
            impliedFactorMask = impliedFactorMask,
            hasSymmetryBreaking = hasSymmetryBreaking,
            numRealVars = numRealVars,
            realLower = realLower,
            realUpper = realUpper,
            openIntLo = openIntLo,
            openIntHi = openIntHi,
        )
    }

    /** Folds the root-level int deductions of a successful bake into [intDomains] so the
     *  tightened bounds are part of the problem itself rather than transient solver state.
     *  Bounds are applied before holes so every recorded hole is interior to the final
     *  bounds; pins collapse the domain to a singleton via the same hole-aware paths. */
    protected fun foldIntoDomains(result: PropagationResult) {
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
        skipExpensiveBake: Boolean = false,
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
        val conflict = state.runToFixpoint(
            allFactors = true,
            cancellation = cancellation,
            skipExpensiveBake = skipExpensiveBake,
        )
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
            // The survivor path enumerates every present value, so it requires an [IntDomain.enumerable]
            // domain. A wide contiguous (or huge-run) domain saturates [size] at Int.MAX — its
            // `holeCount` then looks positive though it is hole-free/few-holed; those fall through to
            // the bound + forEachHole path below (span-independent), never enumerating billions of values.
            if (span > KlauseConfig.current.bitsetThreshold && d.enumerable && d.size <= holeCount) {
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

/**
 * A [Problem] whose root bake is guaranteed to have run: its [Problem.intDomains] carry the
 * root-propagation fold, and it is the only problem type the solvers, the model counter,
 * sampling and the LP engine accept. Produced only by [Problem.bake] (or the presolve pipeline). A raw
 * [Problem] is the supertype, so handing an un-baked model to a solver is a compile error — the caller
 * must [Problem.bake] it first, which is where the parse-vs-solve boundary is enforced by the type system.
 */
class BakedProblem internal constructor(
    numBoolVars: Int,
    numIntVars: Int,
    intDomains: Array<IntDomain>,
    factors: Array<Factor>,
    seedDeductions: PropagationResult = PropagationResult.Implied.EMPTY,
    impliedFactorMask: BooleanArray? = null,
    hasSymmetryBreaking: Boolean = false,
    numRealVars: Int = 0,
    realLower: DoubleArray = EmptyDoubleArray,
    realUpper: DoubleArray = EmptyDoubleArray,
    openIntLo: BooleanArray? = null,
    openIntHi: BooleanArray? = null,
    cancellation: Cancellation = Cancellation.Never,
    /**
     * When `true`, [intDomains] already carry the root-bake fold (an incremental presolve pass view or a
     * presolve rebuild supplies its re-propagated array): share the array and skip the fold. When `false`
     * (the [Problem.bake] path), [intDomains] are the raw declared domains and this constructor folds the
     * base bake into them, exactly as construction used to.
     */
    alreadyFolded: Boolean = false,
) : Problem(
    numBoolVars = numBoolVars,
    numIntVars = numIntVars,
    intDomains = intDomains,
    factors = factors,
    seedDeductions = seedDeductions,
    cancellation = cancellation,
    impliedFactorMask = impliedFactorMask,
    hasSymmetryBreaking = hasSymmetryBreaking,
    sharedDomains = alreadyFolded,
    numRealVars = numRealVars,
    realLower = realLower,
    realUpper = realUpper,
    openIntLo = openIntLo,
    openIntHi = openIntHi,
) {
    init {
        if (!alreadyFolded) {
            val mark = TimeSource.Monotonic.markNow()
            foldIntoDomains(baked)
            bakeElapsed = mark.elapsedNow()
        }
    }

    /** Convenience overload taking factors as a [List] (stored as an [Array]); mirrors [Problem]'s. */
    internal constructor(
        numBoolVars: Int,
        numIntVars: Int,
        intDomains: Array<IntDomain>,
        factors: List<Factor>,
        seedDeductions: PropagationResult = PropagationResult.Implied.EMPTY,
        impliedFactorMask: BooleanArray? = null,
        hasSymmetryBreaking: Boolean = false,
        numRealVars: Int = 0,
        realLower: DoubleArray = EmptyDoubleArray,
        realUpper: DoubleArray = EmptyDoubleArray,
        cancellation: Cancellation = Cancellation.Never,
        alreadyFolded: Boolean = false,
    ) : this(
        numBoolVars = numBoolVars,
        numIntVars = numIntVars,
        intDomains = intDomains,
        factors = Array(factors.size) { factors[it] },
        seedDeductions = seedDeductions,
        impliedFactorMask = impliedFactorMask,
        hasSymmetryBreaking = hasSymmetryBreaking,
        numRealVars = numRealVars,
        realLower = realLower,
        realUpper = realUpper,
        cancellation = cancellation,
        alreadyFolded = alreadyFolded,
    )
}
