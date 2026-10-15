package com.eignex.klause.factor.table

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.Vsids
import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.AtomKind
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.factorAt
import com.eignex.klause.propagation.holeReasonFor
import com.eignex.klause.propagation.mark
import com.eignex.klause.propagation.propagate
import com.eignex.klause.propagation.reasonOf
import com.eignex.klause.propagation.undoTo
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ElementPropagatorTest {
    @Test
    fun `variable-array result exclusions should retain their lazy support reasons`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 4), IntDomain(0, 4), IntDomain(0, 4)),
            factors = arrayOf<Factor>(Element(0, 1, longArrayOf(2, 3), true, 0)),
        )

        PropagationReasonOracle.assertReasonsImply(problem, "element result holes") { state ->
            state.excludeIntValue(2, 1) && state.excludeIntValue(2, 3) &&
                state.excludeIntValue(3, 1) && state.excludeIntValue(3, 3)
        }
    }

    @Test
    fun `selected-cell exclusions should retain the index premise when another cell aliases the result`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 4), IntDomain(0, 4)),
            factors = arrayOf<Factor>(Element(0, 1, longArrayOf(1, 2), true, 0)),
        )

        PropagationReasonOracle.assertReasonsImply(problem, "element selected cell alias") { state ->
            state.tightenIntMin(0, 1) && state.excludeIntValue(1, 1) && state.excludeIntValue(1, 3)
        }
    }

    @Test
    fun `selected-cell holes should retract before a sibling selects an excluded value`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 0), IntDomain(0, 4), IntDomain(0, 4)),
            factors = arrayOf<Factor>(Element(0, 1, longArrayOf(2), true, 0)),
        )
        val state = PropagationState(problem, Assumptions.None)
        assertNull(state.runToFixpoint(allFactors = true))
        state.undoLogging = true
        val mark = state.mark()
        state.currentLevel = 1
        assertTrue(state.excludeIntValue(1, 1) && state.excludeIntValue(1, 3))
        assertNull(state.runToFixpoint(allFactors = false))
        state.undoTo(mark)

        state.currentLevel = 1
        assertTrue(state.tightenIntMin(1, 1) && state.tightenIntMax(1, 1))
        assertNull(state.runToFixpoint(allFactors = false))

        assertEquals(1L, state.intDomains[2].min)
        assertEquals(1L, state.intDomains[2].max)
    }

    @Test
    fun `selection range should extend past the signed integer index offset`() {
        val offset = Int.MAX_VALUE.toLong()
        for (variables in listOf(false, true)) {
            val source = Problem(
                0,
                4,
                arrayOf(
                    IntDomain(offset, offset + 1),
                    IntDomain(9, 9),
                    IntDomain(4, 4),
                    IntDomain(9, 9),
                ),
                arrayOf<Factor>(
                    Element(
                        0,
                        1,
                        if (variables) longArrayOf(2, 3) else longArrayOf(4, 9),
                        variables,
                        Int.MAX_VALUE,
                    ),
                ),
            )
            val state = PropagationState(source, Assumptions.None)

            assertTrue(source.propagators[0].propagate(state, 0))

            assertEquals(offset + 1, state.intDomains[0].min)
            assertEquals(offset + 1, state.intDomains[0].max)
        }
    }

    @Test
    fun `removed constants should exclude their high offset positions`() {
        val offset = Int.MAX_VALUE.toLong()
        val source = Problem(
            0,
            2,
            arrayOf(IntDomain(offset, offset + 1), IntDomain(4, 9)),
            arrayOf<Factor>(Element(0, 1, longArrayOf(4, 9), false, Int.MAX_VALUE)),
        )
        val state = PropagationState(source, Assumptions.None)
        val propagator = source.propagators[0]
        assertTrue(propagator.propagate(state, 0))

        assertTrue(state.tightenIntMax(1, 4))
        assertTrue(propagator.propagate(state, 0))

        assertEquals(offset, state.intDomains[0].min)
        assertEquals(offset, state.intDomains[0].max)
    }

    private fun enumerate(problem: Problem, seed: Long): HashSet<List<Int>> = BacktrackSolver(problem.bake())
        .enumerate(BacktrackParams(randomSeed = seed, variableSelector = Vsids()))
        .take(100_000)
        .map { it.ints.map { v -> v.toInt() } }
        .toHashSet()

    @Test
    fun `const-array element narrowing should wake a bounds-subscribed linear summing its results`() {
        // The sum cap only wakes on bound events, so a narrowing that omits them emits r1 + r2 > 60 leaves.
        val arr = longArrayOf(50, 10)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(1, 2), IntDomain(1, 2), IntDomain(0, 100), IntDomain(0, 100)),
            factors = arrayOf<Factor>(
                Element(idx = 0, result = 2, arr = arr, arrIsVars = false, indexOffset = 1),
                Element(idx = 1, result = 3, arr = arr, arrIsVars = false, indexOffset = 1),
                Linear(intArrayOf(1, 1), intArrayOf(2, 3), LinearOp.LE, 60),
            ),
        )
        val brute = HashSet<List<Int>>()
        for (i1 in 1..2) {
            for (i2 in 1..2) {
                val r1 = arr[i1 - 1].toInt()
                val r2 = arr[i2 - 1].toInt()
                if (r1 + r2 <= 60) brute.add(listOf(i1, i2, r1, r2))
            }
        }
        for (seed in 1L..5L) {
            assertEquals(brute, enumerate(problem, seed), "seed=$seed")
        }
    }

    @Test
    fun `constant-array element deductions should be implied by their reasons under carved holes`() {
        val rng = Random(0xE1E0)
        repeat(300) { iter ->
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 2,
                intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 4)),
                factors = arrayOf<Factor>(
                    Element(
                        idx = 0,
                        result = 1,
                        arr = LongArray(4) { rng.nextInt(5).toLong() },
                        arrIsVars = false,
                        indexOffset = 0,
                    ),
                ),
            )
            PropagationReasonOracle.assertReasonsImply(problem, "element-const#$iter") { state ->
                (0 until 3).all {
                    val v = rng.nextInt(2)
                    when (rng.nextInt(3)) {
                        0 -> state.excludeIntValue(v, rng.nextInt(5).toLong())
                        1 -> state.tightenIntMin(v, rng.nextInt(3).toLong())
                        else -> state.tightenIntMax(v, 1L + rng.nextInt(4))
                    }
                }
            }
        }
    }

    @Test
    fun `a constant-array element should prune a result value citing only the positions that held it`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(5, 6)),
            factors = arrayOf<Factor>(
                Element(idx = 0, result = 1, arr = longArrayOf(6, 5, 6, 5, 6, 6), arrIsVars = false, indexOffset = 0),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.excludeIntValue(0, 1L) && state.excludeIntValue(0, 3L) && state.excludeIntValue(0, 4L))
        state.currentFactor = 0

        check(state.factorAt(0).propagate(state, 0))

        val cited = state.reasonOf(state.intMinAntecedents[1])!!.map { lit ->
            val atom = Lit.variable(lit) - problem.numBoolVars
            Triple(state.atoms.intVar[atom], state.atoms.kind[atom], state.atoms.threshold[atom])
        }
        assertEquals(setOf(Triple(0, AtomKind.EQ, 1L), Triple(0, AtomKind.EQ, 3L)), cited.toSet())
    }

    @Test
    fun `a constant-array result bound should cite the positions below it not the crossed holes`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(1, 4)),
            factors = arrayOf<Factor>(
                Element(idx = 0, result = 1, arr = longArrayOf(1, 2, 3, 4), arrIsVars = false, indexOffset = 0),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.excludeIntValue(1, 2L) && state.tightenIntMin(0, 1L))
        state.currentFactor = 0

        check(state.factorAt(0).propagate(state, 0))

        assertEquals(3L, state.intDomains[1].min)
        assertEquals(
            listOf(Lit.make(state.atomVarGe(0, 2), false)),
            state.reasonOf(state.intMinAntecedents[1])?.toList(),
        )
    }

    @Test
    fun `a variable-array element should drop a position citing only how its cell misses the result`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 5,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 9), IntDomain(0, 9), IntDomain(0, 9), IntDomain(0, 9)),
            factors = arrayOf<Factor>(
                Element(idx = 0, result = 1, arr = longArrayOf(2, 3, 4), arrIsVars = true, indexOffset = 0),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMax(1, 3) && state.tightenIntMin(3, 5) && state.excludeIntValue(4, 7))
        check(state.tightenIntMin(2, 1))
        state.currentFactor = 0

        check(state.factorAt(0).propagate(state, 0))

        val cited = state.reasonOf(state.holeReasonFor(0, 1L))!!.map { lit ->
            val atom = Lit.variable(lit) - problem.numBoolVars
            Triple(state.atoms.intVar[atom], state.atoms.kind[atom], state.atoms.threshold[atom])
        }
        assertEquals(setOf(Triple(1, AtomKind.LE, 3L), Triple(3, AtomKind.GE, 5L)), cited.toSet())
    }

    @Test
    fun `variable-array element deductions should be implied by their reasons under carved holes`() {
        val rng = Random(0xE1E1)
        repeat(300) { iter ->
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 5,
                intDomains = Array(5) { if (it == 0) IntDomain(0, 2) else IntDomain(0, 3) },
                factors = arrayOf<Factor>(
                    Element(idx = 0, result = 1, arr = longArrayOf(2, 3, 4), arrIsVars = true, indexOffset = 0),
                ),
            )
            PropagationReasonOracle.assertReasonsImply(problem, "element-var#$iter") { state ->
                (0 until 5).all {
                    val v = rng.nextInt(5)
                    when (rng.nextInt(3)) {
                        0 -> state.excludeIntValue(v, rng.nextInt(4).toLong())
                        1 -> state.tightenIntMin(v, rng.nextInt(2).toLong())
                        else -> state.tightenIntMax(v, 1L + rng.nextInt(3))
                    }
                }
            }
        }
    }
}
