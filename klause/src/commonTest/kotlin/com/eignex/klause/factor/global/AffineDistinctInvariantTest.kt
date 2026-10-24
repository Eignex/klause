package com.eignex.klause.factor.global

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.localsearch.movesource.SatisfiedStructured
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AffineDistinctInvariantTest {
    @Test
    fun `affine matching seeds should honor source pins with exact offsets`() {
        val wide = BigFraction.of(parseBigInt("1000000000000000000000000000001"), BIG_ONE)
        for (base in listOf(BigFraction.ZERO, wide)) {
            val factor = Distinct((0..2).map { i ->
                Distinct.Operand(intArrayOf(Term.ofIntVar(i)), listOf(BigFraction.ONE),
                    base + BigFraction.ofLong(i.toLong()))
            })
            val source = Problem(0, 3, Array(3) { IntDomain(0, 2) }, arrayOf(factor))
            val state = LocalSearchState(LocalSearchModel.open(source), Random(3), Assumptions.None.withInt(0, 1))
            state.assignment.setInt(0, 1)
            state.assignment.setInt(1, 0)
            state.assignment.setInt(2, 0)

            val seeded = state.factors[0].seedFeasible(state, 0)
            state.recompute()

            assertTrue(seeded)
            assertEquals(1L, state.assignment.intValue(0))
            assertEquals(0L, state.cost)
            for (i in 0..2) assertTrue(state.assignment.intValue(i) in state.rootDomains[i])
        }
    }

    @Test
    fun `affine matching seeds should reject coincident frozen values`() {
        val factor = Distinct((0..1).map { i ->
            Distinct.Operand(intArrayOf(Term.ofIntVar(i)), listOf(BigFraction.ONE),
                BigFraction.ofLong(i.toLong()))
        })
        val source = Problem(0, 2, Array(2) { IntDomain(0, 2) }, arrayOf(factor))
        val pins = Assumptions.None.withInt(0, 1).withInt(1, 0)
        val state = LocalSearchState(LocalSearchModel.open(source), Random(3), pins)
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 0)

        val seeded = state.factors[0].seedFeasible(state, 0)

        assertFalse(seeded)
        assertEquals(listOf(1L, 0L), (0..1).map { state.assignment.intValue(it) })
    }

    @Test
    fun `elected affine moves should preserve feasibility while allowing unused translated values`() {
        val wide = BigFraction.of(parseBigInt("1000000000000000000000000000001"), BIG_ONE)
        val factor = Distinct((0..1).map { i ->
            Distinct.Operand(intArrayOf(Term.ofIntVar(i)), listOf(BigFraction.ONE),
                wide + BigFraction.ofLong(i.toLong()))
        })
        val source = Problem(0, 2, Array(2) { IntDomain(-5, 5) }, arrayOf(factor))
        val state = LocalSearchState(LocalSearchModel.open(source), Random(3))
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 2)
        state.recompute()
        val moves = ArrayList<Move>()
        val structured = SatisfiedStructured.elected(1)

        repeat(8) {
            state.moveSink.clear()
            structured.generate(state, state.moveSink)
            moves.addAll(state.moveSink.list)
        }

        assertTrue(moves.any { it is Move.IntSet })
        assertTrue(moves.any { it is Move.Compound })
        for (move in moves) {
            assertEquals(0L, state.netDelta(move))
            state.apply(move)
            assertEquals(0L, state.cost)
            for (i in 0..1) assertTrue(state.assignment.intValue(i) in state.rootDomains[i])
            val sum = state.assignment.intValue(0) + state.assignment.intValue(1)
            if (move is Move.IntSet) assertTrue(sum != 3L) else assertEquals(3L, sum)
            state.assignment.setInt(0, 1)
            state.assignment.setInt(1, 2)
            state.recompute()
        }
    }

    @Test
    fun `aliased affine roles should decline implicit seeding and structured moves`() {
        val factor = Distinct((0..1).map { i ->
            Distinct.Operand(intArrayOf(Term.ofIntVar(0)), listOf(BigFraction.ONE),
                BigFraction.ofLong(i.toLong()))
        })
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)), arrayOf(factor))
        val state = LocalSearchState(LocalSearchModel.open(source), Random(3))
        state.assignment.setInt(0, 1)
        state.recompute()

        val seeded = state.factors[0].seedFeasible(state, 0)
        state.factors[0].proposeStructuredMoves(state, 0, state.moveSink)

        assertFalse(state.factors[0].providesImplicitNeighbourhood)
        assertFalse(seeded)
        assertTrue(state.moveSink.list.isEmpty())
        assertEquals(1L, state.assignment.intValue(0))
    }

    @Test
    fun `affine collision repairs should offer improving atomic swaps without implicit seeding`() {
        val factor = Distinct((0..3).map { i ->
            Distinct.Operand(intArrayOf(Term.ofIntVar(i)), listOf(BigFraction.ONE),
                BigFraction.ofLong(if (i == 1) -1 else 0))
        })
        val source = Problem(0, 4, Array(4) { IntDomain(0, 3) },
            arrayOf(AllDifferent(intArrayOf(0, 1, 2, 3)), factor))
        val state = LocalSearchState(LocalSearchModel.open(source), Random(3))
        for (i in 0..3) state.assignment.setInt(i, i.toLong())
        state.recompute()
        val sink = state.moveSink

        state.factors[1].proposeRepairMoves(state, 1, sink)

        val swaps = sink.list.filterIsInstance<Move.Compound>()
        assertTrue(swaps.any { state.netDelta(it) < 0 })
        for (move in swaps) {
            val delta = state.netDelta(move)
            state.apply(move)
            assertEquals(4, (0..3).map { state.assignment.intValue(it) }.toSet().size)
            assertEquals(1L + delta, state.cost)
            for (i in 0..3) state.assignment.setInt(i, i.toLong())
            state.recompute()
        }
    }

    @Test
    fun `owned affine collision repairs should preserve the seeded distinctness`() {
        val factor = Distinct((0..3).map { i ->
            Distinct.Operand(intArrayOf(Term.ofIntVar(i)), listOf(BigFraction.ONE),
                BigFraction.ofLong(if (i == 1) -1 else 0))
        })
        val source = Problem(0, 4, Array(4) { IntDomain(0, 3) },
            arrayOf(AllDifferent(intArrayOf(0, 1, 2, 3)), factor))
        val state = LocalSearchState(LocalSearchModel.open(source), Random(3))
        state.seedImplicitFeasible()
        state.recompute()
        val sink = state.moveSink
        sink.proposer = 1

        state.factors[1].proposeRepairMoves(state, 1, sink)

        assertTrue(sink.list.any { state.netDelta(it) < 0 })
        assertEquals(1, sink.proposer)
        for (move in sink.list) {
            val delta = state.netDelta(move)
            state.apply(move)
            assertEquals(4, (0..3).map { state.assignment.intValue(it) }.toSet().size)
            assertEquals(1L + delta, state.cost)
            state.seedImplicitFeasible()
            state.recompute()
        }
    }

    @Test
    fun `owned affine collision repairs should honor frozen coordinates`() {
        val factor = Distinct((0..1).map { i ->
            Distinct.Operand(intArrayOf(Term.ofIntVar(i)), listOf(BigFraction.ONE),
                BigFraction.ofLong(-i.toLong()))
        })
        val source = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            arrayOf(AllDifferent(intArrayOf(0, 1)), factor))
        val state = LocalSearchState(LocalSearchModel.open(source), Random(3), Assumptions.None.withInt(0, 0))
        state.seedImplicitFeasible()
        state.recompute()
        state.moveSink.proposer = 1

        state.factors[1].proposeRepairMoves(state, 1, state.moveSink)

        assertTrue(state.moveSink.list.isEmpty())
        assertEquals(1, state.moveSink.proposer)
    }

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
