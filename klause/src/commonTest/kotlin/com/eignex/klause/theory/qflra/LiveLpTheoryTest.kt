package com.eignex.klause.theory.qflra

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.RealConstants
import com.eignex.klause.ir.RealConsts
import com.eignex.klause.ir.TaggedLinearRow
import com.eignex.klause.ir.Term
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.LpCertificationPolicy
import com.eignex.klause.lp.engine.LpEngineFactory
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.PersistentLpSolver
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.simplex.basis.BasisArithmeticException
import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.IndexedVector
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.pipeline.componentPlan
import com.eignex.klause.solver.pipeline.search
import com.eignex.klause.solver.result.SmtStatsSink
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.RegisteredTheoryDecision
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchConflictResolution
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchIntValue
import com.eignex.klause.solver.search.SearchLearnedConflictResult
import com.eignex.klause.solver.search.SearchRealValue
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SearchSolveParams
import com.eignex.klause.theory.TheoryCheck
import com.eignex.klause.theory.TheoryContext
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveLpTheoryTest {
    @Test
    fun `production factory retains one float owner across opposite assertions and pop`() {
        val source = Problem(
            numBoolVars = 3,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            numRealVars = 1,
            realLower = doubleArrayOf(-2.0),
            realUpper = doubleArrayOf(2.0),
            factors = arrayOf(
                ReifiedRealLinear(
                    0,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(2.0),
                    LinearOp.LE,
                    1.0,
                ),
            ),
        )
        source.componentPlan().search(source, emptyMap()).use { planned ->
            val component = assertIs<ExactLiraSearchComponent>(planned.theory)
            val session = planned.session
            assertIs<ComponentResult.Consistent>(session.initialize())
            repeat(3) {
                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
                session.popTo(0)
                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, false))))
                session.popTo(0)
            }
            assertEquals(1L, assertNotNull(component.lpMetrics).createdOwners)
            assertEquals(1L, assertNotNull(component.lpMetrics).currentOwners)
        }
    }

    @Test
    fun `registered source bounds receive learned implications after a restart`() {
        val source = Problem(
            numBoolVars = 3,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            numRealVars = 1,
            realLower = doubleArrayOf(-2.0),
            realUpper = doubleArrayOf(2.0),
            factors = arrayOf(
                ReifiedRealLinear(
                    0,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(2.0),
                    LinearOp.GE,
                    1.0,
                ),
            ),
        )
        source.componentPlan().search(source, emptyMap()).use { planned ->
            val session = planned.session
            session.initialize()
            val split = assertNotNull(
                SourceBoundAtom.rationalSplit(
                    session,
                    listOf(SourceBoundTerm(SearchRealValue(0), BigFraction.ONE)),
                    BigFraction.ZERO,
                ),
            )
            assertEquals(3, split.positive.literal ushr 1)
            session.push(SearchDecision.Bool(Lit.make(0, true)))
            session.push(SearchDecision.Bool(Lit.make(2, true)))
            val conflict = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Theory(split.positive)))
            val clause = assertNotNull(conflict.explanation)
            assertContentEquals(intArrayOf(Lit.make(0, false), split.negative.literal), clause.literals.sortedArray())
            val learned = assertIs<SearchConflictResolution.Backjump>(
                session.explainedConflict(clause),
            ).conflict
            assertEquals(1, learned.decisionLevel)
            session.popTo(learned.decisionLevel)
            assertIs<SearchLearnedConflictResult.Resume>(learned.apply(session))
            assertIs<ComponentResult.Consistent>(session.restart())
            session.push(SearchDecision.Bool(Lit.make(0, true)))
            assertEquals(1, session.learnedClauseCount)
            assertEquals(false, session.boolValue(split.positive.literal ushr 1))
            val contrary = assertNotNull(
                SourceBoundAtom.rationalSplit(
                    session,
                    listOf(SourceBoundTerm(SearchRealValue(0), BigFraction.ONE)),
                    BigFraction.ZERO,
                ),
            )
            assertEquals(split.positive.literal, contrary.positive.literal)
            assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Theory(contrary.positive)))
        }
    }

    @Test
    fun `learned transformed and free source bounds replay after restart and extend a witness`() {
        val open = Bits(2).also {
            it.set(0)
            it.set(1)
        }
        val source = Problem(
            1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(0, 0), open, open),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(2, -2), intArrayOf(0, 1), LinearOp.GE, 1),
                Linear(intArrayOf(2, -2), intArrayOf(0, 1), LinearOp.LE, 3),
                Linear(intArrayOf(2, 2), intArrayOf(0, 1), LinearOp.GE, 1),
            ),
        )
        source.componentPlan().search(source, emptyMap()).use { planned ->
            val component = assertIs<ExactLiraSearchComponent>(planned.theory)
            val session = planned.session
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
            val alternatives = assertNotNull(component.nextBranch(session)).map {
                assertIs<RegisteredTheoryDecision>(assertIs<SearchDecision.Theory>(it).decision)
            }
            val transformed = alternatives.first {
                val atom = assertIs<SourceBoundAtom>(it.payload)
                val activity = atom.terms.filter { term -> term.source == SearchIntValue(0) }
                    .fold(BigFraction.ZERO) { sum, term -> sum + term.coefficient }
                if (atom.upper) activity > atom.threshold else activity < atom.threshold
            }
            assertEquals(2, assertIs<SourceBoundAtom>(transformed.payload).terms.size)
            val free = assertNotNull(
                SourceBoundAtom.integerSplit(
                    session,
                    listOf(
                        SourceBoundTerm(SearchIntValue(0), BigFraction.ONE),
                        SourceBoundTerm(SearchIntValue(1), BigFraction.ONE),
                    ),
                    BigFraction.ZERO,
                ),
            ).positive
            for (excluded in listOf(transformed, free)) {
                val conflict = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Theory(excluded)))
                val expected = if (excluded == transformed) {
                    intArrayOf(Lit.make(0, false), excluded.literal xor 1)
                } else {
                    intArrayOf(excluded.literal xor 1)
                }
                assertContentEquals(expected.sortedArray(), assertNotNull(conflict.explanation).literals.sortedArray())
                val learned = assertIs<SearchConflictResolution.Backjump>(
                    session.explainedConflict(conflict.explanation),
                ).conflict
                session.popTo(learned.decisionLevel)
                assertIs<SearchLearnedConflictResult.Resume>(learned.apply(session))
                assertIs<ComponentResult.Consistent>(session.restart())
                assertNull(session.boolValue(transformed.literal ushr 1))
                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
                assertEquals(!Lit.isPositive(transformed.literal), session.boolValue(transformed.literal ushr 1))
                assertEquals(!Lit.isPositive(excluded.literal), session.boolValue(excluded.literal ushr 1))
            }
            val result = assertIs<SearchResult.Satisfied>(session.solve(source.numBoolVars))
            val values = assertNotNull(result.model.valueOf<ExactLiraAssignment>(component)).ints
            assertEquals(BigInteger.ONE, values[0] - values[1])
            assertTrue(values[0] + values[1] >= BigInteger.ONE)
            for (excluded in listOf(transformed, free)) {
                val atom = assertIs<SourceBoundAtom>(excluded.payload)
                val activity = atom.terms.fold(BigFraction.ZERO) { sum, term ->
                    sum + term.coefficient * BigFraction.of(
                        values[assertIs<SearchIntValue>(term.source).variable],
                        BigInteger.ONE,
                    )
                }
                assertTrue(if (atom.upper) activity > atom.threshold else activity < atom.threshold)
            }
            assertEquals(2, session.learnedClauseCount)
        }
    }

    @Test
    fun `Boolean substitutions restore source thresholds and cancelled premises on siblings`() {
        for (coefficients in listOf(doubleArrayOf(1.0), doubleArrayOf(1.0, -1.0))) {
            val factor = object : Factor by ReifiedRealLinear(
                0,
                intArrayOf(),
                doubleArrayOf(),
                intArrayOf(0),
                doubleArrayOf(1.0),
                LinearOp.LE,
                1.0,
            ) {
                override val linearForm = LinearForm.Conjunction(
                    listOf(
                        TaggedLinearRow(
                            intArrayOf(
                                Term.ofLit(Lit.make(0, false)),
                            ) + IntArray(coefficients.size) { Term.ofRealVar(0) },
                            RealConstants(RealConsts(doubleArrayOf(2.0)), RealConsts(coefficients), 1.0, false),
                            LinearOp.LE,
                        ),
                    ),
                )
            }
            val source = Problem(
                1,
                intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
                numRealVars = 1,
                realLower = doubleArrayOf(0.0),
                realUpper = doubleArrayOf(2.0),
                factors = arrayOf(factor),
            )
            ExactLiraSearchComponent(source).use { component ->
                val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(1))
                session.initialize()
                repeat(2) {
                    val conflict = assertIs<ComponentResult.Conflict>(
                        session.push(SearchDecision.Bool(Lit.make(0, false))),
                    )
                    assertContentEquals(intArrayOf(Lit.make(0, true)), assertNotNull(conflict.explanation).literals)
                    session.popTo(0)
                    assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
                    val result = assertIs<SearchResult.Satisfied>(session.solve(1))
                    val value = assertNotNull(result.model.valueOf<ExactLraAssignment>(component)).reals.single()
                    assertTrue(value >= BigFraction.ZERO && value <= BigFraction.ofLong(2))
                    assertTrue(
                        coefficients.fold(BigFraction.ZERO) { sum, coefficient ->
                            sum + assertNotNull(BigFraction.ofDouble(coefficient)) * value
                        } <= BigFraction.ONE,
                    )
                    session.popTo(0)
                }
            }
        }
    }

    @Test
    fun `a false equality explores its disequality arms and restores equality on a sibling`() {
        val source = Problem(
            1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(-1), longArrayOf(0), null, null),
            factors = arrayOf(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0)),
        )
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(1))
            session.initialize()
            for (truth in listOf(false, true)) {
                session.push(SearchDecision.Bool(Lit.make(0, truth)))
                val result = assertIs<SearchResult.Satisfied>(session.solve(1))
                val value = assertNotNull(result.model.valueOf<ExactLiraAssignment>(component)).ints.single()
                assertEquals(if (truth) BigInteger.ZERO else -BigInteger.ONE, value)
                session.popTo(0)
            }
        }
    }

    @Test
    fun `strict closure fallback returns a source interior witness`() {
        val source = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
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
                Linear(
                    intVars = intArrayOf(),
                    intCoeffs = doubleArrayOf(),
                    realVars = intArrayOf(0),
                    realCoeffs = doubleArrayOf(1.0),
                    op = LinearOp.LE,
                    bound = 1.0,
                    strict = true,
                ),
            ),
        )
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(0))
            session.initialize()
            val result = assertIs<SearchResult.Satisfied>(session.solve(0))
            val witness = assertNotNull(result.model.valueOf<ExactLraAssignment>(component)).reals.single()
            assertTrue(witness > BigFraction.ZERO && witness < BigFraction.ONE)
        }
    }

    @Test
    fun `a failed disequality arm leaves the other source arm available`() {
        val source = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            numRealVars = 1,
            realLower = doubleArrayOf(-1.0),
            realUpper = doubleArrayOf(0.0),
            factors = arrayOf(
                Linear(
                    intVars = intArrayOf(),
                    intCoeffs = doubleArrayOf(),
                    realVars = intArrayOf(0),
                    realCoeffs = doubleArrayOf(1.0),
                    op = LinearOp.NE,
                    bound = 0.0,
                ),
            ),
        )
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(0))
            session.initialize()
            val result = assertIs<SearchResult.Satisfied>(session.solve(0))
            val witness = assertNotNull(result.model.valueOf<ExactLraAssignment>(component)).reals.single()
            assertTrue(witness >= BigFraction.MINUS_ONE && witness < BigFraction.ZERO)
        }
    }

    @Test
    fun `infeasible comparison alternatives leave the final source disjunct available`() {
        val declaration = Linear(
            intVars = intArrayOf(),
            intCoeffs = doubleArrayOf(),
            realVars = intArrayOf(0),
            realCoeffs = doubleArrayOf(1.0),
            op = LinearOp.EQ,
            bound = 0.0,
        )
        val factor = object : Factor by declaration {
            override val linearForm = LinearForm.Disjunction(
                listOf(
                    Triple(2.0, LinearOp.GE, 1.0),
                    Triple(3.0, LinearOp.LE, -1.0),
                    Triple(5.0, LinearOp.EQ, 0.0),
                ).map { (coefficient, op, rhs) ->
                    TaggedLinearRow(
                        intArrayOf(Term.ofRealVar(0)),
                        RealConstants(RealConsts(doubleArrayOf()), RealConsts(doubleArrayOf(coefficient)), rhs, false),
                        op,
                    )
                },
            )
        }
        val source = Problem(
            0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(0.0),
            factors = arrayOf(factor),
        )
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(0))
            assertIs<ComponentResult.Consistent>(session.initialize())
            val result = assertIs<SearchResult.Satisfied>(session.solve(0))
            val witness = assertNotNull(result.model.valueOf<ExactLraAssignment>(component)).reals.single()
            assertEquals(BigFraction.ZERO, witness)
        }
    }

    @Test
    fun `closed rational fixtures agree with independent cold source solving`() {
        for (rhs in listOf(0.5, 1.0, 4.0)) {
            val source = Problem(
                numBoolVars = 0,
                intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
                numRealVars = 1,
                realLower = doubleArrayOf(0.0),
                realUpper = doubleArrayOf(1.0),
                factors = arrayOf(
                    Linear(
                        intVars = intArrayOf(),
                        intCoeffs = doubleArrayOf(),
                        realVars = intArrayOf(0),
                        realCoeffs = doubleArrayOf(3.0),
                        op = LinearOp.EQ,
                        bound = rhs,
                    ),
                ),
            )
            val reference = ExactLraSolver(source).check(
                booleanArrayOf(),
                object : TheoryContext {
                    override fun consumeCheck(): Boolean = true
                    override fun cancelled(): Boolean = false
                },
            )
            ExactLiraSearchComponent(source).use { component ->
                val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(0))
                val initialized = session.initialize()
                val result = if (initialized is ComponentResult.Conflict) SearchResult.Exhausted else session.solve(0)
                assertEquals(reference is TheoryCheck.Sat, result is SearchResult.Satisfied)
                if (result is SearchResult.Satisfied) {
                    val value = assertNotNull(result.model.valueOf<ExactLraAssignment>(component)).reals.single()
                    assertEquals(assertNotNull(BigFraction.ofDouble(rhs)), value * BigFraction.ofLong(3))
                    assertTrue(value >= BigFraction.ZERO && value <= BigFraction.ONE)
                } else {
                    assertIs<SearchResult.Exhausted>(result)
                }
            }
        }
    }

    @Test
    fun `registered integers beyond Long retain exact thresholds and witnesses`() {
        val limit = BigInteger.parseString("18446744073709551617")
        val source = Problem(
            0,
            intBounds = IntBounds.fromModelBounds(
                longArrayOf(0),
                longArrayOf(0),
                Bits(1).also { it.set(0) },
                Bits(1).also { it.set(0) },
            ),
            factors = emptyArray(),
        )
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(0))
            session.initialize()
            val split = assertNotNull(
                SourceBoundAtom.integerSplit(
                    session,
                    listOf(SourceBoundTerm(SearchIntValue(0), BigFraction.ONE)),
                    BigFraction.of(limit, BigInteger.ONE),
                ),
            )
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Theory(split.negative)))
            val result = assertIs<SearchResult.Satisfied>(session.solve(0))
            val value = assertNotNull(result.model.valueOf<ExactLiraAssignment>(component)).ints.single()
            assertTrue(value > limit)
        }
    }

    @Test
    fun `unbounded integer sources split a bounded transformed coordinate and extend a witness`() {
        val open = Bits(2).also {
            it.set(0)
            it.set(1)
        }
        val source = Problem(
            0,
            intBounds = IntBounds.fromModelBounds(
                longArrayOf(0, 0),
                longArrayOf(0, 0),
                open,
                open,
            ),
            factors = arrayOf(
                Linear(intArrayOf(2, -2), intArrayOf(0, 1), LinearOp.GE, 1),
                Linear(intArrayOf(2, -2), intArrayOf(0, 1), LinearOp.LE, 3),
                Linear(intArrayOf(2, 2), intArrayOf(0, 1), LinearOp.GE, 1),
            ),
        )
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(0))
            session.initialize()
            val alternatives = assertNotNull(component.nextBranch(session))
            val branch = assertIs<SearchDecision.Theory>(alternatives.first()).decision
            val atom = assertIs<RegisteredTheoryDecision>(branch)
            assertEquals(2, assertIs<SourceBoundAtom>(atom.payload).terms.size)
            val result = assertIs<SearchResult.Satisfied>(session.solve(0))
            val values = assertNotNull(result.model.valueOf<ExactLiraAssignment>(component)).ints
            assertEquals(BigInteger.ONE, values[0] - values[1])
            assertTrue(values[0] + values[1] >= BigInteger.ONE)
        }
    }

    @Test
    fun `a required transformed split declines when its source registry is full`() {
        val open = Bits(2).also {
            it.set(0)
            it.set(1)
        }
        val source = Problem(
            0,
            intBounds = IntBounds.fromModelBounds(
                longArrayOf(0, 0),
                longArrayOf(0, 0),
                open,
                open,
            ),
            factors = arrayOf(
                Linear(intArrayOf(2, -2), intArrayOf(0, 1), LinearOp.GE, 1),
                Linear(intArrayOf(2, -2), intArrayOf(0, 1), LinearOp.LE, 3),
            ),
        )
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(0, maxAtoms = 0))
            session.initialize()
            assertIs<SearchResult.Indeterminate>(session.solve(0))
            assertNull(session.model().valueOf<ExactLiraAssignment>(component))
        }
    }

    @Test
    fun `complementary registered integer conflicts refute the full source through shared learning`() {
        val source = Problem(
            0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null),
            factors = arrayOf(Linear(intArrayOf(2), intArrayOf(0), LinearOp.EQ, 1)),
        )
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(0))
            assertIs<ComponentResult.Consistent>(session.initialize())
            val split = assertNotNull(
                SourceBoundAtom.integerSplit(
                    session,
                    listOf(SourceBoundTerm(SearchIntValue(0), BigFraction.ONE)),
                    BigFraction.ZERO,
                ),
            )
            val conflict = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Theory(split.positive)))
            assertContentEquals(intArrayOf(split.negative.literal), assertNotNull(conflict.explanation).literals)
            val learned = assertIs<SearchConflictResolution.Backjump>(
                session.explainedConflict(conflict.explanation),
            ).conflict
            session.popTo(learned.decisionLevel)
            assertIs<SearchLearnedConflictResult.Chronological>(learned.apply(session))
            val opposite = assertIs<ComponentCheck.Infeasible>(component.check(session))
            assertContentEquals(intArrayOf(split.positive.literal), assertNotNull(opposite.explanation).literals)
            assertIs<SearchResult.Exhausted>(session.solve(0))
            assertTrue((0L..1L).none { 2L * it == 1L })
        }
    }

    @Test
    fun `closed integer fixtures agree with exhaustive source enumeration`() {
        for (rhs in listOf(1L, 2L, 4L)) {
            val source = Problem(
                0,
                intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(1, 1), null, null),
                factors = arrayOf(Linear(longArrayOf(2, 1), intArrayOf(0, 1), LinearOp.EQ, rhs)),
            )
            val feasible = (0L..1L).any { x -> (0L..1L).any { y -> 2L * x + y == rhs } }
            ExactLiraSearchComponent(source).use { component ->
                val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(0))
                val initialized = session.initialize()
                val result = if (initialized is ComponentResult.Conflict) SearchResult.Exhausted else session.solve(0)
                assertEquals(feasible, result is SearchResult.Satisfied)
                if (result is SearchResult.Satisfied) {
                    val values = assertNotNull(result.model.valueOf<ExactLiraAssignment>(component)).ints
                    assertEquals(BigInteger.fromLong(rhs), values[0] * BigInteger.TWO + values[1])
                    assertTrue(values.all { it >= BigInteger.ZERO && it <= BigInteger.ONE })
                } else {
                    assertIs<SearchResult.Exhausted>(result)
                }
            }
        }
    }

    @Test
    fun `production ownership closes on initialization conflict terminal decline and solve exception`() {
        for (scenario in listOf("conflict", "decline", "exception")) {
            var created = 0
            var closed = 0
            val factory = object : LpEngineFactory by ProductionLpEngineFactory {
                override fun newPersistentSolver(
                    model: LpModel,
                    cancellation: Cancellation,
                    refactorUpdateLimit: Int,
                    iterationLimit: Int,
                    workLimit: Long,
                    trackDegeneracy: Boolean,
                    pricing: LpPricingOptions,
                ): PersistentLpSolver {
                    created++
                    val delegate = ProductionLpEngineFactory.newPersistentSolver(
                        model,
                        cancellation,
                        refactorUpdateLimit,
                        iterationLimit,
                        workLimit,
                        trackDegeneracy,
                        pricing,
                    )
                    return object : PersistentLpSolver by delegate {
                        override fun resolveBounds(): FloatLpResult? {
                            if (scenario == "exception") error("injected source solve failure")
                            return delegate.resolveBounds()
                        }

                        override fun close() {
                            closed++
                            delegate.close()
                        }
                    }
                }
            }
            val source = Problem(
                0,
                intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null),
                numRealVars = 1,
                realLower = doubleArrayOf(0.0),
                realUpper = doubleArrayOf(0.0),
                factors = arrayOf(
                    Linear(
                        intVars = intArrayOf(0),
                        intCoeffs = doubleArrayOf(2.0),
                        realVars = intArrayOf(0),
                        realCoeffs = doubleArrayOf(1.0),
                        op = LinearOp.GE,
                        bound = if (scenario == "conflict") 3.0 else 1.0,
                    ),
                ),
            )
            source.componentPlan().search(source, emptyMap()).use { planned ->
                val component = assertIs<ExactLiraSearchComponent>(planned.theory)
                component.solveWith(LpSolveContext(factory))
                when (scenario) {
                    "conflict" -> assertIs<ComponentResult.Conflict>(planned.session.initialize())

                    "exception" -> assertEquals(
                        "injected source solve failure",
                        assertFailsWith<IllegalStateException> { planned.session.initialize() }.message,
                    )

                    else -> {
                        assertIs<ComponentResult.Consistent>(planned.session.initialize())
                        assertIs<SearchResult.Indeterminate>(
                            planned.session.solve(0, SearchSolveParams(maxDecisions = 0)),
                        )
                        assertNull(planned.session.model().valueOf<ExactLiraAssignment>(component))
                    }
                }
            }
            assertEquals(1, created, scenario)
            assertEquals(1, closed, scenario)
        }
    }

    @Test
    fun `a cancelled completed float package is withheld and its owner closes once`() {
        var stopped = false
        var created = 0
        var closed = 0
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver {
                created++
                val delegate = ProductionLpEngineFactory.newPersistentSolver(
                    model,
                    cancellation,
                    refactorUpdateLimit,
                    iterationLimit,
                    workLimit,
                    trackDegeneracy,
                    pricing,
                )
                return object : PersistentLpSolver by delegate {
                    override fun resolveBounds(): FloatLpResult? = delegate.resolveBounds().also { stopped = true }
                    override fun close() {
                        closed++
                        delegate.close()
                    }
                }
            }
        }
        val source = Problem(
            0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null),
            factors = arrayOf(Linear(intArrayOf(2), intArrayOf(0), LinearOp.LE, 1)),
        )
        ExactLiraSearchComponent(source).use { component ->
            component.solveWith(LpSolveContext(factory))
            val session = SearchSession(
                listOf(component),
                cancellation = Cancellation { stopped },
                atoms = SearchAtomRegistry(0),
            )
            assertIs<ComponentResult.Indeterminate>(session.initialize())
            assertIs<ComponentCheck.Indeterminate>(session.check())
            assertNull(session.model().valueOf<ExactLiraAssignment>(component))
        }
        assertEquals(1, created)
        assertEquals(1, closed)
    }

    @Test
    fun `cancellation invalidates a previously feasible complete check`() {
        var stopped = false
        val source = Problem(
            0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null),
            factors = arrayOf(Linear(intArrayOf(2), intArrayOf(0), LinearOp.LE, 1)),
        )
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(
                listOf(component),
                cancellation = Cancellation { stopped },
                atoms = SearchAtomRegistry(0),
            )
            session.initialize()
            assertIs<SearchResult.Satisfied>(session.solve(0))
            stopped = true
            assertIs<ComponentCheck.Indeterminate>(session.check())
        }
    }

    @Test
    fun `closed real and integer leaves cannot bypass withheld shared proofs`() {
        for (integer in listOf(false, true)) {
            val source = if (integer) {
                Problem(
                0,
                intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(2), null, null),
                factors = arrayOf(Linear(longArrayOf(1), intArrayOf(0), LinearOp.GE, 1L)),
            )
            } else {
                Problem(
                0,
                intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
                numRealVars = 1,
                realLower = doubleArrayOf(0.0),
                realUpper = doubleArrayOf(2.0),
                factors = arrayOf(
                    Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 1.0),
                ),
            )
            }
            val stats = SmtStatsSink()
            ExactLiraSearchComponent(source).use { component ->
                component.solveWith(LpSolveContext(certificationPolicy = LpCertificationPolicy { _, _ -> false }))
                component.observeWith(stats)
                val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(0))
                assertIs<ComponentResult.Consistent>(session.initialize())

                assertNull(component.nextBranch(session))
                assertIs<ComponentCheck.Indeterminate>(component.check(session))
                assertEquals(0L, stats.snapshot().privateChecks)
                assertTrue(stats.snapshot().continuation.calls > 0L)
                assertTrue(stats.snapshot().continuation.work.values.sum() > 0L)
            }
        }
    }

    @Test
    fun `shared theory recovers a stopped float owner and records continuation work`() {
        val source = Problem(
            0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(2.0),
            factors = arrayOf(
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(3.0), LinearOp.EQ, 1.0),
            ),
        )
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver = RevisedSimplex(model, cancellation, basisSolverFactory = { matrix ->
                val delegate = KotlinBasisSolver(matrix)
                object : BasisSolver by delegate {
                    override fun ftran(x: IndexedVector, expectedDensity: Double): Unit =
                        throw BasisArithmeticException("injected numerical solve failure")
                }
            })
        }
        val stats = SmtStatsSink()
        ExactLiraSearchComponent(source).use { component ->
            component.solveWith(LpSolveContext(engineFactory = factory))
            component.observeWith(stats)
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(0))
            assertIs<ComponentResult.Consistent>(session.initialize())

            val result = assertIs<SearchResult.Satisfied>(session.solve(0))
            val point = assertNotNull(result.model.valueOf<ExactLraAssignment>(component)).reals.single()
            assertEquals(BigFraction.ONE, BigFraction.ofLong(3) * point)
            assertTrue(stats.snapshot().continuation.successes > 0L)
            assertTrue(stats.snapshot().continuation.pivots > 0L)
            assertEquals(0L, stats.snapshot().privateChecks)
        }
    }

    @Test
    fun `a free coordinate restriction replaces a retained reduction before shared branching`() {
        val open = Bits(2).also {
            it.set(0);
            it.set(1)
        }
        val source = Problem(
            1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(0, 0), open, open),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(2, -2), intArrayOf(0, 1), LinearOp.GE, 1),
                Linear(intArrayOf(2, -2), intArrayOf(0, 1), LinearOp.LE, 3),
                Linear(intArrayOf(2, 2), intArrayOf(0, 1), LinearOp.GE, 1),
            ),
        )
        val stats = SmtStatsSink()
        ExactLiraSearchComponent(source).use { component ->
            component.observeWith(stats)
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(1))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
            assertNotNull(component.nextBranch(session))
            val requests = stats.snapshot().reductionRequests
            val split = assertNotNull(
                SourceBoundAtom.integerSplit(
                session,
                listOf(
                    SourceBoundTerm(SearchIntValue(0), BigFraction.ONE),
                    SourceBoundTerm(SearchIntValue(1), BigFraction.ONE),
                ),
                BigFraction.ofLong(2),
            )
            )
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Theory(split.negative)))

            component.nextBranch(session)
            assertTrue(stats.snapshot().reductionRequests > requests)
            repeat(8) {
                if (component.check(session) !is ComponentCheck.Feasible) {
                    val alternatives = assertNotNull(component.nextBranch(session))
                    val branch = alternatives.first { decision ->
                        val atom = assertIs<SourceBoundAtom>(
                            assertIs<RegisteredTheoryDecision>(
                            assertIs<SearchDecision.Theory>(decision).decision,
                        ).payload
                        )
                        val activity = atom.terms.fold(BigFraction.ZERO) { sum, term ->
                            val sourceValue = if (assertIs<SearchIntValue>(term.source).variable == 0) 2L else 1L
                            sum + term.coefficient * BigFraction.ofLong(sourceValue)
                        }
                        if (atom.upper) activity <= atom.threshold else activity >= atom.threshold
                    }
                    assertIs<ComponentResult.Consistent>(session.push(branch))
                }
            }
            assertIs<ComponentCheck.Feasible>(component.check(session))
            val point = assertNotNull(session.model().valueOf<ExactLiraAssignment>(component)).ints
            assertEquals(BigInteger.ONE, point[0] - point[1])
            assertTrue(point[0] + point[1] >= BigInteger.fromLong(3))
        }
    }
}
