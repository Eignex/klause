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
import com.eignex.klause.util.bigIntOf
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
            listOf(LinearSourceRow(listOf(LinearSourceTerm(0, BigFraction.ONE)), lower = third)),
            semantics = SourceSemantics(SourceFormat.MPS, feasibilityTolerance = SourceTolerance(
                absolute = SemanticRational.of(BIG_ONE, bigIntOf(1_000_000)),
            )).resolve(),
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
            listOf(LinearSourceRow(listOf(LinearSourceTerm(0, BigFraction.ONE),
                LinearSourceTerm(0, BigFraction.MINUS_ONE)), lower = BigFraction.ONE)),
            semantics = SourceSemantics(SourceFormat.MPS, feasibilityTolerance = SourceTolerance(
                relative = SemanticRational.of(BIG_ONE, bigIntOf(10)),
            )).resolve(),
        )

        val result = checkLinearSourceWitness(source, listOf(BigFraction.ofLong(10)), Cancellation.Never)

        assertFalse(assertIs<LinearSourceCheck.Accepted>(result).witness.exact)
    }

    @Test
    fun `tolerance should not relax integer integrality`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("n", true)), emptyList(),
            semantics = SourceSemantics(SourceFormat.MPS, feasibilityTolerance = SourceTolerance(
                absolute = SemanticRational.ONE,
            )).resolve(),
        )

        val result = checkLinearSourceWitness(source, listOf(BigFraction.ofLong(2).reciprocal()), Cancellation.Never)

        assertIs<LinearSourceCheck.Rejected>(result)
    }

    @Test
    fun `inactive integer indicators should leave their row unconstrained`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("guard", true), LinearSourceVariable("x", false)),
            listOf(LinearSourceRow(listOf(LinearSourceTerm(1, BigFraction.ONE)), lower = BigFraction.ONE,
                indicator = LinearSourceIndicator(0, true))),
        )

        val result = checkLinearSourceWitness(source, listOf(BigFraction.ZERO, BigFraction.ZERO), Cancellation.Never)

        assertTrue(assertIs<LinearSourceCheck.Accepted>(result).witness.exact)
    }

    @Test
    fun `maximization should retain exact source coefficient and constant units`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("n", true)), emptyList(),
            LinearSourceObjective(listOf(LinearSourceTerm(0, BigFraction.ofLong(3).reciprocal())),
                BigFraction.ofLong(10).reciprocal(), ObjectiveSense.MAXIMIZE),
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
}
