package com.eignex.klause.lp.relaxation

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
            session.equalityLit(0, 1, positive = false)), clause.toSet())
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
            val explanation = assertNotNull(LpPinnedExplanation.capture(parent, listOf(intArrayOf(0)),
                session, Cancellation.Never) {})

            val clause = explanation.clause(intArrayOf(0), session, Cancellation.Never) {}

            assertNull(clause)
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
