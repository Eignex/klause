package com.eignex.klause.factor.bool

import com.eignex.klause.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move.BoolFlip
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class CardinalityInvariantTest {

    private fun assertConsistent(label: String, problem: Problem) {
        val state = LocalSearchState(problem.bake(), Random(0))
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
    fun `incremental break and make counts should match recompute after every bool flip`() {
        val mixed = intArrayOf(pos(0), neg(1), pos(2), neg(3))
        val cases = listOf(
            "cardinality with repeated vars and cancelling polarities" to
                Cardinality(intArrayOf(pos(0), pos(0), pos(1), neg(1), pos(2), neg(3)), min = 1, max = 3),
            "xor even target" to Xor(mixed, targetParity = 0),
            "pseudo boolean GE with negative literals" to
                PseudoBoolean(longArrayOf(2, 4, 3, 1), mixed, PbOp.GE, 5L),
            "pseudo boolean with negative weights" to
                PseudoBoolean(longArrayOf(2, -3, 4, -1), IntArray(4) { pos(it) }, PbOp.LE, 1L),
            "reified cardinality, mixed polarity" to
                ReifiedCardinality(auxBoolVar = 4, literals = mixed, min = 1, max = 2),
            "reified pseudo boolean GE with negative literals" to
                ReifiedPseudoBoolean(4, longArrayOf(3, 2, 4, 1), mixed, PbOp.GE, 5L),
        )
        for ((label, factor) in cases) assertConsistent(label, Problem(5, 0, emptyArray(), listOf(factor)))
    }

    @Test
    fun `break and make counts should stay consistent when factor kinds share variables`() {
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

private fun pos(v: Int): Int = Lit.make(v, true)
private fun neg(v: Int): Int = Lit.make(v, false)
private fun LocalSearchState.boolBreakCountSnapshot(): IntArray = boolBreakCount.copyOf()
private fun LocalSearchState.boolMakeCountSnapshot(): IntArray = boolMakeCount.copyOf()
