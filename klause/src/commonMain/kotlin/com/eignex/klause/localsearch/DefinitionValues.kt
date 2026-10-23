package com.eignex.klause.localsearch

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.Assignment

// Cached evaluations belong to one state; coordinate writes invalidate direct readers, including on rollback.
internal class DefinitionValues(private val network: InvariantNetwork) {
    private val valid = BooleanArray(network.size)
    private val values = LongArray(network.size)

    fun clear() = valid.fill(false)

    fun intChanged(variable: Int) {
        for (node in network.intReaders(variable)) valid[node] = false
    }

    fun boolChanged(variable: Int) {
        for (node in network.boolReaders(variable)) valid[node] = false
    }

    fun eval(index: Int, assignment: Assignment, domains: Array<IntDomain>): Long {
        if (!valid[index]) {
            values[index] = network.node(index).eval(assignment, domains)
            valid[index] = true
        }
        return values[index]
    }
}
