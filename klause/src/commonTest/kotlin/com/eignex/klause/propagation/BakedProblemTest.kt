package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class BakedProblemTest {

    @Test
    fun `conditioned root intersects bounds holes and survivor sets with the source`() {
        val source = Problem(0, 1, arrayOf(holey()), emptyArray()).bake()
        val cases = listOf(
            Assumptions.None.withTightenedMin(
                0,
                1,
            ).withTightenedMax(0, 5) to holey().withMinAtLeast(1).withMaxAtMost(5),
            Assumptions.None.withIntHole(0, 3) to holey().excludeValue(3),
            Assumptions(
                intArrayOf(), booleanArrayOf(), intArrayOf(), longArrayOf(),
                DeducedRestrictions(
                intSetKeys = intArrayOf(0),
                intSetOffsets = intArrayOf(0, 4),
                intSetValues = longArrayOf(-1, 2, 3, 7),
            )
            ) to IntDomain(3, 3),
        )
        for ((assumptions, expected) in cases) {
            val root = source.conditionedRoot(assumptions, Cancellation.Never)
            assertEquals(expected, root.rootIntDomain(0))
            assertEquals(holey(), source.rootIntDomain(0))
        }
    }

    @Test
    fun `contradictory root deductions produce an infeasible root`() {
        val source = Problem(0, 1, arrayOf(holey()), emptyArray()).bake()
        val root = source.conditionedRoot(Assumptions.None.withTightenedMin(0, 7), Cancellation.Never)
        assertIs<PropagationResult.Unsat>(root.baked)
        assertEquals(holey(), source.rootIntDomain(0))
    }

    @Test
    fun `cancelled root preparation does not publish a narrowed root`() {
        val source = Problem(0, 1, arrayOf(holey()), emptyArray()).bake()
        for (stop in listOf(1, 2)) {
            var polls = 0
            assertFailsWith<CancellationException> {
                source.conditionedRoot(Assumptions.None.withTightenedMin(0, 3), Cancellation { ++polls >= stop })
            }
            assertEquals(holey(), source.rootIntDomain(0))
        }
    }

    private fun holey(): IntDomain = IntDomain(0, 6).excludeValue(2).excludeValue(4)

    @Test
    fun `bake folds the root deductions into the domains`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 10)),
            factors = listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3)),
        )

        assertEquals(3, problem.bake().rootIntDomain(0).max, "bake carries the x <= 3 tightening")
    }

    @Test
    fun `the finite surface reports the folded domain of each column`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 10), IntDomain(0, 10)),
            factors = listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3)),
        )

        val baked = problem.bake()

        assertEquals(IntDomain(0, 3), baked.rootIntDomain(0))
        assertEquals(IntDomain(0, 10), baked.rootIntDomain(1))
    }

    @Test
    fun `a non-contiguous declaration reaches the finite projection with its holes`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(holey()),
            factors = arrayOf<Factor>(),
        )

        assertEquals(holey(), problem.bake().rootIntDomain(0))
    }

    @Test
    fun `the finite domains hand back a copy rather than the array the fold owns`() {
        val baked = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 10)),
            factors = arrayOf<Factor>(),
        ).bake()

        baked.rootIntDomains()[0] = IntDomain(7, 7)

        assertEquals(IntDomain(0, 10), baked.rootIntDomain(0))
    }

    @Test
    fun `baking a model that declares bounds alone materializes its range`() {
        val problem = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(2), longArrayOf(9), null, null),
            factors = emptyArray(),
        )

        assertEquals(IntDomain(2, 9), problem.bake().rootIntDomain(0))
    }

    @Test
    fun `baking a column with an open side is refused rather than boxed`() {
        val problem = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(
                longArrayOf(0),
                longArrayOf(0),
                null,
                Bits(1).also { it.set(0) },
            ),
            factors = emptyArray(),
        )

        assertFailsWith<IllegalArgumentException> { problem.bake() }
    }
}
