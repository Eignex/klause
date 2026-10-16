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
import com.eignex.klause.ir.RealConstants
import com.eignex.klause.ir.RealConsts
import com.eignex.klause.ir.TaggedLinearRow
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.constsOf
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.result.SmtStatsSink
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
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QfLiraSolverConflictTest {
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

    @Test
    fun `an asserted real threshold should imply its weaker comparison`() {
        val source = Problem(
            numBoolVars = 1,
            intBounds = openBounds(0),
            numRealVars = 2,
            realLower = DoubleArray(2) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY },
            factors = arrayOf(
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(2.0, 1.0), LinearOp.LE, 0.0),
                ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1),
                    doubleArrayOf(2.0, 1.0), LinearOp.LE, 1.0),
            ),
        )
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(listOf(component))

            assertIs<ComponentResult.Consistent>(session.initialize())

            assertEquals(true, session.boolValue(0))
            assertContentEquals(intArrayOf(Lit.make(0, true)), assertNotNull(session.reasonFor(0)).literals)
            assertIs<SearchResult.Satisfied>(session.solve(0))
        }
    }

    @Test
    fun `negative expression scales should retain the implied literal polarity`() {
        for (operator in listOf(LinearOp.LE, LinearOp.GE)) {
            val source = Problem(
                numBoolVars = 1,
                intBounds = openBounds(0),
                numRealVars = 2,
                realLower = DoubleArray(2) { Double.NEGATIVE_INFINITY },
                realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY },
                factors = arrayOf(
                    Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1),
                        doubleArrayOf(2.0, 1.0), LinearOp.LE, 4.0),
                    ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1),
                        doubleArrayOf(-4.0, -2.0), operator, -10.0),
                ),
            )
            ExactLiraSearchComponent(source).use { component ->
                val session = SearchSession(listOf(component))

                assertIs<ComponentResult.Consistent>(session.initialize())

                val expected = operator == LinearOp.GE
                assertEquals(expected, session.boolValue(0))
                assertContentEquals(intArrayOf(Lit.make(0, expected)), assertNotNull(session.reasonFor(0)).literals)
            }
        }
    }

    @Test
    fun `equal threshold endpoints should retain source strictness`() {
        val cases = listOf(
            Triple(false, LinearOp.LE, true) to null,
            Triple(true, LinearOp.LE, true) to true,
            Triple(false, LinearOp.GE, true) to false,
            Triple(true, LinearOp.GE, false) to false,
            Triple(false, LinearOp.GE, false) to null,
        )
        for ((input, expected) in cases) {
            val (sourceStrict, operator, targetStrict) = input
            val source = Problem(
                numBoolVars = 1,
                intBounds = openBounds(0),
                numRealVars = 2,
                realLower = DoubleArray(2) { Double.NEGATIVE_INFINITY },
                realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY },
                factors = arrayOf(
                    Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1),
                        doubleArrayOf(2.0, 1.0), LinearOp.LE, 0.0, strict = sourceStrict),
                    ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1),
                        doubleArrayOf(2.0, 1.0), operator, 0.0, strict = targetStrict),
                ),
            )
            ExactLiraSearchComponent(source).use { component ->
                val session = SearchSession(listOf(component))

                assertIs<ComponentResult.Consistent>(session.initialize())

                assertEquals(expected, session.boolValue(0))
            }
        }
    }

    @Test
    fun `a real threshold implication should retract with its actual antecedent`() {
        val source = Problem(
            numBoolVars = 2,
            intBounds = openBounds(0),
            numRealVars = 2,
            realLower = DoubleArray(2) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY },
            factors = arrayOf(
                ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1),
                    doubleArrayOf(2.0, 1.0), LinearOp.LE, 0.0),
                ReifiedRealLinear(1, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1),
                    doubleArrayOf(2.0, 1.0), LinearOp.LE, 1.0),
            ),
        )
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(listOf(component))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
            assertEquals(true, session.boolValue(1))
            assertContentEquals(intArrayOf(Lit.make(0, false), Lit.make(1, true)).sortedArray(),
                assertNotNull(session.reasonFor(1)).literals.sortedArray())
            session.popTo(0)
            assertIs<ComponentResult.Consistent>(session.propagate())
            assertNull(session.boolValue(1))

            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, false))))

            assertNull(session.boolValue(1))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, false))))
            val point = assertNotNull(assertIs<SearchResult.Satisfied>(session.solve(0)).model
                .valueOf<ExactLraAssignment>(component)).reals
            assertTrue(point[0] * BigFraction.ofLong(2) + point[1] > BigFraction.ONE)
        }
    }

    @Test
    fun `an unavailable threshold antecedent should withhold a Boolean implication`() {
        val source = Problem(
            numBoolVars = 2,
            intBounds = openBounds(0),
            numRealVars = 2,
            realLower = DoubleArray(2) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY },
            factors = arrayOf(
                ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1),
                    doubleArrayOf(2.0, 1.0), LinearOp.LE, 0.0),
                ReifiedRealLinear(1, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1),
                    doubleArrayOf(2.0, 1.0), LinearOp.LE, 1.0),
            ),
        )
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(2))
        val context = object : SearchContext by session {
            override fun atomLiteral(decision: SearchDecision): Int? =
                if (decision == SearchDecision.Bool(Lit.make(0, true))) null else session.atomLiteral(decision)
        }
        ExactLiraSearchComponent(source).use { component ->
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(component.initialize(context))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
            assertIs<ComponentResult.Consistent>(component.assert(SearchDecision.Bool(Lit.make(0, true)), context))

            assertIs<ComponentResult.Consistent>(component.propagate(context))

            assertNull(session.boolValue(1))
        }
    }

    @Test
    fun `unknown Boolean coefficients should withhold real threshold implications`() {
        for (boolean in listOf(0, 1)) {
            val row = object : Factor by ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1),
                doubleArrayOf(2.0, 1.0), LinearOp.LE, 0.0) {
                override val linearForm = LinearForm.Conjunction(listOf(TaggedLinearRow(
                    intArrayOf(Term.ofLit(Lit.make(boolean, true)), Term.ofRealVar(0), Term.ofRealVar(1)),
                    RealConstants(RealConsts(doubleArrayOf(1.0)), RealConsts(doubleArrayOf(2.0, 1.0)), 0.0, false),
                    LinearOp.LE,
                    activator = 0,
                )))
            }
            val source = Problem(
                numBoolVars = 2,
                intBounds = openBounds(0),
                numRealVars = 2,
                realLower = DoubleArray(2) { Double.NEGATIVE_INFINITY },
                realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY },
                factors = arrayOf(
                    Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1),
                        doubleArrayOf(2.0, 1.0), LinearOp.EQ, 0.0),
                    row,
                ),
            )
            ExactLiraSearchComponent(source).use { component ->
                val session = SearchSession(listOf(component))

                assertIs<ComponentResult.Consistent>(session.initialize())

                assertNull(session.boolValue(0))
                assertNull(session.boolValue(1))
            }
        }
    }

    @Test
    fun `an unrelated real expression should retain its open Boolean alternatives`() {
        val source = Problem(
            numBoolVars = 1,
            intBounds = openBounds(0),
            numRealVars = 2,
            realLower = DoubleArray(2) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY },
            factors = arrayOf(
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(2.0, 1.0), LinearOp.LE, 0.0),
                ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1),
                    doubleArrayOf(3.0, 1.0), LinearOp.LE, 0.0),
            ),
        )
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(listOf(component))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertNull(session.boolValue(0))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, false))))

            val result = assertIs<SearchResult.Satisfied>(session.solve(0))

            val point = assertNotNull(result.model.valueOf<ExactLraAssignment>(component)).reals
            assertTrue(point[0] * BigFraction.ofLong(2) + point[1] <= BigFraction.ZERO)
            assertTrue(point[0] * BigFraction.ofLong(3) + point[1] > BigFraction.ZERO)
        }
    }

    @Test
    fun `a stopped real threshold operation should withhold its consequent`() {
        var stopped = false
        val source = Problem(
            numBoolVars = 2,
            intBounds = openBounds(0),
            numRealVars = 2,
            realLower = DoubleArray(2) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY },
            factors = arrayOf(
                ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1),
                    doubleArrayOf(2.0, 1.0), LinearOp.LE, 0.0),
                ReifiedRealLinear(1, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1),
                    doubleArrayOf(2.0, 1.0), LinearOp.LE, 1.0),
            ),
        )
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(listOf(component), cancellation = Cancellation { stopped })
            assertIs<ComponentResult.Consistent>(session.initialize())
            stopped = true

            assertIs<ComponentResult.Indeterminate>(session.push(SearchDecision.Bool(Lit.make(0, true))))

            assertNull(session.boolValue(1))
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
