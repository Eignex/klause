package com.eignex.klause.factor.global

import com.eignex.klause.factor.OptPresence
import com.eignex.klause.factor.OptionalFactor
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.KeySink
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.MixedVars
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.hashRemappedKey
import com.eignex.klause.ir.materializeKey
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorReduction
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Rewrite
import com.eignex.klause.solver.Unchanged
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.EmptyLongArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntIntMap
import com.eignex.klause.util.LongHashSet
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
    val domainMin: Long,
    /** Number of values in the shared value domain. */
    val domainSize: Int,
    /** Per-position presence literals; empty for the non-opt fast path. When non-empty,
     *  only present positions are required pairwise-different, and matching-based filtering treats
     *  unpinned-presence positions as "may yet be absent" — they neither demand a matching
     *  slot nor block other positions from claiming their value. */
    override val presents: IntArray = EmptyIntArray,
    /** Values exempt from the distinctness requirement: any number of variables may share a
     *  value in this set (the `alldifferent_except` / `alldifferent_except_0` family). Empty for
     *  plain all-different. Excepted values are modelled inside `reginFilter` as capacity-n value
     *  copies, so the exact Hall/matching machinery applies unchanged. */
    val exceptSet: LongArray = EmptyLongArray,
    /** When true, the constraint carried the FlatZinc `::bounds` annotation — the modeller
     *  asked for bounds-consistency rather than full GAC (e.g. ghoulomb's `distinct ::bounds`,
     *  the matching/SCC/Hall machinery is then skipped in favour of a much cheaper
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
    private val exceptSorted: LongArray =
        if (exceptSet.isEmpty()) EmptyLongArray else exceptSet.distinct().sorted().toLongArray()

    /** Membership view of [exceptSet] for the hot value checks; the shared empty set when none. */
    internal val exceptValues: LongHashSet =
        if (exceptSet.isEmpty()) {
            AllDifferentInvariant.NO_EXCEPT
        } else {
            LongHashSet(
                exceptSet.size,
            ).also { s -> for (e in exceptSet) s.add(e) }
        }

    // Propagation strength: full GAC via bipartite matching plus SCC support pruning over the
    // definitely-present positions. IntDomain supports interior holes, so non-matching
    // value pruning lands at the variable domain level. Opt-aware: definitely-absent
    // positions are skipped entirely; unpinned-presence positions are skipped too, so any
    // filtering remains sound under "this position might still go absent".

    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.ALL_DIFFERENT, ::buildKey)

    override fun remapStructuralHash(mapping: VarRemap): Int =
        hashRemappedKey(FactorKind.ALL_DIFFERENT, mapping, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.long(domainMin)
        sink.int(domainSize)
        sink.sortedIntVars(vars)
        sink.sortedBoolLits(presents)
        sink.constLongs(exceptSorted)
        sink.bool(boundsConsistent)
    }

    /** Plain distinctness ignores which values are used — invariant under any value relabeling;
     *  an excepted-value set names those values, so it is not value-anonymous. */
    override fun isValueAnonymous(): Boolean = exceptSet.isEmpty()

    /** Plain all-different names no value, so any relabeling leaves it unchanged; with an
     *  excepted-value set the excepted values are named and must be relabeled too. */
    override fun remapValues(valueMap: (Long) -> Long): Factor? = if (exceptSet.isEmpty()) {
        this
    } else {
        AllDifferent(
            vars,
            domainMin,
            domainSize,
            presents,
            LongArray(exceptSet.size) { valueMap(exceptSet[it]) },
            boundsConsistent,
        )
    }

    override fun remap(mapping: VarRemap): Factor = AllDifferent(
        mapping.ints(vars),
        domainMin,
        domainSize,
        mapping.lits(presents),
        exceptSet,
        boundsConsistent,
    )

    // Structural reductions for a plain all-different (the optional / excepted-value variants have
    // weaker semantics and are left alone):
    //  - two variables → the disequality `v0 != v1` (a cheap binary propagator instead of matching);
    //  - otherwise, split into independent all-differents over value-disjoint components — when the
    //    variables' value ranges partition into groups that cannot share a value, distinctness across
    //    groups is automatic, so each group is its own (smaller, cheaper) all-different and any
    //    singleton group drops. Exact, and it exposes per-component symmetry the whole constraint hid.
    override fun structuralReduce(domains: Array<IntDomain>): FactorReduction {
        if (presents.isNotEmpty() || exceptSet.isNotEmpty()) return Unchanged
        if (vars.size == 2) {
            return Rewrite(
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
        if (components.size == 1) return Unchanged
        val replacement = ArrayList<Factor>(components.size)
        for (group in components) {
            if (group.size < 2) continue
            var lo = Long.MAX_VALUE
            var hi = Long.MIN_VALUE
            for (v in group) {
                lo = minOf(lo, domains[v].min)
                hi = maxOf(hi, domains[v].max)
            }
            // domainSize is an Int-sized value-span used to size occurrence/matching scratch; a
            // component whose value range overflows Int can't be represented, so leave the whole
            // constraint intact rather than emit an unsound split (matching GAC still runs on it).
            val span = hi - lo + 1
            if (span > Int.MAX_VALUE) return Unchanged
            replacement.add(AllDifferent(group.toIntArray(), domainMin = lo, domainSize = span.toInt()))
        }
        return Rewrite(replacement)
    }

    override val variables: VarList = MixedVars(spanInts = vars, boolVars = OptPresence.presenceVarIds(presents))

    /** Pre-computed `intVar → number of slots in [vars] holding it`. Used to compute the
     *  delta of changing a single var's value in O(1) without re-scanning [vars]; for the
     *  common case where each var appears exactly once this is always 1. */
    internal val occurrencesByVar: IntIntMap = run {
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
