package com.eignex.klause.ir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DefinitionHintTest {
    @Test
    fun `remapping should preserve repeated integer roles and source constants`() {
        val hint = DefinitionHint.Linear(
            3, longArrayOf(2, -1, -1), arrayOf(
                DefinitionHint.Operand.Variable(3), DefinitionHint.Operand.Variable(1), DefinitionHint.Operand.Variable(2),
            ), Long.MIN_VALUE,
        )
        val mapping = VarRemap(intArrayOf(0, 0, 1), intArrayOf(2, 0, 0, 1))

        val remapped = assertIs<DefinitionHint.Linear>(hint.remap(mapping))

        assertEquals(1, remapped.output)
        assertEquals(listOf(2L, -1L, -1L), remapped.coefficients.toList())
        assertEquals(listOf(1, 0, 0), remapped.inputs.map { assertIs<DefinitionHint.Operand.Variable>(it).id })
        assertEquals(Long.MIN_VALUE, remapped.bound)
    }

    @Test
    fun `remapping Boolean folds should preserve polarity and repeated roles`() {
        val literals = intArrayOf(Lit.make(0, true), Lit.make(1, false), Lit.make(0, true))
        val hint = DefinitionHint.Fold(BoolFoldDefinition(2, literals, true))
        val mapping = VarRemap(intArrayOf(0, 0, 1), intArrayOf())

        val remapped = assertIs<DefinitionHint.Fold>(hint.remap(mapping)).definition

        assertEquals(1, remapped.out)
        assertEquals(listOf(Lit.make(0, true), Lit.make(0, false), Lit.make(0, true)), remapped.lits.toList())
    }

    @Test
    fun `remapping should keep integer and Boolean coordinate spaces separate`() {
        val hint = DefinitionHint.BooleanValue(3, Lit.make(1, false))
        val mapping = VarRemap(intArrayOf(0, 0, 1), intArrayOf(2, 0, 0, 1))

        val remapped = assertIs<DefinitionHint.BooleanValue>(hint.remap(mapping))

        assertEquals(1, remapped.output)
        assertEquals(Lit.make(0, false), remapped.literal)
    }

    @Test
    fun `remapping should decline a removed source coordinate`() {
        val input = DefinitionHint.Operand.Variable(1)
        val hints = listOf(
            DefinitionHint.Abs(3, input), DefinitionHint.Extreme(3, arrayOf(input), false),
            DefinitionHint.Product(3, input, input), DefinitionHint.Power(3, input, 2),
            DefinitionHint.Plus(3, input, input),
            DefinitionHint.Linear(3, longArrayOf(1, -1), arrayOf(input, DefinitionHint.Operand.Variable(3)), 0),
            DefinitionHint.BooleanValue(3, Lit.make(1, false)), DefinitionHint.Element(3, 1, null, longArrayOf(4), 1),
            DefinitionHint.Comparison(2, longArrayOf(1), arrayOf(input), 0, LinearOp.LE),
            DefinitionHint.Membership(2, 1, longArrayOf(0)),
            DefinitionHint.Fold(BoolFoldDefinition(2, intArrayOf(Lit.make(1, true)), false)),
            DefinitionHint.IntegerReference(1), DefinitionHint.BooleanReference(1),
        )
        val mapping = VarRemap(intArrayOf(0, -1, 1), intArrayOf(0, -1, 1, 2))

        for (hint in hints) assertNull(hint.remap(mapping))
    }

    @Test
    fun `remapping should retain an unknown Boolean alias polarity`() {
        val hint = DefinitionHint.BooleanReference(1, Lit.make(1, false))

        val remapped = assertIs<DefinitionHint.BooleanReference>(
            assertNotNull(hint.remap(VarRemap(intArrayOf(1, 0), intArrayOf()))),
        )

        assertEquals(0, remapped.output)
        assertEquals(Lit.make(0, false), remapped.literal)
    }
}
