package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.MutableIntIntMap

/**
 * Binary implication graph presolve. Harvests `lit -> lit` implications the way [Probing] pins a
 * literal — pin a free Boolean, run [Problem.propagate], and read every *other* Boolean propagation
 * forces — then exploits the graph two ways:
 *
 *  - **Equivalent-literal substitution**: literals on a mutual-implication cycle are logically equal,
 *    so the variables they name are interchangeable. Each cycle collapses to a single representative
 *    and every other member is substituted away (a plain rename via [com.eignex.klause.solver.Factor.remap],
 *    the same machinery affine aliasing uses), then rebuilt for the caller via
 *    [ImplicationReduction.reconstruct].
 *  - **Transitive reduction**: a binary clause `a -> b` whose conclusion is already reachable from
 *    `a` through *other* binary clauses is entailed by that chain, so propagation still derives it and
 *    the clause can be dropped.
 *
 * Clique discovery from the graph (deriving at-most-one sets from the implication structure) is
 * **deferred**: it does not compose cleanly with the substitution rename here and is better built on a
 * dedicated clique store.
 */
internal object ImplicationGraph {

    /**
     * Run the pass on [problem], bounded to [maxCandidates] pinned Booleans (mirroring [Probing]'s
     * per-invocation cap). Returns the reduced problem plus an [ImplicationReduction] whose
     * `reconstruct` lifts a solution back by copying each merged variable's value from its
     * representative; the round engine composes it with the other passes' reconstructs.
     */
    fun reduce(
        problem: Problem,
        maxCandidates: Int,
        cancellation: Cancellation,
        objectiveBoolVars: Set<Int> = emptySet(),
    ): PassDelta {
        if (problem.numBoolVars == 0) return PassDelta()

        val implications = harvestImplications(problem, maxCandidates, cancellation)
        val merges = compatibleMerges(
            problem,
            equivalentVariableMerges(problem.numBoolVars, implications, objectiveBoolVars),
        )

        val original = problem.factors.asList()
        val substituted = if (merges.isEmpty()) original else applyMerges(problem, merges)
        val reduced = dropTransitivelyRedundantBinaries(problem, substituted)

        // A rename/drop leaves survivors identity-equal to inputs; a pure no-op keeps the same list, which
        // [identityDelta] renders as an empty delta (== the fresh path's `=== problem` fixpoint signal).
        if (merges.isEmpty() && reduced === original) return PassDelta()
        return PresolveShared.identityDelta(
            problem.factors,
            reduced,
            reconstruct = ImplicationReduction(merges)::reconstruct,
        )
    }

    /**
     * Implications discovered by probing-style pinning, as a directed graph over **literals**
     * (`2·numBoolVars` nodes, literal `l` indexing node `l`). Pinning literal `p` and reading a forced
     * `q` records the edge `p -> q`; soundness is exactly probing's — `propagate` is sound, so a forced
     * `q` holds in every solution that sets `p`, which is what `p -> q` asserts. A pinned polarity that
     * propagates Unsat is a failed literal; it is left to [Probing] and contributes no edges.
     *
     * Bounded by [maxCandidates] free Booleans (in id order); the round engine re-enters the pass.
     */
    private fun harvestImplications(problem: Problem, maxCandidates: Int, cancellation: Cancellation): Adjacency {
        val adj = Adjacency(2 * problem.numBoolVars)
        var probed = 0
        var v = 0
        while (v < problem.numBoolVars && probed < maxCandidates) {
            if (cancellation()) break
            probed++
            recordPolarity(problem, v, value = true, adj, cancellation)
            recordPolarity(problem, v, value = false, adj, cancellation)
            v++
        }
        return adj
    }

    /** The binary-implication graph of [problem] as a literal-indexed adjacency: `result[lit]` lists
     *  every literal that pinning `lit` forces (edge `lit -> forced`), discovered by sound
     *  probing-style pinning. Bounded to [maxCandidates] pinned Booleans. Consumers index by [Lit.make]. */
    @Suppress("MemberNameEqualsClassName") // the graph this object builds is the natural name for the builder
    fun implicationGraph(
        problem: Problem,
        maxCandidates: Int,
        cancellation: Cancellation = Cancellation.Never,
    ): Array<IntArray> = harvestImplications(problem, maxCandidates, cancellation).toArrays()

