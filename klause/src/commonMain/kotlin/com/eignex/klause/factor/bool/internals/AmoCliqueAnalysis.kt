package com.eignex.klause.factor.bool.internals

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.model.PbOp
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.arrays.argsortBy
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.MutableIntIntMap
import com.eignex.util.collections.MutableIntObjectMap

// Base cliques from single factors: a cardinality with max 1, a binary clause `(l1 ∨ l2)` as at most one
// of `{¬l1, ¬l2}`, and a positive-weight knapsack's heaviest literals.
internal fun amoCliques(factors: List<Factor>): List<Set<Int>> = collectCliques(factors, includeKnapsacks = true)

// A knapsack-implied clique holds only while its knapsack is present, so redundancy elimination must not
// drop a knapsack by one; this set excludes them.
internal fun persistentAmoCliques(factors: List<Factor>): List<Set<Int>> = collectCliques(
    factors,
    includeKnapsacks = false,
)

private fun collectCliques(factors: List<Factor>, includeKnapsacks: Boolean): List<Set<Int>> {
    val cliques = ArrayList<Set<Int>>()
    for (f in factors) {
        when {
            f is Cardinality && f.max == 1 -> cliques.add(f.literals.toHashSet())

            f is Clause && f.literals.size == 2 ->
                cliques.add(hashSetOf(Lit.negate(f.literals[0]), Lit.negate(f.literals[1])))

            includeKnapsacks && f is PseudoBoolean -> pbKnapsackClique(f)?.let { cliques.add(it) }
        }
    }
    return cliques
}

// Only `≤` and `=` bound the activity from above.
private fun pbKnapsackClique(pb: PseudoBoolean): Set<Int>? {
    if (!permitsLongPseudoBoolean(pb.constants)) return null
    if (pb.op != PbOp.LE && pb.op != PbOp.EQ) return null
    if (pb.literals.size < 2 || pb.bound < 0) return null
    val order = argsortBy(pb.weights.size) { a, b -> pb.weights[a].compareTo(pb.weights[b]) }
    for (i in order) if (pb.weights[i] <= 0) return null
    // The suffix from the first index whose pair-sum with its neighbour exceeds the bound is a clique:
    // its two smallest members already exceed b, so every pair does too.
    val b = pb.bound
    for (start in 0 until order.size - 1) {
        if (pb.weights[order[start]] + pb.weights[order[start + 1]] > b) {
            val clique = HashSet<Int>(order.size - start)
            for (k in start until order.size) clique.add(pb.literals[order[k]])
            return if (clique.size >= 2) clique else null
        }
    }
    return null
}

// Counted in conflict-graph neighbour visits, which grow with clique overlap rather than clique count:
// a few wide overlapping at-most-ones cost more than many disjoint binary ones.
private const val CLIQUE_MERGE_WORK_BUDGET = 20_000_000L

private const val CLIQUE_MERGE_CANCEL_POLL_MASK = 0x3F

internal fun maximalAmoCliques(factors: List<Factor>, cancellation: ExecutionControl =
    ExecutionControl.Never): List<Set<Int>> =
    mergeCliques(amoCliques(factors), cancellation)

internal fun maximalPersistentAmoCliques(
    factors: List<Factor>,
    cancellation: ExecutionControl = ExecutionControl.Never,
): List<Set<Int>> = mergeCliques(persistentAmoCliques(factors), cancellation)

// Greedy extension is sound because a literal joins only when it conflicts with every current member. A
// clique whose extension would overrun the budget is kept as it came in, losing strength, never soundness.
// Clique merging as in Achterberg, Bixby, Gu, Rothberg and Weninger, "Presolve Reductions in Mixed Integer
// Programming", INFORMS J. Comput. 2020, https://doi.org/10.1287/ijoc.2018.0857.
internal fun mergeCliques(cliques: List<Set<Int>>, cancellation: ExecutionControl =
    ExecutionControl.Never): List<Set<Int>> {
    if (cliques.size < 2) return cliques
    var budget = CLIQUE_MERGE_WORK_BUDGET
    val graph = ConflictGraph(cliques)
    val grown = ArrayList<Set<Int>>(cliques.size)
    var stopped = false
    for (i in cliques.indices) {
        val clique = cliques[i]
        if (!stopped && (i and CLIQUE_MERGE_CANCEL_POLL_MASK) == 0 && cancellation()) stopped = true
        val cost = if (stopped) Long.MAX_VALUE else graph.extensionCost(clique)
        if (cost > budget) {
            grown.add(clique)
            continue
        }
        budget -= cost
        cancellation.charge(CLIQUE_MERGE_WORK_WEIGHT * cost)
        val members = clique.toHashSet()
        // Candidates: lits adjacent to every base member, taken in id order for determinism. Each
        // one is then re-checked against the members added before it, which the candidate set of the
        // *base* clique does not account for.
        val candidates = graph.commonNeighbours(clique)
        val checks = candidates.size.toLong() * clique.size
        budget -= checks
        cancellation.charge(CLIQUE_MERGE_WORK_WEIGHT * checks)
        for (c in candidates) {
            if (members.all { it == c || graph.adjacent(it, c) }) members.add(c)
        }
        grown.add(members)
    }
    return dropSubsumedCliques(grown)
}

