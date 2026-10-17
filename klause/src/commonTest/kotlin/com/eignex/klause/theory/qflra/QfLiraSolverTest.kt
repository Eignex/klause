package com.eignex.klause.theory.qflra

import com.eignex.klause.factor.arithmetic.ComparisonClause
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
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
import com.eignex.klause.ir.UnitConsts
import com.eignex.klause.ir.linearRows
import com.eignex.klause.model.PbOp
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchExplanation
import com.eignex.klause.solver.search.SearchIntValue
import com.eignex.klause.solver.search.SearchModel
import com.eignex.klause.solver.search.SearchModelKey
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SearchSolveParams
import com.eignex.klause.solver.search.SourceBoundAtom
import com.eignex.klause.solver.search.SourceBoundTerm
import com.eignex.klause.solver.search.contribute
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.parseBigInt
import com.eignex.klause.util.plus
import com.eignex.klause.util.times
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QfLiraSolverTest {
    @Test
    fun `wide Boolean source rows should retain both reification directions in exact arithmetic`() {
        val wide = parseBigInt("18446744073709551616")
        val factor = ReifiedPseudoBoolean(0, arrayOf(wide), intArrayOf(Lit.make(1, false)), PbOp.EQ, wide)
        val real = ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0),
            LinearOp.LE, 0.0)
        val problem = Problem(2, 0, emptyArray(), arrayOf(factor, real),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        for (indicator in listOf(false, true)) {
            val session = SearchSession(listOf(ExactLiraSearchComponent(problem)))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, indicator))))

            assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(1, indicator))))
        }
    }

    @Test
    fun `exact mixed witnesses should own their constructor and exported coordinates`() {
        val wide = parseBigInt("9223372036854775808")
        val third = BigFraction.ofLong(3).reciprocal()
        val bools = booleanArrayOf(true)
        val ints = arrayOf(wide)
        val reals = mutableListOf(third)
        val assignment = ExactLiraAssignment(bools, ints, reals)
        val copied = assignment.copy()

        bools[0] = false
        ints[0] = BIG_ZERO
        reals[0] = BigFraction.ZERO
        assignment.bools[0] = false
        assignment.ints[0] = BIG_ZERO
        copied.component1()[0] = false
        copied.component2()[0] = BIG_ZERO

        assertEquals(ExactLiraAssignment(booleanArrayOf(true), arrayOf(wide), listOf(third)), assignment)
        assertEquals(assignment, copied)
    }

    @Test
    fun `a clause-only Boolean decision should not spend an arithmetic check`() {
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
    fun `a Boolean term in an arithmetic row should trigger partial propagation`() {
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
    fun `decided reified row should conflict with unconditional rows and explain assigned literals`() {
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
    fun `ordinary source contradiction should need no disjunction name capacity`() {
        val model = Problem(
            numBoolVars = 0,
            intBounds = openBounds(),
            factors = arrayOf(
                ComparisonClause(intArrayOf(0, 0), arrayOf(LinearOp.NE, LinearOp.NE), longArrayOf(0, 1)),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 0),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1),
            ),
        )
        ExactLiraSearchComponent(model).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(0, maxAtoms = 0))

            session.initialize()

            assertIs<SearchResult.Exhausted>(session.solve(0))
        }
    }

    @Test
    fun `unnamed source disjunction should withhold a relaxation witness`() {
        val model = Problem(
            numBoolVars = 0,
            intBounds = openBounds(),
            factors = arrayOf(
                ComparisonClause(intArrayOf(0, 0), arrayOf(LinearOp.EQ, LinearOp.EQ), longArrayOf(1, 2)),
            ),
        )
        ExactLiraSearchComponent(model).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(0, maxAtoms = 0))
            assertIs<ComponentResult.Consistent>(session.initialize())

            val result = session.solve(0)

            assertIs<SearchResult.Indeterminate>(result)
        }
    }

    @Test
    fun `existing source predicate should complete a deferred disjunction witness`() {
        val model = Problem(
            numBoolVars = 0,
            intBounds = openBounds(),
            factors = arrayOf(
                ComparisonClause(intArrayOf(0, 0), arrayOf(LinearOp.LE, LinearOp.GE), longArrayOf(0, 2)),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 0),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2),
            ),
        )
        ExactLiraSearchComponent(model).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(0, maxAtoms = 2))
            assertIs<ComponentResult.Consistent>(session.initialize())
            val split = assertNotNull(
                SourceBoundAtom.integerSplit(
                    session,
                    listOf(SourceBoundTerm(SearchIntValue(0), BigFraction.ONE)),
                    BigFraction.ZERO,
                ),
            )
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Theory(split.positive)))

            val result = session.solve(0)

            val witness = assertNotNull(
                assertIs<SearchResult.Satisfied>(result).model
                    .valueOf<ExactLiraAssignment>(component),
            )
            assertEquals(BIG_ZERO, witness.intValue(0))
        }
    }

    @Test
    fun `a strict real disjunction should be decided through its atoms`() {
        for ((upper, sat) in listOf(0.5 to true, 1.0 to false)) {
            val below = strictRealBound(LinearOp.LE, 0.0)
            val above = strictRealBound(LinearOp.GE, upper)
            val clause = object : Factor by below {
                override val linearForm: LinearForm = LinearForm.Disjunction(below.linearRows + above.linearRows)
            }
            val model = Problem(
                numBoolVars = 0,
                intBounds = openBounds(0),
                numRealVars = 1,
                realLower = doubleArrayOf(0.0),
                realUpper = doubleArrayOf(1.0),
                factors = arrayOf(clause),
            )
            ExactLiraSearchComponent(model).use { component ->
                val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(0))
                assertIs<ComponentResult.Consistent>(session.initialize())

                val result = session.solve(0)

                if (sat) assertIs<SearchResult.Satisfied>(result) else assertIs<SearchResult.Exhausted>(result)
            }
        }
    }

    @Test
    fun `feasible partial check should not contribute a witness`() {
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
    fun `partial check should observe cancellation`() {
        val session = SearchSession(
            components = listOf(ExactLiraSearchComponent(partialModel())),
            cancellation = Cancellation { true },
        )

        assertIs<ComponentResult.Indeterminate>(session.initialize())
    }

    @Test
    fun `strict affine ray should be refuted without exhausting the split budget`() {
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
    fun `stopped exact source preparation should withhold the factory and retain its charge`() {
        var charged = 0L
        val meter = WorkMeter { charged += it }
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = charged >= 20L
            override fun workMeter(): WorkMeter = meter
        }

        assertNull(prepareExactLiraSource(partialModel(), stop))
        val stoppedCharge = charged
        assertTrue(stoppedCharge >= 20L)
        assertNull(prepareExactLiraSource(partialModel(), stop))
        assertEquals(stoppedCharge, charged)
    }

    @Test
    fun `prepared source should open fresh disjunction names after cancelled initialization`() {
        val source = comparisonClauseModel(xUpper = 8)
        var preparationExpired = false
        val prepared = assertNotNull(prepareExactLiraSource(source, Cancellation { preparationExpired }))
        preparationExpired = true
        prepared.open().use { interrupted ->
            val session = SearchSession(
                listOf(interrupted),
                cancellation = Cancellation { true },
                atoms = SearchAtomRegistry(0),
            )
            assertIs<ComponentResult.Indeterminate>(session.initialize())
        }

        prepared.open().use { fresh ->
            val session = SearchSession(listOf(fresh), atoms = SearchAtomRegistry(0))

            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<SearchResult.Satisfied>(session.solve(0))
        }
    }

    @Test
    fun `prepared source should open an undecided sibling after a conflicting Boolean prefix`() {
        val source = Problem(
            numBoolVars = 2,
            intBounds = openBounds(),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(0), LinearOp.GE, 1),
            ),
        )
        val prepared = assertNotNull(prepareExactLiraSource(source, Cancellation.Never))
        prepared.open().use { first ->
            val session = SearchSession(listOf(first))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, true))))
            assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(0, true))))
        }

        prepared.open().use { sibling ->
            val session = SearchSession(listOf(sibling))

            assertIs<ComponentResult.Consistent>(session.initialize())
            assertNull(session.boolValue(0))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, false))))
        }
    }

    @Test
    fun `prepared openings should contribute only the current source model callback`() {
        val source = comparisonClauseModel(xUpper = 8)
        val prepared = assertNotNull(prepareExactLiraSource(source, Cancellation.Never))
        val keys = List(2) { SearchModelKey(ExactLiraAssignment::class) }
        for (index in keys.indices) {
            prepared.open { value, model -> model.contribute(keys[index], value) }.use { component ->
                val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(0))

                assertIs<ComponentResult.Consistent>(session.initialize())
                val model = assertIs<SearchResult.Satisfied>(session.solve(0)).model
                val witness = assertNotNull(model.valueOf(keys[index]))
                assertEquals(BIG_ONE, witness.intValue(0) + witness.intValue(1))
                assertTrue(witness.intValue(0) <= bigIntOf(8))
                assertNull(model.valueOf(keys[1 - index]))
            }
        }
    }

    @Test
    fun `prepared real source should preserve strict bounds on fresh openings`() {
        val source = Problem(
            numBoolVars = 0,
            intBounds = openBounds(0),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(1.0),
            factors = arrayOf(
                Linear(
                    intVars = intArrayOf(),
                    intCoeffs = doubleArrayOf(),
                    realVars = intArrayOf(0),
                    realCoeffs = doubleArrayOf(1.0),
                    op = LinearOp.GE,
                    bound = 0.0,
                    strict = true,
                ),
            ),
        )
        val prepared = assertNotNull(prepareExactLiraSource(source, Cancellation.Never))
        for (key in List(2) { SearchModelKey(ExactLiraAssignment::class) }) {
            prepared.open { value, model -> model.contribute(key, value) }.use { component ->
                val session = SearchSession(listOf(component))

                assertIs<ComponentResult.Consistent>(session.initialize())
                val model = assertIs<SearchResult.Satisfied>(session.solve(0)).model
                val value = assertNotNull(model.valueOf(key)).reals.single()
                assertTrue(value > BigFraction.ZERO)
                assertTrue(value <= BigFraction.ONE)
            }
        }
    }

    @Test
    fun `sibling source scopes should discard derived disequality directions`() {
        val model = Problem(
            numBoolVars = 1,
            intBounds = openBounds(),
            factors = arrayOf(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.NE, 0),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.NE, 1),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, -1),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.GE, 2),
            ),
        )
        ExactLiraSearchComponent(model).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(1))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
            assertIs<SearchResult.Satisfied>(session.solve(1))
            session.popTo(0)
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, false))))

            val result = session.solve(1)

            val witness = assertNotNull(
                assertIs<SearchResult.Satisfied>(result).model
                    .valueOf<ExactLiraAssignment>(component),
            )
            assertEquals(bigIntOf(-1), witness.intValue(0))
        }
    }

    @Test
    fun `a retracted strict Boolean row should admit its closed sibling`() {
        val source = Problem(
            numBoolVars = 1,
            intBounds = openBounds(0),
            numRealVars = 2,
            realLower = doubleArrayOf(0.0, 0.0),
            realUpper = doubleArrayOf(0.0, 0.0),
            factors = arrayOf(
                ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1),
                    doubleArrayOf(1.0, 2.0), LinearOp.LE, 0.0, strict = true),
            ),
        )
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(listOf(component))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(0, true))))

            session.popTo(0)
            assertIs<ComponentResult.Consistent>(session.propagate())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, false))))

            val result = assertIs<SearchResult.Satisfied>(session.solve(1))
            val witness = assertNotNull(result.model.valueOf<ExactLraAssignment>(component))
            assertEquals(listOf(BigFraction.ZERO, BigFraction.ZERO), witness.reals)
            assertEquals(false, witness.bools[0])
        }
    }

    @Test
    fun `a retracted Boolean row should preserve the integer leaf`() {
        val source = Problem(
            numBoolVars = 1,
            intBounds = openBounds(),
            factors = arrayOf(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0),
            ),
        )
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(listOf(component))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(0, true))))

            session.popTo(0)
            assertIs<ComponentResult.Consistent>(session.propagate())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, false))))

            val result = assertIs<SearchResult.Satisfied>(session.solve(1))
            val witness = assertNotNull(result.model.valueOf<ExactLiraAssignment>(component))
            assertEquals(BIG_ONE, witness.intValue(0))
            assertEquals(false, witness.bools[0])
        }
    }

    @Test
    fun `a private operation stop should withhold a Boolean leaf until a live operation`() {
        var privateStopped = false
        val component = ExactLiraSearchComponent(partialModel())
        component.useOperationAllowance { parent -> parent or Cancellation { privateStopped } }
        component.use { owned ->
            val session = SearchSession(listOf(owned))
            assertIs<ComponentResult.Consistent>(session.initialize())
            privateStopped = true

            assertIs<ComponentResult.Indeterminate>(session.push(SearchDecision.Bool(Lit.make(0, false))))
            assertNull(session.model().valueOf<ExactLiraAssignment>(owned))
            session.popTo(0)
            privateStopped = false
            assertIs<ComponentResult.Consistent>(session.propagate())

            assertIs<SearchResult.Satisfied>(session.solve(2))
        }
    }

    @Test
    fun `a containing stop should prevent Boolean leaf publication`() {
        var stopped = false
        ExactLiraSearchComponent(partialModel()).use { component ->
            val session = SearchSession(listOf(component), cancellation = Cancellation { stopped })
            assertIs<ComponentResult.Consistent>(session.initialize())
            stopped = true

            assertIs<ComponentResult.Indeterminate>(session.push(SearchDecision.Bool(Lit.make(0, false))))

            assertNull(session.model().valueOf<ExactLiraAssignment>(component))
        }
    }

    @Test
    fun `an integer side should imply its weaker source Boolean before completion`() {
        val source = Problem(
            numBoolVars = 3,
            intBounds = openBounds(2),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(2, 1), intArrayOf(0, 1), LinearOp.LE, 0),
                ReifiedLinear(1, intArrayOf(2, 1), intArrayOf(0, 1), LinearOp.LE, 1),
            ),
        )
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(3, maxAtoms = 0))
            assertIs<ComponentResult.Consistent>(session.initialize())

            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))

            assertEquals(true, session.boolValue(1))
            assertNull(session.boolValue(2))
            assertEquals(setOf(Lit.make(0, false), Lit.make(1, true)),
                assertNotNull(session.reasonFor(1)).literals.toSet())
            val witness = assertNotNull(assertIs<SearchResult.Satisfied>(session.solve(source.numBoolVars)).model
                .valueOf<ExactLiraAssignment>(component))
            assertContentEquals(booleanArrayOf(true, true), witness.bools.copyOf(2))
            assertTrue(witness.intValue(0) * bigIntOf(2) + witness.intValue(1) <= BIG_ZERO)
        }
    }

    @Test
    fun `integer feedback should preserve strict side polarity at equal endpoints`() {
        val cases = listOf(
            Triple(true, LinearOp.LE, true),
            Triple(false, LinearOp.LE, false),
            Triple(false, LinearOp.GE, true),
            Triple(true, LinearOp.GE, null),
        )
        for ((antecedent, operator, expected) in cases) {
            val source = Problem(
                numBoolVars = 3,
                intBounds = openBounds(),
                factors = arrayOf(
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0),
                    ReifiedLinear(1, intArrayOf(1), intArrayOf(0), operator, 0),
                ),
            )
            ExactLiraSearchComponent(source).use { component ->
                val session = SearchSession(listOf(component))
                assertIs<ComponentResult.Consistent>(session.initialize())

                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, antecedent))))

                assertEquals(expected, session.boolValue(1))
                assertNull(session.boolValue(2))
                if (expected != null) {
                    assertEquals(setOf(Lit.make(0, !antecedent), Lit.make(1, expected)),
                        assertNotNull(session.reasonFor(1)).literals.toSet())
                }
            }
        }
    }

    @Test
    fun `integer feedback should preserve signed normalized expression scales`() {
        for (operator in listOf(LinearOp.LE, LinearOp.GE)) {
            val source = Problem(
                numBoolVars = 1,
                intBounds = openBounds(2),
                factors = arrayOf(
                    Linear(intArrayOf(2, 1), intArrayOf(0, 1), LinearOp.LE, 4),
                    ReifiedLinear(0, intArrayOf(-4, -2), intArrayOf(0, 1), operator, -10),
                ),
            )
            ExactLiraSearchComponent(source).use { component ->
                val session = SearchSession(listOf(component))

                assertIs<ComponentResult.Consistent>(session.initialize())

                val expected = operator == LinearOp.GE
                assertEquals(expected, session.boolValue(0))
                assertContentEquals(intArrayOf(Lit.make(0, expected)), assertNotNull(session.reasonFor(0)).literals)
                assertIs<SearchResult.Satisfied>(session.solve(source.numBoolVars))
            }
        }
    }

    @Test
    fun `integer feedback should retract with its source antecedent`() {
        val source = Problem(
            numBoolVars = 2,
            intBounds = openBounds(),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
            ),
        )
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(listOf(component))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
            assertEquals(true, session.boolValue(1))
            session.popTo(0)
            assertIs<ComponentResult.Consistent>(session.propagate())
            assertNull(session.boolValue(1))

            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, false))))

            assertNull(session.boolValue(1))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, false))))
            val witness = assertNotNull(assertIs<SearchResult.Satisfied>(session.solve(source.numBoolVars)).model
                .valueOf<ExactLiraAssignment>(component))
            assertTrue(witness.intValue(0) > BIG_ONE)
        }
    }

    @Test
    fun `integer feedback should admit dormant disjunctions and exclude asserted rows`() {
        for (active in listOf(false, true)) {
            val source = Problem(
                numBoolVars = 3,
                intBounds = openBounds(2),
                factors = arrayOf(
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0),
                    ReifiedLinear(1, intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
                    ComparisonClause(intArrayOf(1, 1), arrayOf(LinearOp.LE, LinearOp.GE), longArrayOf(0, 1)),
                ),
            )
            ExactLiraSearchComponent(source).use { component ->
                val atoms = SearchAtomRegistry(3)
                val session = SearchSession(listOf(component), atoms = atoms)
                assertIs<ComponentResult.Consistent>(session.initialize())
                if (active) {
                    assertIs<SearchResult.Satisfied>(session.solve(source.numBoolVars))
                    val row = assertNotNull(atoms.assertion(Lit.make(3, true)))
                    session.popTo(0)
                    assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Theory(row)))
                }

                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))

                assertEquals(if (active) null else true, session.boolValue(1))
                assertNull(session.boolValue(2))
            }
        }
    }

    @Test
    fun `a source integer branch should exclude comparison feedback`() {
        val source = Problem(
            numBoolVars = 3,
            intBounds = openBounds(2),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
            ),
        )
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(3))
            assertIs<ComponentResult.Consistent>(session.initialize())
            val split = assertNotNull(SourceBoundAtom.integerSplit(session,
                listOf(SourceBoundTerm(SearchIntValue(1), BigFraction.ONE)), BigFraction.ZERO))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Theory(split.positive)))

            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))

            assertNull(session.boolValue(1))
        }
    }

    @Test
    fun `unavailable source names should withhold integer feedback`() {
        for (hidden in listOf(0, 1)) {
            val source = Problem(
                numBoolVars = 2,
                intBounds = openBounds(),
                factors = arrayOf(
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0),
                    ReifiedLinear(1, intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
                ),
            )
            val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(2))
            val context = object : SearchContext by session {
                override fun atomLiteral(decision: SearchDecision): Int? =
                    if (decision == SearchDecision.Bool(Lit.make(hidden, true))) null else session.atomLiteral(decision)
            }
            ExactLiraSearchComponent(source).use { component ->
                assertIs<ComponentResult.Consistent>(session.initialize())
                assertIs<ComponentResult.Consistent>(component.initialize(context))
                val decision = SearchDecision.Bool(Lit.make(0, true))
                assertIs<ComponentResult.Consistent>(session.push(decision))
                assertIs<ComponentResult.Consistent>(component.assert(decision, context))

                assertIs<ComponentResult.Consistent>(component.propagate(context))

                assertNull(session.boolValue(1))
            }
        }
    }

    @Test
    fun `unavailable or oversized integer bound reasons should withhold feedback`() {
        for (oversized in listOf(false, true)) {
            val source = Problem(numBoolVars = 1, intBounds = openBounds(),
                factors = arrayOf(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 1)))
            val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(1))
            assertIs<ComponentResult.Consistent>(session.initialize())
            val bound = SearchDecision.IntAtMost(0, 0)
            assertIs<ComponentResult.Consistent>(session.push(bound))
            val premise = if (oversized) SearchAtomPremise.All(List(4096) { session.intUpperBoundPremise(0) })
                else SearchAtomPremise.Unavailable
            val context = object : SearchContext by session {
                override fun intUpperBoundPremise(variable: Int): SearchAtomPremise = premise
            }
            ExactLiraSearchComponent(source).use { component ->
                assertIs<ComponentResult.Consistent>(component.initialize(context))
                assertIs<ComponentResult.Consistent>(component.assert(bound, context))

                assertIs<ComponentResult.Consistent>(component.propagate(context))

                assertNull(session.boolValue(0))
            }
        }
    }

    @Test
    fun `nonprimitive integer targets should retain unresolved Booleans`() {
        val booleanRow = object : Factor by ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0) {
            override val linearForm = LinearForm.Conjunction(listOf(TaggedLinearRow(
                intArrayOf(Term.ofLit(Lit.make(1, true)), Term.ofIntVar(0)), IntegerConstants(UnitConsts(2), 0L),
                LinearOp.LE, activator = 0,
            )))
        }
        val targets = listOf(
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.NE, 0),
            ReifiedRealLinear(0, intArrayOf(0), doubleArrayOf(0.5), intArrayOf(), doubleArrayOf(), LinearOp.LE, 0.0),
            booleanRow,
        )
        for (target in targets) {
            val source = Problem(numBoolVars = 2, intBounds = openBounds(),
                factors = arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0), target))
            ExactLiraSearchComponent(source).use { component ->
                val session = SearchSession(listOf(component))

                assertIs<ComponentResult.Consistent>(session.initialize())

                assertNull(session.boolValue(0))
                assertNull(session.boolValue(1))
            }
        }
    }

    @Test
    fun `mixed source models should withhold integer comparison feedback`() {
        val source = Problem(
            numBoolVars = 1,
            intBounds = openBounds(),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
            factors = arrayOf(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.EQ, 0.0),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
            ),
        )
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(listOf(component))

            assertIs<ComponentResult.Consistent>(session.initialize())

            assertNull(session.boolValue(0))
        }
    }

    @Test
    fun `a stop during integer reason validation should withhold publication`() {
        var stopped = false
        val source = Problem(numBoolVars = 2, intBounds = openBounds(), factors = arrayOf(
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0),
            ReifiedLinear(1, intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
        ))
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(2))
        val context = object : SearchContext by session {
            override fun atomLiteral(decision: SearchDecision): Int? {
                if (decision == SearchDecision.Bool(Lit.make(1, true))) stopped = true
                return session.atomLiteral(decision)
            }
        }
        ExactLiraSearchComponent(source).use { component ->
            component.useOperationAllowance { parent -> parent or Cancellation { stopped } }
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(component.initialize(context))
            val decision = SearchDecision.Bool(Lit.make(0, true))
            assertIs<ComponentResult.Consistent>(session.push(decision))
            assertIs<ComponentResult.Consistent>(component.assert(decision, context))

            assertIs<ComponentResult.Indeterminate>(component.propagate(context))

            assertNull(session.boolValue(1))
            assertIs<ComponentCheck.Indeterminate>(component.check(context))
        }
    }

    @Test
    fun `an interrupted integer publication should finish only after its local source check`() {
        var stopped = false
        val source = Problem(numBoolVars = 2, intBounds = openBounds(), factors = arrayOf(
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0),
            ReifiedLinear(1, intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
        ))
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(2))
        val context = object : SearchContext by session {
            override fun imply(literal: Int, explanation: SearchExplanation): ComponentResult {
                val result = session.imply(literal, explanation)
                stopped = true
                return result
            }
        }
        ExactLiraSearchComponent(source).use { component ->
            component.useOperationAllowance { parent -> parent or Cancellation { stopped } }
            var contribution: Any? = null
            val assembly = object : SearchModel {
                override fun put(key: Any, value: Any) {
                    if (key === component) contribution = value
                }
            }
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(component.initialize(context))
            val decision = SearchDecision.Bool(Lit.make(0, true))
            assertIs<ComponentResult.Consistent>(session.push(decision))
            assertIs<ComponentResult.Consistent>(component.assert(decision, context))
            assertIs<ComponentResult.Indeterminate>(component.propagate(context))
            assertEquals(true, session.boolValue(1))
            assertIs<ComponentCheck.Indeterminate>(component.check(context))
            component.contributeModel(assembly, context)
            assertNull(contribution)
            stopped = false

            assertIs<ComponentResult.Consistent>(component.propagate(context))

            assertIs<ComponentCheck.Feasible>(component.check(context))
            component.contributeModel(assembly, context)
            val witness = assertIs<ExactLiraAssignment>(contribution)
            assertContentEquals(booleanArrayOf(true, true), witness.bools)
            assertTrue(witness.intValue(0) <= BIG_ZERO)
        }
    }

    @Test
    fun `integer consequences should share one source pass before the numerical check`() {
        val source = Problem(numBoolVars = 4, intBounds = openBounds(), factors = arrayOf(
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0),
            ReifiedLinear(1, intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
            ReifiedLinear(2, intArrayOf(1), intArrayOf(0), LinearOp.LE, 2),
        ))
        var checks = 0
        val session = SearchSession(emptyList())
        val context = object : SearchContext by session {
            override fun consumeCheck(): Boolean {
                checks++
                return true
            }
        }
        ExactLiraSearchComponent(source).use { component ->
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(component.initialize(context))
            val decision = SearchDecision.Bool(Lit.make(0, true))
            assertIs<ComponentResult.Consistent>(session.push(decision))
            assertIs<ComponentResult.Consistent>(component.assert(decision, context))

            assertIs<ComponentResult.Consistent>(component.propagate(context))

            assertEquals(true, session.boolValue(1))
            assertEquals(true, session.boolValue(2))
            assertEquals(0, checks)
            assertIs<ComponentResult.Consistent>(component.propagate(context))
            assertEquals(1, checks)
        }
    }

    private fun comparisonClauseModel(xUpper: Long) = Problem(
        numBoolVars = 0,
        intBounds = openBounds(2),
        factors = arrayOf(
            ComparisonClause(intArrayOf(0, 1), arrayOf(LinearOp.GE, LinearOp.LE), longArrayOf(5, -3)),
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 1),
            Linear(longArrayOf(1), intArrayOf(0), LinearOp.LE, xUpper),
        ),
    )

    private fun partialModel(): Problem = Problem(
        numBoolVars = 2,
        intBounds = openBounds(),
        factors = arrayOf(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1),
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
        ),
    )

    private fun strictRealBound(op: LinearOp, bound: Double) =
        Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), op, bound, strict = true)

    private fun openBounds(size: Int = 1): IntBounds {
        val open = Bits(size).also { bits -> repeat(size, bits::set) }
        return IntBounds.fromModelBounds(LongArray(size), LongArray(size), open, open)
    }
}
