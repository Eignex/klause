package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.DivMod
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep.SweepNode
import com.eignex.klause.solver.Assignment
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.toLongExact

internal class DivModDefinition(private val relation: DivMod, override val out: Int) : SweepNode {
    override val outIsBool: Boolean get() = false
    override val boolInputs: IntArray get() = EmptyIntArray
    override val intInputs: IntArray = relation.divisor?.let { intArrayOf(relation.dividend, it) }
        ?: intArrayOf(relation.dividend)

    override fun eval(assignment: Assignment, domains: Array<IntDomain>): Long {
        val outputs = relation.outputs(bigIntOf(assignment.intValue(relation.dividend)),
            relation.divisor?.let { bigIntOf(assignment.intValue(it)) } ?: checkNotNull(relation.constantDivisor))
            ?: return SweepNode.NO_WRITE
        val exact = if (out == relation.quotient) outputs.first else outputs.second
        val domain = domains[out]
        val value = when {
            exact < bigIntOf(domain.min) -> domain.min
            exact > bigIntOf(domain.max) -> domain.max
            else -> exact.toLongExact()
        }
        return domain.clamp(value)
    }
}

internal fun divModDefinitions(problem: Problem): List<SweepNode> = problem.factors.flatMap { factor ->
    val relation = factor as? DivMod ?: return@flatMap emptyList()
    listOf(relation.quotient, relation.remainder).mapNotNull { output ->
        if (relation.quotient == relation.remainder || output == relation.dividend || output == relation.divisor ||
            !problem.intBounds.hasLower(output) || problem.intBounds.lower(output) == Long.MIN_VALUE) null
        else DivModDefinition(relation, output)
    }
}
