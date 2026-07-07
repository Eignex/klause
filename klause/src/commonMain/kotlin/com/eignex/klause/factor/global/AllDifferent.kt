package com.eignex.klause.factor.global

import com.eignex.klause.factor.OptPresence
import com.eignex.klause.factor.OptionalFactor
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.remapLits
import com.eignex.klause.factor.remapVars
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.FactorReduction
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.IntIntMap
import com.eignex.klause.util.MutableIntIntMap

/**
 * `intVars(i) != intVars(j)` for every pair `i < j`. Stored payload:
 *
 *   `refPayload(factorId)` = State (counts: IntArray, excess: Int)
 *
 * `counts` is indexed by `value - domainMin` and tracks how many vars currently hold each
 * value across the union domain `[domainMin, domainMin + domainSize)`. `excess` is the graded
 * violation `Σ max(0, count - 1)` — the number of vars that must move to clear every clash; the
 * factor is violated iff that's positive, and it is the [Invariant.violationDegree] CBLS descends.
 */
class AllDifferent(
    /** Integer variable ids required to be pairwise distinct. */
    val vars: IntArray,
    /** Minimum value across the shared value domain. */
    val domainMin: Int,
    /** Number of values in the shared value domain. */
    val domainSize: Int,
    /** Per-position presence literals; empty for the non-opt fast path. When non-empty,
     *  only present positions are required pairwise-different, and Régin filtering treats
     *  unpinned-presence positions as "may yet be absent" — they neither demand a matching
     *  slot nor block other positions from claiming their value. */
    override val presents: IntArray = EmptyIntArray,
    /** Values exempt from the distinctness requirement: any number of variables may share a
     *  value in this set (the `alldifferent_except` / `alldifferent_except_0` family, #433).
     *  Empty for plain all-different — then this factor behaves exactly as before. Excepted
     *  values are modelled inside `reginFilter` as capacity-n value copies, so the exact
     *  Hall/matching machinery applies unchanged. */
    val exceptSet: IntArray = EmptyIntArray,
    /** When true, the constraint carried the FlatZinc `::bounds` annotation — the modeller
     *  asked for bounds-consistency rather than full GAC (e.g. ghoulomb's `distinct ::bounds`,
     *  Régin's matching/SCC/Hall machinery is then skipped in favour of a much cheaper
     *  filter, trading pruning strength for per-node throughput as the model intends. */
    val boundsConsistent: Boolean = false,
) : Factor,
    OptionalFactor {

    init {
        require(vars.size >= 2) { "AllDifferent needs at least two variables" }
        require(domainSize >= 1) { "AllDifferent domainSize must be >= 1, got $domainSize" }
        require(presents.isEmpty() || presents.size == vars.size) {
            "AllDifferent: presents must be empty or match vars arity"
        }
    }

    /** Canonical excepted values (deduped, sorted) for [structuralKey] / [remap]. */
    private val exceptSorted: IntArray =
        if (exceptSet.isEmpty()) EmptyIntArray else exceptSet.distinct().sorted().toIntArray()

    /** Membership view of [exceptSet] for the hot value checks; the shared empty set when none. */
    @Suppress("EXPOSED_PROPERTY_TYPE")
    val exceptValues: IntHashSet =
        if (exceptSet.isEmpty()) {
            AllDifferentInvariant.NO_EXCEPT
        } else {
            IntHashSet(
                exceptSet.size,
            ).also { s -> for (e in exceptSet) s.add(e) }
        }

    // Propagation strength: full GAC via Régin's matching + SCC algorithm over the
    // definitely-present positions. IntDomain supports interior holes, so non-matching
    // value pruning lands at the variable domain level. Opt-aware: definitely-absent
    // positions are skipped entirely; unpinned-presence positions are skipped too, so any
    // filtering remains sound under "this position might still go absent".

    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.ALL_DIFFERENT) {
        int(domainMin)
        int(domainSize)
        sortedInts(vars)
        sortedInts(presents)
        sortedInts(exceptSorted)
        bool(boundsConsistent)
    }

    /** Plain distinctness ignores which values are used — invariant under any value relabeling
     *  (#366); an excepted-value set names those values, so it is no longer value-anonymous. */
    override fun isValueAnonymous(): Boolean = exceptSet.isEmpty()

    /** Plain all-different names no value, so any relabeling leaves it unchanged; with an
     *  excepted-value set the excepted values are named and must be relabeled too (#374). */
    override fun remapValues(valueMap: (Int) -> Int): Factor = if (exceptSet.isEmpty()) {
        this
    } else {
        AllDifferent(
            vars,
            domainMin,
            domainSize,
            presents,
            IntArray(exceptSet.size) { valueMap(exceptSet[it]) },
            boundsConsistent,
        )
    }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = AllDifferent(
        vars.remapVars(intMap),
        domainMin,
        domainSize,
        presents.remapLits(boolMap),
        exceptSet,
        boundsConsistent,
    )

    // Structural reductions for a plain all-different (the optional / excepted-value variants have
    // weaker semantics and are left alone):
    //  - two variables → the disequality `v0 != v1` (a cheap binary propagator instead of Régin);
    //  - otherwise, split into independent all-differents over value-disjoint components — when the
    //    variables' value ranges partition into groups that cannot share a value, distinctness across
    //    groups is automatic, so each group is its own (smaller, cheaper) all-different and any
    //    singleton group drops. Exact, and it exposes per-component symmetry the whole constraint hid.
    override fun structuralReduce(domains: Array<IntDomain>): FactorReduction {
        if (presents.isNotEmpty() || exceptSet.isNotEmpty()) return FactorReduction.Unchanged
        if (vars.size == 2) {
            return FactorReduction.Rewrite(
                listOf(Linear(intArrayOf(1, -1), intArrayOf(vars[0], vars[1]), LinearOp.NE, 0)),
            )
        }
        return splitIntoValueDisjointComponents(domains)
    }

    /** Partition [vars] into value-range-connected components (a sweep over `[min, max]` intervals: two
     *  variables are connected when their intervals overlap). With more than one component the
     *  all-different splits into one per multi-variable component — singleton components impose nothing
     *  and drop — else there is nothing to split. */
    private fun splitIntoValueDisjointComponents(domains: Array<IntDomain>): FactorReduction {
        // Sort by interval min and cut a new component wherever the next interval starts beyond the
        // running max — each component is a contiguous slice of the sorted order.
        val order = vars.indices.sortedBy { domains[vars[it]].min }
        val components = ArrayList<List<Int>>()
        var start = 0
        var runningMax: Long = domains[vars[order[0]]].max
        for (k in 1 until order.size) {
            val d = domains[vars[order[k]]]
            if (d.min <= runningMax) {
                if (d.max > runningMax) runningMax = d.max
            } else {
                components.add(order.subList(start, k).map { vars[it] })
                start = k
                runningMax = d.max
            }
        }
        components.add(order.subList(start, order.size).map { vars[it] })
        if (components.size == 1) return FactorReduction.Unchanged
        val replacement = components.mapNotNull { group ->
            if (group.size < 2) return@mapNotNull null
            var lo = Long.MAX_VALUE
            var hi = Long.MIN_VALUE
            for (v in group) {
                lo = minOf(lo, domains[v].min)
                hi = maxOf(hi, domains[v].max)
            }
            AllDifferent(group.toIntArray(), domainMin = lo.toInt(), domainSize = (hi - lo + 1).toInt())
        }
        return FactorReduction.Rewrite(replacement)
    }

    override val boolVars: IntArray = OptPresence.presenceVarIds(presents)
    override val intVars: IntArray = vars

    /** Pre-computed `intVar → number of slots in [vars] holding it`. Used to compute the
     *  delta of changing a single var's value in O(1) without re-scanning [vars]; for the
     *  common case where each var appears exactly once this is always 1. */
    @Suppress("EXPOSED_PROPERTY_TYPE")
    val occurrencesByVar: IntIntMap = run {
        val counts = MutableIntIntMap()
        for (v in vars) counts.addTo(v, 1)
        val keys = IntArrayList(counts.size)
        val values = IntArrayList(counts.size)
        counts.forEach { k, count ->
            keys.add(k)
            values.add(count)
        }
        IntIntMap.build(
            keys = keys.toIntArray(),
            values = values.toIntArray(),
            absent = 0,
        )
    }

    override fun asPropagator(): Propagator = AllDifferentPropagator(
        boolVars,
        intVars,
        vars,
        presents,
        exceptSet,
        boundsConsistent,
        exceptValues,
        { idx, state -> definitelyPresent(idx, state) },
    )

    override fun asInvariant(): Invariant = AllDifferentInvariant(
        vars,
        domainMin,
        domainSize,
        presents,
        exceptValues,
        occurrencesByVar,
        { state, idx -> present(state, idx) },
    )
}
