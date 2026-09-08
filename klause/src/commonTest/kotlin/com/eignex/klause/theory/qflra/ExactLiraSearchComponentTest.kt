package com.eignex.klause.theory.qflra

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.Increasing
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.RealConstants
import com.eignex.klause.ir.RealConsts
import com.eignex.klause.ir.TaggedLinearRow
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.UnitConsts
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.theory.TheoryCheck
import com.eignex.klause.theory.TheoryContext
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ExactLiraSearchComponentTest {

    @Test
    fun `a clause-only Boolean decision does not spend an arithmetic check`() {
        val model = Problem(
            numBoolVars = 3,
            intBounds = openBounds(2),
            factors = arrayOf(
                Clause(intArrayOf(Lit.make(0, true))),
                ReifiedLinear(2, intArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 1),
            ),
        )
        var checks = 0
        val context = object : SearchContext by SearchSession(emptyList()) {
            override fun consumeCheck(): Boolean {
                checks++
                return true
            }
        }
        val component = ExactLiraSearchComponent(model)
        component.initialize(context)

        component.assert(SearchDecision.Bool(Lit.make(0, true)), context)
        component.propagate(context)

        assertEquals(0, checks)
    }

    @Test
    fun `a Boolean term in an arithmetic row triggers partial propagation`() {
        val source = ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0)
        val factor = object : Factor by source {
            override val linearForm: LinearForm = LinearForm.Conjunction(
                listOf(
                    TaggedLinearRow(
                        intArrayOf(Term.ofLit(Lit.make(0, true)), Term.ofIntVar(0)),
                        IntegerConstants(UnitConsts(2), 0L),
                        LinearOp.LE,
                    ),
                ),
            )
        }
        val model = Problem(
            numBoolVars = 2,
            intBounds = openBounds(),
            factors = arrayOf(factor, Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0)),
        )
        val session = SearchSession(listOf(ExactLiraSearchComponent(model)))
        session.initialize()

        assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(0, true))))
    }

    @Test
    fun `decided reified row conflicts with unconditional rows and explains assigned literals`() {
        val component = ExactLiraSearchComponent(partialModel())
        val session = SearchSession(listOf(component))

        assertIs<ComponentResult.Consistent>(session.initialize())
        val conflict = assertIs<ComponentResult.Conflict>(
            session.push(SearchDecision.Bool(Lit.make(0, positive = true))),
        )

        assertContentEquals(intArrayOf(Lit.make(0, positive = false)), conflict.explanation?.literals)
        assertNull(session.model().valueOf<ExactLiraAssignment>(component))
    }

    @Test
    fun `undecided reified row is relaxed and retraction checks its decided complement`() {
        val session = SearchSession(listOf(ExactLiraSearchComponent(partialModel())))

        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(0, positive = true))))
        session.popTo(0)

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, positive = false))))
    }

    @Test
    fun `feasible partial check does not contribute a witness`() {
        val model = Problem(
            numBoolVars = 2,
            intBounds = openBounds(),
            factors = arrayOf(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 0),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 10),
            ),
        )
        val component = ExactLiraSearchComponent(model)
        val session = SearchSession(listOf(component))

        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Consistent>(
            session.push(SearchDecision.Bool(Lit.make(0, positive = true))),
        )

        assertIs<ComponentCheck.Indeterminate>(session.check())
        assertNull(session.model().valueOf<ExactLiraAssignment>(component))
    }

    @Test
    fun `partial check observes cancellation`() {
        val session = SearchSession(
            components = listOf(ExactLiraSearchComponent(partialModel())),
            cancellation = Cancellation { true },
        )

        assertIs<ComponentResult.Indeterminate>(session.initialize())
    }

    @Test
    fun `strict affine ray is refuted without exhausting the split budget`() {
        val model = Problem(
            numBoolVars = 3,
            intBounds = openBounds(2),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(2, -2), intArrayOf(0, 1), LinearOp.EQ, 1),
                ReifiedRealLinear(
                    aux = 1,
                    vars = intArrayOf(),
                    intCoeffs = doubleArrayOf(),
                    realVars = intArrayOf(0),
                    realCoeffs = doubleArrayOf(1.0),
                    op = LinearOp.GE,
                    bound = 0.0,
                    strict = true,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val session = SearchSession(listOf(ExactLiraSearchComponent(model)), maxChecks = 2)

        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, positive = true))))

        assertIs<ComponentResult.Conflict>(
            session.push(SearchDecision.Bool(Lit.make(0, positive = true))),
        )
    }

    @Test
    fun `exact solver rejects an unconditional integer equality beyond double precision`() {
        val model = Problem(
            numBoolVars = 0,
            intBounds = openBounds(),
            factors = arrayOf(
                Linear(longArrayOf(9_007_199_254_740_993L), intArrayOf(0), LinearOp.EQ, 9_007_199_254_740_992L),
            ),
        )
        assertIs<TheoryCheck.Infeasible>(ExactLiraSolver(model).check(BooleanArray(0), exactContext()))
    }

    @Test
    fun `exact solver rejects a decided reified integer equality beyond double precision`() {
        val model = Problem(
            numBoolVars = 1,
            intBounds = openBounds(),
            factors = arrayOf(
                ReifiedLinear(
                    0,
                    longArrayOf(9_007_199_254_740_993L),
                    intArrayOf(0),
                    LinearOp.EQ,
                    9_007_199_254_740_992L,
                ),
            ),
        )
        assertIs<TheoryCheck.Infeasible>(ExactLiraSolver(model).check(booleanArrayOf(true), exactContext()))
    }

    private fun partialModel(): Problem = Problem(
        numBoolVars = 2,
        intBounds = openBounds(),
        factors = arrayOf(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1),
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0),
        ),
    )

    private fun openBounds(size: Int = 1): IntBounds {
        val open = Bits(size).also { bits -> repeat(size, bits::set) }
        return IntBounds.fromModelBounds(LongArray(size), LongArray(size), open, open)
    }

    private fun exactContext(): TheoryContext = object : TheoryContext {
        override fun consumeCheck(): Boolean = true

        override fun cancelled(): Boolean = false
    }

    @Test
    fun `each disequality in one declared form gets its own branch direction`() {
        val declaration = object : Factor by Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1) {
            override val linearForm: LinearForm = LinearForm.Conjunction(
                listOf(
                    LinearRow.ofInts(intArrayOf(0), longArrayOf(1), LinearOp.NE, 0),
                    LinearRow.ofInts(intArrayOf(0), longArrayOf(1), LinearOp.NE, 1),
                    LinearRow.ofInts(intArrayOf(0), longArrayOf(1), LinearOp.GE, 0),
                    LinearRow.ofInts(intArrayOf(0), longArrayOf(1), LinearOp.LE, 1),
                ),
            )
        }
        val model = Problem(numBoolVars = 0, intBounds = openBounds(), factors = arrayOf(declaration))

        assertIs<TheoryCheck.Infeasible>(ExactLiraSolver(model).check(booleanArrayOf(), exactContext()))
    }

    @Test
    fun `an increasing chain participates in a general integer theory model`() {
        val model = Problem(
            numBoolVars = 0,
            intBounds = openBounds(3),
            factors = arrayOf(
                Increasing(intArrayOf(0, 1, 2), strict = true),
                Linear(intArrayOf(1, 2), intArrayOf(0, 2), LinearOp.EQ, 0),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 0),
            ),
        )

        assertIs<TheoryCheck.Infeasible>(ExactLiraSolver(model).check(booleanArrayOf(), exactContext()))
    }

    @Test
    fun `the pure real lane substitutes negative Boolean terms from a declared form`() {
        val source = ReifiedRealLinear(
            0,
            intArrayOf(),
            doubleArrayOf(),
            intArrayOf(0),
            doubleArrayOf(1.0),
            LinearOp.LE,
            0.0,
        )
        val declaration = object : Factor by source {
            override val linearForm: LinearForm = LinearForm.Conjunction(
                listOf(
                    TaggedLinearRow(
                        intArrayOf(Term.ofLit(Lit.make(0, false)), Term.ofRealVar(0)),
                        RealConstants(RealConsts(doubleArrayOf(2.0)), RealConsts(doubleArrayOf(1.0)), 1.0, false),
                        LinearOp.LE,
                    ),
                ),
            )
        }
        val model = Problem(
            numBoolVars = 1,
            intBounds = openBounds(0),
            factors = arrayOf(declaration),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )

        assertIs<TheoryCheck.Infeasible>(ExactLraSolver(model).check(booleanArrayOf(false), exactContext()))
        assertIs<TheoryCheck.Sat<ExactLraAssignment>>(ExactLraSolver(model).check(booleanArrayOf(true), exactContext()))
    }

    @Test
    fun `a fractional coefficient on an integer column is interpreted exactly`() {
        val model = Problem(
            numBoolVars = 1,
            intBounds = openBounds(),
            factors = arrayOf(
                ReifiedRealLinear(
                    0,
                    intArrayOf(0),
                    doubleArrayOf(0.5),
                    intArrayOf(),
                    doubleArrayOf(),
                    LinearOp.LE,
                    0.25,
                ),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
            ),
        )

        assertIs<TheoryCheck.Infeasible>(ExactLiraSolver(model).check(booleanArrayOf(true), exactContext()))
        assertIs<TheoryCheck.Sat<ExactLiraAssignment>>(
            ExactLiraSolver(model).check(booleanArrayOf(false), exactContext()),
        )
    }
}
