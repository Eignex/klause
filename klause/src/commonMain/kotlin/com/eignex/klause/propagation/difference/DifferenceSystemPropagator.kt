package com.eignex.klause.propagation.difference

import com.eignex.klause.arithmetic.difference.DifferenceEdge
import com.eignex.klause.arithmetic.difference.DifferenceFragment
import com.eignex.klause.arithmetic.difference.indexOfSorted
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayList

/**
 * Heads a single refutation sweep visits before yielding.
 *
 * A sweep costs one shortest-path search per head, and this window is what bounds that — measured, not
 * assumed. On a fully bounded model every sweep offers all 1403 heads and every one of 8000 sweeps
 * exceeded the window, because [DifferenceSystemPropagator.headsToSweep] narrows nothing there: the
 * distances through the constant node move on each sweep, which widens the head set back to all of them.
 * Removing the window costs 2.3-2.7x at an identical 4000 nodes.
 *
 * Visiting a rotating window still reaches every head across successive calls — a refutation is deferred,
 * never dropped, and a deferred one only leaves an edge open that the Boolean layer may still decide, so
 * the verdict is unaffected either way.
 */
private const val HEAD_SWEEP_BUDGET = 64

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
 * **The constant node is measured, not walked.** A bounded column contributes `zero → v` and `v → zero`,
 * so on a model whose columns are all bounded that node has degree `2n`: the graph is strongly connected,
 * every shortest-path search spans it, and no reachability argument narrows a sweep (#1529). Those edges
 * stay in the system — the potential has to remain feasible for them, and a cycle through one is a real
 * conflict — but a per-head search does not walk them. Instead
 * [IncrementalDifferenceGraph.refreshZeroDistances] measures the distance to and from that node once per
 * sweep, and a head reads the route off as `d(y → x) = min(d(y ⇝ x), d(y ⇝ zero) + d(zero ⇝ x))`.
 *
 * That is exact, not an approximation. A shortest path visits the node at most once — a second visit
 * encloses a cycle, which is either negative, and so a conflict, or non-negative, and so removable — so
 * splitting at that single visit accounts for every route, including the ones that reach it through model
 * rows rather than straight off an endpoint's own range. Both halves keep their own predecessor chain,
 * which is what lets a refutation routed that way still name the guards on each segment.
 *
 * Scott Cotton and Oded Maler, "Fast and Flexible Difference Constraint Propagation for DPLL(T)",
 * SAT 2006, LNCS 4121.
 */
internal class DifferenceSystemPropagator(edges: List<DifferenceEdge>) : Propagator {

    private val guards: IntArray
    private val tail: IntArray
    private val head: IntArray
    private val bound: LongArray
    private val numVertices: Int
    private val nodes: IntArray

    /** Which edges state a declared range; the graph keeps them but no per-head search walks them. */
    private val hub: BooleanArray

    /** The vertex standing for the constant 0, which every declared-range edge is incident to. */
    private val zeroVertex: Int get() = numVertices - 1

    // Guarded edges bucketed by head vertex: every edge in a bucket is refuted or not by the distances
    // out of that one vertex, so a bucket costs a single shortest-path search rather than one per edge.
    private val bucketVertex: IntArray
    private val bucketStart: IntArray
    private val bucketEdge: IntArray

    // Reverse index over every edge: walked from a vertex it reaches the vertices that can reach it, which
    // is what narrows a sweep to the heads an assertion could have moved.
    private val revStart: IntArray
    private val revEdge: IntArray

    /**
     * Per-solve mutable half, kept on [PropagationState.refPayload] rather than on the propagator: one
     * propagator instance backs every arm of a portfolio, so the graph and its explanation cannot live
     * on the shared object. It needs no reversible storage — edge activity is re-read from the state on
     * every fire, and a potential feasible for a set stays feasible for any subset of it, so a backtrack
     * leaves the structure correct without any undo.
     */
    private class Session(numVertices: Int, tail: IntArray, head: IntArray, bound: LongArray, hub: BooleanArray) {
        val graph = IncrementalDifferenceGraph(numVertices, tail, head, bound, hub)
        val pending = IntArrayList()
        val pendingTails = IntArrayList()
        var reason: IntArray? = null

        /** Bumped whenever an edge enters or leaves the graph, so a sweep can tell the system apart. */
        var version: Int = 0

        /** Bumped only when an edge is asserted, which is the one way a path can shorten. */
        var assertVersion: Int = 0
        var sweptVersion: Int = -1
        var sweptDecisions: Int = -1

        /** Tails of the edges asserted since the last sweep; see [headsToSweep]. */
        val newTails = IntArrayList()

        /** Rotating start into the head list, so a budgeted sweep covers every head over successive calls. */
        var headCursor = 0
        private val reached = IntArray(numVertices)
        private val queue = IntArrayList()
        private var stamp = 0

        /** Mark [v] reached in the current traversal, false when it already was. */
        fun reach(v: Int): Boolean {
            if (reached[v] == stamp) return false
            reached[v] = stamp
            queue.add(v)
            return true
        }

        fun beginTraversal() {
            stamp++
            queue.clear()
        }

        fun wasReached(v: Int): Boolean = reached[v] == stamp

        fun traversalQueue(): IntArrayList = queue
    }

    init {
        // Numbered over every endpoint, including those only a domain edge mentions, so the vertex space
        // does not depend on which edges the graph ends up holding.
        val seen = HashSet<Int>()
        for (e in edges) {
            if (e.source != DifferenceFragment.ZERO) seen.add(e.source)
            if (e.target != DifferenceFragment.ZERO) seen.add(e.target)
        }
        nodes = seen.toIntArray().sortedArray()
        val zeroNode = nodes.size
        fun nodeOf(endpoint: Int) =
            if (endpoint == DifferenceFragment.ZERO) zeroNode else indexOfSorted(nodes, endpoint)
        numVertices = nodes.size + 1

        guards = IntArray(edges.size)
        tail = IntArray(edges.size)
        head = IntArray(edges.size)
        bound = LongArray(edges.size)
        hub = BooleanArray(edges.size)
        edges.forEachIndexed { i, e ->
            guards[i] = e.guard
            tail[i] = nodeOf(e.source)
            head[i] = nodeOf(e.target)
            bound[i] = e.bound
            hub[i] = e.domainBound
        }

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

        val revCounts = IntArray(numVertices + 1)
        for (i in guards.indices) revCounts[head[i] + 1]++
        for (v in 1 until revCounts.size) revCounts[v] += revCounts[v - 1]
        revStart = revCounts
        revEdge = IntArray(guards.size)
        val revFill = revCounts.copyOf()
        for (i in guards.indices) revEdge[revFill[head[i]]++] = i
    }

    /**
     * The heads a sweep still has to visit after the assertions in [Session.newTails], or [bucketVertex]
     * when every head has to be revisited.
     *
     * Asserting `a -> b` can only shorten a path `y ~> x` that runs through it, which needs `y ~> a`. A
     * head that reaches no newly-asserted tail therefore cannot have gained a refutation, and searching
     * from it again would repeat the previous answer.
     *
     * Retraction is deliberately not a trigger: it only lengthens paths, so it can retire a refutation but
     * never create one, and the pins already made stand until the search backtracks past them.
     */
    private fun headsToSweep(session: Session): IntArray {
        if (session.newTails.size == 0) return bucketVertex
        val graph = session.graph
        session.beginTraversal()
        for (i in 0 until session.newTails.size) session.reach(session.newTails[i])
        val queue = session.traversalQueue()
        var read = 0
        while (read < queue.size) {
            val u = queue[read++]
            for (i in revStart[u] until revStart[u + 1]) {
                val e = revEdge[i]
                if (graph.isActive(e)) session.reach(tail[e])
            }
        }
        val out = IntArrayList()
        for (v in bucketVertex) if (session.wasReached(v)) out.add(v)
        return out.toIntArray()
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val session = (state.refPayload[factorId] as? Session)
            ?: Session(numVertices, tail, head, bound, hub).also { state.refPayload[factorId] = it }
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
            session.assertVersion++
            session.newTails.add(tail[e])
            if (cycle == null) continue
            session.reason = blockingClause(cycle)
            return false
        }
        // Nothing to re-derive while the asserted system stands as it was swept and the search has only
        // gone deeper: the same edges refute the same open ones, and those pins are still in force.
        val descended = state.numDecisions >= session.sweptDecisions
        if (session.assertVersion == session.sweptVersion && descended) {
            session.newTails.clear()
            return true
        }
        // At most one sweep per decision. The fixpoint wakes this propagator repeatedly as other factors
        // move domains — roughly twenty times per node on a large SMT model — and each waking that
        // asserted an edge would otherwise sweep again over a state the rest of the fixpoint has not
        // finished settling. Deferring to the next decision defers a refutation, never drops one: the
        // edge stays open and the Boolean layer may decide it in the meantime, which the sweep then reads
        // off the trail. Assertion and retraction above stay eager, because a negative cycle is a
        // conflict and must be seen on the trail that produced it.
        if (state.numDecisions == session.sweptDecisions) {
            session.newTails.clear()
            return true
        }
        // Refreshed before the heads are chosen: a route through the constant node can shorten for a pair
        // the row-only reachability below would never reach, so a move there widens the sweep to all of
        // them. Twice per sweep, against once per head had the node stayed a hub.
        val hubMoved = graph.refreshZeroDistances(zeroVertex)
        // A backtrack releases the guards earlier sweeps pinned, so every head has to be revisited; a
        // descent only has to revisit the heads the new assertions could have moved.
        val heads = if (descended && !hubMoved) headsToSweep(session) else bucketVertex
        session.newTails.clear()
        session.sweptVersion = session.assertVersion
        session.sweptDecisions = state.numDecisions
        return refuteOpenEdges(state, session, heads)
    }

    /** Whether edge [e] currently holds, so it belongs in the graph. */
    private fun asserted(state: PropagationState, e: Int): Boolean =
        guards[e] == DifferenceEdge.ALWAYS || state.litTrue(guards[e])

    /**
     * Falsify the guard of every open edge the asserted system already contradicts. Edge `x → y` of
     * weight `w` closes a negative cycle exactly when the shortest asserted path `y ⇝ x` has weight
     * `d` with `d + w < 0`, so one search out of `y` decides every open edge whose head is `y`.
     */
    private fun refuteOpenEdges(state: PropagationState, session: Session, heads: IntArray): Boolean {
        val graph = session.graph
        val budget = if (heads.size <= HEAD_SWEEP_BUDGET) heads.size else HEAD_SWEEP_BUDGET
        for (k in 0 until budget) {
            val v = heads[(session.headCursor + k) % heads.size]
            session.pending.clear()
            session.pendingTails.clear()
            for (i in bucketStart[v] until bucketStart[v + 1]) {
                val e = bucketEdge[i]
                if (graph.isActive(e) || state.litTruth(guards[e]) != null) continue
                session.pending.add(e)
                session.pendingTails.add(tail[e])
            }
            if (session.pending.size == 0) continue
            // The route through the constant node is already measured, so it refutes without a search and
            // spares the search from settling that edge's tail at all.
            var open = false
            for (k in 0 until session.pending.size) {
                val e = session.pending[k]
                if (state.litTruth(guards[e]) != null) continue
                if (closesNegativeCycle(hubDistance(graph, v, tail[e]), bound[e])) {
                    val route = graph.pathToZeroFrom(v) + graph.pathFromZeroTo(tail[e])
                    if (!refute(state, session, e, blockingClause(route))) return false
                    continue
                }
                open = true
            }
            if (!open) continue
            graph.shortestPathsFrom(v, session.pendingTails.toIntArray())
            for (k in 0 until session.pending.size) {
                val e = session.pending[k]
                if (state.litTruth(guards[e]) != null) continue // an earlier refutation in this sweep decided it
                val d = graph.distanceTo(tail[e])
                if (!closesNegativeCycle(d, bound[e])) continue
                if (!refute(state, session, e, blockingClause(graph.pathTo(tail[e])))) return false
            }
            session.headCursor = if (heads.size == 0) 0 else (session.headCursor + budget) % heads.size
        }
        return true
    }

    /** Pin edge [e]'s guard false, explained by [antecedents] — the guards on whatever refuted it. */
    private fun refute(state: PropagationState, session: Session, e: Int, antecedents: IntArray): Boolean {
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
        // Guard order is the edge order, and an edge set is small, so a linear membership check
        // costs less than a hash set and keeps the emitted clause deterministic.
        val lits = IntArrayList(edges.size)
        for (e in edges) {
            val g = guards[e]
            if (g == DifferenceEdge.ALWAYS) continue
            val negated = Lit.negate(g)
            if (!lits.contains(negated)) lits.add(negated)
        }
        return if (lits.isEmpty()) EmptyIntArray else lits.toIntArray()
    }

    /**
     * Weight of the shortest route `from ⇝ zero ⇝ to`, or [NO_BOUND] when either half is unreachable or
     * the pair cannot be summed inside [Long].
     *
     * The clamp an unbounded model invents is ±2^62, so two of them sum past [Long] — an overflowing pair
     * is reported as no route at all rather than as a wrapped one.
     */
    private fun hubDistance(graph: IncrementalDifferenceGraph, from: Int, to: Int): Long {
        val a = graph.distanceToZeroFrom(from)
        val b = graph.distanceFromZeroTo(to)
        if (a == NO_BOUND || b == NO_BOUND) return NO_BOUND
        val sum = a + b
        return if (((a xor sum) and (b xor sum)) < 0L) NO_BOUND else sum
    }

    /**
     * Whether an edge of weight [weight] closes a negative cycle against a path of weight [distance],
     * which is the test that refutes it. An absent or unreachable distance decides nothing, and neither
     * does a sum that leaves [Long].
     */
    private fun closesNegativeCycle(distance: Long, weight: Long): Boolean {
        if (distance == NO_BOUND) return false
        val sum = distance + weight
        if (((distance xor sum) and (weight xor sum)) < 0L) return false
        return sum < 0L
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        (state.refPayload[factorId] as? Session)?.reason

    private companion object {
        /**
         * A column side no declared domain states. Shares its value with
         * [IncrementalDifferenceGraph.UNREACHABLE] deliberately: both mean "no path of any weight", and
         * [closesNegativeCycle] rejects the two alike.
         */
        const val NO_BOUND: Long = Long.MAX_VALUE
    }
}
