package com.eignex.klause.theory.qflra

import com.eignex.klause.factor.arithmetic.ComparisonClause
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.TaggedLinearRow
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.UnitConsts
import com.eignex.klause.ir.linearRows
import com.eignex.klause.model.PbOp
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.pipeline.componentPlan
import com.eignex.klause.solver.pipeline.search
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchExecutionAccounting
import com.eignex.klause.solver.search.SearchExecutionControlAdapter
import com.eignex.klause.solver.search.SearchIntValue
import com.eignex.klause.solver.search.SearchModelKey
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SearchSolveParams
import com.eignex.klause.solver.search.SearchStopCause
import com.eignex.klause.solver.search.SourceBoundAtom
import com.eignex.klause.solver.search.SourceBoundTerm
import com.eignex.klause.solver.search.contribute
import com.eignex.klause.theory.EncodedTheoryFactor
import com.eignex.klause.theory.EncodingAttribution
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
    fun `separate source bounds should imply either guard polarity with all antecedents`() {
        for (op in listOf(LinearOp.LE, LinearOp.GE)) {
            for (holds in listOf(false, true)) {
                val lower = (op == LinearOp.GE) == holds
                val source = Problem(
                    numBoolVars = 4,
                    intBounds = openBounds(2),
                    factors = arrayOf(
                        ReifiedLinear(0, intArrayOf(1), intArrayOf(0), if (lower) LinearOp.GE else LinearOp.LE,
                            if (lower) 2 else 1),
                        ReifiedLinear(1, intArrayOf(1), intArrayOf(1), if (lower) LinearOp.GE else LinearOp.LE, 1),
                        ReifiedLinear(2, intArrayOf(1, 1), intArrayOf(0, 1), op, if (op == LinearOp.GE) 3 else 2),
                    ),
                )
                ExactLiraSearchComponent(source).use { component ->
                    val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(4, maxAtoms = 0))
                    assertIs<ComponentResult.Consistent>(session.initialize())
                    assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))

                    assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, true))))

                    assertEquals(holds, session.boolValue(2))
                    assertEquals(2, session.decisionLevel)
                    assertContentEquals(
                        intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, holds)).sortedArray(),
                        assertNotNull(session.reasonFor(2)).literals.sortedArray(),
                    )
                }
            }
        }
    }

    @Test
    fun `signed source coefficients should select opposite interval sides`() {
        val source = Problem(
            numBoolVars = 4,
            intBounds = openBounds(2),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(1), LinearOp.GE, 2),
                ReifiedLinear(2, intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0),
            ),
        )
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(4, maxAtoms = 0))
            session.initialize()
            session.push(SearchDecision.Bool(Lit.make(0, true)))

            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, true))))

            assertEquals(true, session.boolValue(2))
            assertContentEquals(intArrayOf(1, 3, 4), assertNotNull(session.reasonFor(2)).literals.sortedArray())
        }
    }

    @Test
    fun `a strict interval endpoint should refute its closed comparison guard`() {
        val source = Problem(
            numBoolVars = 4,
            intBounds = openBounds(2),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(1), LinearOp.GE, 0),
                ReifiedLinear(2, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 0),
            ),
        )
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(4, maxAtoms = 0))
            session.initialize()
            session.push(SearchDecision.Bool(Lit.make(0, false)))

            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, true))))

            assertEquals(false, session.boolValue(2))
            assertContentEquals(intArrayOf(0, 3, 5), assertNotNull(session.reasonFor(2)).literals.sortedArray())
        }
    }

    @Test
    fun `an interval guard fact should retract before an unrelated sibling`() {
        val source = Problem(
            numBoolVars = 4,
            intBounds = openBounds(2),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(1), LinearOp.LE, 1),
                ReifiedLinear(2, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 2),
            ),
        )
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(4, maxAtoms = 0))
            session.initialize()
            session.push(SearchDecision.Bool(Lit.make(0, true)))
            session.push(SearchDecision.Bool(Lit.make(1, true)))
            assertEquals(true, session.boolValue(2))

            session.popTo(1)
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, false))))

            assertNull(session.boolValue(2))
            assertNull(session.reasonFor(2))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(2, false))))
        }
    }

    @Test
    fun `an installed affine definition should retain ordinary guard traversal`() {
        val source = Problem(
            numBoolVars = 2,
            intBounds = openBounds(2),
            factors = arrayOf(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 2),
                ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 3),
            ),
        )
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(2))

            assertIs<ComponentResult.Consistent>(session.initialize())

            assertNull(session.boolValue(0))
            assertNull(session.reasonFor(0))
        }
    }

    @Test
    fun `an interval missing one source side should publish no guard fact`() {
        val source = Problem(
            numBoolVars = 2,
            intBounds = openBounds(2),
            factors = arrayOf(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
                ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 2),
            ),
        )
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(2))

            assertIs<ComponentResult.Consistent>(session.initialize())

            assertNull(session.boolValue(0))
        }
    }

    @Test
    fun `a singleton guard should retain its existing Boolean traversal`() {
        val source = Problem(
            numBoolVars = 2,
            intBounds = openBounds(),
            factors = arrayOf(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 2),
            ),
        )
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(2))

            assertIs<ComponentResult.Consistent>(session.initialize())

            assertNull(session.boolValue(0))
        }
    }

    @Test
    fun `settled equality and disequality guards should retain their existing owner`() {
        for (op in listOf(LinearOp.EQ, LinearOp.NE)) {
            val source = Problem(
                numBoolVars = 2,
                intBounds = openBounds(2),
                factors = arrayOf(
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                    Linear(intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1),
                    ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), op, 2),
                ),
            )
            ExactLiraSearchComponent(source).use { component ->
                val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(2))

                assertIs<ComponentResult.Consistent>(session.initialize())

                assertNull(session.boolValue(0))
            }
        }
    }

    @Test
    fun `a delegated custom row should not inherit native interval guard authority`() {
        val native = ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 2)
        val source = Problem(
            numBoolVars = 2,
            intBounds = openBounds(2),
            factors = arrayOf(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.LE, 1),
                object : Factor by native {},
            ),
        )
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(2))

            assertIs<ComponentResult.Consistent>(session.initialize())

            assertNull(session.boolValue(0))
        }
    }

    @Test
    fun `changed and nested encodings should withhold native interval guard authority`() {
        for (nested in listOf(false, true)) {
            val native = ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 2)
            val attribution = EncodingAttribution(0, listOf(SourceBoolId(0)),
                listOf(SourceIntId(0), SourceIntId(1)), emptyList())
            val encoded = if (nested) {
                EncodedTheoryFactor(EncodedTheoryFactor(native, native.linearForm, attribution),
                    native.linearForm, attribution)
            } else {
                EncodedTheoryFactor(native, LinearForm.Conjunction(native.linearForm.rows), attribution)
            }
            val source = Problem(
                numBoolVars = 2,
                intBounds = openBounds(2),
                factors = arrayOf(
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
                    Linear(intArrayOf(1), intArrayOf(1), LinearOp.LE, 1),
                    encoded,
                ),
            )
            ExactLiraSearchComponent(source).use { component ->
                val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(2))

                assertIs<ComponentResult.Consistent>(session.initialize())

                assertNull(session.boolValue(0))
            }
        }
    }

    @Test
    fun `incomplete interval reasons should withhold the entire source guard fact`() {
        for (refusal in listOf("unavailable", "circular", "unnameable", "oversized", "stale")) {
            var antecedentsLive = true
            val source = Problem(
                numBoolVars = 3,
                intBounds = openBounds(2),
                factors = arrayOf(
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, -10),
                    ReifiedLinear(2, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 2),
                ),
            )
            val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(3, maxAtoms = 0))
            val antecedent = SearchAtomPremise.Asserted(SearchDecision.Bool(Lit.make(0, true)))
            val premise = when (refusal) {
                "unavailable" -> SearchAtomPremise.Unavailable
                "circular" -> SearchAtomPremise.Asserted(SearchDecision.Bool(Lit.make(2, true)))
                "oversized" -> SearchAtomPremise.All(List(4097) { antecedent })
                else -> antecedent
            }
            val context = object : SearchContext by session {
                override fun boolValue(variable: Int): Boolean? = if (variable < 2) antecedentsLive else null
                override fun atomLiteral(decision: SearchDecision): Int? =
                    if (refusal == "unnameable") null else session.atomLiteral(decision)
                override fun intUpperBound(variable: Int): Long = 1L
                override fun intUpperBoundPremise(variable: Int): SearchAtomPremise = premise
            }
            ExactLiraSearchComponent(source).use { component ->
                component.useSolveStop(object : Cancellation {
                    override fun isCancelled(): Boolean = false
                    override fun workMeter(): WorkMeter = WorkMeter { units ->
                        if (refusal == "stale" && units == 10L) antecedentsLive = false
                    }
                })
                component.initialize(context)

                component.propagate(context)

                assertNull(session.boolValue(2))
                assertNull(session.reasonFor(2))
                if (refusal == "stale") assertEquals(false, antecedentsLive)
            }
        }
    }

    @Test
    fun `containing stops during interval reason work should withhold the guard and retain their cause`() {
        for (owner in listOf("parent", "context", "native")) {
            var armed = false
            var stopped = false
            val parent = object : Cancellation {
                override fun isCancelled(): Boolean = stopped && owner == "parent"
                override fun workMeter(): WorkMeter = WorkMeter { units ->
                    if (armed && units == 48L) stopped = true
                }
            }
            val accounting = object : SearchExecutionAccounting {
                override val exhausted: Boolean get() = stopped && owner == "native"
            }
            val controls = SearchExecutionControlAdapter(Cancellation { stopped && owner == "context" }, accounting)
            val source = Problem(
                numBoolVars = 4,
                intBounds = openBounds(2),
                factors = arrayOf(
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
                    ReifiedLinear(1, intArrayOf(1), intArrayOf(1), LinearOp.LE, 1),
                    ReifiedLinear(2, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 2),
                ),
            )
            ExactLiraSearchComponent(source).use { component ->
                component.useSolveStop(parent)
                val session = SearchSession(
                    listOf(component), atoms = SearchAtomRegistry(4), executionControls = controls,
                )
                assertIs<ComponentResult.Consistent>(session.initialize())
                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))

                armed = true
                val result = session.push(SearchDecision.Bool(Lit.make(1, true)))

                assertTrue(stopped)
                assertIs<ComponentResult.Indeterminate>(result)
                assertNull(session.boolValue(2))
                assertEquals(when (owner) {
                    "parent" -> SearchStopCause.OperationAllowance
                    "context" -> SearchStopCause.Cancelled
                    else -> SearchStopCause.WorkAllowance
                }, controls.stopCause)
            }
        }
    }

    @Test
    fun `nested SMT integer comparisons should imply a guard through its unchanged native encoding`() {
        val source = SmtLib.parse("""
            (set-logic QF_LIA)
            (declare-const x Int)
            (declare-const y Int)
            (declare-const a Bool)
            (declare-const b Bool)
            (declare-const c Bool)
            (assert (or a (<= x 1)))
            (assert (or b (<= y 1)))
            (assert (or c (<= (+ x y) 2)))
            (check-sat)
        """.trimIndent()).model
        val rows = source.factors.filterIsInstance<ReifiedLinear>()
        val x = rows.single { it.vars.contentEquals(intArrayOf(0)) }
        val y = rows.single { it.vars.contentEquals(intArrayOf(1)) }
        val target = rows.single { it.vars.size == 2 }
        val plan = source.componentPlan()
        val fragment = assertNotNull(plan.encodedTheoryFragment(source))
        val encoded = fragment.factors.filterIsInstance<EncodedTheoryFactor>().single { it.source === target }
        assertTrue(encoded.linearForm === target.linearForm)
        assertTrue(!source.intBounds.hasLower(0) && !source.intBounds.hasUpper(0))
        plan.search(source, emptyMap()).use { planned ->
            assertIs<ExactLiraSearchComponent>(planned.theory)
            val session = planned.session
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(x.auxBoolVar, true))))

            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(y.auxBoolVar, true))))

            assertEquals(true, session.boolValue(target.auxBoolVar))
            assertContentEquals(
                intArrayOf(Lit.make(x.auxBoolVar, false), Lit.make(y.auxBoolVar, false),
                    Lit.make(target.auxBoolVar, true)).sortedArray(),
                assertNotNull(session.reasonFor(target.auxBoolVar)).literals.sortedArray(),
            )
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
