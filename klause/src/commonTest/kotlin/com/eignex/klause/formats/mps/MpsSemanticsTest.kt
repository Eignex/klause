package com.eignex.klause.formats.mps

import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.NumericAuthority
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MpsSemanticsTest {
    @Test
    fun `parsed source should declare decimal authority before lowering`() {
        val source = Mps.parse("ROWS\n N COST\nCOLUMNS\n X COST 0.1\nENDATA")

        val compiled = source.toProblem()

        assertEquals(SourceFormat.MPS, source.semantics.format)
        assertEquals(NumericAuthority.DECIMAL_LITERALS, source.semantics.numericAuthority)
        assertEquals(source.semantics, compiled.semantics.declared)
        assertSame(compiled.semantics, compiled.model.semantics)
        assertTrue(compiled.semantics.isApproximate)
    }

    @Test
    fun `inapplicable overrides should fail before lowering`() {
        val source = Mps.parse("ROWS\n N COST\nCOLUMNS\n X COST 1\nENDATA")

        assertFailsWith<IllegalArgumentException> {
            source.toProblem(semanticOverrides = SemanticOverrides(floats = FloatSemantics.Continuous))
        }
    }

    @Test
    fun `absolute and relative tolerance should independently control row acceptance`() {
        val source = Mps.parse(
            "ROWS\n N COST\n E R\nCOLUMNS\n X COST 0.1 R 1\nRHS\n RHS R 100\nENDATA",
        )
        val cases = listOf(
            SourceTolerance() to false,
            SourceTolerance(absolute = SemanticRational.ONE) to true,
            SourceTolerance(relative = SemanticRational.of(BIG_ONE, bigIntOf(100))) to true,
        )

        for ((policy, accepted) in cases) {
            val compiled = source.toProblem(semanticOverrides = SemanticOverrides(feasibilityTolerance = policy))
            assertEquals(accepted, compiled.withinTolerance(LongArray(0), doubleArrayOf(99.5)), "$policy")
            assertEquals(policy, compiled.semantics.effective.feasibilityTolerance)
            if (accepted) {
                val witness = compiled.sourceWitness(listOf(BigFraction.of(bigIntOf(199), bigIntOf(2))))
                assertFalse(witness.exact)
                assertEquals("199/20", witness.objective.toString())
            }
        }
    }

    @Test
    fun `zero tolerance should retain decimals and reject a rounded equality point`() {
        val source = Mps.parse("ROWS\n N COST\n E R\nCOLUMNS\n X R 3.0000000000000001\nRHS\n RHS R 1\nENDATA")
        val compiled = source.toProblem(semanticOverrides = SemanticOverrides(feasibilityTolerance = SourceTolerance()))

        assertTrue(compiled.sourceExact)
        assertFalse(compiled.semantics.isApproximate)
        assertFailsWith<MpsLoweringException> {
            compiled.sourceWitness(listOf(BigFraction.ofLong(3).reciprocal()))
        }
    }

    @Test
    fun `positive tolerance should apply even when lowering retains exact source numbers`() {
        val compiled = Mps.parse("ROWS\n N COST\n E R\nCOLUMNS\n X R 1\nRHS\n RHS R 1\nENDATA").toProblem()
        val value = BigFraction.ONE - BigFraction.of(BIG_ONE, bigIntOf(100_000_000))

        val witness = compiled.sourceWitness(listOf(value))

        assertTrue(compiled.sourceExact)
        assertFalse(witness.exact)
        assertTrue(compiled.withinTolerance(LongArray(0), doubleArrayOf(value.toDouble())))
    }

    @Test
    fun `exact point qualification should not depend on source projection equality`() {
        val compiled = Mps.parse("ROWS\n N COST\n E R\nCOLUMNS\n X R 0.1\nRHS\n RHS R 0.3\nENDATA").toProblem()

        val witness = compiled.sourceWitness(listOf(BigFraction.ofLong(3)))

        assertFalse(compiled.sourceExact)
        assertTrue(witness.exact)
    }

    @Test
    fun `indicator lowering should retain the caller tolerance`() {
        val source = Mps.parse(
            "ROWS\n N COST\n E R\nCOLUMNS\n X R 1\n I COST 0\nRHS\n RHS R 1\n" +
                "BOUNDS\n BV B I\nINDICATORS\n IF R I 1\nENDATA",
        )
        val policy = SourceTolerance(absolute = SemanticRational.ONE)
        val compiled = source.toProblem(semanticOverrides = SemanticOverrides(feasibilityTolerance = policy))

        val witness = compiled.sourceWitness(listOf(BigFraction.ZERO, BigFraction.ONE))

        assertEquals(policy, compiled.semantics.effective.feasibilityTolerance)
        assertFalse(witness.exact)
    }

    @Test
    fun `large tolerance should never relax integer integrality`() {
        val compiled = Mps.parse("ROWS\n N COST\nCOLUMNS\n X COST 0\nBOUNDS\n BV B X\nENDATA").toProblem(
            semanticOverrides = SemanticOverrides(feasibilityTolerance = SourceTolerance(absolute = SemanticRational.ONE)),
        )

        assertFailsWith<MpsLoweringException> {
            compiled.sourceWitness(listOf(BigFraction.ofLong(2).reciprocal()))
        }
    }

    @Test
    fun `float checking should preserve wide integer coordinates`() {
        val compiled = Mps.parse(
            "ROWS\n E R\nCOLUMNS\n X R 1\nRHS\n RHS R 9007199254740993\n" +
                "BOUNDS\n LI B X 0\n UI B X 9007199254740994\nENDATA",
        ).toProblem(semanticOverrides = SemanticOverrides(feasibilityTolerance = SourceTolerance()))

        assertTrue(compiled.withinTolerance(longArrayOf(9_007_199_254_740_993L), DoubleArray(0)))
        assertFalse(compiled.withinTolerance(longArrayOf(9_007_199_254_740_992L), DoubleArray(0)))
    }

    @Test
    fun `cancelled source checks should withhold an otherwise valid point`() {
        val compiled = Mps.parse("ROWS\n E R\nCOLUMNS\n X R 1\nRHS\n RHS R 1\nENDATA").toProblem()
        val stop = Cancellation { true }

        assertFalse(compiled.withinTolerance(LongArray(0), doubleArrayOf(1.0), stop))
        assertFailsWith<MpsLoweringException> { compiled.sourceWitness(listOf(BigFraction.ONE), stop) }
    }

    @Test
    fun `cancellation during source checking should withhold a partial witness`() {
        val compiled = Mps.parse("ROWS\n E R\nCOLUMNS\n X R 1\nRHS\n RHS R 1\nENDATA").toProblem()
        var polls = 0
        val stop = Cancellation { ++polls >= 4 }

        assertFailsWith<MpsLoweringException> { compiled.sourceWitness(listOf(BigFraction.ONE), stop) }
    }

    @Test
    fun `cancellation in row activity should use original decimal coefficients`() {
        val compiled = Mps.parse(
            "ROWS\n E R\nCOLUMNS\n X R 10000000000000001\n Y R -10000000000000000\nRHS\n RHS R 0\nENDATA",
        ).toProblem(semanticOverrides = SemanticOverrides(feasibilityTolerance = SourceTolerance()))

        assertFalse(compiled.withinTolerance(LongArray(0), doubleArrayOf(1.0, 1.0)))
        assertTrue(compiled.withinTolerance(LongArray(0), doubleArrayOf(0.0, 0.0)))
    }
}