// In descending size every strict superset is already kept, and it must contain every member, so probing
// only the kept cliques of the member held by the fewest avoids the all-pairs scan.
private fun dropSubsumedCliques(grown: List<Set<Int>>): List<Set<Int>> {
    val sorted = grown.distinct().sortedByDescending { it.size }
    val kept = ArrayList<Set<Int>>(sorted.size)
    val keptWith = MutableIntObjectMap<IntArrayList>()
    for (c in sorted) {
        var probe: IntArrayList? = null
        var unheld = false
        for (l in c) {
            val here = keptWith[l]
            if (here == null) {
                unheld = true
                break
            }
            if (probe == null || here.size < probe.size) probe = here
        }
        val holder = if (unheld) null else probe
        val subsumed = holder != null && (0 until holder.size).any {
            val k = kept[holder[it]]
            k.size > c.size && k.containsAll(c)
        }
        if (subsumed) continue
        val id = kept.size
        kept.add(c)
        for (l in c) keptWith.getOrPut(l) { IntArrayList() }.add(id)
    }
    return kept
}

// Held as clique membership rather than adjacency: two literals conflict exactly when some clique holds
// both, so membership needs `Σ|clique|` space where adjacency needs `Σ|clique|²`, which one wide
// at-most-one turns into heap exhaustion. Sparse Lit ids map to dense positions, and a CSR lists each
// literal's containing clique ids ascending.
private class ConflictGraph(private val cliques: List<Set<Int>>) {
    private val positionOf = MutableIntIntMap()
    private val cliqueStart: IntArray
    private val cliquesOf: IntArray

    // The stamp counts a literal sharing two cliques with one member once; it only increases, so
    // countedFor needs no clearing between calls.
    private val adjacentCount: IntArray
    private val countedFor: IntArray
    private var stamp = 0

    // Summed size of the base cliques containing each literal: the unit the merge budget counts.
    private val visitCost: LongArray

    init {
        var next = 0
        for (clique in cliques) for (u in clique) if (!positionOf.containsKey(u)) positionOf.put(u, next++)
        val litCount = next
        cliqueStart = IntArray(litCount + 1)
        for (clique in cliques) for (u in clique) cliqueStart[positionOf.getOrDefault(u, 0) + 1]++
        for (p in 0 until litCount) cliqueStart[p + 1] += cliqueStart[p]
        val fill = cliqueStart.copyOf(litCount)
        cliquesOf = IntArray(cliqueStart[litCount])
        for (id in cliques.indices) for (u in cliques[id]) cliquesOf[fill[positionOf.getOrDefault(u, 0)]++] = id
        adjacentCount = IntArray(litCount)
        countedFor = IntArray(litCount) { -1 }
        visitCost = LongArray(litCount)
        for (clique in cliques) for (u in clique) visitCost[positionOf.getOrDefault(u, 0)] += clique.size
    }

    fun extensionCost(clique: Set<Int>): Long {
        var cost = 0L
        for (u in clique) {
            val pu = positionOf.getOrDefault(u, -1)
            if (pu >= 0) cost += 2 * visitCost[pu]
        }
        return cost
    }

    // Both slices are ascending, so this is a merge.
    fun adjacent(u: Int, v: Int): Boolean {
        val pu = positionOf.getOrDefault(u, -1)
        val pv = positionOf.getOrDefault(v, -1)
        if (pu < 0 || pv < 0) return false
        var i = cliqueStart[pu]
        var j = cliqueStart[pv]
        val endI = cliqueStart[pu + 1]
        val endJ = cliqueStart[pv + 1]
        while (i < endI && j < endJ) {
            val a = cliquesOf[i]
            val b = cliquesOf[j]
            when {
                a == b -> return true
                a < b -> i++
                else -> j++
            }
        }
        return false
    }

    // Ascending so the greedy extension is deterministic. Sweeping containing cliques rather than
    // intersecting neighbour sets keeps the working set to literals reachable from the clique.
    fun commonNeighbours(clique: Set<Int>): IntArray {
        val common = IntArrayList()
        for (u in clique) {
            stamp++
            forEachNeighbour(u) { pw, w ->
                if (countedFor[pw] != stamp) {
                    countedFor[pw] = stamp
                    adjacentCount[pw]++
                    if (adjacentCount[pw] == clique.size && w !in clique) common.add(w)
                }
            }
        }
        for (u in clique) forEachNeighbour(u) { pw, _ -> adjacentCount[pw] = 0 }
        return common.toIntArray().apply { sort() }
    }

    // Visits a literal once per shared clique; callers that count dedup themselves.
    private inline fun forEachNeighbour(u: Int, action: (Int, Int) -> Unit) {
        val pu = positionOf.getOrDefault(u, -1)
        if (pu < 0) return
        for (i in cliqueStart[pu] until cliqueStart[pu + 1]) {
            for (w in cliques[cliquesOf[i]]) {
                val pw = positionOf.getOrDefault(w, -1)
                if (pw >= 0) action(pw, w)
            }
        }
    }
}

// Charge clique-extension work in simplex-operation units.
private const val CLIQUE_MERGE_WORK_WEIGHT = 5L
