package com.eignex.klause.factor.bool

import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.model.PbOp
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PseudoBooleanTest {

    @Test
    fun `remapping an identified pair should retain both terms`() {
        val factor = PseudoBoolean(
            weights = longArrayOf(2, 3),
            literals = intArrayOf(Lit.make(0, true), Lit.make(1, true)),
            op = PbOp.LE,
            bound = 4,
        )
        val mapped = factor.remap(VarRemap(intArrayOf(0, 0), intArrayOf())) as PseudoBoolean

        assertContentEquals(longArrayOf(2, 3), mapped.weights)
        assertContentEquals(intArrayOf(Lit.make(0, true), Lit.make(0, true)), mapped.literals)
        assertEquals(4L, mapped.bound)
    }

    @Test
    fun `parallel pseudo Boolean arrays should be required to have equal lengths`() {
        assertFailsWith<IllegalArgumentException> {
            PseudoBoolean(longArrayOf(1), intArrayOf(Lit.make(0, true), Lit.make(1, true)), PbOp.LE, 1)
        }
    }

    @Test
    fun `pseudo Boolean sums outside Long range should retain exact source constants`() {
        val factor = PseudoBoolean(longArrayOf(Long.MAX_VALUE, 1),
            intArrayOf(Lit.make(0, true), Lit.make(1, true)), PbOp.LE, 0)

        assertEquals(bigIntOf(Long.MAX_VALUE), factor.constants.exactCoeff(0))
        assertEquals(bigIntOf(1), factor.constants.exactCoeff(1))
    }

    @Test
    fun `wide remapping should preserve exact weights and their literal roles`() {
        val wide = parseBigInt("18446744073709551616")
        val factor = PseudoBoolean(arrayOf(wide, bigIntOf(-2)),
            intArrayOf(Lit.make(0, true), Lit.make(1, false)), PbOp.GE, wide)
        val mapping = VarRemap(intArrayOf(1, 1), intArrayOf())

        val mapped = assertIs<PseudoBoolean>(factor.remap(mapping))

        assertEquals(wide, mapped.constants.exactCoeff(0))
        assertContentEquals(intArrayOf(Lit.make(1, true), Lit.make(1, false)), mapped.literals)
        assertEquals(mapped.structuralKey().hashCode(), factor.remapStructuralHash(mapping))
        assertNotEquals(factor.structuralKey(), PseudoBoolean(arrayOf(wide, bigIntOf(-2)),
            factor.literals, PbOp.GE, bigIntOf(0)).structuralKey())
    }

    @Test
    fun `wide reified colorings should permit identified indicator and body colors`() {
        val wide = parseBigInt("18446744073709551616")
        val factor = ReifiedPseudoBoolean(0, arrayOf(wide), intArrayOf(Lit.make(1, true)), PbOp.EQ, wide)
        val coloring = VarRemap(intArrayOf(0, 0), intArrayOf())

        val hash = runCatching { factor.remapStructuralHash(coloring) }

        assertTrue(hash.isSuccess)
    }

    @Test
    fun `reified pseudo Boolean body should not contain its indicator`() {
        assertFailsWith<IllegalArgumentException> {
            ReifiedPseudoBoolean(0, longArrayOf(1), intArrayOf(Lit.make(0, true)), PbOp.EQ, 1)
        }
    }
}
