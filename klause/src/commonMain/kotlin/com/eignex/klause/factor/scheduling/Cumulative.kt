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
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.IntIntMap

/**
 * Cumulative scheduling constraint: at every integer time point the total resource use of
 * tasks running at that point stays under [capacity]. Task `i` has variable start time
 * `starts(i)`, fixed duration `durations(i) ≥ 0`, fixed resource demand `resources(i) ≥ 0`.
 *
 * The unit-resource / capacity-1 special case (no two tasks overlap) is the disjunctive
 * (one-machine) constraint; build it with [Cumulative.unary], which sets [unary] to dispatch the
 * stronger disjunctive propagator and invariant.
 *
 * Semantics:
 *  - Task `i` occupies the half-open interval `[starts(i), starts(i) + durations(i))`.
 *  - For every integer time point `t`, `Σ_{i: starts(i) ≤ t < starts(i)+durations(i)} resources(i) ≤ capacity`.
 *  - Zero-duration tasks consume no resource and impose no constraint.
 *  - Any task with `resources(i) > capacity` makes the problem trivially infeasible (the
 *    factor still reports a graded overage cost when LS hits such a placement).
 *
 * LS cost is graded: `cost = Σ_t max(0, usage(t) − capacity)`, so broken assignments rank by total
 * energy overflow rather than by a flat boolean. This overage, run through `compressViolation` so a
 * deeply overloaded profile can't dominate the global cost, is the factor's [Invariant.violationDegree].
 *
 * Propagation: **time-tabling**. For every task with compulsory part `[lst_i, ect_i)`
 * (latest start to earliest completion), `resources(i)` is *mandatory* throughout that
 * window. The summed mandatory profile is built event-by-event in O(n log n); any time
 * point with `Σ mandatory > capacity` proves infeasibility. For each non-fixed task `i`,
 * a start bound `s` that would push the profile (mandatory + r_i during `[s, s+d_i)`) above
 * capacity is shaved from either end of the start domain.
 *
 * Time-tabling is paired with Θ-tree edge-finding (P. Vilím, "Edge Finding Filtering Algorithm for
 * Discrete Cumulative Resources in O(kn log n)", CP 2009, https://doi.org/10.1007/978-3-642-04244-7_62),
 * which catches energy-overflow deductions on subsets that have no compulsory profile, and with
 * energetic reasoning that also fires while durations, demands or capacity are still ranges. Each
 * deduction carries an explanation in the style of A. Schutt, T. Feydy, P. J. Stuckey and M. G. Wallace,
 * "Explaining the cumulative propagator", Constraints 16(3), 2011, https://doi.org/10.1007/s10601-010-9103-2.
 *
 * The LS payload is dense: it allocates a usage array over the horizon
 * `max_i(starts(i).max + durations(i)) − min_i(starts(i).min)`.
 */
class Cumulative(
    /** Task start-time variable ids. */
    val starts: IntArray,
    /** Per-task duration: constant fallback / upper bound (when [durationVars] is set this
     *  holds the var's domain ub, used for horizon sizing). */
    val durations: LongArray,
    /** Per-task resource demand: same dual role as [durations]. */
    val resources: LongArray,
    /** Capacity: constant fallback / upper bound (when [capacityVar] ≥ 0 holds the var's ub). */
    val capacity: Long,
    /** Per-task presence literals; empty for the non-opt fast path. Absent tasks contribute
     *  zero energy and no compulsory part; propagation filters with definitely present tasks only,
     *  since an unpinned task may yet go absent. In the [unary] shape, an unpinned task whose
     *  minimum duration would overload the present tasks is ruled absent, and so is a literal shared by
     *  several tasks whose minima would overload them together. */
    override val presents: IntArray = EmptyIntArray,
    /** Per-task duration variables; empty = use [durations] as constants. When set, the
     *  factor reads the current duration from `state.assignment.intValue(durationVars(i))`
     *  and propagation pulls bounds from `state.intDomains(durationVars(i))`. */
    val durationVars: IntArray = EmptyIntArray,
    /** Per-task resource variables; empty = use [resources] as constants. Same pattern as
     *  [durationVars]. */
    val resourceVars: IntArray = EmptyIntArray,
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

    // When no two tasks can run at once — the two smallest resource demands already exceed the capacity
    // — the resource is never shared, so the cumulative is exactly the no-overlap ([unary]) case, whose
    // theta-tree / edge-finding propagator is both stronger and cheaper. Only constant
    // durations/resources/capacity reduce; a single demand above capacity is left to the propagator to
    // report infeasible.
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

    /** Position-faithful (task i is fixed by index): keeps every array in order and folds in all
     *  constants — durations/resources/capacity and the var/const split — so two non-equivalent
     *  cumulatives never collide. */
    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.CUMULATIVE, ::buildKey)

    override fun remapStructuralHash(mapping: VarRemap): Int =
        hashRemappedKey(FactorKind.CUMULATIVE, mapping, ::buildKey)

    // durations/resources/capacity are constant magnitudes; starts/durationVars/resourceVars are int-var
    // ids; capacityVar is an int var or a negative sentinel; presents are Boolean literals.
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
     * No-overlap (unary-resource / one-machine) shape: constant unit demands over a constant capacity of
     * 1, so the resource is never shared. In this shape propagation / local-search dispatch the
     * stronger disjunctive reasoning (time-tabling, detectable precedences, unit Θ-tree edge-finding);
     * the propagator reads only the start/duration windows. Build one directly with [Cumulative.unary].
     */
    val unary: Boolean =
        capacityVar < 0 && capacity == 1L && resourceVars.isEmpty() && resources.all { it == 1L }

    // Var id → its position in the corresponding array (-1 when the var is not in that role).
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
        val capacity = if (capacityVar >= 0) intArrayOf(capacityVar) else EmptyIntArray
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
        /**
         * The no-overlap (unary-resource / one-machine) special case: unit demands over capacity 1, the
         * [unary] shape. Tasks may not overlap in time. Dispatches the stronger disjunctive propagator.
         */
        fun unary(
            starts: IntArray,
            durations: LongArray,
            presents: IntArray = EmptyIntArray,
            durationVars: IntArray = EmptyIntArray,
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
