package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BakedProblemTest {

    @Test
    fun `a conditioned root should intersect bounds holes and survivor sets with the source`() {
        val source = Problem(0, 1, arrayOf(holey()), emptyArray()).bake()
        val cases = listOf(
            Assumptions.None.withTightenedMin(
                0,
                1,
            ).withTightenedMax(0, 5) to holey().withMinAtLeast(1).withMaxAtMost(5),
            Assumptions.None.withIntHole(0, 3) to holey().excludeValue(3),
            Assumptions(
                intArrayOf(),
                booleanArrayOf(),
                intArrayOf(),
                longArrayOf(),
                DeducedRestrictions(
                    intSetKeys = intArrayOf(0),
                    intSetOffsets = intArrayOf(0, 4),
                    intSetValues = longArrayOf(-1, 2, 3, 7),
                ),
            ) to IntDomain(3, 3),
        )
        for ((assumptions, expected) in cases) {
            val root = source.conditionedRoot(assumptions, Cancellation.Never)
            assertEquals(expected, root.rootIntDomain(0))
            assertEquals(holey(), source.rootIntDomain(0))
        }
    }

    @Test
    fun `contradictory root deductions should produce an infeasible root`() {
        val source = Problem(0, 1, arrayOf(holey()), emptyArray()).bake()
        val root = source.conditionedRoot(Assumptions.None.withTightenedMin(0, 7), Cancellation.Never)
        assertIs<PropagationResult.Unsat>(root.baked)
        assertEquals(holey(), source.rootIntDomain(0))
    }

    @Test
    fun `cancelled root preparation should not publish a narrowed root`() {
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
    fun `the finite domains should hand back a copy rather than the owned array`() {
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
    fun `baking a column with an open side should be refused rather than boxed`() {
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

    private fun mixed(): Problem = Problem(
        numBoolVars = 1,
        numIntVars = 1,
        intDomains = arrayOf(IntDomain(0, 5)),
        factors = arrayOf(
            ReifiedRealLinear(
                aux = 0,
                vars = intArrayOf(),
                intCoeffs = doubleArrayOf(),
                realVars = intArrayOf(0),
                realCoeffs = doubleArrayOf(1.0),
                op = LinearOp.LE,
                bound = 2.0,
            ),
        ),
        numRealVars = 1,
        realLower = doubleArrayOf(0.0),
        realUpper = doubleArrayOf(3.0),
    )

    @Test
    fun `baked problem should copy the domains it is given`() {
        val handed = arrayOf(IntDomain(0, 5))
        val baked = BakedProblem(numBoolVars = 0, numIntVars = 1, intDomains = handed, factors = emptyList())

        handed[0] = IntDomain(9, 9)

        assertEquals(IntDomain(0, 5), baked.rootIntDomain(0))
    }

    @Test
    fun `bake should report folded root domains as declared domains`() {
        val baked = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 10)),
            factors = arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3)),
        ).bake()

        assertEquals(3L, baked.rootIntDomain(0).max)
        assertEquals(
            baked.rootIntDomain(0),
            baked.intDomainOrNull(0),
        )
    }

    @Test
    fun `bake should keep a declared hole`() {
        val baked = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 5).excludeValue(3)),
            factors = emptyArray(),
        ).bake()

        assertFalse(3L in baked.rootIntDomain(0))
        assertTrue(2L in baked.rootIntDomain(0) && 4L in baked.rootIntDomain(0))
    }

    @Test
    fun `withAppendedFactor should keep real columns`() {
        val appended = mixed().bake().withAppendedFactor(Clause(intArrayOf(Lit.make(0, true))))

        assertEquals(1, appended.numRealVars)
        assertEquals(0.0, appended.realLower[0])
        assertEquals(3.0, appended.realUpper[0])
    }

    @Test
    fun `withAppendedFactor should keep open integer sides`() {
        val open = Problem(
            numBoolVars = 1,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 5)),
            factors = arrayOf(Clause(intArrayOf(Lit.make(0, true)))),
            openIntHi = booleanArrayOf(true),
        )

        val appended = open.bake().withAppendedFactor(Clause(intArrayOf(Lit.make(0, true))))

        assertTrue(appended.intBounds.isOpenUpper(0))
    }
}
