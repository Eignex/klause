package com.eignex.klause.formats.opb

import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.ir.Lit
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpbDecoderTest {

    @Test
    fun `rejects unrepresentable normalized objective coefficients`() {
        val objectives = listOf(
            "${Long.MAX_VALUE} x1 ${Long.MAX_VALUE} x1",
            "${Long.MIN_VALUE} x1 -1 x1",
            "${Long.MIN_VALUE} ~x1",
            "${Long.MAX_VALUE} x1 -1 ~x1",
            "${Long.MIN_VALUE} x1 1 ~x1",
            "${Long.MAX_VALUE} x1 x2 1 x2 x1",
        )

        for (terms in objectives) {
            val document = Opb.parse("min: $terms ;")

            val error = assertFailsWith<OpbLoweringException>(terms) { document.toProblem() }

            assertTrue("objective coefficient" in error.message.orEmpty(), terms)
        }
    }

    @Test
    fun `preserves supported coefficient and offset boundaries`() {
        val objectives = listOf(
            Triple("${Long.MAX_VALUE} x1", Long.MAX_VALUE, 0L),
            Triple("${Long.MIN_VALUE} x1", Long.MIN_VALUE, 0L),
            Triple("${Long.MAX_VALUE} ~x1", -Long.MAX_VALUE, Long.MAX_VALUE),
            Triple("${Long.MIN_VALUE} ~x1 -1 x1", Long.MAX_VALUE, Long.MIN_VALUE),
            Triple("${Long.MAX_VALUE} x1 -1 x1", Long.MAX_VALUE - 1, 0L),
            Triple("${Long.MIN_VALUE} x1 1 x1", Long.MIN_VALUE + 1, 0L),
        )

        for ((terms, coefficient, constant) in objectives) {
            val document = Opb.parse("min: $terms ;")

            val objective = assertNotNull(document.toProblem().objective)

            assertEquals(coefficient, objective.boolWeights.single(), terms)
            assertEquals(constant, objective.constant, terms)
        }
    }

    @Test
    fun `normalizes cancelling repeated terms before checking range`() {
        val objectives = listOf(
            Triple("${Long.MAX_VALUE} x1 1 x1 -1 x1", Long.MAX_VALUE, 0L),
            Triple("${Long.MIN_VALUE} x1 -1 x1 1 x1", Long.MIN_VALUE, 0L),
            Triple("9223372036854775808 x1 -1 x1", Long.MAX_VALUE, 0L),
            Triple("-9223372036854775809 x1 1 x1", Long.MIN_VALUE, 0L),
            Triple("9223372036854775808 x1 -9223372036854775808 x1", 0L, 0L),
            Triple("${Long.MAX_VALUE} ~x1 1 ~x1 -1 ~x1", -Long.MAX_VALUE, Long.MAX_VALUE),
            Triple("${Long.MIN_VALUE} ~x1 -1 ~x1 1 ~x1 -1 x1", Long.MAX_VALUE, Long.MIN_VALUE),
            Triple("9223372036854775808 ~x1 -1 ~x1", -Long.MAX_VALUE, Long.MAX_VALUE),
            Triple("${Long.MAX_VALUE} x1 ${Long.MAX_VALUE} ~x1", 0L, Long.MAX_VALUE),
            Triple("${Long.MIN_VALUE} ~x1 ${Long.MIN_VALUE} x1", 0L, Long.MIN_VALUE),
        )

        for ((terms, coefficient, constant) in objectives) {
            val document = Opb.parse("min: $terms ;")

            val objective = assertNotNull(document.toProblem().objective)

            assertEquals(coefficient, objective.boolWeights.single(), terms)
            assertEquals(constant, objective.constant, terms)
        }
    }

    @Test
    fun `rejects unrepresentable normalized objective offsets`() {
        val objectives = listOf(
            "min: ${Long.MAX_VALUE} ~x1 1 ~x2 ;",
            "min: ${Long.MIN_VALUE} ~x1 -1 x1 -1 ~x2 ;",
            "[${Long.MAX_VALUE}] 1 x1 >= 1 ; [1] 1 x2 >= 1 ;",
            "min: 1 ~x1 ; [${Long.MAX_VALUE}] 1 x2 >= 1 ;",
        )

        for (text in objectives) {
            val document = Opb.parse(text)

            val error = assertFailsWith<OpbLoweringException>(text) { document.toProblem() }

            assertTrue("objective constant" in error.message.orEmpty(), text)
        }
    }

    @Test
    fun `combines objective statements before checking range`() {
        val document = Opb.parse("min: 9223372036854775808 ~x1 ; min: -1 ~x1 ;")

        val objective = assertNotNull(document.toProblem().objective)

        assertEquals(-Long.MAX_VALUE, objective.boolWeights.single())
        assertEquals(Long.MAX_VALUE, objective.constant)
    }

    @Test
    fun `preserves soft cost boundaries`() {
        for (cost in listOf(0L, Long.MAX_VALUE)) {
            val document = Opb.parse("[$cost] 1 x1 >= 1 ;")

            val lowered = document.toProblem()

            val objective = assertNotNull(lowered.objective)
            val soft = lowered.problem.factors.filterIsInstance<ReifiedPseudoBoolean>().single()
            assertEquals(-cost, objective.boolWeights[soft.auxBoolVar])
            assertEquals(cost, objective.constant)
        }
    }

    @Test
    fun `shares exact product costs and agrees with source objectives for every assignment`() {
        val document = Opb.parse(
            "min: ${Long.MAX_VALUE} x1 ~x2 1 ~x2 x1 -${Long.MAX_VALUE} x1 ~x2 " +
                "${Long.MIN_VALUE} ~x1 -1 x1 ;",
        )

        val lowered = document.toProblem()

        assertEquals(1, lowered.boolFolds.size)
        val objective = assertNotNull(lowered.objective)
        for (mask in 0 until 4) {
            val assignment = BooleanArray(lowered.problem.numBoolVars) { (mask and (1 shl it)) != 0 }
            for (fold in lowered.boolFolds) {
                assignment[fold.out] = fold.lits.all { Lit.evaluate(it, assignment[Lit.variable(it)]) }
            }
            var sourceValue = BIG_ZERO
            for (statement in document.statements.filterIsInstance<OpbStatement.Objective>()) {
                for (term in statement.terms) {
                    if (term.literals.all { Lit.evaluate(it, assignment[Lit.variable(it)]) }) {
                        sourceValue += term.coefficient
                    }
                }
            }
            var loweredValue = bigIntOf(objective.constant)
            objective.boolWeights.forEachIndexed { variable, weight ->
                if (assignment[variable]) loweredValue += bigIntOf(weight)
            }

            assertEquals(sourceValue, loweredValue, "assignment $mask")
        }
    }

    @Test
    fun `preserves soft penalty values after exact offset cancellation`() {
        val document = Opb.parse(
            "[${Long.MAX_VALUE}] 1 x1 >= 1 ; [1] 1 x2 >= 1 ; " +
                "min: -1 ~x1 ;",
        )

        val lowered = document.toProblem()

        val objective = assertNotNull(lowered.objective)
        assertEquals(Long.MAX_VALUE, objective.constant)
        val soft = lowered.problem.factors.filterIsInstance<ReifiedPseudoBoolean>()
        for (mask in 0 until 4) {
            val x1 = (mask and 1) != 0
            val x2 = (mask and 2) != 0
            val assignment = BooleanArray(lowered.problem.numBoolVars)
            assignment[0] = x1
            assignment[1] = x2
            assignment[soft[0].auxBoolVar] = x1
            assignment[soft[1].auxBoolVar] = x2
            var loweredValue = bigIntOf(objective.constant)
            objective.boolWeights.forEachIndexed { variable, weight ->
                if (assignment[variable]) loweredValue += bigIntOf(weight)
            }
            val sourceValue = bigIntOf(if (x1) 0L else Long.MAX_VALUE - 1) + bigIntOf(if (x2) 0 else 1)

            assertEquals(sourceValue, loweredValue, "assignment $mask")
        }
    }
}
