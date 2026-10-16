package com.eignex.klause.formats.opb

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.ir.BoundOverride
import com.eignex.klause.ir.BoundsOverride
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SourceTolerance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class OpbSemanticsTest {
    @Test
    fun `OPB lowering should retain exact declared policy on its source wrapper`() {
        val document = Opb.parse("+1 x1 >= 1 ;")

        val source = document.toProblem()

        assertEquals(opbSemantics, source.semantics.declared)
        assertEquals(opbSemantics, source.semantics.effective)
        assertSame(source.problem.semantics, source.semantics)
        assertFalse(source.semantics.isApproximate)
    }

    @Test
    fun `OPB should reject semantic replacements that do not apply to its declarations`() {
        val document = Opb.parse("min: 9223372036854775808 x1 ;")
        val replacements = listOf(
            SemanticOverrides(floats = FloatSemantics.Continuous),
            SemanticOverrides(undeclaredIntegers = BoundsOverride(lower = BoundOverride.Open)),
            SemanticOverrides(undeclaredReals = BoundsOverride(lower = BoundOverride.Open)),
            SemanticOverrides(feasibilityTolerance = SourceTolerance()),
            SemanticOverrides(pinAbsentOptionalValues = false),
        )

        for (replacement in replacements) {
            val error = assertFailsWith<IllegalArgumentException> {
                document.toProblem(semanticOverrides = replacement)
            }
            assertFalse(error is OpbLoweringException)
        }
    }

    @Test
    fun `both OPB lowering entries should capture the same ambient resource settings`() {
        val saved = KlauseConfig.current
        try {
            KlauseConfig.current = saved.copy(largeSpanThreshold = 17)
            val document = Opb.parse("+1 x1 >= 1 ;")

            val source = document.toProblem()
            val decoded = OpbDecoder.decode(document)

            assertEquals(17L, source.problem.settings.largeSpanThreshold)
            assertEquals(source.problem.settings, decoded.problem.settings)
        } finally {
            KlauseConfig.current = saved
        }
    }

    @Test
    fun `explicit OPB resource settings should remain authoritative`() {
        val settings = ProblemSettings(largeSpanThreshold = 19)
        val document = Opb.parse("+1 x1 >= 1 ;")

        val source = document.toProblem(settings)

        assertEquals(settings, source.problem.settings)
        assertEquals(opbSemantics, source.semantics.effective)
    }
}
