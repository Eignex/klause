package com.eignex.klause.presolve.linear

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.linearRows
import com.eignex.klause.lp.lattice.LATTICE_WORK_WEIGHT
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DependentEqualitiesTest {
    private fun eq(coeffs: IntArray, vars: IntArray, bound: Int) = Linear(coeffs, vars, LinearOp.EQ, bound)

    private fun problem(vararg factors: Factor) = Problem(0, 3, Array(3) { IntDomain(0, 10) }, factors.toList())

    private fun dropped(p: Problem) = DependentEqualities.dropImplied(p, Cancellation.Never)

    @Test
    fun `an equality that is the sum of two others should be dropped`() {
        val rows = listOf(
            eq(intArrayOf(1, 1), intArrayOf(0, 1), 3),
            eq(intArrayOf(1, 1), intArrayOf(1, 2), 4),
            eq(intArrayOf(1, 2, 1), intArrayOf(0, 1, 2), 7),
        )

        for (factors in listOf(rows, rows.map { object : Factor by it {} })) {
            assertEquals(listOf(2), dropped(problem(*factors.toTypedArray())).droppedIndices.toList())
        }
    }

    @Test
    fun `an equality block with no rational solution should refute the model`() {
        val rows = listOf(
            eq(intArrayOf(1, 1), intArrayOf(0, 1), 3),
            eq(intArrayOf(1, 1), intArrayOf(1, 2), 4),
            eq(intArrayOf(1, 2, 1), intArrayOf(0, 1, 2), 8),
        )

        for (factors in listOf(rows, rows.map { object : Factor by it {} })) {
            assertTrue(dropped(problem(*factors.toTypedArray())).infeasible)
        }
    }

    @Test
    fun `an inequality the equalities imply should not be read`() {
        val p = problem(
            eq(intArrayOf(1, 1), intArrayOf(0, 1), 3),
            eq(intArrayOf(1, 1), intArrayOf(1, 2), 4),
            Linear(intArrayOf(1, 2, 1), intArrayOf(0, 1, 2), LinearOp.LE, 7),
        )

        assertTrue(dropped(p).isEmpty)
    }

    @Test
    fun `implied rows should supply premises without authorizing factor removal`() {
        val first = eq(intArrayOf(1, 1), intArrayOf(0, 1), 3)
        val second = eq(intArrayOf(1, 1), intArrayOf(1, 2), 4)
        val sum = eq(intArrayOf(1, 2, 1), intArrayOf(0, 1, 2), 7)
        val premises = first.linearRows + second.linearRows
        for (form in listOf(LinearForm.Conjunction(premises), LinearForm.Relaxation(premises))) {
            val factor = object : Factor by sum {
                override val linearForm: LinearForm = form
            }
            val p = problem(factor, sum)

            assertEquals(listOf(1), dropped(p).droppedIndices.toList())
        }
    }

    @Test
    fun `one redundant equality should not authorize dropping its multi-row factor`() {
        val first = eq(intArrayOf(1, 1), intArrayOf(0, 1), 3)
        val second = eq(intArrayOf(1, 1), intArrayOf(1, 2), 4)
        val sum = eq(intArrayOf(1, 2, 1), intArrayOf(0, 1, 2), 7)
        val extra = eq(intArrayOf(1), intArrayOf(0), 1)
        val factor = object : Factor by sum {
            override val linearForm: LinearForm = LinearForm.Conjunction(sum.linearRows + extra.linearRows)
        }

        assertTrue(dropped(problem(first, second, factor)).isEmpty)
    }

    @Test
    fun `a redundant relaxation row should not authorize dropping its factor`() {
        val equality = eq(intArrayOf(1, 1), intArrayOf(0, 1), 3)
        val factor = object : Factor by equality {
            override val linearForm: LinearForm = LinearForm.Relaxation(equality.linearRows)
        }

        assertTrue(dropped(problem(equality, factor)).isEmpty)
    }

    @Test
    fun `alternative equalities should not be treated as simultaneous premises`() {
        val first = eq(intArrayOf(1), intArrayOf(0), 1)
        val second = eq(intArrayOf(1), intArrayOf(0), 2)
        val factor = object : Factor by first {
            override val linearForm: LinearForm = LinearForm.Disjunction(first.linearRows + second.linearRows)
        }

        val delta = dropped(problem(first, factor))

        assertTrue(delta.isEmpty)
        assertFalse(delta.infeasible)
    }

    @Test
    fun `reified equality declarations should not be unconditional premises`() {
        val equality = eq(intArrayOf(1), intArrayOf(0), 1)
        val reified = LinearRow.ofColumns(
            intArrayOf(0),
            intArrayOf(),
            eq(intArrayOf(1), intArrayOf(0), 2).linearRows.single().constants,
            LinearOp.EQ,
            activator = 0,
        )
        val factor = object : Factor by equality {
            override val linearForm: LinearForm = LinearForm.Conjunction(listOf(reified))
        }
        val p = Problem(1, 3, Array(3) { IntDomain(0, 10) }, listOf(equality, factor))

        val delta = dropped(p)

        assertTrue(delta.isEmpty)
        assertFalse(delta.infeasible)
    }

    @Test
    fun `a spent allowance should stop within a shared pivot bucket`() {
        val factors = List(16) { i -> eq(intArrayOf(1, 1), intArrayOf(0, i + 1), 1) }
        val p = Problem(0, 17, Array(17) { IntDomain(0, 1) }, factors)
        val budget = PresolveBudget(1)

        val delta = DependentEqualities.dropImplied(p, budget.orSpent(Cancellation.Never))

        assertTrue(delta.isEmpty)
        assertFalse(delta.infeasible)
        assertEquals(LATTICE_WORK_WEIGHT, budget.spent())
    }
}
