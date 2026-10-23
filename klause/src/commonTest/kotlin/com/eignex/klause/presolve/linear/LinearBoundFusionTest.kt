package com.eignex.klause.presolve.linear

import com.eignex.klause.factor.arithmetic.ComparisonClause
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.linearRows
import com.eignex.klause.presolve.BakeConfig
import com.eignex.klause.presolve.Presolve
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.propagation.bake
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinearBoundFusionTest {
    private fun le(coeffs: IntArray, vars: IntArray, bound: Int) = Linear(coeffs, vars, LinearOp.LE, bound)
    private fun ge(coeffs: IntArray, vars: IntArray, bound: Int) = Linear(coeffs, vars, LinearOp.GE, bound)

    private fun problem(vararg factors: Linear) = Problem(0, 3, Array(3) { IntDomain(0, 10) }, factors.toList())

    private fun equalities(p: Problem) = p.factors.filterIsInstance<Linear>().filter { it.op == LinearOp.EQ }

    @Test
    fun `an upper and lower bound that meet should fuse into an equality`() {
        val p = problem(
            le(intArrayOf(1, 1), intArrayOf(0, 1), 5),
            ge(intArrayOf(1, 1), intArrayOf(0, 1), 5),
        )
        val out = p.bake().withPassDelta(Presolve.fuseLinearBounds(p), BakeConfig.NONE)
        val eqs = equalities(out)
        assertEquals(1, eqs.size)
        assertEquals(5L, checkNotNull(eqs.single().integerConstants).bound)
        assertEquals(setOf(0, 1), eqs.single().vars.toSet())
        assertTrue(out.factors.none { it is Linear && it.op == LinearOp.LE })
    }

    @Test
    fun `crossing bounds should prove infeasibility`() {
        val p = problem(
            le(intArrayOf(1, 1), intArrayOf(0, 1), 3),
            ge(intArrayOf(1, 1), intArrayOf(0, 1), 5),
        )
        assertTrue(Presolve.fuseLinearBounds(p).infeasible)
    }

    @Test
    fun `a proper interval should be left untouched`() {
        val p = problem(
            le(intArrayOf(1, 1), intArrayOf(0, 1), 5),
            ge(intArrayOf(1, 1), intArrayOf(0, 1), 3),
        )
        assertTrue(Presolve.fuseLinearBounds(p).isEmpty)
    }

    @Test
    fun `an equality contradicted by a tighter inequality should be infeasible`() {
        val p = problem(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 5),
            le(intArrayOf(1, 1), intArrayOf(0, 1), 3),
        )
        assertTrue(Presolve.fuseLinearBounds(p).infeasible)
    }

    @Test
    fun `wide proportional bounds should fuse without narrowing their constants`() {
        val huge = BIG_ONE shl 100
        val model = problem(
            Linear(intArrayOf(0, 1), arrayOf(huge, huge), LinearOp.LE, huge * bigIntOf(3)),
            Linear(intArrayOf(0, 1), arrayOf(huge, huge), LinearOp.GE, huge * bigIntOf(3)),
        )

        val delta = Presolve.fuseLinearBounds(model)

        assertEquals(3L, delta.addedFactors.single().linearRows.single().bound)
        assertContentEquals(intArrayOf(0, 1), delta.droppedIndices)
    }

    @Test
    fun `relaxation rows should support fusion without authorizing factor removal`() {
        val source = le(intArrayOf(1), intArrayOf(0), 5)
        val relaxation = object : Factor by source {
            override val linearForm: LinearForm = LinearForm.Relaxation(source.linearRows)
        }
        val model = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 10) },
            listOf(relaxation, ge(intArrayOf(1), intArrayOf(0), 5)),
        )

        val delta = Presolve.fuseLinearBounds(model)

        assertContentEquals(intArrayOf(1), delta.droppedIndices)
        assertEquals(1, delta.addedFactors.size)
    }

    @Test
    fun `comparison alternatives should not be used as simultaneous bounds`() {
        val model = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 10) },
            listOf(
                ComparisonClause(intArrayOf(0, 0), arrayOf(LinearOp.LE, LinearOp.GE), longArrayOf(0, 2)),
            ),
        )

        assertTrue(Presolve.fuseLinearBounds(model).isEmpty)
    }

    @Test
    fun `coalescing duplicate long terms should widen before fusion`() {
        val source = le(intArrayOf(1), intArrayOf(0), 0)
        val upper = object : Factor by source {
            override val linearForm: LinearForm = LinearForm.Conjunction(
                listOf(LinearRow.ofInts(intArrayOf(0, 0), longArrayOf(Long.MAX_VALUE, Long.MAX_VALUE), LinearOp.LE, 0)),
            )
        }
        val model = Problem(0, 3, Array(3) { IntDomain(0, 10) }, listOf(upper, ge(intArrayOf(1), intArrayOf(0), 0)))

        val delta = Presolve.fuseLinearBounds(model)

        assertEquals(0L, delta.addedFactors.single().linearRows.single().bound)
        assertContentEquals(intArrayOf(0, 1), delta.droppedIndices)
    }

    @Test
    fun `orienting a minimum long bound should preserve an exact contradiction`() {
        val model = problem(
            Linear(longArrayOf(-1), intArrayOf(0), LinearOp.LE, Long.MIN_VALUE),
            Linear(longArrayOf(1), intArrayOf(0), LinearOp.LE, Long.MAX_VALUE),
        )

        assertTrue(Presolve.fuseLinearBounds(model).infeasible)
    }

    @Test
    fun `Boolean alternatives should imply their weakest common affine bound`() {
        for ((op, expected) in listOf(LinearOp.LE to 9L, LinearOp.GE to 5L)) {
            val model = Problem(
                3,
                2,
                Array(2) { IntDomain(-20, 20) },
                listOf(
                    ReifiedLinear(1, intArrayOf(1, 1), intArrayOf(0, 1), op, 5),
                    ReifiedLinear(2, intArrayOf(1, 1), intArrayOf(0, 1), op, 9),
                    Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                    Clause(intArrayOf(Lit.make(0, true), Lit.make(2, true))),
                ),
            )

            val delta = Presolve.fuseLinearBounds(model)

            val row = delta.addedFactors.single().linearRows.single()
            assertEquals(LinearOp.LE, row.relation)
            val sign = if (op == LinearOp.GE) -1L else 1L
            assertEquals(sign * expected, row.bound)
            assertEquals(sign, row.coeff(0))
            assertEquals(sign, row.coeff(1))
            assertTrue(delta.droppedIndices.isEmpty())
        }
    }

    @Test
    fun `equality alternatives should keep their holes while implying both affine sides`() {
        val model = Problem(
            3,
            2,
            Array(2) { IntDomain(-20, 20) },
            listOf(
                ReifiedLinear(1, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 3),
                ReifiedLinear(2, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 8),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(2, true))),
            ),
        )

        val delta = Presolve.fuseLinearBounds(model)

        val rows = delta.addedFactors.map { it.linearRows.single() }
        assertTrue(rows.all { it.relation == LinearOp.LE })
        assertEquals(setOf(-1L to -3L, 1L to 8L), rows.map { it.coeff(0) to it.bound }.toSet())
        assertTrue(delta.droppedIndices.isEmpty())
    }

    @Test
    fun `a source clause should cover every annotated alternative including repeated literals`() {
        for ((alternatives, expected) in listOf(
            intArrayOf(0) to 5L,
            intArrayOf(0, 1, 2) to 9L,
            intArrayOf(0, 1, 2, 2) to 9L,
        )) {
            val model = Problem(
                3,
                2,
                Array(2) { IntDomain(-20, 20) },
                listOf(
                    ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 5),
                    ReifiedLinear(1, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 9),
                    ReifiedLinear(2, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 7),
                    Clause(IntArray(alternatives.size) { Lit.make(alternatives[it], true) }),
                ),
            )

            val delta = Presolve.fuseLinearBounds(model)

            val row = delta.addedFactors.single().linearRows.single()
            assertEquals(LinearOp.LE, row.relation)
            assertEquals(expected, row.bound)
        }
    }

    @Test
    fun `negated inequality guards should use integral complements`() {
        for ((op, expectedOp, bound) in listOf(
            Triple(LinearOp.GE, LinearOp.LE, 8L),
            Triple(LinearOp.LE, LinearOp.GE, 5L),
        )) {
            val model = Problem(
                3,
                2,
                Array(2) { IntDomain(-20, 20) },
                listOf(
                    ReifiedLinear(1, intArrayOf(1, 1), intArrayOf(0, 1), op, 4),
                    ReifiedLinear(2, intArrayOf(1, 1), intArrayOf(0, 1), op, 9),
                    Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
                    Clause(intArrayOf(Lit.make(0, true), Lit.make(2, false))),
                ),
            )

            val delta = Presolve.fuseLinearBounds(model)

            val row = delta.addedFactors.single().linearRows.single()
            assertEquals(LinearOp.LE, row.relation)
            val sign = if (expectedOp == LinearOp.GE) -1L else 1L
            assertEquals(sign * bound, row.bound)
            assertEquals(sign, row.coeff(0))
            assertEquals(sign, row.coeff(1))
        }
    }

    @Test
    fun `negated disequality alternatives should imply an affine interval`() {
        val model = Problem(
            3,
            2,
            Array(2) { IntDomain(-20, 20) },
            listOf(
                ReifiedLinear(1, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.NE, 4),
                ReifiedLinear(2, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.NE, 9),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(2, false))),
            ),
        )

        val delta = Presolve.fuseLinearBounds(model)

        val rows = delta.addedFactors.map { it.linearRows.single() }
        assertTrue(rows.all { it.relation == LinearOp.LE })
        assertEquals(setOf(-1L to -4L, 1L to 9L), rows.map { it.coeff(0) to it.bound }.toSet())
    }

    @Test
    fun `implication paths should transfer their strongest affine consequence`() {
        val model = Problem(
            5,
            2,
            Array(2) { IntDomain(-20, 20) },
            listOf(
                ReifiedLinear(1, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 9),
                ReifiedLinear(2, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 5),
                ReifiedLinear(3, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 7),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(4, true))),
                Clause(intArrayOf(Lit.make(4, false), Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(4, false), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(3, true))),
            ),
        )

        val delta = Presolve.fuseLinearBounds(model)

        val row = delta.addedFactors.single().linearRows.single()
        assertEquals(LinearOp.LE, row.relation)
        assertEquals(7L, row.bound)
    }

    @Test
    fun `guarded affine bounds should share signed scaled and permuted forms`() {
        val model = Problem(
            3,
            2,
            Array(2) { IntDomain(-20, 20) },
            listOf(
                ReifiedLinear(1, intArrayOf(2, 2), intArrayOf(1, 0), LinearOp.LE, 10),
                ReifiedLinear(2, intArrayOf(-3, -3), intArrayOf(0, 1), LinearOp.GE, -27),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(2, true))),
            ),
        )

        val delta = Presolve.fuseLinearBounds(model)

        val factor = delta.addedFactors.single() as Linear
        assertEquals(LinearOp.LE, factor.op)
        assertEquals(9L, checkNotNull(factor.integerConstants).bound)
        assertContentEquals(intArrayOf(0, 1), factor.vars)
        assertContentEquals(longArrayOf(1, 1), checkNotNull(factor.integerConstants).coeffs)
    }

    @Test
    fun `integral guard complements should retain bounds beyond Long`() {
        val open = Bits(2).also { it.set(0); it.set(1) }
        val model = Problem(
            numBoolVars = 3,
            intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open),
            factors = arrayOf(
                ReifiedLinear(1, longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, Long.MIN_VALUE),
                ReifiedLinear(2, longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, Long.MIN_VALUE),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(2, false))),
            ),
        )

        val delta = Presolve.fuseLinearBounds(model)

        val row = delta.addedFactors.single().linearRows.single()
        assertEquals(LinearOp.LE, row.relation)
        assertEquals(bigIntOf(Long.MIN_VALUE) - BIG_ONE, (row.constants as IntegralConstants).exactBound)
    }

    @Test
    fun `alternatives on unrelated affine forms should not create a common bound`() {
        val model = Problem(
            3,
            2,
            Array(2) { IntDomain(-20, 20) },
            listOf(
                ReifiedLinear(1, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 5),
                ReifiedLinear(2, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 9),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(2, true))),
            ),
        )

        val delta = Presolve.fuseLinearBounds(model)

        assertTrue(delta.isEmpty)
    }

    @Test
    fun `an alternative without an affine consequence should leave the form unbounded`() {
        val model = Problem(
            3,
            2,
            Array(2) { IntDomain(-20, 20) },
            listOf(
                ReifiedLinear(1, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 5),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(2, true))),
            ),
        )

        val delta = Presolve.fuseLinearBounds(model)

        assertTrue(delta.isEmpty)
    }

    @Test
    fun `a stronger unconditional side should not gain a redundant guard envelope`() {
        val model = Problem(
            3,
            2,
            Array(2) { IntDomain(-20, 20) },
            listOf(
                ReifiedLinear(1, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 5),
                ReifiedLinear(2, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 9),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(2, true))),
                le(intArrayOf(1, 1), intArrayOf(0, 1), 4),
            ),
        )

        val delta = Presolve.fuseLinearBounds(model)

        assertTrue(delta.isEmpty)
    }

    @Test
    fun `guard envelopes should not be emitted again after installation`() {
        val model = Problem(
            3,
            2,
            Array(2) { IntDomain(-20, 20) },
            listOf(
                ReifiedLinear(1, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 3),
                ReifiedLinear(2, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 8),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(2, true))),
            ),
        )
        val first = Presolve.fuseLinearBounds(model)
        val strengthened = Problem(
            model.numBoolVars,
            model.numIntVars,
            model.declaredIntDomains,
            (model.factors.toList() + first.addedFactors).toTypedArray(),
        )

        val second = Presolve.fuseLinearBounds(strengthened)

        assertEquals(2, first.addedFactors.size)
        assertTrue(second.isEmpty)
    }

    @Test
    fun `cancelled guard analysis should publish no partial bound`() {
        val model = Problem(
            3,
            2,
            Array(2) { IntDomain(-20, 20) },
            listOf(
                ReifiedLinear(1, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 5),
                ReifiedLinear(2, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 9),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(2, true))),
            ),
        )

        val fullBudget = PresolveBudget(Long.MAX_VALUE)
        val complete = LinearBoundFusion.fuseLinearBounds(model, fullBudget.orSpent(Cancellation.Never))
        val budget = PresolveBudget(fullBudget.spent() - 16)

        val delta = LinearBoundFusion.fuseLinearBounds(model, budget.orSpent(Cancellation.Never))

        assertEquals(1, complete.addedFactors.size)
        assertTrue(budget.spent() > 0)
        assertTrue(delta.isEmpty)
    }

    @Test
    fun `a guard envelope crossing an unconditional bound should refute the source`() {
        val model = Problem(
            3,
            2,
            Array(2) { IntDomain(-20, 20) },
            listOf(
                ReifiedLinear(1, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 5),
                ReifiedLinear(2, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 9),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(2, true))),
                ge(intArrayOf(1, 1), intArrayOf(0, 1), 10),
            ),
        )

        val delta = Presolve.fuseLinearBounds(model)

        assertTrue(delta.infeasible)
    }

    @Test
    fun `relaxation guards should not supply an exact affine definition`() {
        val source = ReifiedLinear(1, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 5)
        val relaxation = object : Factor by source {
            override val linearForm = LinearForm.Relaxation(source.linearRows)
        }
        val model = Problem(
            3,
            2,
            Array(2) { IntDomain(-20, 20) },
            listOf(
                relaxation,
                ReifiedLinear(2, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 9),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(2, true))),
            ),
        )

        val delta = Presolve.fuseLinearBounds(model)

        assertTrue(delta.isEmpty)
    }
}
