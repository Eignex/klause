package com.eignex.klause.compile

import com.eignex.klause.ir.QuadraticCoefficient
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticObjectiveTerm
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.unaryMinus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class CompiledObjectivePairingTest {
    @Test
    fun `copied float weights should retain exact source integers beyond binary64`() {
        val source = QuadraticVariable.Real(SourceRealId(0))
        val native = LinearObjective(intCoefficients = longArrayOf(1))
        val paired = native.paired(CompiledObjectivePairing.capture(
            Any(), emptyList(), listOf(source), emptyList(), native))

        val copied = paired.copy(intCoefficients = longArrayOf(9007199254740993L), constant = 7)
        val original = assertNotNull(copied.sourcePairing).original(copied)

        assertEquals(QuadraticObjectiveSpec(listOf(QuadraticObjectiveTerm(
            QuadraticCoefficient.ofLong(9007199254740993L), source)), QuadraticCoefficient.ofLong(7)), original)
    }

    @Test
    fun `negated source coefficients and constants should retain exact signed word overflow`() {
        val source = QuadraticVariable.Real(SourceRealId(0))
        val native = LinearObjective(intCoefficients = longArrayOf(Long.MIN_VALUE), constant = Long.MIN_VALUE)
        val paired = native.paired(CompiledObjectivePairing.capture(
            Any(), emptyList(), listOf(source), emptyList(), native))

        val negated = paired.negated()
        val original = assertNotNull(negated.sourcePairing).original(negated)

        val positive = QuadraticCoefficient.of(-bigIntOf(Long.MIN_VALUE))
        assertEquals(QuadraticObjectiveSpec(listOf(QuadraticObjectiveTerm(positive, source)), positive), original)
    }

    @Test
    fun `a default copy should preserve an exact transformed objective`() {
        val source = QuadraticVariable.Real(SourceRealId(0))
        val native = LinearObjective(intCoefficients = longArrayOf(Long.MIN_VALUE))
        val paired = native.paired(CompiledObjectivePairing.capture(
            Any(), emptyList(), listOf(source), emptyList(), native)).negated()

        val copied = paired.copy()

        assertEquals(assertNotNull(paired.sourcePairing).original(paired),
            assertNotNull(copied.sourcePairing).original(copied))
    }

    @Test
    fun `a replacement coefficient should replace its exact transformed value`() {
        val source = QuadraticVariable.Real(SourceRealId(0))
        val native = LinearObjective(intCoefficients = longArrayOf(Long.MIN_VALUE))
        val paired = native.paired(CompiledObjectivePairing.capture(
            Any(), emptyList(), listOf(source), emptyList(), native)).negated()

        val copied = paired.copy(intCoefficients = longArrayOf(3))
        val original = assertNotNull(copied.sourcePairing).original(copied)

        assertEquals(QuadraticObjectiveSpec(listOf(QuadraticObjectiveTerm(QuadraticCoefficient.ofLong(3), source))),
            original)
    }

    @Test
    fun `an unpaired coefficient should reject source objective resolution`() {
        val native = LinearObjective(intCoefficients = longArrayOf(1))
        val paired = native.paired(CompiledObjectivePairing.capture(
            Any(), emptyList(), listOf(QuadraticVariable.Real(SourceRealId(0))), emptyList(), native))

        val copied = paired.copy(intCoefficients = longArrayOf(0, 1))

        assertFailsWith<IllegalArgumentException> { assertNotNull(copied.sourcePairing).original(copied) }
    }

    @Test
    fun `a pairing from different coefficients should reject source resolution`() {
        val native = LinearObjective(intCoefficients = longArrayOf(1))
        val pairing = CompiledObjectivePairing.capture(
            Any(), emptyList(), listOf(QuadraticVariable.Real(SourceRealId(0))), emptyList(), native)

        val other = LinearObjective(intCoefficients = longArrayOf(2)).paired(pairing)

        assertFailsWith<IllegalArgumentException> { pairing.original(other) }
    }

    @Test
    fun `pairing should preserve the legacy finite objective evaluation`() {
        val native = LinearObjective(intCoefficients = longArrayOf(1), constant = 7)
        val paired = native.paired(CompiledObjectivePairing.capture(
            Any(), emptyList(), listOf(QuadraticVariable.Real(SourceRealId(0))), emptyList(), native))

        val value = paired.evaluateLong(Sample(booleanArrayOf(), longArrayOf(2)))

        assertEquals(9L, value)
    }
}
