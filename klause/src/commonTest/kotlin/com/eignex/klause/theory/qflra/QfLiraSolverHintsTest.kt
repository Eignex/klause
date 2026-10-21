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
import com.eignex.klause.solver.result.HintIndexOutcome
import com.eignex.klause.solver.result.SmtStatsSink
import com.eignex.klause.solver.search.ClauseSearchComponent
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.RegisteredTheoryDecision
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchIntValue
import com.eignex.klause.solver.search.SearchLearnedDbParams
import com.eignex.klause.solver.search.SearchRealValue
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
    fun `registered bounds should expire after delayed Boolean scopes retract`() {
        val model = Problem(
            numBoolVars = 5,
            intBounds = openBounds(0),
            numRealVars = 3,
            realLower = DoubleArray(3),
            realUpper = doubleArrayOf(1.0, 0.0, 0.0),
            factors = Array<Factor>(5) { head ->
                when {
                    head < 3 -> ReifiedRealLinear(head, intArrayOf(), doubleArrayOf(), IntArray(3) { it },
                        DoubleArray(3) { 1.0 }, LinearOp.GE, 0.0)
                    head == 3 -> ReifiedRealLinear(head, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                        doubleArrayOf(1.0), LinearOp.GE, 0.5)
                    else -> Linear(intArrayOf(), doubleArrayOf(), IntArray(3) { it }, DoubleArray(3) { 1.0 },
                        LinearOp.GE, 0.0)
                }
            },
        )
        ExactLiraSearchComponent(model).use { component ->
            val hints = assertNotNull(component.partialPointHints(Cancellation.Never, defaultOpening = true,
                defaultGraphSelections = true)).second
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(5))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertEquals(false, hints.preferredBool(3))
            repeat(3) { head ->
                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(head, true))))
            }
            val atom = assertNotNull(SourceBoundAtom.rationalSplit(session,
                listOf(SourceBoundTerm(SearchRealValue(0), BigFraction.ONE)), BigFraction.ZERO))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Theory(atom.positive)))
            session.popTo(0)

            val result = session.push(SearchDecision.Bool(Lit.make(3, true)))

            assertIs<ComponentResult.Consistent>(result)
        }
    }

    @Test
    fun `unchanged real points should preserve eager guard preferences without recapturing them`() {
        val model = Problem(
            numBoolVars = 5,
            intBounds = openBounds(0),
            numRealVars = 3,
            realLower = DoubleArray(3),
            realUpper = DoubleArray(3),
            factors = Array<Factor>(5) { head ->
                if (head < 4) ReifiedRealLinear(head, intArrayOf(), doubleArrayOf(), IntArray(3) { it },
                    DoubleArray(3) { 1.0 }, LinearOp.LE, 0.0)
                else Linear(intArrayOf(), doubleArrayOf(), IntArray(3) { it }, DoubleArray(3) { 1.0 },
                    LinearOp.GE, 0.0)
            },
        )
        val stats = SmtStatsSink()
        ExactLiraSearchComponent(model).use { component ->
            component.observeWith(stats)
            component.observeCompleteTraversal()
            val hints = assertNotNull(component.partialPointHints(Cancellation.Never, defaultOpening = true,
                defaultGraphSelections = true)).second
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(5))
            assertIs<ComponentResult.Consistent>(session.initialize())
            val captures = stats.snapshot().lpPointHintCaptures

            repeat(3) { head ->
                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(head, true))))
            }

            assertEquals(true, hints.preferredBool(3))
            assertEquals(captures, stats.snapshot().lpPointHintCaptures)
            assertEquals(3L, stats.snapshot().completeSearchStages?.get("POINT_HINT_STABLE_REUSE"))
        }
    }

    @Test
    fun `retained eager hints with Boolean coefficients should follow assigned source values`() {
        for (value in listOf(false, true)) {
            val declaration = object : Factor by ReifiedRealLinear(1, intArrayOf(), doubleArrayOf(),
                IntArray(3) { it }, DoubleArray(3) { 1.0 }, LinearOp.GE, 1.0) {
                override val linearForm: LinearForm = LinearForm.Conjunction(listOf(
                    TaggedLinearRow(
                        intArrayOf(Term.ofLit(Lit.make(0, true)), Term.ofRealVar(0),
                            Term.ofRealVar(1), Term.ofRealVar(2)),
                        RealConstants(RealConsts(doubleArrayOf(1.0)), RealConsts(DoubleArray(3) { 1.0 }), 1.0, false),
                        LinearOp.GE,
                        activator = 1,
                    ),
                ))
            }
            val model = Problem(
                numBoolVars = 4,
                intBounds = openBounds(0),
                numRealVars = 3,
                realLower = DoubleArray(3),
                realUpper = DoubleArray(3),
                factors = arrayOf(declaration, Linear(intArrayOf(), doubleArrayOf(), IntArray(3) { it },
                    DoubleArray(3) { 1.0 }, LinearOp.GE, 0.0)),
            )
            val stats = SmtStatsSink()
            ExactLiraSearchComponent(model).use { component ->
                component.observeWith(stats)
                component.observeCompleteTraversal()
                val hints = assertNotNull(component.partialPointHints(Cancellation.Never, defaultOpening = true,
                    defaultGraphSelections = true)).second
                val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(4))
                assertIs<ComponentResult.Consistent>(session.initialize())
                assertNull(hints.preferredBool(1))

                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, value))))

                assertEquals(value, hints.preferredBool(1))
                assertEquals(0L, stats.snapshot().completeSearchStages?.get("POINT_HINT_STABLE_REUSE") ?: 0L)
                assertEquals(1L, stats.snapshot().completeSearchStages?.get("POINT_REUSE_ACCEPTED"))
            }
        }
    }

    @Test
    fun `deferred compatible guards should be asserted before solving an incompatible guard`() {
        val model = Problem(
            numBoolVars = 3,
            intBounds = openBounds(0),
            numRealVars = 3,
            realLower = DoubleArray(3),
            realUpper = doubleArrayOf(1.0, 0.0, 0.0),
            factors = arrayOf(
                ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), IntArray(3) { it },
                    DoubleArray(3) { 1.0 }, LinearOp.LE, 0.0),
                ReifiedRealLinear(1, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                    doubleArrayOf(1.0), LinearOp.GE, 0.5),
                Linear(intArrayOf(), doubleArrayOf(), IntArray(3) { it }, DoubleArray(3) { 1.0 },
                    LinearOp.GE, 0.0),
            ),
        )
        val stats = SmtStatsSink()
        ExactLiraSearchComponent(model).use { component ->
            component.observeWith(stats)
            component.observeCompleteTraversal()
            val hints = assertNotNull(component.partialPointHints(Cancellation.Never, defaultOpening = true,
                defaultGraphSelections = true)).second
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(3))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertEquals(true, hints.preferredBool(0))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
            assertEquals(1L, stats.snapshot().completeSearchStages?.get("POINT_REUSE_ACCEPTED"))

            val result = session.push(SearchDecision.Bool(Lit.make(1, true)))

            assertIs<ComponentResult.Conflict>(result)
        }
    }

    @Test
    fun `deferred source checks should expire when backtracking replaces the retained point`() {
        val model = Problem(
            numBoolVars = 3,
            intBounds = openBounds(0),
            numRealVars = 3,
            realLower = DoubleArray(3),
            realUpper = doubleArrayOf(1.0, 0.0, 0.0),
            factors = arrayOf(
                ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), IntArray(3) { it },
                    DoubleArray(3) { 1.0 }, LinearOp.LE, 0.0),
                ReifiedRealLinear(1, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                    doubleArrayOf(1.0), LinearOp.GE, 0.5),
                Linear(intArrayOf(), doubleArrayOf(), IntArray(3) { it }, DoubleArray(3) { 1.0 },
                    LinearOp.GE, 0.0),
            ),
        )
        ExactLiraSearchComponent(model).use { component ->
            val hints = assertNotNull(component.partialPointHints(Cancellation.Never, defaultOpening = true,
                defaultGraphSelections = true)).second
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(3))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertEquals(true, hints.preferredBool(0))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
            session.popTo(0)
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, true))))
            assertEquals(false, hints.preferredBool(0))

            val result = session.push(SearchDecision.Bool(Lit.make(0, true)))

            assertIs<ComponentResult.Conflict>(result)
        }
    }

    @Test
    fun `registered real bounds should check a retained point before partial reuse`() {
        for (incompatible in listOf(false, true)) {
            val model = Problem(
                numBoolVars = 3,
                intBounds = openBounds(0),
                numRealVars = 3,
                realLower = DoubleArray(3),
                realUpper = doubleArrayOf(1.0, 0.0, 0.0),
                factors = arrayOf(
                    ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), IntArray(3) { it },
                        DoubleArray(3) { 1.0 }, LinearOp.LE, 0.0),
                    Linear(intArrayOf(), doubleArrayOf(), IntArray(3) { it }, DoubleArray(3) { 1.0 },
                        LinearOp.GE, 0.0),
                ),
            )
            val stats = SmtStatsSink()
            ExactLiraSearchComponent(model).use { component ->
                component.observeWith(stats)
                component.observeCompleteTraversal()
                val hints = assertNotNull(component.partialPointHints(Cancellation.Never, defaultOpening = true,
                    defaultGraphSelections = true)).second
                val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(3))
                assertIs<ComponentResult.Consistent>(session.initialize())
                val captures = stats.snapshot().lpPointHintCaptures
                val atom = assertNotNull(SourceBoundAtom.rationalSplit(session,
                    listOf(SourceBoundTerm(SearchRealValue(0), BigFraction.ONE)),
                    if (incompatible) BigFraction.ofLong(2L).reciprocal() else BigFraction.ZERO))

                val side = if (incompatible) atom.negative else atom.positive
                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Theory(side)))

                assertEquals(!incompatible, hints.preferredBool(0))
                assertEquals(if (incompatible) 0L else 1L,
                    stats.snapshot().completeSearchStages?.get("POINT_REUSE_ACCEPTED") ?: 0L)
                assertEquals(incompatible, stats.snapshot().lpPointHintCaptures > captures)
            }
        }
    }

    @Test
    fun `Boolean-only source rows should preserve checked point reuse in either storage mode`() {
        for (indexed in listOf(false, true)) {
            val heads = if (indexed) 300 else 2
            val reals = if (indexed) 256 else 3
            val model = Problem(
                numBoolVars = heads + 1,
                intBounds = openBounds(0),
                numRealVars = reals,
                realLower = DoubleArray(reals),
                realUpper = DoubleArray(reals),
                factors = Array<Factor>(heads + 2) { head ->
                    when {
                        head < heads -> ReifiedRealLinear(
                            head, intArrayOf(), doubleArrayOf(), IntArray(reals) { it }, DoubleArray(reals) { 1.0 },
                            LinearOp.LE, 0.0,
                        )
                        head == heads -> Linear(intArrayOf(), doubleArrayOf(), IntArray(reals) { it },
                            DoubleArray(reals) { 1.0 }, LinearOp.EQ, 0.0)
                        else -> Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
                    }
                },
            )
            val stats = SmtStatsSink()
            ExactLiraSearchComponent(model).use { component ->
                component.observeWith(stats)
                component.observeCompleteTraversal()
                assertNotNull(component.partialPointHints(Cancellation.Never, defaultOpening = true,
                    defaultGraphSelections = true))
                val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(heads + 1))
                assertIs<ComponentResult.Consistent>(session.initialize())
                val captures = stats.snapshot().lpPointHintCaptures

                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, true))))

                assertTrue(captures > 0L)
                assertEquals(captures, stats.snapshot().lpPointHintCaptures)
                assertEquals(2L, stats.snapshot().completeSearchStages?.get("POINT_REUSE_ACCEPTED"))
            }
        }
    }

    @Test
    fun `compatible true and false guards should reuse an indexed working point`() {
        val model = Problem(
            numBoolVars = 301,
            intBounds = openBounds(0),
            numRealVars = 256,
            realLower = DoubleArray(256),
            realUpper = DoubleArray(256),
            factors = Array<Factor>(301) { head ->
                if (head < 300) ReifiedRealLinear(
                    head, intArrayOf(), doubleArrayOf(), IntArray(256) { it }, DoubleArray(256) { 1.0 },
                    if (head % 2 == 0) LinearOp.LE else LinearOp.GE, 0.0, strict = head % 4 >= 2,
                ) else Linear(intArrayOf(), doubleArrayOf(), IntArray(256) { it }, DoubleArray(256) { 1.0 },
                    LinearOp.EQ, 0.0)
            },
        )
        val stats = SmtStatsSink()
        ExactLiraSearchComponent(model).use { component ->
            component.observeWith(stats)
            component.observeCompleteTraversal()
            val hints = assertNotNull(component.partialPointHints(Cancellation.Never, defaultOpening = true,
                defaultGraphSelections = true)).second
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(301))
            assertIs<ComponentResult.Consistent>(session.initialize())
            val captures = stats.snapshot().lpPointHintCaptures

            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(2, false))))

            assertEquals(true, hints.preferredBool(4))
            assertTrue(captures > 0L)
            assertEquals(captures, stats.snapshot().lpPointHintCaptures)
            assertEquals(2L, stats.snapshot().completeSearchStages?.get("POINT_REUSE_ACCEPTED"))
        }
    }

    @Test
    fun `an incompatible guard should replace its indexed working point`() {
        val model = Problem(
            numBoolVars = 301,
            intBounds = openBounds(0),
            numRealVars = 256,
            realLower = DoubleArray(256),
            realUpper = DoubleArray(256) { if (it == 0) 1.0 else 0.0 },
            factors = Array<Factor>(301) { head ->
                if (head < 300) ReifiedRealLinear(
                    head, intArrayOf(), doubleArrayOf(), IntArray(256) { it }, DoubleArray(256) { 1.0 },
                    LinearOp.LE, 0.0,
                ) else Linear(intArrayOf(), doubleArrayOf(), IntArray(256) { it }, DoubleArray(256) { 1.0 },
                    LinearOp.GE, 0.0)
            },
        )
        val stats = SmtStatsSink()
        ExactLiraSearchComponent(model).use { component ->
            component.observeWith(stats)
            component.observeCompleteTraversal()
            val hints = assertNotNull(component.partialPointHints(Cancellation.Never, defaultOpening = true,
                defaultGraphSelections = true)).second
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(301))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertEquals(true, hints.preferredBool(1))
            val captures = stats.snapshot().lpPointHintCaptures

            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, false))))

            assertEquals(false, hints.preferredBool(1))
            assertTrue(stats.snapshot().lpPointHintCaptures > captures)
            assertEquals(1L, stats.snapshot().completeSearchStages?.get("POINT_REUSE_INCOMPATIBLE"))
        }
    }

    @Test
    fun `reused indexed points should expire across retraction and restart`() {
        for (restart in listOf(false, true)) {
            val model = Problem(
                numBoolVars = 301,
                intBounds = openBounds(0),
                numRealVars = 256,
                realLower = DoubleArray(256),
                realUpper = DoubleArray(256),
                factors = Array<Factor>(301) { head ->
                    if (head < 300) ReifiedRealLinear(
                        head, intArrayOf(), doubleArrayOf(), IntArray(256) { it }, DoubleArray(256) { 1.0 },
                        LinearOp.LE, 0.0,
                    ) else Linear(intArrayOf(), doubleArrayOf(), IntArray(256) { it }, DoubleArray(256) { 1.0 },
                        LinearOp.EQ, 0.0)
                },
            )
            val stats = SmtStatsSink()
            ExactLiraSearchComponent(model).use { component ->
                component.observeWith(stats)
                component.observeCompleteTraversal()
                val hints = assertNotNull(component.partialPointHints(Cancellation.Never, defaultOpening = true,
                    defaultGraphSelections = true)).second
                val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(301))
                assertIs<ComponentResult.Consistent>(session.initialize())
                val captures = stats.snapshot().lpPointHintCaptures
                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))

                if (restart) component.onRestart(session) else session.popTo(0)
                assertNull(hints.preferredBool(1))
                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, true))))

                assertEquals(true, hints.preferredBool(2))
                assertEquals(captures + 1L, stats.snapshot().lpPointHintCaptures)
                assertEquals(1L, stats.snapshot().completeSearchStages?.get("POINT_REUSE_ACCEPTED"))
            }
        }
    }

    @Test
    fun `complete Boolean states should retain full source checking after indexed reuse`() {
        val model = Problem(
            numBoolVars = 301,
            intBounds = openBounds(0),
            numRealVars = 256,
            realLower = DoubleArray(256),
            realUpper = DoubleArray(256),
            factors = Array<Factor>(301) { head ->
                if (head < 300) ReifiedRealLinear(
                    head, intArrayOf(), doubleArrayOf(), IntArray(256) { it }, DoubleArray(256) { 1.0 },
                    if (head % 2 == 0) LinearOp.LE else LinearOp.GE, 0.0, strict = head % 4 >= 2,
                ) else Linear(intArrayOf(), doubleArrayOf(), IntArray(256) { it }, DoubleArray(256) { 1.0 },
                    LinearOp.EQ, 0.0)
            },
        )
        val stats = SmtStatsSink()
        ExactLiraSearchComponent(model).use { component ->
            component.observeWith(stats)
            component.observeCompleteTraversal()
            assertNotNull(component.partialPointHints(Cancellation.Never, defaultOpening = true,
                defaultGraphSelections = true))
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(301))
            assertIs<ComponentResult.Consistent>(session.initialize())

            repeat(300) { head ->
                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(head, head % 4 < 2))))
            }
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(300, true))))

            assertEquals(ComponentCheck.Feasible, component.check(session))
            assertEquals(300L, stats.snapshot().completeSearchStages?.get("POINT_REUSE_ACCEPTED"))
            assertEquals(0L, stats.snapshot().completeSearchLinearChecks)
            assertEquals(0L, stats.snapshot().completeSearchLinearPoints)
            assertEquals(1L, stats.snapshot().completeSearchStages?.get("POINT_REUSE_COMPLETE_ACCEPTED"))
        }
    }

    @Test
    fun `shared guard updates should retire indexed point reuse`() {
        val model = Problem(
            numBoolVars = 301,
            intBounds = openBounds(0),
            numRealVars = 256,
            realLower = DoubleArray(256),
            realUpper = DoubleArray(256) { if (it == 0) 1.0 else 0.0 },
            factors = Array<Factor>(302) { head ->
                when {
                    head < 300 -> ReifiedRealLinear(
                        head, intArrayOf(), doubleArrayOf(), IntArray(256) { it }, DoubleArray(256) { 1.0 },
                        LinearOp.LE, 0.0,
                    )
                    head == 300 -> Linear(intArrayOf(), doubleArrayOf(), IntArray(256) { it },
                        DoubleArray(256) { 1.0 }, LinearOp.GE, 0.0)
                    else -> ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                        doubleArrayOf(1.0), LinearOp.GE, 0.5)
                }
            },
        )
        val stats = SmtStatsSink()
        ExactLiraSearchComponent(model).use { component ->
            component.observeWith(stats)
            component.observeCompleteTraversal()
            val hints = assertNotNull(component.partialPointHints(Cancellation.Never, defaultOpening = true,
                defaultGraphSelections = true)).second
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(301))
            assertIs<ComponentResult.Consistent>(session.initialize())
            val captures = stats.snapshot().lpPointHintCaptures

            assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(0, true))))

            assertNull(hints.preferredBool(1))
            assertEquals(captures, stats.snapshot().lpPointHintCaptures)
            assertEquals(1L, stats.snapshot().completeSearchStages?.get("POINT_REUSE_INCOMPATIBLE"))
            assertEquals(0L, stats.snapshot().completeSearchStages?.get("POINT_REUSE_ACCEPTED") ?: 0L)
        }
    }

    @Test
    fun `indexed point hints should preserve strict comparison polarity beyond eager storage`() {
        val model = Problem(
            numBoolVars = 301,
            intBounds = openBounds(0),
            numRealVars = 256,
            realLower = DoubleArray(256),
            realUpper = DoubleArray(256),
            factors = Array<Factor>(301) { head ->
                if (head < 300) ReifiedRealLinear(
                    head, intArrayOf(), doubleArrayOf(), IntArray(256) { it }, DoubleArray(256) { 1.0 },
                    if (head % 2 == 0) LinearOp.LE else LinearOp.GE, 0.0, strict = head % 4 >= 2,
                ) else Linear(intArrayOf(), doubleArrayOf(), IntArray(256) { it }, DoubleArray(256) { 1.0 },
                    LinearOp.EQ, 0.0)
            },
        )
        ExactLiraSearchComponent(model).use { component ->
            assertNull(component.partialPointHints(Cancellation.Never))
            val hints = assertNotNull(component.partialPointHints(Cancellation.Never, defaultOpening = true,
                defaultGraphSelections = true)).second
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(301))

            assertIs<ComponentResult.Consistent>(session.initialize())

            for (head in 0..3) assertEquals(head < 2, hints.preferredBool(head))
        }
    }

    @Test
    fun `indexed point hints should use the containing stop after a private query expires`() {
        var privateStopped = false
        var containingStopped = false
        val stop = Cancellation { containingStopped }
        val model = Problem(
            numBoolVars = 301,
            intBounds = openBounds(0),
            numRealVars = 256,
            realLower = DoubleArray(256),
            realUpper = DoubleArray(256),
            factors = Array<Factor>(301) { head ->
                if (head < 300) ReifiedRealLinear(
                    head, intArrayOf(), doubleArrayOf(), IntArray(256) { it }, DoubleArray(256) { 1.0 },
                    LinearOp.LE, 0.0,
                ) else Linear(intArrayOf(), doubleArrayOf(), IntArray(256) { it }, DoubleArray(256) { 1.0 },
                    LinearOp.EQ, 0.0)
            },
        )
        ExactLiraSearchComponent(model).use { component ->
            component.useOperationAllowance { parent -> parent or Cancellation { privateStopped } }
            val hints = assertNotNull(component.partialPointHints(stop, defaultOpening = true,
                defaultGraphSelections = true)).second
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(301))
            assertIs<ComponentResult.Consistent>(session.initialize())

            privateStopped = true

            assertEquals(true, hints.preferredBool(0))
            containingStopped = true
            assertNull(hints.preferredBool(0))
        }
    }

    @Test
    fun `indexed point hints should omit shared activators`() {
        val model = Problem(
            numBoolVars = 301,
            intBounds = openBounds(0),
            numRealVars = 256,
            realLower = DoubleArray(256),
            realUpper = DoubleArray(256),
            factors = Array<Factor>(302) { head ->
                when {
                    head < 300 -> ReifiedRealLinear(
                        head, intArrayOf(), doubleArrayOf(), IntArray(256) { it }, DoubleArray(256) { 1.0 },
                        LinearOp.LE, 0.0,
                    )
                    head == 300 -> Linear(intArrayOf(), doubleArrayOf(), IntArray(256) { it },
                        DoubleArray(256) { 1.0 }, LinearOp.EQ, 0.0)
                    else -> ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                        doubleArrayOf(1.0), LinearOp.GE, 0.0)
                }
            },
        )
        ExactLiraSearchComponent(model).use { component ->
            val hints = assertNotNull(component.partialPointHints(Cancellation.Never, defaultOpening = true,
                defaultGraphSelections = true)).second
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(301))

            assertIs<ComponentResult.Consistent>(session.initialize())

            assertNull(hints.preferredBool(0))
            assertEquals(true, hints.preferredBool(1))
        }
    }

    @Test
    fun `indexed point hints should discard points across retraction and restart`() {
        for (restart in listOf(false, true)) {
            val model = Problem(
                numBoolVars = 301,
                intBounds = openBounds(0),
                numRealVars = 256,
                realLower = DoubleArray(256),
                realUpper = DoubleArray(256),
                factors = Array<Factor>(301) { head ->
                    if (head < 300) ReifiedRealLinear(
                        head, intArrayOf(), doubleArrayOf(), IntArray(256) { it }, DoubleArray(256) { 1.0 },
                        LinearOp.LE, 0.0,
                    ) else Linear(intArrayOf(), doubleArrayOf(), IntArray(256) { it }, DoubleArray(256) { 1.0 },
                        LinearOp.EQ, 0.0)
                },
            )
            ExactLiraSearchComponent(model).use { component ->
                val hints = assertNotNull(component.partialPointHints(Cancellation.Never, defaultOpening = true,
                    defaultGraphSelections = true)).second
                val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(301))
                assertIs<ComponentResult.Consistent>(session.initialize())
                assertEquals(true, hints.preferredBool(0))
                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(300, true))))
                assertEquals(true, hints.preferredBool(0))

                if (restart) component.onRestart(session) else session.popTo(0)

                assertNull(hints.preferredBool(0))
            }
        }
    }

    @Test
    fun `indexed point hints should decline an oversized private index`() {
        var decline: HintIndexOutcome? = null
        val model = Problem(
            numBoolVars = 32768,
            intBounds = openBounds(0),
            numRealVars = 256,
            realLower = DoubleArray(256),
            realUpper = DoubleArray(256),
            factors = Array<Factor>(300) { head -> ReifiedRealLinear(
                head, intArrayOf(), doubleArrayOf(), IntArray(256) { it }, DoubleArray(256) { 1.0 },
                LinearOp.LE, 0.0,
            ) },
        )
        ExactLiraSearchComponent(model).use { component ->
            val hints = component.partialPointHints(Cancellation.Never, defaultOpening = true,
                onDecline = { decline = it })

            assertNull(hints)
            assertEquals(HintIndexOutcome.STORAGE, decline)
        }
    }

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
                    val witness = assertNotNull(result.model.valueOf<ExactLraAssignment>(component))
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
                                .model.valueOf<ExactLiraAssignment>(component),
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
            val witness = assertNotNull(result.model.valueOf<ExactLraAssignment>(component))
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
            val hints = assertNotNull(component.partialPointHints(stop)).second
            component.useSolveStop(stop)
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(1))

            assertIs<ComponentResult.Indeterminate>(session.initialize())

            assertTrue(charged > 0L)
            assertNull(hints.preferredBool(0))
            assertNull(component.lpMetrics)
        }
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
                        .valueOf<ExactLiraAssignment>(component),
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
