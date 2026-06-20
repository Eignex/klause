package com.eignex.klause.solver.factor.global

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.factor.OptPresence
import com.eignex.klause.solver.factor.OptionalFactor
import com.eignex.klause.solver.factor.remapLits
import com.eignex.klause.solver.factor.remapVars
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.propagation.IntEvent
import com.eignex.klause.solver.propagation.PropagationState

/**
 * `nvalue(n, xs)` — `n` equals the count of distinct values appearing in [xs]. Plus
 * variants:
 *
 *  - [Mode.Eq] (default): `n = |distinct(xs)|`.
 *  - [Mode.AtLeast]: `n ≤ |distinct(xs)|`.
 *  - [Mode.AtMost]:  `n ≥ |distinct(xs)|`.
 *
 * One factor with a mode flag so all three MiniZinc predicates (`fzn_nvalue`,
 * `fzn_atleast_nvalues`, `fzn_atmost_nvalues`) lower to the same factor type.
 */
class NValue(
    /** Integer variable id holding the distinct-value count target. */
    override val n: Int,
    /** Integer variable ids whose distinct values are counted. */
    override val xs: IntArray,
    /** How [n] relates to the actual distinct-value count. */
    override val mode: Mode = Mode.Eq,
    /** Per-index presence literals; empty for the non-opt fast path. */
    override val presents: IntArray = EmptyIntArray,
) : Factor,
    OptionalFactor,
    NValuePropagator,
    NValueInvariant {

    /** How an `nvalue` constraint's target relates to the actual distinct-value count. */
    enum class Mode {
        /** Distinct count equals [n]. */
        Eq,

        /** Distinct count is at least [n]. */
        AtLeast,

        /** Distinct count is at most [n]. */
        AtMost,
    }

    init {
        require(xs.isNotEmpty()) { "nvalue: empty xs" }
        require(presents.isEmpty() || presents.size == xs.size) {
            "nvalue: presents must be empty or match xs arity"
        }
    }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor =
        NValue(intMap[n], xs.remapVars(intMap), mode, presents.remapLits(boolMap))

    /** The distinct-value count ignores the order of [xs], so the counted vars are sorted (paired with
     *  their presence literal to keep an opt position with its presence); [n] (the count var) and
     *  [mode] are positional constants (#443). */
    override fun structuralKey(): String = "nvalue:$mode:$n:" +
        xs.indices.sortedBy { xs[it] }.joinToString(",") { "${xs[it]}/${presents.getOrElse(it) { -1 }}" }

    override val boolVars: IntArray = OptPresence.presenceVarIds(presents)
    override val intVars: IntArray = xs + intArrayOf(n)

    /** Advisor subscription (#623) for the non-optional variant: the distinct-count bounds read each
     *  variable's full domain (union membership + domain-overlap disjointness), so subscribe to every
     *  kind and consume the dirty-variable delta (#624) to skip fires where nothing changed. The
     *  optional variant keeps occurrence wakeup — a presence-bool flip changes the count but is not in
     *  the int-domain delta, so it must not be gated out. */
    override val initialIntEventWatches: IntArray? = if (presents.isNotEmpty()) {
        null
    } else {
        val distinct = intVars.toHashSet()
        val out = IntArray(distinct.size * IntEvent.COUNT)
        var w = 0
        for (v in distinct) {
            out[w++] = IntEvent.pack(v, IntEvent.LB_RAISED)
            out[w++] = IntEvent.pack(v, IntEvent.UB_LOWERED)
            out[w++] = IntEvent.pack(v, IntEvent.VALUE_REMOVED)
            out[w++] = IntEvent.pack(v, IntEvent.FIXED)
        }
        out
    }

    override val consumesIntEventDelta: Boolean = presents.isEmpty()

    override fun definitelyAbsentNv(idx: Int, state: PropagationState): Boolean = definitelyAbsent(idx, state)

    override fun definitelyPresentNv(idx: Int, state: PropagationState): Boolean = definitelyPresent(idx, state)

    override fun presentNvInv(state: LocalSearchState, idx: Int): Boolean = present(state, idx)
}
