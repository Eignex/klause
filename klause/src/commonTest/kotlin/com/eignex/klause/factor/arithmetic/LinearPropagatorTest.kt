package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.factorAt
import com.eignex.klause.propagation.propagatorProjection
import com.eignex.klause.propagation.reasonOf
import com.eignex.klause.propagation.setIntMinAsDecision
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinearPropagatorTest {
    @Test
    fun `linear slack should retain bounds when every term can span its activity range`() {
        for (op in listOf(LinearOp.LE, LinearOp.GE, LinearOp.EQ)) {
            for ((coefficients, domains, bound) in listOf(
                Triple(longArrayOf(1, 1, 1), Array(3) { IntDomain(0, 3) }, 3L),
                Triple(longArrayOf(2, -3, 0), arrayOf(IntDomain(0, 3), IntDomain(0, 2), IntDomain(-5, -5)), 0L),
            )) {
                val expected = domains.map { it.min to it.max }
                val state = PropagationState(
                    Problem(0, 3, domains, arrayOf(Linear(coefficients, intArrayOf(0, 1, 2), op, bound))),
                    Assumptions.None,
                )

                val consistent = state.factorAt(0).propagate(state, 0)

                assertTrue(consistent)
                assertEquals(expected, state.intDomains.map { it.min to it.max })
            }
        }
    }

    @Test
    fun `linear slack boundaries should retain required bound tightenings`() {
        for ((op, raiseMinimum) in listOf(
            LinearOp.LE to true, LinearOp.EQ to true, LinearOp.GE to false, LinearOp.EQ to false,
        )) {
            val state = PropagationState(
                Problem(
                    0, 2, Array(2) { IntDomain(0, 3) },
                    arrayOf(Linear(longArrayOf(1, 1), intArrayOf(0, 1), op, 3L)),
                ),
                Assumptions.None,
            )
            if (raiseMinimum) assertTrue(state.tightenIntMin(0, 1)) else assertTrue(state.tightenIntMax(0, 2))

            val consistent = state.factorAt(0).propagate(state, 0)

            assertTrue(consistent)
            assertEquals(if (raiseMinimum) 0L to 2L else 1L to 3L, state.intDomains[1].min to state.intDomains[1].max)
        }
    }

    @Test
    fun `overflowing linear term spans should retain endpoint pruning`() {
        for ((op, endpoint) in listOf(LinearOp.LE to -1L, LinearOp.GE to 1L)) {
            val state = PropagationState(
                Problem(
                    0, 1, arrayOf(IntDomain(-1, 1)),
                    arrayOf(Linear(longArrayOf(Long.MAX_VALUE), intArrayOf(0), op, Long.MAX_VALUE * endpoint)),
                ),
                Assumptions.None,
            )

            val consistent = state.factorAt(0).propagate(state, 0)

            assertTrue(consistent)
            assertEquals(endpoint to endpoint, state.intDomains[0].min to state.intDomains[0].max)
        }
    }

    @Test
    fun `nonbinding linear bounds should retain their coefficient visit charges`() {
        for (op in listOf(LinearOp.LE, LinearOp.GE, LinearOp.EQ)) {
            val state = PropagationState(
                Problem(
                    0, 3, Array(3) { IntDomain(0, 3) },
                    arrayOf(Linear(longArrayOf(1, 1, 1), intArrayOf(0, 1, 2), op, 3L)),
                ),
                Assumptions.None,
            )
            val propagator = state.factorAt(0)
            val before = state.work

            val consistent = propagator.propagate(state, 0)

            assertTrue(consistent)
            assertEquals(6L, state.work - before)
        }
    }

    @Test
    fun `signed linear rows should retain implications beside bounds that already hold`() {
        for (op in listOf(LinearOp.LE, LinearOp.EQ)) {
            for (direction in listOf(-1L, 1L)) {
                val problem = Problem(
                    0,
                    2,
                    arrayOf(IntDomain(0L, 3L), IntDomain(0L, 3L)),
                    arrayOf(Linear(longArrayOf(direction, -direction), intArrayOf(0, 1), op, 0L)),
                )

                PropagationReasonOracle.assertReasonsImply(problem, "$op nonbinding bounds $direction") { state ->
                    if (direction == 1L) state.tightenIntMin(0, 2L) else state.tightenIntMax(0, 1L)
                }
            }
        }
    }

    @Test
    fun `linear disequalities should remove targets outside the 32 bit range`() {
        for (target in listOf(Int.MIN_VALUE.toLong() - 1L, Int.MAX_VALUE.toLong() + 1L)) {
            val session = PropagationSession(
                Problem(
                    0,
                    1,
                    arrayOf(IntDomain(target - 1L, target + 1L)),
                    arrayOf(Linear(longArrayOf(1L), intArrayOf(0), LinearOp.NE, target)),
                ),
            )

            assertTrue(target !in session.intDomain(0))
        }
    }

    @Test
    fun `linear disequalities should preserve values when the quotient exceeds Long`() {
        val session = PropagationSession(
            Problem(
                0,
                1,
                arrayOf(IntDomain(Long.MIN_VALUE, Long.MIN_VALUE + 1L)),
                arrayOf(Linear(longArrayOf(-1L), intArrayOf(0), LinearOp.NE, Long.MIN_VALUE)),
            ),
        )

        assertTrue(Long.MIN_VALUE in session.intDomain(0))
    }

    @Test
    fun `linear deductions should be implied by their reasons under carved holes`() {
        val rng = Random(0x11E0)
        for (op in LinearOp.entries) {
            repeat(100) { iter ->
                val n = 3
                val problem = Problem(
                    numBoolVars = 0,
                    numIntVars = n,
                    intDomains = Array(n) { IntDomain(0, 4) },
                    factors = arrayOf<Factor>(
                        Linear(
                            IntArray(n) { (1 + rng.nextInt(3)) * (if (rng.nextBoolean()) 1 else -1) },
                            IntArray(n) { it },
                            op,
                            rng.nextInt(-4, 9),
                        ),
                    ),
                )
                PropagationReasonOracle.assertReasonsImply(problem, "linear-$op#$iter") { state ->
                    (0 until 4).all {
                        val v = rng.nextInt(n)
                        when (rng.nextInt(3)) {
                            0 -> state.excludeIntValue(v, rng.nextInt(5).toLong())
                            1 -> state.tightenIntMin(v, 1L + rng.nextInt(3))
                            else -> state.tightenIntMax(v, 1L + rng.nextInt(3))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `a violated assignment should be rejected when the row sum overflows 64 bits`() {
        val wide = 1L shl 62
        val session = PropagationSession(
            Problem(
                0,
                2,
                arrayOf(IntDomain(-wide, wide), IntDomain(-wide, wide)),
                arrayOf<Factor>(Linear(longArrayOf(1L shl 33, 1L shl 33), intArrayOf(0, 1), LinearOp.LE, 10L)),
            ),
        )
        assertTrue(session.pinInt(0, 1L shl 33) !is PropagationResult.Unsat)
        assertTrue(session.pinInt(1, 1L shl 33) is PropagationResult.Unsat)
    }

    @Test
    fun `a linear bound should cite the weakest bound that still forces it`() {
        // x0 + 2 * x1 <= 10: deciding x0 >= 4 forces x1 <= 3, which x0 >= 3 already does.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 10), IntDomain(0, 10)),
            factors = arrayOf<Factor>(Linear(intArrayOf(1, 2), intArrayOf(0, 1), LinearOp.LE, 10)),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        check(state.setIntMinAsDecision(0, 4))
        state.currentFactor = 0

        check(state.factorAt(0).propagate(state, 0))

        val atom = Lit.variable(state.reasonOf(state.intMaxAntecedents[1])!!.single()) - problem.numBoolVars
        assertEquals(0 to 3L, state.atoms.intVar[atom] to state.atoms.threshold[atom])
    }

    @Test
    fun `a wide linear bound should cite the bounds as they stood when it was deduced`() {
        // x0 + 2 * x1 + x2 + ... + x32 <= 10: a later x0 >= 6 must not change the cited reason.
        val n = 33
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(0, 10) },
            factors = arrayOf<Factor>(
                Linear(IntArray(n) { if (it == 1) 2 else 1 }, IntArray(n) { it }, LinearOp.LE, 10),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        check(state.setIntMinAsDecision(0, 4))
        state.currentFactor = 0
        check(state.factorAt(0).propagate(state, 0))
        check(state.setIntMinAsDecision(0, 6))

        val atom = Lit.variable(state.reasonOf(state.intMaxAntecedents[1])!!.single()) - problem.numBoolVars

        assertEquals(0 to 3L, state.atoms.intVar[atom] to state.atoms.threshold[atom])
    }

    @Test
    fun `a linear conflict should cite the weakest bound that still forces it`() {
        // x0 + x1 <= 5 with x1 >= 3 at the root: deciding x0 >= 4 overshoots by two, so x0 >= 3 already
        // forces the conflict and the reason cites that rather than the decision's own bound.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 10), IntDomain(3, 10)),
            factors = arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 5)),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        check(state.setIntMinAsDecision(0, 4))
        state.currentFactor = 0

        assertFalse(state.factorAt(0).propagate(state, 0))

        val reason = state.factorAt(0).conflictReason(state, 0)!!
        val atom = Lit.variable(reason.single()) - problem.numBoolVars
        assertEquals(0 to 3L, state.atoms.intVar[atom] to state.atoms.threshold[atom])
    }

    @Test
    fun `Linear should subscribe only to bound events`() {
        val lin = Linear(intArrayOf(1, 2, -1), intArrayOf(0, 1, 2), LinearOp.LE, 5)
        val watches = lin.propagatorProjection().initialIntEventWatches!!
        val pairs = watches.map { IntEvent.intVarOf(it) to IntEvent.kindOf(it) }.toSet()
        assertEquals(
            setOf(
                0 to IntEvent.LB_RAISED,
                0 to IntEvent.UB_LOWERED,
                1 to IntEvent.LB_RAISED,
                1 to IntEvent.UB_LOWERED,
                2 to IntEvent.LB_RAISED,
                2 to IntEvent.UB_LOWERED,
            ),
            pairs,
        )
    }
}
