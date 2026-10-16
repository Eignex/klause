package com.eignex.klause.formats.mps

import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MpsToleranceQualificationTest {
    @Test
    fun `a rounded equality should retain an accepted bound below its nominal float optimum`() {
        val compiled = Mps.parse(
            "ROWS\n N COST\n E R\nCOLUMNS\n X COST 1 R 3.0000000000000001\nRHS\n RHS R 1\nENDATA",
        ).toProblem()
        val expected = BigFraction.of(bigIntOf(9999999000000000L), bigIntOf(30000000000000001L))

        val bound = compiled.toleranceObjectiveBound()
        val witness = compiled.sourceWitness(listOf(expected))

        assertEquals(expected, bound)
        assertEquals(expected, witness.objective)
        assertFalse(witness.exact)
        assertTrue(expected < checkNotNull(BigFraction.ofDouble(0.3333333333333333)))
    }

    @Test
    fun `enabled one term rows should preserve an empty tolerance accepted set`() {
        val source = Mps.parse(
            "ROWS\n N COST\n G A\n L B\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 0\n" +
                " M1 'MARKER' 'INTEND'\n X A 1 B 1\nRHS\n RHS A 0.2 B 0.1\n" +
                "BOUNDS\n FX BN I 1\n LO BN X 0\n UP BN X 1\n" +
                "INDICATORS\n IF A I 1\n IF B I 1\nENDATA",
        ).toProblem()

        val qualified = source.exactClaimsCoverTolerance()

        assertTrue(qualified)
    }

    @Test
    fun `an unfixed indicator should not supply an unconditional accepted objective bound`() {
        val compiled = Mps.parse(
            "ROWS\n N COST\n G R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 0\n" +
                " M1 'MARKER' 'INTEND'\n X COST 1 R 1\nRHS\n RHS R 2\n" +
                "BOUNDS\n FR B X\nINDICATORS\n IF R I 1\nENDATA",
        ).toProblem()

        val bound = compiled.toleranceObjectiveBound()

        assertEquals(null, bound)
    }

    @Test
    fun `relative one term bounds should retain both source signs`() {
        for ((lower, expected) in listOf("10" to "5", "-10" to "-20")) {
            val compiled = Mps.parse(
                "ROWS\n N COST\n G R\nCOLUMNS\n X COST 1 R 1\nRHS\n RHS R $lower\nBOUNDS\n FR B X\nENDATA",
            ).toProblem(semanticOverrides = SemanticOverrides(feasibilityTolerance = SourceTolerance(
                relative = SemanticRational.of(BIG_ONE, bigIntOf(2)),
            )))

            val bound = compiled.toleranceObjectiveBound()
            val point = compiled.sourceWitness(listOf(BigFraction.ofLong(expected.toLong())))

            assertEquals(BigFraction.ofLong(expected.toLong()), bound)
            assertEquals(bound, point.objective)
            assertFalse(point.exact)
        }
    }

    @Test
    fun `negative row coefficients should retain absolute slack in original row units`() {
        val compiled = Mps.parse(
            "OBJSENSE\n MAX\nROWS\n N COST\n G R\nCOLUMNS\n X COST 1 R -2\nRHS\n RHS R 4\n" +
                "BOUNDS\n FR B X\nENDATA",
        ).toProblem(semanticOverrides = SemanticOverrides(feasibilityTolerance = SourceTolerance(
            absolute = SemanticRational.of(BIG_ONE, bigIntOf(10)),
        )))
        val expected = BigFraction.of(bigIntOf(-39), bigIntOf(20))

        val bound = compiled.toleranceObjectiveBound()
        val point = compiled.sourceWitness(listOf(expected))

        assertEquals(expected, bound)
        assertEquals(bound, point.objective)
        assertFalse(point.exact)
    }

    @Test
    fun `cancellation during row capture should withhold an accepted objective bound`() {
        val compiled = Mps.parse("ROWS\n N COST\nCOLUMNS\n X COST 1\nENDATA").toProblem()
        var visits = 0

        val bound = compiled.toleranceObjectiveBound(Cancellation { ++visits >= 3 })

        assertEquals(null, bound)
    }

    @Test
    fun `zero tolerance should preserve the nominal feasible set`() {
        val compiled = Mps.parse("ROWS\n N COST\nCOLUMNS\n X COST 1\nENDATA")
            .toExactSourceProblem()

        val qualified = compiled.exactClaimsCoverTolerance()

        assertTrue(qualified)
    }

    @Test
    fun `a nominal constant contradiction should require a tolerance contradiction`() {
        for ((bound, covered) in listOf("0.00000005" to false, "1" to true)) {
            val source = Mps.parse("ROWS\n N COST\n G R\nCOLUMNS\n X COST 0\nRHS\n RHS R $bound\nENDATA")
            val compiled = source.toExactSourceProblem(semanticOverrides = SemanticOverrides())

            val qualified = compiled.exactClaimsCoverTolerance()

            assertEquals(covered, qualified)
            assertEquals(!covered, runCatching { compiled.sourceWitness(listOf(BigFraction.ZERO)) }.isSuccess)
        }
    }

    @Test
    fun `small tolerance should preserve bounded rational integer rows`() {
        val source = Mps.parse(
            "ROWS\n N COST\n L R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 1 R 0.1\n" +
                " J R 0.2\n M1 'MARKER' 'INTEND'\nRHS\n RHS R 0.15\nENDATA",
        )
        val compiled = source.toProblem()

        val qualified = compiled.exactClaimsCoverTolerance()

        assertTrue(qualified)
    }

    @Test
    fun `slack reaching the next integer activity should not preserve the nominal row`() {
        val source = Mps.parse(
            "ROWS\n N COST\n L R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 1 R 0.1\n" +
                " J R 0.2\n M1 'MARKER' 'INTEND'\nRHS\n RHS R 0.15\nENDATA",
        )
        val compiled = source.toProblem(semanticOverrides = SemanticOverrides(feasibilityTolerance = SourceTolerance(
            absolute = SemanticRational.of(BIG_ONE, bigIntOf(20)),
        )))

        val qualified = compiled.exactClaimsCoverTolerance()
        val witness = compiled.sourceWitness(listOf(BigFraction.ZERO, BigFraction.ONE))

        assertFalse(qualified)
        assertFalse(witness.exact)
    }

    @Test
    fun `tolerance admitting a new integer endpoint should withhold nominal claims`() {
        val source = Mps.parse("ROWS\n N COST\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 1\nENDATA")
        val compiled = source.toProblem(semanticOverrides = SemanticOverrides(feasibilityTolerance = SourceTolerance(
            absolute = SemanticRational.ONE,
        )))

        val qualified = compiled.exactClaimsCoverTolerance()
        val witness = compiled.sourceWitness(listOf(BigFraction.MINUS_ONE))

        assertFalse(qualified)
        assertFalse(witness.exact)
    }

    @Test
    fun `relative activity must include cancelling integer terms`() {
        val source = Mps.parse(
            "ROWS\n N COST\n L R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 1 R 1000000000\n" +
                " J R -1000000000\n M1 'MARKER' 'INTEND'\nRHS\n RHS R -1\nENDATA",
        )
        val compiled = source.toProblem()

        val qualified = compiled.exactClaimsCoverTolerance()
        val witness = compiled.sourceWitness(listOf(BigFraction.ONE, BigFraction.ONE))

        assertFalse(qualified)
        assertFalse(witness.exact)
    }

    @Test
    fun `absolute slack below an integer step should preserve open integer rows`() {
        val source = Mps.parse("ROWS\n N COST\n L R\nCOLUMNS\n I COST 1 R 1\nBOUNDS\n LI B I 0\nENDATA")
        val compiled = source.toProblem(semanticOverrides = SemanticOverrides(feasibilityTolerance = SourceTolerance(
            absolute = SemanticRational.of(BIG_ONE, bigIntOf(2)),
        )))

        val qualified = compiled.exactClaimsCoverTolerance()

        assertTrue(qualified)
    }

    @Test
    fun `relative slack on unbounded activity must not imply a finite ceiling`() {
        val source = Mps.parse("ROWS\n N COST\n L R\nCOLUMNS\n I COST 1 R 1\nBOUNDS\n LI B I 0\nENDATA")
        val compiled = source.toProblem(semanticOverrides = SemanticOverrides(feasibilityTolerance = SourceTolerance(
            relative = SemanticRational.ONE,
        )))

        val qualified = compiled.exactClaimsCoverTolerance()
        val witness = compiled.sourceWitness(listOf(BigFraction.ONE))

        assertFalse(qualified)
        assertFalse(witness.exact)
    }

    @Test
    fun `tolerant crossed bounds should qualify only when the accepted interval stays empty`() {
        val source = Mps.parse("ROWS\n N COST\nCOLUMNS\n X COST 0\nBOUNDS\n LO B X 1\n UP B X 0\nENDATA")
        val cases = listOf(SourceTolerance() to true, checkNotNull(mpsSemantics.feasibilityTolerance) to true,
            SourceTolerance(absolute = SemanticRational.ONE) to false)
        for ((policy, covered) in cases) {
            val compiled = source.toExactSourceProblem(
                semanticOverrides = SemanticOverrides(feasibilityTolerance = policy),
            )

            val qualified = compiled.exactClaimsCoverTolerance()

            assertEquals(covered, qualified)
        }
    }

    @Test
    fun `interrupted equivalence checking should withhold qualification`() {
        val compiled = Mps.parse("ROWS\n N COST\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 1\nENDATA")
            .toProblem()

        val qualified = compiled.exactClaimsCoverTolerance(Cancellation { true })

        assertFalse(qualified)
    }
}
