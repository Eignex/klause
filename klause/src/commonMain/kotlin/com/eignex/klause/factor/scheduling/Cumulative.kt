package com.eignex.klause.factor.scheduling

import com.eignex.klause.factor.OptPresence
import com.eignex.klause.factor.OptionalFactor
import com.eignex.klause.factor.remapLits
import com.eignex.klause.factor.remapVars
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.FactorReduction
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.KeySink
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.solver.hashRemappedKey
import com.eignex.klause.solver.materializeKey
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntIntMap

/**
 * Cumulative scheduling constraint: at every integer time point the total resource use of
 * tasks running at that point stays under [capacity]. Task `i` has variable start time
 * `starts(i)`, fixed duration `durations(i) ≥ 0`, fixed resource demand `resources(i) ≥ 0`.
 *
 * Semantics:
 *  - Task `i` occupies the half-open interval `[starts(i), starts(i) + durations(i))`.
 *  - For every integer time point `t`, `Σ_{i: starts(i) ≤ t < starts(i)+durations(i)} resources(i) ≤ capacity`.
 *  - Zero-duration tasks consume no resource and impose no constraint.
 *  - Any task with `resources(i) > capacity` makes the problem trivially infeasible (the
 *    factor still reports a graded overage cost when LS hits such a placement).
 *
 * LS cost is graded:
 *   `cost = Σ_t max(0, usage(t) − capacity)`
 * — broken assignments rank by total energy overflow rather than by a flat boolean,
 * giving the search a real gradient toward the cumulative bound. This energy overage is
 * the factor's [Invariant.violationDegree] (run through `compressViolation` so a deeply-overloaded
 * profile can't dominate the global cost); [Invariant.deltaIfIntSet] / [Invariant.applyIntSet] and the
 * bool-flip paths return its compressed delta. The raw overage is also mirrored to
 * `state.intPayload(factorId)` for strategies that read it directly (as ALNS does).
 *
 * Propagation: **time-tabling**. For every task with overlap window `[lst_i, ect_i)`
 * (latest-start to earliest-completion), `resources[i]` is *mandatory* throughout that
 * window. The summed mandatory profile is built event-by-event in O(n log n); any time
 * point with `Σ mandatory > capacity` proves infeasibility. For each non-fixed task `i`,
 * any candidate start `s` that would push the *post-i* profile (mandatory + r_i during
 * `[s, s+d_i)`) above capacity at some time point is forbidden — the standard
 * time-tabling deduction. Bounds are tightened at the candidate-domain endpoints,
 * mirroring the rest of the factor catalog's bounds-consistency style.
 *
 * Time-tabling is the baseline, paired here with an O(n²) Vilím Θ-tree edge-finder
 * (Vilím 2009 / Schutt-Feydy-Stuckey 2009) running off `CumulativeThetaTree`. The
 * edge-finder catches energy-overflow deductions on subsets that have no compulsory
 * profile, which time-tabling cannot see.
 *
 * Cost model is dense: the LS payload allocates an `IntArray` of size
 * `horizon = max_i(starts[i].max + durations[i]) − min_i(starts[i].min)`. For Challenge
 * instances this is typically a few hundred; if your horizon explodes past ~1M, prefer a
 * Linear-per-timepoint decomposition.
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
     *  zero energy / zero compulsory part. Theta-tree leaves stay inactive for
     *  definitely-absent tasks; unpinned-presence tasks are excluded from edge-finding too
     *  (they may yet go absent, so they can't sharpen Ω-energy deductions). */
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

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = Cumulative(
        starts.remapVars(intMap),
        durations,
        resources,
        capacity,
        presents.remapLits(boolMap),
        durationVars.remapVars(intMap),
        resourceVars.remapVars(intMap),
        if (capacityVar >= 0) intMap[capacityVar] else capacityVar,
    )

    // When no two tasks can run at once — the two smallest resource demands already exceed the capacity
    // — the resource is never shared, so the cumulative is exactly a [Disjunctive] (no-overlap), whose
    // theta-tree / edge-finding propagator is both stronger and cheaper for that case. Only constant
    // durations/resources/capacity reduce; a single demand above capacity is left to the propagator to
    // report infeasible.
    override fun structuralReduce(domains: Array<IntDomain>): FactorReduction {
        if (durationVars.isNotEmpty() || resourceVars.isNotEmpty() || capacityVar >= 0 || n < 2) {
            return FactorReduction.Unchanged
        }
        var min1 = Long.MAX_VALUE
        var min2 = Long.MAX_VALUE
        for (r in resources) {
            if (r > capacity) return FactorReduction.Unchanged
            if (r < min1) {
                min2 = min1
                min1 = r
            } else if (r < min2) {
                min2 = r
            }
        }
        if (min1 + min2 <= capacity) return FactorReduction.Unchanged
        return FactorReduction.Rewrite(listOf(Disjunctive(starts, durations, presents)))
    }

    /** Position-faithful (task i is fixed by index): keeps every array in order and folds in all
     *  constants — durations/resources/capacity and the var/const split — so two non-equivalent
     *  cumulatives never collide (#531). */
    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.CUMULATIVE, ::buildKey)

    override fun remapStructuralHash(boolMap: IntArray, intMap: IntArray): Int =
        hashRemappedKey(FactorKind.CUMULATIVE, boolMap, intMap, ::buildKey)

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

    override val boolVars: IntArray = OptPresence.presenceVarIds(presents)
    override val intVars: IntArray = run {
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
    }

    /** Number of tasks. */
    val n: Int = starts.size

    /** The sharp pointwise time-tabling explanation covers every task as mandatory. Variable durations /
     *  resources / capacity are handled by additionally citing their (fixed-at-propagation) bounds, but
     *  separate presence literals are not, so optional tasks fall back to the sound constraint-wide
     *  reason. RCPSP / mspsp-style instances (mandatory tasks, the multi-skill "presence" carried by a
     *  0/1 resource var rather than a presence literal) are sharp. */
    val sharpReasonEligible: Boolean = presents.isEmpty()

    /** Whether every task's energy (`duration · resource`) and the capacity are compile-time
     *  constants, i.e. the only citable variables this factor reads are the start times. Lets
     *  edge-finding emit a reason scoped to the active set Θ_τ (the standard Schutt edge-finding
     *  explanation, which depends only on the in-window tasks' start bounds) instead of the
     *  constraint-wide all-starts reason; the energy / capacity premises a variable-arg instance
     *  would also need are vacuous here. The common RCPSP shape (`cumulative(starts, d, r, C)`). */
    val constantEnergyAndCap: Boolean =
        durationVars.isEmpty() && resourceVars.isEmpty() && capacityVar < 0

    // Var id → its position in the corresponding array (-1 when the var is not in that role).
    // IntIntMap keeps the lookup unboxed and array-backed for the dense var ids these hold.
    private val startPos: IntIntMap = IntIntMap.build(starts, IntArray(starts.size) { it }, absent = -1)
    private val durPos: IntIntMap = IntIntMap.build(durationVars, IntArray(durationVars.size) { it }, absent = -1)
    private val resPos: IntIntMap = IntIntMap.build(resourceVars, IntArray(resourceVars.size) { it }, absent = -1)

    /** Index of [varId] in [starts], or `-1` if it is not a start variable. */
    fun startPosOf(varId: Int): Int = startPos[varId]

    /** Index of [varId] in `durationVars`, or `-1` if it is not a duration variable. */
    fun durPosOf(varId: Int): Int = durPos[varId]

    /** Index of [varId] in `resourceVars`, or `-1` if it is not a resource variable. */
    fun resPosOf(varId: Int): Int = resPos[varId]

    override fun asPropagator(): Propagator = CumulativePropagator(
        intVars = intVars,
        starts = starts,
        durations = durations,
        resources = resources,
        capacity = capacity,
        presents = presents,
        durationVars = durationVars,
        resourceVars = resourceVars,
        capacityVar = capacityVar,
        n = n,
        sharpReasonEligible = sharpReasonEligible,
        constantEnergyAndCap = constantEnergyAndCap,
    )

    override fun asInvariant(): Invariant = CumulativeInvariant(
        starts = starts,
        durations = durations,
        resources = resources,
        capacity = capacity,
        presents = presents,
        durationVars = durationVars,
        resourceVars = resourceVars,
        capacityVar = capacityVar,
        n = n,
        startPosOf = ::startPosOf,
        durPosOf = ::durPosOf,
        resPosOf = ::resPosOf,
    )
}
