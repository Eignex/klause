package com.eignex.klause.ir

import com.eignex.klause.util.bigIntOf
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
            SemanticOverrides(
                undeclaredIntegers = BoundsOverride(BoundOverride.Value(-2_000_000), BoundOverride.Value(3_000_000)),
            ),
        )

        assertEquals(UndeclaredIntegerBounds(-2_000_000, 3_000_000), resolved.effective.undeclaredIntegers)
        assertEquals(UndeclaredIntegerBounds(-1_000_000, 1_000_000), resolved.declared.undeclaredIntegers)
    }

    @Test
    fun `single endpoint replacements should preserve the other declared endpoint`() {
        val declared = SourceSemantics(
            format = SourceFormat.FLATZINC,
            undeclaredIntegers = UndeclaredIntegerBounds(-10, 10),
        )

        val resolved = declared.resolve(
            SemanticOverrides(undeclaredIntegers = BoundsOverride(lower = BoundOverride.Value(-20))),
        )

        assertEquals(UndeclaredIntegerBounds(-20, 10), resolved.effective.undeclaredIntegers)
    }

    @Test
    fun `real endpoint replacements should retain exact rationals and the other endpoint`() {
        val lower = SemanticRational.of(bigIntOf(1), bigIntOf(10))
        val declared = SourceSemantics(
            format = SourceFormat.SMTLIB,
            undeclaredReals = UndeclaredRealBounds(upper = SemanticRational.ONE),
        )

        val resolved = declared.resolve(
            SemanticOverrides(undeclaredReals = BoundsOverride(lower = BoundOverride.Value(lower))),
        )

        assertEquals(UndeclaredRealBounds(lower, SemanticRational.ONE), resolved.effective.undeclaredReals)
    }

    @Test
    fun `crossed endpoint replacements should be rejected`() {
        val declared = SourceSemantics(
            format = SourceFormat.FLATZINC,
            undeclaredIntegers = UndeclaredIntegerBounds(-10, 10),
        )

        assertFailsWith<IllegalArgumentException> {
            declared.resolve(SemanticOverrides(undeclaredIntegers = BoundsOverride(lower = BoundOverride.Value(20))))
        }
    }

    @Test
    fun `integer range overrides should allow explicitly open endpoints`() {
        val declared = SourceSemantics(
            format = SourceFormat.FLATZINC,
            undeclaredIntegers = UndeclaredIntegerBounds(-1_000_000, 1_000_000),
        )

        val resolved = declared.resolve(
            SemanticOverrides(undeclaredIntegers = BoundsOverride(BoundOverride.Open, BoundOverride.Open)),
        )

        assertEquals(UndeclaredIntegerBounds(), resolved.effective.undeclaredIntegers)
        assertTrue(resolved.hasOverrides)
    }

    @Test
    fun `tolerance overrides should retain decimal numeric authority`() {
        val declared = SourceSemantics(
            format = SourceFormat.MPS,
            numericAuthority = NumericAuthority.DECIMAL_LITERALS,
            feasibilityTolerance = SourceTolerance(
                SemanticRational.of(bigIntOf(1), bigIntOf(10_000_000)),
                SemanticRational.of(bigIntOf(1), bigIntOf(10_000_000)),
            ),
        )

        val resolved = declared.resolve(SemanticOverrides(feasibilityTolerance = SourceTolerance()))

        assertEquals(NumericAuthority.DECIMAL_LITERALS, resolved.effective.numericAuthority)
        assertFalse(resolved.isApproximate)
        assertTrue(declared.resolve().isApproximate)
    }

    @Test
    fun `continuous overrides should preserve other float defaults`() {
        val declared = SourceSemantics(
            format = SourceFormat.FLATZINC,
            floats = FloatSemantics.Grid(1024, 1_000_000),
            undeclaredReals = UndeclaredRealBounds(SemanticRational.ofLong(-10), SemanticRational.ofLong(10)),
        )

        val resolved = declared.resolve(SemanticOverrides(floats = FloatSemantics.Continuous))

        assertEquals(
            UndeclaredRealBounds(SemanticRational.ofLong(-10), SemanticRational.ofLong(10)),
            resolved.effective.undeclaredReals,
        )
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
            SemanticOverrides(undeclaredIntegers = BoundsOverride()),
            SemanticOverrides(undeclaredReals = BoundsOverride()),
            SemanticOverrides(feasibilityTolerance = SourceTolerance()),
            SemanticOverrides(pinAbsentOptionalValues = false),
        )

        for (replacement in replacements) {
            assertFailsWith<IllegalArgumentException> { declared.resolve(replacement) }
        }
    }

    @Test
    fun `tolerance components should reject negative slack`() {
        for (tolerance in listOf(SemanticRational.ofLong(-1), SemanticRational.ofLong(-2))) {
            assertFailsWith<IllegalArgumentException> { SourceTolerance(absolute = tolerance) }
            assertFailsWith<IllegalArgumentException> { SourceTolerance(relative = tolerance) }
        }
    }

    @Test
    fun `either tolerance component should qualify the source as approximate`() {
        val slack = SemanticRational.of(bigIntOf(1), bigIntOf(10_000_000))

        for (tolerance in listOf(SourceTolerance(absolute = slack), SourceTolerance(relative = slack))) {
            val declared = SourceSemantics(SourceFormat.MPS, feasibilityTolerance = tolerance)

            assertTrue(declared.resolve().isApproximate)
        }
    }

    @Test
    fun `range replacements should have the same meaning across applicable formats`() {
        val replacement = UndeclaredIntegerBounds(-20, 30)

        for (format in listOf(SourceFormat.FLATZINC, SourceFormat.SMTLIB, SourceFormat.MPS)) {
            val declared = SourceSemantics(format, undeclaredIntegers = UndeclaredIntegerBounds())
            val resolved = declared.resolve(
                SemanticOverrides(
                    undeclaredIntegers = BoundsOverride(BoundOverride.Value(-20), BoundOverride.Value(30)),
                ),
            )

            assertEquals(replacement, resolved.effective.undeclaredIntegers)
        }
    }

    @Test
    fun `grid approximation windows should require finite endpoints`() {
        for (bounds in listOf(UndeclaredRealBounds(), UndeclaredRealBounds(lower = SemanticRational.ZERO))) {
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
