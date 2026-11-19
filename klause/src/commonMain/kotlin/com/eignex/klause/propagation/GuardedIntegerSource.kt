package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearPropagator
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedLinearPropagator
import com.eignex.klause.factor.arithmetic.WideLinearPropagator
import com.eignex.klause.factor.arithmetic.WideReifiedLinearPropagator
import com.eignex.klause.factor.table.Element
import com.eignex.klause.factor.table.ElementPropagator
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Term
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.minus
import com.eignex.util.numbers.addExact
import com.eignex.util.numbers.subExact

internal class IntegerSourceRow private constructor(
    val linear: LinearPropagator?,
    private val retained: IntegerConflictRow?,
    private val wide: WideLinearPropagator?,
    val guard: Int,
) {
    constructor(linear: LinearPropagator, guard: Int = Lit.NONE) : this(linear, null, null, guard)
    constructor(row: IntegerConflictRow) : this(null, row, null, row.guard)
    constructor(wide: WideLinearPropagator, guard: Int = Lit.NONE) : this(null, null, wide, guard)
    val identity: Any get() = linear ?: wide ?: checkNotNull(retained)
    val op: LinearOp get() = linear?.op ?: wide?.op ?: LinearOp.LE
    val variables: IntArray get() = linear?.vars ?: wide?.vars ?: checkNotNull(retained).variables
    fun row(useLo: Boolean, budget: ArithmeticLearningBudget? = null): IntegerConflictRow? =
        if (retained != null) retained.takeIf { useLo } else if (wide != null)
            IntegerConflictRow.source(wide.vars, wide.coeffs, wide.bound, useLo, guard, budget) else
            IntegerConflictRow.source(checkNotNull(linear), useLo, guard, budget)
}

internal class GuardedIntegerSource(private val state: PropagationState, private val budget: ArithmeticLearningBudget = ArithmeticLearningBudget(state)) {
    private val validated = mutableMapOf<Int, IntegerSourceRow>()

    private fun decline(cause: String, factorId: Int): Nothing? {
        state.arithmeticLearningTrace?.record("integer.source-decline.$cause", factorId.toLong())
        return null
    }

    fun source(factorId: Int, trail: Int = state.undo.size, forBoundReason: Boolean = false): IntegerSourceRow? {
        budget.spend()
        if (forBoundReason && factorId in 0 until state.baseFactorCount && state.problem.factors[factorId] is Element) {
            return decline("element-support-reason", factorId)
        }
        validated[factorId]?.let {
            return if (state.integerGuardTrueAt(it.guard, trail)) it else decline("guard-history", factorId)
        }
        val source = discover(factorId, trail, forBoundReason) ?: return null
        budget.spend(bytes = 80)
        validated[factorId] = source
        return source
    }

