package com.eignex.klause.propagation

import com.eignex.klause.ir.Lit
import com.eignex.util.collections.BitSet
import com.eignex.util.collections.DynamicBits
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.MutableIntIntMap

// Levels and antecedents are invariant during one analysis because the search path is frozen.
internal interface ReasonGraph {
    fun universeSize(): Int

    // ≤ 0 is a root fact.
    fun levelOf(v: Int): Int

    // Null for a decision or leaf.
    fun antecedentsOf(v: Int): IntArray?

    fun knownLeaf(v: Int): Boolean = false

    fun deferredReason(v: Int): Boolean = false
}

// Self-subsuming then binary-resolution minimization of every 1UIP clause. Dropping a redundant literal yields a
// strictly stronger implied clause, so the result stays an asserting nogood.
internal class ClauseMinimizer(private val state: PropagationState, private val graph: ReasonGraph) {
    private var inClause = DynamicBits()
    private var toDrop = DynamicBits()
    private val redundancyCache = MutableIntIntMap()

    // Explicit DFS stacks for [isRedundant], so deep implication graphs cannot overflow the call stack.
    private val redVarStack = IntArrayList()
    private val redIdxStack = IntArrayList()
    private val redAntStack = ArrayList<IntArray>()

    // Marks variables currently on the [isRedundant] DFS path, so a back-edge (atom antecedent
    // graphs can be cyclic) is detected as a cycle rather than re-pushed forever.
    private var onPath = DynamicBits()

    fun reduce(learned: IntArrayList, currentLevel: Int): IntArrayList =
        binaryMinimize(minimize(learned, currentLevel), currentLevel)

    private fun scratch(bits: DynamicBits, n: Int): DynamicBits = bits.also {
        it.resize(n)
        it.clear()
    }

    // Recursive self-subsuming minimization (N. Sörensson, A. Biere, "Minimizing Learned Clauses", SAT 2009,
    // https://doi.org/10.1007/978-3-642-02777-2_23). The UIP literal is never dropped: the engine asserts it at
    // the backjump level.
    private fun minimize(learned: IntArrayList, currentLevel: Int): IntArrayList {
        if (learned.size <= 1) return learned
        val universeSize = graph.universeSize()
        inClause = scratch(inClause, universeSize)
        onPath = scratch(onPath, universeSize)
        for (i in 0 until learned.size) {
            val v = Lit.variable(learned[i])
            if (v < universeSize) inClause[v] = true
        }
        // Per-call redundancy memo: -1 absent, 0 = non-redundant, 1 = redundant.
        val cache = redundancyCache
        cache.clear()
        toDrop = scratch(toDrop, universeSize)
        var dropCount = 0
        for (i in 0 until learned.size) {
            val v = Lit.variable(learned[i])
            if (v >= universeSize) continue
            if (graph.levelOf(v) == currentLevel) continue
            if (isRedundant(v, inClause, cache)) {
                toDrop[v] = true
                dropCount++
            }
        }
        if (dropCount == 0) return learned
        val out = IntArrayList(learned.size - dropCount)
        for (i in 0 until learned.size) {
            val lit = learned[i]
            val v = Lit.variable(lit)
            if (v >= universeSize || !toDrop[v]) out.add(lit)
        }
        return out
    }

    // For the asserting literal `u`, every binary clause `(u ∨ x)` drops `¬x` by one resolution step:
    // `C ⊗ (u ∨ x)` on `var(x)` = `(C \ {¬x}) ∪ {u}` = `C \ {¬x}` since `u ∈ C`. Every removal is justified by
    // the never-removed UIP literal, so removals cannot interact and the clause stays asserting.
    private fun binaryMinimize(clause: IntArrayList, currentLevel: Int): IntArrayList {
        if (clause.size <= 2 || !state.hasBinaryClauses) return clause
        // Bail unless exactly one literal sits at the conflict level and it is Boolean: the binary watch
        // index covers bool vars only.
        var uip = 0
        var uipCount = 0
        for (i in 0 until clause.size) {
            val l = clause[i]
            if (graph.levelOf(Lit.variable(l)) == currentLevel) {
                uip = l
                uipCount++
            }
        }
        if (uipCount != 1 || Lit.variable(uip) >= state.problem.numBoolVars) return clause

        val universeSize = graph.universeSize()
        toDrop = scratch(toDrop, universeSize)
        var dropCount = 0
        state.forEachBinaryPartner(uip) { x ->
            val neg = x xor 1 // ¬x
            val v = Lit.variable(neg)
            if (neg != uip && v < universeSize && !toDrop[v] && clauseContains(clause, neg)) {
                toDrop[v] = true
                dropCount++
            }
        }
        if (dropCount == 0) return clause
        val out = IntArrayList(clause.size - dropCount)
        for (i in 0 until clause.size) {
            val lit = clause[i]
            val v = Lit.variable(lit)
            // A var appears once in a clause, so the marked var is exactly the removable literal.
            if (lit == uip || v >= universeSize || !toDrop[v]) out.add(lit)
        }
        return out
    }

