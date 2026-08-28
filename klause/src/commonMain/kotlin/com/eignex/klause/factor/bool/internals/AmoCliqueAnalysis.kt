package com.eignex.klause.factor.bool.internals

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.model.PbOp
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.MutableIntIntMap
import com.eignex.klause.util.MutableIntObjectMap

/** At-most-one cliques (each a set of Lit-encoded literals, at most one satisfied) recognised
 *  soundly from a single factor:
 *   - a [Cardinality] `Σ lit ≤ max` with `max == 1` (covers both at-most-one and exactly-one);
 *   - any binary [Clause] `(l1 ∨ l2)` ⟺ at most one of `{¬l1, ¬l2}`;
 *   - a positive-weight [PseudoBoolean] knapsack `Σ wⱼ lⱼ ≤ b` (or `= b`): the largest-weight
 *     literals whose two smallest weights already exceed `b` are pairwise exclusive (see
 *     [pbKnapsackClique]).
 *
 *  These are the *base* cliques; [maximalAmoCliques] grows them across factors into larger ones. */
internal fun amoCliques(factors: List<Factor>): List<Set<Int>> = collectCliques(factors, includeKnapsacks = true)

/** [amoCliques] restricted to cliques backed by a [Cardinality] or [Clause] — factors a knapsack
 *  drop never removes. A clique implied by a [PseudoBoolean] knapsack only holds while that knapsack
 *  is present, so it is unsound for redundancy elimination to use one to *drop* a knapsack (it could
 *  drop the very constraint the clique rests on); this restricted set is the one safe to drop by. */
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

/** The at-most-one clique implied by a positive-weight `≤`/`=` knapsack `Σ wⱼ lⱼ ⟨op⟩ b`, or
 *  `null` when none of size ≥ 2 exists. Two literals `i, j` cannot both hold when `wᵢ + wⱼ > b`;
 *  taking the literals in *descending* weight, the largest prefix whose two smallest members still
 *  sum past `b` is pairwise exclusive — an at-most-one clique. Only `≤` and `=` bound the activity
 *  from above (a lone `≥` does not); negative weights and `b < 0` are skipped (not a clean cover). */
private fun pbKnapsackClique(pb: PseudoBoolean): Set<Int>? {
    if (pb.op != PbOp.LE && pb.op != PbOp.EQ) return null
    if (pb.literals.size < 2 || pb.bound < 0) return null
    val order = pb.weights.indices.sortedBy { pb.weights[it] } // ascending weight
    for (i in order) if (pb.weights[i] <= 0) return null
    // Ascending weights: the suffix from the first index whose pair-sum with its neighbour exceeds
    // the bound is a clique (its two smallest members already exceed b, so every pair does too).
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

/**
 * Budget for [mergeCliques], counted in conflict-graph neighbour visits — the quantity the greedy
 * extension actually spends, which is `Σ_clique Σ_member Σ_{base clique ∋ member} |base clique|` and
 * so grows with how densely the cliques overlap rather than with how many there are. A clique count
 * bounds neither: a handful of wide, heavily overlapping at-most-ones costs more than a hundred
 * thousand disjoint binary ones.
 */
private const val CLIQUE_MERGE_WORK_BUDGET = 20_000_000L

/** Poll the cancellation once per this many base cliques in the extension loop. */
private const val CLIQUE_MERGE_CANCEL_POLL_MASK = 0x3F

/** The [amoCliques] of [factors], grown into maximal cliques by [mergeCliques]. */
internal fun maximalAmoCliques(factors: List<Factor>, cancellation: Cancellation = Cancellation.Never): List<Set<Int>> =
    mergeCliques(amoCliques(factors), cancellation)

/** The [persistentAmoCliques] of [factors], grown into maximal cliques by [mergeCliques]. */
internal fun maximalPersistentAmoCliques(
    factors: List<Factor>,
    cancellation: Cancellation = Cancellation.Never,
): List<Set<Int>> = mergeCliques(persistentAmoCliques(factors), cancellation)

/**
 * Merge a set of at-most-one [cliques] into maximal ones. Every unordered pair *within* a base
 * clique is a sound exclusion edge, so their union is a conflict graph (see [ConflictGraph]);
 * greedily extending each base clique with literals adjacent to all its members yields larger
 * at-most-one cliques (e.g. three binary clauses forming a triangle collapse to one size-3 clique).
 * Cliques that end up a subset of another are dropped. Deterministic (cliques in input order,
 * literals in id order) and sound because a literal joins only when it conflicts with every current
 * member.
 *
 * Extension is metered against [CLIQUE_MERGE_WORK_BUDGET] and stops on [cancellation]: a clique
 * whose own extension would overrun what is left is kept as it came in. That only forgoes further
 * growth — every returned clique is still a valid at-most-one — so the result degrades in strength,
 * never in soundness.
 */
internal fun mergeCliques(cliques: List<Set<Int>>, cancellation: Cancellation = Cancellation.Never): List<Set<Int>> {
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
        val members = clique.toHashSet()
        // Candidates: lits adjacent to every base member, taken in id order for determinism. Each
        // one is then re-checked against the members added before it, which the candidate set of the
        // *base* clique does not account for.
        val candidates = graph.commonNeighbours(clique)
        budget -= candidates.size.toLong() * clique.size
        for (c in candidates) {
            if (members.all { it == c || graph.adjacent(it, c) }) members.add(c)
        }
        grown.add(members)
    }
    return dropSubsumedCliques(grown)
}

