package com.eignex.klause.propagation.difference

import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Lit
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayList

/**
 * Joint propagation of a [DifferenceSystem] in the DPLL(T) shape: the asserted edges are consistent
 * exactly when their graph admits a potential function, and an unasserted edge whose guard is still open
 * is *refuted* as soon as the asserted edges already carry a path that would close a negative cycle
 * through it.
 *
 * The second half is what makes the propagator more than a final check. On a real difference-logic
 * instance nearly every edge is guarded by a reified row's aux, so a check that only fires once the
 * Boolean layer has decided the guards learns a nogood after the fact and never prunes ahead of one.
 * Refuting the guard turns the graph into a source of implications the Boolean layer can search on.
 *
 * A deduction is explained by the guards on the path (or cycle) that forced it, so the learned clause
 * names exactly the reified rows whose conjunction is contradictory.
 *
 * Scott Cotton and Oded Maler, "Fast and Flexible Difference Constraint Propagation for DPLL(T)",
 * SAT 2006, LNCS 4121.
 */
internal class DifferenceSystemPropagator(edges: List<DifferenceEdge>) : Propagator {

    private val guards = IntArray(edges.size)
    private val tail = IntArray(edges.size)
    private val head = IntArray(edges.size)
    private val bound = LongArray(edges.size)
    private val numVertices: Int

    // Guarded edges bucketed by head vertex: every edge in a bucket is refuted or not by the distances
    // out of that one vertex, so a bucket costs a single shortest-path search rather than one per edge.
    private val bucketVertex: IntArray
    private val bucketStart: IntArray
    private val bucketEdge: IntArray

    /**
     * Per-solve mutable half, kept on [PropagationState.refPayload] rather than on the propagator: one
     * propagator instance backs every arm of a portfolio, so the graph and its explanation cannot live
     * on the shared object. It needs no reversible storage — edge activity is re-read from the state on
     * every fire, and a potential feasible for a set stays feasible for any subset of it, so a backtrack
     * leaves the structure correct without any undo.
     */
    private class Session(numVertices: Int, tail: IntArray, head: IntArray, bound: LongArray) {
        val graph = IncrementalDifferenceGraph(numVertices, tail, head, bound)
        val pending = IntArrayList()
        val pendingTails = IntArrayList()
        var reason: IntArray? = null

        /** Bumped whenever an edge enters or leaves the graph, so a sweep can tell the system apart. */
        var version: Int = 0
        var sweptVersion: Int = -1
        var sweptDecisions: Int = -1
    }

    init {
        val seen = HashSet<Int>()
        for (e in edges) {
            if (e.source != DifferenceFragment.ZERO) seen.add(e.source)
            if (e.target != DifferenceFragment.ZERO) seen.add(e.target)
        }
        val nodes = seen.toIntArray().sortedArray()
        val zeroNode = nodes.size
        fun nodeOf(endpoint: Int) =
            if (endpoint == DifferenceFragment.ZERO) zeroNode else indexOfSorted(nodes, endpoint)
        edges.forEachIndexed { i, e ->
            guards[i] = e.guard
            tail[i] = nodeOf(e.source)
            head[i] = nodeOf(e.target)
            bound[i] = e.bound
        }
        numVertices = nodes.size + 1

        val counts = IntArray(nodes.size + 2)
        var guarded = 0
        for (i in guards.indices) {
            if (guards[i] == DifferenceEdge.ALWAYS) continue
            counts[head[i] + 1]++
            guarded++
        }
        for (v in 1 until counts.size) counts[v] += counts[v - 1]
        bucketStart = counts
        bucketEdge = IntArray(guarded)
        val fill = counts.copyOf()
        for (i in guards.indices) {
            if (guards[i] == DifferenceEdge.ALWAYS) continue
            bucketEdge[fill[head[i]]++] = i
        }
        val vertices = IntArrayList()
        for (v in 0..nodes.size) if (bucketStart[v] < bucketStart[v + 1]) vertices.add(v)
        bucketVertex = vertices.toIntArray()
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val session = (state.refPayload[factorId] as? Session)
            ?: Session(numVertices, tail, head, bound).also { state.refPayload[factorId] = it }
        session.reason = null
        val graph = session.graph
        if (!graph.usable) return true
        // Retraction must complete before any assertion: an edge held by a branch the search has left
        // would otherwise contribute a path that the current state does not contain.
        for (e in guards.indices) {
            if (!graph.isActive(e) || asserted(state, e)) continue
            graph.retract(e)
            session.version++
        }
        for (e in guards.indices) {
            if (graph.isActive(e) || !asserted(state, e)) continue
            val cycle = graph.assertEdge(e)
            session.version++
            if (cycle == null) continue
            session.reason = blockingClause(cycle)
            return false
        }
        // Nothing to re-derive when neither the graph nor the decision level has moved since the last
        // sweep: the same asserted edges refute the same open ones, and those pins are still in force.
        if (session.version == session.sweptVersion && state.numDecisions == session.sweptDecisions) return true
        session.sweptVersion = session.version
        session.sweptDecisions = state.numDecisions
        return refuteOpenEdges(state, session)
    }

