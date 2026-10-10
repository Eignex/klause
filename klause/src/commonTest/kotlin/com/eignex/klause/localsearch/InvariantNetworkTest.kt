package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.pipeline.parseFlatZincExecution
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
    @Test
    fun `definition propagation preserves implicitly owned outputs`() {
        val problem = Problem(
            0, 3, arrayOf(IntDomain(0, 1), IntDomain(3, 3), IntDomain(0, 3)),
            arrayOf<Factor>(Product(0, 1, 2)),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.invariants = assertNotNull(DefinitionalSweep.infer(problem.factors, 3)).network(3, 0)
        state.moveSink.setOwners(intArrayOf(-1, -1, 7))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 3)
        state.assignment.setInt(2, 0)
        state.recompute()

        val predicted = state.netDelta(Move.IntSet(0, 1))
        state.apply(Move.IntSet(0, 1))

        assertEquals(0L, state.assignment.intValue(2))
        assertTrue(state.cost > 0L)
        assertEquals(state.cost, predicted)
        val cost = state.cost
        state.recompute()
        assertEquals(cost, state.cost)
    }

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
    fun `states sharing a definition network propagate their own assignments`() {
        val execution = parseFlatZincExecution(src)
        val program = execution.program
        val network = assertNotNull(execution.definitionalSweep)
            .network(program.problem.numIntVars, program.problem.numBoolVars)
        val problem = program.problem.bake()
        val first = LocalSearchState(problem, Random(5))
        val second = LocalSearchState(problem, Random(7))
        val iv = program.intVarsByName
        for (state in listOf(first, second)) {
            state.recompute()
            state.invariants = network
            state.apply(Move.IntSet(iv.getValue("x"), 7))
            state.apply(Move.IntSet(iv.getValue("y"), 0))
        }

        first.apply(Move.IntSet(iv.getValue("x"), 10))
        second.apply(Move.IntSet(iv.getValue("y"), 2))

        assertEquals(5, first.assignment.intValue(iv.getValue("s")))
        assertEquals(0, second.assignment.intValue(iv.getValue("s")))
    }

    @Test
    fun `defined vars track their inputs across applied moves`() {
        val execution = parseFlatZincExecution(src)
        val program = execution.program
        val sweep = assertNotNull(execution.definitionalSweep)
        val net = sweep.network(program.problem.numIntVars, program.problem.numBoolVars)
        val iv = program.intVarsByName
        val state = LocalSearchState(program.problem.bake(), Random(5))
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
        val execution = parseFlatZincExecution(src)
        val program = execution.program
        val sweep = assertNotNull(execution.definitionalSweep)
        val net = sweep.network(program.problem.numIntVars, program.problem.numBoolVars)
        val iv = program.intVarsByName
        assertTrue(net.isDefinedInt(iv.getValue("s")))
        assertFalse(net.isDefinedInt(iv.getValue("x")))
        val state = LocalSearchState(program.problem.bake(), Random(5))
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
