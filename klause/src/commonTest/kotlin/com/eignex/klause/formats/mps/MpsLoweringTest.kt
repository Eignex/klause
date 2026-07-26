package com.eignex.klause.formats.mps

import com.eignex.klause.formats.ObjectiveSense
import com.eignex.klause.solver.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MpsLoweringTest {

    private val noObjective = MpsObjective("", IntArray(0), DoubleArray(0), 0.0)

    private fun model(vararg vars: MpsVar) =
        MpsModel("m", ObjectiveSense.MINIMIZE, noObjective, vars.toList(), emptyList())

    @Test
    fun `keeps an unbounded float column as an open LP-only continuous variable`() {
        val compiled = model(MpsVar("x", integer = false, lower = 0.0, upper = null)).toProblem()
        assertEquals(1, compiled.floatColumns)
        assertEquals(1, compiled.problem.numRealVars)
        assertEquals(0.0, compiled.problem.realLower[0])
        assertTrue(compiled.problem.realUpper[0].isInfinite())
    }

    @Test
    fun `lowers a bounded float column to an LP-only continuous variable with its real bounds`() {
        val compiled = model(MpsVar("x", integer = false, lower = 0.0, upper = 10.0)).toProblem()
        assertEquals(1, compiled.floatColumns)
        assertEquals(1, compiled.problem.numRealVars)
        assertEquals(0, compiled.problem.numIntVars)
        assertEquals(0.0, compiled.problem.realLower[0])
        assertEquals(10.0, compiled.problem.realUpper[0])
    }

    @Test
    fun `keeps an integer column as its declared domain`() {
        val compiled = model(MpsVar("x", integer = true, lower = -3.0, upper = 7.0)).toProblem()
        assertEquals(0, compiled.floatColumns)
        assertEquals(-3L, compiled.problem.intDomains[0].min)
        assertEquals(7L, compiled.problem.intDomains[0].max)
    }

    @Test
    fun `closes an unconstrained unbounded integer column under the small-model box`() {
        // No rows and no objective: any single value is a witness, so the small-model box is
        // equisatisfiable and the model is not flagged clamped.
        val compiled = model(MpsVar("x", integer = true, lower = null, upper = null)).toProblem(searchBound = 1000L)
        assertFalse(compiled.clamped)

        // Under an objective the box could truncate an unbounded optimum, so the lossy search
        // window applies and the model is flagged.
        val minimized = model(MpsVar("x", integer = true, lower = null, upper = null))
            .copy(objective = MpsObjective("obj", intArrayOf(0), doubleArrayOf(1.0), 0.0))
            .toProblem(searchBound = 1000L)
        assertTrue(minimized.clamped)
        assertEquals(-1000L, minimized.problem.intDomains[0].min)
        assertEquals(1000L, minimized.problem.intDomains[0].max)
    }

    @Test
    fun `OBBT bounds an unbounded integer side a constraint bounds instead of clamping`() {
        val row = MpsConstraint("C1", intArrayOf(0), doubleArrayOf(1.0), lower = null, upper = 5.0)
        val m = MpsModel(
            "m",
            ObjectiveSense.MINIMIZE,
            noObjective,
            listOf(MpsVar("x", integer = true, lower = 0.0, upper = null)),
            listOf(row),
        )
        val d = m.toProblem(searchBound = 1_000_000L).let {
            assertFalse(it.clamped)
            it.problem.intDomains[0]
        }
        // x >= 0, x <= 5: exact-certified OBBT tightens the open upper side to exactly 5, no clamp.
        assertEquals(0L, d.min)
        assertEquals(5L, d.max)
    }

    @Test
    fun `a tripped load deadline skips OBBT and clamps the open side instead of tightening`() {
        // The huge bound magnitude keeps the small-model box from applying, so the lossy window shows.
        val row = MpsConstraint("C1", intArrayOf(0), doubleArrayOf(1.0), lower = null, upper = 5.0e12)
        val m = MpsModel(
            "m",
            ObjectiveSense.MINIMIZE,
            noObjective,
            listOf(MpsVar("x", integer = true, lower = 0.0, upper = null)),
            listOf(row),
        )
        // With the load deadline already tripped, OBBT runs no LP solves, so the open upper side is clamped
        // to the search bound rather than tightened — this is what bounds load on a large model.
        val compiled = m.toProblem(searchBound = 1_000L, cancellation = Cancellation { true })
        assertTrue(compiled.clamped)
        assertEquals(1_000L, compiled.problem.intDomains[0].max)
    }

    @Test
    fun `OBBT bounds a doubly-unbounded integer column from a two-sided constraint`() {
        val row = MpsConstraint("C1", intArrayOf(0), doubleArrayOf(1.0), lower = -4.0, upper = 8.0)
        val m = MpsModel(
            "m",
            ObjectiveSense.MINIMIZE,
            noObjective,
            listOf(MpsVar("x", integer = true, lower = null, upper = null)),
            listOf(row),
        )
        val d = m.toProblem(searchBound = 1_000_000L).let {
            assertFalse(it.clamped)
            it.problem.intDomains[0]
        }
        assertEquals(-4L, d.min)
        assertEquals(8L, d.max)
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
