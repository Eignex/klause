package com.eignex.klause.util

/**
 * Dense disjoint-set (union-find) over the elements `0 until size`, with path compression on
 * [find] and union-by-arbitrary-root. Use it to coalesce indices that pairwise satisfy an
 * equivalence-generating relation — e.g. verified variable/value/block transpositions in presolve
 * symmetry breaking — and then read off the resulting partition with [groups].
 *
 * Path compression keeps [find] near-constant amortised, so the pairwise `O(n²)` union loops that
 * drive it stay dominated by the relation check rather than by tree walks.
 *
 * Not thread-safe. The element ids are the dense indices; callers grouping a payload (values,
 * blocks) map each member index back to its payload after [groups].
 */
internal class IntDisjointSet(size: Int) {
    private val parent = IntArray(size) { it }

    /** Representative root of [x]'s set, flattening the path so repeat lookups are O(1). */
    fun find(x: Int): Int {
        var root = x
        while (parent[root] != root) root = parent[root]
        var cur = x
        while (parent[cur] != cur) {
            val next = parent[cur]
            parent[cur] = root
            cur = next
        }
        return root
    }

    /** Merge the sets containing [a] and [b]. A no-op if they already share a root. */
    fun union(a: Int, b: Int) {
        val ra = find(a)
        val rb = find(b)
        if (ra != rb) parent[ra] = rb
    }

    /** Whether [a] and [b] are in the same set. */
    fun connected(a: Int, b: Int): Boolean = find(a) == find(b)

    /**
     * Partition of `0 until size`: one [IntArray] per non-empty root, each holding that set's
     * member indices in ascending order. Roots appear in ascending order of their smallest member.
     */
    fun groups(): List<IntArray> {
        val byRoot = MutableIntIntMap(parent.size) // root → index into `members`
        val members = ArrayList<MutableList<Int>>()
        for (x in parent.indices) {
            val r = find(x)
            val slot = byRoot.getOrDefault(r, -1)
            if (slot < 0) {
                byRoot.put(r, members.size)
                members.add(mutableListOf(x))
            } else {
                members[slot].add(x)
            }
        }
        return members.map { it.toIntArray() }
    }
}
