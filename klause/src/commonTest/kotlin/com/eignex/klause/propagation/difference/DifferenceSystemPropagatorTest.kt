package com.eignex.klause.propagation.difference

import com.eignex.klause.factor.ConflictReasonOracle
import com.eignex.klause.factor.FactorPropagationOracle
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntBounds
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.differenceFragmentOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The joint difference system as a propagator. Every model here leaves the integer variables unbounded,
 * so a row-at-a-time propagator can deduce nothing from them at all: what the tests pin down is exactly
 * the reasoning the graph adds — refuting a reified row from a path the asserted rows already carry, and
 * refusing a set of rows whose cycle is negative.
 */
class DifferenceSystemPropagatorTest {

    /** `aux ↔ (hi − lo ≤ bound)`, over unbounded integers. */
    private fun row(aux: Int, hi: Int, lo: Int, bound: Long) =
        ReifiedLinear(aux, longArrayOf(1, -1), intArrayOf(hi, lo), LinearOp.LE, bound)

    private fun problemOf(
        numBools: Int,
        numInts: Int,
        rows: List<ReifiedLinear>,
        domains: Array<IntDomain> = Array(numInts) { IntDomain(Long.MIN_VALUE, Long.MAX_VALUE) },
    ): Problem {
        val bounds = IntBounds.fromFiniteBounds(
            LongArray(numInts) { domains[it].min },
            LongArray(numInts) { domains[it].max },
            BooleanArray(numInts) { domains[it].min == Long.MIN_VALUE },
            BooleanArray(numInts) { domains[it].max == Long.MAX_VALUE },
            null,
            null,
        )
        val fragment = assertNotNull(differenceFragmentOf(Array<Factor>(rows.size) { rows[it] }, numInts, bounds))
        val factors = ArrayList<Factor>(rows)
        factors.add(DifferenceSystem(fragment.edges))
        return Problem(
            numBoolVars = numBools,
            numIntVars = numInts,
            intDomains = domains,
            factors = factors.toTypedArray(),
        )
    }

    /** Every column ranging over `0..hi`, which is what puts the declared sides into the fragment. */
    private fun boxed(numInts: Int, hi: Long) = Array(numInts) { IntDomain(0, hi) }

    /** The index of the system [problemOf] appends last. */
    private fun systemId(problem: Problem) = problem.factors.size - 1

    /** A three-row cycle `x0 < x1 < x2 < x0`, each row reified on its own aux. */
    private fun triangle() = problemOf(
        numBools = 3,
        numInts = 3,
        rows = listOf(row(0, 1, 0, -1L), row(1, 2, 1, -1L), row(2, 0, 2, -1L)),
    )

    private fun stateOf(problem: Problem, vararg pins: Int): PropagationState {
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        for (lit in pins) check(state.pinLit(lit)) { "pinning literal $lit failed" }
        return state
    }

    private fun runSystem(problem: Problem, state: PropagationState): Boolean {
        val id = systemId(problem)
        return problem.propagators[id].propagate(state, id)
    }

    @Test
    fun `two asserted rows refute the third before it is decided`() {
        val problem = triangle()
        val state = stateOf(problem, Lit.make(0, true), Lit.make(1, true))
        assertTrue(runSystem(problem, state))
        assertEquals(false, state.boolValues[2], "closing the cycle is refuted ahead of the decision")
    }

    @Test
    fun `a refutation cites the rows that forced it`() {
        val problem = triangle()
        val state = stateOf(problem, Lit.make(0, true), Lit.make(1, true))
        runSystem(problem, state)
        val antecedents = assertNotNull(state.boolAntecedents[2], "the pin must carry its forcing clause")
        assertEquals(
            setOf(Lit.make(0, false), Lit.make(1, false)),
            antecedents.toSet(),
            "exactly the guards on the refuting path",
        )
    }

    @Test
    fun `a row whose cycle is not closed is left open`() {
        val problem = triangle()
        val state = stateOf(problem, Lit.make(0, true))
        assertTrue(runSystem(problem, state))
        assertEquals(null, state.boolValues[2], "one row alone implies nothing about the third")
    }

