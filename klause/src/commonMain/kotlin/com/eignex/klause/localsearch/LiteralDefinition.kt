package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep.SweepNode
import com.eignex.klause.solver.Assignment
import com.eignex.klause.util.EmptyIntArray

internal class LiteralIntDefinition(override val out: Int, val literal: Int) : SweepNode {
    override val outIsBool: Boolean get() = false
    override val intInputs: IntArray get() = EmptyIntArray
    override val boolInputs: IntArray = intArrayOf(Lit.variable(literal))

    override fun eval(assignment: Assignment, domains: Array<IntDomain>): Long {
        val raw = assignment.boolValue(Lit.variable(literal))
        val holds = if (Lit.isPositive(literal)) raw else !raw
        return domains[out].clamp(if (holds) 1L else 0L)
    }
}

internal class EqualityPredicateDefinition(
    override val out: Int,
    val input: Int,
    val value: Long,
) : SweepNode {
    override val outIsBool: Boolean get() = true
    override val intInputs: IntArray = intArrayOf(input)
    override val boolInputs: IntArray get() = EmptyIntArray
    override fun eval(assignment: Assignment, domains: Array<IntDomain>): Long =
        if (assignment.intValue(input) == value) 1L else 0L
}

internal fun literalDefinitions(problem: Problem, hints: IntArray, nodes: List<SweepNode>): List<SweepNode> {
    val hinted = BooleanArray(problem.numIntVars)
    for (v in hints) if (v in hinted.indices) hinted[v] = true
    val elementResults = BooleanArray(problem.numIntVars)
    for (node in nodes) if (node is ElementResultDefinition) elementResults[node.out] = true
    val channelRows = BooleanArray(problem.numFactors)
    val channels = ArrayList<LiteralIntDefinition>()
    for (i in problem.factors.indices) {
        val row = problem.factors[i] as? ReifiedLinear ?: continue
        if (row.op != LinearOp.EQ || row.vars.size != 1 || !hinted[row.vars[0]]) continue
        if (elementResults[row.vars[0]]) continue
        val constants = row.integerConstants ?: continue
        if (constants.coeff(0) != 1L || constants.bound !in 0L..1L) continue
        val variable = row.vars[0]
        val bounds = problem.intBounds
        if (!bounds.hasLower(variable) || !bounds.hasUpper(variable) ||
            bounds.lower(variable) < 0L || bounds.upper(variable) > 1L
        ) continue
        val literal = Lit.make(row.auxBoolVar, constants.bound == 1L)
        channels.add(LiteralIntDefinition(row.vars[0], literal))
        channelRows[i] = true
    }
    if (channels.isEmpty()) return orderedDefinitions(problem, nodes)
    val counts = IntArray(problem.numBoolVars)
    val sources = arrayOfNulls<ReifiedLinear>(problem.numBoolVars)
    for (i in problem.factors.indices) {
        if (channelRows[i]) continue
        val factor = problem.factors[i]
        for (b in factor.boolVars) counts[b]++
        val row = factor as? ReifiedLinear ?: continue
        if (row.op != LinearOp.EQ || row.vars.size != 1 || row.integerConstants?.coeff(0) != 1L) continue
        sources[row.auxBoolVar] = row
    }
    val predicates = ArrayList<EqualityPredicateDefinition>()
    val claimed = BooleanArray(problem.numBoolVars)
    for (channel in channels) {
        val b = Lit.variable(channel.literal)
        val row = sources[b] ?: continue
        if (counts[b] != 1 || claimed[b]) continue
        predicates.add(EqualityPredicateDefinition(b, row.vars[0], checkNotNull(row.integerConstants).bound))
        claimed[b] = true
    }
    return orderedDefinitions(problem, nodes + predicates + channels)
}

private fun orderedDefinitions(problem: Problem, nodes: List<SweepNode>): List<SweepNode> {
    val intOutputs = IntArray(problem.numIntVars) { -1 }
    val boolOutputs = IntArray(problem.numBoolVars) { -1 }
    for (i in nodes.indices) {
        val node = nodes[i]
        val outputs = if (node.outIsBool) boolOutputs else intOutputs
        outputs[node.out] = if (outputs[node.out] == -1) i else -2
    }
    val state = ByteArray(nodes.size)
    val cyclic = BooleanArray(nodes.size)
    val ordered = ArrayList<SweepNode>(nodes.size)
    fun visit(i: Int) {
        if (i < 0 || state[i].toInt() == 2) return
        if (state[i].toInt() == 1) {
            cyclic[i] = true
            return
        }
        state[i] = 1
        val node = nodes[i]
        for (v in node.intInputs) {
            val input = intOutputs[v]
            visit(input)
            if (input >= 0 && cyclic[input]) cyclic[i] = true
        }
        for (v in node.boolInputs) {
            val input = boolOutputs[v]
            visit(input)
            if (input >= 0 && cyclic[input]) cyclic[i] = true
        }
        if (!cyclic[i]) ordered.add(node)
        state[i] = 2
    }
    for (i in nodes.indices) {
        val node = nodes[i]
        if ((if (node.outIsBool) boolOutputs else intOutputs)[node.out] == i) visit(i)
    }
    return ordered
}
