package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpRowPremises
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpPinnedExplanationTest {
    @Test
    fun `partial source snapshots should reject interior domain changes`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyArray())
        val session = PropagationSession(problem)
        session.pinIntAtMost(0, 2)
        val source = LpBuilder().apply {
            val x = addVar(0L, 2L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 3L)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())
        val parent = LpRelaxation(model, intArrayOf(0), booleanArrayOf(false), 0L, intArrayOf(0), intArrayOf())
        val explanation = assertNotNull(LpPinnedExplanation.capture(parent, listOf(intArrayOf(0)), session,
            Cancellation.Never, requirePinned = false) {})
        assertNotNull(explanation.clause(intArrayOf(0), session, Cancellation.Never) {})

        session.addLearnedClause(Clause(intArrayOf(session.equalityLit(0, 1, positive = false))),
            lbd = 1, permanent = true)

        assertEquals(0L, session.intDomain(0).min)
        assertEquals(2L, session.intDomain(0).max)
        assertNull(explanation.clause(intArrayOf(0), session, Cancellation.Never) {})
    }

    @Test
    fun `partial source snapshots should reject changed finite domain bounds`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 2)), emptyArray())
        val session = PropagationSession(problem)
        session.pinIntAtMost(0, 1)
        val source = LpBuilder().apply {
            val x = addVar(0L, 1L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 2L)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())
        val parent = LpRelaxation(model, intArrayOf(0), booleanArrayOf(false), 0L, intArrayOf(0), intArrayOf())
        assertNull(LpPinnedExplanation.capture(parent, listOf(intArrayOf(0)), session, Cancellation.Never) {})

        val explanation = assertNotNull(LpPinnedExplanation.capture(parent, listOf(intArrayOf(0)), session,
            Cancellation.Never, requirePinned = false) {})

        val clause = assertNotNull(explanation.clause(intArrayOf(0), session, Cancellation.Never) {})
        assertTrue(clause.all { session.litTruth(it) == false })
        val sibling = PropagationSession(problem)
        assertNull(explanation.clause(intArrayOf(0), sibling, Cancellation.Never) {})
    }

    @Test
    fun `partial source snapshots should withhold a row with an unassigned Boolean premise`() {
        val problem = Problem(1, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val source = LpBuilder().apply {
            val r = addRealVar(null, null)
            addRealRow(intArrayOf(r), doubleArrayOf(1.0), Relation.LE, 0.0,
                premiseLits = intArrayOf(Lit.make(0, true)))
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())
        val parent = LpRelaxation(model, intArrayOf(-1), booleanArrayOf(false), 0L, intArrayOf(),
            intArrayOf(-1), colRealId = intArrayOf(0))
        val declines = ArrayList<String>()

        val explanation = LpPinnedExplanation.capture(parent, listOf(intArrayOf(0)), PropagationSession(problem),
            Cancellation.Never, onDecline = declines::add, requirePinned = false) {}

        assertNull(explanation)
        assertEquals(listOf("UNASSIGNED_ROW_PREMISE"), declines)
    }

    @Test
    fun `weaker coincident row guards should be absent from a compacted refutation`() {
        for (reversed in listOf(false, true)) {
            val problem = Problem(3, 0, emptyArray(), emptyArray(), numRealVars = 1,
                realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
                realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
            val session = PropagationSession(problem)
            for (variable in 0 until 3) session.pinBool(variable, true)
            val sign = if (reversed) -1.0 else 1.0
            val source = LpBuilder().apply {
                val r = addRealVar(null, null)
                addRealRow(intArrayOf(r), doubleArrayOf(sign), if (reversed) Relation.GE else Relation.LE,
                    0.0, premiseLits = intArrayOf(Lit.make(0, true)))
                addRealRow(intArrayOf(r), doubleArrayOf(1.0), Relation.LE, 10.0,
                    premiseLits = intArrayOf(Lit.make(1, true)))
                addRealRow(intArrayOf(r), doubleArrayOf(1.0), Relation.GE, 1.0,
                    premiseLits = intArrayOf(Lit.make(2, true)))
            }.build(Sense.MINIMIZE)
            val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())
            val parent = LpRelaxation(model, intArrayOf(-1), booleanArrayOf(false), 0L, intArrayOf(),
                IntArray(3) { -1 }, colRealId = intArrayOf(0))

            val compact = assertNotNull(parent.compactPinnedRows(session, Cancellation.Never) {})
            val clause = assertNotNull(assertNotNull(compact.pinnedExplanation)
                .clause(intArrayOf(0), session, Cancellation.Never) {})

            assertEquals(setOf(Lit.make(0, false), Lit.make(2, false)), clause.toSet())
            assertTrue(clause.all { session.litTruth(it) == false })
        }
    }

    @Test
    fun `equal endpoint ties should retain an original strict parent in either row order`() {
        for (strictFirst in listOf(false, true)) {
            for (reversed in listOf(false, true)) {
                val problem = Problem(3, 0, emptyArray(), emptyArray(), numRealVars = 1,
                    realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
                    realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
                val session = PropagationSession(problem)
                for (variable in 0 until 3) session.pinBool(variable, true)
                val source = LpBuilder().apply {
                    val r = addRealVar(null, null)
                    for (strict in listOf(strictFirst, !strictFirst)) {
                        addRealRow(intArrayOf(r), doubleArrayOf(if (reversed) -1.0 else 1.0),
                            if (reversed) Relation.GE else Relation.LE, 0.0, strict = strict,
                            premiseLits = intArrayOf(Lit.make(if (strict) 1 else 0, true)))
                    }
                    addRealRow(intArrayOf(r), doubleArrayOf(1.0), Relation.GE, 0.0,
                        premiseLits = intArrayOf(Lit.make(2, true)))
                }.build(Sense.MINIMIZE)
                val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())
                val parent = LpRelaxation(model, intArrayOf(-1), booleanArrayOf(false), 0L, intArrayOf(),
                    IntArray(3) { -1 }, colRealId = intArrayOf(0))

                val compact = assertNotNull(parent.compactPinnedRows(session, Cancellation.Never) {})
                val clause = assertNotNull(assertNotNull(compact.pinnedExplanation)
                    .clause(intArrayOf(0), session, Cancellation.Never) {})

                assertEquals(setOf(Lit.make(1, false), Lit.make(2, false)), clause.toSet())
                assertTrue(clause.all { session.litTruth(it) == false })
            }
        }
    }

    @Test
    fun `a tight substituted parent should retain its finite endpoints without loose row conditions`() {
        val problem = Problem(2, 1, arrayOf(IntDomain(0, 1)), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val session = PropagationSession(problem)
        session.pinBool(0, true)
        session.pinBool(1, true)
        session.pinInt(0, 1)
        val source = LpBuilder().apply {
            val x = addVar(1L, 1L)
            val r = addRealVar(null, null)
            addRealRow(intArrayOf(x, r), doubleArrayOf(1.0, 1.0), Relation.LE, 0.0,
                premiseLits = intArrayOf(Lit.make(0, true)))
            addRealRow(intArrayOf(r), doubleArrayOf(1.0), Relation.LE, 10.0,
                premiseLits = intArrayOf(Lit.make(1, true)))
            addRealRow(intArrayOf(r), doubleArrayOf(1.0), Relation.GE, 0.0)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())
        val parent = LpRelaxation(model, intArrayOf(0, -1), booleanArrayOf(false, false), 0L,
            intArrayOf(0), intArrayOf(-1, -1), colRealId = intArrayOf(-1, 0))

        val compact = assertNotNull(parent.compactPinnedRows(session, Cancellation.Never) {})
        val clause = assertNotNull(assertNotNull(compact.pinnedExplanation)
            .clause(intArrayOf(0), session, Cancellation.Never) {})

        assertEquals(setOf(Lit.make(0, false), session.boundGeLit(0, 1, positive = false),
            session.boundLeLit(0, 1, positive = false)), clause.toSet())
        assertTrue(clause.all { session.litTruth(it) == false })
    }

    @Test
    fun `an oversized referenced prefix should withhold its compacted explanation`() {
        val problem = Problem(4097, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val session = PropagationSession(problem)
        for (variable in 0 until problem.numBoolVars) session.pinBool(variable, true)
        val source = LpBuilder().apply {
            val r = addRealVar(null, null)
            addRealRow(intArrayOf(r), doubleArrayOf(1.0), Relation.LE, 0.0)
        }.build(Sense.MINIMIZE)
        source.rowGlobal[0] = false
        source.rowPremises[0] = LpRowPremises(intArrayOf(), booleanArrayOf(), longArrayOf(),
            IntArray(problem.numBoolVars) { Lit.make(it, true) })
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())
        val parent = LpRelaxation(model, intArrayOf(-1), booleanArrayOf(false), 0L, intArrayOf(),
            IntArray(problem.numBoolVars) { -1 }, colRealId = intArrayOf(0))
        val declines = ArrayList<String>()

        val explanation = LpPinnedExplanation.capture(parent, listOf(intArrayOf(0)), session, Cancellation.Never,
            onDecline = declines::add) {}

        assertNull(explanation)
        assertEquals(listOf("PREFIX_LIMIT_B_4096_I_0"), declines)
    }

    @Test
    fun `a compacted source explanation should reject changed finite pins`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
        val source = LpBuilder().apply {
            val x = addVar(1L, 1L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.LE, 0L)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())
        val parent = LpRelaxation(model, intArrayOf(0), booleanArrayOf(false), 0L, intArrayOf(0), intArrayOf())
        val first = PropagationSession(problem)
        first.pinInt(0, 1)
        val explanation = assertNotNull(LpPinnedExplanation.capture(parent, listOf(intArrayOf(0)),
            first, Cancellation.Never) {})
        val sibling = PropagationSession(problem)
        sibling.pinInt(0, 0)

        val clause = explanation.clause(intArrayOf(0), sibling, Cancellation.Never) {}

        assertNull(clause)
        assertNotNull(explanation.clause(intArrayOf(0), first, Cancellation.Never) {})
    }

    @Test
    fun `compacted source reasons should retain fixed values and original row guards`() {
        val problem = Problem(1, 1, arrayOf(IntDomain(0, 1)), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val session = PropagationSession(problem)
        session.pinBool(0, true)
        session.pinInt(0, 1)
        val source = LpBuilder().apply {
            val x = addVar(1L, 1L)
            val r = addRealVar(null, null)
            addRealRow(intArrayOf(x, r), doubleArrayOf(1.0, 1.0), Relation.LE, 1.0)
            addRealRow(intArrayOf(r), doubleArrayOf(1.0), Relation.GE, 1.0)
        }.build(Sense.MINIMIZE)
        source.rowGlobal[0] = false
        source.rowPremises[0] = LpRowPremises(intArrayOf(), booleanArrayOf(), longArrayOf(),
            intArrayOf(Lit.make(0, true)))
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())
        val parent = LpRelaxation(model, intArrayOf(0, -1), booleanArrayOf(false, false), 0L,
            intArrayOf(0), intArrayOf(-1), colRealId = intArrayOf(-1, 0))
        val explanation = assertNotNull(LpPinnedExplanation.capture(parent, listOf(intArrayOf(0, 1)),
            session, Cancellation.Never) {})

        val clause = assertNotNull(explanation.clause(intArrayOf(0), session, Cancellation.Never) {})

        assertEquals(setOf(Lit.make(0, false), session.boundGeLit(0, 1, positive = false),
            session.boundLeLit(0, 1, positive = false)), clause.toSet())
        assertTrue(clause.all { session.litTruth(it) == false })
    }

    @Test
    fun `unbacked source premises should withhold a compacted conflict clause`() {
        for (unbacked in listOf("row", "column")) {
            val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val session = PropagationSession(problem)
            session.pinInt(0, 1)
            val source = LpBuilder().apply {
                val x = addVar(1L, 1L)
                addRow(intArrayOf(x), longArrayOf(1L), Relation.LE, 0L)
            }.build(Sense.MINIMIZE)
            if (unbacked == "row") source.rowGlobal[0] = false
            val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())
            val parent = LpRelaxation(model, intArrayOf(if (unbacked == "column") -1 else 0),
                booleanArrayOf(false), 0L, intArrayOf(0), intArrayOf())
            val declines = ArrayList<String>()
            val explanation = assertNotNull(LpPinnedExplanation.capture(parent, listOf(intArrayOf(0)),
                session, Cancellation.Never, onDecline = declines::add) {})

            val clause = explanation.clause(intArrayOf(0), session, Cancellation.Never) {}

            assertNull(clause)
            assertEquals(listOf(if (unbacked == "row") "ROW_ORIGIN" else "STRUCTURAL_BOUND"), declines)
        }
    }

    @Test
    fun `cancelled source mapping should retain work and withhold its clause`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
        val session = PropagationSession(problem)
        session.pinInt(0, 1)
        val source = LpBuilder().apply {
            val x = addVar(1L, 1L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.LE, 0L)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())
        val parent = LpRelaxation(model, intArrayOf(0), booleanArrayOf(false), 0L, intArrayOf(0), intArrayOf())
        val explanation = assertNotNull(LpPinnedExplanation.capture(parent, listOf(intArrayOf(0)),
                session, Cancellation.Never) {})
        var spent = 0L

        val clause = explanation.clause(intArrayOf(0), session, Cancellation { spent > 0L }) { spent += it }

        assertNull(clause)
        assertTrue(spent > 0L)
        assertNotNull(explanation.clause(intArrayOf(0), session, Cancellation.Never) {})
    }
}
