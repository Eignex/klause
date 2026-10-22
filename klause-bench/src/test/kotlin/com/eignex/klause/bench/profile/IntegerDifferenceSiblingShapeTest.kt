package com.eignex.klause.bench.profile

import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Cancellation
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class IntegerDifferenceSiblingShapeTest {
    @Test
    fun `parsed difference equality siblings should retain their uncovered gate identity`() {
        for (relation in listOf("=", "distinct")) {
            val parsed = SmtLib.parse("""
                (set-logic QF_LIA)
                (declare-const x Int)
                (declare-const y Int)
                (declare-const p Bool)
                (assert (or p ($relation x y)))
            """.trimIndent())
            val model = parsed.source.originalSource as Problem

            val shape = IntegerDifferenceSiblingShape.inspect(model, Cancellation.Never)

            assertEquals(1, shape.getValue("matchedSplits").jsonPrimitive.int)
            assertEquals(1, shape.getValue("uncoveredSplits").jsonPrimitive.int)
            assertEquals(1, shape.getValue("examples").jsonArray.size)
            assertFalse(shape.getValue("solverExecuted").jsonPrimitive.boolean)
            assertFalse(shape.getValue("preparationExecuted").jsonPrimitive.boolean)
            assertFalse(shape.getValue("sourceVerdictClaim").jsonPrimitive.boolean)
        }
    }

    @Test
    fun `an existing sibling coverage clause should be reported separately`() {
        val parsed = SmtLib.parse("""
            (set-logic QF_LIA)
            (declare-const x Int)
            (declare-const y Int)
            (declare-const p Bool)
            (assert (or p (= x y)))
        """.trimIndent())
        val original = parsed.source.originalSource as Problem
        val uncovered = IntegerDifferenceSiblingShape.inspect(original, Cancellation.Never)
        val example = uncovered.getValue("examples").jsonArray.single().jsonObject
        val model = Problem(original.numBoolVars, original.numIntVars,
            Array(original.numIntVars) { IntDomain(0, 0) },
            original.factors.toList() + Clause(intArrayOf(
                example.getValue("atMostLiteral").jsonPrimitive.int,
                example.getValue("atLeastLiteral").jsonPrimitive.int,
            )))

        val shape = IntegerDifferenceSiblingShape.inspect(model, Cancellation.Never)

        assertEquals(1, shape.getValue("matchedSplits").jsonPrimitive.int)
        assertEquals(1, shape.getValue("coveredSplits").jsonPrimitive.int)
        assertEquals(0, shape.getValue("uncoveredSplits").jsonPrimitive.int)
    }

    @Test
    fun `either target polarity should retain the same sibling recognition`() {
        for (positive in listOf(false, true)) {
            val target = Lit.make(2, positive)
            val most = Lit.make(0, true)
            val least = Lit.make(1, true)
            val model = Problem(3, 2, Array(2) { IntDomain(0, 0) }, listOf(
                ReifiedLinear(0, longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, Long.MIN_VALUE),
                ReifiedLinear(1, longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.GE, Long.MIN_VALUE),
                Clause(intArrayOf(Lit.negate(target), most)),
                Clause(intArrayOf(Lit.negate(target), least)),
                Clause(intArrayOf(target, Lit.negate(most), Lit.negate(least))),
            ))

            val shape = IntegerDifferenceSiblingShape.inspect(model, Cancellation.Never)

            assertEquals(1, shape.getValue("uncoveredSplits").jsonPrimitive.int)
            assertEquals(target, shape.getValue("examples").jsonArray.single().jsonObject
                .getValue("targetLiteral").jsonPrimitive.int)
        }
    }

    @Test
    fun `different bounds operators and coefficients should not form a matching split`() {
        for ((op, bound, coefficient) in listOf(
            Triple(LinearOp.GE, 4, -1), Triple(LinearOp.LE, 3, -1), Triple(LinearOp.GE, 3, 1),
        )) {
            val target = Lit.make(2, true)
            val most = Lit.make(0, true)
            val least = Lit.make(1, true)
            val model = Problem(3, 2, Array(2) { IntDomain(0, 0) }, listOf(
                ReifiedLinear(0, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 3),
                ReifiedLinear(1, intArrayOf(1, coefficient), intArrayOf(0, 1), op, bound),
                Clause(intArrayOf(Lit.negate(target), most)),
                Clause(intArrayOf(Lit.negate(target), least)),
                Clause(intArrayOf(target, Lit.negate(most), Lit.negate(least))),
            ))

            val shape = IntegerDifferenceSiblingShape.inspect(model, Cancellation.Never)

            assertEquals(0, shape.getValue("matchedSplits").jsonPrimitive.int)
        }
    }

    @Test
    fun `unrelated order disjunctions should not be counted as equality gates`() {
        val parsed = SmtLib.parse("""
            (set-logic QF_LIA)
            (declare-const x Int)
            (declare-const y Int)
            (declare-const p Bool)
            (assert (or p (<= x y) (>= x y)))
        """.trimIndent())
        val model = parsed.source.originalSource as Problem

        val shape = IntegerDifferenceSiblingShape.inspect(model, Cancellation.Never)

        assertEquals(0, shape.getValue("matchedSplits").jsonPrimitive.int)
    }

    @Test
    fun `containing cancellation should withhold even an empty inspection`() {
        val model = Problem(0, 0, emptyArray(), emptyList())

        assertFailsWith<CancellationException> {
            IntegerDifferenceSiblingShape.inspect(model, Cancellation { true })
        }
    }
}