/**
 * Drop every clique of [grown] that is a strict subset of another, and dedup equals. Taking them in
 * descending size means any strict superset of a clique is already kept — a superset dropped as a
 * subset of a third clique leaves that third one a superset too — and a superset must contain *every*
 * member, so only the kept cliques indexed under the member with the fewest of them can be one. That
 * probe is what keeps this off the all-pairs `O(K²·|clique|)` scan.
 */
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

/**
 * The conflict graph of a set of at-most-one [cliques], held as *clique membership* rather than as
 * an adjacency list. Two literals conflict exactly when some clique contains both, so membership
 * answers the same queries in `Σ|clique|` space where an explicit adjacency needs `Σ|clique|²` — one
 * at-most-one over 5632 literals is 32 M neighbour entries, and materializing that exhausts the
 * heap on a large pseudo-Boolean model.
 *
 * Literals are Lit-encoded and arbitrarily sparse, so they are mapped once to dense positions and
 * everything below is flat primitive arrays, held as a CSR whose per-literal slice lists the
 * containing clique ids ascending.
 */
private class ConflictGraph(private val cliques: List<Set<Int>>) {
    private val positionOf = MutableIntIntMap()
    private val cliqueStart: IntArray
    private val cliquesOf: IntArray

    /** Scratch for [commonNeighbours]: adjacent-member count per position, and the stamp of the
     *  member it was last counted for, so a literal sharing two cliques with one member counts once.
     *  The stamp only ever increases, so [countedFor] needs no clearing between calls. */
    private val adjacentCount: IntArray
    private val countedFor: IntArray
    private var stamp = 0

    /** Per literal, the neighbour visits one [forEachNeighbour] sweep of it costs — the summed size
     *  of the base cliques containing it. The unit [mergeCliques] budgets in. */
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

    /** The neighbour visits [commonNeighbours] spends on [clique] — two sweeps over every member's
     *  containing cliques. */
    fun extensionCost(clique: Set<Int>): Long {
        var cost = 0L
        for (u in clique) {
            val pu = positionOf.getOrDefault(u, -1)
            if (pu >= 0) cost += 2 * visitCost[pu]
        }
        return cost
    }

    /** Whether [u] and [v] are pairwise exclusive — i.e. co-members of some base clique. Both slices
     *  are ascending, so this is a merge rather than a scan of the smaller against a set. */
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

    /**
     * The literals adjacent to *every* member of [clique] and not in it — the candidates
     * [mergeCliques] may extend it with, ascending so the greedy extension is deterministic.
     * Counted by sweeping each member's containing cliques rather than intersecting neighbour sets,
     * which keeps the working set to the literals actually reachable from [clique].
     */
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

    /** Apply [action] to every literal sharing a base clique with [u], by position and value. The
     *  same literal is visited once per shared clique, so callers that must count dedup themselves. */
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
