package com.eignex.klause.factor.table

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.intdomain.SurvivorsDomain
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.AtomKind
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.baked
import com.eignex.klause.propagation.factorAt
import com.eignex.klause.propagation.holeReasonFor
import com.eignex.klause.propagation.mark
import com.eignex.klause.propagation.reasonOf
import com.eignex.klause.propagation.undoTo
import com.eignex.klause.propagation.atomVarEq
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.Cancellation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TablePropagatorTest {

    @Test
    fun `historical alternative should ignore a preferred hole carved after the deduction`() {
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 2) }, arrayOf<Factor>(
            Table(intArrayOf(0, 1, 2), longArrayOf(0, 0, 0, 1, 1, 1))))
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.excludeIntValue(1, 1))
        val atTrail = state.undo.size
        check(state.excludeIntValue(2, 1))
        val preferred = IntHashSet().apply { add(Lit.make(state.atomVarEq(2, 1), true)) }

        val reason = assertNotNull((state.factorAt(0) as TablePropagator).historicalAlternative(
            state, intArrayOf(0, 2, 0, 1), atTrail, preferred))

        assertEquals(listOf(Lit.make(state.atomVarEq(1, 1), true)), reason.toList())
    }

    @Test
    fun `historical alternative should decline tables outside its row cell and column limits`() {
        for ((rows, columns) in listOf(257 to 2, 128 to 33, 1 to 65)) {
            val problem = Problem(0, columns, Array(columns) { IntDomain(0, 1) }, arrayOf<Factor>(
                Table(IntArray(columns) { it }, LongArray(rows * columns))))
            val state = PropagationState(problem, Assumptions.None)

            val reason = (state.factorAt(0) as TablePropagator).historicalAlternative(
                state, intArrayOf(0, 2, 0, 1), 0, IntHashSet())

            assertNull(reason)
        }
    }


    @Test
    fun `indexed table deductions should be implied by their historical reasons`() {
        val tuples = LongArray(9 * 16 * 3) { cell ->
            val row = cell / 3 % 9
            when (cell % 3) {
                0 -> (row / 3).toLong()
                1 -> (row % 3).toLong()
                else -> ((row / 3 + row % 3) % 3).toLong()
            }
        }
        val problem = Problem(
            0, 3, Array(3) { IntDomain(0, 2) }, arrayOf<Factor>(Table(intArrayOf(0, 1, 2), tuples)),
        )

        PropagationReasonOracle.assertReasonsImply(problem, "indexed-delta") { state ->
            state.setInt(0, 0) && state.excludeIntValue(1, 1)
        }
    }

    @Test
    fun `aliased table positions should filter the domains changed by their own pruning`() {
        val relation = longArrayOf(0, 1, 1, 0, 2, 1)
        val problem = Problem(
            0, 1, arrayOf(IntDomain(0, 2)),
            arrayOf<Factor>(Table(intArrayOf(0, 0), LongArray(384) { relation[it % relation.size] })),
        )
        val session = PropagationSession(problem)

        val result = session.pinInt(0, 1)

        assertIs<PropagationResult.Unsat>(result)
    }

    @Test
    fun `indexed live supports should cold start after restoring above their construction branch`() {
        val problem = Problem(
            0, 2, Array(2) { IntDomain(0, 3) },
            arrayOf<Factor>(Table(intArrayOf(0, 1), LongArray(512) { (it / 2 % 4).toLong() })),
        )
        val state = PropagationState(problem, Assumptions.None)
        assertNull(state.runToFixpoint(allFactors = true, skipExpensiveBake = true))
        state.undoLogging = true
        val root = state.mark()
        state.currentLevel = 1
        check(state.setInt(0, 0))
        assertNull(state.runToFixpoint(allFactors = false))
        assertEquals(IntDomain(0, 0), state.intDomains[1])
        state.undoTo(root)
        state.currentLevel = 1
        check(state.setInt(0, 3))

        assertNull(state.runToFixpoint(allFactors = false))

        assertEquals(IntDomain(3, 3), state.intDomains[1])
    }

    @Test
    fun `indexed delta filtering should restore interior supports in a sibling branch`() {
        val problem = Problem(
            0, 2, Array(2) { IntDomain(0, 3) },
            arrayOf<Factor>(Table(intArrayOf(0, 1), LongArray(512) { (it / 2 % 4).toLong() })),
        )
        val state = PropagationState(problem, Assumptions.None)
        assertNull(state.runToFixpoint(allFactors = true))
        state.undoLogging = true
        val root = state.mark()
        state.currentLevel = 1
        check(state.excludeIntValue(0, 1))
        assertNull(state.runToFixpoint(allFactors = false))
        assertTrue(1L !in state.intDomains[1])
        state.undoTo(root)
        state.currentLevel = 1
        check(state.excludeIntValue(0, 2))

        assertNull(state.runToFixpoint(allFactors = false))

        assertTrue(1L in state.intDomains[1])
        assertTrue(2L !in state.intDomains[1])
    }

    @Test
    fun `interrupted cold table preparation should resume its required filtering`() {
        for (limit in listOf(3, 6, 10)) {
            val problem = Problem(
                0, 2, Array(2) { IntDomain(0, 511) },
                arrayOf<Factor>(Table(intArrayOf(0, 1), LongArray(512) { (it / 2 * 2).toLong() })),
            )
            val state = PropagationState(problem, Assumptions.None)
            state.beginRootFixpoint().forEach { }
            var polls = 0

            assertNull(state.advanceRootFixpoint(Cancellation { ++polls >= limit }))

            assertTrue(state.rootFixpointPending)
            assertFalse(state.runCancelled)
            assertEquals(limit, polls)
            while (state.rootFixpointPending) assertNull(state.advanceRootFixpoint(Cancellation.Never))
            for (col in 0..1) assertEquals(256L, state.intDomains[col].valueCount)
        }
    }

    @Test
    fun `interrupted table filtering should preserve sibling supports after undo`() {
        val problem = Problem(
            0, 2, Array(2) { IntDomain(0, 255) },
            arrayOf<Factor>(Table(intArrayOf(0, 1), LongArray(512) { (it / 2).toLong() })),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.beginRootFixpoint().forEach { }
        while (state.rootFixpointPending) assertNull(state.advanceRootFixpoint(Cancellation.Never))
        state.undoLogging = true
        val root = state.mark()
        state.currentLevel = 1
        check(state.tightenIntMax(0, 63))
        state.beginSeedFixpoint().forEach { }
        var polls = 0
        assertNull(state.advanceRootFixpoint(Cancellation { ++polls >= 5 }))
        assertTrue(state.rootFixpointPending)
        state.undoTo(root)
        state.currentLevel = 1
        check(state.tightenIntMin(0, 192))

        assertNull(state.runToFixpoint(allFactors = false))

        assertEquals(192L, state.intDomains[1].min)
        assertEquals(255L, state.intDomains[1].max)
    }

    @Test
    fun `interrupted delayed table reasons should withhold partial premises`() {
        val problem = Problem(
            0, 2, arrayOf(IntDomain(0, 127), IntDomain(0, 2)),
            arrayOf<Factor>(Table(intArrayOf(0, 1), LongArray(256) { cell ->
                val row = cell / 2
                if (cell % 2 == 0) row.toLong() else when (row) {
                    0 -> 0L
                    127 -> 2L
                    else -> 1L
                }
            })),
        )
        val state = PropagationState(problem, Assumptions.None)
        assertNull(state.runToFixpoint(allFactors = true))
        state.undoLogging = true
        state.levelToDecisionVar.add(0)
        state.currentLevel = 1
        check(state.setInt(0, 0))
        assertNull(state.runToFixpoint(allFactors = false))
        val reason = assertNotNull(state.intMaxAntecedents[1])
        var polls = 0
        state.finiteWork.cancellation = Cancellation { ++polls >= 2 }

        assertNull(state.reasonOf(reason))

        assertTrue(state.runCancelled)
        assertFalse(state.lazyReasonMemo.containsKey(reason))
    }

    @Test
    fun `bounded unary supports should constrain local search root domains`() {
        val cases = listOf(
            Table(intArrayOf(0), longArrayOf(10, 1, 5, 5)) to listOf(1L, 5L, 10L),
            Table(intArrayOf(0), longArrayOf(1, 8), longArrayOf(3, 10)) to listOf(1L, 3L, 8L, 9L, 10L),
        )
        for ((table, expected) in cases) {
            val domain = IntDomain(0, 14).excludeValue(2)
            val problem = Problem(0, 1, arrayOf(domain), arrayOf<Factor>(table))

            val model = LocalSearchModel.of(problem.bake())

            assertEquals(expected, (0L..14L).filter { it in model.domains[0] })
            assertEquals(domain, problem.intDomainOrNull(0))
        }
    }

    @Test
    fun `sparse unary supports should fold without walking their span`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 1_000_000)),
            arrayOf<Factor>(Table(intArrayOf(0), longArrayOf(1_000_000, 0, 0))),
        )

        val model = LocalSearchModel.of(problem.bake())

        assertEquals(2L, model.domains[0].valueCount)
        assertEquals(0L, model.domains[0].min)
        assertEquals(1_000_000L, model.domains[0].max)
        assertTrue(500_000L !in model.domains[0])
    }

    @Test
    fun `unary restrictions should intersect during root propagation`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 14)),
            arrayOf<Factor>(
                Table(intArrayOf(0), longArrayOf(1), longArrayOf(10)),
                Table(intArrayOf(0), longArrayOf(5), longArrayOf(7)),
            ),
        )

        val baked = problem.bake()

        assertEquals(IntDomain(5, 7), baked.rootIntDomain(0))
    }

    @Test
    fun `disjoint unary supports should refute the root`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 1)),
            arrayOf<Factor>(Table(intArrayOf(0), longArrayOf(2))),
        )

        val baked = problem.bake()

        assertIs<PropagationResult.Unsat>(baked.baked)
    }

    @Test
    fun `large unary supports should remain deferred during bake`() {
        val cases = listOf(
            Table(intArrayOf(0), longArrayOf(1), longArrayOf(5_000)),
            Table(intArrayOf(0), longArrayOf(Long.MIN_VALUE), longArrayOf(Long.MAX_VALUE)),
            Table(intArrayOf(0), LongArray(4_097) { 1L }),
        )
        for (table in cases) {
            val problem = Problem(0, 1, arrayOf(IntDomain(0, 14)), arrayOf<Factor>(table))

            val baked = problem.bake()

            assertEquals(IntDomain(0, 14), baked.rootIntDomain(0))
        }
    }

    @Test
    fun `bounded unary supports should preserve Long endpoints`() {
        val cases = listOf(
            Triple(Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE + 1),
            Triple(Long.MAX_VALUE - 2, Long.MAX_VALUE - 1, Long.MAX_VALUE),
        )
        for ((base, lower, upper) in cases) {
            val problem = Problem(
                0,
                1,
                arrayOf(IntDomain(base, base + 2)),
                arrayOf<Factor>(Table(intArrayOf(0), longArrayOf(lower), longArrayOf(upper))),
            )

            val baked = problem.bake()

            assertEquals(IntDomain(lower, upper), baked.rootIntDomain(0))
        }
    }

    @Test
    fun `wide table support iteration should stop at the upper Long endpoint`() {
        for (lower in listOf(Long.MAX_VALUE, Long.MAX_VALUE - 1)) {
            val problem = Problem(
                0,
                2,
                arrayOf(IntDomain(0, 0), IntDomain(Long.MAX_VALUE - 2, Long.MAX_VALUE)),
                arrayOf<Factor>(
                    Table(intArrayOf(0, 1), longArrayOf(0, lower), longArrayOf(0, Long.MAX_VALUE)),
                ),
            )
            val state = PropagationState(problem, Assumptions.None)

            val conflict = state.runToFixpoint(allFactors = true)

            assertNull(conflict)
            assertEquals(IntDomain(lower, Long.MAX_VALUE), state.intDomains[1])
        }
    }

    @Test
    fun `skipExpensiveBake should defer the table's root pruning`() {
        fun problem() = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = Array(2) { IntDomain(0, 3) },
            factors = arrayOf<Factor>(Table(xs = intArrayOf(0, 1), tuples = longArrayOf(0, 1, 2, 3))),
        )
        val full = PropagationState(problem(), Assumptions.None)
        full.runToFixpoint(allFactors = true, skipExpensiveBake = false)
        val cheap = PropagationState(problem(), Assumptions.None)
        cheap.runToFixpoint(allFactors = true, skipExpensiveBake = true)
        assertEquals(2L, full.intDomains[0].max)
        assertEquals(3L, cheap.intDomains[0].max)
    }

    @Test
    fun `an interval column should prune to values within the range and domain`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 0), IntDomain(0, 3)),
            factors = arrayOf<Factor>(
                Table(xs = intArrayOf(0, 1), tuples = longArrayOf(0, 2), hi = longArrayOf(0, 5)),
            ),
        )
        val results = BacktrackSolver(problem.bake()).enumerate(BacktrackParams(randomSeed = 0L))
            .map { it.ints.map { v -> v.toInt() } }.toList().toSet()
        assertEquals(setOf(listOf(0, 2), listOf(0, 3)), results)
    }

    @Test
    fun `an empty live tuple prefix should restore after conflict rollback`() {
        for (base in listOf(0L, 5_000_000_000L)) {
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 2,
                intDomains = arrayOf(IntDomain(0, 1), IntDomain(base, base + 1)),
                factors = arrayOf<Factor>(Table(xs = intArrayOf(0, 1), tuples = longArrayOf(0, base, 1, base + 1))),
            )
            val state = PropagationState(problem, Assumptions.None)
            state.runToFixpoint(allFactors = true)
            state.undoLogging = true
            val mark = state.mark()
            state.currentLevel = 1
            assertTrue(state.tightenIntMax(0, 0))
            assertTrue(state.tightenIntMin(1, base + 1))
            assertNotNull(state.runToFixpoint(allFactors = false))
            state.undoTo(mark)

            state.currentLevel = 1
            assertTrue(state.tightenIntMin(0, 1))
            assertNull(state.runToFixpoint(allFactors = false))

            assertEquals(base + 1, state.intDomains[1].min)
            assertEquals(base + 1, state.intDomains[1].max)
        }
    }

    @Test
    fun `duplicate ground supports should leave unsupported sparse values filtered`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 1), SurvivorsDomain(-10, 130, longArrayOf(-10, 63, 64, 130))),
            factors = arrayOf<Factor>(
                Table(xs = intArrayOf(0, 1), tuples = longArrayOf(0, -10, 0, -10, 1, 64, 1, 64, 0, 130)),
            ),
        )

        val session = PropagationSession(problem)

        assertEquals(listOf(-10L, 64L, 130L), listOf(-10L, 63L, 64L, 130L).filter { it in session.intDomain(1) })
        assertIs<PropagationResult.Implied>(session.pinInt(0, 1))
        assertEquals(64L, session.intDomain(1).min)
        assertEquals(64L, session.intDomain(1).max)
        session.popLast()
        assertIs<PropagationResult.Implied>(session.pinInt(0, 0))
        assertEquals(listOf(-10L, 130L), listOf(-10L, 64L, 130L).filter { it in session.intDomain(1) })
    }

    @Test
    fun `interval supports should preserve exact word boundaries over sparse domains`() {
        val ranges = listOf(0 to 0, 0 to 63, 63 to 64, 1 to 190, 64 to 127, 65 to 129)
        for ((first, last) in ranges) {
            val base = -73L
            val members = (0..191).filter { it != 62 && it != 66 }.map { base + it }.toLongArray()
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 2,
                intDomains = arrayOf(IntDomain(0, 0), SurvivorsDomain(base, base + 191, members)),
                factors = arrayOf<Factor>(
                    Table(
                        xs = intArrayOf(0, 1),
                        tuples = longArrayOf(0, base + first, 0, base + 189),
                        hi = longArrayOf(0, base + last, 0, base + 191),
                    ),
                ),
            )

            val state = PropagationState(problem, Assumptions.None)
            state.runToFixpoint(allFactors = true)

            val expected = members.filter { it - base in first.toLong()..last.toLong() || it - base >= 189 }
            val actual = (base..base + 191).filter { it in state.intDomains[1] }
            assertEquals(expected, actual, "range $first..$last")
        }
    }

    @Test
    fun `nested table filtering should restore supports after failed and sibling branches`() {
        for (base in listOf(0L, 5_000_000_000L)) {
            val members = longArrayOf(base, base + 63, base + 64, base + 130)
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 3,
                intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 1), SurvivorsDomain(base, base + 130, members)),
                factors = arrayOf<Factor>(
                    Table(
                        xs = intArrayOf(0, 1, 2),
                        tuples = longArrayOf(0, 0, base, 0, 1, base + 64, 1, 0, base + 63, 2, 1, base + 130),
                        hi = longArrayOf(0, 0, base + 63, 0, 1, base + 130, 1, 0, base + 64, 2, 1, base + 130),
                    ),
                ),
            )
            val session = PropagationSession(problem)
            assertIs<PropagationResult.Implied>(session.pinInt(0, 0))
            assertIs<PropagationResult.Implied>(session.pinInt(1, 0))
            assertEquals(listOf(base, base + 63), members.filter { it in session.intDomain(2) })
            assertIs<PropagationResult.Unsat>(session.pinInt(2, base + 130))
            session.popLast()
            assertIs<PropagationResult.Implied>(session.pinInt(1, 1))
            assertEquals(listOf(base + 64, base + 130), members.filter { it in session.intDomain(2) })
            session.popToLevel(0)
            assertIs<PropagationResult.Implied>(session.pinInt(0, 1))
            assertEquals(listOf(base + 63, base + 64), members.filter { it in session.intDomain(2) })
            session.popLast()
            assertIs<PropagationResult.Implied>(session.pinInt(0, 2))
            assertEquals(listOf(base + 130), members.filter { it in session.intDomain(2) })
        }
    }

    @Test
    fun `interval deductions across support words should be implied by sparse domain reasons`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 1), SurvivorsDomain(0, 130, longArrayOf(0, 63, 64, 130))),
            factors = arrayOf<Factor>(
                Table(
                    xs = intArrayOf(0, 1),
                    tuples = longArrayOf(0, 0, 0, 64, 1, 63),
                    hi = longArrayOf(0, 63, 0, 130, 1, 64),
                ),
            ),
        )

        PropagationReasonOracle.assertReasonsImply(problem, "interval words") { state ->
            state.excludeIntValue(1, 63) && state.tightenIntMin(0, 1)
        }
    }

    @Test
    fun `a table prune should cite only what ruled out the tuples holding the removed values`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 4), IntDomain(0, 3), IntDomain(0, 1)),
            factors = arrayOf<Factor>(
                Table(xs = intArrayOf(0, 1, 2), tuples = longArrayOf(0, 0, 0, 1, 0, 0, 2, 1, 0, 3, 2, 0, 4, 3, 0)),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.excludeIntValue(0, 2L) && state.tightenIntMax(0, 3L) && state.tightenIntMax(2, 0L))
        state.currentFactor = 0

        check(state.factorAt(0).propagate(state, 0))

        val cited = state.reasonOf(state.holeReasonFor(1, 1L))!!.map { lit ->
            val atom = Lit.variable(lit) - problem.numBoolVars
            Triple(state.atoms.intVar[atom], state.atoms.kind[atom], state.atoms.threshold[atom])
        }
        assertEquals(setOf(Triple(0, AtomKind.EQ, 2L)), cited.toSet())
    }

    @Test
    fun `historical ground support should ignore later removals in repeated cells`() {
        for (shortSupport in listOf(false, true)) {
            val tuples = longArrayOf(0, 0, 0, 1, 1, 1, 2, 1, 2, 3, 2, 3)
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 3,
                intDomains = Array(3) { IntDomain(0, 3) },
                factors = arrayOf<Factor>(
                    Table(intArrayOf(0, 1, 2), tuples, hi = if (shortSupport) tuples else null),
                ),
            )
            val state = PropagationState(problem, Assumptions.None)
            assertNull(state.runToFixpoint(allFactors = true))
            state.undoLogging = true
            state.levelToDecisionVar.add(0)
            state.currentLevel = 1
            check(state.excludeIntValue(0, 1))
            assertNull(state.runToFixpoint(allFactors = false))
            val reason = assertNotNull(state.holeReasonFor(2, 1))
            check(state.excludeIntValue(0, 2))
            assertNull(state.runToFixpoint(allFactors = false))

            val cited = assertNotNull(state.reasonOf(reason)).map { lit ->
                val atom = Lit.variable(lit) - problem.numBoolVars
                Triple(state.atoms.intVar[atom], state.atoms.kind[atom], state.atoms.threshold[atom])
            }

            assertEquals(listOf(Triple(0, AtomKind.EQ, 1L)), cited)
        }
    }

    @Test
    fun `delayed table reasons should retain historical holes across sibling branches`() {
        for (base in listOf(1L, 5_000_000_000L)) {
            val values = longArrayOf(0, base, base + 1, base + 2, base + 3)
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 2,
                intDomains = Array(2) { SurvivorsDomain(0, base + 3, values) },
                factors = arrayOf<Factor>(
                    Table(xs = intArrayOf(0, 1), tuples = values.flatMap { listOf(it, it) }.toLongArray()),
                ),
            )
            val state = PropagationState(problem, Assumptions.None)
            assertNull(state.runToFixpoint(allFactors = true))
            state.undoLogging = true
            val mark = state.mark()
            state.levelToDecisionVar.add(problem.numBoolVars)
            state.currentLevel = 1
            check(state.excludeIntValue(0, base) && state.excludeIntValue(0, base + 1))
            assertNull(state.runToFixpoint(allFactors = false))
            val reason = assertNotNull(state.holeReasonFor(1, base))
            val second = assertNotNull(state.holeReasonFor(1, base + 1))
            check(state.excludeIntValue(0, base + 2) && state.tightenIntMax(0, base + 2))

            val cited = assertNotNull(state.reasonOf(reason)).map { lit ->
                val atom = Lit.variable(lit) - problem.numBoolVars
                Triple(state.atoms.intVar[atom], state.atoms.kind[atom], state.atoms.threshold[atom])
            }

            assertEquals(listOf(Triple(0, AtomKind.EQ, base)), cited)
            val secondCited = assertNotNull(state.reasonOf(second)).map { lit ->
                val atom = Lit.variable(lit) - problem.numBoolVars
                Triple(state.atoms.intVar[atom], state.atoms.kind[atom], state.atoms.threshold[atom])
            }
            assertEquals(listOf(Triple(0, AtomKind.EQ, base + 1)), secondCited)
            state.undoTo(mark)
            state.levelToDecisionVar.add(problem.numBoolVars)
            state.currentLevel = 1
            check(state.excludeIntValue(0, base + 2))
            assertNull(state.runToFixpoint(allFactors = false))
            assertTrue(base in state.intDomains[1] && base + 1 in state.intDomains[1])
            val sibling = assertNotNull(state.reasonOf(state.holeReasonFor(1, base + 2))).map { lit ->
                val atom = Lit.variable(lit) - problem.numBoolVars
                Triple(state.atoms.intVar[atom], state.atoms.kind[atom], state.atoms.threshold[atom])
            }
            assertEquals(listOf(Triple(0, AtomKind.EQ, base + 2)), sibling)
        }
    }

    @Test
    fun `table deductions should be implied by their reasons under carved holes`() {
        val rng = Random(0x7AB2)
        repeat(300) { iter ->
            val arity = 3
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = arity,
                intDomains = Array(arity) { IntDomain(0, 3) },
                factors = arrayOf<Factor>(
                    Table(xs = IntArray(arity) { it }, tuples = LongArray(6 * arity) { rng.nextInt(4).toLong() }),
                ),
            )
            PropagationReasonOracle.assertReasonsImply(problem, "table#$iter") { state ->
                (0 until 4).all {
                    val v = rng.nextInt(arity)
                    when (rng.nextInt(3)) {
                        0 -> state.excludeIntValue(v, rng.nextInt(4).toLong())
                        1 -> state.tightenIntMin(v, rng.nextInt(2).toLong())
                        else -> state.tightenIntMax(v, 2L + rng.nextInt(2))
                    }
                }
            }
        }
    }

    @Test
    fun `interval table deductions should be implied by their reasons under carved holes`() {
        val rng = Random(0x7AB3)
        repeat(300) { iter ->
            val arity = 3
            val lo = LongArray(5 * arity) { rng.nextInt(5).toLong() }
            val hi = LongArray(lo.size) { lo[it] + rng.nextInt(3) }
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = arity,
                intDomains = Array(arity) { IntDomain(0, 5) },
                factors = arrayOf<Factor>(Table(xs = IntArray(arity) { it }, tuples = lo, hi = hi)),
            )
            PropagationReasonOracle.assertReasonsImply(problem, "interval-table#$iter") { state ->
                (0 until 5).all {
                    val v = rng.nextInt(arity)
                    when (rng.nextInt(3)) {
                        0 -> state.excludeIntValue(v, rng.nextInt(6).toLong())
                        1 -> state.tightenIntMin(v, rng.nextInt(3).toLong())
                        else -> state.tightenIntMax(v, 3L + rng.nextInt(3))
                    }
                }
            }
        }
    }
}
