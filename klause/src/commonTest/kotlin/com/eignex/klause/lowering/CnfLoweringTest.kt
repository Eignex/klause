package com.eignex.klause.lowering

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
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
import com.eignex.klause.solver.search.ClauseSearchComponent
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchRunEvent
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
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
    fun `conjunction lowering should construct its backward clause`() {
        val sink = Sink()
        sink.tseitinAnd(listOf(Lit.make(1, true), Lit.make(2, false)), Lit.make(0, true))

        val clause = sink.factors.last() as Clause
        assertContentEquals(intArrayOf(Lit.make(1, false), Lit.make(2, true), Lit.make(0, true)), clause.literals)
    }

    @Test
    fun `disjunction lowering should construct its forward clause`() {
        val sink = Sink()
        sink.tseitinOr(listOf(Lit.make(1, true), Lit.make(2, false)), Lit.make(0, true))

        val clause = sink.factors.first() as Clause
        assertContentEquals(intArrayOf(Lit.make(1, true), Lit.make(2, false), Lit.make(0, false)), clause.literals)
    }

    @Test
    fun `excluding one weak side should imply its sibling in the clause projection`() {
        for (op in listOf(LinearOp.EQ, LinearOp.NE)) {
            for (sign in listOf(1L, -1L)) {
                for (bound in listOf(Long.MIN_VALUE, 3L, Long.MAX_VALUE)) {
                    for (excluded in listOf(LinearOp.LE, LinearOp.GE)) {
                        val sink = Sink()
                        val target = sink.reifyDifferenceLinear(longArrayOf(sign, -sign), intArrayOf(0, 1), op, bound)
                        val sides = sink.factors.filterIsInstance<ReifiedLinear>()
                        val side = sides.single { it.op == excluded }
                        val sibling = sides.single { it.op != excluded }
                        val clauses = sink.factors.filterIsInstance<Clause>()
                        val session = SearchSession(listOf(ClauseSearchComponent(clauses)))
                        assertIs<ComponentResult.Consistent>(session.initialize())

                        val result = session.push(SearchDecision.Bool(Lit.make(side.auxBoolVar, false)))

                        assertIs<ComponentResult.Consistent>(result)
                        assertEquals(true, session.boolValue(sibling.auxBoolVar))
                        assertEquals(op == LinearOp.NE, session.boolValue(Lit.variable(target)))
                    }
                }
            }
        }
    }

    @Test
    fun `excluding both weak sides should refute the clause projection`() {
        for (op in listOf(LinearOp.EQ, LinearOp.NE)) {
            for (sign in listOf(1L, -1L)) {
                val sink = Sink()
                sink.reifyDifferenceLinear(longArrayOf(sign, -sign), intArrayOf(0, 1), op, 3L)
                val sides = sink.factors.filterIsInstance<ReifiedLinear>()
                val session = SearchSession(listOf(ClauseSearchComponent(sink.factors.filterIsInstance<Clause>())))
                assertIs<ComponentResult.Consistent>(session.initialize())
                val firstExclusion = SearchDecision.Bool(Lit.make(sides[0].auxBoolVar, false))
                assertIs<ComponentResult.Consistent>(session.push(firstExclusion))

                val result = session.push(SearchDecision.Bool(Lit.make(sides[1].auxBoolVar, false)))

                assertIs<ComponentResult.Conflict>(result)
            }
        }
    }

    @Test
    fun `an excluded equality should retain both strict side assignments`() {
        for (op in listOf(LinearOp.EQ, LinearOp.NE)) {
            val sink = Sink()
            val target = sink.reifyDifferenceLinear(longArrayOf(1, -1), intArrayOf(0, 1), op, 3L)
            val sides = sink.factors.filterIsInstance<ReifiedLinear>()
            val atMost = sides.single { it.op == LinearOp.LE }.auxBoolVar
            val atLeast = sides.single { it.op == LinearOp.GE }.auxBoolVar
            val clauses = sink.factors.filterIsInstance<Clause>() +
                Clause(intArrayOf(if (op == LinearOp.NE) target else Lit.negate(target)))
            val session = SearchSession(listOf(ClauseSearchComponent(clauses)))
            assertIs<ComponentResult.Consistent>(session.initialize())
            val run = session.openRun(sink.numBoolVars)
            val assignments = mutableSetOf<List<Boolean>>()

            var event = run.next()
            while (event is SearchRunEvent.Satisfied) {
                assignments.add(
                    listOf(requireNotNull(session.boolValue(atMost)), requireNotNull(session.boolValue(atLeast))),
                )
                event = run.next()
            }

            assertIs<SearchRunEvent.Exhausted>(event)
            assertEquals(setOf(listOf(false, true), listOf(true, false)), assignments)
        }
    }

    @Test
    fun `an asserted equality should retain both weak sides`() {
        for (op in listOf(LinearOp.EQ, LinearOp.NE)) {
            val sink = Sink()
            val target = sink.reifyDifferenceLinear(longArrayOf(1, -1), intArrayOf(0, 1), op, 3L)
            val sides = sink.factors.filterIsInstance<ReifiedLinear>()
            val clauses = sink.factors.filterIsInstance<Clause>() +
                Clause(intArrayOf(if (op == LinearOp.EQ) target else Lit.negate(target)))
            val session = SearchSession(listOf(ClauseSearchComponent(clauses)))

            val result = session.initialize()

            assertIs<ComponentResult.Consistent>(result)
            assertEquals(listOf(true, true), sides.map { session.boolValue(it.auxBoolVar) })
        }
    }

    @Test
    fun `unsupported split shapes should retain their existing Boolean namespace`() {
        for ((coeffs, op) in listOf(
            longArrayOf(1) to LinearOp.EQ,
            longArrayOf(2, -1) to LinearOp.EQ,
            longArrayOf(1, -1, 1) to LinearOp.NE,
            longArrayOf(1, -1) to LinearOp.LE,
            longArrayOf(1, -1) to LinearOp.GE,
        )) {
            val sink = Sink()

            val target = sink.reifyDifferenceLinear(coeffs, IntArray(coeffs.size) { it }, op, 3L)

            assertEquals(1, sink.numBoolVars)
            assertEquals(0, Lit.variable(target))
        }
    }

    @Test
    fun `difference equality and disequality should retain both Boolean meanings`() {
        for (bound in listOf(3L, 4294967297L)) {
            for (op in listOf(LinearOp.EQ, LinearOp.NE)) {
                for (offset in listOf(bound, bound + 1L)) {
                    val sink = Sink()
                    val literal = sink.reifyDifferenceLinear(
                        longArrayOf(1, -1),
                        intArrayOf(0, 1),
                        op,
                        bound,
                    )
                    val expected = if (op == LinearOp.EQ) offset == bound else offset != bound
                    sink.factors.add(Clause(intArrayOf(if (expected) literal else Lit.negate(literal))))
                    sink.factors.add(Linear(longArrayOf(1L, -1L), intArrayOf(0, 1), LinearOp.EQ, offset))
                    val problem = Problem(
                        sink.numBoolVars,
                        2,
                        Array(2) { IntDomain(Long.MIN_VALUE, Long.MAX_VALUE) },
                        sink.factors.toTypedArray(),
                        openIntLo = BooleanArray(2) { true },
                        openIntHi = BooleanArray(2) { true },
                    )

                    val result = OpenTheoryEngine(problem, problem.componentPlan().theoryPipeline).solve()

                    val assignment = assertIs<OpenTheoryResult.Sat>(result).assignment
                    assertEquals(ProblemPipeline.DIFFERENCE_THEORY, problem.sourceRoute())
                    assertEquals(expected, assignment.boolValue(Lit.variable(literal)))
                }
            }
        }
    }

    @Test
    fun `a false difference equality should conflict with the equal difference`() {
        val sink = Sink()
        val literal = sink.reifyDifferenceLinear(longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 3)
        sink.factors.add(Clause(intArrayOf(Lit.negate(literal))))
        sink.factors.add(Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 3))
        val problem = Problem(
            sink.numBoolVars,
            2,
            Array(2) { IntDomain(Long.MIN_VALUE, Long.MAX_VALUE) },
            sink.factors.toTypedArray(),
            openIntLo = BooleanArray(2) { true },
            openIntHi = BooleanArray(2) { true },
        )

        val result = OpenTheoryEngine(problem, problem.componentPlan().theoryPipeline).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `general and wide equalities should retain the linear route`() {
        for (wide in listOf(false, true)) {
            val sink = Sink()
            val literal = if (wide) {
                sink.reifyLinear(
                    arrayOf(parseBigInt("18446744073709551616"), bigIntOf(-1)),
                    intArrayOf(0, 1),
                    LinearOp.EQ,
                    bigIntOf(3),
                )
            } else {
                sink.reifyLinear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.EQ, 3)
            }
            sink.factors.add(Clause(intArrayOf(literal)))
            val problem = Problem(
                sink.numBoolVars,
                2,
                Array(2) { IntDomain(Long.MIN_VALUE, Long.MAX_VALUE) },
                sink.factors.toTypedArray(),
                openIntLo = BooleanArray(2) { true },
                openIntHi = BooleanArray(2) { true },
            )

            assertEquals(ProblemPipeline.EXACT_LIRA, problem.sourceRoute())
        }
    }

    @Test
    fun `unsafe difference bounds should retain the linear route`() {
        for (bound in listOf(Long.MIN_VALUE, Long.MAX_VALUE)) {
            val sink = Sink()
            val literal = sink.reifyDifferenceLinear(longArrayOf(1L, -1L), intArrayOf(0, 1), LinearOp.EQ, bound)
            sink.factors.add(Clause(intArrayOf(literal)))
            val problem = Problem(
                sink.numBoolVars,
                2,
                Array(2) { IntDomain(Long.MIN_VALUE, Long.MAX_VALUE) },
                sink.factors.toTypedArray(),
                openIntLo = BooleanArray(2) { true },
                openIntHi = BooleanArray(2) { true },
            )

            assertEquals(ProblemPipeline.EXACT_LIRA, problem.sourceRoute())
        }
    }
}
