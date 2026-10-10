package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.circuit.Circuit
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.GlobalCardinality
import com.eignex.klause.factor.global.Increasing
import com.eignex.klause.factor.global.Inverse
import com.eignex.klause.factor.global.LexLess
import com.eignex.klause.factor.global.NValue
import com.eignex.klause.factor.global.SymmetricAllDifferent
import com.eignex.klause.factor.global.ValuePrecede
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.scheduling.Diffn
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.model.PbOp
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PropagationProblemTest {

    @Test
    fun `sessions sharing a native projection keep their decisions independent`() {
        val problem = Problem(
            3,
            0,
            emptyArray(),
            listOf(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)))),
        )
        val projection = PropagationProblem(problem)
        val first = PropagationSession(projection, nativeSat = true)
        val second = PropagationSession(projection, nativeSat = true)

        first.pinBool(0, false)
        first.pinBool(1, false)
        second.pinBool(2, false)
        second.pinBool(1, false)

        assertEquals(listOf(false, false, true), (0..2).map(first::boolValue))
        assertEquals(listOf(true, false, false), (0..2).map(second::boolValue))
    }

    @Test
    fun `the watcher-filtered bool list drops a watcher-using factor from every variable`() {
        val clause = Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val pb = PseudoBoolean(longArrayOf(1, 2), intArrayOf(Lit.make(1, true), Lit.make(2, true)), PbOp.LE, 2L)
        val occ = PropagationProblem(Problem(3, 0, emptyArray(), listOf(clause, pb)))
        assertEquals(listOf(0), occ.boolOccurrences[0].toList())
        assertEquals(listOf(0, 1), occ.boolOccurrences[1].toList())
        assertEquals(listOf(1), occ.boolOccurrences[2].toList())
        assertTrue(occ.nonBoolWatcherBoolOccurrences[0].isEmpty(), "the clause wakes through its literal watchers")
        assertEquals(listOf(1), occ.nonBoolWatcherBoolOccurrences[1].toList())
        assertEquals(listOf(1), occ.nonBoolWatcherBoolOccurrences[2].toList())
    }

    @Test
    fun `the event-filtered int list drops a factor only for the variables it watches`() {
        val watching = AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 3, boundsConsistent = true)
        val plain = AllDifferent(intArrayOf(1, 2), domainMin = 0, domainSize = 3)
        val domains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2))
        val occ = PropagationProblem(Problem(0, 3, domains, listOf(watching, plain)))
        assertEquals(listOf(0), occ.intOccurrences[0].toList())
        assertEquals(listOf(0, 1), occ.intOccurrences[1].toList())
        assertEquals(listOf(1), occ.intOccurrences[2].toList())
        assertTrue(occ.nonIntEventWatcherIntOccurrences[0].isEmpty(), "the subscriber wakes on var 0 via its events")
        assertEquals(listOf(1), occ.nonIntEventWatcherIntOccurrences[1].toList())
        assertEquals(listOf(1), occ.nonIntEventWatcherIntOccurrences[2].toList())
    }

    @Test
    fun `propagation projection owns its occurrence lists`() {
        val problem = Problem(2, 0, emptyArray(), listOf(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))))
        val first = PropagationProblem(problem)
        val second = PropagationProblem(problem)
        assertTrue(first.boolOccurrences.contentDeepEquals(second.boolOccurrences))
    }

    @Test
    fun `interleaved general CP failures keep their own premises through peer success and undo`() {
        val cases = listOf(
            Triple<Factor, List<Pair<Int, Long>>, List<Pair<Int, Long>>>(
                LexLess(intArrayOf(0, 1), intArrayOf(2, 3), strict = true),
                listOf(0 to 2L, 2 to 1L, 1 to 1L, 3 to 2L),
                listOf(0 to 1L, 2 to 1L, 1 to 2L, 3 to 1L),
            ),
            Triple(Increasing(intArrayOf(0, 1, 2), strict = true), listOf(0 to 2L, 1 to 1L), listOf(1 to 2L, 2 to 1L)),
            Triple(SymmetricAllDifferent(intArrayOf(0, 1, 2)), listOf(0 to 1L, 1 to 1L), listOf(1 to 2L, 2 to 2L)),
            Triple(ValuePrecede(1, 2, intArrayOf(0, 1, 2)), listOf(0 to 2L), listOf(0 to 3L, 1 to 2L)),
            Triple(
                NValue(3, intArrayOf(0, 1, 2)),
                listOf(0 to 0L, 1 to 1L, 3 to 1L),
                listOf(1 to 1L, 2 to 2L, 3 to 1L),
            ),
            Triple(
                GlobalCardinality(
                    intArrayOf(0, 1, 2),
                    longArrayOf(1),
                    countLow = intArrayOf(0),
                    countHigh = intArrayOf(1),
                ),
                listOf(0 to 1L, 1 to 1L),
                listOf(1 to 1L, 2 to 1L),
            ),
            Triple(Circuit(intArrayOf(0, 1, 2, 3)), listOf(0 to 2L, 1 to 2L), listOf(2 to 1L, 3 to 1L)),
            Triple(
                Circuit(intArrayOf(0, 1, 2, 3), subcircuit = true),
                listOf(0 to 2L, 1 to 2L),
                listOf(2 to 1L, 3 to 1L),
            ),
            Triple(ArrayMinMax(3, intArrayOf(0, 1, 2), max = true), listOf(0 to 3L, 3 to 2L), listOf(2 to 3L, 3 to 2L)),
            Triple(
                Element(0, 1, longArrayOf(2, 3, 4), arrIsVars = true, indexOffset = 0),
                listOf(0 to 0L, 1 to 2L, 2 to 1L),
                listOf(0 to 1L, 1 to 2L, 3 to 1L),
            ),
            Triple(
                Cumulative(intArrayOf(0, 1, 2), longArrayOf(1, 1, 1), longArrayOf(1, 1, 1), 1),
                listOf(0 to 1L, 1 to 1L, 2 to 3L),
                listOf(0 to 3L, 1 to 1L, 2 to 1L),
            ),
            Triple(
                Cumulative(intArrayOf(0, 1, 2, 3), longArrayOf(1, 1, 1, 1), longArrayOf(1, 1, 1, 1), 2),
                listOf(0 to 1L, 1 to 1L, 2 to 1L, 3 to 3L),
                listOf(0 to 3L, 1 to 1L, 2 to 1L, 3 to 1L),
            ),
            Triple(
                Diffn(intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), longArrayOf(1, 1, 1), longArrayOf(1, 1, 1)),
                listOf(0 to 1L, 1 to 1L, 2 to 3L, 3 to 1L, 4 to 1L, 5 to 3L),
                listOf(0 to 3L, 1 to 1L, 2 to 1L, 3 to 3L, 4 to 1L, 5 to 1L),
            ),
        )
        for ((factor, firstPins, secondPins) in cases) {
            val label = factor::class.simpleName.orEmpty()
            val count = factor.intVars.max() + 1
            val projection = PropagationProblem(Problem(0, count, Array(count) { IntDomain(0, 3) }, listOf(factor)))
            val first = PropagationState(projection, Assumptions.None)
            val second = PropagationState(projection, Assumptions.None)
            val propagator = projection.propagators[0]
            assertSame(first.factorAt(0), second.factorAt(0), label)
            first.undoLogging = true
            second.undoLogging = true
            val firstRoot = first.mark()
            val secondRoot = second.mark()
            first.currentLevel = 1
            first.currentFactor = 0
            for ((v, value) in firstPins) {
                check(first.tightenIntMin(v, value) && first.tightenIntMax(v, value))
            }
            assertNotNull(first.runToFixpoint(allFactors = true), label)
            val firstReason = assertNotNull(propagator.conflictReason(first, 0), label).copyOf()
            second.currentLevel = 1
            second.currentFactor = 0
            for (v in count - 1 downTo 0) second.atomVarGe(v, 1)
            for ((v, value) in secondPins) {
                check(second.tightenIntMin(v, value) && second.tightenIntMax(v, value))
            }
            assertNotNull(second.runToFixpoint(allFactors = true), label)
            val secondReason = assertNotNull(propagator.conflictReason(second, 0), label).copyOf()
            assertContentEquals(firstReason, propagator.conflictReason(first, 0), label)
            second.undoTo(secondRoot)
            second.currentLevel = 1
            second.currentFactor = 0
            assertNull(second.runToFixpoint(allFactors = true), label)
            assertContentEquals(firstReason, propagator.conflictReason(first, 0), label)
            first.undoTo(firstRoot)
            first.currentLevel = 1
            first.currentFactor = 0
            assertNull(first.runToFixpoint(allFactors = true), label)
            second.undoTo(secondRoot)
            second.currentLevel = 1
            second.currentFactor = 0
            for ((v, value) in secondPins) {
                check(second.tightenIntMin(v, value) && second.tightenIntMax(v, value))
            }
            assertNotNull(second.runToFixpoint(allFactors = true), label)
            assertContentEquals(secondReason, propagator.conflictReason(second, 0), label)
        }
    }

    @Test
    fun `inverse Hall failures remain private when a peer succeeds and backtracks`() {
        val factor = Inverse(intArrayOf(0, 1, 2, 3), intArrayOf(4, 5, 6, 7))
        val projection = PropagationProblem(Problem(0, 8, Array(8) { IntDomain(0, 3) }, listOf(factor)))
        val first = PropagationState(projection, Assumptions.None)
        val second = PropagationState(projection, Assumptions.None)
        first.undoLogging = true
        second.undoLogging = true
        first.currentLevel = 1
        first.currentFactor = 0
        for (v in 0..2) check(first.tightenIntMax(v, 1))
        val propagator = projection.propagators[0]
        assertNotNull(first.runToFixpoint(allFactors = true))
        val reason = assertNotNull(propagator.conflictReason(first, 0)).copyOf()
        val root = second.mark()
        second.currentFactor = 0
        assertNull(second.runToFixpoint(allFactors = true))
        second.currentLevel = 1
        for (v in 4..6) check(second.tightenIntMax(v, 1))
        assertNotNull(second.runToFixpoint(allFactors = true))

        assertContentEquals(reason, propagator.conflictReason(first, 0))
        second.undoTo(root)
        second.currentFactor = 0
        assertNull(second.runToFixpoint(allFactors = true))
        assertContentEquals(reason, propagator.conflictReason(first, 0))
    }
}
