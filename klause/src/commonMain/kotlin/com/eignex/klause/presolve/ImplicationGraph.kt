package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.freeBooleanProbeCandidates
import com.eignex.klause.propagation.propagate
import com.eignex.klause.propagation.propagatedImplicationGraph
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.PollStride
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet
import com.eignex.util.collections.MutableIntIntMap
import com.eignex.util.collections.MutableLongIntMap

// Harvests `lit -> lit` implications by pinning free Booleans as [Probing] does, then collapses each
// mutual-implication cycle to one representative by renaming (rebuilt by [asRebuilds]), and drops each
// binary clause whose implication is already reachable through other binary clauses.
internal object ImplicationGraph {

    fun reduce(
        problem: Problem,
        maxCandidates: Int,
        cancellation: ExecutionControl,
        objectiveBoolVars: Set<Int> = emptySet(),
    ): PassDelta {
        if (problem.numBoolVars == 0 || cancellation()) return PassDelta()
        val implications = harvestImplications(problem, maxCandidates, cancellation)
        val reduction = reduceOver(problem, implications, objectiveBoolVars, cancellation) ?: return PassDelta()
        val rebuild = reduction.merges.asRebuilds()
        return PresolveShared.identityDelta(
            problem.factors,
            reduction.factors,
            reconstruct = rebuild.asSampleLift(),
        ).also { it.rebuild = rebuild }
    }

    // Pinning needs finite domains, so the source graph is exactly the clean binary clauses. Its cycles are
    // still equivalences every solution honours, and the rename and transitive reduction read nothing else.
    fun reduceSource(
        problem: Problem,
        cancellation: ExecutionControl,
        objectiveBoolVars: Set<Int> = emptySet(),
    ): SourceDelta {
        if (problem.numBoolVars == 0) return SourceDelta()
        val implications = binaryClauseImplications(problem, problem.factors.asList(), GraphWork(cancellation))
            ?: return SourceDelta()
        val reduction = reduceOver(problem, implications, objectiveBoolVars, cancellation) ?: return SourceDelta()
        return PresolveShared.sourceIdentityDelta(problem.factors, reduction.factors, reduction.merges.asRebuilds())
    }

    private fun reduceOver(
        problem: Problem,
        implications: Adjacency,
        objectiveBoolVars: Set<Int>,
        cancellation: ExecutionControl,
    ): Reduction? {
        val work = GraphWork(cancellation)
        if (!work.check()) return null
        val candidates = equivalentVariableMerges(problem.numBoolVars, implications, objectiveBoolVars, work)
            ?: return null
        val merges = compatibleMerges(problem, candidates, work) ?: return null
        val original = problem.factors.asList()
        val substituted = if (merges.isEmpty()) original else applyMerges(problem, merges, work) ?: return null
        val reduced = dropTransitivelyRedundantBinaries(problem, substituted, work)
        // A rename/drop leaves survivors identity-equal to inputs; a pure no-op keeps the same list.
        if (merges.isEmpty() && reduced === original) return null
        return Reduction(reduced, merges)
    }

    private class Reduction(val factors: List<Factor>, val merges: List<BoolMerge>)

    private fun binaryClauseImplications(
        problem: Problem,
        factors: List<Factor>,
        work: GraphWork,
    ): Adjacency? {
        if (!work.check()) return null
        val adj = Adjacency(2 * problem.numBoolVars)
        for (f in factors) {
            if (!work.visit()) return null
            if (!isCleanBinary(f, problem.numBoolVars)) continue
            val (a, b) = implicationEdges(f as Clause)
            if (!work.visit()) return null
            adj.addEdge(a.first, a.second)
            if (!work.visit()) return null
            adj.addEdge(b.first, b.second)
        }
        return adj
    }

    // An atom literal has no node in the literal graph.
    private fun isCleanBinary(f: Factor, numBoolVars: Int): Boolean =
        f is Clause && f.literals.size == 2 && f.allLiteralsBool(numBoolVars)

    // Pinning `p` and reading a forced `q` records `p -> q`, sound because propagation is. A failed literal
    // contributes no edges; it is left to [Probing]. The round engine re-enters past [maxCandidates].
    private fun harvestImplications(problem: Problem, maxCandidates: Int, cancellation: ExecutionControl): Adjacency {
        val adj = Adjacency(2 * problem.numBoolVars)
        for (v in problem.freeBooleanProbeCandidates(maxCandidates, cancellation)) {
            if (cancellation()) break
            recordPolarity(problem, v, value = true, adj, cancellation)
            recordPolarity(problem, v, value = false, adj, cancellation)
        }
        return adj
    }

