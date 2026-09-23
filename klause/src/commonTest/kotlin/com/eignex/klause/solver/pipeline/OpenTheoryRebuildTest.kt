package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Lit
import com.eignex.klause.presolve.RebuildStep
import com.eignex.klause.presolve.SourceRebuilds
import com.eignex.klause.presolve.asSampleLift
import com.eignex.klause.solver.Sample
import com.eignex.klause.theory.qflra.ExactLiraAssignment
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The open lane's half of a source reconstruction. The steps are the same records the finite lane
 * evaluates; what is checked here is that a witness answering by accessor can carry them, since that is
 * the difference that kept column-eliminating passes off the source lane.
 */
class OpenTheoryRebuildTest {

    private fun pos(v: Int) = Lit.make(v, true)

    /** A difference-route witness, the one open witness backed by a [Sample]. */
    private fun witness(bools: BooleanArray, ints: LongArray = longArrayOf()) =
        OpenTheoryAssignment.Difference(Sample(bools, ints))

    @Test
    fun `a rebuild recovers an eliminated column of an open witness`() {
        val rebuild = SourceRebuilds(listOf(RebuildStep.CopyLiteral(variable = 0, source = pos(1))))

        val lifted = rebuild.lift(witness(booleanArrayOf(false, true)), numBoolVars = 2, numIntVars = 1)

        assertTrue(lifted.boolValue(0), "the eliminated column takes its representative's value")
        assertTrue(lifted.boolValue(1))
    }

    @Test
    fun `a rebuild leaves the numeric columns to the route underneath`() {
        // Only the Booleans are the rebuild's. An integer column keeps whatever width its route answers
        // in, which is the reason the witness is wrapped rather than rewritten.
        val rebuild = SourceRebuilds(listOf(RebuildStep.CopyLiteral(variable = 0, source = pos(1))))
        val base = witness(booleanArrayOf(false, true), longArrayOf(42))

        val lifted = assertIs<OpenTheoryAssignment.Rebuilt>(rebuild.lift(base, numBoolVars = 2, numIntVars = 1))

        assertEquals("42", lifted.intValue(0))
        assertSame(base, lifted.base)
    }

    @Test
    fun `a rebuild that recovers nothing hands back the same witness`() {
        val base = witness(booleanArrayOf(true))

        assertSame(base, SourceRebuilds.NONE.lift(base, numBoolVars = 1, numIntVars = 0))
    }

    @Test
    fun `an open rebuild agrees with the finite one on the same steps`() {
        // The point of stating the reconstruction as data: one record, two witness types, one answer.
        val rebuild = SourceRebuilds(
            listOf(
                RebuildStep.SatisfyClauses(variable = 0, clauses = listOf(intArrayOf(pos(0), pos(1)))),
                RebuildStep.CopyLiteral(variable = 2, source = pos(0)),
            ),
        )
        val solved = Sample(booleanArrayOf(false, false, false), longArrayOf())

        val finite = checkNotNull(rebuild.asSampleLift())(solved)
        val open = rebuild.lift(witness(solved.bools.copyOf()), numBoolVars = 3, numIntVars = 0)

        for (v in 0 until 3) {
            assertEquals(finite.bools[v], open.boolValue(v), "lanes disagree on column $v")
        }
        assertTrue(finite.bools[0], "the eliminated column satisfies its clause")
        assertFalse(solved.bools[0], "neither lane writes through the witness it was given")
    }

    @Test
    fun `an eliminated integer column is recovered at arbitrary precision`() {
        // The value an open route answers in does not fit a Long, which is the whole reason the step is a
        // record the lane evaluates rather than a lift closed over one width.
        val huge = BigInteger.fromLong(2L).pow(70)
        val rebuild = SourceRebuilds(
            listOf(RebuildStep.AffineValue(0, 1L, intArrayOf(1), longArrayOf(2L), divisor = 1L)),
        )
        val base = OpenTheoryAssignment.ExactLira(
            ExactLiraAssignment(
                bools = booleanArrayOf(),
                ints = arrayOf(BigInteger.ZERO, huge),
                reals = emptyList(),
            ),
        )

        val lifted = rebuild.lift(base, numBoolVars = 0, numIntVars = 2)

        assertEquals((huge * BigInteger.fromLong(2L) + BigInteger.ONE).toString(), lifted.intValue(0))
        assertEquals(huge.toString(), lifted.intValue(1), "a column no step names is carried through")
    }

    @Test
    fun `a boolean-only rebuild leaves integer columns to the route underneath`() {
        val rebuild = SourceRebuilds(listOf(RebuildStep.CopyLiteral(variable = 0, source = pos(1))))
        val base = witness(booleanArrayOf(false, true), longArrayOf(42))

        val lifted = assertIs<OpenTheoryAssignment.Rebuilt>(rebuild.lift(base, numBoolVars = 2, numIntVars = 1))

        assertEquals(null, lifted.ints, "no value is materialized when no step recovers one")
        assertEquals("42", lifted.intValue(0))
    }
}
