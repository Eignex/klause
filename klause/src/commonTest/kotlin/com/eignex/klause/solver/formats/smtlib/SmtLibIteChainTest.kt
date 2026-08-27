package com.eignex.klause.solver.formats.smtlib

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.table.Element
import com.eignex.klause.formats.smtlib.*
import com.eignex.klause.lowering.smtlib.SmtLib
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Collapse of `ite`-on-equality chains into [Element]. Every case checks the collapsed model against
 * the chain's own semantics over the selector's *whole* domain — including the values outside the arm
 * constants' range, where the chain takes its default and the array must still answer.
 */
class SmtLibIteChainTest {

    private companion object {
        /** Selector domain, deliberately wider than the arm constants below. */
        const val SEL_LO = 0
        const val SEL_HI = 25

        /** Arm constants: 16 keys, the shortest chain the collapse accepts. */
        const val KEY_LO = 5
        const val KEY_HI = 20

        val KEYS = (KEY_LO..KEY_HI).toList()
    }

    /** `(ite (= s k0) a0 (ite (= s k1) a1 … default))` over [KEYS], arms given by [arm]. */
    private fun chain(default: String, arm: (Int) -> String): String =
        KEYS.foldRight(default) { k, rest -> "(ite (= s $k) ${arm(k)} $rest)" }

    /** A model asserting `r = <chain>` with `s` ranging over `[SEL_LO, SEL_HI]`. */
    private fun model(body: String, extraDecls: String = ""): String =
        """
        (declare-const s Int) (declare-const r Int)
        $extraDecls
        (assert (>= s $SEL_LO)) (assert (<= s $SEL_HI))
        (assert (>= r -10000)) (assert (<= r 10000))
        (assert (= r $body))
        (check-sat)
        """.trimIndent()

    /** The value `r` takes for each `s` in `[SEL_LO, SEL_HI]`, read off a solution with `s` pinned. */
    private fun resultPerSelector(text: String): List<Long> {
        val parsed = SmtLib.parse(text)
        val problem = parsed.model.materializeFiniteBounds()
        val s = parsed.intVarNames.getValue("s")
        val r = parsed.intVarNames.getValue("r")
        return (SEL_LO..SEL_HI).map { v ->
            val domains = Array(problem.numIntVars) { i ->
                if (i == s) IntDomain(v.toLong(), v.toLong()) else problem.requireFiniteIntDomains()[i]
            }
            val solved = BacktrackSolver(problem.withIntDomains(domains).bake()).solve(BacktrackParams())
            assertIs<SolveResult.Sat>(solved, "no solution for s = $v")
            solved.assignment.ints[r]
        }
    }

    private fun elementCount(text: String): Int = SmtLib.parse(text).model.factors.count { it is Element }

    @Test
    fun `a chain of equality tests should collapse to one element factor`() {
        assertEquals(1, elementCount(model(chain("s") { "${it * 10}" })))
    }

    @Test
    fun `a collapsed chain should answer every selector value with the arm the chain selects`() {
        val results = resultPerSelector(model(chain("s") { "${it * 10}" }))

        assertEquals((SEL_LO..SEL_HI).map { if (it in KEYS) it * 10L else it.toLong() }, results)
    }

    @Test
    fun `a collapsed chain should answer a constant default outside the arm constants`() {
        val text = model(chain("77") { "${it * 10}" })
        val results = resultPerSelector(text)

        assertEquals(1, elementCount(text))
        assertEquals((SEL_LO..SEL_HI).map { if (it in KEYS) it * 10L else 77L }, results)
    }

    @Test
    fun `a chain written with negated equality tests should answer as the positive form does`() {
        val text = model(
            KEYS.foldRight("s") { k, rest -> "(ite (not (= s $k)) $rest ${k * 10})" },
        )
        val results = resultPerSelector(text)

        assertEquals(1, elementCount(text))
        assertEquals((SEL_LO..SEL_HI).map { if (it in KEYS) it * 10L else it.toLong() }, results)
    }

    @Test
    fun `a chain whose constants are written as sums should still be recognised`() {
        val text = model(KEYS.foldRight("s") { k, rest -> "(ite (= s (+ $k 0)) ${k * 10} $rest)" })

        assertEquals(1, elementCount(text))
        assertEquals((SEL_LO..SEL_HI).map { if (it in KEYS) it * 10L else it.toLong() }, resultPerSelector(text))
    }

    @Test
    fun `a chain built through let bindings should answer as the inline form does`() {
        val bindings = KEYS.joinToString(" ") { "(c$it (= s (+ $it 0)))" }
        val body = KEYS.foldRight("s") { k, rest -> "(ite c$k ${k * 10} $rest)" }
        val text = model("(let ($bindings) $body)")

        assertEquals(1, elementCount(text))
        assertEquals((SEL_LO..SEL_HI).map { if (it in KEYS) it * 10L else it.toLong() }, resultPerSelector(text))
    }

    @Test
    fun `a chain mixing constant and variable arms should answer with the selected arm`() {
        val text = model(
            chain("s") { if (it % 2 == 0) "${it * 10}" else "x" },
            extraDecls = "(declare-const x Int) (assert (= x 3))",
        )
        val results = resultPerSelector(text)

        assertEquals(1, elementCount(text))
        assertEquals(
            (SEL_LO..SEL_HI).map {
                when {
                    it !in KEYS -> it.toLong()
                    it % 2 == 0 -> it * 10L
                    else -> 3L
                }
            },
            results,
        )
    }

    @Test
    fun `a chain shorter than the trigger should stay a decision list`() {
        val short = (KEY_LO until KEY_LO + 15).toList()
        val text = model(short.foldRight("s") { k, rest -> "(ite (= s $k) ${k * 10} $rest)" })
        val results = resultPerSelector(text)

        assertEquals(0, elementCount(text))
        assertEquals((SEL_LO..SEL_HI).map { if (it in short) it * 10L else it.toLong() }, results)
    }

    @Test
    fun `a chain whose constants are too sparse for a table should stay a decision list`() {
        val sparse = KEYS.map { it * 100 }
        val text = """
            (declare-const s Int) (declare-const r Int)
            (assert (>= s 0)) (assert (<= s 2100))
            (assert (= r ${sparse.foldRight("s") { k, rest -> "(ite (= s $k) ${k + 1} $rest)" }}))
            (check-sat)
        """.trimIndent()

        assertEquals(0, elementCount(text))
    }

    @Test
    fun `a chain on an unbounded selector should stay a decision list`() {
        val text = """
            (declare-const s Int) (declare-const r Int)
            (assert (= r ${chain("s") { "${it * 10}" }}))
            (check-sat)
        """.trimIndent()

        assertEquals(0, elementCount(text))
    }

    @Test
    fun `a chain repeating an arm constant should keep the first arm that matches`() {
        val text = model("(ite (= s 7) 1 ${chain("s") { if (it == 7) "2" else "${it * 10}" }})")
        val expected = (SEL_LO..SEL_HI).map {
            when {
                it == 7 -> 1L
                it in KEYS -> it * 10L
                else -> it.toLong()
            }
        }

        assertEquals(expected, resultPerSelector(text))
    }
}
