package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.solver.objective.FunctionalObjective

internal class ConditionalProductRepair(
    private val state: LocalSearchState,
    private val sink: MoveSink,
    private val output: Int,
    private val limit: Int,
) {
    private var added = 0

    fun propose(): Int {
        if (limit <= 0 || 0L !in state.rootDomains[output] || !sink.allowsDefinedInt(output)) return 0
        val product = state.invariants?.intDefinition(output) as? FunctionalObjective.Times ?: return 0
        val a = product.a.varId
        val b = product.b.varId
        if (a < 0 || b < 0 || a == b) return 0
        if (state.assignment.intValue(b) > 0L) channel(a)
        if (added < limit && state.assignment.intValue(a) > 0L) channel(b)
        return added
    }

    private fun channel(variable: Int) {
        val domain = state.rootDomains[variable]
        if (domain.min < 0L || domain.max > 1L || 0L !in domain ||
            state.assignment.intValue(variable) != 1L
        ) return
        if (state.invariants?.literalChannel(variable) != null) {
            added += LiteralChannelRepair(state, sink, limit - added).propose(variable, 0L)
            return
        }
        if (!sink.allowsInt(variable)) return
        for (fid in state.projection.intOccurrences[variable]) {
            val row = state.problem.factors[fid] as? ReifiedLinear ?: continue
            if (row.op != LinearOp.EQ || row.vars.size != 1) continue
            val constants = row.integerConstants ?: continue
            if (constants.coeff(0) != 1L || constants.bound !in 0L..1L) continue
            val desired = constants.bound == 0L
            if (state.assignment.boolValue(row.auxBoolVar) != desired && !sink.allowsBool(row.auxBoolVar)) continue
            choices(variable, row.auxBoolVar, desired)
            if (added >= limit) return
        }
    }

    private fun choices(channel: Int, indicator: Int, desired: Boolean) {
        for (fid in state.projection.boolOccurrences[indicator]) {
            val row = state.problem.factors[fid] as? ReifiedLinear ?: continue
            if (row.op != LinearOp.EQ || row.vars.size != 1 || row.vars[0] == channel) continue
            val constants = row.integerConstants ?: continue
            if (constants.coeff(0) != 1L) continue
            val variable = row.vars[0]
            if (!sink.allowsInt(variable)) continue
            val span = state.rootDomains[variable].spanOrNull(MAX_CHOICE_VALUES) ?: continue
            val offset = if (desired) 0 else state.rng.nextInt(span.size)
            var emitted = 0
            for (i in 0 until span.size) {
                val target = span.valueAt((offset + i) % span.size)
                if ((target == constants.bound) != desired || target == state.assignment.intValue(variable)) continue
                if (queue(variable, target, channel)) {
                    added++
                    emitted++
                }
                if (added >= limit || emitted >= MAX_PER_PREDICATE) break
            }
            if (added >= limit) return
        }
    }

    private fun queue(variable: Int, target: Long, channel: Int): Boolean {
        val coordinated = ChannelingSink(variable, target)
        coordinated.pin(channel)
        coordinated.add(Move.IntSet(channel, 0L))
        val current = state.assignment.intValue(variable)
        for (fid in state.projection.intOccurrences[variable]) {
            state.factors[fid].contributeChanneling(state, fid, variable, current, target, coordinated)
        }
        coordinated.carryBinaryChannels(state, sink)
        val move = coordinated.toMove() as Move.Compound
        val before = sink.size
        sink.addCompound(move.parts)
        return sink.size > before
    }

    private companion object {
        const val MAX_CHOICE_VALUES = 32L
        const val MAX_PER_PREDICATE = 4
    }
}
