package com.eignex.klause.localsearch

import com.eignex.klause.ir.LinearOp
import com.eignex.klause.solver.objective.FunctionalObjective
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.addExact
import com.eignex.klause.util.mulExact
import com.eignex.klause.util.subExact

internal fun extremumRepair(
    state: LocalSearchState,
    output: Int,
    target: Long,
    allows: (Int) -> Boolean,
): Map<Int, Long>? {
    val net = state.invariants ?: return null
    if (!net.hasExtremumRepair(output)) return null
    val writes = LinkedHashMap<Int, Long>()
    fun value(v: Int): Long = writes[v] ?: state.assignment.intValue(v)
    fun satisfies(value: Long, goal: Long, op: LinearOp): Boolean = when (op) {
        LinearOp.EQ -> value == goal
        LinearOp.GE -> value >= goal
        LinearOp.LE -> value <= goal
        LinearOp.NE -> false
    }
    fun solve(v: Int, goal: Long, op: LinearOp): Boolean {
        val domain = state.rootDomains[v]
        if (op == LinearOp.EQ && goal !in domain) return false
        if ((op == LinearOp.GE && domain.max < goal) || (op == LinearOp.LE && domain.min > goal)) return false
        if (state.assumptions.isFrozenInt(v) && !satisfies(state.assignment.intValue(v), goal, op)) return false
        val definition = net.intDefinition(v)
        if (definition == null) {
            if (value(v) in domain && satisfies(value(v), goal, op)) return true
            if (!allows(v) || writes.containsKey(v)) return false
            var candidate = domain.clamp(goal)
            if (op == LinearOp.GE && candidate < goal) candidate = domain.higher(goal)
            if (op == LinearOp.LE && candidate > goal) candidate = domain.lower(goal)
            writes[v] = candidate
            return true
        }
        if (satisfies(definition.compute(::value), goal, op) && satisfies(value(v), goal, op)) return true
        val saved = LinkedHashMap(writes)
        fun restore() {
            writes.clear()
            writes.putAll(saved)
        }
        when (definition) {
            is FunctionalObjective.Extreme -> {
                val bound = if (definition.max) LinearOp.LE else LinearOp.GE
                fun operand(operand: FunctionalObjective.Operand, requirement: LinearOp): Boolean =
                    if (operand.varId < 0) {
                        satisfies(operand.const, goal, requirement)
                    } else {
                        solve(operand.varId, goal, requirement)
                    }
                if (op == bound) {
                    if (definition.ins.all { operand(it, bound) }) return true
                } else {
                    for (witness in definition.ins) {
                        restore()
                        if (op == LinearOp.EQ) {
                            val valid = definition.ins.all {
                                operand(it, if (it === witness) LinearOp.EQ else bound)
                            }
                            if (valid) return true
                        } else if (operand(witness, op)) {
                            return true
                        }
                    }
                }
            }

            is FunctionalObjective.Lin -> {
                if (definition.outCoeff != 1L && definition.outCoeff != -1L) return false
                for (k in definition.ins.indices) {
                    val operand = definition.ins[k]
                    val coefficient = definition.coeffs[k]
                    if (operand.varId < 0 || coefficient == 0L) continue
                    restore()
                    try {
                        var rhs = subExact(definition.c, mulExact(definition.outCoeff, goal))
                        for (j in definition.ins.indices) {
                            if (j != k) {
                                rhs = subExact(rhs, mulExact(definition.coeffs[j], definition.ins[j].value(::value)))
                            }
                        }
                        if (rhs == Long.MIN_VALUE && coefficient == -1L) continue
                        val positiveSlope = (coefficient > 0L) != (definition.outCoeff > 0L)
                        val requirement = when {
                            op == LinearOp.EQ -> LinearOp.EQ
                            (op == LinearOp.GE) == positiveSlope -> LinearOp.GE
                            else -> LinearOp.LE
                        }
                        var candidate = rhs / coefficient
                        val remainder = rhs % coefficient
                        if (remainder != 0L) {
                            if (requirement == LinearOp.EQ) continue
                            val positiveQuotient = (rhs > 0L) == (coefficient > 0L)
                            if (requirement == LinearOp.GE && positiveQuotient) candidate = addExact(candidate, 1L)
                            if (requirement == LinearOp.LE && !positiveQuotient) candidate = subExact(candidate, 1L)
                        }
                        if (solve(operand.varId, candidate, requirement)) return true
                    } catch (_: CheckedLongOverflowException) {
                        // An overflowing inverse proposal cannot certify a reachable Long target.
                    }
                }
            }

            else -> return false
        }
        restore()
        return false
    }
    if (!solve(output, target, LinearOp.EQ) || writes.isEmpty()) return null
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
