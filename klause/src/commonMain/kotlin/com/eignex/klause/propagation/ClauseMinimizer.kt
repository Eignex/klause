package com.eignex.klause.propagation

import com.eignex.klause.solver.Lit
import com.eignex.klause.util.EmptyBooleanArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.MutableIntIntMap

/**
 * The [ConflictAnalyzer]'s view of the frozen implication graph that [ClauseMinimizer] walks:
 * the live variable universe (bool vars + atoms), the per-variable decision level, and the
 * antecedent literals. Levels and antecedents are invariant for the duration of one analysis
 * (the search path is frozen while the graph is walked), so the minimizer may query them freely.
 */
internal interface ReasonGraph {
    /** Size of the live var space (bool vars + atoms) for the current analysis. */
    fun universeSize(): Int

    /** Decision level of bool var or atom var `v`; ≤ 0 = root fact. */
    fun levelOf(v: Int): Int

    /** Antecedent literals of `v`'s pin, or null when `v` is a decision/leaf. */
    fun antecedentsOf(v: Int): IntArray?
}

/**
 * Learned-clause minimization: self-subsuming resolution ([minimize]) followed by
 * binary-resolution minimization ([binaryMinimize]), applied by [ConflictAnalyzer] to every 1UIP
 * clause before it is emitted. Clause in, shorter implied clause out — dropping a redundant
 * literal always yields a strictly stronger clause, so the result stays an implied, asserting
 * nogood. Owns the scratch buffers the two passes need, persisted across conflicts (one instance
 * per analyzer) instead of reallocating per analysis.
 */
internal class ClauseMinimizer(private val state: PropagationState, private val graph: ReasonGraph) {
    private var inClause = EmptyBooleanArray
    private var toDrop = EmptyBooleanArray

    // Reusable explicit-stack buffers for the iterative [isRedundant] DFS (cleared per call),
    // so deep implication graphs can't overflow the call stack. Three parallel stacks: the
    // variable under examination, its antecedent literals, and the resume index into them.
    private val redVarStack = IntArrayList()
    private val redIdxStack = IntArrayList()
    private val redAntStack = ArrayList<IntArray>()

    // Marks variables currently on the [isRedundant] DFS path, so a back-edge (atom antecedent
    // graphs can be cyclic) is detected as a cycle rather than re-pushed forever.
    private var onPath = EmptyBooleanArray

    /** Run both minimization passes on [learned] and return the (possibly shorter) clause. */
    fun reduce(learned: IntArrayList, currentLevel: Int): IntArrayList =
        binaryMinimize(minimize(learned, currentLevel), currentLevel)

    /** Return [arr] if already ≥ [n], else a fresh array; either way clear `[0, n)` to false. */
    private fun scratch(arr: BooleanArray, n: Int): BooleanArray {
        val a = if (arr.size >= n) arr else BooleanArray(n)
        a.fill(false, 0, n)
        return a
    }

