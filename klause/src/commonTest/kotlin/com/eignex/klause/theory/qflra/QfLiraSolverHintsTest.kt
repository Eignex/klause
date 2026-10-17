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
import com.eignex.klause.ir.WideConstants
import com.eignex.klause.ir.WideConsts
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
import com.eignex.klause.theory.EncodedTheoryModel
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.shl
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QfLiraSolverHintsTest {
    @Test
    fun `selected queries should preserve the original comparison polarity`() {
        for (op in LinearOp.entries) {
            for (strict in listOf(false, true)) {
                val declaration = object : Factor by ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(),
                    intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 0.0) {
                    override val linearForm: LinearForm = LinearForm.Conjunction(listOf(
                        LinearRow.ofColumns(intArrayOf(), intArrayOf(0),
                            RealConstants(RealConsts(doubleArrayOf()), RealConsts(doubleArrayOf(1.0)), 0.0, strict),
                            op, activator = 0),
                    ))
                }
                val model = Problem(
                    numBoolVars = 65530,
                    intBounds = openBounds(0),
                    numRealVars = 3,
                    realLower = DoubleArray(3),
                    realUpper = DoubleArray(3),
                    factors = arrayOf(
                        Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0),
                            doubleArrayOf(1.0), LinearOp.EQ, 0.0),
                        ReifiedRealLinear(1, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1, 2),
                            doubleArrayOf(1.0, 1.0, 1.0), LinearOp.LE, 0.0),
                        declaration,
                    ),
                )
                ExactLiraSearchComponent(model).use { component ->
                    val hints = assertNotNull(
                        component.partialPointHints(Cancellation.Never, defaultOpening = true),
                    ).second
                    val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(model.numBoolVars))
                    assertIs<ComponentResult.Consistent>(session.initialize())
                    val expected = when (op) {
                        LinearOp.LE, LinearOp.GE -> !strict
                        LinearOp.EQ -> true
                        LinearOp.NE -> false
                    }

                    assertEquals(expected, hints.preferredBool(0))
                    assertNull(session.boolValue(0))
                }
            }
        }
    }

    @Test
    fun `a graph opening should release selected query membership`() {
        val model = Problem(
            numBoolVars = 65534,
            intBounds = openBounds(0),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(0.0),
            factors = arrayOf(ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                doubleArrayOf(1.0), LinearOp.LE, 0.0)),
        )
        val stats = SmtStatsSink()
        ExactLiraSearchComponent(model).use { component ->
            component.observeWith(stats)
            component.observeCompleteTraversal()
            val hints = assertNotNull(component.partialPointHints(Cancellation.Never, defaultOpening = true)).second
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(model.numBoolVars))

            assertIs<ComponentResult.Consistent>(session.initialize())

            assertNull(hints.preferredBool(0))
            assertEquals(false, component.partialPointHintAvailable())
            assertEquals(1L, stats.snapshot().completeSearchStages?.get("POINT_HINT_SELECTED_GRAPH_DECLINED"))
        }
    }

    @Test
    fun `large primitive groups should permit selected queries only in the default opening`() {
        val model = Problem(
            numBoolVars = 65530,
            intBounds = openBounds(0),
            numRealVars = 3,
            realLower = DoubleArray(3),
            realUpper = DoubleArray(3),
            factors = arrayOf(
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0),
                    doubleArrayOf(1.0), LinearOp.EQ, 0.0),
                ReifiedRealLinear(63, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                    doubleArrayOf(1.0), LinearOp.LE, 0.0),
                ReifiedRealLinear(65529, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1, 2),
                    doubleArrayOf(1.0, 1.0, 1.0), LinearOp.LE, 0.0),
            ),
        )
        for (defaultOpening in listOf(false, true)) {
            ExactLiraSearchComponent(model).use { component ->
                val stats = SmtStatsSink()
                component.observeWith(stats)
                component.observeCompleteTraversal()
                val prepared = component.partialPointHints(Cancellation.Never, defaultOpening)
                if (!defaultOpening) {
                    assertNull(prepared)
                    return@use
                }
                val (variables, hints) = assertNotNull(prepared)
                val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(model.numBoolVars))
                assertIs<ComponentResult.Consistent>(session.initialize())
                val before = component.lpMetrics
                val stages = stats.snapshot().completeSearchStages
                assertTrue((stages?.get("POINT_HINT_SELECTED_CAPTURE") ?: 0L) > 0L, stages.toString())

                assertContentEquals(intArrayOf(63, 65529), variables)
                assertEquals(true, hints.preferredBool(63))
                assertEquals(true, hints.preferredBool(65529))
                assertNull(hints.preferredBool(2))
                assertNull(hints.preferredBool(-1))
                assertNull(hints.preferredBool(model.numBoolVars))
                assertNull(session.boolValue(63))
                assertEquals(before, component.lpMetrics)
            }
        }
    }

    @Test
    fun `selected query cursors should preserve complete groups in either query direction`() {
        val unconditional = Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0),
            doubleArrayOf(1.0), LinearOp.EQ, 0.0)
        val model = Problem(
            numBoolVars = 65530,
            intBounds = openBounds(0),
            numRealVars = 3,
            realLower = DoubleArray(3),
            realUpper = DoubleArray(3),
            factors = arrayOf(
                unconditional,
                ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                    doubleArrayOf(1.0), LinearOp.LE, 0.0),
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(2),
                    doubleArrayOf(1.0), LinearOp.EQ, 0.0),
                ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(1),
                    doubleArrayOf(1.0), LinearOp.GE, 0.0),
                ReifiedRealLinear(63, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                    doubleArrayOf(1.0), LinearOp.GE, 1.0),
                ReifiedRealLinear(65529, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1, 2),
                    doubleArrayOf(1.0, 1.0, 1.0), LinearOp.LE, 0.0),
            ),
        )
        val stats = SmtStatsSink()
        ExactLiraSearchComponent(model).use { component ->
            component.observeWith(stats)
            component.observeCompleteTraversal()
            val hints = assertNotNull(component.partialPointHints(Cancellation.Never, defaultOpening = true)).second
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(model.numBoolVars))
            assertIs<ComponentResult.Consistent>(session.initialize())

            for (variable in listOf(65529, 0, 63, 0, 65529, 63, 65529)) {
                assertEquals(variable != 63, hints.preferredBool(variable))
            }

            val stages = stats.snapshot().completeSearchStages
            assertTrue((stages?.get("POINT_HINT_SELECTED_SORTED") ?: 0L) > 0L, stages.toString())
            assertTrue((stages?.get("POINT_HINT_SELECTED_CURSOR_REUSED") ?: 0L) > 0L, stages.toString())
        }
    }

    @Test
    fun `selected query cursors should recheck Boolean dependencies after backward queries`() {
        val declaration = object : Factor by ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(),
            intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 0.0) {
            override val linearForm: LinearForm = LinearForm.Conjunction(listOf(
                LinearRow.ofColumns(intArrayOf(), intArrayOf(0),
                    RealConstants(RealConsts(doubleArrayOf()), RealConsts(doubleArrayOf(1.0)), 0.0, false),
                    LinearOp.LE, activator = 0),
                TaggedLinearRow(intArrayOf(Term.ofLit(Lit.make(2, true)), Term.ofRealVar(0)),
                    RealConstants(RealConsts(doubleArrayOf(1.0)), RealConsts(doubleArrayOf(1.0)), 0.0, false),
                    LinearOp.LE, activator = 0),
            ))
        }
        val model = Problem(
            numBoolVars = 65530,
            intBounds = openBounds(0),
            numRealVars = 3,
            realLower = DoubleArray(3),
            realUpper = DoubleArray(3),
            factors = arrayOf(
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0),
                    doubleArrayOf(1.0), LinearOp.EQ, 0.0),
                declaration,
                ReifiedRealLinear(1, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1, 2),
                    doubleArrayOf(1.0, 1.0, 1.0), LinearOp.LE, 0.0),
            ),
        )
        ExactLiraSearchComponent(model).use { component ->
            val hints = assertNotNull(component.partialPointHints(Cancellation.Never, defaultOpening = true)).second
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(model.numBoolVars))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertEquals(true, hints.preferredBool(1))
            assertNull(hints.preferredBool(0))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(2, false))))
            assertEquals(true, hints.preferredBool(1))
            assertEquals(true, hints.preferredBool(0))

            session.popTo(0)

            assertNull(hints.preferredBool(0))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(2, true))))
            assertEquals(true, hints.preferredBool(1))
            assertNull(hints.preferredBool(0))
        }
    }

    @Test
    fun `selected query cursors should retain the complete registration storage boundary`() {
        for (groups in listOf(64, 65)) {
            val model = Problem(
                numBoolVars = 65530,
                intBounds = openBounds(0),
                numRealVars = 549,
                realLower = DoubleArray(549),
                realUpper = DoubleArray(549),
                factors = Array(groups) { variable ->
                    ReifiedRealLinear(variable, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1, 2),
                        doubleArrayOf(1.0, 1.0, 1.0), LinearOp.LE, 0.0)
                },
            )
            ExactLiraSearchComponent(model).use { component ->
                val prepared = component.partialPointHints(Cancellation.Never, defaultOpening = true)

                if (groups == 64) assertEquals(groups, assertNotNull(prepared).first.size) else assertNull(prepared)
            }
        }
    }

    @Test
    fun `selected primitive queries should require agreement from every member`() {
        for ((bound, expected) in listOf(0.0 to true, -1.0 to null)) {
            val first = ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                doubleArrayOf(1.0), LinearOp.LE, 0.0)
            val second = ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                doubleArrayOf(1.0), LinearOp.LE, bound)
            val other = ReifiedRealLinear(1, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1, 2),
                doubleArrayOf(1.0, 1.0, 1.0), LinearOp.LE, 0.0)
            val unconditional = Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0),
                doubleArrayOf(1.0), LinearOp.EQ, 0.0)
            for (factors in listOf(arrayOf<Factor>(unconditional, first, second, other),
                arrayOf<Factor>(first, unconditional, second, other),
                arrayOf<Factor>(unconditional, first, other, second),
                arrayOf<Factor>(unconditional, other, second, first),
                arrayOf<Factor>(first, unconditional, other, second))) {
                val model = Problem(
                    numBoolVars = 65530,
                    intBounds = openBounds(0),
                    numRealVars = 3,
                    realLower = DoubleArray(3),
                    realUpper = DoubleArray(3),
                    factors = factors,
                )
                ExactLiraSearchComponent(model).use { component ->
                    val hints = assertNotNull(
                        component.partialPointHints(Cancellation.Never, defaultOpening = true),
                    ).second
                    val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(model.numBoolVars))
                    assertIs<ComponentResult.Consistent>(session.initialize())

                    assertEquals(expected, hints.preferredBool(0))
                    assertEquals(true, hints.preferredBool(1))
                    assertNull(session.boolValue(0))
                }
            }
        }
    }

    @Test
    fun `selected primitive queries should retain unresolved Boolean dependencies`() {
        val declaration = object : Factor by ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(),
            intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 0.0) {
            override val linearForm: LinearForm = LinearForm.Conjunction(listOf(
                LinearRow.ofColumns(intArrayOf(), intArrayOf(0),
                    RealConstants(RealConsts(doubleArrayOf()), RealConsts(doubleArrayOf(1.0)), 0.0, false),
                    LinearOp.LE, activator = 0),
                TaggedLinearRow(intArrayOf(Term.ofLit(Lit.make(2, true)), Term.ofRealVar(0)),
                    RealConstants(RealConsts(doubleArrayOf(1.0)), RealConsts(doubleArrayOf(1.0)), 0.0, false),
                    LinearOp.LE, activator = 0),
            ))
        }
        val model = Problem(
            numBoolVars = 65530,
            intBounds = openBounds(0),
            numRealVars = 3,
            realLower = DoubleArray(3),
            realUpper = DoubleArray(3),
            factors = arrayOf(
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0),
                    doubleArrayOf(1.0), LinearOp.EQ, 0.0),
                ReifiedRealLinear(1, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1, 2),
                    doubleArrayOf(1.0, 1.0, 1.0), LinearOp.LE, 0.0),
                declaration,
            ),
        )
        ExactLiraSearchComponent(model).use { component ->
            val hints = assertNotNull(component.partialPointHints(Cancellation.Never, defaultOpening = true)).second
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(model.numBoolVars))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertNull(hints.preferredBool(0))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(2, false))))
            assertEquals(true, hints.preferredBool(0))

            session.popTo(0)

            assertNull(hints.preferredBool(0))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(2, true))))
            assertNull(hints.preferredBool(0))
        }
    }

    @Test
    fun `cancelled selected queries should debit an existing containing meter`() {
        val model = Problem(
            numBoolVars = 65530,
            intBounds = openBounds(0),
            numRealVars = 3,
            realLower = DoubleArray(3),
            realUpper = DoubleArray(3),
            factors = arrayOf(
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0),
                    doubleArrayOf(1.0), LinearOp.EQ, 0.0),
                ReifiedRealLinear(1, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1, 2),
                    doubleArrayOf(1.0, 1.0, 1.0), LinearOp.LE, 0.0),
                ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                    doubleArrayOf(1.0), LinearOp.LE, 0.0),
            ),
        )
        for (registrationMeter in listOf(false, true)) {
            var armed = false
            var halted = false
            var charged = 0L
            val stop = object : Cancellation {
                override fun isCancelled(): Boolean = halted
                override fun workMeter(): WorkMeter = WorkMeter {
                    charged += it
                    if (armed) halted = true
                }
            }
            ExactLiraSearchComponent(model).use { component ->
                val hints = assertNotNull(component.partialPointHints(
                    if (registrationMeter) stop else Cancellation.Never,
                    defaultOpening = true,
                )).second
                component.useSolveStop(stop)
                val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(model.numBoolVars))
                assertIs<ComponentResult.Consistent>(session.initialize())
                assertEquals(true, hints.preferredBool(0))
                val before = charged
                armed = true

                assertNull(hints.preferredBool(0))

                assertTrue(halted)
                assertTrue(charged > before)
                assertNull(session.boolValue(0))
            }
        }
    }

    @Test
    fun `selected hint registration should retain its storage limit`() {
        val model = Problem(
            numBoolVars = 65530,
            intBounds = openBounds(0),
            numRealVars = 600,
            realLower = DoubleArray(600),
            realUpper = DoubleArray(600),
            factors = arrayOf(ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1, 2),
                doubleArrayOf(1.0, 1.0, 1.0), LinearOp.LE, 0.0)),
        )
        ExactLiraSearchComponent(model).use { component ->
            val hints = component.partialPointHints(Cancellation.Never, defaultOpening = true)

            assertNull(hints)
            assertEquals(false, component.partialPointHintAvailable())
        }
    }

    @Test
    fun `selected point capture should reserve complete coordinate storage`() {
        val model = Problem(
            numBoolVars = 65530,
            intBounds = openBounds(0),
            numRealVars = 540,
            realLower = DoubleArray(540) { 1e20 },
            realUpper = DoubleArray(540) { 1e20 },
            factors = arrayOf(
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1, 2),
                    doubleArrayOf(1.0, 1.0, -2.0), LinearOp.EQ, 0.0),
                ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1, 2),
                    doubleArrayOf(1.0, 1.0, -2.0), LinearOp.LE, 0.0),
            ),
        )
        val stats = SmtStatsSink()
        ExactLiraSearchComponent(model).use { component ->
            component.observeWith(stats)
            component.observeCompleteTraversal()
            val hints = assertNotNull(component.partialPointHints(Cancellation.Never, defaultOpening = true)).second
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(model.numBoolVars))

            assertIs<ComponentResult.Consistent>(session.initialize())

            assertNull(hints.preferredBool(0))
            assertEquals(false, component.partialPointHintAvailable())
            assertTrue((stats.snapshot().completeSearchStages?.get("POINT_HINT_SELECTED_STORAGE_DECLINED") ?: 0L) > 0L)
        }
    }

    @Test
    fun `selected point hints should decline encoded private real frames`() {
        val source = Problem(
            numBoolVars = 65530,
            intBounds = openBounds(0),
            numRealVars = 3,
            realLower = DoubleArray(3),
            realUpper = DoubleArray(3),
            factors = arrayOf(ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1, 2),
                doubleArrayOf(1.0, 1.0, 1.0), LinearOp.LE, 0.0)),
        )
        val encoded = EncodedTheoryModel(source, source.factors, null, 4)
        ExactLiraSearchComponent(encoded).use { component ->
            val hints = component.partialPointHints(Cancellation.Never, defaultOpening = true)

            assertNull(hints)
        }
    }

    @Test
    fun `selected points should follow clause-only source scopes`() {
        val model = Problem(
            numBoolVars = 65530,
            intBounds = openBounds(0),
            numRealVars = 3,
            realLower = DoubleArray(3),
            realUpper = DoubleArray(3),
            factors = arrayOf(
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0),
                    doubleArrayOf(1.0), LinearOp.EQ, 0.0),
                ReifiedRealLinear(1, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1, 2),
                    doubleArrayOf(1.0, 1.0, 1.0), LinearOp.LE, 0.0),
                ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                    doubleArrayOf(1.0), LinearOp.LE, 0.0),
            ),
        )
        ExactLiraSearchComponent(model).use { component ->
            val hints = assertNotNull(component.partialPointHints(Cancellation.Never, defaultOpening = true)).second
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(model.numBoolVars))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertEquals(true, hints.preferredBool(0))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(2, false))))
            assertEquals(true, hints.preferredBool(0))

            session.popTo(0)

            assertNull(hints.preferredBool(0))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(2, true))))
            assertEquals(true, hints.preferredBool(0))
            component.releasePartialPointHints()
            assertNull(hints.preferredBool(0))
        }
    }

    @Test
    fun `cancelled selected hint preparation should retain its debit`() {
        var halted = false
        var charged = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = halted
            override fun workMeter(): WorkMeter = WorkMeter {
                charged += it
                if (it >= 64L) halted = true
            }
        }
        val model = Problem(
            numBoolVars = 65530,
            intBounds = openBounds(0),
            numRealVars = 3,
            realLower = DoubleArray(3),
            realUpper = DoubleArray(3),
            factors = arrayOf(ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1, 2),
                doubleArrayOf(1.0, 1.0, 1.0), LinearOp.LE, 0.0)),
        )
        ExactLiraSearchComponent(model).use { component ->
            val hints = component.partialPointHints(stop, defaultOpening = true)

            assertNull(hints)
            assertTrue(halted)
            assertTrue(charged > 0L)
            assertEquals(false, component.partialPointHintAvailable())
        }
    }

    @Test
    fun `oversized selected members should not supply a primitive preference`() {
        val wide = BIG_ONE shl 4096
        val declaration = object : Factor by ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(),
            intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 0.0) {
            override val linearForm: LinearForm = LinearForm.Conjunction(listOf(TaggedLinearRow(
                intArrayOf(Term.ofLit(Lit.make(2, true)), Term.ofRealVar(0)),
                WideConstants(WideConsts(arrayOf(wide, BIG_ONE)), BIG_ZERO), LinearOp.LE, activator = 0,
            )))
        }
        val model = Problem(
            numBoolVars = 65530,
            intBounds = openBounds(0),
            numRealVars = 3,
            realLower = DoubleArray(3),
            realUpper = DoubleArray(3),
            factors = arrayOf(
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0),
                    doubleArrayOf(1.0), LinearOp.EQ, 0.0),
                ReifiedRealLinear(1, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1, 2),
                    doubleArrayOf(1.0, 1.0, 1.0), LinearOp.LE, 0.0),
                declaration,
            ),
        )
        ExactLiraSearchComponent(model).use { component ->
            val hints = assertNotNull(component.partialPointHints(Cancellation.Never, defaultOpening = true)).second
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(model.numBoolVars))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(2, false))))

            assertNull(hints.preferredBool(0))
            assertEquals(true, hints.preferredBool(1))
            assertNull(session.boolValue(0))
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
