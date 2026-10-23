package com.eignex.klause.localsearch

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.Assignment
import com.eignex.klause.util.Bits

// Cached evaluations belong to one state; coordinate writes invalidate direct readers, including on rollback.
// A producer stays pending after an output write so a later cone visit can repair an inconsistent output.
internal class DefinitionValues(private val network: InvariantNetwork) {
    private val valid = BooleanArray(network.size)
    private val values = LongArray(network.size)
    val pending = Bits(network.size).also { it.words.fill(-1L) }

    fun clear() {
        valid.fill(false)
        pending.words.fill(-1L)
    }

    fun intChanged(variable: Int) {
        for (node in network.intReaders(variable)) invalidate(node)
        val writer = network.intWriter(variable)
        if (writer >= 0) pending.set(writer)
    }

    fun boolChanged(variable: Int) {
        for (node in network.boolReaders(variable)) invalidate(node)
        val writer = network.boolWriter(variable)
        if (writer >= 0) pending.set(writer)
    }

    private fun invalidate(node: Int) {
        valid[node] = false
        pending.set(node)
    }

    fun eval(index: Int, assignment: Assignment, domains: Array<IntDomain>): Long {
        if (!valid[index]) {
            values[index] = network.node(index).eval(assignment, domains)
            valid[index] = true
        }
        return values[index]
    }
}
