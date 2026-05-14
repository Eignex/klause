package com.eignex.klause.compile

import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.schema.times
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class FloatArithmeticTest {

    @Test
    fun `shifted float comparison`() {
        class S : VariableSchema() {
            val rate by floatVar(min = 0.0, max = 1.0, buckets = 21)

            val c by constraint { (rate + 0.1) le 0.6 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(compiled.problem)
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 1)).take(50).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val rate = compiled.decode(schema.rate, s)
            assertTrue(rate <= 0.5 + 1e-9, "rate=$rate violated rate + 0.1 ≤ 0.6")
        }
    }

    @Test
    fun `scaled float comparison`() {
        class S : VariableSchema() {
            val rate by floatVar(min = 0.0, max = 1.0, buckets = 21)

            val c by constraint { (2 * rate) ge 0.6 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(compiled.problem)
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 2)).take(50).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val rate = compiled.decode(schema.rate, s)
            assertTrue(rate >= 0.3 - 1e-9, "rate=$rate violated 2 * rate ≥ 0.6")
        }
    }

    @Test
    fun `negative coefficient flips comparison`() {
        class S : VariableSchema() {
            val rate by floatVar(min = 0.0, max = 1.0, buckets = 21)

            val c by constraint { -rate le -0.4 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(compiled.problem)
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 3)).take(50).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val rate = compiled.decode(schema.rate, s)
            assertTrue(rate >= 0.4 - 1e-9, "rate=$rate violated -rate ≤ -0.4")
        }
    }

    @Test
    fun `same handle expression vs expression`() {
        class S : VariableSchema() {
            val rate by floatVar(min = 0.0, max = 1.0, buckets = 21)

            val c by constraint { (2 * rate) ge (rate + 0.3) }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(compiled.problem)
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 4)).take(50).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val rate = compiled.decode(schema.rate, s)
            assertTrue(rate >= 0.3 - 1e-9, "rate=$rate violated 2*rate ≥ rate+0.3")
        }
    }

    @Test
    fun `threshold above max is rejected at compile time`() {
        class S : VariableSchema() {
            val rate by floatVar(min = 0.0, max = 1.0, buckets = 21)

            val c by constraint { rate ge 5.0 }
        }
        assertFails { S().compile() }
    }

    @Test
    fun `threshold above max is tautology at compile time`() {
        class S : VariableSchema() {
            val rate by floatVar(min = 0.0, max = 1.0, buckets = 21)

            val c by constraint { rate le 5.0 }
        }
        val compiled = S().compile()
        assertEquals(0, compiled.problem.factors.size, "tautological constraint should produce no factors")
    }

    @Test
    fun `cross handle sum compares across distinct floats`() {
        class S : VariableSchema() {
            val a by floatVar(min = 0.0, max = 1.0, buckets = 11)
            val b by floatVar(min = 0.0, max = 1.0, buckets = 11)
            val c by constraint { (a.toExpr() + b.toExpr()) le 1.0 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(compiled.problem)
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 5)).take(50).toList()
        assertTrue(samples.isNotEmpty())

        for (s in samples) {
            val av = compiled.decode(schema.a, s)
            val bv = compiled.decode(schema.b, s)
            assertTrue(av + bv <= 1.0 + 0.1 + 1e-9, "a=$av b=$bv violated a+b ≤ 1.0")
        }
    }

    @Test
    fun `cross handle subtraction enforces at least difference`() {
        class S : VariableSchema() {
            val a by floatVar(min = 0.0, max = 1.0, buckets = 11)
            val b by floatVar(min = 0.0, max = 1.0, buckets = 11)
            val c by constraint { (a.toExpr() - b.toExpr()) ge 0.5 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(compiled.problem)
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 6)).take(50).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val av = compiled.decode(schema.a, s)
            val bv = compiled.decode(schema.b, s)
            assertTrue(av - bv >= 0.5 - 0.1 - 1e-9, "a=$av b=$bv violated a-b ≥ 0.5")
        }
    }

    @Test
    fun `mixed handle scaling compares correctly`() {
        class S : VariableSchema() {

            val a by floatVar(min = 0.0, max = 2.0, buckets = 21)
            val b by floatVar(min = -1.0, max = 1.0, buckets = 11)
            val c by constraint { (2 * a + 3 * b.toExpr()) le 5.0 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(compiled.problem)
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 7)).take(50).toList()
        assertTrue(samples.isNotEmpty())

        for (s in samples) {
            val av = compiled.decode(schema.a, s)
            val bv = compiled.decode(schema.b, s)
            assertTrue(2 * av + 3 * bv <= 5.0 + 0.8 + 1e-9,
                "a=$av b=$bv violated 2a + 3b ≤ 5.0")
        }
    }

    @Test
    fun `default bucket count is applied`() {
        class S : VariableSchema() {
            val rate by floatVar(min = 0.0, max = 1.0)
            val c by constraint { rate ge 0.5 }
        }
        val compiled = S().compile()

        val rateDomain = compiled.problem.intDomains[0]
        assertEquals(0, rateDomain.min)
        assertEquals(1023, rateDomain.max)
    }
}
