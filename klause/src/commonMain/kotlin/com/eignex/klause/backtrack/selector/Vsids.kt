package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationResult.Unsat
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.util.IndexedMaxHeap
import kotlin.random.Random

/**
 * Variable State Independent Decaying Sum (Moskewicz et al., Chaff 2001). The
 * activity counter for each variable is bumped on every conflict the variable is implicated
 * in, with the bump amount `increment` growing geometrically over time so recent conflicts
 * dominate. Equivalent to per-bump multiplicative decay by a factor of [decay] applied
 * uniformly to every prior activity, but cheaper (we only mutate the increment, not every
 * activity entry). Periodically rescales when `increment` approaches [Double] overflow.
 *
 * Picks the unpinned variable with the highest activity. Ties broken by variable-id order
 * (bools precede ints). Activities persist across [onRestart] — that's the whole point of
 * VSIDS, learning carries over.
 *
 * Attribution is coarse: the engine's [Unsat] carries the *decision variables* at conflict
 * levels, not every variable on the propagation-reason path. Bumping decision variables only
 * still gives a useful signal — consistently better than random / smallest-domain on hard
 * instances — and a richer reason set would reach this heuristic through [onConflict]
 * unchanged.
 *
 * Default `decay = 0.95`. Lower decay (e.g., 0.8) makes the heuristic more
 * aggressive about following recent conflicts; higher (e.g., 0.99) is more conservative.
 */
class Vsids(private val decay: Double = 0.95, private val rescaleThreshold: Double = 1e100) : VariableSelector {

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

    // Identity of the session pick() last ran against. pick() removes assigned vars from the
    // heap and relies on onUnassign (per backtrack) to re-insert them; a solve that ends on a
    // leaf or timeout leaves the heap partial. When the same Vsids instance is reused for a
    // fresh session (optimisation B&B, repeated solves), re-offer every removed var on the
    // first pick — keeping their stored activities so VSIDS stays warm across iterations.
    private var lastSession: PropagationSession? = null

    private fun ensureSized(numBool: Int, numInt: Int) {
        if (heap != null && numBoolCached == numBool && numIntCached == numInt) return
        val h = IndexedMaxHeap(numBool + numInt)
        for (i in 0 until numBool + numInt) h.insert(i, 0.0)
        heap = h
        numBoolCached = numBool
        numIntCached = numInt
    }

    override val tracksUnassign: Boolean get() = true

    override fun pick(session: PropagationSession, rng: Random): VarRef? {
        val problem = session.problem
        ensureSized(problem.numBoolVars, problem.numIntVars)
        val h = requireNotNull(heap)
        if (session !== lastSession) {
            lastSession = session
            val total = numBoolCached + numIntCached
            for (id in 0 until total) if (!h.contains(id)) h.restore(id)
        }
        // Decision pop: extract the max-activity id and *keep it out* of the
        // heap. Already-pinned ids surfacing here (a propagated var sitting near the top) are
        // dropped, not restored — [onUnassign] re-inserts every variable a backtrack frees, so
        // the heap holds (roughly) only unassigned variables and pick stays O(log n) regardless
        // of trail depth, instead of re-skipping every pinned var per pick.
        //
        // The bool branch fully assigns the picked var (true/false), so it can stay removed. An
        // int branch may only *narrow* the domain (still size > 1 after), so the picked int var
        // is restored — onUnassign fires on widening, not narrowing, and would otherwise never
        // re-offer a still-free int var, making pick return null with variables undetermined.
        while (true) {
            while (h.size > 0) {
                val id = h.extractMax()
                if (id < numBoolCached) {
                    if (session.boolValue(id) == null) return VarRef.Bool(id)
                } else {
                    val intId = id - numBoolCached
                    if (!session.intDomain(intId).isFixed) {
                        h.restore(id)
                        return VarRef.IntVar(intId)
                    }
                }
            }
            // The pop-on-pick scheme (a var leaves the heap when it surfaces assigned, re-added by
            // [onUnassign] on backtrack) can strand an open variable out of the heap if a re-add is
            // missed across a restart/backtrack. Reporting `null` then would tell the engine "all
            // assigned" and let it commit an incomplete assignment as a solution. Before that, rescan
            // the live domains and re-offer every still-open variable; retry so the max-activity one
            // wins. Runs only when the heap empties (a candidate leaf), so the O(log n) fast path is
            // untouched — `null` now provably means every variable is determined.
            var refilled = false
            for (v in 0 until numBoolCached) {
                if (session.boolValue(v) == null && !h.contains(v)) {
                    h.restore(v)
                    refilled = true
                }
            }
            for (v in 0 until numIntCached) {
                if (!session.intDomain(v).isFixed && !h.contains(numBoolCached + v)) {
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

    override fun onConflict(varRef: VarRef, unsat: PropagationResult.Unsat) {
        val h = heap ?: return
        // Bump every decision variable in the conflict reason set, plus the failed
        // decision itself (in case it isn't already in the set — typically it is).
        for (b in unsat.conflictBools) bumpBool(h, b)
        for (i in unsat.conflictInts) bumpInt(h, i)
        when (varRef) {
            is VarRef.Bool -> bumpBool(h, varRef.varId)
            is VarRef.IntVar -> bumpInt(h, varRef.varId)
        }
        // Grow the increment for the next conflict — implicit multiplicative decay of
        // every prior activity by `decay`, without touching the keys.
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

    /** Scale every activity (and `increment`) by `1/rescaleThreshold` so the relative
     *  ordering is preserved but we step well clear of `Double.MAX_VALUE`. The heap's
     *  [IndexedMaxHeap.scaleKeys] applies the uniform factor without resifting. */
    private fun rescaleAll(h: IndexedMaxHeap) {
        val k = 1.0 / rescaleThreshold
        h.scaleKeys(k)
        increment *= k
    }
}
