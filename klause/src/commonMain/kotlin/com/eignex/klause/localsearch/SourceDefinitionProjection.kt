package com.eignex.klause.localsearch

import com.eignex.klause.ir.DefinitionHint
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep.SweepNode
import com.eignex.klause.solver.Assignment
import com.eignex.klause.solver.objective.FunctionalObjective
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.MutableIntIntMap
import com.eignex.klause.util.addExact
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.mulExact
import com.eignex.klause.util.plus
import com.eignex.klause.util.times

internal fun sourceDefinitionSweep(
    problem: Problem,
    hints: List<DefinitionHint>,
    cancellation: Cancellation,
): DefinitionalSweep? {
    if (hints.isEmpty() || cancellation()) return null
    var visits = 0
    val integerClaims = MutableIntIntMap()
    val booleanClaims = MutableIntIntMap()
    for (hint in hints) {
        if (++visits % INITIALIZATION_BATCH == 0 && cancellation()) return null
        val claims = if (hint is DefinitionHint.Boolean) booleanClaims else integerClaims
        val count = if (hint is DefinitionHint.Boolean) problem.numBoolVars else problem.numIntVars
        if (hint.output in 0 until count) claims.addTo(hint.output, 1)
    }
    val nodes = ArrayList<SweepNode>()
    for (hint in hints) {
        if (++visits % INITIALIZATION_BATCH == 0 && cancellation()) return null
        val claims = if (hint is DefinitionHint.Boolean) booleanClaims else integerClaims
        if (claims.getOrDefault(hint.output, 0) != 1) continue
        if (hint is DefinitionHint.Integer && (!problem.intBounds.hasLower(hint.output) ||
                problem.intBounds.lower(hint.output) == Long.MIN_VALUE)
        ) continue
        val node = sourceDefinitionNode(problem, hint) ?: continue
        val outputs = if (node.outIsBool) problem.numBoolVars else problem.numIntVars
        if (node.out !in 0 until outputs || node.intInputs.any { it !in 0 until problem.numIntVars } ||
            node.boolInputs.any { it !in 0 until problem.numBoolVars }
        ) continue
        nodes.add(node)
    }
    val ordered = orderedDefinitions(problem, nodes, cancellation)
    return if (ordered.isEmpty()) null else DefinitionalSweep(ordered)
}

private fun sourceDefinitionNode(problem: Problem, hint: DefinitionHint): SweepNode? = when (hint) {
    is DefinitionHint.BooleanValue -> LiteralIntDefinition(hint.output, hint.literal)

    is DefinitionHint.Element -> if ((hint.variables == null) == (hint.values == null)) null else
        SweepNode.ElementDef(hint.output, hint.index, hint.variables, hint.values, hint.offset)

    is DefinitionHint.Comparison -> {
        if (hint.coefficients.size != hint.inputs.size ||
            (hint.strict && hint.relation != LinearOp.LE && hint.relation != LinearOp.GE)
        ) null else CapturedBooleanDefinition(hint)
    }

    is DefinitionHint.Membership -> CapturedBooleanDefinition(hint)

    is DefinitionHint.Fold -> hint.definition.let { SweepNode.BoolFold(it.out, it.lits, it.isAnd) }

    is DefinitionHint.IntegerReference, is DefinitionHint.BooleanReference -> null

    is DefinitionHint.Integer -> sourceIntegerDefinition(problem, hint)
}

internal fun DefinitionHint.Operand.nativeOperand(): FunctionalObjective.Operand = when (this) {
    is DefinitionHint.Operand.Variable -> FunctionalObjective.Operand.v(id)
    is DefinitionHint.Operand.Constant -> FunctionalObjective.Operand.c(value)
}

internal fun DefinitionHint.Operand.variableOrNull(): Int? =
    (this as? DefinitionHint.Operand.Variable)?.id

internal class CapturedBooleanDefinition(private val hint: DefinitionHint.Boolean) : SweepNode {
    override val out: Int get() = hint.output
    override val outIsBool: Boolean get() = true
    override val boolInputs: IntArray get() = EmptyIntArray
    override val intInputs: IntArray = when (hint) {
        is DefinitionHint.Comparison -> hint.inputs.mapNotNull { it.variableOrNull() }.distinct().toIntArray()
        is DefinitionHint.Membership -> intArrayOf(hint.input)
        else -> error("a Boolean source predicate is required")
    }
    private val members = (hint as? DefinitionHint.Membership)?.values?.toHashSet()

    fun holds(intOf: (Int) -> Long): Boolean = when (val source = hint) {
        is DefinitionHint.Comparison -> {
            val comparison = compare(source, intOf)
            when (source.relation) {
                LinearOp.EQ -> comparison == 0
                LinearOp.NE -> comparison != 0
                LinearOp.LE -> if (source.strict) comparison < 0 else comparison <= 0
                LinearOp.GE -> if (source.strict) comparison > 0 else comparison >= 0
            }
        }
        is DefinitionHint.Membership -> intOf(source.input) in checkNotNull(members)
        else -> error("a Boolean source predicate is required")
    }

    private fun compare(source: DefinitionHint.Comparison, intOf: (Int) -> Long): Int = try {
        var activity = 0L
        for (k in source.inputs.indices) {
            activity = addExact(activity, mulExact(source.coefficients[k], source.inputs[k].value(intOf)))
        }
        activity.compareTo(source.bound)
    } catch (_: CheckedLongOverflowException) {
        var activity = BIG_ZERO
        for (k in source.inputs.indices) {
            activity += bigIntOf(source.coefficients[k]) * bigIntOf(source.inputs[k].value(intOf))
        }
        activity.compareTo(bigIntOf(source.bound))
    }

    override fun eval(assignment: Assignment, domains: Array<IntDomain>): Long =
        if (holds { assignment.intValue(it) }) 1L else 0L
}

private fun DefinitionHint.Operand.value(intOf: (Int) -> Long): Long = when (this) {
    is DefinitionHint.Operand.Variable -> intOf(id)
    is DefinitionHint.Operand.Constant -> value
}
