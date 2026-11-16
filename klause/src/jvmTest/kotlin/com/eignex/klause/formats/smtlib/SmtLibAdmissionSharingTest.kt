package com.eignex.klause.formats.smtlib

import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class SmtLibAdmissionSharingTest(private val sort: String, private val helper: Boolean, private val levels: Int) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "sort={0}, helper={1}, levels={2}")
        fun cases(): List<Array<Any>> = listOf("Bool", "Int", "Real").flatMap { sort ->
            listOf(false, true).flatMap { helper ->
                listOf(4, 8).map { levels -> arrayOf(sort, helper, levels) }
            }
        }
    }

    @Test
    fun `shared scalar arguments should keep Boolean variables and factors proportional to source size`() {
        val text = buildString {
            append("(declare-const x $sort) ")
            repeat(levels) { append("(declare-const p$it Bool) ") }
            if (helper) append("(define-fun choose ((p Bool) (b Bool)) Bool (ite p b (not b))) ")
            append("(assert ")
            if (helper) {
                repeat(levels) { append("(choose p$it ") }
                append(if (sort == "Bool") "x" else "(< x 1)")
                repeat(levels) { append(')') }
            } else {
                append("(let ((b0 ${if (sort == "Bool") "x" else "(< x 1)"})) ")
                for (level in 1..levels) {
                    append("(let ((b$level (ite p${level - 1} b${level - 1} (not b${level - 1})))) ")
                }
                append("b$levels")
                repeat(levels + 1) { append(')') }
            }
            append(')')
        }

        val model = SmtLib.parse(text).model

        assertTrue(model.numBoolVars <= levels * 8 + 2,
            "Shared scalar source created ${model.numBoolVars} Boolean variables")
        assertTrue(model.factors.size <= levels * 24 + 4,
            "Shared scalar source created ${model.factors.size} factors")
    }
}