    // `result(lit)` lists every literal pinning `lit` forces, indexed by `Lit.make`.
    @Suppress("MemberNameEqualsClassName") // the graph this object builds is the natural name for the builder
    fun implicationGraph(
        problem: Problem,
        maxCandidates: Int,
        cancellation: ExecutionControl = ExecutionControl.Never,
    ): Array<IntArray> = problem.propagatedImplicationGraph(maxCandidates, cancellation)

    private fun recordPolarity(problem: Problem, v: Int, value: Boolean, adj: Adjacency,
        cancellation: ExecutionControl) {
        if (cancellation()) return
        val result = problem.propagate(Assumptions.None.withBool(v, value), cancellation)
        if (result !is PropagationResult.Implied) return
        val from = Lit.make(v, value)
        for (k in result.boolKeys.indices) {
            cancellation.charge(IMPLICATION_GRAPH_WORK_WEIGHT)
            if (cancellation()) return
            val w = result.boolKeys[k]
            if (w != v) adj.addEdge(from, Lit.make(w, result.boolValues[k]))
        }
    }

    // Merges `w` into representative `r` (the smallest id in its SCC) when `r+` and `w+` share a
    // component. Only same polarity: `Factor.remap` renames ids and cannot flip polarity, since a
    // reification aux var has no literal. A component holding both polarities of one variable is an
    // inconsistency left to [Probing]. Objective variables never merge: a merged variable is left
    // unconstrained, and the objective must keep reading a constrained one.
    private fun equivalentVariableMerges(
        numBoolVars: Int,
        adj: Adjacency,
        objectiveBoolVars: Set<Int>,
        work: GraphWork,
    ): List<BoolMerge>? {
        val componentOf = stronglyConnectedComponents(adj, work) ?: return null
        // Representative variable per literal-SCC: the smallest variable id whose positive literal sits
        // in that component. A component is mergeable only through positive literals (same polarity).
        val repVarOfComponent = MutableIntIntMap()
        for (vv in 0 until numBoolVars) {
            if (!work.visit()) return null
            if (vv in objectiveBoolVars) continue
            val pos = componentOf[Lit.make(vv, true)]
            val neg = componentOf[Lit.make(vv, false)]
            if (pos == neg) continue // u ⇔ ¬u: failed-literal territory
            if (!repVarOfComponent.containsKey(pos) || vv < repVarOfComponent.getOrDefault(pos, 0)) {
                repVarOfComponent.put(pos, vv)
            }
        }
        val merges = ArrayList<BoolMerge>()
        for (vv in 0 until numBoolVars) {
            if (!work.visit()) return null
            if (vv in objectiveBoolVars) continue
            val pos = componentOf[Lit.make(vv, true)]
            val neg = componentOf[Lit.make(vv, false)]
            if (pos == neg) continue
            if (!repVarOfComponent.containsKey(pos)) continue
            val rep = repVarOfComponent.getOrDefault(pos, 0)
            if (rep != vv) merges.add(BoolMerge(from = vv, into = rep))
        }
        return merges
    }

    // Drops clauses the rename makes tautological. The Boolean count is preserved; a merged variable stops
    // appearing in any factor and is rebuilt on the way back.
    private fun applyMerges(problem: Problem, merges: List<BoolMerge>, work: GraphWork): List<Factor>? {
        val boolMap = IntArray(problem.numBoolVars)
        for (v in boolMap.indices) {
            if (!work.visit()) return null
            boolMap[v] = v
        }
        for (m in merges) {
            if (!work.visit()) return null
            boolMap[m.from] = m.into
        }
        val intMap = IntArray(problem.numIntVars)
        for (v in intMap.indices) {
            if (!work.visit()) return null
            intMap[v] = v
        }
        val mapping = VarRemap(boolMap, intMap)
        val out = ArrayList<Factor>(problem.factors.size)
        for (f in problem.factors) {
            if (!work.visit()) return null
            val remapped = f.remap(mapping)
            if (remapped is Clause && isTautology(remapped, work)) continue
            out.add(remapped)
        }
        return if (work.check()) out else null
    }

