package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.Xor
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrivateRebuildParityTest {
    @Test
    fun `complete signed parity rows should produce source entailed pins and reconstruction`() {
        for (phase in 0..1) for (native in listOf(false, true)) {
            val clauses = (0..3).flatMap { excluded ->
                val columns = (0..3).filter { it != excluded }
                val rhs = if (excluded == 0) phase else 0
                (0..7).filter { it.countOneBits() % 2 != rhs }.map { mask ->
                    Clause(IntArray(3) { Lit.make(columns[2 - it], mask and (1 shl (2 - it)) == 0) })
                }
            }.toTypedArray<Factor>()
            val source = Problem(4, 0, emptyArray(), clauses).bake()
            val session = PropagationSession(source, nativeSat = native)

            val captured = assertNotNull(PrivateRebuildParity.capture(session, 1_048_576L, { true }))
            val generation = assertNotNull(PrivateRebuildGeneration.prepare(session, 1_048_576L,
                Cancellation.Never, parity = captured))
            val point = generation.reconstruct(Sample(BooleanArray(4), longArrayOf()))

            assertEquals(4, captured.newPins)
            assertEquals(4, generation.parityPins)
            for (variable in 0..3) {
                assertEquals(phase == 1 && variable != 0, captured.fixed[variable])
                assertEquals(captured.fixed[variable], point.boolValue(variable))
                assertNull(session.boolValue(variable))
            }
            assertTrue(source.factors.all { factor ->
                (factor as Clause).literals.any { Lit.evaluate(it, point.boolValue(Lit.variable(it))) }
            })
        }
    }

    @Test
    fun `an incomplete or duplicated cube should preserve all of its source points`() {
        for (duplicate in listOf(false, true)) {
            val clauses = (0..3).flatMap { excluded ->
                val columns = (0..3).filter { it != excluded }
                val masks = if (excluded == 0) listOf(3, 5, 6) + if (duplicate) listOf(3) else emptyList()
                else listOf(1, 2, 4, 7)
                masks.map { mask -> Clause(IntArray(3) { Lit.make(columns[it], mask and (1 shl it) == 0) }) }
            }.toTypedArray<Factor>()
            val source = Problem(4, 0, emptyArray(), clauses).bake()
            val session = PropagationSession(source)

            val captured = assertNotNull(PrivateRebuildParity.capture(session, 1_048_576L, { true }))

            assertEquals(1, captured.newPins)
            assertEquals(false, captured.fixed[0])
            for (variable in 1..3) assertNull(captured.fixed[variable])
            var points = 0
            for (mask in 0..15) {
                val point = BooleanArray(4) { mask and (1 shl it) != 0 }
                if (clauses.all { factor ->
                        (factor as Clause).literals.any { Lit.evaluate(it, point[Lit.variable(it)]) }
                    }) {
                    points++
                    assertTrue(captured.fixed.indices.all {
                        captured.fixed[it] == null || captured.fixed[it] == point[it]
                    })
                }
            }
            assertEquals(2, points)
        }
    }

    @Test
    fun `multiple free coordinates should cancel only in globally forced pins`() {
        val rows = listOf(intArrayOf(0, 2, 3, 0), intArrayOf(1, 2, 4, 1), intArrayOf(0, 1, 5, 1),
            intArrayOf(3, 4, 6, 0), intArrayOf(5, 6, 7, 1))
        val clauses = rows.flatMap { row ->
            (0..7).filter { it.countOneBits() % 2 != row[3] }.map { mask ->
                Clause(IntArray(3) { Lit.make(row[it], mask and (1 shl it) == 0) })
            }
        }.toTypedArray<Factor>()
        val session = PropagationSession(Problem(8, 0, emptyArray(), clauses).bake())

        val captured = assertNotNull(PrivateRebuildParity.capture(session, 1_048_576L, { true }))

        assertEquals(1, captured.newPins)
        assertEquals(true, captured.fixed[7])
        for (variable in 0..6) assertNull(captured.fixed[variable])
        var points = 0
        for (mask in 0..255) {
            val point = BooleanArray(8) { mask and (1 shl it) != 0 }
            if (clauses.all { factor ->
                    (factor as Clause).literals.any { Lit.evaluate(it, point[Lit.variable(it)]) }
                }) {
                points++
                assertTrue(point[7])
            }
        }
        assertEquals(8, points)
        for (variable in 0..7) assertNull(session.boolValue(variable))
    }

    @Test
    fun `free coordinates above one dependency word should prevent false pins`() {
        val rows = listOf(intArrayOf(0, 70, 71), intArrayOf(1, 70, 71)) + (2..5).map { excluded ->
            (2..5).filter { it != excluded }.toIntArray()
        }
        val clauses = rows.flatMap { row ->
            listOf(1, 2, 4, 7).map { mask ->
                Clause(IntArray(3) { Lit.make(row[it], mask and (1 shl it) == 0) })
            }
        }.toTypedArray<Factor>()
        val session = PropagationSession(Problem(72, 0, emptyArray(), clauses).bake())

        val captured = assertNotNull(PrivateRebuildParity.capture(session, 1_048_576L, { true }))

        assertEquals(4, captured.newPins)
        for (variable in 2..5) assertEquals(false, captured.fixed[variable])
        for (variable in listOf(0, 1, 70, 71)) assertNull(captured.fixed[variable])
        for (variable in 0..71) assertNull(session.boolValue(variable))
    }

    @Test
    fun `binary and ternary parity rows should combine across both signs`() {
        for (phase in 0..1) {
            val clauses = (0..3).filter { it.countOneBits() % 2 != phase }.map { mask ->
                Clause(IntArray(2) { Lit.make(1 - it, mask and (1 shl (1 - it)) == 0) })
            } + listOf(1, 2, 4, 7).map { mask ->
                Clause(IntArray(3) { Lit.make(it, mask and (1 shl it) == 0) })
            }
            val session = PropagationSession(Problem(3, 0, emptyArray(), clauses.toTypedArray<Factor>()).bake())

            val captured = assertNotNull(PrivateRebuildParity.capture(session, 1_048_576L, { true }))
            val generation = assertNotNull(PrivateRebuildGeneration.prepare(session, 1_048_576L,
                Cancellation.Never, parity = captured))
            val point = generation.reconstruct(Sample(booleanArrayOf(false, phase != 0, false), longArrayOf()))

            assertEquals(1, captured.newPins)
            assertEquals(phase != 0, captured.fixed[2])
            assertNull(captured.fixed[0])
            assertNull(captured.fixed[1])
            assertTrue(clauses.all { clause ->
                clause.literals.any { Lit.evaluate(it, point.boolValue(Lit.variable(it))) }
            })
            for (variable in 0..2) assertNull(session.boolValue(variable))
        }
    }

    @Test
    fun `an incomplete or duplicated binary encoding should grant no parity row`() {
        for (duplicate in listOf(false, true)) {
            val clauses = listOf(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))) +
                if (duplicate) listOf(Clause(intArrayOf(Lit.make(1, true), Lit.make(0, true)))) else emptyList()
            val session = PropagationSession(Problem(3, 0, emptyArray(), clauses.toTypedArray<Factor>()).bake())

            val captured = PrivateRebuildParity.capture(session, 1_048_576L, { true })

            assertNull(captured)
            for (variable in 0..2) assertNull(session.boolValue(variable))
        }
    }

    @Test
    fun `opposite complete binary parities should defer source refutation`() {
        val clauses = (0..3).map { mask ->
            Clause(IntArray(2) { Lit.make(it, mask and (1 shl it) == 0) })
        }.toTypedArray<Factor>()
        val session = PropagationSession(Problem(3, 0, emptyArray(), clauses).bake())
        val declines = mutableListOf<String>()

        val captured = PrivateRebuildParity.capture(session, 1_048_576L, { true }, onDecline = declines::add)

        assertNull(captured)
        assertEquals(listOf("RefutationDeferred"), declines)
        for (variable in 0..2) assertNull(session.boolValue(variable))
    }

    @Test
    fun `a complete affine source should preserve every free assignment in a fresh namespace`() {
        for (phase in 0..1) {
            val clauses = (0..7).filter { it.countOneBits() % 2 != phase }.map { mask ->
                Clause(IntArray(3) { Lit.make(it, mask and (1 shl it) == 0) })
            }.toTypedArray<Factor>()
            val source = Problem(3, 0, emptyArray(), clauses).bake()
            val session = PropagationSession(source)

            val captured = assertNotNull(PrivateRebuildParity.capture(session, 1_048_576L, { true }))
            val generation = assertNotNull(PrivateRebuildGeneration.prepare(session, 1_048_576L,
                Cancellation.Never, parity = captured))
            val lifted = mutableSetOf<List<Boolean>>()
            for (mask in 0..7) {
                val point = BooleanArray(3) { mask and (1 shl it) != 0 }
                if (generation.problem.factors.all { factor ->
                        (factor as Clause).literals.any { Lit.evaluate(it, point[Lit.variable(it)]) }
                    }) {
                    val result = generation.reconstruct(Sample(point, longArrayOf()))
                    lifted.add(List(3) { result.boolValue(it) })
                }
            }
            val expected = (0..7).filter { it.countOneBits() % 2 == phase }.map { mask ->
                List(3) { mask and (1 shl it) != 0 }
            }.toSet()

            assertEquals(expected, lifted)
            assertEquals(0, captured.newPins)
            assertEquals(1, generation.affineColumns)
            assertEquals(2, generation.affineFreeColumns)
            assertTrue(generation.problem !== source)
            for (variable in 0..2) assertNull(session.boolValue(variable))
        }
    }

    @Test
    fun `an uncovered clause should prevent elimination of the source parity system`() {
        val clauses = (0..3).flatMap { excluded ->
            val columns = (0..3).filter { it != excluded }
            listOf(1, 2, 4, 7).map { mask ->
                Clause(IntArray(3) { Lit.make(columns[it], mask and (1 shl it) == 0) })
            }
        } + Clause(IntArray(4) { Lit.make(it, true) })
        val session = PropagationSession(Problem(4, 0, emptyArray(), clauses.toTypedArray<Factor>()).bake())

        val captured = assertNotNull(PrivateRebuildParity.capture(session, 1_048_576L, { true }))
        val generation = PrivateRebuildGeneration.prepare(session, 1_048_576L, Cancellation.Never, parity = captured)

        assertNull(captured.affine)
        assertNull(generation)
        for (variable in 0..3) assertNull(session.boolValue(variable))
    }

    @Test
    fun `canonical clauses should be withheld from changed affine coordinates`() {
        val clauses = listOf(0, 3, 5, 6).map { mask ->
            Clause(IntArray(3) { Lit.make(it, mask and (1 shl it) == 0) })
        }.toTypedArray<Factor>()
        val session = PropagationSession(Problem(3, 0, emptyArray(), clauses).bake())
        session.addLearnedClause(Clause((clauses[0] as Clause).literals.copyOf()), 3)

        val captured = assertNotNull(PrivateRebuildParity.capture(session, 1_048_576L, { true }))
        val generation = assertNotNull(PrivateRebuildGeneration.prepare(session, 1_048_576L,
            Cancellation.Never, parity = captured))

        assertTrue(generation.warmClauses.clauses.isEmpty())
        val lifted = generation.reconstruct(Sample(BooleanArray(3), longArrayOf()))
        assertTrue(clauses.all { factor ->
            (factor as Clause).literals.any { Lit.evaluate(it, lifted.boolValue(Lit.variable(it))) }
        })
    }

    @Test
    fun `mixed parity rows should preserve all source points and original coordinates`() {
        for (phase in 0..1) {
            val rows = listOf(intArrayOf(0, 1, 2, phase), intArrayOf(1, 2, 3, phase xor 1))
            val clauses = rows.flatMap { row ->
                (0..7).filter { it.countOneBits() % 2 != row[3] }.map { mask ->
                    Clause(IntArray(3) { Lit.make(row[it], mask and (1 shl it) == 0) })
                }
            } + Clause(intArrayOf(Lit.make(0, false), Lit.make(3, true)))
            val session = PropagationSession(Problem(4, 0, emptyArray(), clauses.toTypedArray<Factor>()).bake())

            val captured = assertNotNull(PrivateRebuildParity.capture(session, 1_048_576L, { true }))
            val generation = assertNotNull(PrivateRebuildGeneration.prepare(session, 1_048_576L,
                Cancellation.Never, parity = captured))
            val sourcePoints = mutableSetOf<List<Boolean>>()
            val privatePoints = mutableSetOf<List<Boolean>>()
            for (mask in 0..15) {
                val point = BooleanArray(4) { mask and (1 shl it) != 0 }
                if (clauses.all { clause -> clause.literals.any { Lit.evaluate(it, point[Lit.variable(it)]) } }) {
                    sourcePoints.add(point.toList())
                }
                if (generation.problem.factors.all { factor ->
                        when (factor) {
                            is Clause -> factor.literals.any { Lit.evaluate(it, point[Lit.variable(it)]) }
                            is Xor -> factor.literals.count { Lit.evaluate(it, point[Lit.variable(it)]) } % 2 ==
                                factor.targetParity
                            else -> false
                        }
                    }) {
                    val lifted = generation.reconstruct(Sample(point, longArrayOf()))
                    privatePoints.add(List(4) { lifted.boolValue(it) })
                }
            }

            assertEquals(sourcePoints, privatePoints)
            assertEquals(2, sourcePoints.size)
            assertEquals(2, generation.parityReducedRows)
            assertEquals(8, generation.parityCoveredClauses)
            assertEquals(0, generation.affineColumns)
            for (variable in 0..3) assertNull(session.boolValue(variable))
        }
    }

    @Test
    fun `extra exclusions within a complete parity group should remain source clauses`() {
        val encoded = listOf(1, 2, 4, 7).map { mask ->
            Clause(IntArray(3) { Lit.make(it, mask and (1 shl it) == 0) })
        }
        val extra = Clause(IntArray(3) { Lit.make(it, true) })
        val clauses = encoded + extra
        val session = PropagationSession(Problem(3, 0, emptyArray(), clauses.toTypedArray<Factor>()).bake())

        val captured = assertNotNull(PrivateRebuildParity.capture(session, 1_048_576L, { true }))
        val generation = assertNotNull(PrivateRebuildGeneration.prepare(session, 1_048_576L,
            Cancellation.Never, parity = captured))
        val points = mutableSetOf<List<Boolean>>()
        for (mask in 0..7) {
            val point = BooleanArray(3) { mask and (1 shl it) != 0 }
            if (generation.problem.factors.all { factor ->
                    when (factor) {
                        is Clause -> factor.literals.any { Lit.evaluate(it, point[Lit.variable(it)]) }
                        is Xor -> factor.literals.count { Lit.evaluate(it, point[Lit.variable(it)]) } % 2 ==
                            factor.targetParity
                        else -> false
                    }
                }) {
                val lifted = generation.reconstruct(Sample(point, longArrayOf()))
                assertTrue(clauses.all { clause ->
                    clause.literals.any { Lit.evaluate(it, lifted.boolValue(Lit.variable(it))) }
                })
                points.add(List(3) { lifted.boolValue(it) })
            }
        }

        assertEquals(3, points.size)
        assertTrue(generation.problem.factors.any { it === extra })
        assertEquals(4, generation.parityCoveredClauses)
    }

    @Test
    fun `mixed row coordinates should retain supported canonical warm clauses`() {
        val clauses = listOf(0, 3, 5, 6).map { mask ->
            Clause(IntArray(3) { Lit.make(it, mask and (1 shl it) == 0) })
        } + Clause(intArrayOf(Lit.make(0, true), Lit.make(3, true)))
        val session = PropagationSession(Problem(4, 0, emptyArray(), clauses.toTypedArray<Factor>()).bake())
        session.addLearnedClause(Clause(clauses[0].literals.copyOf()), 3)

        val captured = assertNotNull(PrivateRebuildParity.capture(session, 1_048_576L, { true }))
        val generation = assertNotNull(PrivateRebuildGeneration.prepare(session, 1_048_576L,
            Cancellation.Never, parity = captured))

        assertEquals(1, generation.parityReducedRows)
        assertEquals(1, generation.warmClauses.clauses.size)
        assertTrue(generation.warmClauses.clauses.all { factor ->
            (factor as Clause).literals.contentEquals(clauses[0].literals)
        })
    }

    @Test
    fun `cancelled mixed row construction should withhold its private generation`() {
        for (allowance in listOf(64L, 128L)) {
            val clauses = listOf(0, 3, 5, 6).map { mask ->
                Clause(IntArray(3) { Lit.make(it, mask and (1 shl it) == 0) })
            } + Clause(IntArray(3) { Lit.make(it, false) })
            val session = PropagationSession(Problem(3, 0, emptyArray(), clauses.toTypedArray<Factor>()).bake())
            val captured = assertNotNull(PrivateRebuildParity.capture(session, 1_048_576L, { true }))
            var work = 0L
            val token = object : Cancellation {
                override fun isCancelled(): Boolean = work >= allowance
                override fun workMeter(): WorkMeter = WorkMeter { work += it }
            }

            val generation = PrivateRebuildGeneration.prepare(session, 1_048_576L, token, parity = captured)

            assertNull(generation)
            for (variable in 0..2) assertNull(session.boolValue(variable))
        }
    }

    @Test
    fun `exhausted parity work should publish no partial pins`() {
        val clauses = (0..3).flatMap { excluded ->
            val columns = (0..3).filter { it != excluded }
            val rhs = if (excluded == 0) 1 else 0
            (0..7).filter { it.countOneBits() % 2 != rhs }.map { mask ->
                Clause(IntArray(3) { Lit.make(columns[it], mask and (1 shl it) == 0) })
            }
        }.toTypedArray<Factor>()
        val source = Problem(4, 0, emptyArray(), clauses).bake()
        var completeWork = 0L
        assertNotNull(PrivateRebuildParity.capture(PropagationSession(source), 1_048_576L, { work ->
            completeWork += work
            true
        }))
        for (allowance in listOf(0L, 1L, 32L, 128L, 512L, completeWork / 2, completeWork - 8, completeWork - 1)) {
            val session = PropagationSession(source)
            var spent = 0L

            val captured = PrivateRebuildParity.capture(session, 1_048_576L, { work ->
                if (work > allowance - spent) false else { spent += work; true }
            })

            assertNull(captured)
            assertTrue(spent <= allowance)
            for (variable in 0..3) assertNull(session.boolValue(variable))
        }
    }

    @Test
    fun `sparse parity discovery should return reconstructable bases within its work grant`() {
        val rows = 1_024
        val clauses = (0 until rows).flatMap { row ->
            (0..7).filter { it.countOneBits() % 2 != row % 2 }.map { mask ->
                Clause(IntArray(3) {
                    val column = if (it == 2) rows + 1 else row + it
                    Lit.make(column, mask and (1 shl it) == 0)
                })
            }
        }.toTypedArray<Factor>()
        val session = PropagationSession(Problem(rows + 2, 0, emptyArray(), clauses).bake())
        var spent = 0L

        val captured = assertNotNull(PrivateRebuildParity.capture(session, 16_777_216L, { work ->
            if (work > 1_048_576L - spent) false else { spent += work; true }
        }))
        val basis = assertNotNull(captured.basis)
        val point = basis.reconstruct(Sample(BooleanArray(rows + 2) { it % 2 == 0 }, longArrayOf()))

        assertEquals(rows, basis.rank)
        assertTrue(clauses.all { factor ->
            (factor as Clause).literals.any { Lit.evaluate(it, point.boolValue(Lit.variable(it))) }
        })
    }

    @Test
    fun `insufficient modeled parity memory should withhold the derived view`() {
        val clauses = (0..3).flatMap { excluded ->
            val columns = (0..3).filter { it != excluded }
            val rhs = if (excluded == 0) 1 else 0
            (0..7).filter { it.countOneBits() % 2 != rhs }.map { mask ->
                Clause(IntArray(3) { Lit.make(columns[it], mask and (1 shl it) == 0) })
            }
        }.toTypedArray<Factor>()
        val session = PropagationSession(Problem(4, 0, emptyArray(), clauses).bake())

        for (bytes in listOf(0L, 512L, 35_000L)) {
            assertNull(PrivateRebuildParity.capture(session, bytes, { true }))
        }
    }

    @Test
    fun `matching dimensions should not transfer parity authority between sources`() {
        val clauses = (0..3).flatMap { excluded ->
            val columns = (0..3).filter { it != excluded }
            val rhs = if (excluded == 0) 1 else 0
            (0..7).filter { it.countOneBits() % 2 != rhs }.map { mask ->
                Clause(IntArray(3) { Lit.make(columns[it], mask and (1 shl it) == 0) })
            }
        }.toTypedArray<Factor>()
        val source = Problem(4, 0, emptyArray(), clauses).bake()
        val captured = assertNotNull(PrivateRebuildParity.capture(PropagationSession(source), 1_048_576L, { true }))
        val other = Problem(4, 0, emptyArray(), clauses.copyOf()).bake()

        val generation = PrivateRebuildGeneration.prepare(PropagationSession(other), 1_048_576L,
            Cancellation.Never, parity = captured)

        assertNull(generation)
    }

    @Test
    fun `scoped roots should withhold parity authority`() {
        val clauses = (0..3).flatMap { excluded ->
            val columns = (0..3).filter { it != excluded }
            val rhs = if (excluded == 0) 1 else 0
            (0..7).filter { it.countOneBits() % 2 != rhs }.map { mask ->
                Clause(IntArray(3) { Lit.make(columns[it], mask and (1 shl it) == 0) })
            }
        }.toTypedArray<Factor>()
        for (permanent in listOf(false, true)) {
            val session = PropagationSession(Problem(4, 0, emptyArray(), clauses).bake())
            if (permanent) session.addLearnedClause(Clause(intArrayOf(1)), 1, permanent = true)
            else session.seed(Assumptions(bools = mapOf(0 to false)))

            assertNull(PrivateRebuildParity.capture(session, 1_048_576L, { true }))
        }
    }
}
