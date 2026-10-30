package com.eignex.klause.theory.qflra

import com.eignex.klause.factor.arithmetic.ComparisonClause
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.RealConstants
import com.eignex.klause.ir.RealConsts
import com.eignex.klause.ir.TaggedLinearRow
import com.eignex.klause.ir.Term
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.pipeline.componentPlan
import com.eignex.klause.solver.pipeline.search
import com.eignex.klause.solver.result.SmtStatsSink
import com.eignex.klause.solver.search.ClauseSearchComponent
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.RegisteredTheoryDecision
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchIntValue
import com.eignex.klause.solver.search.SearchLearnedDbParams
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SourceBoundAtom
import com.eignex.klause.solver.search.SourceBoundPredicate
import com.eignex.klause.solver.search.SourceBoundTerm
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QfLiraSolverHintsTest {
    @Test
    fun `explicit integer point preparation should decline without charging its caller`() {
        var charged = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }
        val model = Problem(
            numBoolVars = 1,
            intBounds = openBounds(),
            factors = arrayOf(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0)),
        )
        ExactLiraSearchComponent(model).use { component ->
            assertNull(component.partialPointHints(stop))
            assertEquals(0L, charged)
        }
    }

    @Test
    fun `default real difference consultation should release hints across retraction and fresh checks`() {
        val model = Problem(
            numBoolVars = 2,
            intBounds = openBounds(0),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(0.0),
            factors = arrayOf(
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
        for (defaultOpening in listOf(false, true)) {
            var charged = 0L
            val stop = object : Cancellation {
                override fun isCancelled(): Boolean = false
                override fun workMeter(): WorkMeter = WorkMeter { charged += it }
            }
            ExactLiraSearchComponent(model).use { component ->
                val hints = assertNotNull(component.partialPointHints(stop, defaultOpening)).second
                val registrationWork = charged
                val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(2))

                assertIs<ComponentResult.Consistent>(session.initialize())

                assertEquals(if (defaultOpening) null else true, hints.preferredBool(0))
                assertEquals(!defaultOpening, component.partialPointHintAvailable())
                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))

                session.popTo(0)

                assertNull(hints.preferredBool(0))
                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, true))))
                assertEquals(if (defaultOpening) null else true, hints.preferredBool(0))
                assertEquals(!defaultOpening, component.partialPointHintAvailable())
                assertEquals(registrationWork, charged)
            }
        }
    }

    @Test
    fun `interrupted integer point preparation should retain its registration debit`() {
        var stopped = false
        var charged = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = stopped
            override fun workMeter(): WorkMeter = WorkMeter {
                charged += it
                stopped = true
            }
        }
        ExactLiraSearchComponent(partialModel()).use { component ->
            assertNull(component.partialPointHints(stop, defaultOpening = true))

            assertTrue(charged > 0L)
            assertTrue(stopped)
            assertEquals(false, component.partialPointHintAvailable())
        }
    }

    @Test
    fun `completed partial points should prefer the original comparison polarity`() {
        for (op in LinearOp.entries) {
            for (strict in listOf(false, true)) {
                val model = Problem(
                    numBoolVars = 1,
                    intBounds = openBounds(0),
                    numRealVars = 1,
                    realLower = doubleArrayOf(0.0),
                    realUpper = doubleArrayOf(0.0),
                    factors = arrayOf(
                        Linear(
                            intArrayOf(),
                            doubleArrayOf(),
                            intArrayOf(0),
                            doubleArrayOf(1.0),
                            LinearOp.EQ,
                            0.0,
                        ),
                        object : Factor by ReifiedRealLinear(
                            0,
                            intArrayOf(),
                            doubleArrayOf(),
                            intArrayOf(0),
                            doubleArrayOf(1.0),
                            LinearOp.LE,
                            0.0,
                        ) {
                            override val linearForm: LinearForm = LinearForm.Conjunction(
                                listOf(
                                    LinearRow.ofColumns(
                                        intArrayOf(),
                                        intArrayOf(0),
                                        RealConstants(
                                            RealConsts(doubleArrayOf()),
                                            RealConsts(doubleArrayOf(1.0)),
                                            0.0,
                                            strict,
                                        ),
                                        op,
                                        activator = 0,
                                    ),
                                ),
                            )
                        },
                    ),
                )
                ExactLiraSearchComponent(model).use { component ->
                    val hints = assertNotNull(component.partialPointHints(Cancellation.Never)).second
                    val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(1))
                    assertIs<ComponentResult.Consistent>(session.initialize())
                    val expected = when (op) {
                        LinearOp.LE, LinearOp.GE -> !strict
                        LinearOp.EQ -> true
                        LinearOp.NE -> false
                    }

                    assertEquals(expected, hints.preferredBool(0))
                    val result = assertIs<SearchResult.Satisfied>(session.solve(1, candidateHints = hints))
                    val witness = assertNotNull(result.model.valueOf(component.realAssignmentKey))
                    assertEquals(expected, witness.boolValue(0))
                    assertEquals(BigFraction.ZERO, witness.reals.single())
                }
            }
        }
    }

    @Test
    fun `partial point preferences should not accept fractional source integers`() {
        for (integerOnly in listOf(false, true)) {
            for (relation in listOf(LinearOp.EQ, LinearOp.GE)) {
                val model = if (integerOnly) {
                    Problem(
                        numBoolVars = 1,
                        intBounds = openBounds(2),
                        factors = arrayOf(
                            Linear(intArrayOf(1, 1), intArrayOf(0, 1), relation, 1),
                            Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
                            Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
                            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0),
                        ),
                    )
                } else {
                    Problem(
                        numBoolVars = 1,
                        intBounds = openBounds(),
                        numRealVars = 1,
                        realLower = doubleArrayOf(0.0),
                        realUpper = doubleArrayOf(0.0),
                        factors = arrayOf(
                            Linear(
                                intArrayOf(0),
                                doubleArrayOf(2.0),
                                intArrayOf(0),
                                doubleArrayOf(1.0),
                                relation,
                                1.0,
                            ),
                            Linear(
                                intArrayOf(0),
                                doubleArrayOf(1.0),
                                intArrayOf(0),
                                doubleArrayOf(1.0),
                                LinearOp.LE,
                                1.0,
                            ),
                            ReifiedRealLinear(
                                0,
                                intArrayOf(0),
                                doubleArrayOf(1.0),
                                intArrayOf(0),
                                doubleArrayOf(1.0),
                                LinearOp.LE,
                                0.0,
                            ),
                        ),
                    )
                }
                ExactLiraSearchComponent(model).use { component ->
                    val hints = assertNotNull(
                        component.partialPointHints(
                            Cancellation.Never,
                            defaultOpening = integerOnly,
                        ),
                    ).second
                    val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(1))
                    assertIs<ComponentResult.Consistent>(session.initialize())
                    assertEquals(false, hints.preferredBool(0))

                    val result = session.solve(1, candidateHints = hints)

                    if (relation == LinearOp.EQ) {
                        assertIs<SearchResult.Exhausted>(result)
                    } else {
                        val witness = assertNotNull(
                            assertIs<SearchResult.Satisfied>(result)
                                .model.valueOf(component.assignmentKey),
                        )
                        assertContentEquals(Array(if (integerOnly) 2 else 1) { BIG_ONE }, witness.ints)
                        assertEquals(false, witness.boolValue(0))
                    }
                }
            }
        }
    }

    @Test
    fun `point preferences should expire across integer branch retraction`() {
        for (integerOnly in listOf(false, true)) {
            val model = if (integerOnly) {
                Problem(
                    numBoolVars = 1,
                    intBounds = openBounds(2),
                    factors = arrayOf(
                        Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
                        Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
                        ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0),
                    ),
                )
            } else {
                Problem(
                    numBoolVars = 1,
                    intBounds = openBounds(),
                    numRealVars = 1,
                    realLower = doubleArrayOf(0.0),
                    realUpper = doubleArrayOf(0.0),
                    factors = arrayOf(
                        Linear(
                            intArrayOf(0),
                            doubleArrayOf(2.0),
                            intArrayOf(0),
                            doubleArrayOf(1.0),
                            LinearOp.GE,
                            1.0,
                        ),
                        ReifiedRealLinear(
                            0,
                            intArrayOf(0),
                            doubleArrayOf(1.0),
                            intArrayOf(0),
                            doubleArrayOf(1.0),
                            LinearOp.LE,
                            0.0,
                        ),
                    ),
                )
            }
            val component = ExactLiraSearchComponent(model)
            val hints = assertNotNull(
                component.partialPointHints(
                    Cancellation.Never,
                    defaultOpening = integerOnly,
                ),
            ).second
            try {
                val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(1))
                assertIs<ComponentResult.Consistent>(session.initialize())
                assertEquals(false, hints.preferredBool(0))
                val split = assertNotNull(
                    SourceBoundAtom.integerSplit(
                        session,
                        listOf(SourceBoundTerm(SearchIntValue(0), BigFraction.ONE)),
                        BigFraction.of(bigIntOf(1), bigIntOf(2)),
                    ),
                )

                assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Theory(split.positive)))
                session.popTo(0)
                assertNull(hints.preferredBool(0))
                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Theory(split.negative)))

                assertEquals(false, hints.preferredBool(0))
            } finally {
                component.close()
            }
            assertNull(hints.preferredBool(0))
        }
    }

    @Test
    fun `partial point hints should read source coordinates before auxiliary columns`() {
        val model = Problem(
            numBoolVars = 1,
            intBounds = openBounds(0),
            numRealVars = 2,
            realLower = doubleArrayOf(1.0, 2.0),
            realUpper = doubleArrayOf(1.0, 2.0),
            factors = arrayOf(
                Linear(
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0, 1),
                    doubleArrayOf(1.0, 2.0),
                    LinearOp.EQ,
                    5.0,
                ),
                ReifiedRealLinear(
                    0,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0, 1),
                    doubleArrayOf(1.0, 2.0),
                    LinearOp.GE,
                    6.0,
                ),
            ),
        )
        ExactLiraSearchComponent(model).use { component ->
            val hints = assertNotNull(component.partialPointHints(Cancellation.Never)).second
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(1))
            assertIs<ComponentResult.Consistent>(session.initialize())

            assertEquals(false, hints.preferredBool(0))
            val result = assertIs<SearchResult.Satisfied>(session.solve(1, candidateHints = hints))
            val witness = assertNotNull(result.model.valueOf(component.realAssignmentKey))
            assertEquals(listOf(BigFraction.ONE, BigFraction.ofLong(2)), witness.reals)
        }
    }

    @Test
    fun `point hints should require Boolean terms and follow their source scope`() {
        for (positive in listOf(false, true)) {
            val declaration = object : Factor by ReifiedRealLinear(
                1,
                intArrayOf(),
                doubleArrayOf(),
                intArrayOf(0),
                doubleArrayOf(1.0),
                LinearOp.GE,
                1.0,
            ) {
                override val linearForm: LinearForm = LinearForm.Conjunction(
                    listOf(
                        TaggedLinearRow(
                            intArrayOf(Term.ofLit(Lit.make(0, positive)), Term.ofRealVar(0)),
                            RealConstants(RealConsts(doubleArrayOf(1.0)), RealConsts(doubleArrayOf(1.0)), 1.0, false),
                            LinearOp.GE,
                            activator = 1,
                        ),
                    ),
                )
            }
            val model = Problem(
                numBoolVars = 3,
                intBounds = openBounds(0),
                numRealVars = 1,
                realLower = doubleArrayOf(0.0),
                realUpper = doubleArrayOf(0.0),
                factors = arrayOf(
                    declaration,
                    Linear(
                        intArrayOf(),
                        doubleArrayOf(),
                        intArrayOf(0),
                        doubleArrayOf(1.0),
                        LinearOp.EQ,
                        0.0,
                    ),
                ),
            )
            ExactLiraSearchComponent(model).use { component ->
                val hints = assertNotNull(component.partialPointHints(Cancellation.Never)).second
                val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(3))
                assertIs<ComponentResult.Consistent>(session.initialize())
                assertNull(hints.preferredBool(1))
                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, false))))
                assertEquals(!positive, hints.preferredBool(1))

                session.popTo(0)

                assertNull(hints.preferredBool(1))
                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
                assertEquals(positive, hints.preferredBool(1))
                component.releasePartialPointHints()
                assertNull(hints.preferredBool(1))
            }
        }
    }

    @Test
    fun `interrupted point evaluation should spend the existing meter and publish no hints`() {
        for (incremental in listOf(false, true)) {
            var halted = false
            var charged = 0L
            val stop = object : Cancellation {
                override fun isCancelled(): Boolean = halted
                override fun workMeter(): WorkMeter = WorkMeter { units ->
                    charged += units
                    if (units >= 4L) halted = true
                }
            }
            val model = Problem(
                numBoolVars = 1,
                intBounds = openBounds(0),
                numRealVars = 1,
                realLower = doubleArrayOf(0.0),
                realUpper = doubleArrayOf(0.0),
                factors = arrayOf(
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
                if (incremental) component.useIncrementalLinearPropagation()
                val hints = assertNotNull(component.partialPointHints(stop)).second
                component.useSolveStop(stop)
                val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(1))

                assertIs<ComponentResult.Indeterminate>(session.initialize())

                assertTrue(charged > 0L)
                assertNull(hints.preferredBool(0))
                assertNull(component.lpMetrics)
            }
        }
    }

    @Test
    fun `incremental point hint polling should preserve charged work`() {
        val model = Problem(
            numBoolVars = 1,
            intBounds = openBounds(0),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(0.0),
            factors = arrayOf(
                ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0),
                    LinearOp.LE, 0.0),
            ),
        )
        val work = listOf(false, true).map { incremental ->
            var charged = 0L
            val stop = object : Cancellation {
                override fun isCancelled() = false
                override fun workMeter() = WorkMeter { charged += it }
            }
            ExactLiraSearchComponent(model).use { component ->
                if (incremental) component.useIncrementalLinearPropagation()
                val hints = assertNotNull(component.partialPointHints(stop)).second
                component.useSolveStop(stop)
                val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(1))
                assertIs<ComponentResult.Consistent>(session.initialize())
                assertEquals(true, hints.preferredBool(0))
            }
            charged
        }

        assertTrue(work.first() > 0L)
        assertEquals(work.first(), work.last())
    }

    @Test
    fun `contradictory primitive proposals should decline their shared activator`() {
        val model = Problem(
            numBoolVars = 1,
            intBounds = openBounds(0),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(0.0),
            factors = arrayOf(
                ReifiedRealLinear(
                    0,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.LE,
                    0.0,
                ),
                ReifiedRealLinear(
                    0,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.GE,
                    1.0,
                ),
            ),
        )
        ExactLiraSearchComponent(model).use { component ->
            val hints = assertNotNull(component.partialPointHints(Cancellation.Never)).second
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(1))

            assertIs<ComponentResult.Consistent>(session.initialize())

            assertNull(hints.preferredBool(0))
            assertNull(session.boolValue(0))
        }
    }

    @Test
    fun `containing cancellation should withhold hints under a separate theory token`() {
        for (integerOnly in listOf(false, true)) {
            var cancelled = false
            val model = if (integerOnly) {
                partialModel()
            } else {
                Problem(
                    numBoolVars = 1,
                    intBounds = openBounds(0),
                    numRealVars = 1,
                    realLower = doubleArrayOf(0.0),
                    realUpper = doubleArrayOf(0.0),
                    factors = arrayOf(
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
            }
            ExactLiraSearchComponent(model).use { component ->
                val hints = assertNotNull(
                    component.partialPointHints(
                        Cancellation.Never,
                        defaultOpening = integerOnly,
                    ),
                ).second
                component.useSolveStop(Cancellation.Never)
                val session = SearchSession(
                    listOf(component),
                    cancellation = Cancellation { cancelled },
                    atoms = SearchAtomRegistry(model.numBoolVars),
                )
                assertIs<ComponentResult.Consistent>(session.initialize())
                assertEquals(!integerOnly, hints.preferredBool(0))

                cancelled = true

                assertNull(hints.preferredBool(0))
                assertNull(session.boolValue(0))
            }
        }
    }

    @Test
    fun `an unresolved member should not borrow another row preference for its activator`() {
        val declaration = object : Factor by ReifiedRealLinear(
            0,
            intArrayOf(),
            doubleArrayOf(),
            intArrayOf(0),
            doubleArrayOf(1.0),
            LinearOp.LE,
            0.0,
        ) {
            override val linearForm: LinearForm = LinearForm.Conjunction(
                listOf(
                    LinearRow.ofColumns(
                        intArrayOf(),
                        intArrayOf(0),
                        RealConstants(RealConsts(doubleArrayOf()), RealConsts(doubleArrayOf(1.0)), 0.0, false),
                        LinearOp.LE,
                        activator = 0,
                    ),
                    TaggedLinearRow(
                        intArrayOf(Term.ofLit(Lit.make(1, true)), Term.ofRealVar(0)),
                        RealConstants(RealConsts(doubleArrayOf(1.0)), RealConsts(doubleArrayOf(1.0)), 0.0, false),
                        LinearOp.LE,
                        activator = 0,
                    ),
                ),
            )
        }
        val model = Problem(
            numBoolVars = 2,
            intBounds = openBounds(0),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(0.0),
            factors = arrayOf(
                declaration,
                Linear(
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.EQ,
                    0.0,
                ),
            ),
        )
        ExactLiraSearchComponent(model).use { component ->
            val hints = assertNotNull(component.partialPointHints(Cancellation.Never)).second
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(2))
            assertIs<ComponentResult.Consistent>(session.initialize())

            assertNull(hints.preferredBool(0))
            assertNull(session.boolValue(0))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, false))))
            assertEquals(true, hints.preferredBool(0))
            session.popTo(0)
            assertNull(hints.preferredBool(0))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, true))))
            assertNull(hints.preferredBool(0))
        }
    }

    @Test
    fun `default mixed disjunctions should prefer a completed point unless an explicit allowance is set`() {
        val model = Problem(
            numBoolVars = 1,
            intBounds = openBounds(),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
            factors = arrayOf(
                Clause(intArrayOf(Lit.make(0, true))),
                ReifiedRealLinear(
                    0,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.LE,
                    1.0,
                ),
                ComparisonClause(intArrayOf(0, 0), arrayOf(LinearOp.GE, LinearOp.LE), longArrayOf(1, 0)),
            ),
        )
        for ((flips, expected) in listOf(null to 0L, 0L to 1L, 4L to 1L)) {
            model.componentPlan().search(
                model,
                emptyMap(),
                Long.MAX_VALUE,
                Cancellation.Never,
                SearchLearnedDbParams(),
                null,
                openHintFlips = flips,
            ).use { planned ->
                val component = assertIs<ExactLiraSearchComponent>(planned.theory)
                component.useMixedPointCompletion()
                assertIs<ComponentResult.Consistent>(planned.session.initialize())

                val result = planned.session.solve(0)

                val witness = assertNotNull(
                    assertIs<SearchResult.Satisfied>(result).model
                        .valueOf(component.assignmentKey),
                )
                assertEquals(bigIntOf(expected), witness.intValue(0))
            }
        }
    }

    @Test
    fun `default mixed disequality choices should prefer a side satisfied by the current point`() {
        val model = Problem(
            numBoolVars = 2,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 1), longArrayOf(3, 2), null, null),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
            factors = arrayOf(
                Clause(intArrayOf(Lit.make(0, false))),
                Clause(intArrayOf(Lit.make(1, true))),
                ReifiedLinear(0, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
                ReifiedRealLinear(
                    1,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.LE,
                    1.0,
                ),
            ),
        )
        for (guided in listOf(false, true)) {
            ExactLiraSearchComponent(model).use { component ->
                assertNotNull(component.partialPointHints(Cancellation.Never, defaultOpening = true))
                component.useMixedPointCompletion()
                if (guided) component.useDefaultDisjunctionHints()
                val session = SearchSession(
                    listOf(ClauseSearchComponent(model.factors.filterIsInstance<Clause>()), component),
                    atoms = SearchAtomRegistry(2),
                )
                assertIs<ComponentResult.Consistent>(session.initialize())

                val choices = assertNotNull(component.nextBranch(session))

                assertEquals(2, choices.size)
                val chosen = assertIs<SearchDecision.Theory>(choices.first()).decision
                val decision = assertIs<RegisteredTheoryDecision>(chosen)
                val side = assertIs<SourceBoundPredicate>(decision.payload).sourceBound
                assertEquals(guided, side.upper)
                assertEquals(if (guided) BigFraction.MINUS_ONE else BigFraction.ZERO, side.threshold)
            }
        }
    }

    @Test
    fun `complete traversal observations should exclude private query openings`() {
        for (complete in listOf(false, true)) {
            val stats = SmtStatsSink()
            ExactLiraSearchComponent(partialModel()).use { component ->
                component.observeWith(stats)
                if (complete) component.observeCompleteTraversal()
                val hints = assertNotNull(component.partialPointHints(Cancellation.Never, defaultOpening = true)).second
                assertNull(hints.preferredBool(0))
                val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(2))
                assertIs<ComponentResult.Consistent>(session.initialize())
                assertEquals(false, hints.preferredBool(0))
                assertEquals(
                    if (complete) (0 until 2).count { session.boolValue(it) == null } else null,
                    stats.snapshot().completeSearchMinUnassignedBooleans,
                )
                assertEquals(if (complete) 0L else null, stats.snapshot().completeSearchLinearChecks)

                repeat(2) {
                    assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, false))))
                    assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, false))))
                    session.popTo(0)
                }

                val observed = stats.snapshot()
                assertEquals(if (complete) 2 else null, observed.completeSearchBooleanVariables)
                assertEquals(if (complete) 0 else null, observed.completeSearchMinUnassignedBooleans)
                assertEquals(complete, (observed.completeSearchLinearChecks ?: 0L) > 0L)
                assertEquals(complete, (observed.completeSearchLinearPoints ?: 0L) > 0L)
                assertEquals(observed.completeSearchLinearChecks, observed.completeSearchLinearOutcomes?.values?.sum())
                assertEquals(complete, observed.completeSearchStages != null)
                assertEquals(if (complete) 1L else null, observed.completeSearchStages?.get("POINT_HINT_UNAVAILABLE"))
                assertEquals(if (complete) 1L else null, observed.completeSearchStages?.get("POINT_HINT_PREFERRED"))
                assertEquals(complete, (observed.completeSearchStages?.get("POINT_HINT_LP_CAPTURE") ?: 0L) > 0L)
            }
        }
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
}
