package com.eignex.klause.formats.mps

import com.eignex.klause.ir.BoundOverride
import com.eignex.klause.ir.BoundsOverride
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.ir.UndeclaredIntegerBounds
import com.eignex.klause.ir.UndeclaredRealBounds
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MpsDeclarationDefaultsTest {
    @Test
    fun `unranged declarations should retain the MPS integer and real defaults`() {
        val source = Mps.parse("ROWS\n N COST\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 1\n" +
            " M1 'MARKER' 'INTEND'\n X COST 1\nENDATA")

        val compiled = source.toProblem()

        assertEquals(UndeclaredIntegerBounds(0, 1), compiled.semantics.effective.undeclaredIntegers)
        assertEquals(UndeclaredRealBounds(SemanticRational.ZERO, null),
            compiled.semantics.effective.undeclaredReals)
        compiled.sourceWitness(listOf(BigFraction.ONE, BigFraction.ofLong(20)))
        assertFailsWith<MpsLoweringException> {
            compiled.sourceWitness(listOf(BigFraction.ofLong(2), BigFraction.ONE))
        }
        assertFailsWith<MpsLoweringException> {
            compiled.sourceWitness(listOf(BigFraction.ONE, BigFraction.MINUS_ONE))
        }
    }

    @Test
    fun `caller integer defaults should replace the complete unranged interval`() {
        val source = Mps.parse("ROWS\n N COST\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 1\nENDATA")
        val overrides = SemanticOverrides(undeclaredIntegers = BoundsOverride(
            BoundOverride.Value(2L), BoundOverride.Value(5L)))

        val results = listOf(source.toProblem(semanticOverrides = overrides),
            source.toExactSourceProblem(semanticOverrides = overrides))

        for (compiled in results) {
            compiled.sourceWitness(listOf(BigFraction.ofLong(3)))
            assertFailsWith<MpsLoweringException> { compiled.sourceWitness(listOf(BigFraction.ONE)) }
            assertEquals(2L, compiled.model.intBounds.lower(0))
            assertEquals(5L, compiled.model.intBounds.upper(0))
        }
    }

    @Test
    fun `explicit bounds including open and one sided ranges should ignore declaration defaults`() {
        val records = listOf("LO B X -2", "UP B X 2", "UP B X -2", "FX B X 2", "FR B X",
            "MI B X", "PL B X", "BV B X", "LI B X -2", "UI B X 2")
        val overrides = SemanticOverrides(
            undeclaredIntegers = BoundsOverride(BoundOverride.Value(10L), BoundOverride.Value(20L)),
            undeclaredReals = BoundsOverride(BoundOverride.Value(SemanticRational.ofLong(10)),
                BoundOverride.Value(SemanticRational.ofLong(20))),
        )
        for (record in records) {
            val source = Mps.parse("ROWS\n N COST\nCOLUMNS\n X COST 1\nBOUNDS\n $record\nENDATA")
            val original = source.toProblem()

            val compiled = source.toProblem(semanticOverrides = overrides)

            for (value in listOf(-3L, -2L, 0L, 1L, 2L, 10L)) {
                val point = listOf(BigFraction.ofLong(value))
                assertEquals(runCatching { original.sourceWitness(point) }.isSuccess,
                    runCatching { compiled.sourceWitness(point) }.isSuccess, "$record at $value")
            }
        }
    }

    @Test
    fun `rational real declaration bounds should remain exact source rows`() {
        val third = SemanticRational.of(BIG_ONE, bigIntOf(3))
        val source = Mps.parse("ROWS\n N COST\nCOLUMNS\n X COST 1\nENDATA")

        val compiled = source.toProblem(semanticOverrides = SemanticOverrides(
            undeclaredReals = BoundsOverride(BoundOverride.Value(third), BoundOverride.Open),
            feasibilityTolerance = SourceTolerance(),
        ))

        assertTrue(compiled.sourceExact)
        compiled.sourceWitness(listOf(BigFraction.of(BIG_ONE, bigIntOf(3))))
        assertFailsWith<MpsLoweringException> {
            compiled.sourceWitness(listOf(checkNotNull(BigFraction.ofDouble(1.0 / 3.0))))
        }
    }

    @Test
    fun `finite integer extrema should remain bounded after replacing defaults`() {
        val source = Mps.parse("ROWS\n N COST\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 1\nENDATA")

        val compiled = source.toProblem(semanticOverrides = SemanticOverrides(undeclaredIntegers = BoundsOverride(
            BoundOverride.Value(Long.MIN_VALUE), BoundOverride.Value(Long.MAX_VALUE))))

        assertTrue(compiled.model.intBounds.hasLower(0))
        assertTrue(compiled.model.intBounds.hasUpper(0))
        assertEquals(Long.MIN_VALUE, compiled.model.intBounds.lower(0))
        assertEquals(Long.MAX_VALUE, compiled.model.intBounds.upper(0))
        compiled.sourceWitness(listOf(BigFraction.ofLong(Long.MAX_VALUE)))
        assertFailsWith<MpsLoweringException> {
            compiled.sourceWitness(listOf(BigFraction.ofLong(Long.MAX_VALUE) + BigFraction.ONE))
        }
    }

    @Test
    fun `open replacements should remove both integer default endpoints`() {
        val source = Mps.parse("ROWS\n N COST\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 1\nENDATA")

        val compiled = source.toProblem(semanticOverrides = SemanticOverrides(undeclaredIntegers = BoundsOverride(
            BoundOverride.Open, BoundOverride.Open)))

        assertEquals(false, compiled.model.intBounds.hasLower(0))
        assertEquals(false, compiled.model.intBounds.hasUpper(0))
        compiled.sourceWitness(listOf(BigFraction.ofLong(-20)))
        compiled.sourceWitness(listOf(BigFraction.ofLong(Long.MAX_VALUE) + BigFraction.ONE))
    }

    @Test
    fun `finite caller real bounds should survive the MPS text infinity convention`() {
        val wide = parseBigInt("1000000000000000000000")
        val source = Mps.parse("ROWS\n N COST\nCOLUMNS\n X COST 1\nENDATA")

        val compiled = source.toProblem(semanticOverrides = SemanticOverrides(
            undeclaredReals = BoundsOverride(BoundOverride.Value(SemanticRational.of(wide)), BoundOverride.Open),
            feasibilityTolerance = SourceTolerance(),
        ))

        compiled.sourceWitness(listOf(BigFraction.of(wide, BIG_ONE)))
        assertFailsWith<MpsLoweringException> { compiled.sourceWitness(listOf(BigFraction.ZERO)) }
    }

    @Test
    fun `programmatic replacement bounds should be explicit source ranges`() {
        val parsed = Mps.parse("ROWS\n N COST\nCOLUMNS\n X COST 1\nENDATA")
        val source = parsed.copy(variables = listOf(parsed.variables.single().copy(lower = -2.0, upper = 3.0)))

        val compiled = source.toProblem(semanticOverrides = SemanticOverrides(undeclaredReals = BoundsOverride(
            BoundOverride.Value(SemanticRational.ofLong(10)), BoundOverride.Value(SemanticRational.ofLong(20)))))

        compiled.sourceWitness(listOf(BigFraction.ZERO))
        assertFailsWith<MpsLoweringException> { compiled.sourceWitness(listOf(BigFraction.ofLong(10))) }
    }

    @Test
    fun `copied sources should retain default eligibility without changing earlier compilations`() {
        val source = Mps.parse("ROWS\n N COST\nCOLUMNS\n X COST 1\nENDATA")
        val policy = SemanticOverrides(undeclaredReals = BoundsOverride(
            BoundOverride.Value(SemanticRational.ofLong(-2)), BoundOverride.Value(SemanticRational.ofLong(3))))
        val compiled = source.copy(name = "copy").toProblem(semanticOverrides = policy)

        val original = source.toProblem()

        compiled.sourceWitness(listOf(BigFraction.MINUS_ONE))
        assertFailsWith<MpsLoweringException> { original.sourceWitness(listOf(BigFraction.MINUS_ONE)) }
        assertEquals(mpsSemantics, compiled.semantics.declared)
        assertEquals(mpsSemantics, source.semantics)
    }
}