    // Drops any merge that would rename a [ReifiedPseudoBoolean]'s auxiliary and a body variable onto one
    // variable; both keep their own ids.
    private fun compatibleMerges(
        problem: Problem,
        candidates: List<BoolMerge>,
        work: GraphWork,
    ): List<BoolMerge>? {
        if (candidates.isEmpty()) return candidates
        val boolMap = IntArray(problem.numBoolVars)
        for (v in boolMap.indices) {
            if (!work.visit()) return null
            boolMap[v] = v
        }
        for (merge in candidates) {
            if (!work.visit()) return null
            boolMap[merge.from] = merge.into
        }
        for (factor in problem.factors) {
            if (!work.visit()) return null
            when (factor) {
                is ReifiedPseudoBoolean -> restoreAuxiliarySeparation(factor, boolMap, work)
            }
        }
        val compatible = ArrayList<BoolMerge>()
        for (candidate in candidates) {
            if (!work.visit()) return null
            if (boolMap[candidate.from] == candidate.into) compatible.add(candidate)
        }
        return compatible
    }

    private fun restoreAuxiliarySeparation(factor: ReifiedPseudoBoolean, boolMap: IntArray, work: GraphWork) {
        val auxiliary = factor.auxBoolVar
        for (literal in factor.literals) {
            if (!work.visit()) return
            val bodyVariable = Lit.variable(literal)
            if (boolMap[auxiliary] == boolMap[bodyVariable]) {
                boolMap[auxiliary] = auxiliary
                boolMap[bodyVariable] = bodyVariable
            }
        }
    }

    private fun isTautology(clause: Clause, work: GraphWork): Boolean {
        val seen = IntHashSet(clause.literals.size)
        for (lit in clause.literals) {
            if (!work.visit()) return false
            if (Lit.negate(lit) in seen) return true
            seen.add(lit)
        }
        return false
    }

    // A binary clause `a -> b` is redundant when `b` is reachable from `a` over the kept binary clauses.
    // Clauses are settled one at a time, removing dropped edges before the next test; judging all against
    // the whole graph would let the clauses of one cycle vouch for each other and all drop, losing the
    // equivalence. Edges are counted per clause, so a duplicate keeps exactly one copy. Returns [factors]
    // itself when nothing drops.
    private fun dropTransitivelyRedundantBinaries(
        problem: Problem,
        factors: List<Factor>,
        work: GraphWork,
    ): List<Factor> {
        val binaryIndices = IntArrayList()
        factors.forEachIndexed { i, f ->
            if (!work.visit()) return factors
            if (isCleanBinary(f, problem.numBoolVars)) binaryIndices.add(i)
        }
        if (binaryIndices.size < 2) return factors

        val adj = binaryClauseImplications(problem, factors, work) ?: return factors
        val live = MutableLongIntMap()
        binaryIndices.forEach { i ->
            if (!work.visit()) return factors
            val (e1, e2) = implicationEdges(factors[i] as Clause)
            live.addTo(e1.key, 1)
            live.addTo(e2.key, 1)
        }

        val drop = IntHashSet()
        for (k in 0 until binaryIndices.size) {
            if (!work.visit()) break
            val i = binaryIndices[k]
            val (e1, e2) = implicationEdges(factors[i] as Clause)
            live.addTo(e1.key, -1)
            live.addTo(e2.key, -1)
            // Checking one direction suffices: the contrapositive is reachable iff the forward one is.
            val reachable = reachable(adj, live, e1.first, e1.second, work)
            if (reachable == true) {
                drop.add(i)
            } else {
                live.addTo(e1.key, 1)
                live.addTo(e2.key, 1)
            }
            if (reachable == null) break
        }
        if (drop.isEmpty()) return factors

        val kept = ArrayList<Factor>(factors.size - drop.size)
        factors.forEachIndexed { i, f ->
            if (!work.visit()) return factors
            if (i !in drop) kept.add(f)
        }
        return kept
    }

    private fun implicationEdges(clause: Clause): Pair<Edge, Edge> {
        val p = clause.literals[0]
        val q = clause.literals[1]
        return Edge(Lit.negate(p), q) to Edge(Lit.negate(q), p)
    }

    private fun reachable(
        adj: Adjacency,
        live: MutableLongIntMap,
        source: Int,
        target: Int,
        work: GraphWork,
    ): Boolean? {
        val stack = IntArrayList()
        val seen = IntHashSet()
        stack.add(source)
        seen.add(source)
        while (!stack.isEmpty()) {
            if (!work.visit()) return null
            val node = stack.last()
            stack.removeAtSwap(stack.size - 1)
            adj.neighbours(node).forEach { next ->
                if (!work.visit()) return null
                if (next !in seen && live.getOrDefault(Edge(node, next).key, 0) > 0) {
                    if (next == target) return true
                    seen.add(next)
                    stack.add(next)
                }
            }
        }
        return false
    }

