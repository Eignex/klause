package com.eignex.klause.solver.formats.smtlib

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.formats.FormatException
import com.eignex.klause.formats.smtlib.*
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.formats.smtlib.SmtLibProblem
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.solver.pipeline.OpenTheoryEngine
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.ProblemPipeline
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.solver.pipeline.componentPlan
import com.eignex.klause.solver.pipeline.sourceRoute
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.theory.qflra.ExactLiraAssignment
import com.eignex.klause.theory.qflra.ExactLraAssignment
import com.eignex.klause.theory.qflra.supportsExactLra
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import com.eignex.klause.util.toLongExact
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SmtLibTest {

    private fun openSolve(
        model: com.eignex.klause.ir.Problem,
        params: TheoryParams = TheoryParams(),
    ): OpenTheoryResult = OpenTheoryEngine(model, model.componentPlan().theoryPipeline).solve(params)

    private fun liraSat(
        model: com.eignex.klause.ir.Problem,
        params: TheoryParams = TheoryParams(),
    ): ExactLiraAssignment {
        val sat = assertIs<OpenTheoryResult.Sat>(openSolve(model, params))
        return assertIs<OpenTheoryAssignment.ExactLira>(sat.assignment).assignment
    }

    private fun liraUnsat(model: com.eignex.klause.ir.Problem, params: TheoryParams = TheoryParams()) {
        assertIs<OpenTheoryResult.Unsat>(openSolve(model, params))
    }

    private fun lraSat(model: com.eignex.klause.ir.Problem): ExactLraAssignment {
        val sat = assertIs<OpenTheoryResult.Sat>(openSolve(model))
        return assertIs<OpenTheoryAssignment.ExactLra>(sat.assignment).assignment
    }

    /**
     * The witness of an open solve, read column by column.
     *
     * Values come through the interface rather than out of a route's own arrays: source presolve may
     * eliminate a column, in which case the reconstruction holds its value and the route underneath still
     * has the one it had before; and a model the passes simplify can take a cheaper route than the one it
     * declared, so the concrete witness type is not the caller's to assume. A test that means to pin a
     * route asserts `sourceRoute()` on the parsed model.
     */
    private fun liaSat(model: com.eignex.klause.ir.Problem): ExactLiraAssignment {
        val witness = assertIs<OpenTheoryResult.Sat>(openSolve(model)).assignment
        return ExactLiraAssignment(
            bools = BooleanArray(model.numBoolVars) { witness.boolValue(it) },
            ints = Array(model.numIntVars) { parseBigInt(witness.intValue(it)) },
            reals = emptyList(),
        )
    }

    private fun differenceSat(model: com.eignex.klause.ir.Problem): Sample {
        val sat = assertIs<OpenTheoryResult.Sat>(openSolve(model))
        return assertIs<OpenTheoryAssignment.Difference>(sat.assignment).sample
    }

    private fun solve(text: String): LongArray {
        val parsed = SmtLib.parse(text)
        if (parsed.model.sourceRoute() == ProblemPipeline.EXACT_LIRA) {
            val assignment = liaSat(parsed.model)
            return LongArray(parsed.intVarNames.values.maxOrNull()?.plus(1) ?: 0) { v ->
                assignment.ints[v].toLongExact()
            }
        }
        if (parsed.model.sourceRoute() == ProblemPipeline.DIFFERENCE_THEORY) {
            return differenceSat(parsed.model).ints
        }
        val r = BacktrackSolver(parsed.bounded().bake()).solve(BacktrackParams())
        assertTrue(r is SolveResult.Sat, "expected SAT, got $r")
        return r.assignment.ints
    }

    private fun SmtLibProblem.bounded(): Problem = model.bake()

    @Test
    fun `open QF LIRA returns an exact mixed witness`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LIRA)
                (declare-const x Int) (declare-const y Real)
                (assert (= y (+ (to_real x) (/ 1.0 3.0))))
                (check-sat)
            """.trimIndent(),
        )

        assertEquals(ProblemPipeline.EXACT_LIRA, parsed.model.sourceRoute())
        val result = liraSat(parsed.model)

        val x = result.ints[parsed.intVarNames.getValue("x")]
        val y = result.reals[parsed.realVarNames.getValue("y")]
        assertEquals("1/3", (y - BigFraction.of(x, BIG_ONE)).toString())
    }

    @Test
    fun `open QF LIRA retains a BigInt branch bound`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LIRA)
                (declare-const x Int) (declare-const y Real)
                (assert (= (to_real x) y))
                (assert (= y (/ 2305843009213693953.0 2.0)))
                (check-sat)
            """.trimIndent(),
        )

        assertEquals(ProblemPipeline.EXACT_LIRA, parsed.model.sourceRoute())
        liraUnsat(parsed.model)
    }

    @Test
    fun `open QF LRA equality returns an exact rational witness`() {
        val parsed = SmtLib.parse(
            "(set-logic QF_LRA) (declare-const x Real) (assert (= x (/ 1.0 3.0))) (check-sat)",
        )

        assertTrue(parsed.model.supportsExactLra())
        val result = lraSat(parsed.model)

        assertEquals("1/3", result.reals[parsed.realVarNames.getValue("x")].toString())
    }

    @Test
    fun `a bitvector literal is rejected as outside the integer-only fragment`() {
        val text = "(declare-const x Int) (assert (= x #xFF))"
        val e = assertFailsWith<UnsupportedSmtException> { SmtLib.parse(text) }
        assertTrue("bitvector literal" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test
    fun `parses objective and finds optimum`() {
        val text = """
            (declare-const x Int) (declare-const y Int)
            (assert (>= x 0)) (assert (<= x 10)) (assert (>= y 0)) (assert (<= y 10))
            (assert (<= (+ x y) 10))
            (assert (or (>= x 7) (>= y 7)))
            (minimize (+ x y))
        """.trimIndent()
        val parsed = SmtLib.parse(text)
        val obj = requireNotNull(parsed.objective)
        val r = BacktrackSolver(parsed.bounded().bake()).minimize(obj.toLinearObjective(), BacktrackParams())
        assertTrue(r is MinimizeResult.Optimal, "expected Optimal, got $r")
        assertEquals(7.0, r.objective)
    }

    @Test
    fun `let bindings expand with scoped shadowing`() {
        val text = """
            (declare-const x Int) (declare-const y Int)
            (assert (>= x 0)) (assert (>= y 0))
            (assert (let ((s (+ x y))) (and (<= s 10) (let ((s (* 2 x))) (>= s 4)))))
            (check-sat)
        """.trimIndent()
        val ints = solve(text)
        val x = ints[0]
        val y = ints[1]
        assertTrue(x + y <= 10 && 2 * x >= 4, "x=$x y=$y")
    }

    @Test
    fun `a deeply nested let chain compiles without overflowing the stack`() {
        // Machine-generated SMT nests thousands of lets in tail position. Compilation must
        // unwind the chain iteratively (heap-allocated scope stack), not recurse per let.
        val depth = 10_000
        val body = StringBuilder("(declare-const x Int)\n(assert ")
        val close = StringBuilder()
        for (i in 0 until depth) {
            val v = if (i == 0) "(>= x 0)" else "b${i - 1}"
            body.append("(let ((b$i $v)) ")
            close.append(')')
        }
        body.append("b${depth - 1}").append(close).append(")\n(check-sat)")
        // Parses and compiles (no StackOverflowError); x >= 0 is asserted through the chain.
        assertTrue(solve(body.toString())[0] >= 0)
    }

    @Test
    fun `a strict real interval solves to a point strictly inside`() {
        val parsed = SmtLib.parse(
            "(declare-const x Real) (assert (< x 2.5)) (assert (> x 2.4)) (check-sat)",
        )
        val r = BacktrackSolver(parsed.bounded().bake()).solve(BacktrackParams())
        assertTrue(r is SolveResult.Sat, "expected SAT, got $r")
        val x = r.assignment.reals[0]
        assertTrue(x > 2.4 && x < 2.5, "x=$x must sit strictly inside (2.4, 2.5)")
    }

    @Test
    fun `a strict boundary conflict over reals is unsat`() {
        val parsed = SmtLib.parse(
            "(declare-const x Real) (assert (< x 2.0)) (assert (>= x 2.0)) (check-sat)",
        )
        val r = BacktrackSolver(parsed.bounded().bake()).solve(BacktrackParams())
        assertTrue(r is SolveResult.Unsat, "expected UNSAT, got $r")
    }

    @Test
    fun `an open mixed integer-real model is not materialized for CP`() {
        val parsed = SmtLib.parse(
            """
            (declare-const r Real) (declare-const n Int)
            (assert (= r 2.5)) (assert (>= n 2)) (assert (<= n 2)) (assert (= n (to_int r)))
            (check-sat)
            """.trimIndent(),
        )
        assertEquals(ProblemPipeline.EXACT_LIRA, parsed.model.sourceRoute())
    }

    @Test
    fun `a fully bounded model remains finite`() {
        val parsed = SmtLib.parse(
            "(declare-fun x () Int) (assert (>= x 0)) (assert (<= x 5)) (assert (>= x 8)) (check-sat)",
        )
        assertEquals(ProblemPipeline.FINITE_CP, parsed.model.sourceRoute())
    }

    @Test
    fun `constant folding overflow promotes to a wide value instead of wrapping`() {
        // SMT integers are unbounded; 2^63-1 + 1 = 2^63 overflows Long, so it is carried as a wide value
        // (never silently wrapped to Long.MIN). x = 2^63 has no Long domain to live in, so x is lowered
        // onto digit columns and the witness reads back exactly 2^63.
        val text = "(declare-const x Int) (assert (= x (+ 9223372036854775807 1))) (check-sat)"
        assertEquals(parseBigInt("9223372036854775808"), soleIntValue(text))
    }

    /** The value of the single declared int in [text], read off its digit columns when it has them. */
    private fun soleIntValue(text: String): BigInt {
        val parsed = SmtLib.parse(text)
        if (parsed.model.sourceRoute() == ProblemPipeline.EXACT_LIRA) {
            return liaSat(parsed.model).ints[parsed.intVarNames.values.first()]
        }
        val r = BacktrackSolver(parsed.model.bake()).solve(BacktrackParams())
        assertTrue(r is SolveResult.Sat, "expected SAT, got $r")
        return bigIntOf(r.assignment.ints[parsed.intVarNames.values.first()])
    }

    @Test
    fun `structural s-expression errors surface as a format exception`() {
        // An unbalanced or unterminated token is a structural parse failure; it must surface through
        // the catchable FormatException supertype, not a raw IllegalArgumentException from require{}.
        val malformed = listOf(
            "(declare-const x Int) (assert (>= x 0",
            ")",
            "(declare-const |x",
            "(assert \"unterminated",
        )
        for (text in malformed) {
            assertFailsWith<FormatException>(text) { SmtLib.parse(text) }
        }
    }
}
