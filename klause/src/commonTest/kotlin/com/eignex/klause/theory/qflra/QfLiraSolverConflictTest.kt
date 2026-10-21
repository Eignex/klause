package com.eignex.klause.theory.qflra

import com.eignex.klause.factor.arithmetic.ComparisonClause
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
import com.eignex.klause.ir.TaggedLinearRow
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.constsOf
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.pipeline.componentPlan
import com.eignex.klause.solver.pipeline.search
import com.eignex.klause.solver.result.SmtStatsSink
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.RegisteredTheoryDecision
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchComponent
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
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QfLiraSolverConflictTest {
    @Test
    fun `a finite selector should force its open source row before Boolean completion`() {
        val open = Bits(2).also { it.set(1) }
        val source = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(1, 0), open, open),
            factors = arrayOf(
                ComparisonClause(intArrayOf(0, 1), arrayOf(LinearOp.NE, LinearOp.EQ), longArrayOf(0, 1)),
                ReifiedLinear(0, intArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 7),
            ),
        )
        source.componentPlan().search(source, emptyMap()).use { planned ->
            assertNotNull(planned.cp)
            val component = assertIs<ExactLiraSearchComponent>(planned.theory)
            val stats = SmtStatsSink()
            component.observeWith(stats)
            component.observeCompleteTraversal()
            val session = planned.session
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.IntEqual(0, 0L)))
            assertNull(session.boolValue(0))

            val result = session.push(SearchDecision.IntAtLeast(1, 2L))

            assertIs<ComponentResult.Conflict>(result)
            assertEquals(1L, stats.snapshot().completeSearchStages?.get("FIXED_SELECTOR_DISJUNCT_IMPLIED"))
            assertEquals(0L, stats.snapshot().completeSearchLinearChecks)
        }
    }

    @Test
    fun `a forced source row should explain both shared fixing sides`() {
        val source = Problem(numBoolVars = 1, intBounds = openBounds(2),
            factors = arrayOf(ComparisonClause(intArrayOf(0, 1), arrayOf(LinearOp.NE, LinearOp.EQ),
                longArrayOf(0, 1))))
        ExactLiraSearchComponent(source).use { component ->
            val received = ArrayList<RegisteredTheoryDecision>()
            val receiver = object : SearchComponent by component {
                override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
                    if (decision is SearchDecision.Theory) {
                        received += assertIs<RegisteredTheoryDecision>(decision.decision)
                    }
                    return component.assert(decision, context)
                }
            }
            val session = SearchSession(listOf(receiver), atoms = SearchAtomRegistry(1))
            assertIs<ComponentResult.Consistent>(session.initialize())
            val lower = SearchDecision.IntAtLeast(0, 0L)
            val upper = SearchDecision.IntAtMost(0, 0L)
            assertIs<ComponentResult.Consistent>(session.push(lower))

            assertIs<ComponentResult.Consistent>(session.push(upper))

            val forced = received.single()
            val reason = assertNotNull(session.reasonFor(Lit.variable(forced.literal)))
            assertEquals(setOf(forced.literal, assertNotNull(session.atomLiteral(lower)) xor 1,
                assertNotNull(session.atomLiteral(upper)) xor 1), reason.literals.toSet())
            assertNull(session.boolValue(0))
        }
    }

    @Test
    fun `a different selector sibling should retract the old forced row`() {
        val source = Problem(numBoolVars = 1, intBounds = openBounds(2), factors = arrayOf(
            ComparisonClause(intArrayOf(0, 1), arrayOf(LinearOp.NE, LinearOp.EQ), longArrayOf(0, 1)),
            ComparisonClause(intArrayOf(0, 1), arrayOf(LinearOp.NE, LinearOp.EQ), longArrayOf(1, 2)),
        ))
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(1))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.IntEqual(0, 0L)))
            assertIs<ComponentResult.Conflict>(session.push(SearchDecision.IntAtLeast(1, 2L)))
            session.popTo(0)

            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.IntEqual(0, 1L)))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.IntAtLeast(1, 2L)))
            session.popTo(1)
            assertIs<ComponentResult.Conflict>(session.push(SearchDecision.IntAtMost(1, 1L)))
        }
    }

    @Test
    fun `an active opposite row should conflict with both selector fixing premises`() {
        val source = Problem(numBoolVars = 1, intBounds = openBounds(2),
            factors = arrayOf(ComparisonClause(intArrayOf(0, 1), arrayOf(LinearOp.NE, LinearOp.EQ),
                longArrayOf(0, 1))))
        ExactLiraSearchComponent(source).use { component ->
            val received = ArrayList<RegisteredTheoryDecision>()
            val receiver = object : SearchComponent by component {
                override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
                    if (decision is SearchDecision.Theory) {
                        received += assertIs<RegisteredTheoryDecision>(decision.decision)
                    }
                    return component.assert(decision, context)
                }
            }
            val session = SearchSession(listOf(receiver), atoms = SearchAtomRegistry(1))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.IntEqual(0, 0L)))
            val forced = received.single()
            session.popTo(0)
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(forced.literal xor 1)))
            val lower = SearchDecision.IntAtLeast(0, 0L)
            val upper = SearchDecision.IntAtMost(0, 0L)
            assertIs<ComponentResult.Consistent>(session.push(lower))

            val conflict = assertIs<ComponentResult.Conflict>(session.push(upper))

            assertEquals(setOf(forced.literal, assertNotNull(session.atomLiteral(lower)) xor 1,
                assertNotNull(session.atomLiteral(upper)) xor 1), assertNotNull(conflict.explanation).literals.toSet())
        }
    }

    @Test
    fun `full completion should reuse a forced row name and validate the original source`() {
        val source = Problem(numBoolVars = 1, intBounds = openBounds(2),
            factors = arrayOf(ComparisonClause(intArrayOf(0, 1), arrayOf(LinearOp.NE, LinearOp.EQ),
                longArrayOf(0, 1))))
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(1, maxAtoms = 4))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.IntEqual(0, 0L)))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(0)))

            val result = assertIs<SearchResult.Satisfied>(session.solve(1))

            val point = assertNotNull(result.model.valueOf<ExactLiraAssignment>(component))
            assertEquals(listOf(BIG_ZERO, BIG_ONE), point.ints.toList())
            assertEquals(listOf(true), point.bools.toList())
        }
    }

    @Test
    fun `a full atom registry should withhold an optional forced row without stopping the prefix`() {
        val source = Problem(numBoolVars = 1, intBounds = openBounds(2),
            factors = arrayOf(ComparisonClause(intArrayOf(0, 1), arrayOf(LinearOp.NE, LinearOp.EQ),
                longArrayOf(0, 1))))
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(1, maxAtoms = 2))
            assertIs<ComponentResult.Consistent>(session.initialize())

            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.IntEqual(0, 0L)))

            assertIs<ComponentResult.Consistent>(session.propagate())
            assertNull(session.boolValue(0))
            assertIs<ComponentCheck.Indeterminate>(component.check(session))
        }
    }

    @Test
    fun `forced scalar disjuncts should preserve their source bound polarity`() {
        for ((relation, violation) in listOf(LinearOp.LE to SearchDecision.IntAtLeast(1, 3L),
            LinearOp.GE to SearchDecision.IntAtMost(1, 1L))) {
            val source = Problem(numBoolVars = 1, intBounds = openBounds(2),
                factors = arrayOf(ComparisonClause(intArrayOf(0, 1), arrayOf(LinearOp.NE, relation),
                    longArrayOf(0, 2))))
            ExactLiraSearchComponent(source).use { component ->
                val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(1))
                assertIs<ComponentResult.Consistent>(session.initialize())
                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.IntEqual(0, 0L)))

                assertIs<ComponentResult.Conflict>(session.push(violation))
            }
        }
    }

    @Test
    fun `a forced disjunct with unresolved Boolean terms should wait for their actual values`() {
        val declaration = object : Factor by Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0) {
            override val linearForm: LinearForm = LinearForm.Disjunction(listOf(
                TaggedLinearRow(intArrayOf(Term.ofIntVar(0)), IntegerConstants(constsOf(longArrayOf(1)), 0L),
                    LinearOp.NE),
                TaggedLinearRow(intArrayOf(Term.ofLit(Lit.make(0, true))),
                    IntegerConstants(constsOf(longArrayOf(1)), 1L), LinearOp.EQ),
            ))
        }
        val source = Problem(numBoolVars = 2, intBounds = openBounds(), factors = arrayOf(declaration))
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(2))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.IntEqual(0, 0L)))
            assertNull(session.boolValue(0))

            assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(1)))
            assertNull(session.boolValue(1))
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
