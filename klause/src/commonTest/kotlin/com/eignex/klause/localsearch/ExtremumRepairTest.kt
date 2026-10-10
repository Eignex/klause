package com.eignex.klause.localsearch

import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExtremumRepairTest {
    @Test
    fun `alias repairs backsolve affine min and max operands`() {
        for (extreme in listOf("min", "max")) {
            val parsed = Xcsp3.parse(
                """
                <instance><variables><var id="x">0..2</var><var id="y">0..3</var>
                <var id="r">0..3</var><var id="s">0..3</var></variables><constraints>
                <intension>eq(r,$extreme(add(x,1),y))</intension>
                <intension>eq(s,r)</intension></constraints></instance>
                """.trimIndent(),
            )
            val problem = parsed.problem
            val sweep = assertNotNull(DefinitionalSweep.infer(problem.factors, problem.numIntVars, parsed.definedVars))
            val state = LocalSearchState(problem.bake(), Random(0))
            state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
            state.assignment.setInt(0, 0L)
            state.assignment.setInt(1, if (extreme == "min") 3L else 0L)
            sweep.sweep(state.assignment, state.rootDomains)
            state.recompute()

            state.moveSink.addChannelingIntSet(state, 3, 2L)
            state.apply(state.moveSink.list.single())

            assertEquals(2L, state.assignment.intValue(2))
            assertEquals(2L, state.assignment.intValue(3))
            assertEquals(0L, state.cost)
            state.recompute()
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `inverse repairs respect input domains pins and implicit owners`() {
        for (restriction in listOf("domain", "pin", "owner")) {
            val domain = if (restriction == "domain") "0 2" else "0..2"
            val parsed = Xcsp3.parse(
                """
                <instance><variables><var id="x">$domain</var><var id="y">3</var>
                <var id="r">0..3</var></variables><constraints>
                <intension>eq(r,min(add(x,1),y))</intension></constraints></instance>
                """.trimIndent(),
            )
            val problem = parsed.problem
            val sweep = assertNotNull(DefinitionalSweep.infer(problem.factors, problem.numIntVars, parsed.definedVars))
            val pins = if (restriction == "pin") Assumptions(ints = mapOf(0 to 0L)) else Assumptions.None
            val state = LocalSearchState(problem.bake(), Random(0), pins)
            state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
            state.assignment.setInt(0, 0L)
            state.assignment.setInt(1, 3L)
            sweep.sweep(state.assignment, state.rootDomains)
            state.recompute()
            if (restriction == "owner") {
                state.moveSink.setOwners(IntArray(problem.numIntVars) { if (it == 0) 42 else -1 })
            }

            state.moveSink.addChannelingIntSet(state, 2, 2L)

            assertTrue(state.moveSink.list.isEmpty())
            assertEquals(0L, state.assignment.intValue(0))
            assertEquals(1L, state.assignment.intValue(2))
        }
    }

    @Test
    fun `shared input collisions cannot publish an unreachable extremum repair`() {
        val parsed = Xcsp3.parse(
            """
            <instance><variables><var id="x">-2..2</var><var id="r">-2..2</var></variables>
            <constraints><intension>eq(r,min(x,neg(x)))</intension></constraints></instance>
            """.trimIndent(),
        )
        val problem = parsed.problem
        val sweep = assertNotNull(DefinitionalSweep.infer(problem.factors, problem.numIntVars, parsed.definedVars))
        val state = LocalSearchState(problem.bake(), Random(0))
        state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
        state.assignment.setInt(0, 2L)
        sweep.sweep(state.assignment, state.rootDomains)
        state.recompute()

        state.moveSink.addChannelingIntSet(state, 1, 1L)

        assertTrue(state.moveSink.list.isEmpty())
        assertEquals(-2L, state.assignment.intValue(1))
        state.moveSink.addChannelingIntSet(state, 1, 0L)
        state.apply(state.moveSink.list.single())
        assertEquals(0L, state.assignment.intValue(1))
        assertEquals(0L, state.cost)
    }

    @Test
    fun `constant witnesses permit inverse repairs across input domain gaps`() {
        for (max in listOf(false, true)) {
            val domain = if (max) "-3 0" else "0 3"
            val target = if (max) -2L else 2L
            val extreme = if (max) "max" else "min"
            val parsed = Xcsp3.parse(
                """
                <instance><variables><var id="x">$domain</var><var id="r">-3..3</var></variables>
                <constraints><intension>eq(r,$extreme(x,$target))</intension></constraints></instance>
                """.trimIndent(),
            )
            val problem = parsed.problem
            val sweep = assertNotNull(DefinitionalSweep.infer(problem.factors, problem.numIntVars, parsed.definedVars))
            val state = LocalSearchState(problem.bake(), Random(0))
            state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
            state.assignment.setInt(0, 0L)
            sweep.sweep(state.assignment, state.rootDomains)
            state.recompute()

            state.moveSink.addChannelingIntSet(state, 1, target)
            state.apply(state.moveSink.list.single())

            assertEquals(if (max) -3L else 3L, state.assignment.intValue(0))
            assertEquals(target, state.assignment.intValue(1))
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `inverse bound repairs round affine operands toward a constant witness`() {
        for ((max, coefficient) in listOf(false to 2, true to 2, false to -2, true to -2)) {
            val negativeInput = max == (coefficient > 0)
            val domain = if (negativeInput) "-2..0" else "0..2"
            val target = if (max) -3L else 3L
            val extreme = if (max) "max" else "min"
            val parsed = Xcsp3.parse(
                """
                <instance><variables><var id="x">$domain</var><var id="r">-4..4</var></variables>
                <constraints><intension>eq(r,$extreme(mul($coefficient,x),$target))</intension></constraints></instance>
                """.trimIndent(),
            )
            val problem = parsed.problem
            val sweep = assertNotNull(DefinitionalSweep.infer(problem.factors, problem.numIntVars, parsed.definedVars))
            val state = LocalSearchState(problem.bake(), Random(0))
            state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
            state.assignment.setInt(0, 0L)
            sweep.sweep(state.assignment, state.rootDomains)
            state.recompute()

            state.moveSink.addChannelingIntSet(state, 1, target)
            state.apply(state.moveSink.list.single())

            assertEquals(if (negativeInput) -2L else 2L, state.assignment.intValue(0))
            assertEquals(target, state.assignment.intValue(1))
            assertEquals(0L, state.cost)
            state.recompute()
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `inverse validation recomputes shared product inputs`() {
        val parsed = Xcsp3.parse(
            """
            <instance><variables><var id="x">-2..2</var><var id="y">-2</var><var id="r">-4..4</var></variables>
            <constraints><intension>eq(r,max(mul(x,y),add(x,1)))</intension></constraints></instance>
            """.trimIndent(),
        )
        val problem = parsed.problem
        val sweep = assertNotNull(DefinitionalSweep.infer(problem.factors, problem.numIntVars, parsed.definedVars))
        val state = LocalSearchState(problem.bake(), Random(0))
        state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
        state.assignment.setInt(0, 0L)
        state.assignment.setInt(1, -2L)
        sweep.sweep(state.assignment, state.rootDomains)
        state.recompute()

        state.moveSink.addChannelingIntSet(state, 2, 0L)

        assertTrue(state.moveSink.list.isEmpty())
        assertEquals(0L, state.assignment.intValue(0))
        assertEquals(1L, state.assignment.intValue(2))
        assertEquals(0L, state.cost)
    }
}
