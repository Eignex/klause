package com.eignex.klause.presolve.structural

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.NValue
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.BakeConfig
import com.eignex.klause.presolve.Presolve
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.presolve.PresolveShared.withSourcePassDelta
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.propagate
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StructuralReductionTest {
    private fun theLinear(problem: Problem): Linear = problem.factors.filterIsInstance<Linear>().single()

    private fun reduced(problem: Problem): Problem =
        problem.bake().let { it.withPassDelta(Presolve.reduceStructural(it), BakeConfig.NONE) }

    private fun fixedElement(openResult: Boolean) = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(2, 2), IntDomain(0, 100)),
        factors = listOf(Element(idx = 0, result = 1, arr = longArrayOf(10, 20, 30), arrIsVars = false)),
        openIntHi = booleanArrayOf(false, openResult),
    )

    @Test
    fun `source reduction should rewrite a global whose columns are all closed`() {
        val delta = StructuralReduction.reduceSource(fixedElement(openResult = false))

        assertEquals(listOf(0), delta.droppedIndices.toList())
        assertTrue(delta.addedFactors.single() is Linear)
    }

    @Test
    fun `source reduction should leave a global over an open column alone`() {
        val delta = StructuralReduction.reduceSource(fixedElement(openResult = true))

        assertTrue(delta.isEmpty)
    }

    @Test
    fun `unit specialization should preserve all integer and Boolean assignments`() {
        for (op in LinearOp.entries) {
            for (truth in listOf(false, true)) {
                val unit = Clause(intArrayOf(Lit.make(0, truth)))
                val problem = Problem(
                    1, 1, arrayOf(IntDomain(-2, 2)),
                    listOf(unit, ReifiedLinear(0, intArrayOf(1), intArrayOf(0), op, 0)),
                )

                val out = problem.withSourcePassDelta(StructuralReduction.reduceSource(problem))!!

                assertTrue(out.factors.none { it is ReifiedLinear })
                assertSame(unit, out.factors.filterIsInstance<Clause>().single())
                for (value in -2L..2L) {
                    for (boolean in listOf(false, true)) {
                        val assumptions = Assumptions.None.withInt(0, value).withBool(0, boolean)
                        assertEquals(
                            problem.propagate(assumptions) !is PropagationResult.Unsat,
                            out.propagate(assumptions) !is PropagationResult.Unsat,
                            "op=$op truth=$truth value=$value boolean=$boolean",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `unit specialization should keep source declarations and namespaces open`() {
        val unit = Clause(intArrayOf(Lit.make(0, true)))
        val problem = Problem(
            1, 2, Array(2) { IntDomain(0, 0) },
            listOf(unit, ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 0)),
            openIntLo = booleanArrayOf(true, true),
            openIntHi = booleanArrayOf(true, true),
        )

        val out = problem.withSourcePassDelta(StructuralReduction.reduceSource(problem))!!

        assertEquals(1, out.numBoolVars)
        assertEquals(2, out.numIntVars)
        assertSame(unit, out.factors.filterIsInstance<Clause>().single())
        assertEquals(LinearOp.EQ, theLinear(out).op)
        for (variable in 0..1) {
            assertTrue(out.intBounds.isOpenLower(variable))
            assertTrue(out.intBounds.isOpenUpper(variable))
        }
    }

    @Test
    fun `undecided or contradictory units should not specialize a reification`() {
        for (units in listOf(
            emptyList(),
            listOf(Clause(intArrayOf(Lit.make(0, true))), Clause(intArrayOf(Lit.make(0, false)))),
        )) {
            val problem = Problem(
                1, 1, arrayOf(IntDomain(0, 0)),
                units + ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
                openIntLo = booleanArrayOf(true),
                openIntHi = booleanArrayOf(true),
            )

            val delta = StructuralReduction.reduceSource(problem)

            assertTrue(delta.isEmpty)
        }
    }

    @Test
    fun `unit specialization should expose dependent equalities without repeating its rewrite`() {
        val problem = Problem(
            3, 2, Array(2) { IntDomain(0, 0) },
            List(3) { Clause(intArrayOf(Lit.make(it, true))) } + listOf(
                ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 1),
                ReifiedLinear(1, intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.EQ, 2),
                ReifiedLinear(2, intArrayOf(3), intArrayOf(0), LinearOp.EQ, 3),
            ),
            openIntLo = booleanArrayOf(true, true),
            openIntHi = booleanArrayOf(true, true),
        )

        val out = problem.withSourcePassDelta(StructuralReduction.reduceSource(problem))!!
        val dependent = Presolve.dropSourceDependentEqualities(out, Cancellation.Never)

        assertFalse(dependent.infeasible)
        assertEquals(1, dependent.droppedIndices.size)
        assertTrue(StructuralReduction.reduceSource(out).isEmpty)
    }

    @Test
    fun `interrupted unit collection should not authorize a partial specialization`() {
        val problem = Problem(
            3, 1, arrayOf(IntDomain(0, 0)),
            listOf(Clause(intArrayOf(Lit.make(0, true)))) +
                List(128) { Clause(intArrayOf(Lit.make(1, true), Lit.make(2, true))) } + listOf(
                    Clause(intArrayOf(Lit.make(0, false))),
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
                ),
            openIntLo = booleanArrayOf(true),
            openIntHi = booleanArrayOf(true),
        )
        for (allowance in listOf(1L, 51L, 1_001L)) {
            val budget = PresolveBudget(allowance)

            val delta = StructuralReduction.reduceSource(problem, budget.orSpent(Cancellation.Never))

            assertTrue(delta.isEmpty, "allowance=$allowance")
            assertTrue(budget.spent() in allowance..allowance + 9, "spent=${budget.spent()}")
        }
    }

    @Test
    fun `interrupted construction should retain prior rewrites and the unfinished original factor`() {
        val unfinished = ReifiedLinear(1, IntArray(128) { 1 }, IntArray(128) { it }, LinearOp.LE, 0)
        val problem = Problem(
            2, 128, Array(128) { IntDomain(0, 0) },
            listOf(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(1, false))),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
                unfinished,
            ),
            openIntLo = BooleanArray(128) { true },
            openIntHi = BooleanArray(128) { true },
        )
        for (allowance in listOf(1_600L, 2_000L, 2_400L)) {
            val budget = PresolveBudget(allowance)

            val delta = StructuralReduction.reduceSource(problem, budget.orSpent(Cancellation.Never))
            val out = problem.withSourcePassDelta(delta)!!

            assertEquals(listOf(2), delta.droppedIndices.toList(), "allowance=$allowance")
            assertSame(unfinished, out.factors.filterIsInstance<ReifiedLinear>().single())
            assertTrue(budget.spent() in allowance..allowance + 79, "spent=${budget.spent()}")
        }
    }

    @Test
    fun `source specialization should preserve a complement outside Long bounds`() {
        val problem = Problem(
            1, 1, arrayOf(IntDomain(0, 0)),
            listOf(
                Clause(intArrayOf(Lit.make(0, false))),
                ReifiedLinear(0, longArrayOf(1), intArrayOf(0), LinearOp.GE, Long.MIN_VALUE),
            ),
            openIntLo = booleanArrayOf(true),
            openIntHi = booleanArrayOf(true),
        )

        val out = problem.withSourcePassDelta(StructuralReduction.reduceSource(problem))!!

        assertEquals(LinearOp.LE, theLinear(out).op)
        assertEquals("-9223372036854775809", checkNotNull(theLinear(out).wideConstants).bound.toString())
    }

    @Test
    fun `a constant array of one value should fix the result and tighten the index range`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 5), IntDomain(0, 10)),
            listOf(Element(idx = 0, result = 1, arr = longArrayOf(7, 7, 7), arrIsVars = false)),
        )
        val out = reduced(problem)
        assertTrue(out.factors.none { it is Element })
        assertEquals(7L, checkNotNull(theLinear(out).integerConstants).bound)
        assertEquals(1L, out.finiteIntDomain(0).min)
        assertEquals(3L, out.finiteIntDomain(0).max)
    }

    @Test
    fun `a two-variable all-different should become a binary disequality`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            listOf(AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 4)),
        )
        val out = reduced(problem)
        assertTrue(out.factors.none { it is AllDifferent })
        val ne = theLinear(out)
        assertEquals(LinearOp.NE, ne.op)
        assertEquals(setOf(0, 1), ne.vars.toSet())
        assertEquals(0L, checkNotNull(ne.integerConstants).bound)
    }

    @Test
    fun `an all-different over value-disjoint groups should split into independent all-differents`() {
        val problem = Problem(
            0,
            6,
            arrayOf(
                IntDomain(0, 5),
                IntDomain(0, 5),
                IntDomain(0, 5),
                IntDomain(10, 15),
                IntDomain(10, 15),
                IntDomain(10, 15),
            ),
            listOf(AllDifferent(intArrayOf(0, 1, 2, 3, 4, 5), domainMin = 0, domainSize = 16)),
        )
        val out = reduced(problem)
        val groups = out.factors.filterIsInstance<AllDifferent>().map { it.vars.toSet() }
        assertEquals(setOf(setOf(0, 1, 2), setOf(3, 4, 5)), groups.toSet())
    }

    @Test
    fun `a cumulative whose tasks cannot share the resource should become a disjunctive`() {
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 10), IntDomain(0, 10), IntDomain(0, 10)),
            listOf(
                Cumulative(
                    starts = intArrayOf(0, 1, 2),
                    durations = longArrayOf(2, 2, 2),
                    resources = longArrayOf(3, 3, 3),
                    capacity = 4L,
                ),
            ),
        )
        val out = reduced(problem)
        assertTrue(out.factors.none { it is Cumulative && !it.unary })
        val disj = out.factors.filterIsInstance<Cumulative>().single { it.unary }
        assertEquals(listOf(0, 1, 2), disj.starts.toList())
    }

    private fun coeffOf(linear: Linear, v: Int): Long = checkNotNull(
        linear.integerConstants,
    ).coeffs[linear.vars.indexOf(v)]

    @Test
    fun `a fixed operand should turn a product into a linear equality`() {
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(3, 3), IntDomain(0, 10), IntDomain(0, 100)),
            listOf(Product(a = 0, b = 1, result = 2)),
        )
        val out = reduced(problem)
        assertTrue(out.factors.none { it is Product })
        val eq = theLinear(out)
        assertEquals(LinearOp.EQ, eq.op)
        assertEquals(0L, checkNotNull(eq.integerConstants).bound)
        assertEquals(1L, coeffOf(eq, 2))
        assertEquals(-3L, coeffOf(eq, 1))
    }

    @Test
    fun `an nvalue with target equal to arity should become all-different`() {
        val problem = Problem(
            0,
            4,
            arrayOf(IntDomain(0, 5), IntDomain(0, 5), IntDomain(0, 5), IntDomain(3, 3)),
            listOf(NValue(n = 3, xs = intArrayOf(0, 1, 2))),
        )
        val out = reduced(problem)
        assertTrue(out.factors.none { it is NValue })
        val ad = out.factors.filterIsInstance<AllDifferent>().single()
        assertEquals(listOf(0, 1, 2), ad.vars.toList())
    }

    @Test
    fun `an nvalue with target one should force all values equal`() {
        val problem = Problem(
            0,
            4,
            arrayOf(IntDomain(0, 5), IntDomain(0, 5), IntDomain(0, 5), IntDomain(1, 1)),
            listOf(NValue(n = 3, xs = intArrayOf(0, 1, 2))),
        )
        val out = reduced(problem)
        assertTrue(out.factors.none { it is NValue })
        assertEquals(2, out.factors.filterIsInstance<Linear>().size)
    }
}
