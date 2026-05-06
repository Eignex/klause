package com.eignex.klause.solver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LitTest {

    @Test
    fun makeAndDecodePositiveAndNegative() {
        for (v in listOf(0, 1, 7, 31, 1000, Int.MAX_VALUE ushr 1)) {
            val pos = Lit.make(v, positive = true)
            assertEquals(v, Lit.variable(pos))
            assertTrue(Lit.isPositive(pos))

            val neg = Lit.make(v, positive = false)
            assertEquals(v, Lit.variable(neg))
            assertFalse(Lit.isPositive(neg))
        }
    }

    @Test
    fun negateFlipsPolarityKeepsVariable() {
        for (v in 0..5) {
            val pos = Lit.make(v, positive = true)
            val flipped = Lit.negate(pos)
            assertEquals(v, Lit.variable(flipped))
            assertFalse(Lit.isPositive(flipped))
            // Double negate returns the original.
            assertEquals(pos, Lit.negate(flipped))
        }
    }

    @Test
    fun evaluateTruthTable() {
        val pos = Lit.make(0, positive = true)
        val neg = Lit.make(0, positive = false)
        // Positive literal: true when var is true.
        assertTrue(Lit.evaluate(pos, value = true))
        assertFalse(Lit.evaluate(pos, value = false))
        // Negative literal: true when var is false.
        assertFalse(Lit.evaluate(neg, value = true))
        assertTrue(Lit.evaluate(neg, value = false))
    }

    @Test
    fun differentVarsProduceDistinctLiterals() {
        // Sanity: the lit encoding is injective over (var, polarity).
        val seen = HashSet<Int>()
        for (v in 0..15) {
            for (pos in listOf(true, false)) {
                val lit = Lit.make(v, pos)
                assertTrue(seen.add(lit), "duplicate lit encoding for v=$v positive=$pos")
            }
        }
    }
}
