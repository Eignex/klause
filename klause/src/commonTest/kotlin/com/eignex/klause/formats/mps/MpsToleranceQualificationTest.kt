package com.eignex.klause.formats.mps

import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.pipeline.toleranceQualification
import com.eignex.klause.util.Cancellation
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MpsToleranceQualificationTest {
    @Test
    fun `a rounded equality should retain an accepted bound below its nominal float optimum`() {
        val source = assertNotNull(Mps.parse(
            "ROWS\n N COST\n E R\nCOLUMNS\n X COST 1 R 3.0000000000000001\nRHS\n RHS R 1\nENDATA",
        ).toLinearSourceProblem())
        val expected = BigFraction.of(bigIntOf(9999999000000000L), bigIntOf(30000000000000001L))

        val bound = source.toleranceQualification(Cancellation.Never)?.objectiveBound()
        val witness = assertNotNull(source.checkMpsWitness(listOf(expected)))

        assertEquals(expected, bound)
        assertEquals(expected, witness.objective)
        assertFalse(witness.exact)
        assertTrue(expected < checkNotNull(BigFraction.ofDouble(0.3333333333333333)))
    }

    @Test
    fun `enabled one term rows should preserve an empty tolerance accepted set`() {
        val source = assertNotNull(Mps.parse(
            "ROWS\n N COST\n G A\n L B\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 0\n" +
                " M1 'MARKER' 'INTEND'\n X A 1 B 1\nRHS\n RHS A 0.2 B 0.1\n" +
                "BOUNDS\n FX BN I 1\n LO BN X 0\n UP BN X 1\n" +
                "INDICATORS\n IF A I 1\n IF B I 1\nENDATA",
        ).toLinearSourceProblem())

        val qualified = source.toleranceQualification(Cancellation.Never)?.verify()

        assertEquals(true, qualified)
    }

    @Test
    fun `an unfixed indicator should not supply an unconditional accepted objective bound`() {
        val source = assertNotNull(Mps.parse(
            "ROWS\n N COST\n G R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 0\n" +
                " M1 'MARKER' 'INTEND'\n X COST 1 R 1\nRHS\n RHS R 2\n" +
                "BOUNDS\n FR B X\nINDICATORS\n IF R I 1\nENDATA",
        ).toLinearSourceProblem())

        val bound = source.toleranceQualification(Cancellation.Never)?.objectiveBound()

        assertEquals(null, bound)
    }

    @Test
    fun `relative one term bounds should retain both source signs`() {
        for ((lower, expected) in listOf("10" to "5", "-10" to "-20")) {
            val source = assertNotNull(Mps.parse(
                "ROWS\n N COST\n G R\nCOLUMNS\n X COST 1 R 1\nRHS\n RHS R $lower\nBOUNDS\n FR B X\nENDATA",
            ).toLinearSourceProblem(SemanticOverrides(feasibilityTolerance = SourceTolerance(
                relative = SemanticRational.of(BIG_ONE, bigIntOf(2)),
            ))))

            val bound = source.toleranceQualification(Cancellation.Never)?.objectiveBound()
            val point = assertNotNull(source.checkMpsWitness(listOf(BigFraction.ofLong(expected.toLong()))))

            assertEquals(BigFraction.ofLong(expected.toLong()), bound)
            assertEquals(bound, point.objective)
            assertFalse(point.exact)
        }
    }

    @Test
    fun `negative row coefficients should retain absolute slack in original row units`() {
        val source = assertNotNull(Mps.parse(
            "OBJSENSE\n MAX\nROWS\n N COST\n G R\nCOLUMNS\n X COST 1 R -2\nRHS\n RHS R 4\n" +
                "BOUNDS\n FR B X\nENDATA",
        ).toLinearSourceProblem(SemanticOverrides(feasibilityTolerance = SourceTolerance(
            absolute = SemanticRational.of(BIG_ONE, bigIntOf(10)),
        ))))
        val expected = BigFraction.of(bigIntOf(-39), bigIntOf(20))

        val bound = source.toleranceQualification(Cancellation.Never)?.objectiveBound()
        val point = assertNotNull(source.checkMpsWitness(listOf(expected)))

        assertEquals(expected, bound)
        assertEquals(bound, point.objective)
        assertFalse(point.exact)
    }

    @Test
    fun `cancellation during row capture should withhold an accepted objective bound`() {
        val source = assertNotNull(Mps.parse("ROWS\n N COST\nCOLUMNS\n X COST 1\nENDATA").toLinearSourceProblem())
        var visits = 0

        val bound = source.toleranceQualification(Cancellation { ++visits >= 3 })?.objectiveBound()

        assertEquals(null, bound)
    }

    @Test
    fun `zero tolerance should preserve the nominal feasible set`() {
        val source = assertNotNull(Mps.parse("ROWS\n N COST\nCOLUMNS\n X COST 1\nENDATA")
            .toLinearSourceProblem(SemanticOverrides(feasibilityTolerance = SourceTolerance())))

        val qualified = source.toleranceQualification(Cancellation.Never)?.verify()

        assertEquals(true, qualified)
    }

    @Test
    fun `a nominal constant contradiction should require a tolerance contradiction`() {
        for ((bound, covered) in listOf("0.00000005" to false, "1" to true)) {
            val source = assertNotNull(Mps.parse(
                "ROWS\n N COST\n G R\nCOLUMNS\n X COST 0\nRHS\n RHS R $bound\nENDATA",
            ).toLinearSourceProblem())

            val qualified = source.toleranceQualification(Cancellation.Never)?.verify()

            assertEquals(covered, qualified)
            assertEquals(!covered, source.checkMpsWitness(listOf(BigFraction.ZERO)) != null)
        }
    }

    @Test
    fun `small tolerance should preserve bounded rational integer rows`() {
        val source = assertNotNull(Mps.parse(
            "ROWS\n N COST\n L R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 1 R 0.1\n" +
                " J R 0.2\n M1 'MARKER' 'INTEND'\nRHS\n RHS R 0.15\nENDATA",
        ).toLinearSourceProblem())

        val qualified = source.toleranceQualification(Cancellation.Never)?.verify()

        assertEquals(true, qualified)
    }

    @Test
    fun `slack reaching the next integer activity should not preserve the nominal row`() {
        val source = assertNotNull(Mps.parse(
            "ROWS\n N COST\n L R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 1 R 0.1\n" +
                " J R 0.2\n M1 'MARKER' 'INTEND'\nRHS\n RHS R 0.15\nENDATA",
        ).toLinearSourceProblem(SemanticOverrides(feasibilityTolerance = SourceTolerance(
            absolute = SemanticRational.of(BIG_ONE, bigIntOf(20)),
        ))))

        val qualified = source.toleranceQualification(Cancellation.Never)?.verify()
        val witness = assertNotNull(source.checkMpsWitness(listOf(BigFraction.ZERO, BigFraction.ONE)))

        assertEquals(false, qualified)
        assertFalse(witness.exact)
    }

    @Test
    fun `tolerance admitting a new integer endpoint should withhold nominal claims`() {
        val source = assertNotNull(Mps.parse("ROWS\n N COST\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 1\nENDATA")
            .toLinearSourceProblem(SemanticOverrides(feasibilityTolerance = SourceTolerance(
                absolute = SemanticRational.ONE,
            ))))

        val qualified = source.toleranceQualification(Cancellation.Never)?.verify()
        val witness = assertNotNull(source.checkMpsWitness(listOf(BigFraction.MINUS_ONE)))

        assertEquals(false, qualified)
        assertFalse(witness.exact)
    }

    @Test
    fun `relative activity must include cancelling integer terms`() {
        val source = assertNotNull(Mps.parse(
            "ROWS\n N COST\n L R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 1 R 1000000000\n" +
                " J R -1000000000\n M1 'MARKER' 'INTEND'\nRHS\n RHS R -1\nENDATA",
        ).toLinearSourceProblem())

        val qualified = source.toleranceQualification(Cancellation.Never)?.verify()
        val witness = assertNotNull(source.checkMpsWitness(listOf(BigFraction.ONE, BigFraction.ONE)))

        assertEquals(false, qualified)
        assertFalse(witness.exact)
    }

    @Test
    fun `absolute slack below an integer step should preserve open integer rows`() {
        val source = assertNotNull(Mps.parse("ROWS\n N COST\n L R\nCOLUMNS\n I COST 1 R 1\nBOUNDS\n LI B I 0\nENDATA")
            .toLinearSourceProblem(SemanticOverrides(feasibilityTolerance = SourceTolerance(
                absolute = SemanticRational.of(BIG_ONE, bigIntOf(2)),
            ))))

        val qualified = source.toleranceQualification(Cancellation.Never)?.verify()

        assertEquals(true, qualified)
    }

    @Test
    fun `relative slack on unbounded activity must not imply a finite ceiling`() {
        val source = assertNotNull(Mps.parse("ROWS\n N COST\n L R\nCOLUMNS\n I COST 1 R 1\nBOUNDS\n LI B I 0\nENDATA")
            .toLinearSourceProblem(SemanticOverrides(feasibilityTolerance = SourceTolerance(
                relative = SemanticRational.ONE,
            ))))

        val qualified = source.toleranceQualification(Cancellation.Never)?.verify()
        val witness = assertNotNull(source.checkMpsWitness(listOf(BigFraction.ONE)))

        assertEquals(false, qualified)
        assertFalse(witness.exact)
    }

    @Test
    fun `tolerant crossed bounds should qualify only when the accepted interval stays empty`() {
        val parsed = Mps.parse("ROWS\n N COST\nCOLUMNS\n X COST 0\nBOUNDS\n LO B X 1\n UP B X 0\nENDATA")
        val cases = listOf(SourceTolerance() to true, checkNotNull(mpsSemantics.feasibilityTolerance) to true,
            SourceTolerance(absolute = SemanticRational.ONE) to false)
        for ((policy, covered) in cases) {
            val source = assertNotNull(parsed.toLinearSourceProblem(SemanticOverrides(feasibilityTolerance = policy)))

            val qualified = source.toleranceQualification(Cancellation.Never)?.verify()

            assertEquals(covered, qualified)
        }
    }

    @Test
    fun `interrupted equivalence checking should withhold qualification`() {
        val source = assertNotNull(Mps.parse("ROWS\n N COST\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 1\nENDATA")
            .toLinearSourceProblem())

        val qualified = source.toleranceQualification(Cancellation { true })

        assertEquals(null, qualified)
    }
}
