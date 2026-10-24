package com.eignex.klause.factor.global

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AffineDistinctInvariantTest {
    @Test
    fun `incremental affine deltas should agree with exact grading across aliases and wide offsets`() {
        val wide = BigFraction.of(parseBigInt("1000000000000000000000000000001"), BIG_ONE)
        for (base in listOf(BigFraction.ZERO, wide)) {
            val vars = intArrayOf(0, 0, 1, 2)
            val offsets = longArrayOf(0, 1, 0, -1)
            val factor = Distinct(vars.indices.map { i ->
                Distinct.Operand(intArrayOf(Term.ofIntVar(vars[i])), listOf(BigFraction.ONE),
                    base + BigFraction.ofLong(offsets[i]))
            })
            val source = Problem(0, 3, Array(3) { IntDomain(-2, 2) }, arrayOf(factor))
            val state = LocalSearchState(LocalSearchModel.open(source), Random(3))
            state.assignment.setInt(0, 0)
            state.assignment.setInt(1, 1)
            state.assignment.setInt(2, 0)
            state.recompute()
            for (variable in 0..2) {
                for (value in -2L..2L) {
                    val move = Move.IntSet(variable, value)
                    val before = state.cost
                    val delta = state.netDelta(move)
                    assertEquals(delta, state.netDelta(move))
                    state.apply(move)
                    val exact = factor.collisions(
                        { bigIntOf(state.assignment.intValue(it)) }, { error("no Boolean operand") },
                        { error("no real operand") },
                    ).toLong()
                    assertEquals(exact, state.cost)
                    assertEquals(exact - before, delta)
                }
            }
        }
    }

    @Test
    fun `compound probes should restore affine collision counts before applying repeated coordinates`() {
        val factor = Distinct((0..2).map { i ->
            Distinct.Operand(intArrayOf(Term.ofIntVar(i)), listOf(BigFraction.ONE), BigFraction.ofLong(i.toLong()))
        })
        val source = Problem(0, 3, Array(3) { IntDomain(-2, 2) }, arrayOf(factor))
        val state = LocalSearchState(LocalSearchModel.open(source), Random(3))
        for (i in 0..2) state.assignment.setInt(i, -i.toLong())
        state.recompute()
        val move = Move.Compound(listOf(Move.IntSet(0, 2), Move.IntSet(1, -2), Move.IntSet(0, 1)))
        val before = state.cost

        val delta = state.netDelta(move)
        assertEquals(delta, state.netDelta(move))
        assertEquals(before, state.cost)
        assertEquals(listOf(0L, -1L, -2L), (0..2).map { state.assignment.intValue(it) })
        state.apply(move)

        assertEquals(before + delta, state.cost)
        assertEquals(0L, state.cost)
    }

    @Test
    fun `affine collision repairs should propose improving source moves beyond signed word offsets`() {
        val offset = BigFraction.of(parseBigInt("9223372036854775808"), BIG_ONE)
        val factor = Distinct((0..1).map { i ->
            Distinct.Operand(intArrayOf(Term.ofIntVar(i)), listOf(BigFraction.ONE), offset)
        })
        val source = Problem(0, 2, Array(2) { IntDomain(0, 1) }, arrayOf(factor))
        val state = LocalSearchState(LocalSearchModel.open(source), Random(3))
        for (i in 0..1) state.assignment.setInt(i, 0)
        state.recompute()
        val sink = MoveSink()

        state.factors[0].proposeRepairMoves(state, 0, sink)

        assertTrue(sink.list.isNotEmpty())
        for (move in sink.list) assertEquals(-1L, state.netDelta(move))
    }
}
