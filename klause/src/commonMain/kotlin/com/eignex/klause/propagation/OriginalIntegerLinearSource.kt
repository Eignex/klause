package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearPropagator
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Term

internal class OriginalIntegerLinearSource(
    private val state: PropagationState,
    private val work: IntegerSourceWork,
) {
    private fun decline(cause: String): Nothing? {
        state.arithmeticLearningTrace?.record("integer.retained.original-word.reject.$cause")
        return null
    }

    fun source(factorId: Int): LinearPropagator? {
        if (factorId !in 0 until state.baseFactorCount) return decline("not-original")
        val original = state.problem.factors[factorId]
        val size = when (original) {
            is Linear -> original.vars.size
            is ExactLinear -> original.size
            else -> return decline("source-kind")
        }
        repeat(size) { repeat(4) { work.visit() } }
        repeat(8) { work.visit() }
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