    @Test
    fun `a positive cycle refutes nothing`() {
        val problem = problemOf(
            numBools = 3,
            numInts = 3,
            rows = listOf(row(0, 1, 0, 1L), row(1, 2, 1, 1L), row(2, 0, 2, 1L)),
        )
        val state = stateOf(problem, Lit.make(0, true), Lit.make(1, true))
        assertTrue(runSystem(problem, state))
        assertEquals(null, state.boolValues[2], "the cycle sums to 3 and is satisfiable")
    }

    @Test
    fun `a fully asserted negative cycle is a conflict`() {
        val problem = triangle()
        val state = stateOf(problem, Lit.make(0, true), Lit.make(1, true), Lit.make(2, true))
        assertFalse(runSystem(problem, state))
    }

    @Test
    fun `the conflict names the rows on the cycle`() {
        val problem = triangle()
        val state = stateOf(problem, Lit.make(0, true), Lit.make(1, true), Lit.make(2, true))
        runSystem(problem, state)
        val id = systemId(problem)
        val reason = assertNotNull(problem.propagators[id].conflictReason(state, id))
        assertEquals(setOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, false)), reason.toSet())
    }

    @Test
    fun `a false aux asserts the row's negation`() {
        // `¬(x1 − x0 ≤ −5)` is `x1 − x0 ≥ −4`, which the second row's `x1 − x0 ≤ −9` contradicts.
        val problem = problemOf(numBools = 2, numInts = 2, rows = listOf(row(0, 1, 0, -5L), row(1, 1, 0, -9L)))
        val state = stateOf(problem, Lit.make(0, false))
        assertTrue(runSystem(problem, state))
        assertEquals(false, state.boolValues[1], "the false branch of a reified difference constrains too")
    }

    @Test
    fun `a row its columns' declared ranges already exclude is refuted`() {
        // x1 - x0 <= -6 cannot hold with both columns in 0..5, and no row of the model says so: the
        // deduction is available only through the two declared ranges the graph no longer carries.
        val problem = problemOf(numBools = 1, numInts = 2, rows = listOf(row(0, 1, 0, -6L)), domains = boxed(2, 5))
        val state = stateOf(problem)
        assertTrue(runSystem(problem, state))
        assertEquals(false, state.boolValues[0], "the declared ranges refute the row on their own")
    }

    @Test
    fun `a refutation from declared ranges alone cites no guard`() {
        val problem = problemOf(numBools = 1, numInts = 2, rows = listOf(row(0, 1, 0, -6L)), domains = boxed(2, 5))
        val state = stateOf(problem)
        runSystem(problem, state)
        val antecedents = assertNotNull(state.boolAntecedents[0], "the pin must carry its forcing clause")
        assertTrue(antecedents.isEmpty(), "a declared range holds unconditionally, so nothing guards it")
    }

    @Test
    fun `a row its ranges permit is left open`() {
        val problem = problemOf(numBools = 1, numInts = 2, rows = listOf(row(0, 1, 0, -5L)), domains = boxed(2, 5))
        val state = stateOf(problem)
        assertTrue(runSystem(problem, state))
        assertEquals(null, state.boolValues[0], "x1 - x0 = -5 is reachable inside 0..5")
    }

    @Test
    fun `a range at the unbounded-search clamp refutes nothing`() {
        // The two sides sum past Long. A wrapped sum reads as a hugely negative distance, which would
        // refute every row in the model — the false-UNSAT shape this fold has to refuse outright.
        val clamp = 1L shl 62
        val problem = problemOf(
            numBools = 1,
            numInts = 2,
            rows = listOf(row(0, 1, 0, -6L)),
            domains = Array(2) { IntDomain(-clamp, clamp) },
        )
        val state = stateOf(problem)
        assertTrue(runSystem(problem, state))
        assertEquals(null, state.boolValues[0], "a range that cannot be summed decides nothing")
    }

    @Test
    fun `refuting over bounded columns never contradicts a solution`() {
        // The oracle the issue's history demands: #1534 and #1540 were both false unsat from a wrong
        // difference deduction, and both hid behind the clamp as `unknown`.
        val rng = Random(0x1529)
        repeat(120) { iter ->
            val hi = 1L + rng.nextInt(3)
            val rows = (0 until 3).map { aux ->
                val hiVar = rng.nextInt(3)
                var loVar = rng.nextInt(3)
                if (loVar == hiVar) loVar = (loVar + 1) % 3
                row(aux, hiVar, loVar, (rng.nextInt(5) - 3).toLong())
            }
            val problem = problemOf(numBools = 3, numInts = 3, rows = rows, domains = boxed(3, hi))
            FactorPropagationOracle.assertSound(problem, "difference-bounded#$iter")
        }
    }

    @Test
    fun `a conflict over bounded columns names a clause every solution satisfies`() {
        val rows = listOf(row(0, 1, 0, -1L), row(1, 2, 1, -1L), row(2, 0, 2, -1L))
        val problem = problemOf(numBools = 3, numInts = 3, rows = rows, domains = boxed(3, 4))
        val state = stateOf(problem, Lit.make(0, true), Lit.make(1, true), Lit.make(2, true))
        assertFalse(runSystem(problem, state))
        ConflictReasonOracle.assertEntailed(problem, state, systemId(problem), "difference-bounded-conflict")
    }

    @Test
    fun `a route reaching the constant node through a model row still refutes`() {
        // y >= 10 holds only through the row a <= y and a's own range; x <= 5 likewise through b. Neither
        // endpoint is bounded itself, so the refuting route runs y -> a -> zero -> b -> x: it reaches the
        // constant node through model rows rather than from the endpoints directly.
        val rows = listOf(
            ReifiedLinear(1, longArrayOf(1, -1), intArrayOf(2, 0), LinearOp.LE, 0L), // aux1: a - y <= 0
            ReifiedLinear(2, longArrayOf(1, -1), intArrayOf(1, 3), LinearOp.LE, 0L), // aux2: x - b <= 0
            row(0, 0, 1, 4L), // aux0: y - x <= 4
        )
        val domains = arrayOf(
            IntDomain(Long.MIN_VALUE, Long.MAX_VALUE), // y, open
            IntDomain(Long.MIN_VALUE, Long.MAX_VALUE), // x, open
            IntDomain(10, 10), // a
            IntDomain(5, 5), // b
        )
        val problem = problemOf(numBools = 3, numInts = 4, rows = rows, domains = domains)
        val state = stateOf(problem, Lit.make(1, true), Lit.make(2, true))
        assertTrue(runSystem(problem, state))
        assertEquals(false, state.boolValues[0], "y >= 10 and x <= 5 leave y - x >= 5, refuting the row")
    }

    @Test
    fun `a refutation routed through the constant node cites the rows that reached it`() {
        val rows = listOf(
            ReifiedLinear(1, longArrayOf(1, -1), intArrayOf(2, 0), LinearOp.LE, 0L),
            ReifiedLinear(2, longArrayOf(1, -1), intArrayOf(1, 3), LinearOp.LE, 0L),
            row(0, 0, 1, 4L),
        )
        val domains = arrayOf(
            IntDomain(Long.MIN_VALUE, Long.MAX_VALUE),
            IntDomain(Long.MIN_VALUE, Long.MAX_VALUE),
            IntDomain(10, 10),
            IntDomain(5, 5),
        )
        val problem = problemOf(numBools = 3, numInts = 4, rows = rows, domains = domains)
        val state = stateOf(problem, Lit.make(1, true), Lit.make(2, true))
        runSystem(problem, state)
        val antecedents = assertNotNull(state.boolAntecedents[0], "the pin must carry its forcing clause")
        assertEquals(
            setOf(Lit.make(1, false), Lit.make(2, false)),
            antecedents.toSet(),
            "both segments of the route are guarded rows and both must be named",
        )
    }

    @Test
    fun `two states sharing one propagator keep separate systems`() {
        // One propagator instance backs every arm of a portfolio, so its graph must not be shared.
        val problem = triangle()
        val id = systemId(problem)
        val decided = stateOf(problem, Lit.make(0, true), Lit.make(1, true))
        assertTrue(problem.propagators[id].propagate(decided, id))
        val fresh = stateOf(problem, Lit.make(0, true))
        assertTrue(problem.propagators[id].propagate(fresh, id))
        assertEquals(null, fresh.boolValues[2], "the second state must not inherit the first's edges")
    }
}
