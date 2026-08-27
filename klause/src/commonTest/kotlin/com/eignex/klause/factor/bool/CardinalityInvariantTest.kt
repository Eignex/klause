package com.eignex.klause.factor.bool

import com.eignex.klause.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.Move.BoolFlip
import com.eignex.klause.localsearch.Move.IntSet
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
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

    private fun assertConsistent(label: String, problem: Problem) {
        val state = LocalSearchState(problem, Random(0))
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
                "$label: boolBreakCount mismatch after flipping var=$v",
            )
            assertEquals(
                incMake.toList(),
                state.boolMakeCountSnapshot().toList(),
                "$label: boolMakeCount mismatch after flipping var=$v",
            )
        }
    }

    @Test
    fun `incremental break and make counts match recompute after every bool flip`() {
        val cases = listOf(
            "cardinality at most one" to Problem(
                5,
                0,
                emptyArray(),
                listOf(Cardinality.atMostOne(IntArray(5) { Lit.make(it, positive = true) })),
            ),
            "cardinality exactly one" to Problem(
                4,
                0,
                emptyArray(),
                listOf(Cardinality.exactlyOne(IntArray(4) { Lit.make(it, positive = true) })),
            ),
            "cardinality bounded range, mixed polarity" to Problem(
                6,
                0,
                emptyArray(),
                listOf(
                    Cardinality(
                        intArrayOf(
                            Lit.make(0, true),
                            Lit.make(1, false),
                            Lit.make(2, true),
                            Lit.make(3, false),
                            Lit.make(4, true),
                            Lit.make(5, true),
                        ),
                        min = 2,
                        max = 4,
                    ),
                ),
            ),
            // var 0 appears twice positive (signed = +2); var 1 once pos + once neg (signed = 0).
            "cardinality with repeated vars and cancelling polarities" to Problem(
                4,
                0,
                emptyArray(),
                listOf(
                    Cardinality(
                        intArrayOf(
                            Lit.make(0, true),
                            Lit.make(0, true),
                            Lit.make(1, true),
                            Lit.make(1, false),
                            Lit.make(2, true),
                            Lit.make(3, false),
                        ),
                        min = 1,
                        max = 3,
                    ),
                ),
            ),
            "xor odd target" to Problem(
                5,
                0,
                emptyArray(),
                listOf(Xor(IntArray(5) { Lit.make(it, positive = true) }, targetParity = 1)),
            ),
            "xor even target" to Problem(
                4,
                0,
                emptyArray(),
                listOf(
                    Xor(
                        intArrayOf(Lit.make(0, true), Lit.make(1, false), Lit.make(2, true), Lit.make(3, false)),
                        targetParity = 0,
                    ),
                ),
            ),
            "pseudo boolean LE" to Problem(
                5,
                0,
                emptyArray(),
                listOf(
                    PseudoBoolean(
                        weights = longArrayOf(3, 2, 1, 5, 4),
                        literals = IntArray(5) { Lit.make(it, positive = true) },
                        op = PbOp.LE,
                        bound = 7L,
                    ),
                ),
            ),
            "pseudo boolean GE with negative literals" to Problem(
                4,
                0,
                emptyArray(),
                listOf(
                    PseudoBoolean(
                        weights = longArrayOf(2, 4, 3, 1),
                        literals = intArrayOf(
                            Lit.make(0, true),
                            Lit.make(1, false),
                            Lit.make(2, true),
                            Lit.make(3, false),
                        ),
                        op = PbOp.GE,
                        bound = 5L,
                    ),
                ),
            ),
            "pseudo boolean EQ" to Problem(
                5,
                0,
                emptyArray(),
                listOf(
                    PseudoBoolean(
                        weights = longArrayOf(1, 1, 1, 1, 1),
                        literals = IntArray(5) { Lit.make(it, positive = true) },
                        op = PbOp.EQ,
                        bound = 3L,
                    ),
                ),
            ),
            "pseudo boolean with negative weights" to Problem(
                4,
                0,
                emptyArray(),
                listOf(
                    PseudoBoolean(
                        weights = longArrayOf(2, -3, 4, -1),
                        literals = IntArray(4) { Lit.make(it, positive = true) },
                        op = PbOp.LE,
                        bound = 1L,
                    ),
                ),
            ),
            "reified cardinality, body satisfiable" to Problem(
                6,
                0,
                emptyArray(),
                listOf(
                    ReifiedCardinality(
                        auxBoolVar = 5,
                        literals = IntArray(5) { Lit.make(it, positive = true) },
                        min = 2,
                        max = 3,
                    ),
                ),
            ),
            "reified cardinality, mixed polarity" to Problem(
                5,
                0,
                emptyArray(),
                listOf(
                    ReifiedCardinality(
                        auxBoolVar = 4,
                        literals = intArrayOf(
                            Lit.make(0, true),
                            Lit.make(1, false),
                            Lit.make(2, true),
                            Lit.make(3, false),
                        ),
                        min = 1,
                        max = 2,
                    ),
                ),
            ),
            "reified pseudo boolean LE" to Problem(
                6,
                0,
                emptyArray(),
                listOf(
                    ReifiedPseudoBoolean(
                        auxBoolVar = 5,
                        weights = longArrayOf(2, 3, 1, 4, 2),
                        literals = IntArray(5) { Lit.make(it, positive = true) },
                        op = PbOp.LE,
                        bound = 6L,
                    ),
                ),
            ),
            "reified pseudo boolean GE with negative literals" to Problem(
                5,
                0,
                emptyArray(),
                listOf(
                    ReifiedPseudoBoolean(
                        auxBoolVar = 4,
                        weights = longArrayOf(3, 2, 4, 1),
                        literals = intArrayOf(
                            Lit.make(0, true),
                            Lit.make(1, false),
                            Lit.make(2, true),
                            Lit.make(3, false),
                        ),
                        op = PbOp.GE,
                        bound = 5L,
                    ),
                ),
            ),
            "reified pseudo boolean EQ" to Problem(
                5,
                0,
                emptyArray(),
                listOf(
                    ReifiedPseudoBoolean(
                        auxBoolVar = 4,
                        weights = longArrayOf(1, 1, 1, 1),
                        literals = IntArray(4) { Lit.make(it, positive = true) },
                        op = PbOp.EQ,
                        bound = 2L,
                    ),
                ),
            ),
        )
        for ((label, problem) in cases) assertConsistent(label, problem)
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
        for (i in 0 until 3) state.assignment.setInt(i, (i + 1).toLong())
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
        for (i in 0 until 3) state.assignment.setInt(i, (i + 1).toLong())
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
    fun `break and make counts stay consistent when factor kinds share variables`() {
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
            weights = longArrayOf(2, 1, 3, 2),
            literals = intArrayOf(
                Lit.make(1, true),
                Lit.make(3, false),
                Lit.make(4, true),
                Lit.make(5, true),
            ),
            op = PbOp.LE,
            bound = 4L,
        )
        assertConsistent("card + xor + pb over shared vars", Problem(6, 0, emptyArray(), listOf<Factor>(card, xor, pb)))
    }
}

private fun LocalSearchState.boolBreakCountSnapshot(): IntArray = boolBreakCount.copyOf()
private fun LocalSearchState.boolMakeCountSnapshot(): IntArray = boolMakeCount.copyOf()
