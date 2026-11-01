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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrivateRebuildGenerationTest {
    @Test
    fun `learned equivalences should open a reconstructable private consumer without root pins`() {
        for (replacement in listOf(2, 3)) for (native in listOf(false, true)) {
            val source = Problem(4, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(1, replacement, 4)), Clause(intArrayOf(1, replacement, 5)),
                Clause(intArrayOf(0, Lit.negate(replacement), 6)), Clause(intArrayOf(0, Lit.negate(replacement), 7)),
            )).bake()
            val session = PropagationSession(source, nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(1, replacement)), 2)
            session.addLearnedClause(Clause(intArrayOf(0, Lit.negate(replacement))), 2)
            val captured = assertNotNull(PrivateRebuildBinaryLearning.capture(session, longArrayOf(), 0) { true })

            val generation = assertNotNull(PrivateRebuildGeneration.prepare(session, 1_048_576L,
                Cancellation.Never, learnedBinary = captured))
            val reduced = assertIs<SolveResult.Sat>(BacktrackSolver(generation.problem).solve(
                BacktrackParams(nativeSat = native),
            ))
            val point = generation.reconstruct(reduced.assignment)

            assertEquals(1, generation.equivalenceMerges)
            assertNotSame(source, generation.problem)
            assertTrue(source.factors.all { factor ->
                (factor as Clause).literals.any { Lit.evaluate(it, point.boolValue(Lit.variable(it))) }
            })
            assertNull(session.boolValue(0))
            assertEquals(4, source.numFactors)
        }
    }

    @Test
    fun `matching dimensions should not grant another source learned equivalence authority`() {
        val source = Problem(3, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(1, 2)), Clause(intArrayOf(0, 3, 4)), Clause(intArrayOf(0, 3, 5)),
        )).bake()
        val session = PropagationSession(source)
        session.addLearnedClause(Clause(intArrayOf(0, 3)), 2)
        val captured = assertNotNull(PrivateRebuildBinaryLearning.capture(session, longArrayOf(), 0) { true })
        val unrelated = Problem(3, 0, emptyArray(), source.factors.copyOf()).bake()

        val generation = PrivateRebuildGeneration.prepare(PropagationSession(unrelated), 1_048_576L,
            Cancellation.Never, learnedBinary = captured)

        assertNull(generation)
    }

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
    fun `unchanged clauses should remain shared and unmodified in a private consumer`() {
        for (native in listOf(false, true)) {
            val source = Problem(6, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(3, 5, 6)), Clause(intArrayOf(0, 2)),
                Clause(intArrayOf(3, 5, 7)), Clause(intArrayOf(1, 2)),
                Clause(intArrayOf(2, 4, 6)), Clause(intArrayOf(2, 4, 7)),
                Clause(intArrayOf(8, 10)), Clause(intArrayOf(8, 11)),
            )).bake()
            val unchanged = source.factors.take(6).map { it as Clause }
                .filter { clause -> clause.literals.none { Lit.variable(it) == 0 } }
            val literals = unchanged.map { it.literals.copyOf() }
            val session = PropagationSession(source, nativeSat = native)
            session.addRootDerivedClause(Clause(intArrayOf(8)), 1, OWN_ORIGIN, false)
            val generation = assertNotNull(PrivateRebuildGeneration.prepare(session, 1_048_576L, Cancellation.Never))

            val reduced = assertIs<SolveResult.Sat>(BacktrackSolver(generation.problem).solve(
                BacktrackParams(nativeSat = native),
            ))
            val point = generation.reconstruct(reduced.assignment)

            for (index in unchanged.indices) {
                assertTrue(generation.problem.factors.any { it === unchanged[index] })
                assertContentEquals(literals[index], unchanged[index].literals)
            }
            assertTrue(source.factors.all { factor ->
                (factor as Clause).literals.any { Lit.evaluate(it, point.boolValue(Lit.variable(it))) }
            })
        }
    }

    @Test
    fun `translated learning should enter a reconstructed private consumer`() {
        for (native in listOf(false, true)) {
            val source = Problem(6, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(3, 5, 6)), Clause(intArrayOf(0, 2)),
                Clause(intArrayOf(3, 5, 7)), Clause(intArrayOf(1, 2)),
                Clause(intArrayOf(2, 4, 6)), Clause(intArrayOf(2, 4, 7)),
                Clause(intArrayOf(8, 10)), Clause(intArrayOf(8, 11)),
            )).bake()
            val session = PropagationSession(source, nativeSat = native)
            session.addRootDerivedClause(Clause(intArrayOf(8)), 1, OWN_ORIGIN, false)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)

            val generation = assertNotNull(PrivateRebuildGeneration.prepare(session, 1_048_576L, Cancellation.Never))
            val reduced = assertIs<SolveResult.Sat>(BacktrackSolver(generation.problem).solve(
                BacktrackParams(nativeSat = native),
            ))
            val point = generation.reconstruct(reduced.assignment)

            assertEquals(1, generation.warmClauses.translated)
            assertTrue(source.factors.all { factor ->
                (factor as Clause).literals.any { Lit.evaluate(it, point.boolValue(Lit.variable(it))) }
            })
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
        for (duringPropagation in listOf(false, true)) {
            val source = Problem(4, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(0, 2)), Clause(intArrayOf(1, 2)),
                Clause(intArrayOf(2, 4, 6)), Clause(intArrayOf(2, 4, 7)),
                Clause(intArrayOf(3, 5, 6)), Clause(intArrayOf(3, 5, 7)),
            )).bake()
            var stopped = false
            var constructions = 0
            val meter = object : RootWorkMeter {
                override fun charge(units: Long) = Unit
                override fun propagation(units: Long) {
                    if (duringPropagation && units > 0L) stopped = true
                }
                override fun construction(entries: Long) {
                    constructions++
                    if (!duringPropagation) stopped = true
                }
            }
            val token = object : Cancellation {
                override fun isCancelled(): Boolean = stopped
                override fun workMeter(): RootWorkMeter = meter
            }

            val generation = PrivateRebuildGeneration.prepare(PropagationSession(source), 1_048_576L, token)

            assertNull(generation)
            assertTrue(stopped)
            assertEquals(1, constructions)
            assertEquals(6, source.numFactors)
        }
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
