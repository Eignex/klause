package com.eignex.klause.factor.scheduling

import com.eignex.klause.factor.OptPresence
import com.eignex.klause.factor.OptionalFactor
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.FactorReduction
import com.eignex.klause.ir.FactorReduction.Rewrite
import com.eignex.klause.ir.FactorReduction.Unchanged
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.KeySink
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.MixedVars
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.hashRemappedKey
import com.eignex.klause.ir.materializeKey
import com.eignex.klause.localsearch.Invariant
import com.eignex.util.arrays.emptyIntArray
import com.eignex.util.collections.IntHashSet
import com.eignex.util.collections.IntIntMap

/**
 * Cumulative scheduling constraint: at every integer time point the total resource use of
 * tasks running at that point stays within [capacity]. Task `i` has start time `starts(i)`,
 * duration `durations(i) ≥ 0` and resource demand `resources(i) ≥ 0`; durations, demands and
 * capacity may also be variables.
 *
 * The capacity-1 unit-demand case (no two tasks overlap) is the disjunctive constraint; build it with
 * [Cumulative.unary], which sets [unary] to dispatch the stronger disjunctive propagator and invariant.
 *
 * Semantics:
 *  - Task `i` occupies the half-open interval `[starts(i), starts(i) + durations(i))`.
 *  - For every integer time point `t`, `Σ_{i: starts(i) ≤ t < starts(i)+durations(i)} resources(i) ≤ capacity`.
 *  - Zero-duration tasks consume no resource and impose no constraint.
 *  - Any task with `resources(i) > capacity` makes the problem infeasible.
 *
 * The local-search [Invariant.violationDegree] is the compressed overage
 * `Σ_t max(0, usage(t) − capacity)`, so broken assignments rank by total energy overflow.
 *
 * Propagation is time-tabling over compulsory parts `[lst_i, ect_i)`, paired with Θ-tree edge-finding
 * (P. Vilím, "Edge Finding Filtering Algorithm for Discrete Cumulative Resources in O(kn log n)",
 * CP 2009, https://doi.org/10.1007/978-3-642-04244-7_62),
 * which catches energy-overflow deductions on subsets that have no compulsory profile, and with
 * energetic reasoning that also fires while durations, demands or capacity are still ranges. Each
 * deduction carries an explanation in the style of A. Schutt, T. Feydy, P. J. Stuckey and M. G. Wallace,
 * "Explaining the cumulative propagator", Constraints 16(3), 2011, https://doi.org/10.1007/s10601-010-9103-2.
 *
 * Local search maintains a dense resource profile for short horizons and sweeps task
 * start and end events for wider horizons.
 */
