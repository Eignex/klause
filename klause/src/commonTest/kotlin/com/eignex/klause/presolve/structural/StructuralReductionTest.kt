package com.eignex.klause.presolve.structural

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.NValue
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorReduction
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.BakeConfig
import com.eignex.klause.presolve.Presolve
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.propagatorProjection
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StructuralReductionTest {
    @Test
    fun `interruption in a later hook should retain only the complete earlier rewrite`() {
        for (source in listOf(true, false)) {
            var stopped = false
            val first = Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1)
            val second = Linear(intArrayOf(1), intArrayOf(1), LinearOp.EQ, 0)
            val factors = listOf(
                object : Factor by first, Propagator by first.propagatorProjection() {
                    override fun structuralReduce(domains: Array<IntDomain>): FactorReduction =
                        FactorReduction.Rewrite(emptyList(), mapOf(0 to 1..1))
                },
                object : Factor by second, Propagator by second.propagatorProjection() {
                    override fun structuralReduce(domains: Array<IntDomain>): FactorReduction {
                        stopped = true
                        return FactorReduction.Rewrite(emptyList(), mapOf(1 to 0..0))
                    }
                },
            )
            val problem = BakedProblem(0, 2, Array(2) { IntDomain(0, 3) }, factors, alreadyFolded = true)
            val token = Cancellation { stopped }

            if (source) {
                val delta = StructuralReduction.reduceSource(problem, token)

                assertEquals(listOf(0), delta.droppedIndices.toList())
                assertTrue(delta.addedFactors.isEmpty())
                val bounds = checkNotNull(delta.bounds)
                assertEquals(1L, bounds.lower(0))
                assertEquals(1L, bounds.upper(0))
                assertEquals(0L, bounds.lower(1))
                assertEquals(3L, bounds.upper(1))
            } else {
                val delta = StructuralReduction.reduce(problem, token)

                assertEquals(listOf(0), delta.droppedIndices.toList())
                assertTrue(delta.addedFactors.isEmpty())
                val domains = checkNotNull(delta.domains)
                assertEquals(IntDomain(1, 1), domains[0])
                assertEquals(IntDomain(0, 3), domains[1])
            }
        }
    }

    @Test
    fun `interrupted replacement collection should publish no partial rewrite`() {
        for (source in listOf(true, false)) {
            var reads = 0
            val row = Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 3)
            val replacement = object : Factor by row {
                override val intVars: IntArray
                    get() {
                        reads++
                        return intArrayOf(0, 1)
                    }
            }
            val factor = object : Factor by row, Propagator by row.propagatorProjection() {
                override fun structuralReduce(domains: Array<IntDomain>): FactorReduction =
                    FactorReduction.Rewrite(List(128) { replacement })
            }
            val problem = BakedProblem(0, 2, Array(2) { IntDomain(0, 3) }, listOf(factor), alreadyFolded = true)
            val budget = PresolveBudget(200L)
            val token = budget.orSpent(Cancellation.Never)

            val empty = if (source) StructuralReduction.reduceSource(problem, token).isEmpty
            else StructuralReduction.reduce(problem, token).isEmpty

            assertTrue(empty)
            assertTrue(reads in 1..10)
            assertEquals(200L, budget.spent())
        }
    }

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
