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
import com.eignex.klause.ir.linearRows
import com.eignex.klause.solver.result.SmtStatsSink
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SearchSolveParams
import com.eignex.klause.theory.TheoryCheck
import com.eignex.klause.theory.TheoryContext
import com.eignex.klause.theory.TheorySearchComponent
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExactLiraSearchComponentTest {

    @Test
    fun `repeated partial conflicts omit an irrelevant assertion after retraction`() {
        val source = partialModel()
        val model = Problem(3, intBounds = source.intBounds, factors = source.factors)
        val stats = SmtStatsSink()
        val component = ExactLiraSearchComponent(model).also { it.observeWith(stats) }
        val session = SearchSession(listOf(component))
        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, true))))

        repeat(3) {
            val conflict = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(0, true))))
            assertContentEquals(intArrayOf(Lit.make(0, false)), conflict.explanation?.literals)
            session.popTo(1)
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, false))))
            session.popTo(1)
        }

        assertEquals(3L, stats.snapshot().explainedConflicts)
        assertEquals(3L, stats.snapshot().conflictLiterals)
    }

    @Test
    fun `partial integer equality is relaxed before complete chronological refutation`() {
        val model = Problem(
            2,
            intBounds = openBounds(),
            factors = arrayOf(ReifiedLinear(0, intArrayOf(2), intArrayOf(0), LinearOp.EQ, 1)),
        )
        val session = SearchSession(listOf(ExactLiraSearchComponent(model)))
        assertIs<ComponentResult.Consistent>(session.initialize())

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, true))))
        assertIs<SearchResult.Exhausted>(session.solve(2))
    }

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
    fun `partial relaxation conflict records its explanation without a private exact search`() {
        val stats = SmtStatsSink()
        val component = ExactLiraSearchComponent(partialModel()).also { it.observeWith(stats) }
        val session = SearchSession(listOf(component))

        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(0, positive = true))))

        val snapshot = stats.snapshot()
        assertEquals(0L, snapshot.privateChecks)
        assertTrue(requireNotNull(component.lpMetrics).preparationAttempts > 0L)
        assertEquals(1L, snapshot.conflicts)
        assertEquals(1L, snapshot.explainedConflicts)
        assertEquals(1L, snapshot.conflictLiterals)
    }

    @Test
    fun `theory adapter forwards exact LIRA telemetry`() {
        val model = Problem(
            numBoolVars = 0,
            intBounds = openBounds(),
            factors = arrayOf(Linear(intArrayOf(2), intArrayOf(0), LinearOp.EQ, 1)),
        )
        val stats = SmtStatsSink()
        val component = TheorySearchComponent(ExactLiraSolver(model)).also { it.observeWith(stats) }

        val result = SearchSession(listOf(component)).initialize()

        assertIs<ComponentResult.Conflict>(result)
        val snapshot = stats.snapshot()
        assertTrue(snapshot.privateChecks > 0L)
        assertEquals(1L, snapshot.unexplainedConflicts)
        assertEquals(0L, snapshot.explainedConflicts)
    }

    @Test
    fun `real disjunction search reports strict witness telemetry`() {
        val source = Linear(
            intVars = intArrayOf(),
            intCoeffs = doubleArrayOf(),
            realVars = intArrayOf(0),
            realCoeffs = doubleArrayOf(1.0),
            op = LinearOp.LE,
            bound = 0.0,
            strict = true,
        )
        val factor = object : Factor by source {
            override val linearForm: LinearForm = LinearForm.Disjunction(source.linearRows)
        }
        val model = Problem(
            numBoolVars = 0,
            intBounds = openBounds(0),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
            factors = arrayOf(factor),
        )
        val stats = SmtStatsSink()
        val solver = ExactLraSolver(model).also { it.observeWith(stats) }

        val result = solver.check(booleanArrayOf(), exactContext())

        assertIs<TheoryCheck.Sat<ExactLraAssignment>>(result)
        val snapshot = stats.snapshot()
        assertEquals(1L, snapshot.witnessCandidates)
        assertEquals(1L, snapshot.witnessAccepted)
        assertEquals(1L, snapshot.strictWitnessAccepted)
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
        val session = SearchSession(listOf(ExactLiraSearchComponent(model)), maxChecks = 6)

        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, positive = true))))

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(2, true))))
        assertIs<SearchResult.Exhausted>(
            session.solve(3, SearchSolveParams(maxDecisions = 0)),
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
