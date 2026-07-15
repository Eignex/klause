package com.eignex.klause.formats.mps

import com.eignex.klause.formats.ObjectiveSense
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MpsLoweringTest {

    private val noObjective = MpsObjective("", IntArray(0), DoubleArray(0), 0.0)

    private fun model(vararg vars: MpsVar) =
        MpsModel("m", ObjectiveSense.MINIMIZE, noObjective, vars.toList(), emptyList())

    @Test
    fun `rejects an unbounded float column`() {
        val m = model(MpsVar("x", integer = false, lower = 0.0, upper = null))
        assertFailsWith<MpsFormatException> { m.toProblem() }
    }

    @Test
    fun `buckets a bounded float column into the configured buckets`() {
        val compiled = model(MpsVar("x", integer = false, lower = 0.0, upper = 10.0)).toProblem(floatBuckets = 100)
        assertEquals(1, compiled.floatColumns)
        assertEquals(0L, compiled.problem.intDomains[0].min)
        assertEquals(99L, compiled.problem.intDomains[0].max) // buckets - 1
        assertNotNull(compiled.floatBucketings[0])
    }

    @Test
    fun `keeps an integer column as its declared domain`() {
        val compiled = model(MpsVar("x", integer = true, lower = -3.0, upper = 7.0)).toProblem()
        assertEquals(0, compiled.floatColumns)
        assertEquals(-3L, compiled.problem.intDomains[0].min)
        assertEquals(7L, compiled.problem.intDomains[0].max)
    }

    @Test
    fun `clamps an unbounded integer column to the search range`() {
        val compiled = model(MpsVar("x", integer = true, lower = null, upper = null)).toProblem(searchBound = 1000L)
        assertTrue(compiled.clamped)
        assertEquals(-1000L, compiled.problem.intDomains[0].min)
        assertEquals(1000L, compiled.problem.intDomains[0].max)
    }

    @Test
    fun `drops a term-free constraint row instead of emitting an empty sum`() {
        val emptyRow = MpsConstraint("ZBESTROW", IntArray(0), DoubleArray(0), lower = null, upper = 0.0)
        val realRow = MpsConstraint("C1", intArrayOf(0), doubleArrayOf(1.0), lower = null, upper = 5.0)
        val m = MpsModel(
            "m",
            ObjectiveSense.MINIMIZE,
            noObjective,
            listOf(MpsVar("x", integer = true, lower = 0.0, upper = 10.0)),
            listOf(emptyRow, realRow),
        )
        // The `0 <= 0` placeholder row is redundant and dropped; only the real row lowers to a factor.
        assertEquals(1, m.toProblem().problem.factors.size)
    }

    @Test
    fun `rejects a term-free row whose bound is infeasible`() {
        val badRow = MpsConstraint("BAD", IntArray(0), DoubleArray(0), lower = 5.0, upper = null)
        val m = MpsModel(
            "m",
            ObjectiveSense.MINIMIZE,
            noObjective,
            listOf(MpsVar("x", integer = true, lower = 0.0, upper = 10.0)),
            listOf(badRow),
        )
        assertFailsWith<MpsFormatException> { m.toProblem() }
    }
}
