package com.eignex.klause.presolve.linear

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.RealConstants
import com.eignex.klause.ir.RealConsts
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.ir.TaggedLinearRow
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.WideConstants
import com.eignex.klause.ir.WideConsts
import com.eignex.klause.ir.linearRows
import com.eignex.klause.lp.lattice.LATTICE_WORK_WEIGHT
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolvePass
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.shl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DependentEqualitiesTest {
    private fun exact(refs: IntArray, coefficients: List<BigFraction>, bound: BigFraction) =
        ExactLinear.of(refs, coefficients, LinearOp.EQ, bound)

    private fun realProblem(factors: List<Factor>, integers: Int = 0) = Problem(
        0, integers, Array(integers) { IntDomain(0, 10) }, factors,
        numRealVars = 3, realLower = DoubleArray(3) { Double.NEGATIVE_INFINITY },
        realUpper = DoubleArray(3) { Double.POSITIVE_INFINITY },
    )

    private fun eq(coeffs: IntArray, vars: IntArray, bound: Int) = Linear(coeffs, vars, LinearOp.EQ, bound)

    private fun problem(vararg factors: Factor) = Problem(0, 3, Array(3) { IntDomain(0, 10) }, factors.toList())

    private fun dropped(p: Problem) = DependentEqualities.dropImplied(p, Cancellation.Never)

    @Test
    fun `pure real blocks should drop dependent equalities at exact fractional and wide scales`() {
        val refs = IntArray(3, Term::ofRealVar)
        for (scale in listOf(BigFraction.ONE, BigFraction.of(bigIntOf(1), bigIntOf(3)), BigFraction.of(bigIntOf(1) shl 90, BIG_ONE))) {
            val rows = listOf(
                exact(refs.copyOfRange(0, 2), listOf(scale, scale), scale),
                exact(refs.copyOfRange(1, 3), listOf(scale, scale), scale * BigFraction.ofLong(2)),
                exact(intArrayOf(refs[0], refs[2]), listOf(scale, scale.negated()), scale.negated()),
            )
            val p = realProblem(rows)

            assertTrue(PresolvePass.DROP_DEPENDENT_EQUALITIES.namespaceEligible(p))
            assertEquals(listOf(2), dropped(p).droppedIndices.toList())
        }
    }

    @Test
    fun `pure real equality blocks should refute incompatible bounds`() {
        val one = BigFraction.ONE
        val rows = listOf(
            exact(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1)), listOf(one, one), one),
            exact(intArrayOf(Term.ofRealVar(1), Term.ofRealVar(2)), listOf(one, one), BigFraction.ofLong(2)),
            exact(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(2)), listOf(one, one.negated()), BigFraction.ZERO),
        )

        assertTrue(dropped(realProblem(rows)).infeasible)
    }

    @Test
    fun `integer and real coordinates with the same id should stay independent`() {
        val rows = listOf(
            exact(intArrayOf(Term.ofIntVar(0), Term.ofRealVar(0)), List(2) { BigFraction.ONE }, BigFraction.ONE),
            exact(intArrayOf(Term.ofIntVar(0), Term.ofRealVar(0)),
                listOf(BigFraction.ONE, BigFraction.ONE.negated()), BigFraction.ZERO),
        )

        assertTrue(dropped(realProblem(rows, integers = 1)).isEmpty)
    }

    @Test
    fun `binary64 fractional rows should use exact rational cancellation`() {
        val rows = listOf(
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(0.5, 0.25), LinearOp.EQ, 0.75),
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(1, 2), doubleArrayOf(0.25, 0.5), LinearOp.EQ, 1.25),
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1, 2),
                doubleArrayOf(0.5, 0.5, 0.5), LinearOp.EQ, 2.0),
        )

        assertEquals(listOf(2), dropped(realProblem(rows)).droppedIndices.toList())
    }

    @Test
    fun `wide repeated coefficients should merge without signed word overflow`() {
        val wide = bigIntOf(1) shl 80
        val row = TaggedLinearRow(intArrayOf(Term.ofIntVar(0), Term.ofIntVar(0)),
            WideConstants(WideConsts(arrayOf(wide, wide)), wide shl 1), LinearOp.EQ)
        val factor = object : Factor by eq(intArrayOf(1), intArrayOf(0), 1) {
            override val linearForm = LinearForm.Conjunction(listOf(row))
        }

        assertEquals(listOf(1), dropped(problem(eq(intArrayOf(1), intArrayOf(0), 1), factor)).droppedIndices.toList())
    }

    @Test
    fun `cancelled coordinates should expose a constant contradiction`() {
        val row = TaggedLinearRow(intArrayOf(Term.ofIntVar(0), Term.ofIntVar(0)),
            RealConstants(RealConsts(doubleArrayOf(0.5, -0.5)), RealConsts(doubleArrayOf()), 0.25, false), LinearOp.EQ)
        val factor = object : Factor by eq(intArrayOf(1), intArrayOf(0), 1) {
            override val linearForm = LinearForm.Conjunction(listOf(row))
        }

        assertTrue(dropped(problem(factor)).infeasible)
    }

    @Test
    fun `positive source tolerance should decline nominal row deductions`() {
        val p = Problem(0, 3, Array(3) { IntDomain(0, 10) }, listOf(
            eq(intArrayOf(1), intArrayOf(0), 1), eq(intArrayOf(1), intArrayOf(0), 2),
        ), semantics = SourceSemantics(SourceFormat.IR,
            feasibilityTolerance = SourceTolerance(absolute = SemanticRational.ofLong(1))).resolve())

        assertTrue(dropped(p).isEmpty)
    }

    @Test
    fun `a row exceeding the bit limit should remain outside the premise block`() {
        val scale = BigFraction.of(bigIntOf(1) shl 4096, BIG_ONE)
        val factor = exact(intArrayOf(Term.ofRealVar(0)), listOf(scale), scale)

        assertTrue(dropped(realProblem(listOf(factor, factor))).isEmpty)
    }

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
    fun `dependent binary partition equations should retain their finite search structure`() {
        for (scale in listOf(-1, 1, 2)) {
            val rows = listOf(
                eq(intArrayOf(scale, scale), intArrayOf(0, 1), scale),
                eq(intArrayOf(scale, scale), intArrayOf(2, 3), scale),
                eq(intArrayOf(scale, scale), intArrayOf(0, 2), scale),
                eq(intArrayOf(scale, scale), intArrayOf(1, 3), scale),
            )
            val p = Problem(0, 4, Array(4) { IntDomain(0, 1) }, rows)

            val delta = dropped(p)

            assertTrue(delta.isEmpty)
        }
    }

    @Test
    fun `binary partition equations should supply premises for other redundant equalities`() {
        val rows = listOf(
            eq(intArrayOf(1, 1), intArrayOf(0, 1), 1),
            eq(intArrayOf(1, 1), intArrayOf(2, 3), 1),
            eq(intArrayOf(1, 1, 1, 1), intArrayOf(0, 1, 2, 3), 2),
        )
        val p = Problem(0, 4, Array(4) { IntDomain(0, 1) }, rows)

        val delta = dropped(p)

        assertEquals(listOf(2), delta.droppedIndices.toList())
    }

    @Test
    fun `inconsistent binary partition equations should refute the model`() {
        val rows = listOf(
            eq(intArrayOf(1, 1), intArrayOf(0, 1), 1),
            eq(intArrayOf(1, 1), intArrayOf(0, 1), 0),
        )
        val p = Problem(0, 2, Array(2) { IntDomain(0, 1) }, rows)

        val delta = dropped(p)

        assertTrue(delta.infeasible)
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
