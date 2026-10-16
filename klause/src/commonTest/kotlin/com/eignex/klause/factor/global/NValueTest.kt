package com.eignex.klause.factor.global

import com.eignex.klause.ir.FactorReduction
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.VarRemap
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class NValueTest {
    @Test
    fun `remapping should preserve excluded values and paired presence roles`() {
        val factor = NValue(2, intArrayOf(0, 1, 0), presents = intArrayOf(
            Lit.make(0, true), Lit.make(1, false), Lit.make(0, true),
        ), exceptSet = longArrayOf(Long.MIN_VALUE, 0))

        val mapped = assertIs<NValue>(factor.remap(VarRemap(intArrayOf(1, 0), intArrayOf(2, 1, 0))))

        assertEquals(0, mapped.n)
        assertContentEquals(intArrayOf(2, 1, 2), mapped.xs)
        assertContentEquals(intArrayOf(Lit.make(1, true), Lit.make(0, false), Lit.make(1, true)), mapped.presents)
        assertContentEquals(longArrayOf(Long.MIN_VALUE, 0), mapped.exceptSet)
    }

    @Test
    fun `excluded value sets should participate in structural identity`() {
        val factor = NValue(2, intArrayOf(0, 1), exceptSet = longArrayOf(2, 0, 2))
        val reordered = NValue(2, intArrayOf(1, 0), exceptSet = longArrayOf(0, 2))
        val plain = NValue(2, intArrayOf(0, 1))

        assertEquals(factor.structuralKey(), reordered.structuralKey())
        assertNotEquals(factor.structuralKey(), plain.structuralKey())
    }

    @Test
    fun `one counted value should not reduce to equality when values are excluded`() {
        val factor = NValue(2, intArrayOf(0, 1), exceptSet = longArrayOf(0))

        val reduced = factor.structuralReduce(arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(1, 1)))

        assertEquals(FactorReduction.Unchanged, reduced)
    }
}
