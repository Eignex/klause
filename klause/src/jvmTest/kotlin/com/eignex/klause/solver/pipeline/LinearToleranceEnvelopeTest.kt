package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.LinearSourceIndicator
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.LinearSourceRow
import com.eignex.klause.ir.LinearSourceTerm
import com.eignex.klause.ir.LinearSourceVariable
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.solver.validation.LinearSourceCheck
import com.eignex.klause.solver.validation.checkLinearSourceWitness
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

@RunWith(Parameterized::class)
class LinearToleranceEnvelopeTest(private val upper: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "upper={0}")
        fun sides(): List<Boolean> = listOf(false, true)
    }

    private val slack = BigFraction.ofLong(10).reciprocal()
    private val tolerance = SourceTolerance(absolute = SemanticRational.of(slack.num, slack.den))

    @Test
    fun `an absolute envelope should accept exactly the source row boundary and its indicator`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("b", true, SemanticRational.ZERO, SemanticRational.ONE),
                LinearSourceVariable("r", false)),
            listOf(LinearSourceRow(listOf(LinearSourceTerm(1, SemanticRational.ONE)),
                lower = SemanticRational.ONE.takeUnless { upper },
                upper = SemanticRational.ONE.takeIf { upper }, indicator = LinearSourceIndicator(0, true))),
            semantics = SourceSemantics(SourceFormat.MPS, feasibilityTolerance = tolerance).resolve(),
        )
        val envelope = assertNotNull(source.absoluteToleranceEnvelope(ExecutionControl.Never))
        val boundary = BigFraction.ONE + if (upper) slack else slack.negated()
        val outside = boundary + if (upper) slack else slack.negated()
        fun accepted(model: LinearSourceProblem, pin: BigFraction, value: BigFraction): Boolean =
            checkLinearSourceWitness(model, listOf(pin, value), ExecutionControl.Never) is LinearSourceCheck.Accepted

        assertEquals(true, accepted(envelope, BigFraction.ONE, boundary))
        assertEquals(false, accepted(envelope, BigFraction.ONE, outside))
        for (pin in listOf(BigFraction.ZERO, BigFraction.ONE)) {
            for (value in listOf(boundary, outside)) {
                assertEquals(accepted(source, pin, value), accepted(envelope, pin, value))
            }
        }
        assertNull(if (upper) envelope.rows.single().lower else envelope.rows.single().upper)
    }

    @Test
    fun `an absolute envelope should preserve integral declaration acceptance and open sides`() {
        val source = LinearSourceProblem(listOf(LinearSourceVariable("x", true,
            lower = SemanticRational.ONE.takeUnless { upper }, upper = SemanticRational.ONE.takeIf { upper })),
            emptyList(), semantics = SourceSemantics(SourceFormat.MPS, feasibilityTolerance = tolerance).resolve())
        val envelope = assertNotNull(source.absoluteToleranceEnvelope(ExecutionControl.Never))

        for (value in listOf(BigFraction.ZERO, BigFraction.ONE, BigFraction.ofLong(2), slack)) {
            assertEquals(
                checkLinearSourceWitness(source, listOf(value), ExecutionControl.Never) is LinearSourceCheck.Accepted,
                checkLinearSourceWitness(envelope, listOf(value), ExecutionControl.Never) is LinearSourceCheck.Accepted,
            )
        }
        assertNull(if (upper) envelope.variables.single().lower else envelope.variables.single().upper)
    }

    @Test
    fun `a relative tolerance should decline an absolute envelope`() {
        val source = LinearSourceProblem(emptyList(), emptyList(), semantics = SourceSemantics(SourceFormat.MPS,
            feasibilityTolerance = tolerance.copy(relative = SemanticRational.ONE)).resolve())

        assertFailsWith<IllegalArgumentException> { source.absoluteToleranceEnvelope(ExecutionControl.Never) }
    }

    @Test
    fun `an interrupted envelope should publish no source`() {
        val source = LinearSourceProblem(listOf(LinearSourceVariable("x", false)), emptyList(),
            semantics = SourceSemantics(SourceFormat.MPS, feasibilityTolerance = tolerance).resolve())
        var polls = 0

        val envelope = source.absoluteToleranceEnvelope(ExecutionControl { ++polls >= 2 })

        assertNull(envelope)
    }

    @Test
    fun `an exact source should retain its original authority`() {
        val source = LinearSourceProblem(emptyList(), emptyList(), semantics = SourceSemantics(SourceFormat.MPS,
            feasibilityTolerance = SourceTolerance()).resolve())

        assertSame(source, source.absoluteToleranceEnvelope(ExecutionControl.Never))
    }
}
