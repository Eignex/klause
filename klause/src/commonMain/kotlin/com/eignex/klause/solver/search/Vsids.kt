package com.eignex.klause.solver.search

import com.eignex.klause.util.IndexedMaxHeap
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
 * across [onRestart], so learning carries over.
 *
 * Written against [BranchingState], so the same activities drive a finite CP traversal and a theory
 * traversal. A shared Boolean analyzer names the variables of its asserting clause. Native CP analysis
 * names the variables visited in its implication graph; conflicts without a learned consequence use
 * the decision variables at their conflicting levels.
 *
 * Default `decay = 0.95`. Lower decay (e.g., 0.8) makes the heuristic more
 * aggressive about following recent conflicts; higher (e.g., 0.99) is more conservative.
 */
class Vsids(private val decay: Double = 0.95, private val rescaleThreshold: Double = 1e100) :
    VariableBranching<BranchingState> {

    override fun fresh() = Vsids(decay, rescaleThreshold)

    init {
        require(decay in 0.5..0.999) { "VSIDS decay must be in 0.5..0.999, got $decay" }
    }

    private var increment: Double = 1.0

    // Combined index space: 0..numBool-1 are bool ids; numBool..numBool+numInt-1 are int ids
    // offset by numBool. One indexed max-heap over both gives O(log n) bumps and amortised
    // O((1 + pinned-skip) · log n) picks instead of an O(numBool + numInt) linear scan.
    private var heap: IndexedMaxHeap? = null
    private var numBoolCached: Int = 0
    private var numIntCached: Int = 0

    // Identity of the state pick() last ran against. pick() removes assigned vars from the
    // heap and relies on onUnassign to re-insert them, so a solve that ends on a leaf or timeout
    // leaves the heap partial. A fresh search on the same instance re-offers every removed var on
    // its first pick, keeping their stored activities warm.
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
        // Extract the max-activity id and keep it out of the heap. Already-pinned ids surfacing here
        // are dropped, not restored — [onUnassign] re-inserts every variable a backtrack frees, so
        // the heap holds roughly only unassigned variables and pick stays O(log n) regardless of
        // trail depth.
        //
        // The bool branch fully assigns the picked var (true/false), so it can stay removed. An
        // int branch may only *narrow* the domain (still size > 1 after), so the picked int var
        // is restored — onUnassign fires on widening, not narrowing, and would otherwise never
        // re-offer a still-free int var, making pick return null with variables undetermined.
        while (true) {
            while (h.size > 0) {
                val id = h.extractMax()
                if (id < numBoolCached) {
                    if (state.boolValue(id) == null) return VarRef.Bool(id)
                } else {
                    val intId = id - numBoolCached
                    if (!state.intFixed(intId)) {
                        h.restore(id)
                        return VarRef.IntVar(intId)
                    }
                }
            }
            // Pop-on-pick can strand an open variable out of the heap if a re-add is missed across a
            // restart or backtrack, and returning `null` then would commit an incomplete assignment
            // as a solution. Rescan and re-offer every still-open variable before giving up; this runs
            // only when the heap empties, so `null` provably means every variable is determined.
            var refilled = false
            for (v in 0 until numBoolCached) {
                if (state.boolValue(v) == null && !h.contains(v)) {
                    h.restore(v)
                    refilled = true
                }
            }
            for (v in 0 until numIntCached) {
                if (!state.intFixed(v) && !h.contains(numBoolCached + v)) {
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

    /** Scale every activity and `increment` by `1/rescaleThreshold`, preserving their order while staying
     *  clear of `Double.MAX_VALUE`; a uniform factor needs no resifting. */
    private fun rescaleAll(h: IndexedMaxHeap) {
        val k = 1.0 / rescaleThreshold
        h.scaleKeys(k)
        increment *= k
    }
}
