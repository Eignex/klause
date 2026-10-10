package com.eignex.klause.localsearch

import com.eignex.klause.solver.objective.FunctionalObjective
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.mulExact
import com.eignex.klause.util.subExact

internal fun extremumRepair(
    state: LocalSearchState,
    output: Int,
    target: Long,
    allows: (Int) -> Boolean,
): Map<Int, Long>? {
    val net = state.invariants ?: return null
    val writes = LinkedHashMap<Int, Long>()
    fun value(v: Int): Long = writes[v] ?: state.assignment.intValue(v)
    fun solve(v: Int, goal: Long): Boolean {
        if (goal !in state.rootDomains[v]) return false
        if (state.assumptions.isFrozenInt(v) && goal != state.assignment.intValue(v)) return false
        val definition = net.intDefinition(v)
        if (definition == null) {
            if (goal == value(v)) return true
            if (!allows(v)) return false
            val previous = writes[v]
            if (previous != null && previous != goal) return false
            writes[v] = goal
            return true
        }
        when (definition) {
            is FunctionalObjective.Extreme -> {
                val saved = LinkedHashMap(writes)
                for (witness in definition.ins) {
                    writes.clear()
                    writes.putAll(saved)
                    var valid = true
                    for (operand in definition.ins) {
                        val current = operand.value(::value)
                        val outside = if (definition.max) current > goal else current < goal
                        if (outside || operand === witness) {
                            if (operand.varId < 0 || !solve(operand.varId, goal)) {
                                valid = false
                                break
                            }
                        }
                    }
                    if (valid) return true
                }
                writes.clear()
                writes.putAll(saved)
            }
            is FunctionalObjective.Lin -> {
                val saved = LinkedHashMap(writes)
                for (k in definition.ins.indices) {
                    val operand = definition.ins[k]
                    val coefficient = definition.coeffs[k]
                    if (operand.varId < 0 || coefficient == 0L) continue
                    writes.clear()
                    writes.putAll(saved)
                    try {
                        var rhs = subExact(definition.c, mulExact(definition.outCoeff, goal))
                        for (j in definition.ins.indices) {
                            if (j != k) rhs = subExact(rhs, mulExact(definition.coeffs[j], definition.ins[j].value(::value)))
                        }
                        if (rhs == Long.MIN_VALUE && coefficient == -1L) continue
                        if (rhs % coefficient == 0L && solve(operand.varId, rhs / coefficient)) return true
                    } catch (_: CheckedLongOverflowException) {
                        // An overflowing inverse proposal cannot certify a reachable Long target.
                    }
                }
                writes.clear()
                writes.putAll(saved)
            }
            else -> return false
        }
        return false
    }
    if (!solve(output, target) || writes.isEmpty()) return null
    val evaluated = LinkedHashMap(writes)
    for (i in net.affectedNodes(writes.keys.toIntArray(), IntArray(0))) {
        val node = net.node(i)
        if (node.outIsBool || state.assumptions.isFrozenInt(node.out)) continue
        val definition = net.intDefinition(node.out) ?: continue
        evaluated[node.out] = state.rootDomains[node.out].clamp(
            definition.compute { evaluated[it] ?: state.assignment.intValue(it) },
        )
    }
    // Shared inputs can undo another branch's target; verify the complete maintained cone.
    val definition = net.intDefinition(output) ?: return null
    if (definition.compute { evaluated[it] ?: state.assignment.intValue(it) } != target) return null
    if ((evaluated[output] ?: state.assignment.intValue(output)) != target) return null
    return writes
}
