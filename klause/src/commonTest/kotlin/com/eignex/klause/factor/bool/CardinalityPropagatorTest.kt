package com.eignex.klause.factor.bool

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CardinalityPropagatorTest {

    @Test
    fun `cardinality preserves multiple literals for one Boolean variable`() {
        val factor = Cardinality(intArrayOf(Lit.make(0, true), Lit.make(0, false)), min = 0, max = 1)

        assertContentEquals(intArrayOf(Lit.make(0, true), Lit.make(0, false)), factor.literals)
    }

    @Test
    fun `remapping a cardinality preserves collapsed Boolean variables`() {
        val factor = Cardinality(intArrayOf(Lit.make(0, true), Lit.make(1, true)), min = 0, max = 1)

        val remapped = factor.remap(VarRemap(intArrayOf(0, 0), intArrayOf())) as Cardinality

        assertContentEquals(intArrayOf(Lit.make(0, true), Lit.make(0, true)), remapped.literals)
    }

    @Test
    fun `at-least boundary forces remaining unassigned to true`() {
        val problem = Problem(
            numBoolVars = 4,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Cardinality(IntArray(4) { Lit.make(it, true) }, min = 2, max = 4)),
        )
        val session = PropagationSession(problem)
        assertIs<PropagationResult.Implied>(session.pinBool(0, false))
        assertIs<PropagationResult.Implied>(session.pinBool(1, false))
        assertEquals(true, session.boolValue(2))
        assertEquals(true, session.boolValue(3))
    }

}
