package com.eignex.klause.solver.validation

import com.eignex.klause.ir.LinearSourceIndicator
import com.eignex.klause.ir.LinearSourceObjective
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.LinearSourceRow
import com.eignex.klause.ir.LinearSourceTerm
import com.eignex.klause.ir.LinearSourceVariable
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LinearSourceWitnessTest {
    @Test
    fun `absolute tolerance should use original rational row units`() {
        val third = BigFraction.ofLong(3).reciprocal()
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", false)),
            listOf(LinearSourceRow(
                listOf(LinearSourceTerm(0, SemanticRational.ONE)),
                lower = SemanticRational.of(BIG_ONE, bigIntOf(3)),
            )),
            semantics = SourceSemantics(
                SourceFormat.MPS,
                feasibilityTolerance = SourceTolerance(
                absolute = SemanticRational.of(BIG_ONE, bigIntOf(1_000_000)),
            )
            ).resolve(),
        )
        val value = third - BigFraction.ofLong(2_000_000).reciprocal()

        val result = checkLinearSourceWitness(source, listOf(value), Cancellation.Never)

        val witness = assertIs<LinearSourceCheck.Accepted>(result).witness
        assertEquals(listOf(value), witness.values)
        assertFalse(witness.exact)
        assertSame(source, witness.source)
    }

    @Test
    fun `relative tolerance should retain canceling original term magnitudes`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", false)),
            listOf(
                LinearSourceRow(
                    listOf(
                LinearSourceTerm(0, SemanticRational.ONE),
                LinearSourceTerm(0, SemanticRational.ofLong(-1)),
            ),
                lower = SemanticRational.ONE
                )
            ),
            semantics = SourceSemantics(
                SourceFormat.MPS,
                feasibilityTolerance = SourceTolerance(
                relative = SemanticRational.of(BIG_ONE, bigIntOf(10)),
            )
            ).resolve(),
        )

        val result = checkLinearSourceWitness(source, listOf(BigFraction.ofLong(10)), Cancellation.Never)

        assertFalse(assertIs<LinearSourceCheck.Accepted>(result).witness.exact)
    }

    @Test
    fun `tolerance should not relax integer integrality`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("n", true)),
            emptyList(),
            semantics = SourceSemantics(
                SourceFormat.MPS,
                feasibilityTolerance = SourceTolerance(
                absolute = SemanticRational.ONE,
            )
            ).resolve(),
        )

        val result = checkLinearSourceWitness(source, listOf(BigFraction.ofLong(2).reciprocal()), Cancellation.Never)

        assertIs<LinearSourceCheck.Rejected>(result)
    }

    @Test
    fun `inactive integer indicators should leave their row unconstrained`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("guard", true), LinearSourceVariable("x", false)),
            listOf(
                LinearSourceRow(
                listOf(LinearSourceTerm(1, SemanticRational.ONE)),
                lower = SemanticRational.ONE,
                indicator = LinearSourceIndicator(0, true),
            )
            ),
        )

        val result = checkLinearSourceWitness(source, listOf(BigFraction.ZERO, BigFraction.ZERO), Cancellation.Never)

        assertTrue(assertIs<LinearSourceCheck.Accepted>(result).witness.exact)
    }

    @Test
    fun `maximization should retain exact source coefficient and constant units`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("n", true)),
            emptyList(),
            LinearSourceObjective(
                listOf(LinearSourceTerm(0, SemanticRational.of(BIG_ONE, bigIntOf(3)))),
                SemanticRational.of(BIG_ONE, bigIntOf(10)),
                ObjectiveSense.MAXIMIZE,
            ),
        )

        val result = checkLinearSourceWitness(source, listOf(BigFraction.ofLong(3)), Cancellation.Never)

        assertEquals(BigFraction.ofLong(11) * BigFraction.ofLong(10).reciprocal(),
            assertIs<LinearSourceCheck.Accepted>(result).witness.objective)
    }

    @Test
    fun `a stopped point check should decline without rejecting the source`() {
        val source = LinearSourceProblem(listOf(LinearSourceVariable("x", false)), emptyList())

        val result = checkLinearSourceWitness(source, listOf(BigFraction.ZERO), Cancellation { true })

        assertIs<LinearSourceCheck.Declined>(result)
    }

    @Test
    fun `exact rows should retain wide rational coordinates and original objective units`() {
        val large = parseBigInt("100000000000000000000000000000000000001")
        val coordinate = BigFraction.of(large, bigIntOf(3))
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", false,
                SemanticRational.of(large, bigIntOf(3)), SemanticRational.of(large, bigIntOf(3)))),
            listOf(LinearSourceRow(
                listOf(LinearSourceTerm(0, SemanticRational.ofLong(7)),
                    LinearSourceTerm(0, SemanticRational.ofLong(-7))),
                SemanticRational.ZERO, SemanticRational.ZERO,
            )),
            LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.of(BIG_ONE, bigIntOf(7)))),
                SemanticRational.of(BIG_ONE, bigIntOf(11))),
            SourceSemantics(SourceFormat.MPS, feasibilityTolerance = SourceTolerance(
                absolute = SemanticRational.ONE, relative = SemanticRational.ONE,
            )).resolve(),
        )

        val result = checkLinearSourceWitness(source, listOf(coordinate), Cancellation.Never)

        val witness = assertIs<LinearSourceCheck.Accepted>(result).witness
        assertTrue(witness.exact)
        assertEquals(listOf(coordinate), witness.values)
        assertEquals(BigFraction.of(large, bigIntOf(21)) + BigFraction.of(BIG_ONE, bigIntOf(11)), witness.objective)
    }

    @Test
    fun `relative tolerance should retain both sides of canceling original rows`() {
        for ((lower, upper) in listOf(
            SemanticRational.ofLong(2) to null,
            null to SemanticRational.ofLong(-2),
            SemanticRational.ofLong(2) to SemanticRational.ofLong(2),
            SemanticRational.ofLong(-2) to SemanticRational.ofLong(-2),
            SemanticRational.ofLong(2) to SemanticRational.ofLong(3),
        )) {
            val source = LinearSourceProblem(
                listOf(LinearSourceVariable("x", false)),
                listOf(LinearSourceRow(listOf(
                    LinearSourceTerm(0, SemanticRational.ONE),
                    LinearSourceTerm(0, SemanticRational.ofLong(-1)),
                ), lower, upper)),
                semantics = SourceSemantics(SourceFormat.MPS, feasibilityTolerance = SourceTolerance(
                    relative = SemanticRational.of(BIG_ONE, bigIntOf(10)),
                )).resolve(),
            )

            val result = checkLinearSourceWitness(source, listOf(BigFraction.ofLong(10)), Cancellation.Never)

            assertFalse(assertIs<LinearSourceCheck.Accepted>(result).witness.exact)
        }
    }

    @Test
    fun `a tolerant bound should remain inexact after later exact rows`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", false, upper = SemanticRational.ONE)),
            listOf(LinearSourceRow(listOf(LinearSourceTerm(0, SemanticRational.ofLong(2))),
                SemanticRational.ofLong(3), SemanticRational.ofLong(3))),
            semantics = SourceSemantics(SourceFormat.MPS, feasibilityTolerance = SourceTolerance(
                absolute = SemanticRational.of(BIG_ONE, bigIntOf(2)),
            )).resolve(),
        )

        val result = checkLinearSourceWitness(source, listOf(BigFraction.of(bigIntOf(3), bigIntOf(2))),
            Cancellation.Never)

        assertFalse(assertIs<LinearSourceCheck.Accepted>(result).witness.exact)
    }

    @Test
    fun `a row outside the available source tolerance should be rejected`() {
        for (tolerance in listOf(
            SourceTolerance(),
            SourceTolerance(absolute = SemanticRational.ONE),
            SourceTolerance(relative = SemanticRational.of(BIG_ONE, bigIntOf(20))),
        )) {
            val source = LinearSourceProblem(
                listOf(LinearSourceVariable("x", false)),
                listOf(LinearSourceRow(listOf(
                    LinearSourceTerm(0, SemanticRational.ONE),
                    LinearSourceTerm(0, SemanticRational.ofLong(-1)),
                ), lower = SemanticRational.ofLong(2))),
                semantics = SourceSemantics(SourceFormat.MPS, feasibilityTolerance = tolerance).resolve(),
            )

            val result = checkLinearSourceWitness(source, listOf(BigFraction.ofLong(10)), Cancellation.Never)

            assertIs<LinearSourceCheck.Rejected>(result)
        }
    }

    @Test
    fun `relative row tolerance should stop on the containing work allowance`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", false)),
            listOf(LinearSourceRow(listOf(
                LinearSourceTerm(0, SemanticRational.ONE),
                LinearSourceTerm(0, SemanticRational.ofLong(-1)),
            ), lower = SemanticRational.ONE)),
            semantics = SourceSemantics(SourceFormat.MPS, feasibilityTolerance = SourceTolerance(
                relative = SemanticRational.of(BIG_ONE, bigIntOf(10)),
            )).resolve(),
        )
        var work = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = work >= 5L
            override fun workMeter(): WorkMeter = WorkMeter { work += it }
        }

        val result = checkLinearSourceWitness(source, listOf(BigFraction.ofLong(10)), stop)

        assertIs<LinearSourceCheck.Declined>(result)
    }
}
