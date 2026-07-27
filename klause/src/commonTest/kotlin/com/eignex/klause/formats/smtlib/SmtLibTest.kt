package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.formats.FormatException
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.Presolver
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmtLibTest {

    private fun solve(text: String): LongArray {
        val r = BacktrackSolver(SmtLib.parse(text).problem).solve(BacktrackParams())
        assertTrue(r is SolveResult.Sat, "expected SAT, got $r")
        return r.assignment.ints
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
        ).problem
        // The relation bounds carry the >32-bit literals; the lower bound 2^32 exceeds the +2^31 domain
        // cap so it clamps, but parsing succeeds with no exception.
        assertEquals(1, p.numIntVars)
    }

    @Test
    fun `an integer literal beyond 64 bits is rejected as an over-range integer`() {
        val text = "(declare-const x Int) (assert (<= x 170141183460469231731687303715884105728))"
        val e = assertFailsWith<UnsupportedSmtException> { SmtLib.parse(text) }
        assertTrue("integer literal" in e.message.orEmpty() && "64-bit" in e.message.orEmpty(), e.message.orEmpty())
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
        // silently declared as an Int constant (the arg-sort list was previously ignored).
        val e = assertFailsWith<UnsupportedSmtException> {
            SmtLib.parse("(declare-fun f (Int) Int) (assert (>= f 0)) (check-sat)")
        }
        assertTrue("declare-fun" in e.message.orEmpty(), e.message.orEmpty())
        val p = SmtLib.parse("(declare-fun f () Int) (assert (>= f 0)) (check-sat)").problem
        assertEquals(1, p.numIntVars)
    }

    @Test
    fun `parses conjunctive and disjunctive QF_LIA and solves SAT`() {
        val text = """
            (set-logic QF_LIA)
            (declare-const x Int) (declare-const y Int)
            (assert (>= x 0)) (assert (>= y 0))
            (assert (<= (+ x y) 10))
            (assert (or (>= x 7) (>= y 7)))
            (check-sat)
        """.trimIndent()
        val parsed = SmtLib.parse(text)
        assertEquals(2, parsed.problem.numIntVars)
        val ints = solve(text)
        val x = ints[0]
        val y = ints[1]
        assertTrue(x >= 0 && y >= 0 && x + y <= 10 && (x >= 7 || y >= 7), "x=$x y=$y")
    }

    @Test
    fun `parses objective and finds optimum`() {
        val text = """
            (declare-const x Int) (declare-const y Int)
            (assert (>= x 0)) (assert (>= y 0))
            (assert (<= (+ x y) 10))
            (assert (or (>= x 7) (>= y 7)))
            (minimize (+ x y))
        """.trimIndent()
        val parsed = SmtLib.parse(text)
        val obj = requireNotNull(parsed.objective)
        val r = BacktrackSolver(parsed.problem).minimize(obj, BacktrackParams())
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
        // Generated SMT (e.g. Dartagnan) nests thousands of lets in tail position. Compilation must
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
        assertTrue(SmtLib.parse(text).problem.factors.any { it is AllDifferent }, "expected AllDifferent")
        val ints = solve(text)
        assertEquals(setOf(1L, 2L, 3L), listOf(ints[0], ints[1], ints[2]).toSet())
    }

    @Test
    fun `distinct over bools forces inequality`() {
        val text = """
            (declare-const p Bool) (declare-const q Bool)
            (assert (distinct p q))
            (check-sat)
        """.trimIndent()
        val r = BacktrackSolver(SmtLib.parse(text).problem).solve(BacktrackParams())
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
        val p = SmtLib.parse(text).problem
        assertEquals(3, p.intDomains[0].min)
        assertEquals(7, p.intDomains[0].max)
        assertEquals(0, p.intDomains[1].min)
        assertEquals(7, p.intDomains[1].max)
    }

    @Test
    fun `bound inference falls back to the default bound when unprovable`() {
        val p = SmtLib.parse(
            "(declare-const x Int) (assert (<= x 4))",
            unboundedIntLo = -50,
            unboundedIntHi = 50,
        ).problem
        assertEquals(4, p.intDomains[0].max)
        assertEquals(-50, p.intDomains[0].min)
    }

    @Test
    fun `to_real and to_int are identity over ints`() {
        val p = SmtLib.parse(
            "(declare-const x Int) (assert (<= (to_int (to_real x)) 5)) (assert (>= x 5)) (check-sat)",
        ).problem
        assertEquals(5, p.intDomains[0].min)
        assertEquals(5, p.intDomains[0].max)
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
        assertEquals(1, parsed.problem.numRealVars)
        val r = BacktrackSolver(parsed.problem).solve(BacktrackParams())
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
        val r = BacktrackSolver(parsed.problem).solve(BacktrackParams())
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
        val r = BacktrackSolver(parsed.problem).solve(BacktrackParams())
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
        val r = BacktrackSolver(parsed.problem).solve(BacktrackParams())
        assertTrue(r is SolveResult.Sat, "expected SAT, got $r")
        val x = r.assignment.reals[0]
        assertTrue(x > 2.4 && x < 2.5, "x=$x must sit strictly inside (2.4, 2.5)")
    }

    @Test
    fun `a strict boundary conflict over reals is unsat`() {
        val parsed = SmtLib.parse(
            "(declare-const x Real) (assert (< x 2.0)) (assert (>= x 2.0)) (check-sat)",
        )
        val r = BacktrackSolver(parsed.problem).solve(BacktrackParams())
        assertTrue(r is SolveResult.Unsat, "expected UNSAT, got $r")
    }

    @Test
    fun `a real disjunction solves through reified atoms`() {
        val parsed = SmtLib.parse(
            "(declare-const x Real) (assert (or (<= x 1.0) (>= x 3.0))) (assert (>= x 2.0)) (check-sat)",
        )
        val r = BacktrackSolver(parsed.problem).solve(BacktrackParams())
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
        val r = BacktrackSolver(parsed.problem).solve(BacktrackParams())
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
        val r = BacktrackSolver(parsed.problem).solve(BacktrackParams())
        assertTrue(r is SolveResult.Sat, "expected SAT, got $r")
        assertTrue(abs(r.assignment.reals[0] - 2.5) < 1e-9, "x=${r.assignment.reals[0]}")
    }

    @Test
    fun `to_int of a fractional real floors it`() {
        val parsed = SmtLib.parse(
            """
            (declare-const r Real) (declare-const n Int)
            (assert (= r 2.5)) (assert (= n (to_int r)))
            (check-sat)
            """.trimIndent(),
        )
        val res = BacktrackSolver(parsed.problem).solve(BacktrackParams())
        assertTrue(res is SolveResult.Sat, "expected SAT, got $res")
        assertEquals(2L, res.assignment.ints[parsed.intVarNames.getValue("n")])
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
        val r = BacktrackSolver(parsed.problem).solve(BacktrackParams())
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
        val problem = SmtLib.parse(text).problem
        assertTrue(problem.numIntVars >= 1)
    }

    private fun solveFor(text: String, name: String): Long {
        val parsed = SmtLib.parse(text)
        val r = BacktrackSolver(parsed.problem).solve(BacktrackParams())
        assertTrue(r is SolveResult.Sat, "expected SAT, got $r")
        return r.assignment.ints[parsed.intVarNames.getValue(name)]
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
    fun `a small unbounded system closes under the small-model bound without clamping`() {
        // One row with tiny coefficients: the small-model bound fits, so the finite box is
        // equisatisfiable with the unbounded model and no clamp flag is raised.
        val parsed = SmtLib.parse("(declare-fun x () Int) (assert (> x 3)) (check-sat)")
        assertFalse(parsed.domainsClamped, "the small-model bound covers this system exactly")
    }

    @Test
    fun `an unbounded variable past the small-model range marks the model as clamped`() {
        val parsed = SmtLib.parse(
            "(declare-fun x () Int) (assert (> x 3000000000000)) (check-sat)",
        )
        assertTrue(parsed.domainsClamped, "the coefficient magnitude pushes the small-model bound past 2^62")
    }

    @Test
    fun `a fully bounded model is not clamped`() {
        val parsed = SmtLib.parse(
            "(declare-fun x () Int) (assert (>= x 0)) (assert (<= x 5)) (assert (>= x 8)) (check-sat)",
        )
        assertFalse(parsed.domainsClamped, "both bounds are provable, so an unsat here is sound")
    }

    @Test
    fun `OBBT derives a finite bound for an otherwise-unbounded variable`() {
        // x has no declared bound, but `2*x = 10` pins it to 5. Interval bound inference cannot divide,
        // so it leaves x unbounded; OBBT solves the LP to x in [5, 5], so the model is not clamped
        // (an unsat would be sound) and x solves to 5.
        val text = "(set-logic QF_LIA)\n(declare-fun x () Int)\n(assert (= (* 2 x) 10))\n(check-sat)"
        val parsed = SmtLib.parse(text)
        assertFalse(parsed.domainsClamped, "OBBT bounded x from the LP, so the model is not clamped")
        assertEquals(5L, solveFor(text, "x"))
    }

    @Test
    fun `an unbounded variable OBBT cannot bound still finds a witness`() {
        // x > 3 leaves x unbounded above; OBBT cannot bound it. The small-model box closes it and
        // search still finds the witness x = 4.
        val text = "(set-logic QF_LIA)\n(declare-fun x () Int)\n(assert (> x 3))\n(check-sat)"
        val parsed = SmtLib.parse(text)
        assertFalse(parsed.domainsClamped, "the small-model box is equisatisfiable, not a clamp")
        assertTrue(solveFor(text, "x") > 3L, "search still finds a witness within the box")
    }

    @Test
    fun `a sat witness beyond the legacy million-value window is found`() {
        // The searchable fallback spans the overflow-safe Long range, so a witness at 10^10 --
        // far past the historical +-10^6 window -- is reachable.
        val text = "(set-logic QF_LIA)\n(declare-fun x () Int)\n(assert (>= x 10000000000))\n(check-sat)"
        assertTrue(solveFor(text, "x") >= 10_000_000_000L)
    }

    @Test
    fun `OBBT bounds a coupled equality system interval inference cannot solve`() {
        // Neither x nor y has a declared bound; x+y=10 and x-y=4 pin x=7. Interval inference cannot
        // solve the 2x2 system, so both stay open. OBBT's LP relaxation derives x in [7,7], so the model
        // is not clamped (an unsat would be sound) and x solves to 7.
        val text = "(set-logic QF_LIA)\n(declare-fun x () Int)\n(declare-fun y () Int)\n" +
            "(assert (= (+ x y) 10))\n(assert (= (- x y) 4))\n(check-sat)"
        val parsed = SmtLib.parse(text)
        assertFalse(parsed.domainsClamped, "OBBT closed both variables from the linear system")
        assertEquals(7L, solveFor(text, "x"))
    }

    @Test
    fun `a fresh abs over an unbounded operand marks the model clamped`() {
        // abs(x) with x unbounded: the fresh |x| var must inherit x's open range and be flagged when it
        // is clamped, so a search 'unsat' over the box is reported unknown — never a false unsat.
        val parsed = SmtLib.parse("(declare-fun x () Int) (assert (> (abs x) 3000000000000)) (check-sat)")
        assertTrue(parsed.domainsClamped, "the fresh abs var inherits x's unbounded range")
    }

    @Test
    fun `a fresh div quotient over an unbounded dividend marks the model clamped`() {
        val parsed = SmtLib.parse("(declare-fun x () Int) (assert (> (div x 2) 3000000000000)) (check-sat)")
        assertTrue(parsed.domainsClamped, "the fresh quotient var inherits x's unbounded range")
    }

    @Test
    fun `a fresh ite over an unbounded branch marks the model clamped`() {
        val parsed = SmtLib.parse(
            "(declare-fun x () Int) (declare-fun p () Bool) (assert (> (ite p x 0) 3000000000000)) (check-sat)",
        )
        assertTrue(parsed.domainsClamped, "the fresh ite var inherits the unbounded branch's range")
    }

    @Test
    fun `a divisibility-only unsat over unbounded variables is decided by the small-model box`() {
        // 3x + 3y = 1 has no integer solution (gcd 3 does not divide 1), and its LP relaxation is
        // feasible so OBBT derives no bound. The small-model bound fits, making the finite box
        // equisatisfiable: the resulting unsat is sound for the unbounded problem, no clamp flag.
        val parsed = SmtLib.parse(
            "(set-logic QF_LIA)\n(declare-fun x () Int)\n(declare-fun y () Int)\n" +
                "(assert (= (+ (* 3 x) (* 3 y)) 1))\n(check-sat)",
        )
        assertFalse(parsed.domainsClamped, "the small-model box decides divisibility unsat exactly")
    }

    @Test
    fun `presolve proves a gcd-indivisible equality infeasible before baking the wide domain`() {
        // 3x + 3y = 1 has no integer solution (gcd 3 does not divide 1). Coefficient strengthening runs
        // as the first presolve pass and reports this in O(factors) — before the (deferred) root bake
        // would narrow the wide clamped domain toward the empty domain one step per round (O(span)).
        val problem = SmtLib.parse(
            "(declare-fun x () Int) (declare-fun y () Int)" +
                " (assert (= (+ (* 3 x) (* 3 y)) 1)) (check-sat)",
        ).problem
        assertTrue(
            Presolver.run(problem, PresolveConfig.parse("default")).infeasible,
            "coefficient strengthening should prove the gcd-indivisible equality infeasible",
        )
    }

    @Test
    fun `a stray closing paren is a parse error rather than an infinite loop`() {
        // A top-level unbalanced ')' must be rejected; previously the reader returned an empty token
        // without advancing and spun forever.
        assertFailsWith<FormatException> {
            SmtLib.parse("(declare-const x Int)\n(assert (>= x 0))\n(check-sat))")
        }
    }

    @Test
    fun `constant folding overflow is rejected rather than silently wrapping`() {
        // SMT integers are unbounded; folding 2^63-1 + 1 wraps to Long.MIN_VALUE if unchecked, so the
        // term must be rejected the same way an over-64-bit literal is.
        val text = "(declare-const x Int) (assert (= x (+ 9223372036854775807 1))) (check-sat)"
        val e = assertFailsWith<UnsupportedSmtException> { SmtLib.parse(text) }
        assertTrue("overflow" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test
    fun `constant multiplication overflow is rejected`() {
        val text = "(declare-const x Int) (assert (= x (* 3037000500 3037000500))) (check-sat)"
        assertFailsWith<UnsupportedSmtException> { SmtLib.parse(text) }
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
