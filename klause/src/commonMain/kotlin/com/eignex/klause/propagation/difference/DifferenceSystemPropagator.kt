package com.eignex.klause.propagation.difference

import com.eignex.klause.arithmetic.difference.DifferenceEdge
import com.eignex.klause.arithmetic.difference.DifferenceFragment
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.util.arrays.binarySearchInt
import com.eignex.util.arrays.emptyIntArray
import com.eignex.util.collections.IntArrayDeque
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet

// Open heads per sweep, each costing at most one shortest-path search. Deferred heads stay queued; a
// delayed refutation leaves its edge open for Boolean search, so soundness is unaffected.
private const val HEAD_SWEEP_BUDGET = 64
private const val HUB_REFUTATION_FAILED = -1

// Joint propagation of a DifferenceSystem in the DPLL(T) shape: the asserted edges are consistent exactly
// when their graph admits a potential function, and an open guarded edge is refuted as soon as the
// asserted edges carry a path that would close a negative cycle through it. Deductions are explained by
// the guards on the forcing path or cycle.
//
// The constant node is measured, not walked: with every column bounded it has degree 2n and would make
// every search span the graph. Its edges stay in the system, but refreshZeroDistances measures distances
// to and from it once per sweep, and a head reads `d(y, x) = min(d(y ⇝ x), d(y ⇝ zero) + d(zero ⇝ x))`.
// This is exact because a shortest path visits the node at most once.
//
// Scott Cotton and Oded Maler, "Fast and Flexible Difference Constraint Propagation for DPLL(T)",
// SAT 2006, LNCS 4121, https://doi.org/10.1007/11814948_19.
internal class DifferenceSystemPropagator(
    edges: List<DifferenceEdge>,
    private val indexGuards: Map<Int, DifferenceIndexGuard> = emptyMap(),
) : Propagator {

    private val guards: IntArray
    private val tail: IntArray
    private val head: IntArray
    private val bound: LongArray
    private val numVertices: Int
    private val nodes: IntArray

    // Declared-range edges; the graph keeps them but no per-head search walks them.
    private val hub: BooleanArray

    private val zeroVertex: Int get() = numVertices - 1

    // Guarded edges bucketed by head vertex, so a bucket costs a single shortest-path search.
    private val bucketVertex: IntArray
    private val bucketStart: IntArray
    private val bucketEdge: IntArray

    // Reverse index over every edge, narrowing a sweep to the heads an assertion could have moved.
    private val revStart: IntArray
    private val revEdge: IntArray

    // Per-solve state lives on refPayload because one propagator instance backs every portfolio arm. No
    // undo is needed: edge activity is re-read on every fire, and a potential feasible for a set stays
    // feasible for any subset.
    private class Session(
        numVertices: Int,
        tail: IntArray,
        head: IntArray,
        bound: LongArray,
        hub: BooleanArray,
        val guards: IntArray,
    ) {
        val graph = IncrementalDifferenceGraph(numVertices, tail, head, bound, hub)
        val pending = IntArrayList()
        val pendingTails = IntArrayList()
        var reason: IntArray? = null

        // Bumped only when an edge is asserted, the one way a path can shorten.
        var assertVersion: Int = 0
        var sweptVersion: Int = -1
        var sweptDecisions: Int = -1

        // Tails of the edges asserted since the last sweep.
        val newTails = IntArrayList()

        val heads = IntArrayDeque()
        private val queuedHeads = BooleanArray(numVertices)
        var retractedSinceSweep = false
        private val reached = IntArray(numVertices)
        private val queue = IntArrayList()
        private var stamp = 0

        fun queueHeads(vertices: IntArray) {
            for (v in vertices) {
                if (queuedHeads[v]) continue
                queuedHeads[v] = true
                heads.addLast(v)
            }
        }

        fun nextHead(): Int = heads.removeFirst().also { queuedHeads[it] = false }

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
        val seen = IntHashSet()
        for (e in edges) {
            if (e.source != DifferenceFragment.ZERO) seen.add(e.source)
            if (e.target != DifferenceFragment.ZERO) seen.add(e.target)
        }
        nodes = seen.toIntArray().also { it.sort() }
        val zeroNode = nodes.size
        fun nodeOf(endpoint: Int) =
            if (endpoint == DifferenceFragment.ZERO) zeroNode else nodes.binarySearchInt(endpoint).coerceAtLeast(-1)
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
            if (guards[i] == DifferenceEdge.ALWAYS && i !in indexGuards) continue
            counts[head[i] + 1]++
            guarded++
        }
        for (v in 1 until counts.size) counts[v] += counts[v - 1]
        bucketStart = counts
        bucketEdge = IntArray(guarded)
        val fill = counts.copyOf()
        for (i in guards.indices) {
            if (guards[i] == DifferenceEdge.ALWAYS && i !in indexGuards) continue
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

    // Asserting `a → b` can only shorten a path `y ⇝ x` through it, which needs `y ⇝ a`, so only heads
    // reaching a newly asserted tail can gain a refutation.
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
            ?: Session(numVertices, tail, head, bound, hub, resolveGuards(state))
                .also { state.refPayload[factorId] = it }
        session.reason = null
        val graph = session.graph
        if (!graph.usable) return true
        // Retraction must complete before any assertion: an edge held by a branch the search has left
        // would otherwise contribute a path that the current state does not contain.
        retractReleasedEdges(state, session)
        if (!assertHeldEdges(state, session)) return false
        // Completed heads need no revisit on descent while their asserted paths and implied pins survive.
        val descended = state.numDecisions >= session.sweptDecisions
        val changed = session.assertVersion != session.sweptVersion
        if (!changed && descended && !session.retractedSinceSweep && session.heads.isEmpty()) {
            session.newTails.clear()
            return true
        }
        // At most one sweep per decision, since the fixpoint wakes this propagator many times per node.
        // Deferring a refutation never drops it: the edge stays open for the Boolean layer. Assertion and
        // retraction stay eager because a negative cycle must be seen on the trail that produced it.
        if (state.numDecisions == session.sweptDecisions && !session.retractedSinceSweep) return true
        if (changed || !descended || session.retractedSinceSweep) {
            // A route through the constant can shorten outside row-only reachability, so it widens the
            // scheduled heads. Retraction can release implied guards even on a sibling at the same depth.
            val hubMoved = graph.refreshZeroDistances(zeroVertex)
            val heads = if (descended && !hubMoved && !session.retractedSinceSweep) {
                headsToSweep(session)
            } else {
                bucketVertex
            }
            session.queueHeads(heads)
            session.newTails.clear()
            session.retractedSinceSweep = false
            session.sweptVersion = session.assertVersion
        }
        session.sweptDecisions = state.numDecisions
        return refuteOpenEdges(state, session)
    }

    private fun resolveGuards(state: PropagationState): IntArray {
        if (indexGuards.isEmpty()) return guards
        return guards.copyOf().also { resolved ->
            for ((edge, selector) in indexGuards) {
                resolved[edge] = Lit.make(state.atomVarEq(selector.variable, selector.value), true)
            }
        }
    }

    // The scans over every edge are separate methods so each compiles apart from the rest of the fixpoint step.
    private fun retractReleasedEdges(state: PropagationState, session: Session) {
        val graph = session.graph
        for (e in session.guards.indices) {
            if (!graph.isActive(e) || asserted(state, session, e)) continue
            graph.retract(e)
            session.retractedSinceSweep = true
        }
    }

    private fun assertHeldEdges(state: PropagationState, session: Session): Boolean {
        val graph = session.graph
        for (e in session.guards.indices) {
            if (graph.isActive(e) || !asserted(state, session, e)) continue
            val cycle = graph.assertEdge(e)
            session.assertVersion++
            session.newTails.add(tail[e])
            if (cycle == null) continue
            session.reason = blockingClause(session, cycle)
            return false
        }
        return true
    }

    private fun asserted(state: PropagationState, session: Session, e: Int): Boolean =
        session.guards[e] == DifferenceEdge.ALWAYS || state.litTrue(session.guards[e])

    // Edge `x → y` of weight w closes a negative cycle exactly when the shortest asserted path `y ⇝ x` has
    // weight d with d + w < 0, so one search out of y decides every open edge with head y.
    private fun refuteOpenEdges(state: PropagationState, session: Session): Boolean {
        var openHeads = 0
        while (!session.heads.isEmpty() && openHeads < HEAD_SWEEP_BUDGET) {
            val v = session.nextHead()
            collectOpenEdges(state, session, v)
            if (session.pending.size == 0) continue
            openHeads++
            val open = refuteViaHub(state, session, v)
            if (open == HUB_REFUTATION_FAILED) return false
            if (open == 0) continue
            if (!refuteBySearch(state, session, v)) return false
        }
        return true
    }

    // A head's stages are separate methods so each JIT-compiles apart from the whole sweep.
    private fun collectOpenEdges(state: PropagationState, session: Session, v: Int) {
        val graph = session.graph
        session.pending.clear()
        session.pendingTails.clear()
        for (i in bucketStart[v] until bucketStart[v + 1]) {
            val e = bucketEdge[i]
            if (graph.isActive(e) || state.litTruth(session.guards[e]) != null) continue
            session.pending.add(e)
            session.pendingTails.add(tail[e])
        }
    }

    // Refutes through the already-measured constant node without a search. Returns how many pending edges
    // remain open, or HUB_REFUTATION_FAILED when a refutation conflicts.
    private fun refuteViaHub(state: PropagationState, session: Session, v: Int): Int {
        val graph = session.graph
        var open = 0
        for (k in 0 until session.pending.size) {
            val e = session.pending[k]
            if (state.litTruth(session.guards[e]) != null) continue
            if (closesNegativeCycle(hubDistance(graph, v, tail[e]), bound[e])) {
                val route = graph.pathToZeroFrom(v) + graph.pathFromZeroTo(tail[e])
                if (!refute(state, session, e, blockingClause(session, route))) return HUB_REFUTATION_FAILED
                continue
            }
            open++
        }
        return open
    }

    private fun refuteBySearch(state: PropagationState, session: Session, v: Int): Boolean {
        val graph = session.graph
        graph.shortestPathsFrom(v, session.pendingTails.toIntArray())
        for (k in 0 until session.pending.size) {
            val e = session.pending[k]
            if (state.litTruth(session.guards[e]) != null) continue // an earlier refutation in this sweep decided it
            val d = graph.distanceTo(tail[e])
            if (!closesNegativeCycle(d, bound[e])) continue
            if (!refute(state, session, e, blockingClause(session, graph.pathTo(tail[e])))) return false
        }
        return true
    }

    private fun refute(state: PropagationState, session: Session, e: Int, antecedents: IntArray): Boolean {
        val lit = Lit.negate(session.guards[e])
        if (state.pinLit(lit, antecedents)) return true
        session.reason = antecedents + lit
        return false
    }

    // Negated guards of asserted edges, all currently false as conflict analysis requires. An
    // all-unconditional set yields the empty clause: the system is unsatisfiable outright.
    private fun blockingClause(session: Session, edges: IntArray): IntArray {
        // Edge sets are small, so a linear membership check beats a hash set and keeps the clause deterministic.
        val lits = IntArrayList(edges.size)
        for (e in edges) {
            val g = session.guards[e]
            if (g == DifferenceEdge.ALWAYS) continue
            val negated = Lit.negate(g)
            if (!lits.contains(negated)) lits.add(negated)
        }
        return if (lits.isEmpty()) emptyIntArray() else lits.toIntArray()
    }

    // NO_BOUND when either half is unreachable or the sum overflows; two ±2^62 clamps of an unbounded model
    // can sum past Long.
    private fun hubDistance(graph: IncrementalDifferenceGraph, from: Int, to: Int): Long {
        val a = graph.distanceToZeroFrom(from)
        val b = graph.distanceFromZeroTo(to)
        if (a == NO_BOUND || b == NO_BOUND) return NO_BOUND
        val sum = a + b
        return if (((a xor sum) and (b xor sum)) < 0L) NO_BOUND else sum
    }

    // An unreachable distance or an overflowing sum decides nothing.
    private fun closesNegativeCycle(distance: Long, weight: Long): Boolean {
        if (distance == NO_BOUND) return false
        val sum = distance + weight
        if (((distance xor sum) and (weight xor sum)) < 0L) return false
        return sum < 0L
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        (state.refPayload[factorId] as? Session)?.reason

    private companion object {
        // Deliberately equal to IncrementalDifferenceGraph.UNREACHABLE: both mean no path of any weight.
        const val NO_BOUND: Long = Long.MAX_VALUE
    }
}
