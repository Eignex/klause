package com.eignex.klause.localsearch

import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep.SweepNode
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayDeque
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.MutableIntIntMap

@Suppress("CyclomaticComplexMethod", "LongMethod")
internal fun orderedDefinitions(
    problem: Problem,
    nodes: List<SweepNode>,
    cancellation: Cancellation = Cancellation.Never,
): List<SweepNode> {
    var visits = 0
    fun stopped(): Boolean = visits++ % INITIALIZATION_BATCH == 0 && cancellation()
    val intOutputs = MutableIntIntMap()
    val boolOutputs = MutableIntIntMap()
    for (i in nodes.indices) {
        if (stopped()) return emptyList()
        val node = nodes[i]
        val count = if (node.outIsBool) problem.numBoolVars else problem.numIntVars
        if (node.out !in 0 until count || node.intInputs.any { it !in 0 until problem.numIntVars } ||
            node.boolInputs.any { it !in 0 until problem.numBoolVars }
        ) return emptyList()
        val outputs = if (node.outIsBool) boolOutputs else intOutputs
        outputs.put(node.out, if (outputs.containsKey(node.out)) -2 else i)
    }
    val pending = IntArray(nodes.size)
    val followers = Array(nodes.size) { IntArrayList() }
    val ready = IntArrayDeque()
    for (i in nodes.indices) {
        if (stopped()) return emptyList()
        val node = nodes[i]
        if ((if (node.outIsBool) boolOutputs else intOutputs).getOrDefault(node.out, -1) != i) continue
        for (input in node.intInputs) {
            if (stopped()) return emptyList()
            val parent = intOutputs.getOrDefault(input, -1)
            if (parent >= 0) {
                pending[i]++
                followers[parent].add(i)
            }
        }
        for (input in node.boolInputs) {
            if (stopped()) return emptyList()
            val parent = boolOutputs.getOrDefault(input, -1)
            if (parent >= 0) {
                pending[i]++
                followers[parent].add(i)
            }
        }
        if (pending[i] == 0) ready.addLast(i)
    }
    val ordered = ArrayList<SweepNode>(nodes.size)
    while (ready.isNotEmpty()) {
        if (stopped()) return emptyList()
        val i = ready.removeFirst()
        ordered.add(nodes[i])
        for (k in 0 until followers[i].size) {
            if (stopped()) return emptyList()
            val child = followers[i][k]
            if (--pending[child] == 0) ready.addLast(child)
        }
    }
    return if (cancellation()) emptyList() else ordered
}

internal fun Cancellation.definitionStopped(): Boolean {
    if (this()) return true
    charge(1)
    return this()
}