    /** Pin `v = [value]`, propagate, and record `pinned -> forced` for every other Boolean the
     *  propagation forces. A self-edge (the pin itself) is never emitted. */
    private fun recordPolarity(problem: Problem, v: Int, value: Boolean, adj: Adjacency, cancellation: Cancellation) {
        val result = problem.propagate(Assumptions.None.withBool(v, value), cancellation)
        if (result !is PropagationResult.Implied) return
        val from = Lit.make(v, value)
        result.forEachBool { w, b ->
            if (w != v) adj.addEdge(from, Lit.make(w, b))
        }
    }

    /**
     * Same-polarity equivalent-variable merges: variable `w` merges into representative `r` when their
     * positive literals lie on a mutual-implication cycle (`r+ ⇒ w+` and `w+ ⇒ r+`), proven by the
     * harvested graph. Cycles are the strongly-connected components of the literal graph; the smallest
     * variable id in a component is its representative.
     *
     * **Polarity scope.** Only same-polarity equivalence is merged. The existing
     * [com.eignex.klause.solver.Factor.remap] renames a variable id while preserving literal polarity
     * and cannot express the polarity flip an anti-equivalence `r ⇔ ¬w` needs (a reification aux var is
     * a raw id, not a literal, so there is no polarity to flip), so anti-equivalent literals are
     * detected but deliberately left unmerged. A component that contains both polarities of one
     * variable (`u+` and `u-` together) would assert `u ⇔ ¬u`, i.e. an inconsistency; that variable is
     * skipped and left to [Probing]'s failed-literal handling.
     *
     * A variable the objective reads ([objectiveBoolVars]) is never merged in either direction: the
     * presolved problem leaves a merged variable unconstrained, and an objective term over it must keep
     * a constrained variable to read, so the objective set is left wholly intact.
     */
    private fun equivalentVariableMerges(
        numBoolVars: Int,
        adj: Adjacency,
        objectiveBoolVars: Set<Int>,
    ): List<BoolMerge> {
        val componentOf = stronglyConnectedComponents(adj)
        // Representative variable per literal-SCC: the smallest variable id whose positive literal sits
        // in that component. A component is mergeable only through positive literals (same polarity).
        val repVarOfComponent = MutableIntIntMap()
        for (vv in 0 until numBoolVars) {
            if (vv in objectiveBoolVars) continue
            val pos = componentOf[Lit.make(vv, true)]
            val neg = componentOf[Lit.make(vv, false)]
            if (pos == neg) continue // u+ and u- in one SCC ⇒ u ⇔ ¬u: skip (failed-literal territory)
            if (!repVarOfComponent.containsKey(pos) || vv < repVarOfComponent.getOrDefault(pos, 0)) {
                repVarOfComponent.put(pos, vv)
            }
        }
        val merges = ArrayList<BoolMerge>()
        for (vv in 0 until numBoolVars) {
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

    /** Rewrite every factor with each merged variable renamed to its representative, dropping any
     *  binary clause that the rename turns into a tautology (`r ⇒ r`). Bool variable count is
     *  preserved; a merged variable simply stops appearing in any factor and is rebuilt on the way
     *  back. */
    private fun applyMerges(problem: Problem, merges: List<BoolMerge>): List<Factor> {
        val boolMap = IntArray(problem.numBoolVars) { it }
        for (m in merges) boolMap[m.from] = m.into
        val intMap = IntArray(problem.numIntVars) { it }
        val mapping = VarRemap(boolMap, intMap)
        val out = ArrayList<Factor>(problem.factors.size)
        for (f in problem.factors) {
            val remapped = f.remap(mapping)
            if (remapped is Clause && isTautology(remapped)) continue
            out.add(remapped)
        }
        return out
    }

    /** Keep an SCC substitution within the factor representations supported by this pass. */
    private fun compatibleMerges(problem: Problem, candidates: List<BoolMerge>): List<BoolMerge> {
        if (candidates.isEmpty()) return candidates
        val boolMap = IntArray(problem.numBoolVars) { it }
        for (merge in candidates) boolMap[merge.from] = merge.into
        for (factor in problem.factors) {
            when (factor) {
                is ReifiedPseudoBoolean -> restoreAuxiliarySeparation(factor, boolMap)
            }
        }
        return candidates.filter { boolMap[it.from] == it.into }
    }

    private fun restoreAuxiliarySeparation(factor: ReifiedPseudoBoolean, boolMap: IntArray) {
        val auxiliary = factor.auxBoolVar
        for (literal in factor.literals) {
            val bodyVariable = Lit.variable(literal)
            if (boolMap[auxiliary] == boolMap[bodyVariable]) {
                boolMap[auxiliary] = auxiliary
                boolMap[bodyVariable] = bodyVariable
            }
        }
    }

    /** A clause that holds in every assignment because some variable appears in both polarities. */
    private fun isTautology(clause: Clause): Boolean {
        val seen = IntHashSet(clause.literals.size)
        for (lit in clause.literals) {
            if (Lit.negate(lit) in seen) return true
            seen.add(lit)
        }
        return false
    }

    /**
     * Drop every binary clause whose implication is entailed by a longer chain of the *other* binary
     * clauses. A binary clause `(¬a ∨ b)` is the implication `a -> b` (and its contrapositive
     * `¬b -> ¬a`); both edges go into a graph built from the binary clauses **alone**. The clause is
     * redundant exactly when `b` is reachable from `a` over the remaining edges without using this
     * clause's own two edges — propagation then still derives `b` from `a`, so satisfiability and the
     * optimum are untouched. Non-binary factors and unit clauses are always kept; when nothing is
     * dropped [factors] is returned unchanged (identity, the pass's no-op signal).
     */
    private fun dropTransitivelyRedundantBinaries(problem: Problem, factors: List<Factor>): List<Factor> {
        val binaryIndices = IntArrayList()
        factors.forEachIndexed { i, f -> if (f is Clause && f.literals.size == 2) binaryIndices.add(i) }
        if (binaryIndices.size < 2) return factors

        val adj = Adjacency(2 * problem.numBoolVars)
        binaryIndices.forEach { i ->
            val (a, b) = implicationEdges(factors[i] as Clause)
            adj.addEdge(a.first, a.second)
            adj.addEdge(b.first, b.second)
        }

        val drop = IntHashSet()
        binaryIndices.forEach { i ->
            val clause = factors[i] as Clause
            val (e1, e2) = implicationEdges(clause)
            // Redundant iff the implication has an alternative path that avoids this clause's own two
            // directed edges. Checking one direction suffices: the contrapositive is reachable iff the
            // forward implication is, so a single source→target search settles the clause.
            if (reachableAvoiding(adj, e1.first, e1.second, e1, e2)) drop.add(i)
        }
        if (drop.isEmpty()) return factors

        val kept = ArrayList<Factor>(factors.size - drop.size)
        factors.forEachIndexed { i, f -> if (i !in drop) kept.add(f) }
        return kept
    }

    /** The two implication edges a binary clause `(p ∨ q)` encodes: `¬p -> q` and `¬q -> p`. */
    private fun implicationEdges(clause: Clause): Pair<Edge, Edge> {
        val p = clause.literals[0]
        val q = clause.literals[1]
        return Edge(Lit.negate(p), q) to Edge(Lit.negate(q), p)
    }

    /** Whether [target] is reachable from [source] over [adj] without traversing either [skip1] or
     *  [skip2] (the edges of the clause under test) — a path of length ≥ 2 entailing `source -> target`. */
    private fun reachableAvoiding(adj: Adjacency, source: Int, target: Int, skip1: Edge, skip2: Edge): Boolean {
        val stack = IntArrayList()
        val seen = IntHashSet()
        stack.add(source)
        seen.add(source)
        while (!stack.isEmpty()) {
            val node = stack.last()
            stack.removeAt(stack.size - 1)
            adj.forEachNeighbor(node) { next ->
                val skipped = (node == skip1.first && next == skip1.second) ||
                    (node == skip2.first && next == skip2.second)
                if (!skipped && next !in seen) {
                    if (next == target) return true
                    seen.add(next)
                    stack.add(next)
                }
            }
        }
        return false
    }

    /** Tarjan strongly-connected components over the literal graph: `component[l]` is a stable
     *  component id shared by exactly the literals on a mutual-implication cycle with `l`. A literal
     *  with no edges is its own singleton component. Iterative (explicit stack) so a deep implication
     *  chain cannot overflow the call stack on the native targets. */
    private fun stronglyConnectedComponents(adj: Adjacency): IntArray {
        val n = adj.nodeCount
        val index = IntArray(n) { -1 }
        val low = IntArray(n)
        val onStack = BooleanArray(n)
        val component = IntArray(n) { -1 }
        val tarjanStack = IntArrayList()
        var nextIndex = 0
        var nextComponent = 0

        // Explicit DFS: each frame tracks the node and how far its neighbour list has been walked.
        val callNode = IntArrayList()
        val callNeighbour = IntArrayList()
        for (root in 0 until n) {
            if (index[root] != -1) continue
            callNode.add(root)
            callNeighbour.add(0)
            while (!callNode.isEmpty()) {
                val node = callNode[callNode.size - 1]
                if (callNeighbour[callNeighbour.size - 1] == 0 && index[node] == -1) {
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
                    val next = neighbours[ni]
                    if (index[next] == -1) {
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
                        val w = tarjanStack.last()
                        tarjanStack.removeAt(tarjanStack.size - 1)
                        onStack[w] = false
                        component[w] = nextComponent
                        if (w == node) break
                    }
                    nextComponent++
                }
                callNode.removeAt(callNode.size - 1)
                callNeighbour.removeAt(callNeighbour.size - 1)
                if (!callNode.isEmpty()) {
                    val parent = callNode[callNode.size - 1]
                    if (low[node] < low[parent]) low[parent] = low[node]
                }
            }
        }
        return component
    }

    private data class Edge(val first: Int, val second: Int)

    /** Adjacency list over literal nodes `0 until [nodeCount]`, built incrementally. Parallel
     *  neighbour lists per node; reads expose a plain [IntArray] view for the SCC walk. */
    private class Adjacency(val nodeCount: Int) {
        private val out = Array(nodeCount) { IntArrayList() }

        fun addEdge(from: Int, to: Int) {
            if (from == to) return
            out[from].add(to)
        }

        fun neighbours(node: Int): IntArray = out[node].toIntArray()

        fun toArrays(): Array<IntArray> = Array(nodeCount) { neighbours(it) }

        inline fun forEachNeighbor(node: Int, action: (Int) -> Unit) {
            out[node].forEach { next -> action(next) }
        }
    }
}

/** A same-polarity equivalence merge recorded by [ImplicationGraph]: variable [from] is renamed to
 *  its representative [into], which holds the identical value in every solution. */
internal class BoolMerge(val from: Int, val into: Int)

/**
 * The equivalent-literal merges [ImplicationGraph.reduce] made, holding the data to rebuild merged
 * variables. Pass a solution of the reduced problem through [reconstruct] to recover a solution of the
 * original.
 */
internal class ImplicationReduction(private val merges: List<BoolMerge>) {
    /** Recover each merged variable in a solution [sample] by copying its representative's value.
     *  Representatives are never themselves merged away (the smallest id in a component is the rep and
     *  only larger ids merge into it), so a single forward pass suffices. */
    fun reconstruct(sample: Sample): Sample {
        if (merges.isEmpty()) return sample
        val bools = sample.bools.copyOf()
        for (m in merges) bools[m.from] = bools[m.into]
        return Sample(bools, sample.ints)
    }
}
