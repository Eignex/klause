package com.eignex.klause.lowering

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.pipeline.OpenTheoryEngine
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.ProblemPipeline
import com.eignex.klause.solver.pipeline.componentPlan
import com.eignex.klause.solver.pipeline.sourceRoute
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import com.eignex.klause.util.toLongExact
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CnfLoweringTest {

    private class Sink : CnfLowering {
        override val factors = mutableListOf<Factor>()
        override var trueLitCache = -1
        private var nextBool = 0

        val numBoolVars: Int get() = nextBool

        override fun newBool(): Int = nextBool++
    }

    @Test
    fun `conjunction lowering constructs its backward clause`() {
        val sink = Sink()
        sink.tseitinAnd(listOf(Lit.make(1, true), Lit.make(2, false)), Lit.make(0, true))

        val clause = sink.factors.last() as Clause
        assertContentEquals(intArrayOf(Lit.make(1, false), Lit.make(2, true), Lit.make(0, true)), clause.literals)
    }

    @Test
    fun `disjunction lowering constructs its forward clause`() {
        val sink = Sink()
        sink.tseitinOr(listOf(Lit.make(1, true), Lit.make(2, false)), Lit.make(0, true))

        val clause = sink.factors.first() as Clause
        assertContentEquals(intArrayOf(Lit.make(1, true), Lit.make(2, false), Lit.make(0, false)), clause.literals)
    }

    @Test
    fun `difference equality and disequality retain both Boolean meanings`() {
        for (bound in listOf(3L, 4294967297L)) {
            for (op in listOf(LinearOp.EQ, LinearOp.NE)) {
                for (offset in listOf(bound - 1L, bound, bound + 1L)) {
                    val sink = Sink()
                    val literal = sink.reifyDifferenceLinear(
                        longArrayOf(1, -1), intArrayOf(0, 1), op, bound,
                    )
                    val expected = if (op == LinearOp.EQ) offset == bound else offset != bound
                    sink.factors.add(Clause(intArrayOf(if (expected) literal else Lit.negate(literal))))
                    sink.factors.add(Linear(longArrayOf(1L, -1L), intArrayOf(0, 1), LinearOp.EQ, offset))
                    val problem = Problem(
                        sink.numBoolVars, 2, Array(2) { IntDomain(Long.MIN_VALUE, Long.MAX_VALUE) },
                        sink.factors.toTypedArray(), openIntLo = BooleanArray(2) { true },
                        openIntHi = BooleanArray(2) { true },
                    )

                    val result = OpenTheoryEngine(problem, problem.componentPlan().theoryPipeline).solve()

                    val assignment = assertIs<OpenTheoryResult.Sat>(result).assignment
                    assertEquals(ProblemPipeline.DIFFERENCE_THEORY, problem.sourceRoute())
                    assertEquals(expected, assignment.boolValue(Lit.variable(literal)))
                    assertEquals(
                        offset,
                        parseBigInt(assignment.intValue(0)).toLongExact() -
                            parseBigInt(assignment.intValue(1)).toLongExact(),
                    )
                }
            }
        }
    }

    @Test
    fun `a false difference equality conflicts with the equal difference`() {
        val sink = Sink()
        val literal = sink.reifyDifferenceLinear(longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 3)
        sink.factors.add(Clause(intArrayOf(Lit.negate(literal))))
        sink.factors.add(Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 3))
        val problem = Problem(
            sink.numBoolVars, 2, Array(2) { IntDomain(Long.MIN_VALUE, Long.MAX_VALUE) },
            sink.factors.toTypedArray(), openIntLo = BooleanArray(2) { true },
            openIntHi = BooleanArray(2) { true },
        )

        val result = OpenTheoryEngine(problem, problem.componentPlan().theoryPipeline).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `general and wide equalities retain the linear route`() {
        for (wide in listOf(false, true)) {
            val sink = Sink()
            val literal = if (wide) {
                sink.reifyLinear(
                    arrayOf(parseBigInt("18446744073709551616"), bigIntOf(-1)),
                    intArrayOf(0, 1), LinearOp.EQ, bigIntOf(3),
                )
            } else {
                sink.reifyLinear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.EQ, 3)
            }
            sink.factors.add(Clause(intArrayOf(literal)))
            val problem = Problem(
                sink.numBoolVars, 2, Array(2) { IntDomain(Long.MIN_VALUE, Long.MAX_VALUE) },
                sink.factors.toTypedArray(), openIntLo = BooleanArray(2) { true },
                openIntHi = BooleanArray(2) { true },
            )

            assertEquals(ProblemPipeline.EXACT_LIRA, problem.sourceRoute())
        }
    }

    @Test
    fun `unsafe difference bounds retain the linear route`() {
        for (bound in listOf(Long.MIN_VALUE, Long.MAX_VALUE)) {
            val sink = Sink()
            val literal = sink.reifyDifferenceLinear(longArrayOf(1L, -1L), intArrayOf(0, 1), LinearOp.EQ, bound)
            sink.factors.add(Clause(intArrayOf(literal)))
            val problem = Problem(
                sink.numBoolVars, 2, Array(2) { IntDomain(Long.MIN_VALUE, Long.MAX_VALUE) },
                sink.factors.toTypedArray(), openIntLo = BooleanArray(2) { true },
                openIntHi = BooleanArray(2) { true },
            )

            assertEquals(ProblemPipeline.EXACT_LIRA, problem.sourceRoute())
        }
    }
}