    private fun discover(factorId: Int, trail: Int, forBoundReason: Boolean): IntegerSourceRow? {
        budget.spend()
        if (factorId >= state.baseFactorCount && factorId < state.totalFactorCount) {
            val retained = state.factorAt(factorId) as? LearnedIntegerPropagator
                ?: return decline("not-arithmetic", factorId)
            if (!state.integerGuardTrueAt(retained.row.guard, trail)) return decline("guard-history", factorId)
            return IntegerSourceRow(retained.row)
        }
        if (factorId !in 0 until state.baseFactorCount) return decline("not-original", factorId)
        budget.spend(state.factorAt(factorId).intVars.size.toLong() + 1)
        return when (val original = state.problem.factors[factorId]) {
            is ExactLinear -> {
                if (original.variables.reals.isNotEmpty()) return decline("real-coordinates", factorId)
                if (original.activator != LinearRow.ALWAYS || original.strict) {
                    return decline("exact-activation-or-strictness", factorId)
                }
                if (state.factorAt(factorId) is WideLinearPropagator) {
                    val constants = original.constants as? IntegralConstants ?: return decline("constants", factorId)
                    val source = state.factorAt(factorId) as WideLinearPropagator
                    if (source.op == LinearOp.NE || source.op != original.relation || source.bound != constants.exactBound ||
                        source.vars.size != original.size || source.vars.indices.any {
                            source.vars[it] != Term.intVar(original.ref(it)) || source.coeffs[it] != constants.exactCoeff(it)
                        }) return decline("projection-identity", factorId)
                    return IntegerSourceRow(source)
                }
                val constants = original.constants as? IntegerConstants
                    ?: return decline("constants", factorId)
                val source = state.factorAt(factorId) as? LinearPropagator
                    ?: return decline("projection-kind", factorId)
                if (source.boolVars.isNotEmpty() || source.op == LinearOp.NE || source.op != original.relation ||
                    source.bound != constants.bound || source.vars.size != original.size ||
                    source.vars.indices.any { source.vars[it] != Term.intVar(original.ref(it)) } ||
                    !source.coeffs.contentEquals(constants.coeffs)) {
                    return decline("projection-identity-or-operation", factorId)
                }
                IntegerSourceRow(source)
            }
            is Linear -> {
                if (original.realVars.isNotEmpty()) return decline("real-coordinates", factorId)
                if (state.factorAt(factorId) is WideLinearPropagator) {
                    val constants = original.integralConstants ?: return decline("constants", factorId)
                    val source = state.factorAt(factorId) as WideLinearPropagator
                    if (source.op == LinearOp.NE || source.op != original.op || source.bound != constants.exactBound ||
                        !source.vars.contentEquals(original.vars) || source.coeffs.indices.any {
                            source.coeffs[it] != constants.exactCoeff(it)
                        }) return decline("projection-identity", factorId)
                    return IntegerSourceRow(source)
                }
                val constants = original.integerConstants ?: return decline("constants", factorId)
                val source = state.factorAt(factorId) as? LinearPropagator
                    ?: return decline("projection-kind", factorId)
                if (source.boolVars.isNotEmpty() || source.op == LinearOp.NE || source.op != original.op ||
                    source.bound != constants.bound || !source.vars.contentEquals(original.vars) ||
                    !source.coeffs.contentEquals(constants.coeffs)) {
                    return decline("projection-identity-or-operation", factorId)
                }
                IntegerSourceRow(source)
            }
            is ReifiedLinear -> {
                if (state.factorAt(factorId) is WideReifiedLinearPropagator) {
                    val source = state.factorAt(factorId) as WideReifiedLinearPropagator
                    if (source.auxBoolVar != original.auxBoolVar || source.op != original.op ||
                        source.bound != original.constants.exactBound || !source.vars.contentEquals(original.vars) ||
                        source.coeffs.indices.any { source.coeffs[it] != original.constants.exactCoeff(it) }) {
                        return decline("projection-identity", factorId)
                    }
                    val truth = state.boolValues[original.auxBoolVar] ?: return decline("guard-unassigned", factorId)
                    val guard = Lit.make(original.auxBoolVar, truth)
                    if (!state.integerGuardTrueAt(guard, trail)) return decline("guard-history", factorId)
                    val op = if (truth) original.op else when (original.op) {
                        LinearOp.LE -> LinearOp.GE
                        LinearOp.GE -> LinearOp.LE
                        LinearOp.EQ -> LinearOp.NE
                        LinearOp.NE -> LinearOp.EQ
                    }
                    if (op == LinearOp.NE) return decline("disequality", factorId)
                    val bound = if (truth) source.bound else when (original.op) {
                        LinearOp.LE -> source.bound + BIG_ONE
                        LinearOp.GE -> source.bound - BIG_ONE
                        else -> source.bound
                    }
                    return IntegerSourceRow(WideLinearPropagator(source.intVars, source.vars, source.coeffs, op, bound), guard)
                }
                val constants = original.integerConstants ?: return decline("wide-constants", factorId)
                val source = state.factorAt(factorId) as? ReifiedLinearPropagator
                    ?: return decline("projection-kind", factorId)
                if (source.auxBoolVar != original.auxBoolVar || source.op != original.op ||
                    source.bound != constants.bound || !source.vars.contentEquals(original.vars) ||
                    !source.coeffs.contentEquals(constants.coeffs)) return decline("projection-identity", factorId)
                val truth = state.boolValues[original.auxBoolVar] ?: return decline("guard-unassigned", factorId)
                val guard = Lit.make(original.auxBoolVar, truth)
                if (!state.integerGuardTrueAt(guard, trail)) return decline("guard-history", factorId)
                val op = if (truth) original.op else when (original.op) {
                    LinearOp.LE -> LinearOp.GE
                    LinearOp.GE -> LinearOp.LE
                    LinearOp.EQ -> LinearOp.NE
                    LinearOp.NE -> LinearOp.EQ
                }
                if (op == LinearOp.NE) return decline("disequality", factorId)
                val bound = if (truth) constants.bound else when (original.op) {
                    LinearOp.LE -> addExact(constants.bound, 1L)
                    LinearOp.GE -> subExact(constants.bound, 1L)
                    else -> constants.bound
                }
                IntegerSourceRow(LinearPropagator(intArrayOf(), source.intVars, source.coeffs, source.vars, op, bound),
                    guard)
            }
            is Element -> {
                if (forBoundReason) return decline("element-support-reason", factorId)
                if (!original.arrIsVars) return decline("element-constant-array", factorId)
                val source = state.factorAt(factorId) as? ElementPropagator
                    ?: return decline("projection-kind", factorId)
                if (source.idx != original.idx || source.result != original.result ||
                    source.arrIsVars != original.arrIsVars || source.indexOffset != original.indexOffset ||
                    source.arr !== original.arr) return decline("projection-identity", factorId)
                val selected = state.domainAt(original.idx, trail)
                if (selected.min != selected.max) return decline("element-unfixed-selection", factorId)
                val first = original.indexOffset.toLong()
                if (selected.min < first || selected.min >= first + original.arr.size) {
                    return decline("element-selection-range", factorId)
                }
                val cell = original.arr[(selected.min - first).toInt()]
                if (cell !in 0 until state.problem.numIntVars.toLong()) {
                    return decline("element-cell-coordinate", factorId)
                }
                val variables = intArrayOf(original.result, cell.toInt())
                val guard = Lit.make(state.atomVarEq(original.idx, selected.min), true)
                if (!state.integerGuardTrueAt(guard, trail)) return decline("guard-history", factorId)
                IntegerSourceRow(LinearPropagator(intArrayOf(), variables, longArrayOf(1, -1), variables,
                    LinearOp.EQ, 0), guard)
            }
            else -> decline("source-kind", factorId)
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
        AtomKind.EQ -> if (domain.min == threshold && domain.max == threshold) true
        else if (!inDomainAt(atoms.intVar[atom], threshold, trail, domain)) false else null
    }
    return truth != null && Lit.evaluate(guard, truth)
}
