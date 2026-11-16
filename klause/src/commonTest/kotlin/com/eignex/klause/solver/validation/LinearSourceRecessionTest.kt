package com.eignex.klause.solver.validation

import com.eignex.klause.ir.LinearSourceObjective
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.LinearSourceRow
import com.eignex.klause.ir.LinearSourceTerm
import com.eignex.klause.ir.LinearSourceVariable
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.bigIntOf
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LinearSourceRecessionTest {
    @Test
    fun `a decimal ray keeping every active row should prove recession`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", false, lower = SemanticRational.ZERO),
                LinearSourceVariable("y", false, lower = SemanticRational.ZERO)),
            listOf(LinearSourceRow(listOf(term(0, 1, 10), term(1, 1, 1)), lower = decimal(3, 10))),
            LinearSourceObjective(listOf(term(0, -1, 1))),
        )
        val base = witness(source, BigFraction.ZERO, fraction(3, 10))

        assertTrue(checkLinearSourceRecession(base, listOf(BigFraction.ONE, BigFraction.ZERO), ExecutionControl.Never))
    }

    @Test
    fun `a ray whose row slope is negative only in exact decimals should prove no recession`() {
        val source = LinearSourceProblem(
            listOf("x", "y", "z").map { LinearSourceVariable(it, false, lower = SemanticRational.ZERO) },
            listOf(
                LinearSourceRow(listOf(term(0, 1, 10), term(1, 2, 10),
                    LinearSourceTerm(2, SemanticRational.of(bigIntOf(-30_000_000_000_000_001L),
                        bigIntOf(100_000_000_000_000_000L)))), lower = SemanticRational.ZERO),
                LinearSourceRow(listOf(term(0, 1, 1), term(2, -1, 1)), SemanticRational.ZERO, SemanticRational.ZERO),
                LinearSourceRow(listOf(term(1, 1, 1), term(2, -1, 1)), SemanticRational.ZERO, SemanticRational.ZERO),
            ),
            LinearSourceObjective(listOf(term(2, -1, 1))),
        )
        val base = witness(source, BigFraction.ZERO, BigFraction.ZERO, BigFraction.ZERO)
        val ray = listOf(BigFraction.ONE, BigFraction.ONE, BigFraction.ONE)

        val leveled = levelLinearSourceRay(base, ray, ExecutionControl.Never)

        assertFalse(checkLinearSourceRecession(base, ray, ExecutionControl.Never))
        assertFalse(checkLinearSourceRecession(base, assertNotNull(leveled), ExecutionControl.Never))
    }

    @Test
    fun `a ray level only in binary64 should be leveled onto the exact source row`() {
        val source = LinearSourceProblem(
            listOf("x", "y", "z").map { LinearSourceVariable(it, false, lower = SemanticRational.ZERO) },
            listOf(LinearSourceRow(listOf(term(0, 1, 10), term(1, 2, 10), term(2, -3, 10)),
                SemanticRational.ZERO, SemanticRational.ZERO)),
            LinearSourceObjective(listOf(term(2, -1, 1))),
        )
        val base = witness(source, BigFraction.ZERO, BigFraction.ZERO, BigFraction.ZERO)
        val projected = assertNotNull(BigFraction.ofDouble(0.3)) * assertNotNull(BigFraction.ofDouble(0.1)).reciprocal()
        val ray = listOf(projected, BigFraction.ZERO, BigFraction.ONE)

        val leveled = assertNotNull(levelLinearSourceRay(base, ray, ExecutionControl.Never))

        assertFalse(checkLinearSourceRecession(base, ray, ExecutionControl.Never))
        assertTrue(checkLinearSourceRecession(base, leveled, ExecutionControl.Never))
    }

    private fun witness(source: LinearSourceProblem, vararg values: BigFraction): LinearSourceWitness =
        assertIs<LinearSourceCheck.Accepted>(
            checkLinearSourceWitness(source, values.toList(), ExecutionControl.Never),
        ).witness

    private fun term(column: Int, numerator: Long, denominator: Long): LinearSourceTerm =
        LinearSourceTerm(column, decimal(numerator, denominator))

    private fun decimal(numerator: Long, denominator: Long): SemanticRational =
        SemanticRational.of(bigIntOf(numerator), bigIntOf(denominator))

    private fun fraction(numerator: Long, denominator: Long): BigFraction =
        BigFraction.ofLong(numerator) * BigFraction.ofLong(denominator).reciprocal()
}
