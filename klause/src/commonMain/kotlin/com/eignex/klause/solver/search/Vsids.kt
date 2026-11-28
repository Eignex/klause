package com.eignex.klause.solver.search

import com.eignex.util.collections.IndexedMaxHeap
import kotlin.random.Random

/**
 * Variable State Independent Decaying Sum (Moskewicz, Madigan, Zhao, Zhang and Malik, "Chaff:
 * Engineering an Efficient SAT Solver", DAC 2001, https://doi.org/10.1145/378239.379017). The
 * activity counter for each variable is bumped on every conflict the variable is implicated
 * in, with the bump amount `increment` growing geometrically so recent conflicts dominate.
 * Equivalent to multiplicative decay of every prior activity by [decay] per conflict, but only the
 * increment is mutated. Rescales when `increment` passes `rescaleThreshold`.
 *
 * Picks the unpinned variable with the highest activity. Ties follow the heap order. Activities persist
 * across [onRestart]. Lower [decay] follows recent conflicts more aggressively.
 */
class Vsids(private val decay: Double = 0.95, private val rescaleThreshold: Double = 1e100) :
    VariableBranching<BranchingState> {

    override fun fresh() = Vsids(decay, rescaleThreshold)

    init {
        require(decay in 0.5..0.999) { "VSIDS decay must be in 0.5..0.999, got $decay" }
    }

    private var increment: Double = 1.0

    // Bool ids first, then int ids offset by numBool, in one indexed max-heap.
    private var heap: IndexedMaxHeap? = null
    private var numBoolCached: Int = 0
    private var numIntCached: Int = 0

    // A solve that ends on a leaf or timeout leaves the heap partial, so a pick against a new state
    // re-offers every removed var with its stored activity.
    private var lastState: BranchingState? = null

    private fun ensureSized(numBool: Int, numInt: Int) {
        if (heap != null && numBoolCached == numBool && numIntCached == numInt) return
        val h = IndexedMaxHeap(numBool + numInt)
        for (i in 0 until numBool + numInt) h.insert(i, 0.0)
        heap = h
        numBoolCached = numBool
        numIntCached = numInt
    }

    override val tracksUnassign: Boolean get() = true

    override fun pick(state: BranchingState, rng: Random): VarRef? {
        ensureSized(state.numBoolVars, state.numIntVars)
        val h = requireNotNull(heap)
        if (state !== lastState) {
            lastState = state
            val total = numBoolCached + numIntCached
            for (id in 0 until total) if (!h.contains(id)) h.restore(id)
        }
        // Pinned ids are dropped, not restored: onUnassign re-inserts every variable a backtrack frees.
        // A picked int var is restored because its branch may only narrow the domain, and onUnassign
        // would never re-offer it.
        while (true) {
            while (h.size > 0) {
                val id = h.extractMax()
                if (id < numBoolCached) {
                    if (state.needsBoolDecision(id)) return VarRef.Bool(id)
                } else {
                    val intId = id - numBoolCached
                    if (state.needsIntDecision(intId)) {
                        h.restore(id)
                        return VarRef.IntVar(intId)
                    }
                }
            }
            // A missed re-add would strand an open variable, and returning null would commit an
            // incomplete assignment. Rescan before giving up so null means nothing needs a decision.
            var refilled = false
            for (v in 0 until numBoolCached) {
                if (state.needsBoolDecision(v) && !h.contains(v)) {
                    h.restore(v)
                    refilled = true
                }
            }
            for (v in 0 until numIntCached) {
                if (state.needsIntDecision(v) && !h.contains(numBoolCached + v)) {
                    h.restore(numBoolCached + v)
                    refilled = true
                }
            }
            if (!refilled) return null
        }
    }

    override fun onUnassign(varRef: VarRef) {
        val h = heap ?: return
        val id = when (varRef) {
            is VarRef.Bool -> varRef.varId
            is VarRef.IntVar -> numBoolCached + varRef.varId
        }
        if (!h.contains(id)) h.restore(id)
    }

    override fun onConflict(varRef: VarRef?, conflict: BranchingConflict) {
        val h = heap ?: return
        // The failed decision is bumped too when the substrate did not already implicate it.
        for (b in conflict.bools) bumpBool(h, b)
        for (i in conflict.ints) bumpInt(h, i)
        when (varRef) {
            is VarRef.Bool -> if (varRef.varId !in conflict.bools) bumpBool(h, varRef.varId)
            is VarRef.IntVar -> if (varRef.varId !in conflict.ints) bumpInt(h, varRef.varId)
            null -> Unit
        }
        // Implicit multiplicative decay of every prior activity by `decay`, without touching the keys.
        increment /= decay
        if (increment > rescaleThreshold) rescaleAll(h)
    }

    private fun bumpBool(h: IndexedMaxHeap, v: Int) {
        if (v >= numBoolCached) return
        h.updateKey(v, h.keyOf(v) + increment)
    }

    private fun bumpInt(h: IndexedMaxHeap, v: Int) {
        if (v >= numIntCached) return
        val id = numBoolCached + v
        h.updateKey(id, h.keyOf(id) + increment)
    }

    // A uniform factor preserves heap order, so no resifting is needed.
    private fun rescaleAll(h: IndexedMaxHeap) {
        val k = 1.0 / rescaleThreshold
        h.scaleKeys(k)
        increment *= k
    }
}
