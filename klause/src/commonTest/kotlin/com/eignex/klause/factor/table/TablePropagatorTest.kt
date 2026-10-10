package com.eignex.klause.factor.table

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.intdomain.SurvivorsDomain
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.AtomKind
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.factorAt
import com.eignex.klause.propagation.holeReasonFor
import com.eignex.klause.propagation.mark
import com.eignex.klause.propagation.reasonOf
import com.eignex.klause.propagation.undoTo
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TablePropagatorTest {

    @Test
    fun `skipExpensiveBake defers the table's root pruning`() {
        // Table {(0,1),(2,3)} over vars in [0..3] is GAC at the root: value 3 of var0 has no support,
        // so a full bake tightens its max 3 -> 2. A raw problem never bakes at construction, so each state
        // starts from the raw [0..3] domains.
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
        assertEquals(2L, full.intDomains[0].max, "table fires: 3 unsupported, max 3 -> 2")
        assertEquals(3L, cheap.intDomains[0].max, "expensive table skipped: domain intact")
    }

    @Test
    fun `an interval column prunes to only values within the range and domain`() {
        // Single row (a, [2..5]) with b ∈ [0..3]: b must land in [2..5] ∩ [0..3] = {2, 3}.
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
    fun `an empty live tuple prefix restores after conflict rollback`() {
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
    fun `duplicate ground supports leave unsupported sparse values filtered`() {
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
    fun `interval supports preserve exact word boundaries over sparse domains`() {
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
    fun `nested table filtering restores supports after failed and sibling branches`() {
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
    fun `interval deductions across support words are implied by sparse domain reasons`() {
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
    fun `a table prune cites only what ruled out the tuples holding the removed values`() {
        // x1 loses 1 and 3: (2, 1, 0) fell to x0 != 2 and (4, 3, 0) to x0 <= 3. x2 <= 0 ruled out no tuple.
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
        assertEquals(setOf(Triple(0, AtomKind.EQ, 2L), Triple(0, AtomKind.LE, 3L)), cited.toSet())
    }

    @Test
    fun `delayed table reasons retain historical holes across sibling branches`() {
        for (base in listOf(1L, 5_000_000_000L)) {
            val values = longArrayOf(0, base, base + 1, base + 2)
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 2,
                intDomains = Array(2) { SurvivorsDomain(0, base + 2, values) },
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
            check(state.excludeIntValue(0, base))
            assertNull(state.runToFixpoint(allFactors = false))
            val reason = assertNotNull(state.holeReasonFor(1, base))
            check(state.excludeIntValue(0, base + 1) && state.tightenIntMax(0, base + 1))

            val cited = assertNotNull(state.reasonOf(reason)).map { lit ->
                val atom = Lit.variable(lit) - problem.numBoolVars
                Triple(state.atoms.intVar[atom], state.atoms.kind[atom], state.atoms.threshold[atom])
            }

            assertEquals(listOf(Triple(0, AtomKind.EQ, base)), cited)
            state.undoTo(mark)
            state.levelToDecisionVar.add(problem.numBoolVars)
            state.currentLevel = 1
            check(state.excludeIntValue(0, base + 1))
            assertNull(state.runToFixpoint(allFactors = false))
            val sibling = assertNotNull(state.reasonOf(state.holeReasonFor(1, base + 1))).map { lit ->
                val atom = Lit.variable(lit) - problem.numBoolVars
                Triple(state.atoms.intVar[atom], state.atoms.kind[atom], state.atoms.threshold[atom])
            }
            assertEquals(listOf(Triple(0, AtomKind.EQ, base + 1)), sibling)
        }
    }

    @Test
    fun `table deductions are implied by their reasons under carved holes`() {
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
    fun `interval table deductions are implied by their reasons under carved holes`() {
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
