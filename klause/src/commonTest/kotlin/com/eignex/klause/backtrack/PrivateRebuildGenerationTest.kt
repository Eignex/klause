package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.OWN_ORIGIN
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.RootWorkMeter
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrivateRebuildGenerationTest {
    @Test
    fun `global root progress should produce reconstructable private eliminations`() {
        for (native in listOf(false, true)) {
            val source = Problem(5, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(0, 2)), Clause(intArrayOf(1, 4)),
                Clause(intArrayOf(0, 6)), Clause(intArrayOf(0, 6, 2)),
                Clause(intArrayOf(1, 6)), Clause(intArrayOf(1, 6, 4)),
                Clause(intArrayOf(6, 8)), Clause(intArrayOf(6, 9)),
            )).bake()
            val session = PropagationSession(source, nativeSat = native)
            session.addRootDerivedClause(Clause(intArrayOf(6)), 1, OWN_ORIGIN, false)

            val generation = assertNotNull(PrivateRebuildGeneration.prepare(session, 1_048_576L, Cancellation.Never))
            val reduced = assertIs<SolveResult.Sat>(BacktrackSolver(generation.problem).solve())
            val point = generation.reconstruct(reduced.assignment)

            assertNotSame(source, generation.problem)
            assertTrue(generation.eliminated > 0)
            assertTrue(generation.problem.numFactors < source.numFactors)
            assertTrue(point.boolValue(3))
            assertTrue(source.factors.all { factor ->
                (factor as Clause).literals.any { Lit.evaluate(it, point.boolValue(Lit.variable(it))) }
            })
            assertEquals(null, session.boolValue(0))
        }
    }

    @Test
    fun `completed eliminations should remain consumable when the local pass allowance expires`() {
        val source = Problem(7, 0, emptyArray(), listOf(
            Clause(intArrayOf(0, 2)), Clause(intArrayOf(1, 3)),
        ) + List(16) { Clause(intArrayOf(4, 6, 8)) } +
            List(16) { Clause(intArrayOf(5, 6, 10)) } +
            List(16) { Clause(intArrayOf(4, 7, 9)) } +
            List(16) { Clause(intArrayOf(5, 7, 11)) } +
            List(128) { Clause(intArrayOf(12, 4, 6)) }).bake()

        val generation = assertNotNull(PrivateRebuildGeneration.prepare(
            PropagationSession(source), 1_048_576L, Cancellation.Never, passWork = { 17_000L },
        ))
        val point = generation.reconstruct(Sample(BooleanArray(7) { it == 2 || it == 3 }, longArrayOf()))

        assertTrue(generation.passStopped)
        assertTrue(generation.eliminated > 0)
        assertTrue(source.factors.all { factor ->
            (factor as Clause).literals.any { Lit.evaluate(it, point.boolValue(Lit.variable(it))) }
        })
    }

    @Test
    fun `conditional roots should withhold a globally reusable generation`() {
        for (scoped in listOf(false, true)) {
            val source = Problem(2, 0, emptyArray(), arrayOf(Clause(intArrayOf(0, 2)))).bake()
            val session = PropagationSession(source)
            if (scoped) session.addLearnedClause(Clause(intArrayOf(1)), 1, permanent = true)
            else session.seed(Assumptions.None.withBool(0, false))

            val generation = PrivateRebuildGeneration.prepare(session, 1_048_576L, Cancellation.Never)

            assertNull(generation)
        }
    }

    @Test
    fun `insufficient memory or cancellation should retain the established source`() {
        for ((bytes, cancellation) in listOf(0L to Cancellation.Never, 1_048_576L to Cancellation { true })) {
            val source = Problem(2, 0, emptyArray(), arrayOf(Clause(intArrayOf(0, 2)))).bake()
            val session = PropagationSession(source)

            val generation = PrivateRebuildGeneration.prepare(session, bytes, cancellation)

            assertNull(generation)
            assertEquals(1, session.problem.numFactors)
            assertNull(session.boolValue(0))
        }
    }

    @Test
    fun `cancellation during private baking should withhold the generation`() {
        val source = Problem(2, 0, emptyArray(), arrayOf(Clause(intArrayOf(0, 2)))).bake()
        var stopped = false
        var constructions = 0
        val meter = object : RootWorkMeter {
            override fun charge(units: Long) = Unit
            override fun propagation(units: Long) = Unit
            override fun construction(entries: Long) {
                constructions++
                stopped = true
            }
        }
        val token = object : Cancellation {
            override fun isCancelled(): Boolean = stopped
            override fun workMeter(): RootWorkMeter = meter
        }

        val generation = PrivateRebuildGeneration.prepare(PropagationSession(source), 1_048_576L, token)

        assertNull(generation)
        assertEquals(1, constructions)
        assertEquals(1, source.numFactors)
    }

    @Test
    fun `matching dimensions should not grant another model reconstruction authority`() {
        val source = Problem(2, 0, emptyArray(), arrayOf(Clause(intArrayOf(0, 2)))).bake()
        val generation = assertNotNull(PrivateRebuildGeneration.prepare(
            PropagationSession(source), 1_048_576L, Cancellation.Never,
        ))
        val unrelated = Problem(2, 0, emptyArray(), emptyArray()).bake()

        assertFailsWith<IllegalArgumentException> {
            generation.mapping.reconstructFrom(unrelated, Sample(BooleanArray(2), longArrayOf()))
        }
    }
}
