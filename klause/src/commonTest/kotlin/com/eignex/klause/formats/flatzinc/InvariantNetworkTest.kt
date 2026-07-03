package com.eignex.klause.formats.flatzinc

import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Per-move one-way invariants (issue #153): after any applied move, the affected definitional
 * cone re-evaluates through the incremental apply path, and defined vars are excluded from
 * move generation at the sink.
 */
class InvariantNetworkTest {

    private val src = """
        var 0..10: x;
        var 0..10: y;
        var -10..10: dx;
        var -10..10: dy;
        var 0..10: a;
        var 0..10: b;
        var 0..10: m;
        var 0..20: s;
        constraint int_lin_eq([1, -1], [x, dx], 7) :: defines_var(dx);
        constraint int_lin_eq([1, -1], [y, dy], 2) :: defines_var(dy);
        constraint int_abs(dx, a) :: defines_var(a);
        constraint int_abs(dy, b) :: defines_var(b);
        constraint int_min(a, b, m) :: defines_var(m);
        constraint int_lin_eq([1, 1, -1], [a, b, s], 0) :: defines_var(s);
        constraint int_le(s, 6);
        solve satisfy;
    """.trimIndent()

    @Test
    fun `defined vars track their inputs across applied moves`() {
        val program = parseFlatZinc(src)
        val sweep = assertNotNull(program.definitionalSweep)
        val net = sweep.network(program.problem.numIntVars, program.problem.numBoolVars)
        val iv = program.intVarsByName
        val state = LocalSearchState(program.problem, Random(5))
        state.recompute()
        state.invariants = net
        state.apply(Move.IntSet(iv.getValue("x"), 10)) // dx = 3, a = 3
        state.apply(Move.IntSet(iv.getValue("y"), 0)) // dy = -2, b = 2, m = 2, s = 5
        assertEquals(3, state.assignment.intValue(iv.getValue("a")))
        assertEquals(2, state.assignment.intValue(iv.getValue("b")))
        assertEquals(2, state.assignment.intValue(iv.getValue("m")))
        assertEquals(5, state.assignment.intValue(iv.getValue("s")))
        // Move x again: the whole cone re-propagates from one move.
        state.apply(Move.IntSet(iv.getValue("x"), 7)) // dx = 0, a = 0, m = 0, s = 2
        assertEquals(0, state.assignment.intValue(iv.getValue("a")))
        assertEquals(0, state.assignment.intValue(iv.getValue("m")))
        assertEquals(2, state.assignment.intValue(iv.getValue("s")))
        // Incremental state stayed consistent with a from-scratch recompute.
        val incCost = state.cost
        state.recompute()
        assertEquals(state.cost, incCost, "propagated incremental cost must match recompute")
    }

    @Test
    fun `defined vars are excluded from move generation`() {
        val program = parseFlatZinc(src)
        val sweep = assertNotNull(program.definitionalSweep)
        val net = sweep.network(program.problem.numIntVars, program.problem.numBoolVars)
        val iv = program.intVarsByName
        assertTrue(net.isDefinedInt(iv.getValue("s")))
        assertFalse(net.isDefinedInt(iv.getValue("x")))
        val state = LocalSearchState(program.problem, Random(5))
        state.invariants = net
        val sink = state.moveSink
        sink.clear()
        sink.addIntSet(iv.getValue("s"), 3) // defined: dropped
        sink.addIntSet(iv.getValue("x"), 3) // free: kept
        assertEquals(1, sink.list.size, "defined-var move must be filtered at the sink")
        sink.clear()
        // Compound: the defined part drops; the lone survivor demotes to a primitive move.
        sink.addCompound(listOf(Move.IntSet(iv.getValue("s"), 3), Move.IntSet(iv.getValue("x"), 4)))
        val kept = sink.list.single() as Move.IntSet
        assertEquals(iv.getValue("x"), kept.varId)
    }
}
