package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearPropagator
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedLinearPropagator
import com.eignex.klause.factor.table.Element
import com.eignex.klause.factor.table.ElementPropagator
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.util.addExact
import com.eignex.klause.util.subExact

internal class IntegerSourceRow(val linear: LinearPropagator, val guard: Int = Lit.NONE) {
    fun row(useLo: Boolean): IntegerConflictRow? = IntegerConflictRow.source(linear, useLo, guard)
}

internal class GuardedIntegerSource(private val state: PropagationState) {
    fun source(factorId: Int, trail: Int = state.undo.size, forBoundReason: Boolean = false): IntegerSourceRow? {
        if (factorId !in 0 until state.baseFactorCount) return null
        return when (val original = state.problem.factors[factorId]) {
            is Linear -> {
                if (original.vars.size > INTEGER_LEARNING_TERMS || original.realVars.isNotEmpty()) return null
                val constants = original.integerConstants ?: return null
                val source = state.factorAt(factorId) as? LinearPropagator ?: return null
                if (source.boolVars.isNotEmpty() || source.op == LinearOp.NE || source.op != original.op ||
                    source.bound != constants.bound || !source.vars.contentEquals(original.vars) ||
                    !source.coeffs.contentEquals(constants.coeffs)) return null
                IntegerSourceRow(source)
            }
            is ReifiedLinear -> {
                if (original.vars.size > INTEGER_LEARNING_TERMS) return null
                val constants = original.integerConstants ?: return null
                val source = state.factorAt(factorId) as? ReifiedLinearPropagator ?: return null
                if (source.auxBoolVar != original.auxBoolVar || source.op != original.op ||
                    source.bound != constants.bound || !source.vars.contentEquals(original.vars) ||
                    !source.coeffs.contentEquals(constants.coeffs)) return null
                val truth = state.boolValues[original.auxBoolVar] ?: return null
                val guard = Lit.make(original.auxBoolVar, truth)
                if (!state.integerGuardTrueAt(guard, trail)) return null
                val op = if (truth) original.op else when (original.op) {
                    LinearOp.LE -> LinearOp.GE
                    LinearOp.GE -> LinearOp.LE
                    LinearOp.EQ -> LinearOp.NE
                    LinearOp.NE -> LinearOp.EQ
                }
                if (op == LinearOp.NE) return null
                val bound = if (truth) constants.bound else when (original.op) {
                    LinearOp.LE -> addExact(constants.bound, 1L)
                    LinearOp.GE -> subExact(constants.bound, 1L)
                    else -> constants.bound
                }
                IntegerSourceRow(LinearPropagator(intArrayOf(), source.intVars, source.coeffs, source.vars, op, bound),
                    guard)
            }
            is Element -> {
                if (forBoundReason || !original.arrIsVars) return null
                val source = state.factorAt(factorId) as? ElementPropagator ?: return null
                if (source.idx != original.idx || source.result != original.result ||
                    source.arrIsVars != original.arrIsVars || source.indexOffset != original.indexOffset ||
                    !source.arr.contentEquals(original.arr)) return null
                val selected = state.domainAt(original.idx, trail)
                if (selected.min != selected.max) return null
                val first = original.indexOffset.toLong()
                if (selected.min < first || selected.min >= first + original.arr.size) return null
                val cell = original.arr[(selected.min - first).toInt()]
                if (cell !in 0 until state.problem.numIntVars.toLong()) return null
                val variables = intArrayOf(original.result, cell.toInt())
                val guard = Lit.make(state.atomVarEq(original.idx, selected.min), true)
                if (!state.integerGuardTrueAt(guard, trail)) return null
                IntegerSourceRow(LinearPropagator(intArrayOf(), variables, longArrayOf(1, -1), variables,
                    LinearOp.EQ, 0), guard)
            }
            else -> null
        }
    }
}

internal fun PropagationState.integerGuardTrueAt(guard: Int, trail: Int = undo.size): Boolean {
    if (guard == Lit.NONE) return true
    val variable = Lit.variable(guard)
    if (variable < problem.numBoolVars) return boolPinnedAt(variable, trail) && litTruth(guard) == true
    val atom = atomIdOf(variable)
    val domain = domainAt(atoms.intVar[atom], trail)
    val threshold = atoms.threshold[atom]
    val truth = when (atoms.kind[atom]) {
        AtomKind.GE -> if (domain.min >= threshold) true else if (domain.max < threshold) false else null
        AtomKind.LE -> if (domain.max <= threshold) true else if (domain.min > threshold) false else null
        AtomKind.EQ -> if (domain.min == threshold && domain.max == threshold) true else
            if (!inDomainAt(atoms.intVar[atom], threshold, trail, domain)) false else null
    }
    return truth != null && Lit.evaluate(guard, truth)
}
