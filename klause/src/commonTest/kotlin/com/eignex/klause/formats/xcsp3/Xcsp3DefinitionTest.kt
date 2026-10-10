package com.eignex.klause.formats.xcsp3

import com.eignex.klause.brute.BruteForceParams
import com.eignex.klause.brute.BruteForceSolver
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Xcsp3DefinitionTest {
    @Test
    fun `min and max chains retain aliases and exact source solutions`() {
        for (extreme in listOf("min", "max")) {
            val parsed = Xcsp3.parse(
                """
                <instance><variables>
                <var id="x">0..1</var><var id="y">0..1</var>
                <var id="r">0..2</var><var id="s">0..2</var>
                </variables><constraints>
                <intension>eq(r,$extreme(add(x,1),$extreme(y,1)))</intension>
                <intension>eq(s,r)</intension>
                </constraints></instance>
                """.trimIndent(),
            )
            val problem = parsed.problem
            val sweep = assertNotNull(DefinitionalSweep.infer(problem.factors, problem.numIntVars, parsed.definedVars))
            val state = LocalSearchState(problem.bake(), Random(0))
            state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
            assertTrue(state.invariants!!.isDefinedInt(2))
            assertTrue(state.invariants!!.isDefinedInt(3))
            val expected = (0L..1L).flatMap { x ->
                (0L..1L).map { y ->
                    val r = if (extreme == "min") minOf(x + 1, minOf(y, 1)) else maxOf(x + 1, maxOf(y, 1))
                    listOf(x, y, r, r)
                }
            }.toSet()
            val actual = BruteForceSolver(problem.bake()).enumerate(BruteForceParams(randomSeed = 0L))
                .map { it.ints.take(4) }.toSet()
            assertEquals(expected, actual)
            sweep.sweep(state.assignment, state.rootDomains)
            state.recompute()
            for (x in 0L..1L) {
                for (y in 0L..1L) {
                    state.apply(Move.Compound(listOf(Move.IntSet(0, x), Move.IntSet(1, y))))
                    val r = if (extreme == "min") minOf(x + 1, minOf(y, 1)) else maxOf(x + 1, maxOf(y, 1))
                    assertEquals(r, state.assignment.intValue(2))
                    assertEquals(r, state.assignment.intValue(3))
                    assertEquals(0L, state.cost)
                    val cost = state.cost
                    state.recompute()
                    assertEquals(cost, state.cost)
                }
            }
        }
    }

    @Test
    fun `clipped min and max outputs remain violated and repair searched inputs`() {
        for (extreme in listOf("min", "max")) {
            val parsed = Xcsp3.parse(
                """
                <instance><variables><var id="x">0..4</var><var id="y">0..4</var>
                <var id="r">1..3</var></variables><constraints>
                <intension>eq(r,$extreme(x,y))</intension>
                <intension>ne(r,x)</intension>
                </constraints></instance>
                """.trimIndent(),
            )
            val problem = parsed.problem
            val sweep = assertNotNull(DefinitionalSweep.infer(problem.factors, problem.numIntVars, parsed.definedVars))
            val fixed = if (extreme == "min") 3L else 1L
            val state = LocalSearchState(problem.bake(), Random(0), Assumptions(ints = mapOf(0 to fixed)))
            state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
            val initial = if (extreme == "min") 0L else 4L
            state.assignment.setInt(0, fixed)
            state.assignment.setInt(1, initial)
            sweep.sweep(state.assignment, state.rootDomains)
            state.recompute()
            assertEquals(if (extreme == "min") 1L else 3L, state.assignment.intValue(2))
            assertTrue(state.cost > 0)
            state.moveSink.addChannelingIntSet(state, 2, 2L)
            val repair = state.moveSink.list.single()
            state.apply(repair)
            assertEquals(2L, state.assignment.intValue(2))
            assertEquals(0L, state.cost)
            state.recompute()
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `pinned outputs survive sweep and per move updates`() {
        val parsed = Xcsp3.parse(
            """
            <instance><variables><var id="x">0..3</var><var id="y">0..3</var>
            <var id="r">0..3</var></variables><constraints>
            <intension>eq(r,min(x,y))</intension></constraints></instance>
            """.trimIndent(),
        )
        val problem = parsed.problem
        val sweep = assertNotNull(DefinitionalSweep.infer(problem.factors, problem.numIntVars, parsed.definedVars))
        val pins = Assumptions(ints = mapOf(2 to 2L, 0 to 3L))
        val state = LocalSearchState(problem.bake(), Random(0), pins)
        state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
        state.assignment.setInt(0, 3L)
        state.assignment.setInt(1, 0L)
        state.assignment.setInt(2, 2L)
        sweep.sweepPinned(state.assignment, state.rootDomains, problem.factors, pins)
        state.recompute()
        assertEquals(2L, state.assignment.intValue(2))
        state.apply(Move.IntSet(1, 1L))
        assertEquals(2L, state.assignment.intValue(2))
        assertTrue(state.cost > 0L)
        state.moveSink.addChannelingIntSet(state, 2, 2L)
        state.apply(state.moveSink.list.single())
        assertEquals(3L, state.assignment.intValue(0))
        assertEquals(2L, state.assignment.intValue(1))
        assertEquals(0L, state.cost)
    }

    @Test
    fun `independent output constraints repair through maintained extrema`() {
        for (extreme in listOf("min", "max")) {
            val parsed = Xcsp3.parse(
                """
                <instance><variables><var id="x">0..4</var><var id="y">0..4</var>
                <var id="r">1..3</var></variables><constraints>
                <intension>eq(r,$extreme(x,y))</intension>
                <intension>ne(r,x)</intension></constraints></instance>
                """.trimIndent(),
            )
            val problem = parsed.problem
            val sweep = assertNotNull(DefinitionalSweep.infer(problem.factors, problem.numIntVars, parsed.definedVars))
            val fixed = if (extreme == "min") 3L else 1L
            val pins = Assumptions(ints = mapOf(0 to fixed))
            val state = LocalSearchState(problem.bake(), Random(0), pins)
            state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
            state.assignment.setInt(0, fixed)
            state.assignment.setInt(1, fixed)
            sweep.sweep(state.assignment, state.rootDomains)
            state.recompute()
            assertTrue(state.cost > 0L)

            val factorId = problem.factors.lastIndex
            state.factors[factorId].proposeRepairMoves(state, factorId, state.moveSink)
            state.apply(state.moveSink.list.single())

            assertEquals(fixed, state.assignment.intValue(0))
            assertEquals(2L, state.assignment.intValue(2))
            assertEquals(0L, state.cost)
            state.recompute()
            assertEquals(0L, state.cost)
        }
    }
}