    // Clauses are short, so a scan beats a per-conflict set allocation.
    private fun clauseContains(clause: IntArrayList, lit: Int): Boolean {
        for (i in 0 until clause.size) if (clause[i] == lit) return true
        return false
    }

    // Redundant iff every antecedent chain ends in a clause literal or a level-0 fact; a decision leaf makes it
    // non-redundant. The per-call cache keeps total work linear in the graph reached.
    private fun isRedundant(root: Int, inClause: BitSet, cache: MutableIntIntMap): Boolean {
        val numBoolVars = state.problem.numBoolVars
        val cachedRoot = cache.getOrDefault(root, -1)
        if (cachedRoot >= 0) return cachedRoot == 1
        val rootAnt = graph.antecedentsOf(root) ?: run {
            cache.put(root, 0)
            return false
        }
        // Iterative post-order DFS: recursion overflows the call stack on deep graphs.
        redVarStack.clear()
        redIdxStack.clear()
        redAntStack.clear()
        redVarStack.add(root)
        redAntStack.add(rootAnt)
        redIdxStack.add(0)
        if (root < onPath.size) onPath[root] = true
        while (!redVarStack.isEmpty()) {
            val top = redVarStack.size - 1
            val v = redVarStack[top]
            val ant = redAntStack[top]
            var i = redIdxStack[top]
            var failed = false
            var pushed = false
            while (i < ant.size) {
                val u = Lit.variable(ant[i])
                i++
                if (u == v) continue
                // Order literals of one integer variable are coupled views of the same domain, and a bound
                // atom's reason cites the same-var frontier atom by design. Resolving through the coupling can
                // circularly drop the set that jointly constrains the variable, which is unsound.
                if (u >= numBoolVars && v >= numBoolVars &&
                    state.atoms.intVar[u - numBoolVars] == state.atoms.intVar[v - numBoolVars]
                ) {
                    failed = true
                    break
                }
                if (graph.levelOf(u) <= 0) continue
                if (u < inClause.size && inClause[u]) continue
                // A back-edge is a cycle and cannot be proven redundant.
                if (u < onPath.size && onPath[u]) {
                    failed = true
                    break
                }
                when (cache.getOrDefault(u, -1)) {
                    1 -> continue

                    0 -> {
                        failed = true
                        break
                    }

                    else -> {
                        if (state.reasonPreflight && redIdxStack[top] == 0 && i < ant.size) {
                            state.checkFiniteWork()
                            if (graph.deferredReason(u) && hasFailedSibling(v, ant, i, inClause, cache)) {
                                rejectPath(cache)
                                return false
                            }
                        }
                        val uAnt = graph.antecedentsOf(u)
                        if (uAnt == null) {
                            cache.put(u, 0)
                            failed = true
                            break
                        }
                        redIdxStack[top] = i // resume here once the child frame completes
                        redVarStack.add(u)
                        redAntStack.add(uAnt)
                        redIdxStack.add(0)
                        if (u < onPath.size) onPath[u] = true
                        pushed = true
                        break
                    }
                }
            }
            if (pushed) continue
            if (failed) {
                // A non-redundant antecedent makes every ancestor on the path non-redundant.
                rejectPath(cache)
                return false
            }
            cache.put(v, 1) // all antecedents resolved redundant
            if (v < onPath.size) onPath[v] = false
            redVarStack.removeAtSwap(top)
            redAntStack.removeAt(redAntStack.size - 1)
            redIdxStack.removeAtSwap(top)
        }
        return true
    }

    private fun hasFailedSibling(
        v: Int,
        antecedents: IntArray,
        start: Int,
        inClause: BitSet,
        cache: MutableIntIntMap,
    ): Boolean {
        val bools = state.problem.numBoolVars
        for (i in start until antecedents.size) {
            state.checkFiniteWork()
            val u = Lit.variable(antecedents[i])
            if (u == v) continue
            if (u >= bools && v >= bools && state.atoms.intVar[u - bools] == state.atoms.intVar[v - bools]) {
                return true
            }
            if (graph.levelOf(u) <= 0 || (u < inClause.size && inClause[u])) continue
            val cached = cache.getOrDefault(u, -1)
            val leaf = cached < 0 && graph.knownLeaf(u)
            if (leaf) cache.put(u, 0)
            if ((u < onPath.size && onPath[u]) || cached == 0 || leaf) {
                return true
            }
        }
        return false
    }

    private fun rejectPath(cache: MutableIntIntMap) {
        for (k in 0 until redVarStack.size) {
            val v = redVarStack[k]
            cache.put(v, 0)
            if (v < onPath.size) onPath[v] = false
        }
    }
}