class Cumulative(
    /** Task start-time variable ids. */
    val starts: IntArray,
    /** Per-task duration constant, or the duration variable's upper bound when [durationVars] is set. */
    val durations: LongArray,
    /** Per-task resource demand: same dual role as [durations]. */
    val resources: LongArray,
    /** Capacity constant, or the capacity variable's upper bound when [capacityVar] ≥ 0. */
    val capacity: Long,
    /** Per-task presence literals, empty when every task is present. Absent tasks contribute nothing;
     *  propagation filters with definitely present tasks only, since an unpinned task may yet go absent. */
    override val presents: IntArray = emptyIntArray(),
    /** Per-task duration variables; empty means [durations] are constants. */
    val durationVars: IntArray = emptyIntArray(),
    /** Per-task resource variables; empty means [resources] are constants. */
    val resourceVars: IntArray = emptyIntArray(),
    /** Capacity variable id; -1 = use [capacity] as a constant. */
    val capacityVar: Int = -1,
) : Factor,
    OptionalFactor {

    init {
        require(starts.size == durations.size && starts.size == resources.size) {
            "Cumulative arrays must match: starts=${starts.size} " +
                "durations=${durations.size} resources=${resources.size}"
        }
        require(capacity >= 0) { "Cumulative capacity must be ≥ 0, got $capacity" }
        for (i in durations.indices) {
            require(durations[i] >= 0) { "Cumulative durations[$i] must be ≥ 0, got ${durations[i]}" }
            require(resources[i] >= 0) { "Cumulative resources[$i] must be ≥ 0, got ${resources[i]}" }
        }
        require(presents.isEmpty() || presents.size == starts.size) {
            "Cumulative: presents must be empty or match starts arity"
        }
        require(durationVars.isEmpty() || durationVars.size == starts.size) {
            "Cumulative: durationVars must be empty or match starts arity"
        }
        require(resourceVars.isEmpty() || resourceVars.size == starts.size) {
            "Cumulative: resourceVars must be empty or match starts arity"
        }
    }

    override fun remap(mapping: VarRemap): Factor = Cumulative(
        mapping.ints(starts),
        durations,
        resources,
        capacity,
        mapping.lits(presents),
        mapping.ints(durationVars),
        mapping.ints(resourceVars),
        if (capacityVar >= 0) mapping.int(capacityVar) else capacityVar,
    )

    // When the two smallest demands already exceed the capacity, the resource is never shared, so the
    // cumulative is exactly the [unary] case, whose propagator is stronger and cheaper. A single demand
    // above capacity is left to the propagator to report infeasible.
    override fun structuralReduce(domains: Array<IntDomain>): FactorReduction {
        if (unary || durationVars.isNotEmpty() || resourceVars.isNotEmpty() || capacityVar >= 0 || n < 2) {
            return Unchanged
        }
        var min1 = Long.MAX_VALUE
        var min2 = Long.MAX_VALUE
        for (r in resources) {
            if (r > capacity) return Unchanged
            if (r < min1) {
                min2 = min1
                min1 = r
            } else if (r < min2) {
                min2 = r
            }
        }
        if (min1 + min2 <= capacity) return Unchanged
        return Rewrite(listOf(unary(starts, durations, presents)))
    }

    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.CUMULATIVE, ::buildKey)

    override fun remapStructuralHash(mapping: VarRemap): Int =
        hashRemappedKey(FactorKind.CUMULATIVE, mapping, ::buildKey)

    // Position-faithful: task i is identified by index, so every array keeps its order.
    private fun buildKey(sink: KeySink) {
        sink.long(capacity)
        sink.intVarOrSelf(capacityVar)
        sink.constLongs(durations)
        sink.constLongs(resources)
        sink.intVars(starts)
        sink.boolLits(presents)
        sink.intVars(durationVars)
        sink.intVars(resourceVars)
    }

    override val variables: VarList = MixedVars(
        spanInts = run {
            val extra = (if (durationVars.isNotEmpty()) durationVars.size else 0) +
                (if (resourceVars.isNotEmpty()) resourceVars.size else 0) +
                (if (capacityVar >= 0) 1 else 0)
            if (extra == 0) {
                starts
            } else {
                val out = IntArray(starts.size + extra)
                var k = 0
                for (v in starts) out[k++] = v
                if (durationVars.isNotEmpty()) for (v in durationVars) out[k++] = v
                if (resourceVars.isNotEmpty()) for (v in resourceVars) out[k++] = v
                if (capacityVar >= 0) out[k++] = capacityVar
                out
            }
        },
        boolVars = OptPresence.presenceVarIds(presents),
    )

    /** Number of tasks. */
    val n: Int = starts.size

    /**
     * No-overlap shape: constant unit demands over a constant capacity of 1. Propagation and local search
     * dispatch the stronger disjunctive reasoning here.
     */
    val unary: Boolean =
        capacityVar < 0 && capacity == 1L && resourceVars.isEmpty() && resources.all { it == 1L }

    private val startPos: IntIntMap = IntIntMap.build(starts, IntArray(starts.size) { it }, absent = -1)
    private val durPos: IntIntMap = IntIntMap.build(durationVars, IntArray(durationVars.size) { it }, absent = -1)
    private val resPos: IntIntMap = IntIntMap.build(resourceVars, IntArray(resourceVars.size) { it }, absent = -1)

    /** Index of [varId] in [starts], or `-1` if it is not a start variable. */
    fun startPosOf(varId: Int): Int = startPos[varId]

    /** Index of [varId] in `durationVars`, or `-1` if it is not a duration variable. */
    fun durPosOf(varId: Int): Int = durPos[varId]

    /** Index of [varId] in `resourceVars`, or `-1` if it is not a resource variable. */
    fun resPosOf(varId: Int): Int = resPos[varId]

    // Coordinates filling more than one slot: a write to one of them moves several footprints at once.
    internal val repeatedIntVars: IntHashSet by lazy {
        val capacity = if (capacityVar >= 0) intArrayOf(capacityVar) else emptyIntArray()
        repeated(arrayOf(starts, durationVars, resourceVars, capacity))
    }

    internal val repeatedPresenceVars: IntHashSet by lazy {
        repeated(arrayOf(IntArray(presents.size) { Lit.variable(presents[it]) }))
    }

    private fun repeated(roles: Array<IntArray>): IntHashSet {
        val seen = IntHashSet()
        val out = IntHashSet()
        for (role in roles) for (v in role) if (!seen.add(v)) out.add(v)
        return out
    }

    /** Factories for the [Cumulative] special cases. */
    companion object {
        /** The no-overlap special case: unit demands over capacity 1, the [unary] shape. */
        fun unary(
            starts: IntArray,
            durations: LongArray,
            presents: IntArray = emptyIntArray(),
            durationVars: IntArray = emptyIntArray(),
        ): Cumulative = Cumulative(
            starts = starts,
            durations = durations,
            resources = LongArray(starts.size) { 1L },
            capacity = 1L,
            presents = presents,
            durationVars = durationVars,
        )
    }
}