    /**
     * Self-subsuming-resolution clause minimization. After 1UIP produces the learned
     * clause, any literal `l` whose variable has antecedents fully implied by the rest
     * of the clause is *redundant* — dropping it yields a strictly stronger clause
     * (subset of the original under standard resolution rules).
     *
     * The UIP literal (at [currentLevel]) is never dropped — it's the asserting literal
     * the engine relies on at the backjump level. Other literals are checked via
     * [isRedundant], which walks antecedents recursively with a per-call cache to keep
     * the cost linear in the implication graph reached.
     *
     * Standard CDCL polish. Shrinks learned clauses by 10-30% on typical
     * SAT-style instances, with knock-on improvements to watcher-list traversal
     * cost during future propagation.
     */
    private fun minimize(learned: IntArrayList, currentLevel: Int): IntArrayList {
        if (learned.size <= 1) return learned
        val universeSize = graph.universeSize()
        inClause = scratch(inClause, universeSize)
        onPath = scratch(onPath, universeSize)
        for (i in 0 until learned.size) {
            val v = Lit.variable(learned[i])
            if (v < universeSize) inClause[v] = true
        }
        // Per-call redundancy memo: -1 absent, 0 = non-redundant, 1 = redundant. A primitive
        // int map avoids boxing the var key and the Boolean value per cached node.
        val cache = MutableIntIntMap(learned.size * 4)
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

    /**
     * Binary-resolution minimization, run as a second stage after [minimize]. For the kept
     * asserting (UIP) literal `u`, every binary clause `(u ∨ x)` lets us drop the clause literal
     * `¬x` by one resolution step:
     *   `C ⊗ (u ∨ x)` on `var(x)` = `(C \ {¬x}) ∪ {u}` = `C \ {¬x}`   (since `u ∈ C`).
     * Because every removal is justified by the single, never-removed UIP literal, the
     * removals can't interact, so the result stays an implied, asserting clause however many
     * literals are dropped. Gated on binary clauses being present and on the clause being a
     * genuine 1UIP clause (exactly one literal at the conflict level); a non-asserting clause
     * is left untouched.
     *
     * Complements self-subsuming minimization: it removes literals reachable by a *binary*
     * implication from the asserting literal that the antecedent-recursion pass does not,
     * typically shrinking the clause a few percent further and lowering its LBD.
     */
    private fun binaryMinimize(clause: IntArrayList, currentLevel: Int): IntArrayList {
        if (clause.size <= 2 || !state.hasBinaryClauses) return clause
        // The asserting literal is the unique literal at the conflict level. Bail when there
        // isn't exactly one (non-asserting clause) or it's an atom literal (the binary watch
        // index covers bool vars only).
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
            // Keep the UIP and any literal not marked. A var appears once in a clause (no
            // tautologies), so the marked var corresponds to exactly the removable literal.
            if (lit == uip || v >= universeSize || !toDrop[v]) out.add(lit)
        }
        return out
    }

    /** Linear membership test for [lit] in [clause]; clauses are short, so this beats a
     *  per-conflict set allocation. */
    private fun clauseContains(clause: IntArrayList, lit: Int): Boolean {
        for (i in 0 until clause.size) if (clause[i] == lit) return true
        return false
    }

    /**
     * True iff every chain of antecedents leading to `v`'s pin terminates in either a
     * variable that's *already in the learned clause* ([inClause]) or a level-0 fact.
     * Decision-style leaves (variables with `null` antecedents) make `v` non-redundant.
     *
     * Cached per variable for the duration of a single [minimize] call — the recursion
     * depth is bounded by the size of the implication graph reached, but the cache
     * keeps the total work linear.
     */
    private fun isRedundant(root: Int, inClause: BooleanArray, cache: MutableIntIntMap): Boolean {
        val numBoolVars = state.problem.numBoolVars
        val cachedRoot = cache.getOrDefault(root, -1)
        if (cachedRoot >= 0) return cachedRoot == 1
        val rootAnt = graph.antecedentsOf(root) ?: run {
            cache.put(root, 0)
            return false
        }
        // Iterative post-order DFS over the implication graph — recursion overflows the call
        // stack on deep graphs. The stack holds the root-to-current
        // path; a node is redundant iff all its antecedents are redundant / in-clause / level-0.
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
                // Two order literals of one integer variable are coupled views of the same domain,
                // joined by the monotonicity / duality / eq↔bound channeling clauses — and a bound
                // atom's reason cites the same-var frontier atom by design. Self-subsuming resolution
                // treats them as independent and can circularly drop the coupled set that jointly
                // constrains the variable, which is unsound. Keep the literal rather than resolving
                // through the coupling (the LCG minimizer rule for a variable's auxiliary literals).
                if (u >= numBoolVars && v >= numBoolVars &&
                    state.atoms.intVar[u - numBoolVars] == state.atoms.intVar[v - numBoolVars]
                ) {
                    failed = true
                    break
                }
                if (graph.levelOf(u) <= 0) continue
                if (u < inClause.size && inClause[u]) continue
                // A back-edge to a variable already on the path is a cycle; it can't be proven
                // redundant, so treat it (and thus v) as non-redundant — sound, just keeps the literal.
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
                // A non-redundant antecedent makes v — and therefore every ancestor on the
                // current path — non-redundant. Mark them all and stop.
                for (k in 0 until redVarStack.size) {
                    val a = redVarStack[k]
                    cache.put(a, 0)
                    if (a < onPath.size) onPath[a] = false
                }
                return false
            }
            cache.put(v, 1) // all antecedents resolved redundant
            if (v < onPath.size) onPath[v] = false
            redVarStack.removeAt(top)
            redAntStack.removeAt(redAntStack.size - 1)
            redIdxStack.removeAt(top)
        }
        return true
    }
}
