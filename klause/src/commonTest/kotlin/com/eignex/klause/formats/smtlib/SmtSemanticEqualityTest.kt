package com.eignex.klause.formats.smtlib

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SmtSemanticEqualityTest {
    @Test
    fun `different exact rational spellings compare mathematically`() {
        val parsed = SmtLib.parse("(declare-const a Real) (declare-const b Real)").copy(
            sourceChecks = listOf(SmtSourceCheck { values -> values.equal("a", "b") == true }),
        )

        val model = parsed.reconstructModel({ false }, { "0" }, { if (it == 0) "0.5" else "(/ 1.0 2.0)" })

        assertEquals(2, model.values.size)
    }

    @Test
    fun `native algebraic values compare across different defining polynomials`() {
        val arithmetic = AlgebraicArithmetic()
        val first = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val second = arithmetic.roots(RationalPolynomial.of(-4, 0, 0, 0, 1)).last()
        val sample = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(1.0, 2.0),
            algebraicReals = listOf(first, second))
        val parsed = SmtLib.parse("(declare-const a Real) (declare-const b Real)").copy(
            sourceChecks = listOf(SmtSourceCheck { values -> values.equal("a", "b") == true }),
        )

        val model = parsed.reconstructModel(sample)

        assertTrue((model.values[0].second as SmtValue.Real).term != (model.values[1].second as SmtValue.Real).term)
    }

    @Test
    fun `equal real telemetry does not hide unequal native roots`() {
        val roots = AlgebraicArithmetic().roots(RationalPolynomial.of(-2, 0, 1))
        val sample = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(0.0, 0.0), algebraicReals = roots)
        val parsed = SmtLib.parse("(declare-const a Real) (declare-const b Real)").copy(
            sourceChecks = listOf(SmtSourceCheck { values -> values.equal("a", "b") == false }),
        )

        val model = parsed.reconstructModel(OpenTheoryAssignment.Sampled(sample))

        assertEquals(2, model.values.size)
    }

    @Test
    fun `unavailable native authority does not imply different real values`() {
        val parsed = SmtLib.parse("(declare-const a Real) (declare-const b Real)").copy(
            sourceChecks = listOf(SmtSourceCheck { values ->
                values.equal("a", "b") ?: throw SmtSourceCheckingUnavailable()
            }),
        )

        assertFailsWith<SmtSourceCheckingUnavailable> {
            parsed.reconstructModel({ false }, { "0" }, {
                if (it == 0) "(root-obj (+ (^ x 2) (- 2)) 2)"
                else "(root-obj (+ (^ x 4) (- 4)) 2)"
            })
        }
    }

    @Test
    fun `constructor field comparison uses native real semantics`() {
        val parsed = SmtLib.parse("(declare-const a Real) (declare-const b Real)").copy(
            sourceChecks = listOf(SmtSourceCheck { values ->
                val left = SmtValue.Application("box", listOf(values.value("a")))
                val right = SmtValue.Application("box", listOf(values.value("b")))
                values.equal(left, right) == true
            }),
        )

        val model = parsed.reconstructModel({ false }, { "0" }, { if (it == 0) "0.25" else "(/ 1.0 4.0)" })

        assertEquals(2, model.values.size)
    }

    @Test
    fun `source comparison shares its cumulative witness allowance`() {
        var work = 0L
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = work >= 100
            override fun workMeter(): WorkMeter = WorkMeter { work += it }
        }
        val parsed = SmtLib.parse("(declare-const a Real) (declare-const b Real)").copy(
            sourceChecks = listOf(SmtSourceCheck { values ->
                repeat(100) { values.equal("a", "b") }
                true
            }),
        )

        assertFailsWith<CancellationException> {
            parsed.reconstructModel({ false }, { "0" }, { if (it == 0) "0.5" else "(/ 1.0 2.0)" }, cancellation)
        }
        assertTrue(work >= 100)
    }

    @Test
    fun `unavailable semantic checking withholds native witness authority`() {
        val parsed = SmtLib.parse("(declare-const b Bool)").copy(
            sourceChecks = listOf(SmtSourceCheck { throw SmtSourceCheckingUnavailable() }),
        ).withSourceWitnessValidation()
        val sample = Sample(booleanArrayOf(true), longArrayOf())

        val checked = parsed.source.witnessValidators.single().validate(OpenTheoryAssignment.Sampled(sample),
            Cancellation.Never)

        assertIs<SourceEvidenceCheck.Unavailable>(checked)
    }
}
