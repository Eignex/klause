package com.eignex.klause.solver.incumbent

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.QuadraticEngineInvocation
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.checkWitness
import com.eignex.klause.solver.objective.evaluateExact
import com.eignex.klause.solver.result.QuadraticIncumbent

internal class QuadraticWitnessPublication(
    val invocation: QuadraticEngineInvocation,
    val incumbent: QuadraticIncumbent,
) {
    fun verify(
        expected: QuadraticEngineInvocation,
        candidate: QuadraticIncumbent,
        arithmetic: AlgebraicArithmetic,
        sourceChecker: (Sample) -> SourceEvidenceCheck,
    ): SourceEvidenceCheck = try {
        arithmetic.arithmetic.step()
        when {
            !invocation.sameSourceObjective(expected) ->
                SourceEvidenceCheck.Rejected("quadratic witness belongs to another source objective or direction")
            !incumbent.sample.equals(candidate.sample) ->
                SourceEvidenceCheck.Rejected("quadratic witness differs from the actual published point")
            arithmetic.compare(incumbent.value, candidate.value) != 0 ->
                SourceEvidenceCheck.Rejected("quadratic witness differs from the actual published value")
            else -> checkPoint(expected, candidate, arithmetic, sourceChecker)
        }.also { arithmetic.arithmetic.step() }
    } catch (_: PolynomialInterrupted) {
        SourceEvidenceCheck.Unavailable("quadratic witness checking interrupted")
    } catch (_: PolynomialResourceLimit) {
        SourceEvidenceCheck.Unavailable("quadratic witness checking exhausted its arithmetic allowance")
    }

    private fun checkPoint(
        expected: QuadraticEngineInvocation,
        candidate: QuadraticIncumbent,
        arithmetic: AlgebraicArithmetic,
        sourceChecker: (Sample) -> SourceEvidenceCheck,
    ): SourceEvidenceCheck {
        for (condition in listOf(invocation.condition, expected.condition)) {
            val checked = condition.checkWitness(candidate.sample, arithmetic)
            if (checked != SourceEvidenceCheck.Checked) return checked
        }
        val checked = sourceChecker(candidate.sample)
        if (checked != SourceEvidenceCheck.Checked) return checked
        val value = expected.objective.evaluateExact(candidate.sample, arithmetic)
        return if (arithmetic.compare(value, candidate.value) == 0) SourceEvidenceCheck.Checked
            else SourceEvidenceCheck.Rejected("quadratic witness value differs from its original source objective")
    }
}
