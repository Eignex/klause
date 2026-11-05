package com.eignex.klause.propagation

import kotlin.test.Test
import kotlin.test.assertContentEquals

class ConflictFactorScratchTest {
    @Test
    fun `factor membership grows across words and preserves first encounter order`() {
        val scratch = ConflictFactorScratch()

        for (factor in intArrayOf(129, 0, 64, 63, 129, 64)) scratch.add(factor)

        assertContentEquals(intArrayOf(129, 0, 64, 63), scratch.frontier.toIntArray())
    }

    @Test
    fun `clearing permits previously seen factors in the next conflict`() {
        val scratch = ConflictFactorScratch()
        for (factor in intArrayOf(129, 0, 64, 63)) scratch.add(factor)

        scratch.clear()
        for (factor in intArrayOf(64, 129, 64)) scratch.add(factor)

        assertContentEquals(intArrayOf(64, 129), scratch.frontier.toIntArray())
    }
}
