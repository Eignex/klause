package com.eignex.klause.ir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceSemanticsTest {
    @Test
    fun `integer range overrides should replace defaults rather than intersect them`() {
        val declared = SourceSemantics(
            format = SourceFormat.FLATZINC,
            undeclaredIntegers = UndeclaredIntegerBounds(-1_000_000, 1_000_000),
        )

        val resolved = declared.resolve(
            SemanticOverrides(undeclaredIntegers = UndeclaredIntegerBounds(-2_000_000, 3_000_000)),
        )

        assertEquals(UndeclaredIntegerBounds(-2_000_000, 3_000_000), resolved.effective.undeclaredIntegers)
        assertEquals(UndeclaredIntegerBounds(-1_000_000, 1_000_000), resolved.declared.undeclaredIntegers)
    }

    @Test
    fun `integer range overrides should allow explicitly open endpoints`() {
        val declared = SourceSemantics(
            format = SourceFormat.FLATZINC,
            undeclaredIntegers = UndeclaredIntegerBounds(-1_000_000, 1_000_000),
        )

        val resolved = declared.resolve(SemanticOverrides(undeclaredIntegers = UndeclaredIntegerBounds()))

        assertEquals(UndeclaredIntegerBounds(), resolved.effective.undeclaredIntegers)
        assertTrue(resolved.hasOverrides)
    }

    @Test
    fun `tolerance overrides should retain decimal numeric authority`() {
        val declared = SourceSemantics(
            format = SourceFormat.MPS,
            numericAuthority = NumericAuthority.DECIMAL_LITERALS,
            feasibilityTolerance = 1e-6,
        )

        val resolved = declared.resolve(SemanticOverrides(feasibilityTolerance = 0.0))

        assertEquals(NumericAuthority.DECIMAL_LITERALS, resolved.effective.numericAuthority)
        assertFalse(resolved.isApproximate)
        assertTrue(declared.resolve().isApproximate)
    }

    @Test
    fun `continuous overrides should preserve other float defaults`() {
        val declared = SourceSemantics(
            format = SourceFormat.FLATZINC,
            floats = FloatSemantics.Grid(1024, 1_000_000),
            undeclaredReals = UndeclaredRealBounds(-10.0, 10.0),
        )

        val resolved = declared.resolve(SemanticOverrides(floats = FloatSemantics.Continuous))

        assertEquals(UndeclaredRealBounds(-10.0, 10.0), resolved.effective.undeclaredReals)
        assertFalse(resolved.isApproximate)
        assertTrue(declared.resolve().isApproximate)
    }

    @Test
    fun `resolution without replacements should retain the declared contract`() {
        val declared = SourceSemantics(SourceFormat.SMTLIB, NumericAuthority.RATIONAL_LITERALS)

        val resolved = declared.resolve()

        assertEquals(declared, resolved.effective)
        assertFalse(resolved.hasOverrides)
    }

    @Test
    fun `inapplicable replacements should be rejected even when their values look like defaults`() {
        val declared = SourceSemantics(SourceFormat.DIMACS)
        val replacements = listOf(
            SemanticOverrides(floats = FloatSemantics.Continuous),
            SemanticOverrides(undeclaredIntegers = UndeclaredIntegerBounds()),
            SemanticOverrides(undeclaredReals = UndeclaredRealBounds()),
            SemanticOverrides(feasibilityTolerance = 0.0),
            SemanticOverrides(pinAbsentOptionalValues = false),
        )

        for (replacement in replacements) {
            assertFailsWith<IllegalArgumentException> { declared.resolve(replacement) }
        }
    }

    @Test
    fun `invalid replacement tolerances should be rejected during resolution`() {
        val declared = SourceSemantics(SourceFormat.MPS, feasibilityTolerance = 1e-6)

        for (tolerance in listOf(-1.0, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> {
                declared.resolve(SemanticOverrides(feasibilityTolerance = tolerance))
            }
        }
    }

    @Test
    fun `range replacements should have the same meaning across applicable formats`() {
        val replacement = UndeclaredIntegerBounds(-20, 30)

        for (format in listOf(SourceFormat.FLATZINC, SourceFormat.SMTLIB, SourceFormat.MPS)) {
            val declared = SourceSemantics(format, undeclaredIntegers = UndeclaredIntegerBounds())
            val resolved = declared.resolve(SemanticOverrides(undeclaredIntegers = replacement))

            assertEquals(replacement, resolved.effective.undeclaredIntegers)
        }
    }

    @Test
    fun `grid approximation windows should require finite endpoints`() {
        for (bounds in listOf(UndeclaredRealBounds(), UndeclaredRealBounds(lower = 0.0))) {
            assertFailsWith<IllegalArgumentException> { FloatSemantics.Grid(8, 1000, bounds) }
        }
    }

    @Test
    fun `absent optional values should accept an explicit false replacement`() {
        val declared = SourceSemantics(SourceFormat.SCHEMA, pinAbsentOptionalValues = true)

        val resolved = declared.resolve(SemanticOverrides(pinAbsentOptionalValues = false))

        assertEquals(false, resolved.effective.pinAbsentOptionalValues)
        assertTrue(resolved.hasOverrides)
    }
}
