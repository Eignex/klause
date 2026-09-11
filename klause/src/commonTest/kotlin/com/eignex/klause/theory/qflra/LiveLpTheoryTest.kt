package com.eignex.klause.theory.qflra

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.LpEngineFactory
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.PersistentLpSolver
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.pipeline.componentPlan
import com.eignex.klause.solver.pipeline.search
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
import com.eignex.klause.theory.TheoryCheck
import com.eignex.klause.theory.TheoryContext
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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
            ),
        )
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(0))
            session.initialize()
            val result = assertIs<SearchResult.Satisfied>(session.solve(0))
            val witness = assertNotNull(result.model.valueOf<ExactLraAssignment>(component)).reals.single()
            assertTrue(witness > BigFraction.ZERO && witness <= BigFraction.ONE)
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
}