    // R. Tarjan, "Depth-first search and linear graph algorithms", SIAM J. Comput. 1972,
    // https://doi.org/10.1137/0201010. Iterative so a deep implication chain cannot overflow the native stack.
    private fun stronglyConnectedComponents(adj: Adjacency, work: GraphWork): IntArray? {
        if (!work.check()) return null
        val n = adj.nodeCount
        val index = IntArray(n)
        val low = IntArray(n)
        val onStack = BooleanArray(n)
        val component = IntArray(n)
        val tarjanStack = IntArrayList()
        var nextIndex = 1
        var nextComponent = 0

        // Explicit DFS: each frame tracks the node and how far its neighbour list has been walked.
        val callNode = IntArrayList()
        val callNeighbour = IntArrayList()
        for (root in 0 until n) {
            if (!work.visit()) return null
            if (index[root] != 0) continue
            callNode.add(root)
            callNeighbour.add(0)
            while (!callNode.isEmpty()) {
                if (!work.visit()) return null
                val node = callNode[callNode.size - 1]
                if (callNeighbour[callNeighbour.size - 1] == 0 && index[node] == 0) {
                    index[node] = nextIndex
                    low[node] = nextIndex
                    nextIndex++
                    tarjanStack.add(node)
                    onStack[node] = true
                }
                val neighbours = adj.neighbours(node)
                var ni = callNeighbour[callNeighbour.size - 1]
                var descended = false
                while (ni < neighbours.size) {
                    if (!work.visit()) return null
                    val next = neighbours[ni]
                    if (index[next] == 0) {
                        callNeighbour[callNeighbour.size - 1] = ni + 1
                        callNode.add(next)
                        callNeighbour.add(0)
                        descended = true
                        break
                    } else if (onStack[next]) {
                        if (index[next] < low[node]) low[node] = index[next]
                    }
                    ni++
                }
                if (descended) continue
                if (index[node] == low[node]) {
                    while (true) {
                        if (!work.visit()) return null
                        val w = tarjanStack.last()
                        tarjanStack.removeAtSwap(tarjanStack.size - 1)
                        onStack[w] = false
                        component[w] = nextComponent
                        if (w == node) break
                    }
                    nextComponent++
                }
                callNode.removeAtSwap(callNode.size - 1)
                callNeighbour.removeAtSwap(callNeighbour.size - 1)
                if (!callNode.isEmpty()) {
                    val parent = callNode[callNode.size - 1]
                    if (low[node] < low[parent]) low[parent] = low[node]
                }
            }
        }
        return if (work.check()) component else null
    }

    private class GraphWork(private val cancellation: ExecutionControl) {
        private val poll = PollStride()
        private val budget = cancellation.presolveBudget()
        private var stopped = false

        fun check(): Boolean {
            if (!stopped && cancellation()) stopped = true
            return !stopped
        }

        fun visit(): Boolean {
            if (stopped) return false
            cancellation.charge(IMPLICATION_GRAPH_WORK_WEIGHT)
            return if (budget?.remaining() == 0L || poll.due()) check() else true
        }
    }

    private data class Edge(val first: Int, val second: Int) {
        val key: Long get() = (first.toLong() shl Int.SIZE_BITS) or (second.toLong() and 0xFFFF_FFFFL)
    }

    private class Adjacency(val nodeCount: Int) {
        private val out = arrayOfNulls<IntArrayList>(nodeCount)
        private val empty = IntArrayList()

        fun addEdge(from: Int, to: Int) {
            if (from == to) return
            (out[from] ?: IntArrayList().also { out[from] = it }).add(to)
        }

        fun neighbours(node: Int): IntArrayList = out[node] ?: empty
    }
}

internal class BoolMerge(val from: Int, val into: Int)

// A representative is never itself merged away, so the merges rebuild in the order they were found.
internal fun List<BoolMerge>.asRebuilds(): SourceRebuilds =
    SourceRebuilds(map { RebuildStep.CopyLiteral(it.from, Lit.make(it.into, true)) })

// Work units per implication edge or graph node visited, relative to one simplex operation.
private const val IMPLICATION_GRAPH_WORK_WEIGHT = 10L
