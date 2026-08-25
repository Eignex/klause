package com.eignex.klause.solver.formats.smtlib

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.formats.FormatException
import com.eignex.klause.formats.smtlib.*
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.ProblemPipeline
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.componentPlan
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.solver.pipeline.OpenTheoryEngine
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.theory.TheoryParams
import com.eignex.klause.theory.lia.GeneralLiaAssignment
import com.eignex.klause.theory.qflra.ExactLiraAssignment
import com.eignex.klause.theory.qflra.ExactLraAssignment
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SmtLibTest {

    private fun openSolve(
        model: com.eignex.klause.solver.ProblemSpec,
        params: TheoryParams = TheoryParams(),
    ): OpenTheoryResult = OpenTheoryEngine(model, model.componentPlan().theoryPipeline).solve(params)

    private fun liraSat(
        model: com.eignex.klause.solver.ProblemSpec,
        params: TheoryParams = TheoryParams(),
    ): ExactLiraAssignment {
        val sat = assertIs<OpenTheoryResult.Sat>(openSolve(model, params))
        return assertIs<OpenTheoryAssignment.ExactLira>(sat.assignment).assignment
    }

    private fun liraUnsat(model: com.eignex.klause.solver.ProblemSpec, params: TheoryParams = TheoryParams()) {
        assertIs<OpenTheoryResult.Unsat>(openSolve(model, params))
    }

    private fun lraSat(model: com.eignex.klause.solver.ProblemSpec): ExactLraAssignment {
        val sat = assertIs<OpenTheoryResult.Sat>(openSolve(model))
        return assertIs<OpenTheoryAssignment.ExactLra>(sat.assignment).assignment
    }

    private fun lraUnsat(model: com.eignex.klause.solver.ProblemSpec) {
        assertIs<OpenTheoryResult.Unsat>(openSolve(model))
    }

    private fun liaSat(model: com.eignex.klause.solver.ProblemSpec): GeneralLiaAssignment {
        val sat = assertIs<OpenTheoryResult.Sat>(openSolve(model))
        return assertIs<OpenTheoryAssignment.GeneralLia>(sat.assignment).assignment
    }

    private fun differenceSat(model: com.eignex.klause.solver.ProblemSpec): Sample {
        val sat = assertIs<OpenTheoryResult.Sat>(openSolve(model))
        return assertIs<OpenTheoryAssignment.Difference>(sat.assignment).sample
    }

    private fun solve(text: String): LongArray {
        val parsed = SmtLib.parse(text)
        if (parsed.sourcePipeline == ProblemPipeline.GENERAL_LIA) {
            val assignment = liaSat(parsed.model)
            return LongArray(parsed.intVarNames.values.maxOrNull()?.plus(1) ?: 0) { v ->
                assignment.ints[v].longValue()
            }
        }
        if (parsed.sourcePipeline == ProblemPipeline.DIFFERENCE_THEORY) {
            return differenceSat(parsed.model).ints
        }
        val r = BacktrackSolver(parsed.bounded().bake()).solve(BacktrackParams())
        assertTrue(r is SolveResult.Sat, "expected SAT, got $r")
        return r.assignment.ints
    }

    private fun SmtLibProblem.bounded(): Problem = model.materializeFiniteBounds()

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

        assertEquals(ProblemPipeline.EXACT_LIRA, parsed.sourcePipeline)
        val result = liraSat(parsed.model)

        val x = result.ints[parsed.intVarNames.getValue("x")]
        val y = result.reals[parsed.realVarNames.getValue("y")]
        assertEquals("1/3", (y - BigFraction.of(x, BigInteger.ONE)).toString())
    }

    @Test
    fun `open QF LIRA evaluates one satisfiable leaf within a one leaf budget`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LIRA)
                (declare-const x Int) (declare-const y Real)
                (assert (= y (to_real x)))
                (check-sat)
            """.trimIndent(),
        )

        val result = liraSat(parsed.model, TheoryParams(maxLeaves = 1))
    }

    @Test
    fun `open QF LIRA cuts a fractional integer relaxation`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LIRA)
                (declare-const x Int) (declare-const y Real)
                (assert (= (to_real x) y))
                (assert (= y (/ 1.0 2.0)))
                (check-sat)
            """.trimIndent(),
        )

        assertEquals(ProblemPipeline.EXACT_LIRA, parsed.sourcePipeline)
        liraUnsat(parsed.model, TheoryParams(maxLeaves = 3))
    }

    @Test
    fun `open QF LIRA retains a BigInteger branch bound`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LIRA)
                (declare-const x Int) (declare-const y Real)
                (assert (= (to_real x) y))
                (assert (= y (/ 2305843009213693953.0 2.0)))
                (check-sat)
            """.trimIndent(),
        )

        assertEquals(ProblemPipeline.EXACT_LIRA, parsed.sourcePipeline)
        liraUnsat(parsed.model)
    }

    @Test
    fun `open QF LIRA finds a witness beyond the former frontend clamp`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LIRA)
                (declare-const x Int) (declare-const y Real)
                (assert (= (to_real x) y))
                (assert (= y 1180591620717411303424.0))
                (check-sat)
            """.trimIndent(),
        )

        val result = liraSat(parsed.model, TheoryParams(maxLeaves = 2))

        assertEquals(BigInteger.ONE shl 70, result.ints[parsed.intVarNames.getValue("x")])
    }

    @Test
    fun `open QF LIRA keeps a wide integer row exact beside a real column`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LIRA)
                (declare-const x Int) (declare-const y Real)
                (assert (= y (to_real x)))
                (assert (= (* 1208925819614629174706176 x) 1208925819614629174706176))
                (check-sat)
            """.trimIndent(),
        )

        assertEquals(ProblemPipeline.EXACT_LIRA, parsed.sourcePipeline)
        val result = liraSat(parsed.model)

        assertEquals(BigInteger.ONE, result.ints[parsed.intVarNames.getValue("x")])
    }

    @Test
    fun `open QF LIRA keeps integer-only rows in the mixed relaxation`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LIRA)
                (declare-const x Int) (declare-const y Real)
                (assert (>= x 5))
                (assert (= y (+ (to_real x) (/ 1.0 3.0))))
                (check-sat)
            """.trimIndent(),
        )

        val result = liraSat(parsed.model)

        assertTrue(result.ints[parsed.intVarNames.getValue("x")] >= BigInteger.fromInt(5))
    }

    @Test
    fun `open QF LIRA searches Boolean real atoms before integer branching`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LIRA)
                (declare-const x Int) (declare-const y Real)
                (assert (>= x 0))
                (assert (= y (/ 1.0 2.0)))
                (assert (or (<= y (to_real x)) (>= y (+ (to_real x) 1.0))))
                (check-sat)
            """.trimIndent(),
        )

        val result = liraSat(parsed.model)

        assertTrue(result.ints[parsed.intVarNames.getValue("x")] >= BigInteger.ONE)
    }

    @Test
    fun `open QF LIRA decomposes an integer disequality`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LIRA)
                (declare-const x Int) (declare-const y Real)
                (assert (= y (to_real x)))
                (assert (distinct x 0))
                (check-sat)
            """.trimIndent(),
        )

        assertEquals(ProblemPipeline.EXACT_LIRA, parsed.sourcePipeline)
        val result = liraSat(parsed.model)

        assertTrue(result.ints[parsed.intVarNames.getValue("x")] != BigInteger.ZERO)
    }

    @Test
    fun `open QF LIRA decomposes a real disequality into strict arms`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LIRA)
                (declare-const x Int) (declare-const y Real)
                (assert (= y (to_real x)))
                (assert (distinct y 0.0))
                (check-sat)
            """.trimIndent(),
        )

        assertEquals(ProblemPipeline.EXACT_LIRA, parsed.sourcePipeline)
        val result = liraSat(parsed.model)

        assertTrue(result.reals[parsed.realVarNames.getValue("y")].isZero.not())
    }

    @Test
    fun `open QF LIRA decomposes a reified integer disequality`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LIRA)
                (declare-const x Int) (declare-const y Real) (declare-const p Bool)
                (assert (= y (to_real x)))
                (assert (or (not (= x 0)) p))
                (assert (not p))
                (check-sat)
            """.trimIndent(),
        )

        assertEquals(ProblemPipeline.EXACT_LIRA, parsed.sourcePipeline)
        val result = liraSat(parsed.model)

        assertTrue(result.ints[parsed.intVarNames.getValue("x")] != BigInteger.ZERO)
    }

    @Test
    fun `open QF LIRA proves an incompatible integer disequality unsatisfiable`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LIRA)
                (declare-const x Int) (declare-const y Real)
                (assert (= y (to_real x)))
                (assert (= y 0.0))
                (assert (distinct x 0))
                (check-sat)
            """.trimIndent(),
        )

        assertEquals(ProblemPipeline.EXACT_LIRA, parsed.sourcePipeline)
        liraUnsat(parsed.model)
    }

    @Test
    fun `open QF LRA equality returns an exact rational witness`() {
        val parsed = SmtLib.parse(
            "(set-logic QF_LRA) (declare-const x Real) (assert (= x (/ 1.0 3.0))) (check-sat)",
        )

        assertEquals(ProblemPipeline.EXACT_LRA, parsed.sourcePipeline)
        val result = lraSat(parsed.model)

        assertEquals("1/3", result.reals[parsed.realVarNames.getValue("x")].toString())
    }

    @Test
    fun `open QF LRA Boolean real atoms decide unsatisfiable`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LRA)
                (declare-const x Real)
                (assert (or (<= x (/ 1.0 3.0)) (>= x (/ 2.0 3.0))))
                (assert (> x (/ 1.0 3.0)))
                (assert (< x (/ 2.0 3.0)))
                (check-sat)
            """.trimIndent(),
        )

        assertEquals(ProblemPipeline.EXACT_LRA, parsed.sourcePipeline)
        lraUnsat(parsed.model)
    }

    @Test
    fun `open QF LRA retains a wide rational witness`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LRA)
                (declare-const x Real)
                (assert (= (* 1152921504606846976.0 x) 1.0))
                (check-sat)
            """.trimIndent(),
        )

        val result = lraSat(parsed.model)

        assertEquals("1/1152921504606846976", result.reals[parsed.realVarNames.getValue("x")].toString())
    }

    @Test
    fun `integer equality over an ite operand is an arithmetic relation`() {
        // `(= (ite p x (+ x 1)) 5)`: the first operand is an int-sorted ite. The dispatch must treat
        // this as arithmetic equality (not boolean iff), else it compiles the int subterm as Bool.
        val text = """
            (set-logic QF_LIA)
            (declare-const x Int) (declare-const p Bool)
            (assert (= (ite p x (+ x 1)) 5))
            (assert p)
            (check-sat)
        """.trimIndent()
        assertEquals(5, solve(text)[0], "p true ⇒ ite = x = 5")
    }

    @Test
    fun `an integer literal beyond 32 bits parses as a 64-bit value`() {
        // 2^31 and 2^32 exceed Int but are valid arbitrary-precision QF_LIA integers; they must parse
        // (carried as Long), not be misclassified as reals and rejected.
        val p = SmtLib.parse(
            "(declare-const x Int) (assert (<= x 2147483648)) (assert (>= x 4294967296)) (check-sat)",
            unboundedIntLo = 0L,
            unboundedIntHi = Int.MAX_VALUE.toLong(),
        ).model
        // The relation bounds carry the >32-bit literals, so parsing succeeds without truncation.
        assertEquals(1, p.numIntVars)
    }

    @Test
    fun `an over-Int64 coefficient solves to a witness satisfying it exactly`() {
        // 2^64·x + y = 2^64 + 1 with x, y in [0, 3] forces x = 1, y = 1 (y is too small to carry a 2^64
        // unit). The 2^64 coefficient can only live in a wide row, so this exercises the wide lowering.
        val w = BigInteger.parseString("18446744073709551616") // 2^64
        val text = """
            (set-logic QF_LIA)
            (declare-const x Int) (declare-const y Int)
            (assert (>= x 0)) (assert (<= x 3)) (assert (>= y 0)) (assert (<= y 3))
            (assert (= (+ (* 18446744073709551616 x) y) 18446744073709551617))
            (check-sat)
        """.trimIndent()
        val ints = solve(text)
        val lhs = w * BigInteger.fromLong(ints[0]) + BigInteger.fromLong(ints[1])
        assertEquals(w + BigInteger.ONE, lhs, "witness (x=${ints[0]}, y=${ints[1]}) must satisfy the wide row")
    }

    @Test
    fun `an over-Int64 coefficient with no integer solution is unsat`() {
        // 2^64·x = 2^64 + 1 has no integer x (remainder 1), so the wide row makes the problem unsat.
        val text = """
            (set-logic QF_LIA)
            (declare-const x Int)
            (assert (>= x 0)) (assert (<= x 3))
            (assert (= (* 18446744073709551616 x) 18446744073709551617))
            (check-sat)
        """.trimIndent()
        val r = BacktrackSolver(SmtLib.parse(text).bounded().bake()).solve(BacktrackParams())
        assertTrue(r is SolveResult.Unsat, "expected UNSAT, got $r")
    }

    @Test
    fun `a reified over-Int64 relation solves correctly`() {
        // b ↔ (2^64·x ≤ 2^64) ⟺ x ≤ 1; asserting b and x ≥ 1 pins x = 1. The nested relation reifies, so
        // this exercises the wide reified lowering (a wide ReifiedLinear).
        val text = """
            (set-logic QF_LIA)
            (declare-const x Int) (declare-const b Bool)
            (assert (>= x 0)) (assert (<= x 3))
            (assert (= b (<= (* 18446744073709551616 x) 18446744073709551616)))
            (assert b) (assert (>= x 1))
            (check-sat)
        """.trimIndent()
        assertEquals(1L, solve(text)[0], "b ∧ x ≥ 1 with b ⟺ x ≤ 1 pins x = 1")
    }

    @Test
    fun `a bitvector literal is rejected as outside the integer-only fragment`() {
        val text = "(declare-const x Int) (assert (= x #xFF))"
        val e = assertFailsWith<UnsupportedSmtException> { SmtLib.parse(text) }
        assertTrue("bitvector literal" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test
    fun `a malformed command is rejected with a clear message not a raw cast crash`() {
        // A declare-const missing its sort must surface as an UnsupportedSmtException, not a
        // ClassCastException / IndexOutOfBounds leaking from an unchecked access.
        val e = assertFailsWith<UnsupportedSmtException> { SmtLib.parse("(declare-const x)") }
        assertTrue("declare-const" in e.message.orEmpty(), e.message.orEmpty())
        // A declare-const whose name position is a list (not an atom) likewise fails cleanly.
        assertFailsWith<UnsupportedSmtException> { SmtLib.parse("(declare-const (a b) Int)") }
    }

    @Test
    fun `declare-fun with arguments is rejected while a 0-arity constant is accepted`() {
        // A non-empty argument list makes f a genuine function symbol, not a variable; it must not be
        // silently declared as an Int constant by ignoring the arg-sort list.
        val e = assertFailsWith<UnsupportedSmtException> {
            SmtLib.parse("(declare-fun f (Int) Int) (assert (>= f 0)) (check-sat)")
        }
        assertTrue("declare-fun" in e.message.orEmpty(), e.message.orEmpty())
        val p = SmtLib.parse("(declare-fun f () Int) (assert (>= f 0)) (check-sat)").model
        assertEquals(1, p.numIntVars)
    }

    @Test
    fun `parses conjunctive and disjunctive QF_LIA and solves SAT`() {
        val text = """
            (set-logic QF_LIA)
            (declare-const x Int) (declare-const y Int)
            (assert (>= x 0)) (assert (<= x 10)) (assert (>= y 0)) (assert (<= y 10))
            (assert (<= (+ x y) 10))
            (assert (or (>= x 7) (>= y 7)))
            (check-sat)
        """.trimIndent()
        val parsed = SmtLib.parse(text)
        assertEquals(2, parsed.model.numIntVars)
        val ints = solve(text)
        val x = ints[0]
        val y = ints[1]
        assertTrue(x >= 0 && y >= 0 && x + y <= 10 && (x >= 7 || y >= 7), "x=$x y=$y")
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
        val r = BacktrackSolver(parsed.bounded().bake()).minimize(obj, BacktrackParams())
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
        val depth = 20_000
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
    fun `n-ary distinct over ints maps to AllDifferent and is a permutation`() {
        val text = """
            (declare-const a Int) (declare-const b Int) (declare-const c Int)
            (assert (>= a 1)) (assert (<= a 3))
            (assert (>= b 1)) (assert (<= b 3))
            (assert (>= c 1)) (assert (<= c 3))
            (assert (distinct a b c))
            (check-sat)
        """.trimIndent()
        assertTrue(SmtLib.parse(text).model.factors.any { it is AllDifferent }, "expected AllDifferent")
        val ints = solve(text)
        assertEquals(setOf(1L, 2L, 3L), listOf(ints[0], ints[1], ints[2]).toSet())
    }

    @Test
    fun `open distinct posts strict-order theory alternatives`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LIA)
                (declare-const x Int)
                (declare-const y Int)
                (assert (distinct x y))
                (check-sat)
            """.trimIndent(),
        )

        assertEquals(ProblemPipeline.DIFFERENCE_THEORY, parsed.sourcePipeline)
        assertEquals(2, parsed.model.factors.count { it is ReifiedLinear })
        assertEquals(1, parsed.model.factors.count { it is Clause })
        val result = differenceSat(parsed.model)
        assertTrue(result.ints[0] != result.ints[1])
    }

    @Test
    fun `reified open distinct posts strict-order theory alternatives`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LIA)
                (declare-const x Int)
                (declare-const y Int)
                (assert (or false (distinct x y)))
                (check-sat)
            """.trimIndent(),
        )

        assertEquals(ProblemPipeline.DIFFERENCE_THEORY, parsed.sourcePipeline)
        assertEquals(2, parsed.model.factors.count { it is ReifiedLinear })
        val result = differenceSat(parsed.model)
        assertTrue(result.ints[0] != result.ints[1])
    }

    /** `k` bounded integers over `[0, k - 1]`, with [tail] appended as the closing assertions. */
    private fun boundedInts(k: Int, tail: String): String {
        val decls = (0 until k).joinToString("\n") {
            "(declare-const x$it Int) (assert (>= x$it 0)) (assert (<= x$it ${k - 1}))"
        }
        return "$decls\n$tail\n(check-sat)"
    }

    private fun names(k: Int): String = (0 until k).joinToString(" ") { "x$it" }

    @Test
    fun `a reified distinct over bounded ints costs a constant number of literals`() {
        val small = SmtLib.parse(boundedInts(6, "(declare-const p Bool) (assert (or p (distinct ${names(6)})))"))
        val large = SmtLib.parse(boundedInts(40, "(declare-const p Bool) (assert (or p (distinct ${names(40)})))"))
        assertEquals(
            small.model.numBoolVars,
            large.model.numBoolVars,
            "reified distinct allocated literals per operand pair",
        )
    }

    @Test
    fun `a negated distinct over bounded ints forces a repeated value`() {
        val ints = solve(boundedInts(4, "(assert (not (distinct ${names(4)})))"))
        val terms = (0 until 4).map { ints[it] }
        assertTrue(terms.toSet().size < 4, "expected a repeated value, got $terms")
    }

    @Test
    fun `a reified distinct held true over bounded ints yields distinct values`() {
        val ints = solve(boundedInts(4, "(assert (or false (distinct ${names(4)})))"))
        val terms = (0 until 4).map { ints[it] }
        assertEquals(4, terms.toSet().size, "expected distinct values, got $terms")
    }

    @Test
    fun `distinct over bools forces inequality`() {
        val text = """
            (declare-const p Bool) (declare-const q Bool)
            (assert (distinct p q))
            (check-sat)
        """.trimIndent()
        val r = BacktrackSolver(SmtLib.parse(text).bounded().bake()).solve(BacktrackParams())
        assertTrue(r is SolveResult.Sat, "expected SAT, got $r")
        assertTrue(r.assignment.bools[0] != r.assignment.bools[1], "p and q must differ")
    }

    @Test
    fun `bound inference tightens domains from constant comparisons`() {
        val text = """
            (declare-const x Int) (declare-const y Int)
            (assert (>= x 3)) (assert (<= x 7))
            (assert (<= (+ x y) 10)) (assert (>= y 0))
            (check-sat)
        """.trimIndent()
        val bounds = SmtLib.parse(text).model.intBounds
        assertEquals(3, bounds.lower(0))
        assertEquals(7, bounds.upper(0))
        assertEquals(0, bounds.lower(1))
        assertTrue(bounds.isOpenUpper(1))
    }

    @Test
    fun `parsing leaves a multi-variable bound to the presolve-phase tightening`() {
        // Parsing has no wall-clock budget, so it must not run a bound fixpoint: it pins only the
        // single-variable rows. `x <= 10 - y` needs the row's other term, which is feasibility-based
        // tightening and belongs to the deferred run — where the presolve budget bounds it.
        val text = """
            (declare-const x Int) (declare-const y Int)
            (assert (>= x 0)) (assert (>= y 0)) (assert (<= (+ x y) 10))
            (check-sat)
        """.trimIndent()

        val parsed = SmtLib.parse(text)

        assertEquals(0, parsed.model.intBounds.lower(0), "a single-variable row is pinned at parse")
        assertTrue(parsed.model.intBounds.isOpenUpper(0), "parse must not derive a bound across terms")
    }

    @Test
    fun `bound inference leaves an unstated side open`() {
        val p = SmtLib.parse(
            "(declare-const x Int) (assert (<= x 4))",
            unboundedIntLo = -50,
            unboundedIntHi = 50,
        ).model.intBounds
        assertEquals(4, p.upper(0))
        assertTrue(p.isOpenLower(0))
    }

    @Test
    fun `to_real and to_int are identity over ints`() {
        val p = SmtLib.parse(
            "(declare-const x Int) (assert (<= x 5)) " +
                "(assert (<= (to_int (to_real x)) 5)) (assert (>= x 5)) (check-sat)",
        ).bounded()
        assertEquals(5, p.requireFiniteIntDomains()[0].min)
        assertEquals(5, p.requireFiniteIntDomains()[0].max)
    }

    @Test
    fun `real declarations lower to lp-only columns and solve`() {
        val parsed = SmtLib.parse(
            """
            (declare-const x Real) (declare-const n Int)
            (assert (>= n 1)) (assert (<= n 3))
            (assert (<= (to_real n) x)) (assert (<= x 2.5))
            (check-sat)
            """.trimIndent(),
        )
        assertEquals(1, parsed.model.numRealVars)
        val r = BacktrackSolver(parsed.bounded().bake()).solve(BacktrackParams())
        assertTrue(r is SolveResult.Sat, "expected SAT, got $r")
        val n = r.assignment.ints[0]
        val x = r.assignment.reals[0]
        assertTrue(n in 1..2, "n=$n must fit under x <= 2.5")
        assertTrue(x >= n.toDouble() && x <= 2.5, "x=$x outside [n, 2.5]")
    }

    @Test
    fun `an infeasible real system is unsat via the exact leaf verdict`() {
        val parsed = SmtLib.parse(
            "(declare-const x Real) (assert (>= x 6.0)) (assert (<= (* 0.1 x) 0.5)) (check-sat)",
        )
        val r = BacktrackSolver(parsed.bounded().bake()).solve(BacktrackParams())
        assertTrue(r is SolveResult.Unsat, "expected UNSAT, got $r")
    }

    @Test
    fun `rational coefficients fold exactly through division`() {
        val parsed = SmtLib.parse(
            """
            (declare-const x Real)
            (assert (= (* 3.0 x) 1.0)) (assert (>= x 0.3)) (assert (<= x 0.4))
            (check-sat)
            """.trimIndent(),
        )
        val r = BacktrackSolver(parsed.bounded().bake()).solve(BacktrackParams())
        assertTrue(r is SolveResult.Sat, "expected SAT, got $r")
        val x = r.assignment.reals[0]
        assertTrue(x > 0.333 && x < 0.334, "x=$x should be 1/3")
    }

    @Test
    fun `a real objective parses into real coefficients`() {
        val parsed = SmtLib.parse(
            "(declare-const x Real) (assert (>= x 2.5)) (minimize x) (check-sat)",
        )
        val obj = parsed.objective
        assertTrue(obj != null && obj.realCoefficients.size == 1 && obj.realCoefficients[0] == 1.0)
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
    fun `a real disjunction solves through reified atoms`() {
        val parsed = SmtLib.parse(
            "(declare-const x Real) (assert (or (<= x 1.0) (>= x 3.0))) (assert (>= x 2.0)) (check-sat)",
        )
        val r = BacktrackSolver(parsed.bounded().bake()).solve(BacktrackParams())
        assertTrue(r is SolveResult.Sat, "expected SAT, got $r")
        assertTrue(r.assignment.reals[0] >= 3.0 - 1e-9, "x=${r.assignment.reals[0]} must take the >= 3 branch")
    }

    @Test
    fun `a real disjunction with contradictory branches is unsat`() {
        val parsed = SmtLib.parse(
            """
            (declare-const x Real)
            (assert (or (< x 1.0) (> x 3.0)))
            (assert (>= x 1.5)) (assert (<= x 2.5))
            (check-sat)
            """.trimIndent(),
        )
        val r = BacktrackSolver(parsed.bounded().bake()).solve(BacktrackParams())
        assertTrue(r is SolveResult.Unsat, "expected UNSAT, got $r")
    }

    @Test
    fun `a real ite selects the branch chosen by its condition`() {
        val parsed = SmtLib.parse(
            """
            (declare-const x Real) (declare-const c Bool)
            (assert c) (assert (= x (ite c 2.5 7.5)))
            (check-sat)
            """.trimIndent(),
        )
        val r = BacktrackSolver(parsed.bounded().bake()).solve(BacktrackParams())
        assertTrue(r is SolveResult.Sat, "expected SAT, got $r")
        assertTrue(abs(r.assignment.reals[0] - 2.5) < 1e-9, "x=${r.assignment.reals[0]}")
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
        assertEquals(ProblemPipeline.EXACT_LIRA, parsed.sourcePipeline)
    }

    @Test
    fun `distinct over pinned reals is unsat`() {
        val parsed = SmtLib.parse(
            """
            (declare-const x Real) (declare-const y Real)
            (assert (distinct x y))
            (assert (= x 2.0)) (assert (= y 2.0))
            (check-sat)
            """.trimIndent(),
        )
        val r = BacktrackSolver(parsed.bounded().bake()).solve(BacktrackParams())
        assertTrue(r is SolveResult.Unsat, "expected UNSAT, got $r")
    }

    @Test
    fun `a decimal comparison over an int scales to an exact integer row`() {
        // x <= 1.5 over Int multiplies through by the denominator: 2x <= 3, so x = 1 is the witness.
        val text = "(declare-const x Int) (assert (<= x 1.5)) (assert (>= x 1)) (check-sat)"
        assertEquals(1L, solveFor(text, "x"))
    }

    @Test
    fun `integer ite selects the branch chosen by its condition`() {
        // c forced true ⇒ x = 7; a second solve with c false ⇒ x = 3.
        val whenTrue = solve(
            """
            (declare-const x Int) (declare-const c Bool)
            (assert c) (assert (= x (ite c 7 3))) (check-sat)
            """.trimIndent(),
        )
        assertEquals(7, whenTrue[0])
        val whenFalse = solve(
            """
            (declare-const x Int) (declare-const c Bool)
            (assert (not c)) (assert (= x (ite c 7 3))) (check-sat)
            """.trimIndent(),
        )
        assertEquals(3, whenFalse[0])
    }

    @Test
    fun `strict bounds errors on an unbounded variable`() {
        val ex = assertFailsWith<UnsupportedSmtException> {
            SmtLib.parse("(declare-const x Int) (declare-const y Int) (assert (<= x 4))", strictBounds = true)
        }
        assertTrue(ex.message!!.contains("y") || ex.message!!.contains("bound"), ex.message!!)
    }

    @Test
    fun `deeply nested terms parse without overflowing the stack`() {
        // A depth that overflows a recursive-descent fold but is cheap iteratively. Exercises all
        // three tree-walkers: boolean nesting (compileBool), arithmetic nesting (linearTerm), and
        // let-scope nesting (the evaluator's scope frames).
        val depth = 20_000
        val notChain = "(not ".repeat(depth) + "p" + ")".repeat(depth)
        val plusChain = "(+ 1 ".repeat(depth) + "x" + ")".repeat(depth)
        val letOpen = StringBuilder()
        val letClose = StringBuilder()
        for (i in 0 until depth) {
            letOpen.append("(let ((y$i ${if (i == 0) "x" else "y${i - 1}"})) ")
            letClose.append(")")
        }
        val letChain = "$letOpen y${depth - 1} $letClose"
        val text = """
            (set-logic QF_LIA)
            (declare-const x Int) (declare-const p Bool)
            (assert $notChain)
            (assert (<= $plusChain 1000000))
            (assert (>= $letChain 0))
            (check-sat)
        """.trimIndent()
        val problem = SmtLib.parse(text).model
        assertTrue(problem.numIntVars >= 1)
    }

    private fun solveFor(text: String, name: String): Long {
        val parsed = SmtLib.parse(text)
        return solve(text)[parsed.intVarNames.getValue(name)]
    }

    @Test
    fun `a define-fun call is inlined`() {
        // `mymax(x, 5) = 8` forces x = 8 (the larger operand), exercising macro inlining over `ite`.
        val text = """
            (set-logic QF_LIA)
            (define-fun mymax ((a Int) (b Int)) Int (ite (>= a b) a b))
            (declare-fun x () Int)
            (assert (>= x 0)) (assert (<= x 10))
            (assert (= (mymax x 5) 8))
            (check-sat)
        """.trimIndent()
        assertEquals(8L, solveFor(text, "x"))
    }

    @Test
    fun `a real-returning define-fun in a relation keeps its body in a real context`() {
        val parsed = SmtLib.parse(
            """
            (set-logic QF_LRA)
            (declare-fun x () Real)
            (assert (and (<= 0 x) (<= x 1)))
            (define-fun obj () Real (* 10000 x))
            (assert (>= obj 5000))
            (check-sat)
            """.trimIndent(),
        )
        val r = BacktrackSolver(parsed.bounded().bake()).solve(BacktrackParams())
        assertTrue(r is SolveResult.Sat, "expected SAT, got $r")
        assertTrue(r.assignment.reals[0] >= 0.5, "10000*x >= 5000 forces x >= 0.5")
    }

    @Test
    fun `abs constrains the absolute value`() {
        val text = """
            (set-logic QF_LIA)
            (declare-fun x () Int)
            (assert (>= x -10)) (assert (<= x 10))
            (assert (= (abs x) 4)) (assert (< x 0))
            (check-sat)
        """.trimIndent()
        assertEquals(-4L, solveFor(text, "x"))
    }

    @Test
    fun `div and mod use euclidean semantics with a constant divisor`() {
        val text = """
            (set-logic QF_LIA)
            (declare-fun x () Int)
            (assert (>= x 0)) (assert (<= x 30))
            (assert (= (mod x 7) 3)) (assert (= (div x 7) 2))
            (check-sat)
        """.trimIndent()
        assertEquals(17L, solveFor(text, "x")) // 7*2 + 3
    }

    @Test
    fun `an open variable remains open past the former search range`() {
        val parsed = SmtLib.parse(
            "(declare-fun x () Int) (assert (> x 3000000000000)) (check-sat)",
        )
        assertFalse(parsed.model.intBounds.isOpenLower(0))
        assertTrue(parsed.model.intBounds.isOpenUpper(0))
        assertEquals(ProblemPipeline.DIFFERENCE_THEORY, parsed.sourcePipeline)
    }

    @Test
    fun `a single-variable equality disjunction bounds the variable`() {
        val text = "(set-logic QF_LIA)\n(declare-fun u () Int)\n" +
            "(assert (or (= u 0) (= u 1) (= u 2) (= u 3) (= u 4)))\n(check-sat)"
        val parsed = SmtLib.parse(text)
        val v = parsed.intVarNames.getValue("u")
        assertEquals(0L, parsed.model.intBounds.lower(v))
        assertEquals(4L, parsed.model.intBounds.upper(v))
        assertTrue(solve(text)[0] in 0L..4L, "the witness is one of the stated values")
    }

    @Test
    fun `a disjunction over two variables bounds neither`() {
        // Satisfiable with x arbitrarily large, so no side may be read off the constants.
        val text = "(set-logic QF_LIA)\n(declare-fun x () Int)\n(declare-fun y () Int)\n" +
            "(assert (or (= x 1) (= y 2)))\n(check-sat)"
        val parsed = SmtLib.parse(text)
        assertTrue(parsed.model.intBounds.isOpenUpper(parsed.intVarNames.getValue("x")))
    }

    @Test
    fun `a disjunction with a non-equality disjunct bounds nothing`() {
        val text = "(set-logic QF_LIA)\n(declare-fun x () Int)\n" +
            "(assert (or (= x 1) (>= x 100)))\n(check-sat)"
        val parsed = SmtLib.parse(text)
        assertTrue(parsed.model.intBounds.isOpenUpper(parsed.intVarNames.getValue("x")))
    }

    @Test
    fun `a fully bounded model remains finite`() {
        val parsed = SmtLib.parse(
            "(declare-fun x () Int) (assert (>= x 0)) (assert (<= x 5)) (assert (>= x 8)) (check-sat)",
        )
        assertEquals(ProblemPipeline.FINITE_CP, parsed.sourcePipeline)
    }

    @Test
    fun `a pinned open variable routes to General LIA`() {
        val text = "(set-logic QF_LIA)\n(declare-fun x () Int)\n(declare-fun y () Int)\n" +
            "(assert (= (+ (* 2 x) y) 10))\n(assert (= y 0))\n(check-sat)"
        val parsed = SmtLib.parse(text)
        assertEquals(ProblemPipeline.GENERAL_LIA, parsed.sourcePipeline)
        assertEquals(5L, solveFor(text, "x"))
    }

    @Test
    fun `an open inequality routes to difference theory`() {
        val text = "(set-logic QF_LIA)\n(declare-fun x () Int)\n(assert (> x 3))\n(check-sat)"
        val parsed = SmtLib.parse(text)
        assertEquals(ProblemPipeline.DIFFERENCE_THEORY, parsed.sourcePipeline)
        assertTrue(solveFor(text, "x") > 3L, "search still finds a witness within the box")
    }

    @Test
    fun `a sat witness ten billion values out is found`() {
        // The exact theory path does not need a finite search window to reach this witness.
        val text = "(set-logic QF_LIA)\n(declare-fun x () Int)\n(assert (>= x 10000000000))\n(check-sat)"
        assertTrue(solveFor(text, "x") >= 10_000_000_000L)
    }

    @Test
    fun `a coupled open equality system routes to General LIA`() {
        val text = "(set-logic QF_LIA)\n(declare-fun x () Int)\n(declare-fun y () Int)\n" +
            "(assert (= (+ x y) 10))\n(assert (= (- x y) 4))\n(check-sat)"
        val parsed = SmtLib.parse(text)
        assertEquals(ProblemPipeline.GENERAL_LIA, parsed.sourcePipeline)
        assertEquals(7L, solveFor(text, "x"))
    }

    @Test
    fun `a fresh variable over an unbounded operand stays in General LIA`() {
        val cases = listOf(
            "(declare-fun x () Int) (assert (> (abs x) 3000000000000)) (check-sat)" to
                "the fresh abs var inherits x's unbounded range",
            "(declare-fun x () Int) (assert (> (div x 2) 3000000000000)) (check-sat)" to
                "the fresh quotient var inherits x's unbounded range",
            "(declare-fun x () Int) (declare-fun p () Bool) (assert (> (ite p x 0) 3000000000000)) (check-sat)" to
                "the fresh ite var inherits the unbounded branch's range",
        )
        for ((text, message) in cases) {
            assertEquals(ProblemPipeline.GENERAL_LIA, SmtLib.parse(text).sourcePipeline, message)
        }
    }

    @Test
    fun `a divisibility-only open equality system routes to General LIA`() {
        val parsed = SmtLib.parse(
            "(set-logic QF_LIA)\n(declare-fun x () Int)\n(declare-fun y () Int)\n" +
                "(assert (= (+ (* 3 x) (* 3 y)) 1))\n(check-sat)",
        )
        assertEquals(ProblemPipeline.GENERAL_LIA, parsed.sourcePipeline)
    }

    @Test
    fun `presolve proves a gcd-indivisible equality infeasible before baking the wide domain`() {
        // 3x + 3y = 1 has no integer solution (gcd 3 does not divide 1). Coefficient strengthening runs
        // as the first exact-theory check.
        val model = SmtLib.parse(
            "(declare-fun x () Int) (declare-fun y () Int)" +
                " (assert (= (+ (* 3 x) (* 3 y)) 1)) (check-sat)",
        ).model
        assertIs<OpenTheoryResult.Unsat>(openSolve(model))
    }

    @Test
    fun `a stray closing paren is a parse error rather than an infinite loop`() {
        // A top-level unbalanced ')' must be rejected; a reader that returns an empty token without
        // advancing would spin forever instead.
        assertFailsWith<FormatException> {
            SmtLib.parse("(declare-const x Int)\n(assert (>= x 0))\n(check-sat))")
        }
    }

    @Test
    fun `constant folding overflow promotes to a wide value instead of wrapping`() {
        // SMT integers are unbounded; 2^63-1 + 1 = 2^63 overflows Long, so it is carried as a wide value
        // (never silently wrapped to Long.MIN). x = 2^63 has no Long domain to live in, so x is lowered
        // onto digit columns and the witness reads back exactly 2^63.
        val text = "(declare-const x Int) (assert (= x (+ 9223372036854775807 1))) (check-sat)"
        assertEquals(BigInteger.parseString("9223372036854775808"), soleIntValue(text))
    }

    @Test
    fun `constant multiplication overflow promotes to a wide value`() {
        // 3037000500^2 = 9223372037000250000 overflows Long; it is carried wide (not rejected or wrapped)
        // and x holds it on digit columns.
        val text = "(declare-const x Int) (assert (= x (* 3037000500 3037000500))) (check-sat)"
        assertEquals(BigInteger.parseString("9223372037000250000"), soleIntValue(text))
    }

    /** The value of the single declared int in [text], read off its digit columns when it has them. */
    private fun soleIntValue(text: String): BigInteger {
        val parsed = SmtLib.parse(text)
        if (parsed.sourcePipeline == com.eignex.klause.solver.ProblemPipeline.GENERAL_LIA) {
            return liaSat(parsed.model).ints[parsed.intVarNames.values.first()]
        }
        val r = BacktrackSolver(parsed.model.materializeFiniteBounds().bake()).solve(BacktrackParams())
        assertTrue(r is SolveResult.Sat, "expected SAT, got $r")
        return BigInteger.fromLong(r.assignment.ints[parsed.intVarNames.values.first()])
    }

    @Test
    fun `an ite branch beyond 64 bits is decided instead of rejected`() {
        // The branch value 2^64 has no 64-bit domain to live in, so the ite result is carried as digit
        // columns. Asserting p forces the wide branch, and 2^64·x = 2^64 pins x = 1.
        val text = """
            (set-logic QF_LIA)
            (declare-const x Int) (declare-const p Bool)
            (assert (>= x 0)) (assert (<= x 3))
            (assert (= (ite p 18446744073709551616 0) (* 18446744073709551616 x)))
            (assert p)
            (check-sat)
        """.trimIndent()
        assertEquals(1L, solve(text)[0], "the wide branch value pins x = 1")
    }

    @Test
    fun `an ite branch beyond 64 bits still refutes an impossible equality`() {
        // The digit encoding must keep refutation, not merely admit models: 2^64 = 1 cannot hold.
        val text = """
            (set-logic QF_LIA)
            (declare-const p Bool)
            (assert (= (ite p 18446744073709551616 0) 1))
            (assert p)
            (check-sat)
        """.trimIndent()
        val r = BacktrackSolver(SmtLib.parse(text).bounded().bake()).solve(BacktrackParams())
        assertTrue(r is SolveResult.Unsat, "expected UNSAT, got $r")
    }

    @Test
    fun `a declared integer the model drives past Long stays in General LIA`() {
        // c = 4096·a with a ≥ 2^60 forces c past 2^72. General LIA keeps its wide rows and witness in
        // BigInteger rather than rewriting a declared column onto finite Long digits.
        val text = """
            (set-logic QF_LIA)
            (declare-fun a () Int) (declare-fun b () Int) (declare-fun c () Int)
            (assert (= b (* 64 a))) (assert (= c (* 64 b)))
            (assert (>= a 1152921504606846977))
            (check-sat)
        """.trimIndent()
        val parsed = SmtLib.parse(text)
        val result = liaSat(parsed.model)
        val values = parsed.intVarNames.mapValues { (_, id) -> result.ints[id] }
        val sixtyFour = BigInteger.fromLong(64)
        assertEquals(values.getValue("b"), values.getValue("a") * sixtyFour, "b = 64a")
        assertEquals(values.getValue("c"), values.getValue("b") * sixtyFour, "c = 64b")
        assertTrue(values.getValue("a") >= BigInteger.parseString("1152921504606846977"), "a keeps its bound")
    }

    @Test
    fun `an ite branch over an unbounded variable is decided rather than refused`() {
        // The branch value ranges over an open domain, so its magnitude comes from the same fallback box
        // the deferred bounding uses. Refusing it instead gave no verdict at all on the calypto family.
        val text = """
            (set-logic QF_LIA)
            (declare-const x Int) (declare-const p Bool)
            (assert (= (ite p (* 18446744073709551616 x) 0) 18446744073709551616))
            (assert p)
            (check-sat)
        """.trimIndent()
        assertEquals(1L, solve(text)[0], "2^64·x = 2^64 pins x = 1")
    }

    @Test
    fun `abs of a value beyond 64 bits is its magnitude`() {
        val text = """
            (set-logic QF_LIA)
            (declare-const x Int)
            (assert (>= x 0)) (assert (<= x 3))
            (assert (= (abs (- 0 18446744073709551616)) (* 18446744073709551616 x)))
            (check-sat)
        """.trimIndent()
        assertEquals(1L, solve(text)[0], "|-2^64| = 2^64 pins x = 1")
    }

    @Test
    fun `div and mod by a divisor beyond 64 bits are decided`() {
        // (2^64 + 1) div 2^64 = 1 and its remainder is 1, both past what a 64-bit quotient variable holds.
        val text = """
            (set-logic QF_LIA)
            (declare-const x Int) (declare-const y Int)
            (assert (>= x 0)) (assert (<= x 3)) (assert (>= y 0)) (assert (<= y 3))
            (assert (= x (div 18446744073709551617 18446744073709551616)))
            (assert (= y (mod 18446744073709551617 18446744073709551616)))
            (check-sat)
        """.trimIndent()
        val ints = solve(text)
        assertEquals(1L, ints[0], "quotient")
        assertEquals(1L, ints[1], "remainder")
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

    @Test
    fun `malformed let bindings surface as a format exception`() {
        // A missing binding name/value or a non-list binding block must surface through the catchable
        // FormatException, not a ClassCastException / IndexOutOfBounds from an unchecked head access.
        val malformed = listOf(
            "(declare-const x Int) (assert (let (()) (>= x 0)))",
            "(declare-const x Int) (assert (let x (>= x 0)))",
            "(declare-const x Int) (assert (>= (let (()) 5) 0))",
        )
        for (text in malformed) {
            assertFailsWith<FormatException>(text) { SmtLib.parse(text) }
        }
    }
}
