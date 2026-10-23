package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.table.Element
import com.eignex.klause.factor.table.RealElement
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.lowering.LinComb
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.componentPlan
import com.eignex.klause.solver.pipeline.search
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchBoolValue
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchExactIntValue
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.solver.search.SearchSolveParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmtLibSelectionTest {
    @Test
    fun `reified extrema comparisons should preserve signed affine source truth`() {
        for (maximum in listOf(false, true)) for (scale in listOf(-2, 1)) {
            for (first in -1..1) for (second in -1..1)
                for (offset in if (first == 0) listOf(null, -1, 0, 1) else listOf(null)) {
                    val extremum = if (maximum) maxOf(first, second) else minOf(first, second)
                    val value = if (offset == null) scale * extremum + 1 else scale * (extremum - first) - offset
                    val x = if (first < 0) "(- ${-first})" else "$first"
                    val y = if (second < 0) "(- ${-second})" else "$second"
                    val coefficient = if (scale < 0) "(- ${-scale})" else "$scale"
                    val extreme = if (maximum) "(ite (< x y) y x)" else "(ite (< x y) x y)"
                    val offsetTerm = if (offset != null && offset < 0) "(- ${-offset})" else "$offset"
                    val left = if (offset == null) "(+ (* $coefficient $extreme) z)"
                        else "(* $coefficient $extreme)"
                    val right = if (offset == null) "0" else "(+ (* $coefficient x) (/ $offsetTerm 3))"
                    for (op in listOf("<=", "<", ">=", ">", "=")) for (truth in listOf(false, true)) {
                        val expected = when (op) {
                            "<=" -> value <= 0
                            "<" -> value < 0
                            ">=" -> value >= 0
                            ">" -> value > 0
                            else -> value == 0
                        }
                        val parsed = SmtLib.parse(
                            """
                            (declare-const p Bool)
                            (declare-const x Real) (declare-const y Real) (declare-const z Real)
                            (assert (= x (/ $x 3))) (assert (= y (/ $y 3))) (assert (= z (/ 1 3)))
                            (assert (= p ($op $left $right)))
                            (assert ${if (truth) "p" else "(not p)"})
                            """.trimIndent(),
                        )

                        val result = BacktrackSolver.source(parsed.model)
                            .solve(BacktrackParams(randomSeed = 0, maxDecisions = 1000))

                        val context = "max=$maximum scale=$scale x=$first y=$second offset=$offset op=$op truth=$truth"
                        if (truth == expected) assertIs<OpenTheoryResult.Sat>(result, context)
                        else assertIs<OpenTheoryResult.Unsat>(result, context)
                    }
                }
        }
    }

    @Test
    fun `hard extrema comparisons should preserve strict source boundaries`() {
        for (maximum in listOf(false, true)) for (scale in listOf(-1, 2)) {
            for (first in -1..1) for (second in -1..1)
                for (offset in if (first == 0) listOf(null, -1, 0, 1) else listOf(null)) {
                    val extremum = if (maximum) maxOf(first, second) else minOf(first, second)
                    val value = if (offset == null) scale * extremum - 1 else scale * (extremum - first) - offset
                    val x = if (first < 0) "(- ${-first})" else "$first"
                    val y = if (second < 0) "(- ${-second})" else "$second"
                    val coefficient = if (scale < 0) "(- ${-scale})" else "$scale"
                    val extreme = if (maximum) "(ite (<= x y) y x)" else "(ite (<= x y) x y)"
                    val offsetTerm = if (offset != null && offset < 0) "(- ${-offset})" else "$offset"
                    val right = if (offset == null) "(/ 1 3)" else "(+ (* $coefficient x) (/ $offsetTerm 3))"
                    for (op in listOf("<=", "<", ">=", ">", "=")) {
                        val expected = when (op) {
                            "<=" -> value <= 0
                            "<" -> value < 0
                            ">=" -> value >= 0
                            ">" -> value > 0
                            else -> value == 0
                        }
                        val parsed = SmtLib.parse(
                            """
                            (declare-const x Real) (declare-const y Real)
                            (assert (= x (/ $x 3))) (assert (= y (/ $y 3)))
                            (assert ($op (* $coefficient $extreme) $right))
                            """.trimIndent(),
                        )

                        val result = BacktrackSolver.source(parsed.model)
                            .solve(BacktrackParams(randomSeed = 0, maxDecisions = 1000))

                        val context = "max=$maximum scale=$scale x=$first y=$second offset=$offset op=$op"
                        if (expected) assertIs<OpenTheoryResult.Sat>(result, context)
                        else assertIs<OpenTheoryResult.Unsat>(result, context)
                    }
                }
        }
    }

    @Test
    fun `equivalent real extrema should share their result coordinate`() {
        for (maximum in listOf(false, true)) for (affine in listOf(false, true)) {
            val left = if (affine) "(+ x (/ 1 3))" else "x"
            val right = if (affine) "(- y (/ 1 3))" else "y"
            val first = if (maximum) "(ite (< $left $right) $right $left)"
                else "(ite (< $left $right) $left $right)"
            val second = if (maximum) "(ite (<= $right $left) $left $right)"
                else "(ite (<= $right $left) $right $left)"
            val expected = if (maximum) "(/ 2 3)" else "(/ 1 3)"
            val parsed = SmtLib.parse(
                """
                (declare-const x Real) (declare-const y Real)
                (assert (= x (/ 1 3))) (assert (= y (/ 2 3)))
                (assert (= $first $expected))
                (assert (= $second $expected))
                """.trimIndent(),
            )

            val result = BacktrackSolver.source(parsed.model)
                .solve(BacktrackParams(randomSeed = 0, maxDecisions = 1000))

            assertEquals(0, parsed.model.numIntVars)
            assertEquals(3, parsed.model.numRealVars)
            assertIs<OpenTheoryResult.Sat>(result, result.toString())
        }
    }

    @Test
    fun `real minima and maxima should retain distinct result coordinates`() {
        val parsed = SmtLib.parse(
            """
            (declare-const x Real) (declare-const y Real)
            (assert (= x (/ 1 3))) (assert (= y (/ 2 3)))
            (assert (= (ite (<= x y) x y) (/ 1 3)))
            (assert (= (ite (<= x y) y x) (/ 2 3)))
            """.trimIndent(),
        )

        val result = BacktrackSolver.source(parsed.model)
            .solve(BacktrackParams(randomSeed = 0, maxDecisions = 1000))

        assertEquals(0, parsed.model.numIntVars)
        assertEquals(4, parsed.model.numRealVars)
        assertIs<OpenTheoryResult.Sat>(result, result.toString())
    }

    @Test
    fun `ordered real branches should preserve strict signed extrema`() {
        for (op in listOf("<=", "<", ">=", ">")) for (negative in listOf(false, true)) {
            for ((first, second) in listOf(1 to 2, 2 to 1, 2 to 2)) {
                for (reverse in listOf(false, true)) {
                    val left = if (reverse) "y" else "x"
                    val right = if (reverse) "x" else "y"
                    val order = "($op $left $right)"
                    val a = if (reverse) second else first
                    val b = if (reverse) first else second
                    val holds = when (op) {
                        "<=" -> a <= b
                        "<" -> a < b
                        ">=" -> a >= b
                        else -> a > b
                    }
                    val expected = if (holds != negative) first else second
                    val parsed = SmtLib.parse(
                        """
                        (declare-const x Real) (declare-const y Real) (declare-const z Real)
                        (assert (= x (/ $first 3))) (assert (= y (/ $second 3)))
                        (assert (= z (ite ${if (negative) "(not $order)" else order} x y)))
                        """.trimIndent(),
                    )

                    val result = BacktrackSolver.source(parsed.model)
                        .solve(BacktrackParams(randomSeed = 0, maxDecisions = 1000))

                    assertEquals(0, parsed.model.numIntVars)
                    assertEquals("$expected/3", assertIs<OpenTheoryResult.Sat>(result, result.toString()).assignment
                        .realValue(parsed.realVarNames.getValue("z")))
                }
            }
        }
    }

    @Test
    fun `unrelated real guards should retain native selection`() {
        for (branch in listOf(false, true)) {
            val parsed = SmtLib.parse(
                """
                (declare-const b Bool)
                (declare-const x Real) (declare-const y Real) (declare-const z Real)
                (assert (= x (/ 1 3))) (assert (= y (/ 2 3)))
                (assert (or b (< x y)))
                (assert (= (ite (< x y) x y) (/ 1 3)))
                (assert (= z (ite b x y)))
                (assert ${if (branch) "b" else "(not b)"})
                """.trimIndent(),
            )

            val result = BacktrackSolver.source(parsed.model)
                .solve(BacktrackParams(randomSeed = 0, maxDecisions = 1000))

            assertEquals(1, parsed.model.factors.filterIsInstance<RealElement>().size)
            assertEquals(if (branch) "1/3" else "2/3",
                assertIs<OpenTheoryResult.Sat>(result, result.toString()).assignment
                    .realValue(parsed.realVarNames.getValue("z")))
        }
    }

    @Test
    fun `indicator counts and affine real choices should retain source-only integer coordinates`() {
        for (positive in listOf(false, true)) for (branch in listOf(false, true)) {
            val parsed = SmtLib.parse(
                """
                (declare-const b Bool)
                (declare-const x Real) (declare-const y Real) (declare-const z Real)
                (assert (= x (/ 1 3))) (assert (= y (/ 2 3)))
                (assert (<= (+ (ite b 1 0) (ite (< x y) 1 0)) 1))
                (assert (= z (ite (${if (positive) "<=" else ">"} (+ x (/ 1 3)) (- y (/ 1 3)))
                    (+ x (/ 1 3)) (- y (/ 1 3)))))
                (assert ${if (branch) "b" else "(not b)"})
                """.trimIndent(),
            )

            val result = BacktrackSolver.source(parsed.model)
                .solve(BacktrackParams(randomSeed = 0, maxDecisions = 1000))

            assertEquals(0, parsed.model.numIntVars)
            assertEquals(1, parsed.model.factors.filterIsInstance<Cardinality>().size)
            if (branch) {
                assertIs<OpenTheoryResult.Unsat>(result, result.toString())
            } else {
                val assignment = assertIs<OpenTheoryResult.Sat>(result, result.toString()).assignment
                assertEquals(if (positive) "1/3" else "2/3", assignment.realValue(parsed.realVarNames.getValue("z")))
            }
        }
    }

    @Test
    fun `coincident real branches should leave either guard polarity feasible`() {
        for (branch in listOf(true, false)) {
            val parsed = SmtLib.parse(
                """
                (declare-const b Bool)
                (declare-const x Real) (declare-const y Real) (declare-const z Real)
                (assert ${if (branch) "b" else "(not b)"})
                (assert (= x (/ 2 3))) (assert (= y (/ 2 3)))
                (assert (= z (ite b x y)))
                """.trimIndent(),
            )

            val result = BacktrackSolver.source(parsed.model).solve(BacktrackParams(randomSeed = 0, maxDecisions = 100))

            assertEquals("2/3", assertIs<OpenTheoryResult.Sat>(result, result.toString()).assignment
                .realValue(parsed.realVarNames.getValue("z")))
        }
    }

    @Test
    fun `indicator cardinalities should preserve signed guards and opposite arms`() {
        for ((guard, thenValue) in listOf("b" to 1, "b" to 0, "(not b)" to 1, "(not b)" to 0)) {
            for (op in listOf("<=", ">=", "=")) {
                for (mask in 0..3) {
                    val b = mask and 1 != 0
                    val c = mask and 2 != 0
                    val selected = if (b == (guard == "b")) thenValue else 1 - thenValue
                    val count = selected + if (c) 1 else 0
                    val expected = when (op) {
                        "<=" -> count <= 1
                        ">=" -> count >= 1
                        else -> count == 1
                    }
                    val parsed = SmtLib.parse(
                        """
                        (declare-const b Bool) (declare-const c Bool)
                        (assert ($op (+ (ite $guard $thenValue ${1 - thenValue}) (ite c 1 0)) 1))
                        (assert ${if (b) "b" else "(not b)"})
                        (assert ${if (c) "c" else "(not c)"})
                        """.trimIndent(),
                    )

                    val result = BacktrackSolver(parsed.model.bake()).solve(BacktrackParams(randomSeed = 0))

                    assertEquals(0, parsed.model.numIntVars)
                    assertEquals(1, parsed.model.factors.filterIsInstance<Cardinality>().size)
                    if (expected) assertIs<SolveResult.Sat>(result) else assertIs<SolveResult.Unsat>(result)
                }
            }
        }
    }

    @Test
    fun `indicator cardinalities should preserve zero and full count thresholds`() {
        for (op in listOf("<=", ">=", "=")) {
            for (bound in listOf(0, 2)) {
                val parsed = SmtLib.parse(
                    """
                    (declare-const b Bool) (declare-const c Bool)
                    (assert ($op (+ (ite b 1 0) (ite c 1 0)) $bound))
                    """.trimIndent(),
                )

                val sample = assertIs<SolveResult.Sat>(
                    BacktrackSolver(parsed.model.bake()).solve(BacktrackParams(randomSeed = 0)),
                ).assignment

                val count = listOf("b", "c").count { sample.boolValue(parsed.boolVarNames.getValue(it)) }
                val holds = when (op) {
                    "<=" -> count <= bound
                    ">=" -> count >= bound
                    else -> count == bound
                }
                assertTrue(holds)
                assertEquals(0, parsed.model.numIntVars)
            }
        }
    }

    @Test
    fun `indicator cardinalities should retain shared composed guard definitions`() {
        for (mask in 0..7) {
            val a = mask and 1 != 0
            val b = mask and 2 != 0
            val c = mask and 4 != 0
            val expected = (if (a xor b) 1 else 0) + (if (b && c) 0 else 1) <= 1
            val parsed = SmtLib.parse(
                """
                (declare-const a Bool) (declare-const b Bool) (declare-const c Bool)
                (assert (let ((g (xor a b)) (h (and b c)))
                    (<= (+ (ite g 1 0) (ite h 0 1)) 1)))
                (assert ${if (a) "a" else "(not a)"})
                (assert ${if (b) "b" else "(not b)"})
                (assert ${if (c) "c" else "(not c)"})
                """.trimIndent(),
            )

            val result = BacktrackSolver(parsed.model.bake()).solve(BacktrackParams(randomSeed = 0))

            assertEquals(0, parsed.model.numIntVars)
            assertEquals(1, parsed.model.factors.filterIsInstance<Cardinality>().size)
            if (expected) assertIs<SolveResult.Sat>(result) else assertIs<SolveResult.Unsat>(result)
        }
    }

    @Test
    fun `indicator cardinalities should preserve independent numeric observations`() {
        for (mask in 0..3) {
            val a = mask and 1 != 0
            val b = mask and 2 != 0
            val parsed = SmtLib.parse(
                """
                (declare-const a Bool) (declare-const b Bool) (declare-const c Bool) (declare-const x Int)
                (assert (<= 0 x 1)) (assert (not c))
                (assert (let ((g (and a b))) (let ((v (ite g 1 0)))
                    (and (<= (+ v (ite c 1 0)) 1) (= x v)))))
                (assert ${if (a) "a" else "(not a)"})
                (assert ${if (b) "b" else "(not b)"})
                """.trimIndent(),
            )

            val sample = assertIs<SolveResult.Sat>(
                BacktrackSolver(parsed.model.bake()).solve(BacktrackParams(randomSeed = 0)),
            ).assignment

            assertEquals(if (a && b) 1L else 0L, sample.intValue(parsed.intVarNames.getValue("x")))
            assertEquals(1, parsed.model.factors.filterIsInstance<Cardinality>().size)
        }
    }

    @Test
    fun `repeated indicator guards should retain their arithmetic multiplicity`() {
        for (second in listOf("(ite b 1 0)", "(ite (not b) 0 1)")) {
            val parsed = SmtLib.parse(
                """
                (declare-const b Bool)
                (assert b) (assert (<= (+ (ite b 1 0) $second) 1))
                """.trimIndent(),
            )

            val result = BacktrackSolver(parsed.model.bake()).solve(BacktrackParams(randomSeed = 0))

            assertTrue(parsed.model.factors.none { it is Cardinality })
            assertIs<SolveResult.Unsat>(result)
        }
    }

    @Test
    fun `other indicator relations should preserve arithmetic fallback`() {
        for (relation in listOf(
            "(<= (+ (* 2 (ite b 1 0)) (ite c 1 0)) 2)",
            "(distinct (+ (ite b 1 0) (ite c 1 0)) 0)",
            "(or (= (+ (ite b 1 0) (ite c 1 0)) 1) c)",
            "(<= (+ (ite b 1 0) (ite c 1 0)) 3)",
            "(>= (+ (ite b 1 0) (ite c 1 0)) (- 1))",
        )) {
            val parsed = SmtLib.parse(
                """
                (declare-const b Bool) (declare-const c Bool)
                (assert b) (assert (not c)) (assert $relation)
                """.trimIndent(),
            )

            val result = BacktrackSolver(parsed.model.bake()).solve(BacktrackParams(randomSeed = 0))

            assertTrue(parsed.model.factors.none { it is Cardinality })
            assertIs<SolveResult.Sat>(result)
        }
    }

    @Test
    fun `indicator cardinalities should retain arithmetic dependencies of their guards`() {
        for (x in listOf(0, 2)) {
            val parsed = SmtLib.parse(
                """
                (declare-const b Bool) (declare-const c Bool) (declare-const x Int)
                (assert (<= 0 x 2)) (assert b) (assert (not c))
                (assert (ite b (= x $x) (= x 1)))
                (assert (<= (+ (ite (and b (< (div x 2) 1)) 1 0) (ite c 1 0)) 0))
                """.trimIndent(),
            )

            val result = BacktrackSolver(parsed.model.bake()).solve(BacktrackParams(randomSeed = 0))

            assertEquals(1, parsed.model.factors.filterIsInstance<Cardinality>().size)
            if (x == 2) assertIs<SolveResult.Sat>(result) else assertIs<SolveResult.Unsat>(result)
        }
    }

    @Test
    fun `affine integer conditionals should retain native selection`() {
        val parsed = SmtLib.parse(
            """
            (declare-const b Bool) (declare-const x Int) (declare-const y Int)
            (assert (and (<= 0 x 2) (<= 0 y 2) (> (ite b (+ x 1) y) 1)))
            """.trimIndent(),
        )

        val sample = assertIs<SolveResult.Sat>(
            BacktrackSolver(parsed.model.bake())
                .solve(BacktrackParams(randomSeed = 0)),
        ).assignment

        assertEquals(1, parsed.model.factors.filterIsInstance<Element>().size)
        val selected = if (sample.boolValue(parsed.boolVarNames.getValue("b"))) {
            sample.intValue(parsed.intVarNames.getValue("x")) + 1
        } else {
            sample.intValue(parsed.intVarNames.getValue("y"))
        }
        assertTrue(selected > 1)
    }

    @Test
    fun `short sparse decision lists should retain a complete default branch`() {
        val parsed = SmtLib.parse(
            """
            (declare-const i Int) (declare-const x Int)
            (assert (and (<= 0 i 1000) (<= 1 x 2)
                (= x (ite (= i 3) 1 (ite (= i 999) 2 0)))))
            """.trimIndent(),
        )

        val sample = assertIs<SolveResult.Sat>(
            BacktrackSolver(parsed.model.bake())
                .solve(BacktrackParams(randomSeed = 0)),
        ).assignment

        assertEquals(1, parsed.model.factors.filterIsInstance<Element>().size)
        val index = sample.intValue(parsed.intVarNames.getValue("i"))
        val expected = if (index == 3L) {
            1L
        } else if (index == 999L) {
            2L
        } else {
            0L
        }
        assertEquals(expected, sample.intValue(parsed.intVarNames.getValue("x")))
    }

    @Test
    fun `shared conditions should not select inconsistent arithmetic branches`() {
        val parsed = SmtLib.parse(
            """
            (declare-const b Bool)
            (assert (= (+ (ite b 0 1) (ite b 1 0)) 0))
            """.trimIndent(),
        )

        val result = BacktrackSolver(parsed.model.bake()).solve(BacktrackParams(randomSeed = 0))

        assertEquals(0, parsed.model.factors.filterIsInstance<Element>().size)
        assertIs<SolveResult.Unsat>(result)
    }

    @Test
    fun `adjacent constant conditionals should channel both signed guard branches`() {
        for (lower in listOf(-2, 0)) for (positive in listOf(false, true)) for (reversed in listOf(false, true)) {
            for (branch in listOf(false, true)) {
                val whenTrue = if (reversed) lower + 1 else lower
                val whenFalse = if (reversed) lower else lower + 1
                val trueTerm = if (whenTrue < 0) "(- ${-whenTrue})" else "$whenTrue"
                val falseTerm = if (whenFalse < 0) "(- ${-whenFalse})" else "$whenFalse"
                val parsed = SmtLib.parse(
                    """
                    (declare-const b Bool) (declare-const r Real)
                    (assert (= r (to_real (ite ${if (positive) "b" else "(not b)"}
                        $trueTerm $falseTerm))))
                    (assert ${if (branch) "b" else "(not b)"})
                    """.trimIndent(),
                )

                val result = BacktrackSolver.source(parsed.model).solve(BacktrackParams(randomSeed = 0))

                val assignment = assertIs<OpenTheoryResult.Sat>(result, result.toString()).assignment
                assertEquals(branch, assignment.boolValue(parsed.boolVarNames.getValue("b")))
                assertEquals(if (branch == positive) "$whenTrue" else "$whenFalse",
                    assignment.realValue(parsed.realVarNames.getValue("r")))
            }
        }
    }

    @Test
    fun `adjacent constant selections should retain only their value column`() {
        val parsed = SmtLib.parse(
            """
            (declare-const b Bool) (declare-const r Real)
            (assert (= r (to_real (ite b 0 1))))
            """.trimIndent(),
        )

        assertEquals(1, parsed.model.numIntVars)
        assertTrue(parsed.model.factors.none { it is Element })
    }

    @Test
    fun `nonadjacent constant selections should exclude values between their arms`() {
        for ((whenTrue, whenFalse, between) in listOf(Triple(0, 2, 1), Triple(2, 0, 1),
            Triple(-3, 0, -1), Triple(0, -3, -1))) {
            val trueTerm = if (whenTrue < 0) "(- ${-whenTrue})" else "$whenTrue"
            val falseTerm = if (whenFalse < 0) "(- ${-whenFalse})" else "$whenFalse"
            val betweenTerm = if (between < 0) "(- ${-between})" else "$between"
            val parsed = SmtLib.parse(
                """
                (declare-const b Bool) (declare-const r Real)
                (assert (= r (to_real (ite b $trueTerm $falseTerm))))
                (assert (= r $betweenTerm))
                """.trimIndent(),
            )

            val result = BacktrackSolver.source(parsed.model).solve(BacktrackParams(randomSeed = 0))

            assertIs<OpenTheoryResult.Unsat>(result, result.toString())
        }
    }

    @Test
    fun `adjacent extreme constant selections should preserve exact endpoints`() {
        for (lower in listOf(Long.MIN_VALUE, Long.MAX_VALUE - 1L)) for (reversed in listOf(false, true)) {
            for (branch in listOf(false, true)) {
                val whenTrue = if (reversed) lower + 1L else lower
                val whenFalse = if (reversed) lower else lower + 1L
                val builder = Compiler.Builder(Long.MIN_VALUE, Long.MAX_VALUE, false)
                val guard = builder.newBool()
                val selected = builder.newInt(lower, lower + 1L)
                builder.exclusiveIntegerSelection(selected, listOf(Lit.make(guard, true)),
                    listOf(LinComb(emptyMap(), whenTrue)), LinComb(emptyMap(), whenFalse))
                builder.factors.add(Clause(intArrayOf(Lit.make(guard, branch))))
                val parsed = builder.build()

                val result = BacktrackSolver(parsed.model.bake()).solve(
                    BacktrackParams(randomSeed = 0),
                )

                val assignment = assertIs<SolveResult.Sat>(result, result.toString()).assignment
                assertTrue(parsed.model.factors.any { it is Element })
                assertEquals(if (branch) whenTrue else whenFalse, assignment.intValue(selected))
            }
        }
    }

    @Test
    fun `signed conditional guards should receive point preferences without assigning source Booleans`() {
        for (positive in listOf(false, true)) {
            val parsed = SmtLib.parse(
                """
                (declare-const b Bool) (declare-const x Int) (declare-const y Int)
                (assert (and (<= (- 4) x 4) (<= (- 4) y 4)))
                (assert (= x y))
                (assert (= (+ x y) (ite ${if (positive) "b" else "(not b)"} (+ x 1) (- y 1))))
                """.trimIndent(),
            )
            val variable = parsed.boolVarNames.getValue("b")
            parsed.model.componentPlan().search(parsed.model, emptyMap()).use { planned ->
                assertNull(planned.candidateHints.preferredBool(variable))
                assertIs<ComponentResult.Consistent>(planned.session.initialize())

                val preferred = assertNotNull(planned.candidateHints.preferredBool(variable))

                assertNull(planned.session.boolValue(variable))
                assertIs<ComponentResult.Consistent>(
                    planned.session.push(SearchDecision.Bool(Lit.make(variable, preferred))),
                )
                val result = assertIs<SearchResult.Satisfied>(
                    planned.session.solve(
                        parsed.model.numBoolVars,
                        SearchSolveParams(maxDecisions = 16),
                        candidateHints = planned.candidateHints,
                    ),
                )
                assertEquals(preferred, result.model.valueOf(SearchBoolValue(variable)))
                val x = assertNotNull(
                    result.model.valueOf(
                        SearchExactIntValue(SourceIntId(parsed.intVarNames.getValue("x"))),
                    ),
                )
                assertEquals(if (preferred == positive) "1" else "-1", x.toString())
            }
        }
    }

    @Test
    fun `unused choices should preserve a derived guard shared by live conditional indexes`() {
        for (branch in listOf(false, true)) {
            val source = """
                (declare-const b Bool) (declare-const c Bool)
                (declare-const x Int) (declare-const y Int)
                (declare-const z Int) (declare-const w Int)
                (assert (and (<= (- 4) x 4) (<= (- 4) y 4) (<= (- 4) z 4) (<= (- 4) w 4)))
                (assert (let ((g (and b c))) (and
                    (= x y) (= z w)
                    (= (+ x y) (ite g (+ x 1) (- y 1)))
                    (= (+ z w) (ite (not g) (+ z 2) (- w 2)))
                    ${if (branch) "g" else "(not g)"}
            """.trimIndent()
            val live = SmtLib.parse("$source\n)))")
            val parsed = SmtLib.parse("$source\n(= (ite (and c b) 7 8) ${if (branch) 7 else 8})\n)))")

            val result = BacktrackSolver.source(parsed.model)
                .solve(BacktrackParams(randomSeed = 0, maxDecisions = 100))

            assertEquals(live.model.numIntVars, parsed.model.numIntVars)
            assertEquals(live.boolVarNames.keys, parsed.boolVarNames.keys)
            assertEquals(2, parsed.model.factors.filterIsInstance<Element>().map { it.idx }.distinct().size)
            val sample = assertIs<OpenTheoryResult.Sat>(result).assignment
            assertEquals(if (branch) "1" else "-1", sample.intValue(parsed.intVarNames.getValue("x")))
            assertEquals(if (branch) "-2" else "2", sample.intValue(parsed.intVarNames.getValue("z")))
        }
    }

    @Test
    fun `open conditional values should preserve selected signed branches`() {
        for (positive in listOf(false, true)) {
            for (branch in listOf(false, true)) {
                val parsed = SmtLib.parse(
                    """
                    (declare-const b Bool) (declare-const x Int) (declare-const y Int)
                    (assert (= x y))
                    (assert (= (+ x y) (ite ${if (positive) "b" else "(not b)"} (+ x 1) (- y 1))))
                    (assert ${if (branch) "b" else "(not b)"})
                    """.trimIndent(),
                )

                val result = BacktrackSolver.source(parsed.model)
                    .solve(BacktrackParams(randomSeed = 0, maxDecisions = 100))

                assertTrue(parsed.model.factors.none { it is Element })
                val sample = assertIs<OpenTheoryResult.Sat>(result, result.toString()).assignment
                assertEquals(branch, sample.boolValue(parsed.boolVarNames.getValue("b")))
                assertEquals(
                    if (branch == positive) "1" else "-1",
                    sample.intValue(parsed.intVarNames.getValue("x")),
                )
                assertEquals(
                    sample.intValue(parsed.intVarNames.getValue("x")),
                    sample.intValue(parsed.intVarNames.getValue("y")),
                )
            }
        }
    }

    @Test
    fun `negative real conditional guards should retain their source branch`() {
        for (branch in listOf(false, true)) {
            val parsed = SmtLib.parse(
                """
                (declare-const b Bool) (declare-const x Real) (declare-const y Real)
                (assert ${if (branch) "b" else "(not b)"}) (assert (= x (/ 2 3)))
                (assert (= y (ite (not b) (+ x (/ 1 3)) (- x (/ 1 3)))))
                """.trimIndent(),
            )

            val result = BacktrackSolver.source(parsed.model).solve(BacktrackParams(randomSeed = 0, maxDecisions = 100))

            assertEquals(
                if (branch) "1/3" else "1",
                assertIs<OpenTheoryResult.Sat>(result, result.toString()).assignment
                    .realValue(parsed.realVarNames.getValue("y")),
            )
        }
    }

    @Test
    fun `Boolean conditional normalization should remove unused private choices`() {
        val parsed = SmtLib.parse("(declare-const b Bool) (assert (= (ite b 1 2) 1))")

        assertEquals(0, parsed.model.numIntVars)
        assertTrue(parsed.model.factors.none { it is Element })
    }

    @Test
    fun `wide constants should remain exact beside an open conditional branch`() {
        val magnitude = "18446744073709551623"
        val parsed = SmtLib.parse(
            """
            (declare-const b Bool) (declare-const x Int) (declare-const y Int)
            (assert b) (assert (= y (ite b $magnitude x)))
            """.trimIndent(),
        )

        val result = BacktrackSolver.source(parsed.model).solve(BacktrackParams(randomSeed = 0, maxDecisions = 100))

        assertEquals(
            magnitude,
            assertIs<OpenTheoryResult.Sat>(result, result.toString()).assignment
                .intValue(parsed.intVarNames.getValue("y")),
        )
    }

    @Test
    fun `wide conditional digits should share one source condition`() {
        val magnitude = "18446744073709551623"
        for (branch in listOf(true, false)) {
            val parsed = SmtLib.parse(
                """
                (declare-const b Bool) (declare-const y Int)
                (assert ${if (branch) "b" else "(not b)"})
                (assert (= y (ite b $magnitude (- $magnitude))))
                (assert (${if (branch) ">=" else "<="} y ${if (branch) magnitude else "(- $magnitude)"}))
                """.trimIndent(),
            )

            val result = BacktrackSolver.source(parsed.model).solve(BacktrackParams(randomSeed = 0, maxDecisions = 100))

            val selections = parsed.model.factors.filterIsInstance<Element>()
            assertTrue(selections.isNotEmpty())
            assertEquals(1, selections.map { it.idx }.distinct().size)
            assertEquals(
                if (branch) magnitude else "-$magnitude",
                assertIs<OpenTheoryResult.Sat>(result, result.toString()).assignment
                    .intValue(parsed.intVarNames.getValue("y")),
            )
        }
    }

    @Test
    fun `real conditionals should retain exact rational affine branches`() {
        for (branch in listOf(true, false)) {
            val parsed = SmtLib.parse(
                """
                (declare-const b Bool) (declare-const x Real) (declare-const y Real)
                (assert ${if (branch) "b" else "(not b)"}) (assert (= x (/ 2 3)))
                (assert (= y (ite b (+ x (/ 1 3)) (- x (/ 1 3)))))
                """.trimIndent(),
            )

            val result = BacktrackSolver.source(parsed.model).solve(BacktrackParams(randomSeed = 0, maxDecisions = 100))

            assertEquals(
                if (branch) "1" else "1/3",
                assertIs<OpenTheoryResult.Sat>(result, result.toString()).assignment
                    .realValue(parsed.realVarNames.getValue("y")),
            )
            assertEquals(1, parsed.model.factors.filterIsInstance<RealElement>().size)
        }
    }

    @Test
    fun `shared real conditional guards should not choose incompatible branches`() {
        val parsed = SmtLib.parse(
            """
            (declare-const b Bool) (declare-const x Real) (declare-const y Real)
            (assert (= x (ite b 0.0 1.0))) (assert (= y (ite b 1.0 0.0)))
            (assert (= (+ x y) 0.0))
            """.trimIndent(),
        )

        val result = BacktrackSolver.source(parsed.model).solve(BacktrackParams(randomSeed = 0, maxDecisions = 100))

        assertIs<OpenTheoryResult.Unsat>(result)
    }
}
