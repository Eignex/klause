package com.eignex.klause.solver

import com.eignex.klause.solver.propagation.IntEvent
import com.eignex.klause.solver.propagation.PropagationResult
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.solver.propagation.extractConflictBools
import com.eignex.klause.solver.propagation.extractConflictFactors
import com.eignex.klause.solver.propagation.extractConflictInts
import com.eignex.klause.util.IntArrayList
import kotlin.random.Random

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
     * Opt-in failed-literal probing at bake time. When `true`, every free bool variable is
     * tested with both polarities: if pinning one polarity propagates Unsat, the other
     * polarity is permanently folded into [baked]. Iterated to a fixed point. Cost is
     * `O(numFreeBools × propagate)` once at construction; the result is a tighter baseline
     * for every subsequent session. Off by default — tests construct many small problems
     * and don't want the construction overhead.
     */
    val probeFailedLiterals: Boolean = false,
    /**
     * Opt-in bound-SAC (singleton arc consistency) probing at bake time. After
     * [probeFailedLiterals] settles, every int var with a multi-value domain has its
     * min and max probed: pin the bound, propagate, and if Unsat, tighten the bound by
     * one and loop. Captures bound-level deductions the per-call propagator misses
     * because they require hypothetical reasoning across factors. Cost is
     * `O(Σ |dom(v)|_extreme × propagate)`; result rides in [baked] as bound
     * tightenings (non-singleton) and pins (when SAC narrows a var to a single value).
     * Interior-hole SAC (probing values strictly between min and max) is left for a
     * follow-up — it needs Implied/Assumptions to carry hole sets too.
     */
    val probeIntBounds: Boolean = false,
    /**
     * Opt-in interior-hole SAC. Builds on [probeIntBounds]: after bound-SAC settles,
     * each multi-value int var has its interior values (strictly between current min
     * and max) probed; on Unsat the value is recorded as an interior hole in [baked].
     * Cost is `O(Σ |dom(v)| × propagate)` per pass; use sparingly on large domains.
     * Implies [probeIntBounds].
     */
    val probeIntHoles: Boolean = false,
    /**
     * Cap on per-var probe calls during bake-time SAC. After this many `propagate` calls
     * targeting one var (across both bound and hole probing), the loop stops probing
     * that var for the remainder of the bake. Defaults to unlimited; set to a small
     * positive number for very wide domains to prevent pathological construction cost.
     */
    val probeBudgetPerVar: Int = Int.MAX_VALUE,
    /**
     * Cap on total probe calls across all vars and all SAC passes during bake. Once
     * exceeded, the SAC loops exit gracefully with whatever tightenings they've
     * accumulated so far. Unlimited by default.
     */
    val probeTotalBudget: Int = Int.MAX_VALUE,
    /**
     * Seed for the RNG that breaks ties in the wdeg-weighted SAC probe order. Equal-score
     * vars get a random permutation each outer pass so a fixed-point loop on a flat
     * weight landscape doesn't keep visiting the same first var. Deterministic for a
     * given seed.
     */
    val probeSeed: Long = 0L,
    /**
     * Cooperative-cancellation token for the construction-time bake ([baked]). Polled on the
     * full-propagation fixpoint and between SAC passes so a `-t` deadline can abort an
     * otherwise-uncancellable bake on a slow propagator over wide domains. The partial bake
     * that results is sound (it only ever tightens). Defaults to [Cancellation.Never], so
     * every consumer that doesn't pass a deadline bakes to completion exactly as before.
     */
    val cancellation: Cancellation = Cancellation.Never,
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
     */
    val intDomains: Array<IntDomain> = intDomains.copyOf()

    init {
        require(intDomains.size == numIntVars) {
            "intDomains size ${intDomains.size} != numIntVars $numIntVars"
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
        probeFailedLiterals: Boolean = false,
        probeIntBounds: Boolean = false,
        probeIntHoles: Boolean = false,
        probeBudgetPerVar: Int = Int.MAX_VALUE,
        probeTotalBudget: Int = Int.MAX_VALUE,
        probeSeed: Long = 0L,
        cancellation: Cancellation = Cancellation.Never,
    ) : this(
        numBoolVars = numBoolVars,
        numIntVars = numIntVars,
        intDomains = intDomains,
        factors = Array(factors.size) { factors[it] },
        probeFailedLiterals = probeFailedLiterals,
        probeIntBounds = probeIntBounds,
        probeIntHoles = probeIntHoles,
        probeBudgetPerVar = probeBudgetPerVar,
        probeTotalBudget = probeTotalBudget,
        probeSeed = probeSeed,
        cancellation = cancellation,
    )

    /** Factor ids mentioning each Boolean variable, indexed by bool var id. */
    val boolOccurrences: Array<IntArray> = invert(numBoolVars) { it.boolVars }

    /** Factor ids mentioning each integer variable, indexed by int var id. */
    val intOccurrences: Array<IntArray> = invert(numIntVars) { it.intVars }

    /**
     * [boolOccurrences] minus factors that use per-literal wakeup (see
     * [Factor.initialBoolWatchers]). The propagation engine walks this list for
     * occurrence-driven wakeup, while watcher-using factors are woken via the
     * per-state [com.eignex.klause.solver.propagation.PropagationState.boolWatchersByLit]
     * index instead. Identical to [boolOccurrences] when no factor opts in.
     */
    val nonBoolWatcherBoolOccurrences: Array<IntArray> = run {
        val watcherFid = BooleanArray(factors.size)
        var any = false
        for (i in factors.indices) {
            if (factors[i].initialBoolWatchers != null) {
                watcherFid[i] = true
                any = true
            }
        }
        if (!any) {
            boolOccurrences
        } else {
            Array(numBoolVars) { v ->
                boolOccurrences[v].filter { !watcherFid[it] }.toIntArray()
            }
        }
    }

    /** True iff some factor opts into typed int-domain event wakeup
     *  ([Factor.initialIntEventWatches]). When false, the engine skips all int-event bookkeeping
     *  and [nonIntEventWatcherIntOccurrences] aliases [intOccurrences]. */
    val usesIntEventWatchers: Boolean = factors.any { it.initialIntEventWatches != null }

    /** True iff some factor consumes the per-factor dirty-variable delta ([Factor.consumesIntEventDelta]).
     *  When false the engine allocates no delta accumulators and the dirty-var bookkeeping is skipped. */
    val usesIntEventDeltaConsumers: Boolean = factors.any { it.consumesIntEventDelta }

    /**
     * [intOccurrences] minus, per variable, the factors that subscribe to a typed int-event on
     * *that* variable (see [Factor.initialIntEventWatches]). The propagation engine walks this list
     * for occurrence-driven int wakeup; a subscribing factor is woken for its subscribed variables
     * via the per-`(var, kind)`
     * [com.eignex.klause.solver.propagation.PropagationState.intEventWatchersBySlot] index instead.
     *
     * Exclusion is per `(factor, variable)`, not all-or-nothing: a factor that subscribes to events
     * on variable `a` but not `b` (both in its [Factor.intVars]) is dropped from `a`'s list yet kept
     * on `b`'s, so `b` still wakes it the normal way. Identical to [intOccurrences] when no factor
     * opts in.
     */
    val nonIntEventWatcherIntOccurrences: Array<IntArray> = if (!usesIntEventWatchers) {
        intOccurrences
    } else {
        Array(numIntVars) { v ->
            intOccurrences[v].filter { fid ->
                val watches = factors[fid].initialIntEventWatches
                watches == null || watches.none { IntEvent.intVarOf(it) == v }
            }.toIntArray()
        }
    }

    /** Total number of factors. */
    val numFactors: Int get() = factors.size

    /**
     * Result of running [propagate] once with empty assumptions at construction time. Caches
     * literals/values forced by the constraints alone — every solver call gets a smaller
     * residual problem with no per-call propagation cost, and trivially-Unsat problems surface
     * here instead of after a full search budget. May be [PropagationResult.Unsat] for
     * trivially-infeasible problems; callers that want fail-fast behavior can check this.
     *
     * The deductions recorded here are also folded back into [intDomains], so the diff is
     * expressed relative to the constructor-input domains rather than the tightened ones.
     * Re-seeding it on an already-tightened domain is a no-op, so existing consumers that
     * replay [baked] as assumptions are unaffected.
     */
    val baked: PropagationResult = computeBaked().also(::foldIntoDomains)

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
        val holesByVar = HashMap<Int, IntArrayList>()
        result.forEachIntHole { v, value -> holesByVar.getOrPut(v) { IntArrayList() }.add(value) }
        for ((v, holes) in holesByVar) {
            val sorted = holes.toIntArray().also { it.sort() }
            intDomains[v] = requireNotNull(intDomains[v].excludeValues(sorted)) {
                "baked holes emptied domain $v despite an Implied bake"
            }
        }
    }

    private fun computeBaked(): PropagationResult {
        val initial = propagate(Assumptions.None, cancellation)
        if (initial is PropagationResult.Unsat) return initial
        var result: PropagationResult = initial
        // SAC probing fires `propagate` repeatedly; stop entering new probe phases once the
        // bake deadline has passed (each phase below also polls between its own probes).
        if (cancellation()) return result
        if (probeFailedLiterals) {
            result = probeFreeBools(result as PropagationResult.Implied)
            if (result is PropagationResult.Unsat) return result
        }
        if (probeIntBounds || probeIntHoles) {
            // wdeg state shared across bound-SAC and hole-SAC: a probe failure under bound-SAC
            // raises factor weights that then steer hole-SAC's first iteration, and vice versa.
            val factorWeights = DoubleArray(factors.size) { 1.0 }
            val rng = Random(probeSeed)
            result = probeBoundSac(result as PropagationResult.Implied, factorWeights, rng)
            if (result is PropagationResult.Unsat) return result
            if (probeIntHoles) {
                result = probeIntHoles(result as PropagationResult.Implied, factorWeights, rng)
            }
        }
        return result
    }

    /** Interior-value SAC: probe every value strictly between each multi-value int var's
     *  current min and max. Iterates with bound-SAC interleaved so that hole-discovered
     *  tightenings can lift bounds and vice versa. */
    private fun probeIntHoles(
        base: PropagationResult.Implied,
        factorWeights: DoubleArray,
        rng: Random,
    ): PropagationResult {
        var acc: PropagationResult.Implied = base
        val perVarCalls = IntArray(numIntVars)
        var totalCalls = 0
        var changed = true
        while (changed) {
            changed = false
            for (v in sacProbeOrder(acc, factorWeights, rng)) {
                if (cancellation()) return acc
                if (acc.intValueOrNull(v) != null) continue
                if (perVarCalls[v] >= probeBudgetPerVar) continue
                if (totalCalls >= probeTotalBudget) return acc
                val orig = intDomains[v]
                val curMin = acc.intMinOrNullCompat(v) ?: orig.min
                val curMax = acc.intMaxOrNullCompat(v) ?: orig.max
                if (curMin >= curMax) continue
                val accAsAssumptions = acc.toAssumptions()
                // Build a per-var hole-set once so the `alreadyHole` lookup in the k-loop
                // is O(1) instead of a linear scan of acc.intHoleVarIds for every probed k.
                val existingHoles = HashSet<Int>()
                for (i in 0 until acc.intHoleVarIds.size) {
                    if (acc.intHoleVarIds[i] == v) existingHoles.add(acc.intHoleValues[i])
                }
                for (k in (curMin + 1) until curMax) {
                    if (perVarCalls[v] >= probeBudgetPerVar) break
                    if (totalCalls >= probeTotalBudget) return acc
                    if (k !in orig) continue
                    if (k in existingHoles) continue
                    perVarCalls[v]++
                    totalCalls++
                    val pin = propagate(accAsAssumptions.withInt(v, k), cancellation)
                    if (pin is PropagationResult.Unsat) {
                        bumpFactorWeights(pin, factorWeights)
                        perVarCalls[v]++
                        totalCalls++
                        val r = propagate(accAsAssumptions.withIntHole(v, k), cancellation)
                        if (r is PropagationResult.Unsat) return r
                        acc = addHoleToImplied(acc, v, k)
                        acc = mergeImplied(acc, r as PropagationResult.Implied)
                        changed = true
                    }
                }
            }
        }
        return acc
    }

    private fun addHoleToImplied(a: PropagationResult.Implied, v: Int, value: Int): PropagationResult.Implied {
        val holeSet = HashSet<Long>()
        a.forEachIntHole { id, vv ->
            holeSet.add((id.toLong() shl 32) or (vv.toLong() and 0xFFFFFFFFL))
        }
        holeSet.add((v.toLong() shl 32) or (value.toLong() and 0xFFFFFFFFL))
        val sorted = holeSet.toLongArray().also { it.sort() }
        val ids = IntArray(sorted.size) { (sorted[it] ushr 32).toInt() }
        val vals = IntArray(sorted.size) { sorted[it].toInt() }
        val mins = HashMap<Int, Int>()
        a.forEachIntMin { k, vv -> mins[k] = vv }
        val maxes = HashMap<Int, Int>()
        a.forEachIntMax { k, vv -> maxes[k] = vv }
        val minK = mins.keys.toIntArray().also { it.sort() }
        val maxK = maxes.keys.toIntArray().also { it.sort() }
        return PropagationResult.Implied(
            bools = a.bools,
            ints = a.ints,
            intMinKeys = minK,
            intMinValues = IntArray(minK.size) { mins.getValue(minK[it]) },
            intMaxKeys = maxK,
            intMaxValues = IntArray(maxK.size) { maxes.getValue(maxK[it]) },
            intHoleVarIds = ids,
            intHoleValues = vals,
        )
    }

    /** Probe-order heuristic: wdeg / dom — sort descending by `Σ factorWeights[f] / dom(v)`
     *  so the budget is spent first on vars that are heavily-constrained relative to their
     *  remaining domain. Each Unsat probe bumps the weights of the factors in its conflict
     *  via [bumpFactorWeights], so failing probes steer the next pass toward related vars
     *  (the classic wdeg adaptation). Ties break by a per-pass random key (deterministic
     *  for a given [probeSeed]) — this avoids the deterministic-id-order bias that the
     *  prior dom-sized order would inherit when every var has the same dom and weight. */
    private fun sacProbeOrder(acc: PropagationResult.Implied, factorWeights: DoubleArray, rng: Random): IntArray {
        val scores = DoubleArray(numIntVars) { v ->
            if (acc.intValueOrNull(v) != null) return@DoubleArray Double.NEGATIVE_INFINITY
            val orig = intDomains[v]
            val lo = acc.intMinOrNullCompat(v) ?: orig.min
            val hi = acc.intMaxOrNullCompat(v) ?: orig.max
            val dom = (hi - lo + 1).coerceAtLeast(1)
            var wdeg = 0.0
            val occ = intOccurrences[v]
            for (i in occ.indices) wdeg += factorWeights[occ[i]]
            wdeg / dom
        }
        val tie = IntArray(numIntVars) { rng.nextInt() }
        val boxed = Array(numIntVars) { it }
        boxed.sortWith(
            Comparator { a, b ->
                val sa = scores[a]
                val sb = scores[b]
                if (sa != sb) sb.compareTo(sa) else tie[a].compareTo(tie[b])
            },
        )
        return IntArray(numIntVars) { boxed[it] }
    }

    /** Bump every factor implicated in an Unsat conflict by 1.0. This is the wdeg update
     *  rule: factors that repeatedly fail under hypothetical pins gain weight and steer
     *  the SAC probe order toward the vars they mention on subsequent passes. */
    private fun bumpFactorWeights(unsat: PropagationResult.Unsat, factorWeights: DoubleArray) {
        for (f in unsat.conflictFactors) {
            if (f in factorWeights.indices) factorWeights[f] += 1.0
        }
    }

    /**
     * Bound-SAC fixed-point loop. Probes the min and max of each multi-value int var
     * under the current [base]; an Unsat result lets us tighten that bound by one
     * and re-probe. Returns the strengthened [PropagationResult.Implied], or `Unsat`
     * if the problem turns out to be infeasible.
     */
    private fun probeBoundSac(
        base: PropagationResult.Implied,
        factorWeights: DoubleArray,
        rng: Random,
    ): PropagationResult {
        var acc: PropagationResult.Implied = base
        val perVarCalls = IntArray(numIntVars)
        var totalCalls = 0
        var changed = true
        while (changed) {
            changed = false
            for (v in sacProbeOrder(acc, factorWeights, rng)) {
                if (cancellation()) return acc
                if (acc.intValueOrNull(v) != null) continue
                if (perVarCalls[v] >= probeBudgetPerVar) continue
                if (totalCalls >= probeTotalBudget) return acc
                val orig = intDomains[v]
                val curMin = acc.intMinOrNullCompat(v) ?: orig.min
                val curMax = acc.intMaxOrNullCompat(v) ?: orig.max
                if (curMin >= curMax) continue
                val accAsAssumptions = acc.toAssumptions()
                perVarCalls[v]++
                totalCalls++
                val pinMin = propagate(accAsAssumptions.withInt(v, curMin), cancellation)
                if (pinMin is PropagationResult.Unsat) {
                    bumpFactorWeights(pinMin, factorWeights)
                    perVarCalls[v]++
                    totalCalls++
                    val tightened = accAsAssumptions.withTightenedMin(v, curMin + 1)
                    val r = propagate(tightened, cancellation)
                    if (r is PropagationResult.Unsat) return r
                    acc = addMinToImplied(acc, v, curMin + 1)
                    acc = mergeImplied(acc, r as PropagationResult.Implied)
                    changed = true
                    continue
                }
                if (perVarCalls[v] >= probeBudgetPerVar) continue
                if (totalCalls >= probeTotalBudget) return acc
                perVarCalls[v]++
                totalCalls++
                val pinMax = propagate(accAsAssumptions.withInt(v, curMax), cancellation)
                if (pinMax is PropagationResult.Unsat) {
                    bumpFactorWeights(pinMax, factorWeights)
                    perVarCalls[v]++
                    totalCalls++
                    val tightened = accAsAssumptions.withTightenedMax(v, curMax - 1)
                    val r = propagate(tightened, cancellation)
                    if (r is PropagationResult.Unsat) return r
                    acc = addMaxToImplied(acc, v, curMax - 1)
                    acc = mergeImplied(acc, r as PropagationResult.Implied)
                    changed = true
                }
            }
        }
        return acc
    }

    private fun addMinToImplied(a: PropagationResult.Implied, v: Int, newMin: Int): PropagationResult.Implied {
        val mins = HashMap<Int, Int>()
        a.forEachIntMin { k, vv -> mins[k] = vv }
        mins[v] = maxOf(mins[v] ?: Int.MIN_VALUE, newMin)
        val maxes = HashMap<Int, Int>()
        a.forEachIntMax { k, vv -> maxes[k] = vv }
        val minK = mins.keys.toIntArray().also { it.sort() }
        val maxK = maxes.keys.toIntArray().also { it.sort() }
        return PropagationResult.Implied(
            bools = a.bools,
            ints = a.ints,
            intMinKeys = minK,
            intMinValues = IntArray(minK.size) { mins.getValue(minK[it]) },
            intMaxKeys = maxK,
            intMaxValues = IntArray(maxK.size) { maxes.getValue(maxK[it]) },
            intHoleVarIds = a.intHoleVarIds.copyOf(),
            intHoleValues = a.intHoleValues.copyOf(),
        )
    }

    private fun addMaxToImplied(a: PropagationResult.Implied, v: Int, newMax: Int): PropagationResult.Implied {
        val mins = HashMap<Int, Int>()
        a.forEachIntMin { k, vv -> mins[k] = vv }
        val maxes = HashMap<Int, Int>()
        a.forEachIntMax { k, vv -> maxes[k] = vv }
        maxes[v] = minOf(maxes[v] ?: Int.MAX_VALUE, newMax)
        val minK = mins.keys.toIntArray().also { it.sort() }
        val maxK = maxes.keys.toIntArray().also { it.sort() }
        return PropagationResult.Implied(
            bools = a.bools,
            ints = a.ints,
            intMinKeys = minK,
            intMinValues = IntArray(minK.size) { mins.getValue(minK[it]) },
            intMaxKeys = maxK,
            intMaxValues = IntArray(maxK.size) { maxes.getValue(maxK[it]) },
            intHoleVarIds = a.intHoleVarIds.copyOf(),
            intHoleValues = a.intHoleValues.copyOf(),
        )
    }

    /** Union two `Implied`s by replaying everything from `b` into the `a` base. */
    private fun mergeImplied(a: PropagationResult.Implied, b: PropagationResult.Implied): PropagationResult.Implied {
        val bools = HashMap(a.bools)
        b.forEachBool { k, v -> bools[k] = v }
        val ints = HashMap(a.ints)
        b.forEachInt { k, v -> ints[k] = v }
        val mins = HashMap<Int, Int>()
        a.forEachIntMin { k, v -> mins[k] = v }
        b.forEachIntMin { k, v -> mins[k] = maxOf(mins[k] ?: Int.MIN_VALUE, v) }
        val maxes = HashMap<Int, Int>()
        a.forEachIntMax { k, v -> maxes[k] = v }
        b.forEachIntMax { k, v -> maxes[k] = minOf(maxes[k] ?: Int.MAX_VALUE, v) }
        // Holes — union.
        val holes = HashSet<Long>()
        a.forEachIntHole { id, v -> holes.add((id.toLong() shl 32) or (v.toLong() and 0xFFFFFFFFL)) }
        b.forEachIntHole { id, v -> holes.add((id.toLong() shl 32) or (v.toLong() and 0xFFFFFFFFL)) }
        // Drop now-pinned vars from the bound and hole sets.
        for (k in ints.keys) {
            mins.remove(k)
            maxes.remove(k)
            holes.removeAll { (it ushr 32).toInt() == k }
        }
        val minK = mins.keys.toIntArray().also { it.sort() }
        val maxK = maxes.keys.toIntArray().also { it.sort() }
        val holesSorted = holes.toLongArray().also { it.sort() }
        val holeIds = IntArray(holesSorted.size) { (holesSorted[it] ushr 32).toInt() }
        val holeVals = IntArray(holesSorted.size) { holesSorted[it].toInt() }
        return PropagationResult.Implied(
            bools = bools,
            ints = ints,
            intMinKeys = minK,
            intMinValues = IntArray(minK.size) { mins.getValue(minK[it]) },
            intMaxKeys = maxK,
            intMaxValues = IntArray(maxK.size) { maxes.getValue(maxK[it]) },
            intHoleVarIds = holeIds,
            intHoleValues = holeVals,
        )
    }

    /**
     * Iteratively strengthens [initial] by probing each free bool with both polarities. If
     * one polarity is Unsat under the current accumulated base, the opposite polarity is
     * forced. Repeats until a full pass finds no new forcings.
     */
    private fun probeFreeBools(initial: PropagationResult.Implied): PropagationResult {
        val bools = HashMap(initial.bools)
        val ints = HashMap(initial.ints)
        var changed = true
        while (changed) {
            changed = false
            for (v in 0 until numBoolVars) {
                if (cancellation()) return PropagationResult.Implied(bools, ints)
                if (v in bools) continue
                val tryTrue = propagate(Assumptions(bools + (v to true), ints), cancellation)
                if (tryTrue is PropagationResult.Unsat) {
                    val r = propagate(Assumptions(bools + (v to false), ints), cancellation)
                    if (r is PropagationResult.Unsat) return r
                    foldInto(bools, ints, v, false, r as PropagationResult.Implied)
                    changed = true
                    continue
                }
                val tryFalse = propagate(Assumptions(bools + (v to false), ints), cancellation)
                if (tryFalse is PropagationResult.Unsat) {
                    val r = propagate(Assumptions(bools + (v to true), ints), cancellation)
                    if (r is PropagationResult.Unsat) return r
                    foldInto(bools, ints, v, true, r as PropagationResult.Implied)
                    changed = true
                }
            }
        }
        return PropagationResult.Implied(bools, ints)
    }

    private fun foldInto(
        bools: HashMap<Int, Boolean>,
        ints: HashMap<Int, Int>,
        forcedVar: Int,
        forcedValue: Boolean,
        implied: PropagationResult.Implied,
    ) {
        bools[forcedVar] = forcedValue
        implied.forEachBool { k, b -> bools[k] = b }
        implied.forEachInt { k, i -> ints[k] = i }
    }

    /**
     * Run sound-but-incomplete deductive propagation against [assumptions]. Each factor's
     * [Factor.propagate] is invoked to fixed point; pins / domain tightenings cascade through
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
        val iVals = IntArrayList(initialCapacity = 8)
        val iMinKeys = IntArrayList(initialCapacity = 8)
        val iMinVals = IntArrayList(initialCapacity = 8)
        val iMaxKeys = IntArrayList(initialCapacity = 8)
        val iMaxVals = IntArrayList(initialCapacity = 8)
        val iHoleIds = IntArrayList(initialCapacity = 8)
        val iHoleVals = IntArrayList(initialCapacity = 8)
        for (v in 0 until numIntVars) {
            val d = state.intDomains[v]
            if (d.min == d.max) {
                if (assumptions.intValueOrNull(v) == d.min) continue
                iKeys.add(v)
                iVals.add(d.min)
                continue
            }
            // Non-singleton: emit bound tightenings relative to the effective seed bounds.
            val orig = intDomains[v]
            val seedMin = maxOf(orig.min, assumptions.intMinOrNull(v) ?: Int.MIN_VALUE)
            val seedMax = minOf(orig.max, assumptions.intMaxOrNull(v) ?: Int.MAX_VALUE)
            if (d.min > seedMin) {
                iMinKeys.add(v)
                iMinVals.add(d.min)
            }
            if (d.max < seedMax) {
                iMaxKeys.add(v)
                iMaxVals.add(d.max)
            }
            // Interior holes: values strictly between current (d.min, d.max) that are in
            // [orig] but absent from [d]. Skip values already in the seed assumption's
            // hole set so we only emit newly-derived ones.
            for (value in (d.min + 1) until d.max) {
                if (value in orig && value !in d) {
                    // Cheap O(n) skip if same hole present in seed.
                    var preExisting = false
                    for (i in 0 until assumptions.intHoleVarIds.size) {
                        if (assumptions.intHoleVarIds[i] == v &&
                            assumptions.intHoleValues[i] == value
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
            intValues = iVals.toIntArray(),
            intMinKeys = iMinKeys.toIntArray(),
            intMinValues = iMinVals.toIntArray(),
            intMaxKeys = iMaxKeys.toIntArray(),
            intMaxValues = iMaxVals.toIntArray(),
            intHoleVarIds = iHoleIds.toIntArray(),
            intHoleValues = iHoleVals.toIntArray(),
        )
    }

    private inline fun invert(slots: Int, vars: (Factor) -> IntArray): Array<IntArray> {
        val counts = IntArray(slots)
        for (f in factors) for (v in vars(f)) counts[v]++
        val out = Array(slots) { IntArray(counts[it]) }
        val cursor = IntArray(slots)
        factors.forEachIndexed { id, f ->
            for (v in vars(f)) out[v][cursor[v]++] = id
        }
        return out
    }
}
