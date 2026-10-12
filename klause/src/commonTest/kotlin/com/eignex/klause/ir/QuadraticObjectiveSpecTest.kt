package com.eignex.klause.ir

import com.eignex.klause.simplex.exact.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QuadraticObjectiveSpecTest {
    @Test
    fun `source term mutation does not change captured objective`() {
        val x = QuadraticVariable.Real(SourceRealId(0))
        val term = QuadraticObjectiveTerm(BigFraction.ONE, x, x)
        val terms = mutableListOf(term, term)
        val objective = QuadraticObjectiveSpec(terms)

        terms.clear()
        (objective.terms as MutableList).clear()

        assertEquals(listOf(term, term), objective.terms)
    }

    @Test
    fun `remapping preserves distinct source namespaces`() {
        val objective = QuadraticObjectiveSpec(listOf(
            QuadraticObjectiveTerm(BigFraction.ONE, QuadraticVariable.Bool(SourceBoolId(0))),
            QuadraticObjectiveTerm(
                BigFraction.ONE, QuadraticVariable.Int(SourceIntId(0)), QuadraticVariable.Real(SourceRealId(0)),
            ),
        ))

        val remapped = objective.remap(VarRemap(intArrayOf(2), intArrayOf(3), intArrayOf(4)))

        assertEquals(QuadraticVariable.Bool(SourceBoolId(2)), remapped.terms[0].first)
        assertEquals(QuadraticVariable.Int(SourceIntId(3)), remapped.terms[1].first)
        assertEquals(QuadraticVariable.Real(SourceRealId(4)), remapped.terms[1].second)
    }

    @Test
    fun `removed objective coordinates require exact substitution before remapping`() {
        val objective = QuadraticObjectiveSpec(listOf(
            QuadraticObjectiveTerm(BigFraction.ONE, QuadraticVariable.Int(SourceIntId(0))),
        ))

        assertFailsWith<IllegalArgumentException> { objective.remap(VarRemap(intArrayOf(), intArrayOf(-1))) }
    }
}
