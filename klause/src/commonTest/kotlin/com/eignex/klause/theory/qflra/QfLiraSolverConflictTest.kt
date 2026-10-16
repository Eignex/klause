package com.eignex.klause.theory.qflra

import com.eignex.klause.factor.arithmetic.ComparisonClause
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.RealConstants
import com.eignex.klause.ir.RealConsts
import com.eignex.klause.ir.TaggedLinearRow
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.WideConstants
import com.eignex.klause.ir.WideConsts
import com.eignex.klause.ir.constsOf
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.result.SmtStatsSink
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchRealValue
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SourceBoundAtom
import com.eignex.klause.solver.search.SourceBoundTerm
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.plus
import com.eignex.klause.util.shl
import com.eignex.klause.util.unaryMinus
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QfLiraSolverConflictTest {
    @Test
    fun `source capture should reject oversized Boolean arithmetic before cancellation`() {
        val wide = BIG_ONE shl 4096
        val admitted = BIG_ONE shl 4095
        val cases = listOf(
            listOf(wide, -wide) to true,
            listOf(wide, -wide) to false,
            listOf(-admitted, -admitted, admitted, admitted) to true,
        )
        for ((coefficients, positive) in cases) {
            val references = IntArray(coefficients.size + 1) { index ->
                if (index == coefficients.size) Term.ofRealVar(1) else Term.ofLit(Lit.make(index + 1, true))
            }
            val constants = Array(coefficients.size + 1) { index ->
                if (index == coefficients.size) BIG_ONE else coefficients[index]
            }
            val row = object : Factor by ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(),
                intArrayOf(1), doubleArrayOf(1.0), LinearOp.LE, 0.0) {
                override val linearForm: LinearForm = LinearForm.Conjunction(listOf(TaggedLinearRow(
                    references, WideConstants(WideConsts(constants), BIG_ZERO), LinearOp.LE, activator = 0,
                )))
            }
            val source = Problem(
                numBoolVars = coefficients.size + 1,
                intBounds = openBounds(0),
                numRealVars = 2,
                realLower = DoubleArray(2) { Double.NEGATIVE_INFINITY },
                realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY },
                factors = arrayOf(
                    Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1),
                        doubleArrayOf(1.0, 2.0), LinearOp.EQ, 2.0),
                    Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1),
                        doubleArrayOf(1.0, -2.0), LinearOp.EQ, -2.0),
                    row,
                ),
            )
            val stats = SmtStatsSink()
            ExactLiraSearchComponent(source).use { component ->
                component.observeWith(stats)
                component.observeCompleteTraversal()
                val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(source.numBoolVars))
                assertIs<ComponentResult.Consistent>(session.initialize())
                for (variable in 1 until source.numBoolVars) {
                    val decision = SearchDecision.Bool(Lit.make(variable, positive))
                    assertIs<ComponentResult.Consistent>(session.push(decision))
                }

                assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(0)))

                assertEquals(1L, stats.snapshot().completeSearchStages?.get("SOURCE_EQUALITY_CAPTURE_DECLINED"))
                assertEquals(0L, stats.snapshot().completeSearchStages?.get("SOURCE_EQUALITY_CONFLICT") ?: 0L)
            }
        }
    }

    @Test
    fun `source capture should decline an oversized antecedent row before folding`() {
        val references = IntArray(4096) { index ->
            if (index == 4095) Term.ofRealVar(1) else Term.ofLit(Lit.make(index + 1, true))
        }
        val row = object : Factor by ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(),
            intArrayOf(1), doubleArrayOf(1.0), LinearOp.LE, 0.0) {
            override val linearForm: LinearForm = LinearForm.Conjunction(listOf(TaggedLinearRow(
                references, IntegerConstants(constsOf(LongArray(4096) { 1L }), 0L), LinearOp.LE, activator = 0,
            )))
        }
        val source = Problem(
            numBoolVars = 4096,
            intBounds = openBounds(0),
            numRealVars = 2,
            realLower = DoubleArray(2) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY },
            factors = arrayOf(
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(1.0, 2.0), LinearOp.EQ, 2.0),
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(1.0, -2.0), LinearOp.EQ, -2.0),
                row,
            ),
        )
        val stats = SmtStatsSink()
        ExactLiraSearchComponent(source).use { component ->
            component.observeWith(stats)
            component.observeCompleteTraversal()
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(4096))

            assertIs<ComponentResult.Consistent>(session.initialize())

            assertEquals(1L, stats.snapshot().completeSearchStages?.get("SOURCE_EQUALITY_CAPTURE_DECLINED"))
            assertEquals(0L, stats.snapshot().completeSearchStages?.get("SOURCE_EQUALITY_CONFLICT") ?: 0L)
        }
    }

    @Test
    fun `a stop during bounded Boolean capture should allow a live retry`() {
        val wide = BIG_ONE shl 4095
        val row = object : Factor by ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(),
            intArrayOf(1), doubleArrayOf(1.0), LinearOp.LE, 0.0) {
            override val linearForm: LinearForm = LinearForm.Conjunction(listOf(TaggedLinearRow(
                intArrayOf(Term.ofLit(2), Term.ofLit(4), Term.ofRealVar(1)),
                WideConstants(WideConsts(arrayOf(wide, -wide, BIG_ONE)), BIG_ZERO), LinearOp.LE, activator = 0,
            )))
        }
        val source = Problem(
            numBoolVars = 3,
            intBounds = openBounds(0),
            numRealVars = 2,
            realLower = DoubleArray(2) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY },
            factors = arrayOf(
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(1.0, 2.0), LinearOp.EQ, 2.0),
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(1.0, -2.0), LinearOp.EQ, -2.0),
                row,
            ),
        )
        var armed = false
        var stopped = false
        val stats = SmtStatsSink()
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(3))
        ExactLiraSearchComponent(source).use { component ->
            component.observeWith(stats)
            component.observeCompleteTraversal()
            component.useOperationAllowance { parent ->
                object : Cancellation {
                    override fun isCancelled(): Boolean = stopped || parent()
                    override fun deadline() = parent.deadline()
                    override fun workMeter() = parent.workMeter()
                    override fun charge(units: Long) {
                        parent.charge(units)
                        if (armed && units == 65L) stopped = true
                    }
                }
            }
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(component.initialize(session))
            for (variable in listOf(1, 2, 0)) {
                val decision = SearchDecision.Bool(Lit.make(variable, true))
                assertIs<ComponentResult.Consistent>(session.push(decision))
                assertIs<ComponentResult.Consistent>(component.assert(decision, session))
                if (variable != 0) assertIs<ComponentResult.Consistent>(component.propagate(session))
            }
            armed = true

            assertIs<ComponentResult.Indeterminate>(component.propagate(session))

            assertTrue(stopped)
            assertEquals(0L, stats.snapshot().completeSearchStages?.get("SOURCE_EQUALITY_CONFLICT") ?: 0L)
            armed = false
            stopped = false
            val conflict = assertIs<ComponentResult.Conflict>(component.propagate(session))
            assertEquals(setOf(1, 3, 5), assertNotNull(conflict.explanation).literals.toSet())
        }
    }

    @Test
    fun `source equalities should refute an incompatible row without a numeric check`() {
        val source = Problem(
            numBoolVars = 0,
            intBounds = openBounds(0),
            numRealVars = 2,
            realLower = DoubleArray(2) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY },
            factors = arrayOf(
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(1.0, 2.0), LinearOp.EQ, 1.0),
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(1.0, -2.0), LinearOp.EQ, 1.0),
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(1), doubleArrayOf(1.0), LinearOp.GE, 1.0),
            ),
        )
        val stats = SmtStatsSink()
        ExactLiraSearchComponent(source).use { component ->
            component.observeWith(stats)
            component.observeCompleteTraversal()
            val session = SearchSession(listOf(component), maxChecks = 0)

            val conflict = assertIs<ComponentResult.Conflict>(session.initialize())

            assertTrue(assertNotNull(conflict.explanation).literals.isEmpty())
            assertEquals(0L, stats.snapshot().completeSearchLinearChecks)
            assertEquals(1L, stats.snapshot().completeSearchStages?.get("SOURCE_EQUALITY_CONFLICT"))
        }
    }

    @Test
    fun `an equality conflict should cite its guard and retract before a sibling`() {
        val source = Problem(
            numBoolVars = 1,
            intBounds = openBounds(0),
            numRealVars = 2,
            realLower = DoubleArray(2) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY },
            factors = arrayOf(
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(1.0, 2.0), LinearOp.EQ, 1.0),
                ExactLinear.of(
                    intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1)),
                    listOf(BigFraction.ONE, BigFraction.ofLong(-2)),
                    LinearOp.EQ, BigFraction.ONE, activator = 0,
                ),
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(1), doubleArrayOf(1.0), LinearOp.GE, 1.0),
            ),
        )
        val stats = SmtStatsSink()
        ExactLiraSearchComponent(source).use { component ->
            component.observeWith(stats)
            component.observeCompleteTraversal()
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(1))
            assertIs<ComponentResult.Consistent>(session.initialize())

            val conflict = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(0)))

            assertContentEquals(intArrayOf(1), assertNotNull(conflict.explanation).literals)
            assertEquals(1L, stats.snapshot().completeSearchStages?.get("SOURCE_EQUALITY_CONFLICT"))
            session.popTo(0)
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(1)))
        }
    }

    @Test
    fun `equality projection should wait for and cite a signed Boolean coefficient`() {
        val row = object : Factor by ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(),
            intArrayOf(1), doubleArrayOf(1.0), LinearOp.LE, 0.0) {
            override val linearForm: LinearForm = LinearForm.Conjunction(listOf(TaggedLinearRow(
                intArrayOf(Term.ofLit(Lit.make(1, false)), Term.ofRealVar(1)),
                RealConstants(RealConsts(doubleArrayOf(1.0)), RealConsts(doubleArrayOf(1.0)), 0.0, false),
                LinearOp.LE, activator = 0,
            )))
        }
        val source = Problem(
            numBoolVars = 2,
            intBounds = openBounds(0),
            numRealVars = 2,
            realLower = DoubleArray(2) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY },
            factors = arrayOf(
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(1.0, 2.0), LinearOp.EQ, 1.0),
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(1.0, -2.0), LinearOp.EQ, 1.0),
                row,
            ),
        )
        val stats = SmtStatsSink()
        ExactLiraSearchComponent(source).use { component ->
            component.observeWith(stats)
            component.observeCompleteTraversal()
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(2))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(0)))

            val conflict = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(3)))

            assertEquals(setOf(1, 2), assertNotNull(conflict.explanation).literals.toSet())
            assertEquals(1L, stats.snapshot().completeSearchStages?.get("SOURCE_EQUALITY_CONFLICT"))
        }
    }

    @Test
    fun `an unavailable equality guard should retain ordinary conflict handling`() {
        val source = Problem(
            numBoolVars = 1,
            intBounds = openBounds(0),
            numRealVars = 2,
            realLower = DoubleArray(2) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY },
            factors = arrayOf(
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(1.0, 2.0), LinearOp.EQ, 1.0),
                ExactLinear.of(
                    intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1)),
                    listOf(BigFraction.ONE, BigFraction.ofLong(-2)),
                    LinearOp.EQ, BigFraction.ONE, activator = 0,
                ),
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(1), doubleArrayOf(1.0), LinearOp.GE, 1.0),
            ),
        )
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(1))
        val context = object : SearchContext by session {
            override fun atomLiteral(decision: SearchDecision): Int? =
                if (decision == SearchDecision.Bool(0)) null else session.atomLiteral(decision)
        }
        val stats = SmtStatsSink()
        ExactLiraSearchComponent(source).use { component ->
            component.observeWith(stats)
            component.observeCompleteTraversal()
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(component.initialize(context))
            val decision = SearchDecision.Bool(0)
            assertIs<ComponentResult.Consistent>(session.push(decision))
            assertIs<ComponentResult.Consistent>(component.assert(decision, context))

            val conflict = assertIs<ComponentResult.Conflict>(component.propagate(context))

            assertNull(conflict.explanation)
            assertEquals(0L, stats.snapshot().completeSearchStages?.get("SOURCE_EQUALITY_CONFLICT") ?: 0L)
        }
    }

    @Test
    fun `a stop during equality explanation should allow a live retry`() {
        var stopped = false
        var armed = false
        val source = Problem(
            numBoolVars = 1,
            intBounds = openBounds(0),
            numRealVars = 2,
            realLower = DoubleArray(2) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY },
            factors = arrayOf(
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(1.0, 2.0), LinearOp.EQ, 1.0),
                ExactLinear.of(
                    intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1)),
                    listOf(BigFraction.ONE, BigFraction.ofLong(-2)),
                    LinearOp.EQ, BigFraction.ONE, activator = 0,
                ),
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(1), doubleArrayOf(1.0), LinearOp.GE, 1.0),
            ),
        )
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(1))
        val context = object : SearchContext by session {
            override fun atomLiteral(decision: SearchDecision): Int? {
                val literal = session.atomLiteral(decision)
                if (armed) stopped = true
                return literal
            }
        }
        val stats = SmtStatsSink()
        ExactLiraSearchComponent(source).use { component ->
            component.observeWith(stats)
            component.observeCompleteTraversal()
            component.useOperationAllowance { parent -> parent or Cancellation { stopped } }
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(component.initialize(context))
            val decision = SearchDecision.Bool(0)
            assertIs<ComponentResult.Consistent>(session.push(decision))
            assertIs<ComponentResult.Consistent>(component.assert(decision, context))
            armed = true
            assertIs<ComponentResult.Indeterminate>(component.propagate(context))
            assertIs<ComponentCheck.Indeterminate>(component.check(context))
            assertEquals(0L, stats.snapshot().completeSearchStages?.get("SOURCE_EQUALITY_CONFLICT") ?: 0L)
            armed = false
            stopped = false

            val conflict = assertIs<ComponentResult.Conflict>(component.propagate(context))

            assertContentEquals(intArrayOf(1), assertNotNull(conflict.explanation).literals)
            assertEquals(1L, stats.snapshot().completeSearchStages?.get("SOURCE_EQUALITY_CONFLICT"))
        }
    }

    @Test
    fun `a false source clause should conflict despite feasible real bounds`() {
        val model = Problem(
            numBoolVars = 1,
            intBounds = openBounds(0),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
            factors = arrayOf(Clause(intArrayOf(Lit.make(0, true)))),
        )
        ExactLiraSearchComponent(model).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(1))
            assertIs<ComponentResult.Consistent>(session.initialize())

            val result = session.push(SearchDecision.Bool(Lit.make(0, false)))

            assertIs<ComponentResult.Conflict>(result)
        }
    }

    @Test
    fun `compound disjunct conflicts should retain their Boolean names`() {
        for ((relation, values, fixed) in listOf(
            Triple(LinearOp.EQ, longArrayOf(0L, 1L), 2),
            Triple(LinearOp.NE, longArrayOf(0L, 0L), 0),
        )) {
            val model = Problem(
                numBoolVars = 0,
                intBounds = openBounds(),
                factors = arrayOf(
                    ComparisonClause(intArrayOf(0, 0), arrayOf(relation, relation), values),
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, fixed),
                ),
            )
            val stats = SmtStatsSink()
            ExactLiraSearchComponent(model).use { component ->
                component.observeWith(stats)
                val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(0))
                assertIs<ComponentResult.Consistent>(session.initialize())

                assertIs<SearchResult.Exhausted>(session.solve(0))

                assertTrue(stats.snapshot().explainedConflicts > 0L)
                assertEquals(0L, stats.snapshot().unexplainedConflicts)
            }
        }
    }

    @Test
    fun `a learned disjunct with Boolean terms should preserve its predicate after rollback`() {
        val rows = listOf(
            TaggedLinearRow(
                intArrayOf(Term.ofIntVar(0), Term.ofLit(Lit.make(0, true))),
                IntegerConstants(constsOf(longArrayOf(1, 1)), 1L),
                LinearOp.GE,
            ),
            TaggedLinearRow(
                intArrayOf(Term.ofIntVar(0), Term.ofLit(Lit.make(0, true))),
                IntegerConstants(constsOf(longArrayOf(1, -1)), 1L),
                LinearOp.GE,
            ),
        )
        val declaration = object : Factor by Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0) {
            override val linearForm: LinearForm = LinearForm.Disjunction(rows)
        }
        val model = Problem(
            numBoolVars = 1,
            intBounds = openBounds(),
            factors = arrayOf(declaration, Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0)),
        )
        ExactLiraSearchComponent(model).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(1))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, false))))
            val chosen = assertNotNull(component.nextBranch(session)).first()
            val conflict = assertIs<ComponentResult.Conflict>(session.push(chosen))
            val explanation = assertNotNull(conflict.explanation)
            assertEquals(setOf(0, assertNotNull(session.atomLiteral(chosen)) xor 1), explanation.literals.toSet())
            session.learn(explanation)
            session.popTo(0)

            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
            val result = assertIs<SearchResult.Satisfied>(session.solve(1))

            assertEquals(BIG_ZERO, assertNotNull(result.model.valueOf<ExactLiraAssignment>(component)).ints.single())
        }
    }

    @Test
    fun `a disequality row should cite the source side that directs it`() {
        val model = Problem(
            numBoolVars = 1,
            intBounds = openBounds(0),
            numRealVars = 1,
            realLower = doubleArrayOf(-1.0),
            realUpper = doubleArrayOf(1.0),
            factors = arrayOf(
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.NE, 0.0),
                ReifiedRealLinear(
                    0,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.LE,
                    0.0,
                ),
            ),
        )
        ExactLiraSearchComponent(model).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(1))
            assertIs<ComponentResult.Consistent>(session.initialize())
            val side = realSignSplit(session)
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Theory(side.positive)))

            val conflict = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(0, false))))

            assertEquals(setOf(0, side.positive.literal xor 1), assertNotNull(conflict.explanation).literals.toSet())
            session.popTo(1)
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
        }
    }

    @Test
    fun `an integer bound conflict should contain its shared witness`() {
        val model = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0L), longArrayOf(2L), null, null),
            factors = arrayOf(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 1)),
        )
        ExactLiraSearchComponent(model).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(1))
            assertIs<ComponentResult.Consistent>(session.initialize())
            val bound = SearchDecision.IntAtLeast(0, 2L)
            assertIs<ComponentResult.Consistent>(session.push(bound))

            val conflict = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(0)))

            assertEquals(
                setOf(1, assertNotNull(session.atomLiteral(bound)) xor 1),
                assertNotNull(conflict.explanation).literals.toSet(),
            )
        }
    }

    @Test
    fun `a deferred comparison should retain the shared integer bound that refutes it`() {
        val model = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0L, 0L), longArrayOf(10L, 10L), null, null),
            factors = arrayOf(
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1),
                ReifiedLinear(0, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0),
            ),
        )
        ExactLiraSearchComponent(model).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(1))
            assertIs<ComponentResult.Consistent>(session.initialize())
            val bound = SearchDecision.IntAtLeast(0, 4L)
            assertIs<ComponentResult.Consistent>(session.push(bound))

            val conflict = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(0)))

            assertEquals(
                setOf(1, assertNotNull(session.atomLiteral(bound)) xor 1),
                assertNotNull(conflict.explanation).literals.toSet(),
            )
        }
    }

    @Test
    fun `an extreme integer complement should retain arbitrary precision source meaning`() {
        val model = Problem(numBoolVars = 0, intBounds = openBounds(), factors = emptyArray())
        ExactLiraSearchComponent(model).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(0))
            assertIs<ComponentResult.Consistent>(session.initialize())
            val bound = SearchDecision.IntAtMost(0, Long.MAX_VALUE)
            assertIs<ComponentResult.Consistent>(session.push(bound))
            val literal = assertNotNull(session.atomLiteral(bound))
            session.popTo(0)

            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(literal xor 1)))
            val result = assertIs<SearchResult.Satisfied>(session.solve(0))

            assertEquals(
                bigIntOf(Long.MAX_VALUE) + BIG_ONE,
                assertNotNull(result.model.valueOf<ExactLiraAssignment>(component)).ints.single(),
            )
        }
    }

    private fun realSignSplit(session: SearchContext) = assertNotNull(
        SourceBoundAtom.rationalSplit(
            session,
            listOf(SourceBoundTerm(SearchRealValue(0), BigFraction.ONE)),
            BigFraction.ZERO,
        ),
    )

    private fun openBounds(size: Int = 1): IntBounds {
        val open = Bits(size).also { bits -> repeat(size, bits::set) }
        return IntBounds.fromModelBounds(LongArray(size), LongArray(size), open, open)
    }
}
