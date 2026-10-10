package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Lit
import com.eignex.klause.presolve.RebuildStep
import com.eignex.klause.presolve.SourceRebuilds
import com.eignex.klause.solver.Sample
import com.eignex.klause.theory.qflra.ExactLiraAssignment
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.shl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
    fun `an eliminated integer column is recovered at arbitrary precision`() {
        // The value an open route answers in does not fit a Long, which is the whole reason the step is a
        // record the lane evaluates rather than a lift closed over one width.
        val huge = BIG_ONE shl 70
        val rebuild = SourceRebuilds(
            listOf(RebuildStep.AffineValue(0, 1L, intArrayOf(1), longArrayOf(2L), divisor = 1L)),
        )
        val base = OpenTheoryAssignment.ExactLira(
            ExactLiraAssignment(
                bools = booleanArrayOf(),
                ints = arrayOf(BIG_ZERO, huge),
                reals = emptyList(),
            ),
        )

        val lifted = rebuild.lift(base, numBoolVars = 0, numIntVars = 2)

        assertEquals((huge * bigIntOf(2L) + BIG_ONE).toString(), lifted.intValue(0))
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
