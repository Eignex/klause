package com.eignex.klause.localsearch

import com.eignex.klause.util.IntArrayList

// A state borrows the live node prefix until its synchronous move or probe returns.
internal class DefinitionTraversal(val network: InvariantNetwork) {
    private val marked = BooleanArray(network.size)
    val nodes = IntArrayList()

    fun clear() {
        for (position in 0 until nodes.size) marked[nodes[position]] = false
        nodes.clear()
    }

    fun add(node: Int) {
        if (marked[node]) return
        nodes.add(node)
        marked[node] = true
    }
}
