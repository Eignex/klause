package com.eignex.klause.solver.factor.bool

import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.Move.BoolFlip
import com.eignex.klause.solver.Move.IntSet
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.arithmetic.LinearOp
import com.eignex.klause.solver.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.solver.factor.arithmetic.ReifiedLinear
import com.eignex.klause.solver.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.solver.factor.bool.Cardinality
import com.eignex.klause.solver.factor.bool.PseudoBoolean
import com.eignex.klause.solver.factor.bool.Xor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CardinalityInvariantTest {

    @Test
    fun `at most violated proposes only true literal flips`() {
        val a = 0
        val b = 1
        val c = 2
        val d = 3
        val factor = Cardinality(
            literals = intArrayOf(Lit.make(a, true), Lit.make(b, true), Lit.make(c, true), Lit.make(d, true)),
            min = 0,
            max = 2,
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        for (v in intArrayOf(a, b, c, d)) state.assignment.setBool(v, true)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))

        val sink = MoveSink()
        state.factors[0].proposeRepairMoves(state, 0, sink)
        val proposed = sink.list.filterIsInstance<Move.BoolFlip>().map { it.varId }.toSet()

        assertEquals(setOf(a, b, c, d), proposed)
    }

    @Test
    fun `at least violated proposes only false literal flips`() {
        val a = 0
        val b = 1
        val c = 2
        val factor = Cardinality(
            literals = intArrayOf(Lit.make(a, true), Lit.make(b, true), Lit.make(c, true)),
            min = 2,
            max = 3,
        )
        val problem = Problem(3, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setBool(a, true)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))

        val sink = MoveSink()
        state.factors[0].proposeRepairMoves(state, 0, sink)
        val proposed = sink.list.filterIsInstance<Move.BoolFlip>().map { it.varId }.toSet()

        assertEquals(setOf(b, c), proposed)
    }

    @Test
    fun `mixed polarity counts correctly`() {
        val a = 0
        val b = 1
        val factor = Cardinality(
            literals = intArrayOf(Lit.make(a, true), Lit.make(b, false)),
            min = 2,
            max = 2,
        )
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setBool(a, true)
        state.assignment.setBool(b, true)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))

        val sink = MoveSink()
        state.factors[0].proposeRepairMoves(state, 0, sink)
        val proposed = sink.list.filterIsInstance<Move.BoolFlip>().map { it.varId }.toSet()
        assertEquals(setOf(b), proposed)
    }

    @Test
    fun `satisfied cardinality proposes nothing`() {
        val a = 0
        val b = 1
        val factor = Cardinality(
            literals = intArrayOf(Lit.make(a, true), Lit.make(b, true)),
            min = 1,
            max = 2,
        )
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setBool(a, true)
        state.recompute()
        assertTrue(!state.factors[0].isViolated(state, 0))
        val sink = MoveSink()
        state.factors[0].proposeRepairMoves(state, 0, sink)
        assertTrue(sink.list.isEmpty())
    }

    @Test
    fun `at most one violated with two true`() {
        val amo = Cardinality.atMostOne(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)))
        val problem = Problem(3, 0, emptyArray(), listOf(amo))
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setBool(0, true)
        state.assignment.setBool(1, true)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
        assertEquals(2L, state.longPayload[0])
    }

    @Test
    fun `exactly one transitions`() {
        val one = Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)))
        val problem = Problem(3, 0, emptyArray(), listOf(one))
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setBool(0, true)
        state.recompute()
        assertFalse(state.factors[0].isViolated(state, 0))
        val deltaPredicted = state.factors[0].deltaIfBoolFlipped(state, 0, 1)
        assertEquals(1, deltaPredicted)
        state.apply(Move.BoolFlip(1))
        assertTrue(state.factors[0].isViolated(state, 0))
        assertEquals(1, state.cost)
    }

    // IncrementalBreakMakeTest tests below

    private fun assertConsistent(problem: Problem, seed: Long = 0L) {
        val state = LocalSearchState(problem, Random(seed))
        for (v in 0 until problem.numBoolVars) state.assignment.setBool(v, v and 1 == 0)
        state.recompute()
        for (v in 0 until problem.numBoolVars) {
            state.apply(BoolFlip(v))
            val incBreak = state.boolBreakCountSnapshot()
            val incMake = state.boolMakeCountSnapshot()
            state.recompute()
            assertEquals(
                incBreak.toList(),
                state.boolBreakCountSnapshot().toList(),
                "boolBreakCount mismatch after flipping var=$v",
            )
            assertEquals(
                incMake.toList(),
                state.boolMakeCountSnapshot().toList(),
                "boolMakeCount mismatch after flipping var=$v",
            )
        }
    }

    @Test
    fun `cardinality at most one stays consistent across flips`() {
        val lits = IntArray(5) { Lit.make(it, positive = true) }
        val factor = Cardinality.atMostOne(lits)
        assertConsistent(Problem(5, 0, emptyArray(), listOf(factor)))
    }

    @Test
    fun `cardinality exactly one stays consistent`() {
        val lits = IntArray(4) { Lit.make(it, positive = true) }
        val factor = Cardinality.exactlyOne(lits)
        assertConsistent(Problem(4, 0, emptyArray(), listOf(factor)))
    }

    @Test
    fun `cardinality bounded range with mixed polarity`() {
        val lits = intArrayOf(
            Lit.make(0, true),
            Lit.make(1, false),
            Lit.make(2, true),
            Lit.make(3, false),
            Lit.make(4, true),
            Lit.make(5, true),
        )
        val factor = Cardinality(lits, min = 2, max = 4)
        assertConsistent(Problem(6, 0, emptyArray(), listOf(factor)))
    }

    @Test
    fun `cardinality with repeated vars and cancelling polarities`() {
        // var 0 appears twice positive (signed = +2); var 1 once pos + once neg (signed = 0).
        val lits = intArrayOf(
            Lit.make(0, true),
            Lit.make(0, true),
            Lit.make(1, true),
            Lit.make(1, false),
            Lit.make(2, true),
            Lit.make(3, false),
        )
        val factor = Cardinality(lits, min = 1, max = 3)
        assertConsistent(Problem(4, 0, emptyArray(), listOf(factor)))
    }

    @Test
    fun `xor odd target stays consistent`() {
        val lits = IntArray(5) { Lit.make(it, positive = true) }
        val factor = Xor(lits, targetParity = 1)
        assertConsistent(Problem(5, 0, emptyArray(), listOf(factor)))
    }

    @Test
    fun `xor even target stays consistent`() {
        val lits = intArrayOf(
            Lit.make(0, true),
            Lit.make(1, false),
            Lit.make(2, true),
            Lit.make(3, false),
        )
        val factor = Xor(lits, targetParity = 0)
        assertConsistent(Problem(4, 0, emptyArray(), listOf(factor)))
    }

    @Test
    fun `pseudo boolean LE stays consistent`() {
        val factor = PseudoBoolean(
            weights = intArrayOf(3, 2, 1, 5, 4),
            literals = IntArray(5) { Lit.make(it, positive = true) },
            op = PbOp.LE,
            bound = 7,
        )
        assertConsistent(Problem(5, 0, emptyArray(), listOf(factor)))
    }

    @Test
    fun `pseudo boolean GE with negative literals stays consistent`() {
        val factor = PseudoBoolean(
            weights = intArrayOf(2, 4, 3, 1),
            literals = intArrayOf(
                Lit.make(0, true),
                Lit.make(1, false),
                Lit.make(2, true),
                Lit.make(3, false),
            ),
            op = PbOp.GE,
            bound = 5,
        )
        assertConsistent(Problem(4, 0, emptyArray(), listOf(factor)))
    }

    @Test
    fun `pseudo boolean EQ stays consistent`() {
        val factor = PseudoBoolean(
            weights = intArrayOf(1, 1, 1, 1, 1),
            literals = IntArray(5) { Lit.make(it, positive = true) },
            op = PbOp.EQ,
            bound = 3,
        )
        assertConsistent(Problem(5, 0, emptyArray(), listOf(factor)))
    }

    @Test
    fun `pseudo boolean with negative weights stays consistent`() {
        val factor = PseudoBoolean(
            weights = intArrayOf(2, -3, 4, -1),
            literals = IntArray(4) { Lit.make(it, positive = true) },
            op = PbOp.LE,
            bound = 1,
        )
        assertConsistent(Problem(4, 0, emptyArray(), listOf(factor)))
    }

    @Test
    fun `reified cardinality body satisfied stays consistent`() {
        // aux is var 5; literals over vars 0..4.
        val factor = ReifiedCardinality(
            auxBoolVar = 5,
            literals = IntArray(5) { Lit.make(it, positive = true) },
            min = 2,
            max = 3,
        )
        assertConsistent(Problem(6, 0, emptyArray(), listOf(factor)))
    }

    @Test
    fun `reified cardinality with mixed polarity stays consistent`() {
        val factor = ReifiedCardinality(
            auxBoolVar = 4,
            literals = intArrayOf(
                Lit.make(0, true),
                Lit.make(1, false),
                Lit.make(2, true),
                Lit.make(3, false),
            ),
            min = 1,
            max = 2,
        )
        assertConsistent(Problem(5, 0, emptyArray(), listOf(factor)))
    }

    @Test
    fun `reified pseudo boolean LE stays consistent`() {
        val factor = ReifiedPseudoBoolean(
            auxBoolVar = 5,
            weights = intArrayOf(2, 3, 1, 4, 2),
            literals = IntArray(5) { Lit.make(it, positive = true) },
            op = PbOp.LE,
            bound = 6,
        )
        assertConsistent(Problem(6, 0, emptyArray(), listOf(factor)))
    }

    @Test
    fun `reified pseudo boolean GE with negative literals stays consistent`() {
        val factor = ReifiedPseudoBoolean(
            auxBoolVar = 4,
            weights = intArrayOf(3, 2, 4, 1),
            literals = intArrayOf(
                Lit.make(0, true),
                Lit.make(1, false),
                Lit.make(2, true),
                Lit.make(3, false),
            ),
            op = PbOp.GE,
            bound = 5,
        )
        assertConsistent(Problem(5, 0, emptyArray(), listOf(factor)))
    }

    @Test
    fun `reified pseudo boolean EQ stays consistent`() {
        val factor = ReifiedPseudoBoolean(
            auxBoolVar = 4,
            weights = intArrayOf(1, 1, 1, 1),
            literals = IntArray(4) { Lit.make(it, positive = true) },
            op = PbOp.EQ,
            bound = 2,
        )
        assertConsistent(Problem(5, 0, emptyArray(), listOf(factor)))
    }

    @Test
    fun `reified linear LE stays consistent across int sets`() {
        val factor = ReifiedLinear(
            auxBoolVar = 0,
            coeffs = intArrayOf(2, 3, 1),
            vars = intArrayOf(0, 1, 2),
            op = LinearOp.LE,
            bound = 10,
        )
        val intDomains = Array(3) { IntDomain(0, 5) }
        val problem = Problem(1, 3, intDomains, listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        for (i in 0 until 3) state.assignment.setInt(i, i + 1)
        state.recompute()
        for (round in 0 until 10) {
            val v = round % 3
            val cur = state.assignment.intValue(v)
            val target = (cur + 1) % 6
            if (cur == target) continue
            state.apply(IntSet(v, target))
            val incBreak = state.boolBreakCountSnapshot()
            val incMake = state.boolMakeCountSnapshot()
            state.recompute()
            assertEquals(
                incBreak.toList(),
                state.boolBreakCountSnapshot().toList(),
                "boolBreakCount mismatch after IntSet(v=$v, target=$target)",
            )
            assertEquals(
                incMake.toList(),
                state.boolMakeCountSnapshot().toList(),
                "boolMakeCount mismatch after IntSet(v=$v, target=$target)",
            )
        }
    }

    @Test
    fun `reified linear LE stays consistent across aux flips`() {
        // boolVars = [aux]; int vars are 0..2 with domains [0, 5].
        val factor = ReifiedLinear(
            auxBoolVar = 0,
            coeffs = intArrayOf(2, 3, 1),
            vars = intArrayOf(0, 1, 2),
            op = LinearOp.LE,
            bound = 10,
        )
        val intDomains = Array(3) { IntDomain(0, 5) }
        val problem = Problem(1, 3, intDomains, listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        for (i in 0 until 3) state.assignment.setInt(i, i + 1)
        state.recompute()
        repeat(4) {
            state.apply(BoolFlip(0))
            val incBreak = state.boolBreakCountSnapshot()
            val incMake = state.boolMakeCountSnapshot()
            state.recompute()
            assertEquals(incBreak.toList(), state.boolBreakCountSnapshot().toList())
            assertEquals(incMake.toList(), state.boolMakeCountSnapshot().toList())
        }
    }

    @Test
    fun `multiple factors of different kinds compose correctly`() {
        // Combined problem with all three factor kinds touching overlapping variables.
        val card = Cardinality(
            literals = intArrayOf(
                Lit.make(0, true),
                Lit.make(1, true),
                Lit.make(2, true),
                Lit.make(3, true),
            ),
            min = 1,
            max = 3,
        )
        val xor = Xor(
            literals = intArrayOf(
                Lit.make(0, true),
                Lit.make(2, true),
                Lit.make(4, true),
            ),
            targetParity = 1,
        )
        val pb = PseudoBoolean(
            weights = intArrayOf(2, 1, 3, 2),
            literals = intArrayOf(
                Lit.make(1, true),
                Lit.make(3, false),
                Lit.make(4, true),
                Lit.make(5, true),
            ),
            op = PbOp.LE,
            bound = 4,
        )
        assertConsistent(Problem(6, 0, emptyArray(), listOf<Factor>(card, xor, pb)))
    }
}

private fun LocalSearchState.boolBreakCountSnapshot(): IntArray = boolBreakCount.copyOf()
private fun LocalSearchState.boolMakeCountSnapshot(): IntArray = boolMakeCount.copyOf()
