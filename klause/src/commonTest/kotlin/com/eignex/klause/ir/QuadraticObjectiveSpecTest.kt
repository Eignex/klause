package com.eignex.klause.ir

import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QuadraticObjectiveSpecTest {
    @Test
    fun `source rational coefficients normalize sign and common factors`() {
        val coefficient = QuadraticCoefficient.of(bigIntOf(-6), bigIntOf(-8))

        assertEquals(QuadraticCoefficient.of(bigIntOf(3), bigIntOf(4)), coefficient)
    }

    @Test
    fun `zero source denominator is rejected`() {
        assertFailsWith<IllegalArgumentException> { QuadraticCoefficient.of(bigIntOf(1), bigIntOf(0)) }
    }

    @Test
    fun `source term mutation does not change captured objective`() {
        val x = QuadraticVariable.Real(SourceRealId(0))
        val term = QuadraticObjectiveTerm(QuadraticCoefficient.ONE, x, x)
        val terms = mutableListOf(term, term)
        val objective = QuadraticObjectiveSpec(terms)

        terms.clear()
        (objective.terms as MutableList<QuadraticObjectiveTerm>).clear()

        assertEquals(listOf(term, term), objective.terms)
    }

    @Test
    fun `remapping preserves distinct source namespaces`() {
        val objective = QuadraticObjectiveSpec(listOf(
            QuadraticObjectiveTerm(QuadraticCoefficient.ONE, QuadraticVariable.Bool(SourceBoolId(0))),
            QuadraticObjectiveTerm(
                QuadraticCoefficient.ONE, QuadraticVariable.Int(SourceIntId(0)), QuadraticVariable.Real(SourceRealId(0)),
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
            QuadraticObjectiveTerm(QuadraticCoefficient.ONE, QuadraticVariable.Int(SourceIntId(0))),
        ))

        assertFailsWith<IllegalArgumentException> { objective.remap(VarRemap(intArrayOf(), intArrayOf(-1))) }
    }
}
