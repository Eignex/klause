package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit

internal class LiteralChannelRepair(
    private val state: LocalSearchState,
    private val sink: MoveSink,
    private val output: Int,
    private val limit: Int = MAX_ALTERNATIVES,
) {
    fun propose(target: Long): Int = propose(output, target, 0, limit)

    private fun propose(variable: Int, target: Long, depth: Int, remaining: Int): Int {
        if (depth >= MAX_DEPTH || remaining <= 0 || target !in 0L..1L ||
            target !in state.rootDomains[variable] || !sink.allowsDefinedInt(variable)
        ) return 0
        val network = state.invariants ?: return 0
        val channel = network.literalChannel(variable) ?: return 0
        val b = Lit.variable(channel.literal)
        val desired = (target == 1L) == Lit.isPositive(channel.literal)
        if (state.assumptions.isFrozenBool(b) || state.assignment.boolValue(b) == desired) return 0
        if (sink.allowsBool(b)) {
            val coordinated = searchedChoices(variable, b, desired, minOf(remaining, MAX_ALTERNATIVES))
            if (coordinated > 0) return coordinated
            val before = sink.size
            sink.addBoolFlip(b)
            return sink.size - before
        }
        val predicate = network.equalityPredicate(b) ?: return 0
        val domain = state.rootDomains[predicate.input]
        if (desired) {
            if (predicate.value !in domain) return 0
            return input(predicate.input, predicate.value, depth, minOf(remaining, MAX_ALTERNATIVES))
        }
        val span = domain.spanOrNull(MAX_CHOICE_VALUES) ?: return 0
        val offset = state.rng.nextInt(span.size)
        var added = 0
        for (i in 0 until span.size) {
            val value = span.valueAt((offset + i) % span.size)
            if (value == predicate.value || value == state.assignment.intValue(predicate.input)) continue
            val cap = minOf(remaining, MAX_ALTERNATIVES)
            added += input(predicate.input, value, depth, cap - added)
            if (added >= cap) break
        }
        return added
    }

    private fun searchedChoices(channel: Int, indicator: Int, desired: Boolean, remaining: Int): Int {
        var added = 0
        for (fid in state.projection.boolOccurrences[indicator]) {
            val row = state.problem.factors[fid] as? ReifiedLinear ?: continue
            if (row.op != LinearOp.EQ || row.vars.size != 1 || row.vars[0] == channel) continue
            val constants = row.integerConstants ?: continue
            val variable = row.vars[0]
            if (constants.coeff(0) != 1L || !sink.allowsInt(variable)) continue
            val domain = state.rootDomains[variable]
            if (desired) {
                val target = constants.bound
                if (target !in domain || target == state.assignment.intValue(variable)) continue
                val before = sink.size
                sink.addChannelingIntSet(state, variable, target)
                added += sink.size - before
            } else {
                val span = domain.spanOrNull(MAX_CHOICE_VALUES) ?: continue
                val offset = state.rng.nextInt(span.size)
                for (i in 0 until span.size) {
                    val target = span.valueAt((offset + i) % span.size)
                    if (target == constants.bound || target == state.assignment.intValue(variable)) continue
                    val before = sink.size
                    sink.addChannelingIntSet(state, variable, target)
                    added += sink.size - before
                    if (added >= remaining) break
                }
            }
            if (added >= remaining) break
        }
        return added
    }

    private fun input(variable: Int, target: Long, depth: Int, remaining: Int): Int {
        if (!sink.allowsDefinedInt(variable)) return 0
        if (state.invariants?.literalChannel(variable) != null) return propose(variable, target, depth + 1, remaining)
        val before = sink.size
        sink.addChannelingIntSet(state, variable, target)
        return sink.size - before
    }

    private companion object {
        const val MAX_ALTERNATIVES = 4
        const val MAX_CHOICE_VALUES = 32L
        const val MAX_DEPTH = 16
    }
}
