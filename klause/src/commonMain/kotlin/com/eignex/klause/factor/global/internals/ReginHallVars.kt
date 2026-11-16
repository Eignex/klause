package com.eignex.klause.factor.global.internals

import com.eignex.util.collections.IntArrayList

internal fun reginHallVarsFrom(valNode: Int, adj: Array<IntArrayList>, vars: IntArray): IntArray {
    val total = adj.size
    val vis = BooleanArray(total)
    val bfs = IntArray(total)
    var qh = 0
    var qt = 0
    vis[valNode] = true
    bfs[qt++] = valNode
    val acc = IntArrayList()
    while (qh < qt) {
        val u = bfs[qh++]
        if (u < vars.size) acc.add(vars[u])
        val a = adj[u]
        for (k in 0 until a.size) {
            val w = a[k]
            if (!vis[w]) {
                vis[w] = true
                bfs[qt++] = w
            }
        }
    }
    return acc.toIntArray()
}
