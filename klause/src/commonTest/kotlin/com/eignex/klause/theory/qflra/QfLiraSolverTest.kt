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
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchIntValue
import com.eignex.klause.solver.search.SearchModelKey
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SearchSolveParams
import com.eignex.klause.solver.search.SourceBoundAtom
import com.eignex.klause.solver.search.SourceBoundTerm
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.parseBigInt
import com.eignex.klause.util.plus
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QfLiraSolverTest {
    @Test
    fun `incremental nested siblings should preserve the surviving source scope`() {
        val source = Problem(3, intBounds = openBounds(), factors = arrayOf(
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0),
            ReifiedLinear(1, intArrayOf(1), intArrayOf(0), LinearOp.LE, -2),
            ReifiedLinear(2, intArrayOf(1), intArrayOf(0), LinearOp.LE, -4),
        ))
        ExactLiraSearchComponent(source).use { component ->
            component.useIncrementalLinearPropagation()
            val session = SearchSession(listOf(component))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, true))))
            session.popTo(1)

            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, false))))

            val result = assertIs<SearchResult.Satisfied>(session.solve(1))
            val witness = assertNotNull(result.model.valueOf(component.assignmentKey))
            assertTrue(witness.intValue(0) <= BIG_ZERO)
            assertTrue(witness.intValue(0) >= bigIntOf(-1))
            assertContentEquals(booleanArrayOf(true, false, false), witness.bools)
        }
    }

    @Test
    fun `incremental prepared rows should consume a strict consequence before Boolean completion`() {
        val source = Problem(5, 0, emptyArray(), arrayOf(
            ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0),
                LinearOp.LE, 1.0),
            ReifiedRealLinear(1, intArrayOf(), doubleArrayOf(), intArrayOf(1), doubleArrayOf(1.0),
                LinearOp.GE, 2.0),
            ReifiedRealLinear(2, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(2.0, -3.0),
                LinearOp.LE, -4.0, strict = true),
            ReifiedRealLinear(3, intArrayOf(), doubleArrayOf(), intArrayOf(1), doubleArrayOf(1.0),
                LinearOp.GE, 2.0, strict = true),
        ), numRealVars = 2, realLower = DoubleArray(2) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY })
        val prepared = assertNotNull(prepareExactLiraSource(source, Cancellation.Never))
        prepared.open().use { component ->
            component.useIncrementalLinearPropagation()
            val session = SearchSession(listOf(component))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, true))))
            assertNull(session.boolValue(2))

            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(3, true))))

            assertEquals(true, session.boolValue(2))
            assertNull(session.boolValue(4))
            assertEquals(setOf(Lit.make(0, false), Lit.make(3, false), Lit.make(2, true)),
                assertNotNull(session.reasonFor(2)).literals.toSet())
        }
    }

    @Test
    fun `incremental prepared rows should reassert an opposite guard after backtracking`() {
        val source = Problem(3, intBounds = openBounds(), factors = arrayOf(
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
            ReifiedLinear(1, intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
        ))
        val prepared = assertNotNull(prepareExactLiraSource(source, Cancellation.Never))
        prepared.open().use { component ->
            component.useIncrementalLinearPropagation()
            val session = SearchSession(listOf(component))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
            assertEquals(true, session.boolValue(1))
            session.popTo(0)

            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, false))))

            assertEquals(false, session.boolValue(1))
            assertEquals(setOf(Lit.make(0, true), Lit.make(1, false)),
                assertNotNull(session.reasonFor(1)).literals.toSet())
            assertTrue(component.partialLinearPropagationStats.sourceRowInvalidations > 0)
        }
    }

    @Test
    fun `incremental prepared rows should reuse a compatible strict comparison decline`() {
        val source = Problem(5, 0, emptyArray(), arrayOf(
            ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0),
                LinearOp.LE, 1.0),
            ReifiedRealLinear(1, intArrayOf(), doubleArrayOf(), intArrayOf(1), doubleArrayOf(1.0),
                LinearOp.GE, 2.0),
            ReifiedRealLinear(2, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(2.0, -3.0),
                LinearOp.LE, -4.0, strict = true),
            ReifiedRealLinear(3, intArrayOf(), doubleArrayOf(), intArrayOf(2), doubleArrayOf(1.0),
                LinearOp.LE, 1.0),
        ), numRealVars = 3, realLower = DoubleArray(3) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(3) { Double.POSITIVE_INFINITY })
        val prepared = assertNotNull(prepareExactLiraSource(source, Cancellation.Never))
        prepared.open().use { component ->
            component.useIncrementalLinearPropagation()
            val session = SearchSession(listOf(component))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, true))))
            val reused = component.partialLinearPropagationStats.unchangedDeclines

            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(3, true))))

            assertNull(session.boolValue(2))
            assertTrue(component.partialLinearPropagationStats.unchangedDeclines > reused)
        }
    }

    @Test
    fun `a strict bound should enable a guard after a closed endpoint declined`() {
        val model = Problem(4, 0, emptyArray(), arrayOf(
            ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0),
                LinearOp.LE, 1.0),
            ReifiedRealLinear(1, intArrayOf(), doubleArrayOf(), intArrayOf(1), doubleArrayOf(1.0),
                LinearOp.GE, 2.0),
            ReifiedRealLinear(2, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(2.0, -3.0),
                LinearOp.LE, -4.0, strict = true),
            ReifiedRealLinear(3, intArrayOf(), doubleArrayOf(), intArrayOf(1), doubleArrayOf(1.0),
                LinearOp.GE, 2.0, strict = true),
        ), numRealVars = 2, realLower = DoubleArray(2) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY })
        ExactLiraSearchComponent(model).use { component ->
            component.usePartialLinearPropagation()
            component.reusePartialLinearDeclines()
            val session = SearchSession(listOf(component))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, true))))
            assertNull(session.boolValue(2))

            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(3, true))))

            assertEquals(true, session.boolValue(2))
        }
    }

    @Test
    fun `automatic partial propagation should complete the sole component after its own implication`() {
        val model = Problem(3, 0, emptyArray(), arrayOf(
            ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0),
                LinearOp.LE, 1.0),
            ReifiedRealLinear(1, intArrayOf(), doubleArrayOf(), intArrayOf(1), doubleArrayOf(1.0),
                LinearOp.GE, 2.0),
            ReifiedRealLinear(2, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(2.0, -3.0),
                LinearOp.LE, -4.0),
        ), numRealVars = 2, realLower = DoubleArray(2) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY })
        for (cached in listOf(false, true)) {
            ExactLiraSearchComponent(model).use { component ->
                component.usePartialLinearPropagation()
                if (cached) component.reusePartialLinearDeclines()
                val session = SearchSession(listOf(component))
                assertIs<ComponentResult.Consistent>(session.initialize())
                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))

                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, true))))

                assertEquals(true, session.boolValue(2))
                assertIs<ComponentCheck.Feasible>(component.check(session))
            }
        }
    }

    @Test
    fun `automatic partial propagation should not retain a consequence in the opposite branch`() {
        val model = Problem(2, intBounds = openBounds(), factors = arrayOf(
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
            ReifiedLinear(1, intArrayOf(1), intArrayOf(0), LinearOp.LE, 2),
        ))
        for (cached in listOf(false, true)) {
            ExactLiraSearchComponent(model).use { component ->
                component.usePartialLinearPropagation()
                if (cached) component.reusePartialLinearDeclines()
                val session = SearchSession(listOf(component))
                assertIs<ComponentResult.Consistent>(session.initialize())
                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
                assertEquals(true, session.boolValue(1))
                session.popTo(0)

                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, false))))

                assertNull(session.boolValue(1))
            }
        }
    }

    @Test
    fun `partial interval propagation should publish a guard with both active bound premises`() {
        val model = Problem(3, 0, emptyArray(), arrayOf(
            ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0),
                LinearOp.LE, 1.0),
            ReifiedRealLinear(1, intArrayOf(), doubleArrayOf(), intArrayOf(1), doubleArrayOf(1.0),
                LinearOp.GE, 2.0),
            ReifiedRealLinear(2, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(2.0, -3.0),
                LinearOp.LE, -4.0),
        ), numRealVars = 2, realLower = DoubleArray(2) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY })
        ExactLiraSearchComponent(model).use { component ->
            val session = SearchSession(listOf(component))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, true))))

            val probe = component.probePartialLinearImplications()

            assertEquals(1, probe.deductions.size)
            assertEquals(true, session.boolValue(2))
            assertEquals(setOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, true)),
                assertNotNull(session.reasonFor(2)).literals.toSet())
        }
    }

    @Test
    fun `partial interval propagation should decline after a required bound retracts`() {
        val model = Problem(2, intBounds = openBounds(), factors = arrayOf(
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
            ReifiedLinear(1, intArrayOf(1), intArrayOf(0), LinearOp.LE, 2),
        ))
        ExactLiraSearchComponent(model).use { component ->
            val session = SearchSession(listOf(component))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
            assertEquals(1, component.probePartialLinearImplications().deductions.size)
            assertIs<ComponentResult.Consistent>(session.propagate())

            session.popTo(0)

            assertTrue(component.probePartialLinearImplications().deductions.isEmpty())
            assertNull(session.boolValue(1))
        }
    }

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
        for (incremental in listOf(false, true)) {
            ExactLiraSearchComponent(model).use { component ->
                if (incremental) component.useIncrementalLinearPropagation()
                val session = SearchSession(listOf(component))
                session.initialize()

                assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(0, true))))
            }
        }
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
        assertNull(session.model().valueOf(component.assignmentKey))
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
                    .valueOf(component.assignmentKey),
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
        assertNull(session.model().valueOf(component.assignmentKey))
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
        val source = partialModel()
        val prepared = assertNotNull(prepareExactLiraSource(source, Cancellation.Never))
        prepared.open().use { first ->
            val session = SearchSession(listOf(first))
            assertIs<ComponentResult.Consistent>(session.initialize())
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
                    .valueOf(component.assignmentKey),
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
            val witness = assertNotNull(result.model.valueOf(component.realAssignmentKey))
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
            val witness = assertNotNull(result.model.valueOf(component.assignmentKey))
            assertEquals(BIG_ONE, witness.intValue(0))
            assertEquals(false, witness.bools[0])
        }
    }

    @Test
    fun `a private operation stop should withhold a Boolean leaf until a live operation`() {
        val source = Problem(2, intBounds = openBounds(), factors = arrayOf(
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0),
        ))
        for (incremental in listOf(false, true)) {
            var privateStopped = false
            val component = ExactLiraSearchComponent(source)
            if (incremental) component.useIncrementalLinearPropagation()
            component.useOperationAllowance { parent -> parent or Cancellation { privateStopped } }
            component.use { owned ->
                val session = SearchSession(listOf(owned))
                assertIs<ComponentResult.Consistent>(session.initialize())
                assertNull(session.boolValue(0))
                privateStopped = true

                assertIs<ComponentResult.Indeterminate>(session.push(SearchDecision.Bool(Lit.make(0, false))))
                assertNull(session.model().valueOf(owned.assignmentKey))
                session.popTo(0)
                privateStopped = false
                assertIs<ComponentResult.Consistent>(session.propagate())

                assertIs<SearchResult.Satisfied>(session.solve(2))
            }
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

            assertNull(session.model().valueOf(component.assignmentKey))
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
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0),
        ),
    )

    private fun strictRealBound(op: LinearOp, bound: Double) =
        Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), op, bound, strict = true)

    private fun openBounds(size: Int = 1): IntBounds {
        val open = Bits(size).also { bits -> repeat(size, bits::set) }
        return IntBounds.fromModelBounds(LongArray(size), LongArray(size), open, open)
    }
}
