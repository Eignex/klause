package com.eignex.klause.lowering

import com.eignex.klause.ir.LinearSourceIndicator
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.LinearSourceRow
import com.eignex.klause.ir.LinearSourceTerm
import com.eignex.klause.ir.LinearSourceVariable
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LinearSourceIntegersTest {
    @Test
    fun `chained affine definitions should derive integer bounds independently of row order`() {
        val source = LinearSourceProblem(
            listOf(
                LinearSourceVariable("x", true, SemanticRational.ZERO, SemanticRational.ONE),
                LinearSourceVariable("y", false), LinearSourceVariable("z", false),
            ),
            listOf(
                LinearSourceRow(listOf(LinearSourceTerm(2, SemanticRational.ONE),
                    LinearSourceTerm(1, SemanticRational.ofLong(-2))), SemanticRational.ONE, SemanticRational.ONE),
                LinearSourceRow(listOf(LinearSourceTerm(1, SemanticRational.ONE),
                    LinearSourceTerm(0, SemanticRational.ofLong(-3))),
                    SemanticRational.ofLong(2), SemanticRational.ofLong(2)),
            ),
        )

        val bounds = assertNotNull(source.integralRealBounds(Cancellation.Never))

        assertEquals(BigFraction.ofLong(2) to BigFraction.ofLong(5), bounds[1])
        assertEquals(BigFraction.ofLong(5) to BigFraction.ofLong(11), bounds[2])
    }

    @Test
    fun `a fractional affine definition should retain its continuous column`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", true, SemanticRational.ZERO, SemanticRational.ONE),
                LinearSourceVariable("z", false)),
            listOf(LinearSourceRow(listOf(LinearSourceTerm(1, SemanticRational.ofLong(2)),
                LinearSourceTerm(0, SemanticRational.ofLong(-1))), SemanticRational.ZERO, SemanticRational.ZERO)),
        )

        val bounds = assertNotNull(source.integralRealBounds(Cancellation.Never))

        assertNull(bounds[1])
    }

    @Test
    fun `a guarded affine definition should retain its continuous column`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", true, SemanticRational.ZERO, SemanticRational.ONE),
                LinearSourceVariable("z", false)),
            listOf(LinearSourceRow(listOf(LinearSourceTerm(1, SemanticRational.ONE),
                LinearSourceTerm(0, SemanticRational.ofLong(-3))),
                SemanticRational.ofLong(2), SemanticRational.ofLong(2), LinearSourceIndicator(0, true))),
        )

        val bounds = assertNotNull(source.integralRealBounds(Cancellation.Never))

        assertNull(bounds[1])
    }

    @Test
    fun `an affine range outside signed word storage should retain its continuous column`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", true, SemanticRational.ZERO, SemanticRational.ofLong(Long.MAX_VALUE)),
                LinearSourceVariable("z", false)),
            listOf(LinearSourceRow(listOf(LinearSourceTerm(1, SemanticRational.ONE),
                LinearSourceTerm(0, SemanticRational.ofLong(-2))), SemanticRational.ZERO, SemanticRational.ZERO)),
        )

        val bounds = assertNotNull(source.integralRealBounds(Cancellation.Never))

        assertNull(bounds[1])
    }

    @Test
    fun `integral affine bounds should retain integers beyond binary64 precision`() {
        val wide = SemanticRational.of(bigIntOf(10_000_000_000_000_001L), BIG_ONE)
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", true, SemanticRational.ZERO, SemanticRational.ONE),
                LinearSourceVariable("z", false)),
            listOf(LinearSourceRow(listOf(LinearSourceTerm(1, SemanticRational.ONE),
                LinearSourceTerm(0, SemanticRational.ofLong(-3))), wide, wide)),
        )

        val bounds = assertNotNull(source.integralRealBounds(Cancellation.Never))

        assertEquals(BigFraction.ofLong(10_000_000_000_000_001L) to BigFraction.ofLong(10_000_000_000_000_004L),
            bounds[1])
    }
}
