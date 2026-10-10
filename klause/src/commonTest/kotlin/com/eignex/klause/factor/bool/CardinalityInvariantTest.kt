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
