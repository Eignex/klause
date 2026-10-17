package com.eignex.klause.formats.mps

import com.eignex.klause.ir.BoundOverride
import com.eignex.klause.ir.BoundsOverride
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.NumericAuthority
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.ir.UndeclaredIntegerBounds
import com.eignex.klause.ir.UndeclaredRealBounds
import com.eignex.klause.ir.linearRows
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MpsSemanticsTest {
    @Test
    fun `unranged integer replacements should preserve unspecified defaults and declared metadata`() {
        val source = Mps.parse(
            "ROWS\n N COST\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 1\n M1 'MARKER' 'INTEND'\nENDATA",
        )
        val compiled = source.toProblem(semanticOverrides = SemanticOverrides(
            undeclaredIntegers = BoundsOverride(upper = BoundOverride.Value(3L)),
        ))

        assertEquals(UndeclaredIntegerBounds(0, 1), compiled.semantics.declared.undeclaredIntegers)
        assertEquals(UndeclaredIntegerBounds(0, 3), compiled.semantics.effective.undeclaredIntegers)
        assertEquals(0L, compiled.model.intBounds.lower(0))
        assertEquals(3L, compiled.model.intBounds.upper(0))
        assertEquals(BigFraction.ofLong(3), compiled.sourceWitness(listOf(BigFraction.ofLong(3))).objective)
        assertFailsWith<MpsLoweringException> { source.toProblem().sourceWitness(listOf(BigFraction.ofLong(3))) }
    }

    @Test
    fun `every explicit source range should retain its complete range under caller defaults`() {
        val ranges = listOf(
            "LO B X 0" to listOf(100L to true, -1L to false),
            "UP B X 100" to listOf(100L to true, 101L to false),
            "UP B X -100" to listOf(-101L to true, 0L to false),
            "FX B X 100" to listOf(100L to true, 99L to false),
            "FR B X" to listOf(-100L to true, 100L to true),
            "MI B X" to listOf(-100L to true, 100L to true),
            "PL B X" to listOf(100L to true, -1L to false),
            "BV B X" to listOf(1L to true, 2L to false),
            "LI B X 100" to listOf(100L to true, 99L to false),
            "UI B X 100" to listOf(100L to true, 101L to false),
        )
        val overrides = SemanticOverrides(
            undeclaredIntegers = BoundsOverride(BoundOverride.Value(-10L), BoundOverride.Value(10L)),
            undeclaredReals = BoundsOverride(
                BoundOverride.Value(SemanticRational.ofLong(-10)), BoundOverride.Value(SemanticRational.ofLong(10)),
            ),
            feasibilityTolerance = SourceTolerance(),
        )
        for (integer in listOf(false, true)) {
            val columns = if (integer) " M0 'MARKER' 'INTORG'\n X COST 1\n M1 'MARKER' 'INTEND'" else " X COST 1"
            for ((range, points) in ranges) {
                val compiled = Mps.parse("ROWS\n N COST\nCOLUMNS\n$columns\nBOUNDS\n $range\nENDATA")
                    .toProblem(semanticOverrides = overrides)
                for ((point, accepted) in points) {
                    val values = listOf(BigFraction.ofLong(point))
                    if (accepted) compiled.sourceWitness(values)
                    else assertFailsWith<MpsLoweringException>(range) { compiled.sourceWitness(values) }
                }
            }
        }
    }

    @Test
    fun `opening unranged integer defaults should preserve both open sides without a finite window`() {
        val source = Mps.parse(
            "ROWS\n N COST\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 1\n M1 'MARKER' 'INTEND'\nENDATA",
        )
        val overrides = SemanticOverrides(undeclaredIntegers = BoundsOverride(BoundOverride.Open, BoundOverride.Open))

        val compiled = source.toProblem(semanticOverrides = overrides)

        assertTrue(compiled.model.intBounds.isOpenLower(0))
        assertTrue(compiled.model.intBounds.isOpenUpper(0))
        for (value in listOf(Long.MIN_VALUE, Long.MAX_VALUE)) {
            compiled.sourceWitness(listOf(BigFraction.ofLong(value)))
        }
    }

    @Test
    fun `finite caller integer extrema should remain finite in lowering and source validation`() {
        val source = Mps.parse(
            "ROWS\n N COST\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 1\n M1 'MARKER' 'INTEND'\nENDATA",
        )
        val overrides = SemanticOverrides(
            undeclaredIntegers = BoundsOverride(
                BoundOverride.Value(Long.MIN_VALUE), BoundOverride.Value(Long.MAX_VALUE),
            ),
            feasibilityTolerance = SourceTolerance(),
        )

        val compiled = source.toProblem(semanticOverrides = overrides)

        assertFalse(compiled.model.intBounds.isOpenLower(0))
        assertFalse(compiled.model.intBounds.isOpenUpper(0))
        assertEquals(Long.MIN_VALUE, compiled.model.intBounds.lower(0))
        assertEquals(Long.MAX_VALUE, compiled.model.intBounds.upper(0))
        compiled.sourceWitness(listOf(BigFraction.ofLong(Long.MIN_VALUE)))
        compiled.sourceWitness(listOf(BigFraction.ofLong(Long.MAX_VALUE)))
        assertFailsWith<MpsLoweringException> {
            compiled.sourceWitness(listOf(BigFraction.ofLong(Long.MAX_VALUE) + BigFraction.ONE))
        }
    }

    @Test
    fun `rational caller real bounds should govern exact source rows through both loading entries`() {
        val source = Mps.parse("ROWS\n L R\nCOLUMNS\n X R 0\nENDATA")
        val third = SemanticRational.of(BIG_ONE, bigIntOf(3))
        val overrides = SemanticOverrides(
            undeclaredReals = BoundsOverride(BoundOverride.Value(third), BoundOverride.Value(third)),
            feasibilityTolerance = SourceTolerance(),
        )

        val compiled = listOf(source.toProblem(semanticOverrides = overrides),
            source.toExactSourceProblem(semanticOverrides = overrides))

        for (model in compiled) {
            val value = BigFraction.ofLong(3).reciprocal()
            model.sourceWitness(listOf(value))
            assertTrue(model.sourceExact)
            assertTrue(model.model.factors.flatMap { it.linearRows }.all { row ->
                val constants = row.constants as IntegralConstants
                val activity = (0 until row.size).fold(BigFraction.ZERO) { sum, index ->
                    sum + BigFraction.of(constants.exactCoeff(index), BIG_ONE) * value
                }
                activity == BigFraction.of(constants.exactBound, BIG_ONE)
            })
            assertFailsWith<MpsLoweringException> {
                model.sourceWitness(listOf(checkNotNull(BigFraction.ofDouble(1.0 / 3))))
            }
        }
    }

    @Test
    fun `caller real endpoints beyond the text infinity convention should remain finite`() {
        val source = Mps.parse("ROWS\n N COST\nCOLUMNS\n X COST 1\nENDATA")
        val lower = SemanticRational.of(parseBigInt("1000000000000000000000000000000"))
        val overrides = SemanticOverrides(
            undeclaredReals = BoundsOverride(BoundOverride.Value(lower), BoundOverride.Open),
            feasibilityTolerance = SourceTolerance(),
        )

        val compiled = source.toProblem(semanticOverrides = overrides)

        val value = BigFraction.of(lower.numerator, lower.denominator)
        compiled.sourceWitness(listOf(value))
        assertTrue(compiled.sourceExact)
        assertFailsWith<MpsLoweringException> { compiled.sourceWitness(listOf(value - BigFraction.ONE)) }
    }

    @Test
    fun `nonfinite binary64 bound projections should preserve finite rational source authority`() {
        val source = Mps.parse("ROWS\n L R\nCOLUMNS\n X R 0\nENDATA")
        val endpoint = SemanticRational.of(parseBigInt("1" + "0".repeat(400)))
        val overrides = SemanticOverrides(
            undeclaredReals = BoundsOverride(upper = BoundOverride.Value(endpoint)),
            feasibilityTolerance = SourceTolerance(),
        )

        val compiled = source.toProblem(semanticOverrides = overrides)

        val value = BigFraction.of(endpoint.numerator, endpoint.denominator)
        compiled.sourceWitness(listOf(value))
        assertTrue(compiled.sourceExact)
        assertFailsWith<MpsLoweringException> { compiled.sourceWitness(listOf(value + BigFraction.ONE)) }
    }

    @Test
    fun `programmatic declaration ranges should remain explicit under caller defaults`() {
        val source = Mps.parse("ROWS\n N COST\nCOLUMNS\n X COST 1\nENDATA")
        val supplied = source.copy(variables = listOf(MpsVar("X", false, null, null)))
        val overrides = SemanticOverrides(
            undeclaredReals = BoundsOverride(upper = BoundOverride.Value(SemanticRational.ONE)),
        )

        val compiled = supplied.toProblem(semanticOverrides = overrides)

        compiled.sourceWitness(listOf(BigFraction.ofLong(-100)))
        compiled.sourceWitness(listOf(BigFraction.ofLong(100)))
    }

    @Test
    fun `programmatic finite real bounds should not use the text infinity convention`() {
        val source = Mps.parse("ROWS\n N COST\nCOLUMNS\n X COST 1\nENDATA")
            .copy(variables = listOf(MpsVar("X", false, 1e21, null)))

        val compiled = source.toProblem(semanticOverrides = SemanticOverrides(feasibilityTolerance = SourceTolerance()))

        val endpoint = checkNotNull(BigFraction.ofDouble(1e21))
        compiled.sourceWitness(listOf(endpoint))
        assertFailsWith<MpsLoweringException> { compiled.sourceWitness(listOf(BigFraction.ZERO)) }
    }

    @Test
    fun `declared range defaults should apply to parsed unranged declarations`() {
        val source = Mps.parse("ROWS\n N COST\nCOLUMNS\n X COST 1\nENDATA").copy(
            semantics = mpsSemantics.copy(undeclaredReals = UndeclaredRealBounds(lower = SemanticRational.ONE)),
        )

        val compiled = source.toProblem()

        compiled.sourceWitness(listOf(BigFraction.ONE))
        assertFailsWith<MpsLoweringException> { compiled.sourceWitness(listOf(BigFraction.ZERO)) }
        assertSame(source.semantics, compiled.semantics.declared)
    }

    @Test
    fun `copying a parsed model should preserve unranged declaration origins`() {
        val source = Mps.parse("ROWS\n N COST\nCOLUMNS\n X COST 1\nENDATA")
        val overrides = SemanticOverrides(undeclaredReals = BoundsOverride(lower = BoundOverride.Open))

        val compiled = source.copy().toProblem(semanticOverrides = overrides)

        compiled.sourceWitness(listOf(BigFraction.MINUS_ONE))
        assertEquals(UndeclaredRealBounds(lower = SemanticRational.ZERO), compiled.semantics.declared.undeclaredReals)
        assertFailsWith<MpsLoweringException> { source.toProblem().sourceWitness(listOf(BigFraction.MINUS_ONE)) }
    }

    @Test
    fun `indicator lowering should use effective unranged declarations and retain source guards`() {
        val source = Mps.parse(
            "ROWS\n N COST\n G R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 0\n" +
                " M1 'MARKER' 'INTEND'\n X COST 1 R 1\nINDICATORS\n IF R I 1\nENDATA",
        )
        val overrides = SemanticOverrides(
            undeclaredIntegers = BoundsOverride(upper = BoundOverride.Value(2L)),
            undeclaredReals = BoundsOverride(lower = BoundOverride.Open),
        )

        val compiled = source.toProblem(semanticOverrides = overrides)

        assertEquals(BigFraction.MINUS_ONE,
            compiled.sourceWitness(listOf(BigFraction.ofLong(2), BigFraction.MINUS_ONE)).objective)
        assertFailsWith<MpsLoweringException> {
            compiled.sourceWitness(listOf(BigFraction.ONE, BigFraction.MINUS_ONE))
        }
    }

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
    fun `unsupported source policies should be rejected rather than retained as ignored metadata`() {
        val source = Mps.parse("ROWS\n N COST\nCOLUMNS\n X COST 1\nENDATA")
        val policies = listOf(
            mpsSemantics.copy(format = SourceFormat.SMTLIB),
            mpsSemantics.copy(numericAuthority = NumericAuthority.RATIONAL_LITERALS),
            mpsSemantics.copy(feasibilityTolerance = null),
            mpsSemantics.copy(floats = FloatSemantics.Continuous),
            mpsSemantics.copy(undeclaredIntegers = null),
            mpsSemantics.copy(undeclaredReals = null),
            mpsSemantics.copy(pinAbsentOptionalValues = true),
        )

        for (policy in policies) {
            assertFailsWith<IllegalArgumentException> { source.copy(semantics = policy) }
        }
    }

    @Test
    fun `relative bound slack should use the source bound magnitude without an absolute floor`() {
        val source = Mps.parse("ROWS\n N COST\nCOLUMNS\n X COST 0\nBOUNDS\n LO B X 0.1\nENDATA")
        val relative = SourceTolerance(relative = SemanticRational.of(BIG_ONE, bigIntOf(10)))
        val compiled = source.toProblem(semanticOverrides = SemanticOverrides(feasibilityTolerance = relative))

        assertTrue(compiled.withinTolerance(LongArray(0), doubleArrayOf(0.095)))
        assertFalse(compiled.withinTolerance(LongArray(0), doubleArrayOf(0.085)))
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
            val values = listOf(assertNotNull(BigFraction.ofDouble(99.5)))
            assertEquals(policy, compiled.semantics.effective.feasibilityTolerance)
            if (accepted) {
                val witness = compiled.sourceWitness(values)
                assertFalse(witness.exact, "$policy")
                assertEquals("199/20", witness.objective.toString())
            } else {
                assertFailsWith<MpsLoweringException>("$policy") { compiled.sourceWitness(values) }
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
        assertFalse(compiled.sourceWitness(listOf(assertNotNull(BigFraction.ofDouble(value.toDouble())))).exact)
        assertTrue(compiled.withinTolerance(longArrayOf(1), DoubleArray(0)))
    }

    @Test
    fun `exact point qualification should not depend on source projection equality`() {
        val compiled = Mps.parse(
            "ROWS\n N COST\n E R\nCOLUMNS\n X COST 0.10000000000000000001 R 3\nRHS\n RHS R 1\nENDATA",
        ).toProblem()

        val witness = compiled.sourceWitness(listOf(BigFraction.ofLong(3).reciprocal()))

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
            semanticOverrides = SemanticOverrides(
                feasibilityTolerance = SourceTolerance(absolute = SemanticRational.ONE),
            ),
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
