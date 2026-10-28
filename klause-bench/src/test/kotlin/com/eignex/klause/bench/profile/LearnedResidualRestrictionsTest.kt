package com.eignex.klause.bench.profile

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class LearnedResidualRestrictionsTest {
    @Test
    fun `a live objective PB should join two source cycles`() {
        val cycles = List(2) { c -> (0 until 5).map { c * 5 + it } }
        val factors = cycles.flatMap { vs -> vs.indices.map { i ->
            Clause(intArrayOf(Lit.make(vs[i], true), Lit.make(vs[(i + 1) % 5], true)))
        } }
        val session = PropagationSession(Problem(10, 0, emptyArray(), factors.toTypedArray()), nativeSat = false)
        session.addLearnedPb(LongArray(10) { 1L }, IntArray(10) { Lit.make(it, false) }, 5L, 1, permanent = true)
        val recorder = ConditionalComponentTrace.Recorder(session.problem, Long.MAX_VALUE, 0)

        val observed = LearnedResidualRestrictions.inspect(session)
        repeat(8) { recorder.observe(session) }

        assertTrue(observed.complete)
        assertEquals(1, observed.componentCount(cycles))
        assertEquals((0 until 10).toList(), observed.entries.single().support)
        assertTrue(observed.entries.single().permanentRetention)
        val incidence = recorder.summary().getValue("lastEnforcingIncidence").jsonObject
        assertEquals(2, incidence.getValue("sourceOnlyComponents").jsonPrimitive.int)
        assertEquals(1, incidence.getValue("allEnforcingComponents").jsonPrimitive.int)
    }

    @Test
    fun `established PB weight should discharge its free bridge`() {
        val session = PropagationSession(Problem(4, 0, emptyArray(), emptyArray()), nativeSat = false)
        session.addLearnedPb(longArrayOf(5L, 2L, 3L), IntArray(3) { Lit.make(it, true) }, 5L, 2)
        session.pinBool(0, true)

        val observed = LearnedResidualRestrictions.inspect(session)

        assertTrue(observed.entries.single().discharged)
        assertEquals(2, observed.componentCount(listOf(listOf(1), listOf(2))))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `retention should not suppress source valid permanent or ordinary clause incidence`(native: Boolean) {
            val source = Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
            val session = PropagationSession(Problem(4, 0, emptyArray(), arrayOf(source)), nativeSat = native)
            session.addLearnedClause(source, 2, permanent = true)
            session.addLearnedClause(Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true))), 2)

            val observed = LearnedResidualRestrictions.inspect(session)

            assertTrue(observed.complete)
            assertEquals(listOf(true, false), observed.entries.map { it.permanentRetention })
            assertEquals(2, observed.componentCount((0 until 4).map { listOf(it) }))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `undo and compaction should read the current store rather than cached entry identities`(native: Boolean) {
            val session = PropagationSession(Problem(4, 0, emptyArray(), emptyArray()), nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))), 2)
            session.addLearnedClause(Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true))), 2)
            session.pinBool(2, true)
            assertTrue(LearnedResidualRestrictions.inspect(session).entries.last().discharged)
            session.popToLevel(0)
            session.forgetLearnedClauses { index, _ -> index != 0 }

            val observed = LearnedResidualRestrictions.inspect(session)

            assertEquals(1, observed.storeCount)
            assertEquals(0, observed.entries.single().storeIndex)
            assertEquals(listOf(2, 3), observed.entries.single().support)
            assertFalse(observed.entries.single().discharged)
    }

    @Test
    fun `PB support should include terms outside its covering watch set`() {
        val session = PropagationSession(Problem(4, 0, emptyArray(), emptyArray()), nativeSat = false)
        session.addLearnedPb(longArrayOf(10, 1, 1, 1), IntArray(4) { Lit.make(it, true) }, 1, 2)

        val observed = LearnedResidualRestrictions.inspect(session)

        assertEquals(listOf(0, 1, 2, 3), observed.entries.single().support)
    }

    @Test
    fun `an unnameable atom should withhold complete enforcing incidence`() {
        val session = PropagationSession(Problem(1, 1, arrayOf(IntDomain(0, 2)), emptyArray()))
        val atom = session.boundGeLit(0, 1, positive = true)
        session.addLearnedClause(Clause(intArrayOf(Lit.make(0, true), atom)), 2)

        val observed = LearnedResidualRestrictions.inspect(session)

        assertFalse(observed.complete)
        assertNull(observed.componentCount(listOf(listOf(0))))
    }

    @Test
    fun `interrupted and capped observations should decline completeness`() {
        val session = PropagationSession(Problem(3, 0, emptyArray(), emptyArray()))
        session.addLearnedClause(Clause(IntArray(3) { Lit.make(it, true) }), 3)

        assertEquals("interrupted", LearnedResidualRestrictions.inspect(session, Cancellation { true }).decline)
        val capped = LearnedResidualRestrictions.inspect(session, maxTerms = 2)
        assertEquals("term-limit", capped.decline)
        assertFalse(capped.complete)
    }

    @Test
    fun `an unsupported stored kind should withhold completeness`() {
        val observed = LearnedResidualInspection(
            listOf(LearnedResidualRestriction(0, "unknown", false, null, null, false)), 1, null,
        )

        assertFalse(observed.complete)
        assertNull(observed.componentCount(listOf(listOf(0), listOf(1))))
    }
}
