package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearPropagator
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Term
import com.eignex.klause.util.addExact
import com.eignex.klause.util.mulExact
import com.eignex.klause.util.subExact

internal class IntegerBoundImplicationSource(
    private val state: PropagationState,
    private val sourceWork: IntegerSourceWork,
) {
    private fun decline(cause: String): Nothing? {
        state.arithmeticLearningTrace?.record("integer.retained.bound-implication.reject.$cause")
        return null
    }

    fun source(variable: Int, lower: Boolean, marker: IntArray): IntegerConflictRow? {
        sourceWork.visit()
        if (variable !in 0 until state.problem.numIntVars) return decline("coordinate")
        val current = if (lower) state.intMinAntecedents[variable] else state.intMaxAntecedents[variable]
        val owner = if (lower) state.intMinReason[variable] else state.intMaxReason[variable]
        if (state.incremental || !state.undoLogging || marker !== current || !isLazyReason(marker) ||
            marker.size != LAZY_PAYLOAD + 6 || marker[1] != owner || marker[2] !in 0..state.undo.size ||
            marker[LAZY_PAYLOAD + 5] != 0) return decline("marker")
        val bounds = state.problem.intBounds
        if (!bounds.hasLower(variable) || !bounds.hasUpper(variable) ||
            bounds.lower(variable) != 0L || bounds.upper(variable) != 1L) return decline("declared-pivot")
        val source = projection(owner) ?: return null
        val position = marker[LAZY_PAYLOAD]
        if (source.vars.getOrNull(position) != variable) return decline("position")
        val side = marker[LAZY_PAYLOAD + 1]
        if (side !in 0..1) return decline("side")
        val useLo = side == 1
        if ((source.op == LinearOp.LE && !useLo) || (source.op == LinearOp.GE && useLo)) return decline("side")
        val coefficient = if (useLo) source.coeffs[position] else subExact(0L, source.coeffs[position])
        if (coefficient == 0L || coefficient == Long.MIN_VALUE || (coefficient > 0L) == lower) {
            return decline("pivot")
        }
        val live = state.intDomains[variable]
        if ((lower && live.min != 1L) || (!lower && live.max != 0L)) return decline("nonunit-endpoint")
        val terms = linkedMapOf(variable to if (lower) -1L else 1L)
        val other = historicalOther(source, position, useLo, marker[2], terms) ?: return null
        val bound = if (useLo) source.bound else subExact(0L, source.bound)
        val residual = subExact(bound, other)
        val requested = if (lower) IntegerConflictRow.ceilDivide(residual, coefficient)
            else IntegerConflictRow.floorDivide(residual, coefficient)
        if (requested != if (lower) 1L else 0L) return decline("raw-unit")
        val prior = state.domainAt(variable, marker[2])
        if ((lower && prior.min >= requested) || (!lower && prior.max <= requested) ||
            !state.inDomainAt(variable, requested, marker[2], prior) ||
            !state.rawIntegerEndpointSupported(variable, lower, requested, marker[2])) return decline("raw-history")
        repeat(terms.size * 4 + 8) { sourceWork.visit() }
        val implicationBound = terms.values.count { it > 0L }.toLong() - 1L
        return IntegerConflictRow.normalized(terms, implicationBound, Lit.NONE)
    }

    private fun historicalOther(
        source: LinearPropagator,
        position: Int,
        useLo: Boolean,
        trail: Int,
        terms: MutableMap<Int, Long>,
    ): Long? {
        val variable = source.vars[position]
        var other = 0L
        val bounds = state.problem.intBounds
        for (index in source.vars.indices) {
            sourceWork.visit()
            val v = source.vars[index]
            if (v !in 0 until state.problem.numIntVars) return decline("coordinate")
            if (index == position) continue
            if (v == variable) return decline("repeated-pivot")
            val c = if (useLo) source.coeffs[index] else subExact(0L, source.coeffs[index])
            if (c == 0L) continue
            if ((c > 0L && !bounds.hasLower(v)) || (c < 0L && !bounds.hasUpper(v))) {
                return decline("declared-endpoint")
            }
            val baseline = if (c > 0L) bounds.lower(v) else bounds.upper(v)
            sourceWork.visit()
            val historical = state.domainAt(v, trail)
            val endpoint = if (c > 0L) historical.min else historical.max
            if ((c > 0L && endpoint < baseline) || (c < 0L && endpoint > baseline)) {
                return decline("historical-domain")
            }
            other = addExact(other, mulExact(c, endpoint))
            if (endpoint != baseline) {
                if (!bounds.hasLower(v) || !bounds.hasUpper(v) ||
                    bounds.lower(v) != 0L || bounds.upper(v) != 1L || endpoint !in 0L..1L) {
                    return decline("declared-condition")
                }
                val domain = state.intDomains[v]
                if ((endpoint == 1L && domain.min != 1L) || (endpoint == 0L && domain.max != 0L)) {
                    return decline("inactive-condition")
                }
                val term = if (endpoint == 1L) 1L else -1L
                val previous = terms.put(v, term)
                if (previous != null && previous != term) return decline("condition")
                if (terms.size > INTEGER_LEARNING_TERMS) return decline("complete-width")
            }
        }
        return other
    }

    private fun projection(factorId: Int): LinearPropagator? {
        if (factorId !in 0 until state.baseFactorCount) return decline("not-original")
        val original = state.problem.factors[factorId]
        val size = when (original) {
            is Linear -> original.vars.size
            is ExactLinear -> original.size
            else -> return decline("source-kind")
        }
        repeat(size) { repeat(4) { sourceWork.visit() } }
        repeat(8) { sourceWork.visit() }
        val source = state.factorAt(factorId) as? LinearPropagator ?: return decline("projection")
        if (source.boolVars.isNotEmpty() || source.op == LinearOp.NE || source.vars.size != size) {
            return decline("projection")
        }
        when (original) {
            is Linear -> {
                val constants = original.integerConstants ?: return decline("word")
                if (original.realVars.isNotEmpty() || source.op != original.op || source.bound != constants.bound ||
                    !source.vars.contentEquals(original.vars) || !source.coeffs.contentEquals(constants.coeffs)) {
                    return decline("identity")
                }
            }
            is ExactLinear -> {
                val constants = original.constants as? IntegerConstants ?: return decline("word")
                if (original.variables.reals.isNotEmpty() ||
                    original.activator != LinearRow.ALWAYS || original.strict ||
                    source.op != original.relation || source.bound != constants.bound ||
                    source.vars.indices.any { source.vars[it] != Term.intVar(original.ref(it)) } ||
                    !source.coeffs.contentEquals(constants.coeffs)) return decline("identity")
            }
            else -> return decline("source-kind")
        }
        return source
    }
}