    /** Whether edge [e] currently holds, so it belongs in the graph. */
    private fun asserted(state: PropagationState, e: Int): Boolean =
        guards[e] == DifferenceEdge.ALWAYS || state.litTrue(guards[e])

    /**
     * Falsify the guard of every open edge the asserted system already contradicts. Edge `x → y` of
     * weight `w` closes a negative cycle exactly when the shortest asserted path `y ⇝ x` has weight
     * `d` with `d + w < 0`, so one search out of `y` decides every open edge whose head is `y`.
     */
    private fun refuteOpenEdges(state: PropagationState, session: Session): Boolean {
        val graph = session.graph
        for (v in bucketVertex) {
            session.pending.clear()
            session.pendingTails.clear()
            for (i in bucketStart[v] until bucketStart[v + 1]) {
                val e = bucketEdge[i]
                if (graph.isActive(e) || state.litTruth(guards[e]) != null) continue
                session.pending.add(e)
                session.pendingTails.add(tail[e])
            }
            if (session.pending.size == 0) continue
            graph.shortestPathsFrom(v, session.pendingTails.toIntArray())
            for (k in 0 until session.pending.size) {
                val e = session.pending[k]
                if (state.litTruth(guards[e]) != null) continue // an earlier refutation in this sweep decided it
                val d = graph.distanceTo(tail[e])
                if (d == IncrementalDifferenceGraph.UNREACHABLE || d + bound[e] >= 0L) continue
                if (!refute(state, session, e)) return false
            }
        }
        return true
    }

    /** Pin edge [e]'s guard false, explained by the guards on the path that refutes it. */
    private fun refute(state: PropagationState, session: Session, e: Int): Boolean {
        val antecedents = blockingClause(session.graph.pathTo(tail[e]))
        val lit = Lit.negate(guards[e])
        if (state.pinLit(lit, antecedents)) return true
        session.reason = antecedents + lit
        return false
    }

    /**
     * The clause blocking a set of asserted [edges]: the negation of every guard on them. Each such
     * literal is false now — the edge is asserted, so its guard holds — which is what conflict analysis
     * requires of a seed. An all-unconditional set has no guards and yields the empty clause, which says
     * the system is unsatisfiable outright.
     */
    private fun blockingClause(edges: IntArray): IntArray {
        val lits = LinkedHashSet<Int>()
        for (e in edges) {
            val g = guards[e]
            if (g == DifferenceEdge.ALWAYS) continue
            lits.add(Lit.negate(g))
        }
        return if (lits.isEmpty()) EmptyIntArray else lits.toIntArray()
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        (state.refPayload[factorId] as? Session)?.reason
}
