package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.Lit
import com.eignex.klause.lowering.PolynomialSourceInterrupted
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.search.ClauseSearchComponent
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SearchSolveParams
import com.eignex.klause.theory.qflra.ExactLiraAssignment
import com.eignex.klause.theory.qflra.ExactLiraSearchComponent
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.StringCharSource
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SmtLibConditionalEqualityTest {
    @Test
    fun `an expanded conditional query should retain the same Boolean frontier as its direct formula`() {
        val prefix = """
            (declare-const b Bool) (declare-const x Int) (declare-const r Bool)
            (assert true)
        """.trimIndent()
        val conditional = SmtLib.parse("$prefix (assert (= r (= (ite b x 0) 1)))")
        val direct = SmtLib.parse("$prefix (assert (= r (and b (= x 1))))")

        assertEquals(direct.model.numBoolVars, conditional.model.numBoolVars)
        assertEquals(direct.boolVarNames.keys, conditional.boolVarNames.keys)
    }

    @Test
    fun `dependent expanded queries should preserve declared Boolean values`() {
        for (selected in listOf(true, false)) {
            val parsed = SmtLib.parse(
                """
                (declare-const b Bool) (declare-const c Bool)
                (declare-const r Bool) (declare-const x Int)
                (assert ${if (selected) "b" else "(not b)"}) (assert c)
                (assert (= x 1))
                (assert (let ((u (ite b x 0))) (let ((v (ite c u 2))) (= r (= v 1)))))
                """.trimIndent(),
            )

            val result = BacktrackSolver.source(parsed.model)
                .solve(BacktrackParams(randomSeed = 0, maxDecisions = 100))

            val sample = assertIs<OpenTheoryResult.Sat>(result, result.toString()).assignment
            assertEquals(selected, sample.boolValue(parsed.boolVarNames.getValue("b")))
            assertEquals(true, sample.boolValue(parsed.boolVarNames.getValue("c")))
            assertEquals(selected, sample.boolValue(parsed.boolVarNames.getValue("r")))
            assertEquals("1", sample.intValue(parsed.intVarNames.getValue("x")))
        }
    }

    @Test
    fun `a negative expanded query should preserve its declared result`() {
        val parsed = SmtLib.parse(
            """
            (declare-const b Bool) (declare-const r Bool)
            (assert (= r (= (ite b 1 2) 2))) (assert r)
            """.trimIndent(),
        )

        val result = BacktrackSolver.source(parsed.model)
            .solve(BacktrackParams(randomSeed = 0, maxDecisions = 100))

        val sample = assertIs<OpenTheoryResult.Sat>(result, result.toString()).assignment
        assertEquals(false, sample.boolValue(parsed.boolVarNames.getValue("b")))
        assertEquals(true, sample.boolValue(parsed.boolVarNames.getValue("r")))
    }

    @Test
    fun `an aliased query should retain an independent numeric objective consumer`() {
        val parsed = SmtLib.parse(
            """
            (declare-const b Bool) (declare-const x Int) (declare-const y Int)
            (declare-const r Bool)
            (assert (let ((v (ite b x 0))) (and (= y v) (= r (= v 1)))))
            (assert r) (assert b) (assert (= x 1))
            (minimize y)
            """.trimIndent(),
        )

        val result = BacktrackSolver.source(parsed.model).optimize(
            assertNotNull(parsed.objective).toLinearObjective(),
        )

        val optimum = assertIs<QuadraticOptimizationResult.Optimal>(result.result)
        assertEquals(BigFraction.ofLong(1), optimum.incumbent.value.rational)
    }

    @Test
    fun `late child images should reject multiple values within one shared allowance`() {
        val builder = Compiler.Builder(
            Long.MIN_VALUE,
            Long.MAX_VALUE,
            false,
            conditionalEqualities = SmtLibConditionalEquality(workLimit = 6),
        )
        val reader =
            SExprReader(
                StringCharSource(
                    """
            (declare-const b Bool) (declare-const s Int) (assert (<= 0 s 1))
            (declare-const r10 Bool) (declare-const r11 Bool) (declare-const r12 Bool)
            (assert (let ((v (ite b (ite (= s 0) 1 2) 3)))
                (and (= r10 (= v 10)) (= r11 (= v 11)) (= r12 (= v 12)))))
                    """.trimIndent(),
                ),
            )
        while (true) builder.command(reader.readCommandOrNull() ?: break)
        val parsed = builder.build()
        val session = SearchSession(
            listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>())),
            atoms = SearchAtomRegistry(parsed.model.numBoolVars),
        )

        val result = session.initialize()

        assertEquals(parsed.intVarNames.size, parsed.model.numIntVars)
        assertTrue(parsed.model.factors.none { it is Element })
        assertIs<ComponentResult.Consistent>(result)
        for (value in 10..12) assertEquals(false, session.boolValue(parsed.boolVarNames.getValue("r$value")))
    }

    @Test
    fun `an incomplete late image should retain exact numeric comparisons`() {
        val builder = Compiler.Builder(
            Long.MIN_VALUE,
            Long.MAX_VALUE,
            false,
            conditionalEqualities = SmtLibConditionalEquality(workLimit = 3),
        )
        val reader =
            SExprReader(
                StringCharSource(
                    """
            (declare-const b Bool) (declare-const s Int) (assert (<= 0 s 1))
            (declare-const r10 Bool) (declare-const r11 Bool) (declare-const r12 Bool)
            (assert (let ((v (ite b (ite (= s 0) 1 2) 3)))
                (and (= r10 (= v 10)) (= r11 (= v 11)) (= r12 (= v 12)))))
                    """.trimIndent(),
                ),
            )
        while (true) builder.command(reader.readCommandOrNull() ?: break)
        val parsed = builder.build()

        val result = BacktrackSolver.source(parsed.model)
            .solve(BacktrackParams(randomSeed = 0, maxDecisions = 100))

        assertTrue(parsed.model.factors.any { it is Element })
        val sample = assertIs<OpenTheoryResult.Sat>(result, result.toString()).assignment
        for (value in 10..12) assertEquals(false, sample.boolValue(parsed.boolVarNames.getValue("r$value")))
    }

    @Test
    fun `completed guard images should exclude an open default without bounding its source`() {
        val builder = Compiler.Builder(
            Long.MIN_VALUE,
            Long.MAX_VALUE,
            false,
            conditionalEqualities = SmtLibConditionalEquality(workLimit = 128),
        )
        val reader =
            SExprReader(
                StringCharSource(
                    """
            (declare-const b Bool) (declare-const s Int) (declare-const x Int)
            (assert (<= 0 s 1)) (assert (> x 1000000))
            (assert (let ((v (ite b (ite (= s 0) 1 2) 3)))
                (= (ite (= v 1) 4 (ite (= v 2) 5 (ite (= v 3) 6 x))) 99)))
                    """.trimIndent(),
                ),
            )
        while (true) builder.command(reader.readCommandOrNull() ?: break)
        val parsed = builder.build()
        val session = SearchSession(
            listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>())),
            atoms = SearchAtomRegistry(parsed.model.numBoolVars),
        )

        val result = session.initialize()

        assertEquals(parsed.intVarNames.size, parsed.model.numIntVars)
        assertFalse(parsed.model.intBounds.hasUpper(parsed.intVarNames.getValue("x")))
        assertIs<ComponentResult.Conflict>(result)
    }

    @Test
    fun `an independent numeric consumer should retain a late conditional value`() {
        val parsed = SmtLib.parse(
            """
            (declare-const b Bool) (declare-const s Int) (declare-const x Int) (declare-const r Bool)
            (assert (<= 0 s 1)) (assert (= x 3))
            (assert (let ((v (ite b (ite (= s 0) 1 2) 3))) (and (= r (= v 10)) (= x v))))
            """.trimIndent(),
        )

        val result = BacktrackSolver.source(parsed.model)
            .solve(BacktrackParams(randomSeed = 0, maxDecisions = 100))

        assertTrue(parsed.model.factors.any { it is Element })
        val sample = assertIs<OpenTheoryResult.Sat>(result, result.toString()).assignment
        assertEquals(false, sample.boolValue(parsed.boolVarNames.getValue("b")))
        assertEquals("3", sample.intValue(parsed.intVarNames.getValue("x")))
    }

    @Test
    fun `cancellation during late image completion should abort parsing`() {
        val images = SmtLibConditionalEquality()
        var observedBuilder: Compiler.Builder? = null
        var sourceIntegers = Int.MAX_VALUE
        var completionPolls = 0
        val stop = Cancellation {
            val current = observedBuilder
            current != null && current.nextInt > sourceIntegers &&
                (sourceIntegers until current.nextInt).all(images::isDefined) && ++completionPolls == 2
        }
        val builder = Compiler.Builder(
            Long.MIN_VALUE,
            Long.MAX_VALUE,
            false,
            conditionalEqualities = images,
            sourceCancellation = stop,
        )
        observedBuilder = builder
        val reader =
            SExprReader(
                StringCharSource(
                    """
            (declare-const b Bool) (declare-const s Int) (assert (<= 0 s 1))
            (assert (= (ite b (ite (= s 0) 1 2) 3) 10))
                    """.trimIndent(),
                ),
            )
        while (true) builder.command(reader.readCommandOrNull() ?: break)
        sourceIntegers = builder.intNames.size

        assertFailsWith<PolynomialSourceInterrupted> { builder.build() }
    }

    @Test
    fun `repeated conditional guards should exclude unreachable values without equality expansion`() {
        val terms = listOf(
            "(ite c 5 (ite c 3 41))",
            "(ite c (ite c 5 3) 41)",
            "(ite c 5 (ite (not c) 41 3))",
            "(ite c (ite (not c) 3 5) 41)",
        )
        for (guard in listOf("b", "(= x 0)")) {
            for (term in terms) {
                val builder = Compiler.Builder(
                    Long.MIN_VALUE,
                    Long.MAX_VALUE,
                    false,
                    conditionalEqualities = SmtLibConditionalEquality(workLimit = 0),
                )
                val reader = SExprReader(
                    StringCharSource(
                        "(declare-const b Bool) (declare-const x Int) (assert (let ((c $guard)) (= $term 3)))",
                    ),
                )
                while (true) builder.command(reader.readCommandOrNull() ?: break)
                val parsed = builder.build()
                val session = SearchSession(
                    listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>())),
                    atoms = SearchAtomRegistry(parsed.model.numBoolVars),
                )

                assertIs<ComponentResult.Conflict>(session.initialize(), "$guard: $term")
            }
        }
    }

    @Test
    fun `restricting one conditional use should preserve an independent consumer of its inner definition`() {
        val parsed = SmtLib.parse(
            """
            (declare-const b Bool) (declare-const x Int)
            (assert (not b)) (assert (= x 5))
            (assert (let ((inner (ite b x 7)))
                (and (= (ite b inner 9) 9) (= inner 7))))
            """.trimIndent(),
        )
        ExactLiraSearchComponent(parsed.model).use { component ->
            val session = SearchSession(
                listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>()), component),
                atoms = SearchAtomRegistry(parsed.model.numBoolVars),
            )
            assertIs<ComponentResult.Consistent>(session.initialize())

            val result = assertIs<SearchResult.Satisfied>(session.solve(parsed.model.numBoolVars))

            val assignment = assertNotNull(result.model.valueOf<ExactLiraAssignment>(component))
            assertEquals(bigIntOf(5), assignment.ints[parsed.intVarNames.getValue("x")])
        }
    }

    @Test
    fun `unused difference equality gates should release their arithmetic reads`() {
        val parsed = SmtLib.parse(
            """
                (declare-const x Int) (declare-const y Int)
                (assert (let ((s (ite (= (- x y) 3) 1 2))) (= (ite true 1 s) 1)))
            """.trimIndent(),
        )

        assertTrue(parsed.model.factors.all { it.intVars.isEmpty() })
    }

    @Test
    fun `a covered identity default should exclude its old selector values without expansion`() {
        val builder = Compiler.Builder(
            Long.MIN_VALUE,
            Long.MAX_VALUE,
            false,
            conditionalEqualities = SmtLibConditionalEquality(workLimit = 0),
        )
        val reader = SExprReader(
            StringCharSource(
                """
                (declare-const b Bool)
                (assert (let ((s (ite b 1 2)))
                    (let ((r (ite (= s 1) 3 (ite (= s 2) 4 s)))) (= r 1))))
                """.trimIndent(),
            ),
        )
        while (true) builder.command(reader.readCommandOrNull() ?: break)
        val parsed = builder.build()
        val session = SearchSession(
            listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>())),
            atoms = SearchAtomRegistry(parsed.model.numBoolVars),
        )

        assertIs<ComponentResult.Conflict>(session.initialize())
    }

    @Test
    fun `a covered selector should exclude an open default without expansion`() {
        val builder = Compiler.Builder(
            Long.MIN_VALUE,
            Long.MAX_VALUE,
            false,
            conditionalEqualities = SmtLibConditionalEquality(workLimit = 0),
        )
        val reader = SExprReader(
            StringCharSource(
                """
                (declare-const b Bool) (declare-const x Int)
                (assert (let ((s (ite b 1 2)))
                    (let ((r (ite (= s 1) 3 (ite (= s 2) 4 x)))) (= r 99))))
                """.trimIndent(),
            ),
        )
        while (true) builder.command(reader.readCommandOrNull() ?: break)
        val parsed = builder.build()
        val session = SearchSession(
            listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>())),
            atoms = SearchAtomRegistry(parsed.model.numBoolVars),
        )

        assertIs<ComponentResult.Conflict>(session.initialize())
    }

    @Test
    fun `an uncovered selector value should retain its open default without expansion`() {
        val builder = Compiler.Builder(
            Long.MIN_VALUE,
            Long.MAX_VALUE,
            false,
            conditionalEqualities = SmtLibConditionalEquality(workLimit = 0),
        )
        val reader = SExprReader(
            StringCharSource(
                """
                (declare-const b Bool) (declare-const x Int)
                (assert (not b))
                (assert (let ((s (ite b 1 2))) (let ((r (ite (= s 1) 3 x))) (= r 99))))
                """.trimIndent(),
            ),
        )
        while (true) builder.command(reader.readCommandOrNull() ?: break)
        val parsed = builder.build()
        ExactLiraSearchComponent(parsed.model).use { component ->
            val session = SearchSession(
                listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>()), component),
                atoms = SearchAtomRegistry(parsed.model.numBoolVars),
            )
            assertIs<ComponentResult.Consistent>(session.initialize())

            val result = assertIs<SearchResult.Satisfied>(session.solve(parsed.model.numBoolVars))

            val assignment = assertNotNull(result.model.valueOf<ExactLiraAssignment>(component))
            assertEquals(bigIntOf(99), assignment.ints[parsed.intVarNames.getValue("x")])
        }
    }

    @Test
    fun `a final comparison should refute the source after earlier comparisons exceed the expansion budget`() {
        val builder = Compiler.Builder(
            Long.MIN_VALUE,
            Long.MAX_VALUE,
            false,
            conditionalEqualities = SmtLibConditionalEquality(workLimit = 3),
        )
        val reader = SExprReader(
            StringCharSource(
                """
                (declare-const x Int) (declare-const s Int) (declare-const early Bool)
                (assert (>= s 0)) (assert (<= s 2))
                (assert (= early (= (ite (<= x 0) x 2) 1)))
                (assert (= (ite (= s 0) s 2) 1))
                """.trimIndent(),
            ),
        )
        while (true) builder.command(reader.readCommandOrNull() ?: break)
        val parsed = builder.build()
        val session = SearchSession(
            listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>())),
            atoms = SearchAtomRegistry(parsed.model.numBoolVars),
        )

        assertIs<ComponentResult.Conflict>(session.initialize())
    }

    @Test
    fun `an exhausted comparison expansion should retain its exact arithmetic meaning`() {
        for (value in listOf(0, 1)) {
            val builder = Compiler.Builder(
                Long.MIN_VALUE,
                Long.MAX_VALUE,
                false,
                conditionalEqualities = SmtLibConditionalEquality(workLimit = 0),
            )
            val reader = SExprReader(
                StringCharSource(
                    """
                    (declare-const x Int) (declare-const result Bool)
                    (assert (= x $value))
                    (assert (= result (= (ite (<= x 0) x 2) 0)))
                    """.trimIndent(),
                ),
            )
            while (true) builder.command(reader.readCommandOrNull() ?: break)
            val parsed = builder.build()
            ExactLiraSearchComponent(parsed.model).use { component ->
                val session = SearchSession(
                    listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>()), component),
                    atoms = SearchAtomRegistry(parsed.model.numBoolVars),
                )
                assertIs<ComponentResult.Consistent>(session.initialize())

                val result = assertIs<SearchResult.Satisfied>(session.solve(parsed.model.numBoolVars))

                val assignment = assertNotNull(result.model.valueOf<ExactLiraAssignment>(component))
                assertEquals(value == 0, assignment.bools[parsed.boolVarNames.getValue("result")])
            }
        }
    }

    @Test
    fun `a constant conditional result should complete without arithmetic predicate decisions`() {
        val guards = listOf(
            "(<= x 0)",
            "(= x 0)",
            "(and (<= x 0) (= x 0))",
            "(or (<= x 0) (= x 0))",
            "(xor (<= x 0) (= x 0))",
            "(=> (<= x 0) (= x 0))",
            "(= (<= x 0) (= x 0))",
            "(ite (<= x 0) (= x 0) (< x 0))",
        )
        for (guard in guards) {
            val parsed = SmtLib.parse(
                "(declare-const x Int) (assert (= (ite $guard 1 1) 1))",
            )
            val session = SearchSession(
                listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>())),
                atoms = SearchAtomRegistry(parsed.model.numBoolVars),
            )
            assertIs<ComponentResult.Consistent>(session.initialize())

            assertIs<SearchResult.Satisfied>(
                session.solve(parsed.model.numBoolVars, SearchSolveParams(maxDecisions = 0)),
                guard,
            )
        }
    }

    @Test
    fun `a Boolean gate should retain its meaning when distinct consumes it`() {
        val parsed = SmtLib.parse(
            """
            (declare-const b Bool) (declare-const c Bool)
            (assert (not b)) (assert (not c))
            (assert (= (ite b 1 1) 1))
            (assert (distinct (and b c) false))
            """.trimIndent(),
        )
        val result = BacktrackSolver.source(parsed.model).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `a source Boolean should retain a later arithmetic comparison after unused predicates are omitted`() {
        for (value in listOf(3, 4)) {
            val parsed = SmtLib.parse(
                """
                (declare-const b Bool) (declare-const x Int) (declare-const result Bool)
                (assert b)
                (assert (= (ite b 1 2) 1))
                (assert (= result (= x 3)))
                (assert (= x $value))
                """.trimIndent(),
            )
            ExactLiraSearchComponent(parsed.model).use { component ->
                val session = SearchSession(
                    listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>()), component),
                    atoms = SearchAtomRegistry(parsed.model.numBoolVars),
                )
                assertIs<ComponentResult.Consistent>(session.initialize())

                val result = assertIs<SearchResult.Satisfied>(session.solve(parsed.model.numBoolVars))

                val assignment = assertNotNull(result.model.valueOf<ExactLiraAssignment>(component))
                assertEquals(true, assignment.bools[parsed.boolVarNames.getValue("b")])
                assertEquals(value == 3, assignment.bools[parsed.boolVarNames.getValue("result")])
            }
        }
    }

    @Test
    fun `a conditional should not take a conflicting selector value without arithmetic checks`() {
        for (comparison in listOf("(= (ite (= s 0) s 2) 1)", "(= (ite (= s 0) 2 s) 0)")) {
            val parsed = SmtLib.parse(
                """
                (declare-const s Int)
                (assert (>= s 0)) (assert (<= s 2))
                (assert $comparison)
                """.trimIndent(),
            )
            val session = SearchSession(
                listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>())),
                atoms = SearchAtomRegistry(parsed.model.numBoolVars),
            )

            assertIs<ComponentResult.Conflict>(session.initialize(), comparison)
        }
    }

    @Test
    fun `a conditional should retain its guard meaning after another selector shares the literal`() {
        val parsed = SmtLib.parse(
            """
            (declare-const b Bool)
            (assert (let ((first (ite b 0 1)))
                (let ((updated (ite (= first 0) first 2)))
                    (let ((other (ite b 5 7)))
                        (and (or (= other 5) (= other 7)) (= updated 1))))))
            """.trimIndent(),
        )
        val session = SearchSession(
            listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>())),
            atoms = SearchAtomRegistry(parsed.model.numBoolVars),
        )

        assertIs<ComponentResult.Conflict>(session.initialize())
    }

    @Test
    fun `a conditional default should remain available at a different selector value`() {
        for (value in 0..2) {
            val parsed = SmtLib.parse(
                """
                (declare-const s Int) (declare-const result Bool)
                (assert (>= s 0)) (assert (<= s 2)) (assert (= s $value))
                (assert (= result (= (ite (= s 0) 2 s) 1)))
                """.trimIndent(),
            )
            ExactLiraSearchComponent(parsed.model).use { component ->
                val session = SearchSession(
                    listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>()), component),
                    atoms = SearchAtomRegistry(parsed.model.numBoolVars),
                )
                assertIs<ComponentResult.Consistent>(session.initialize())

                val result = assertIs<SearchResult.Satisfied>(session.solve(parsed.model.numBoolVars))

                val assignment = assertNotNull(result.model.valueOf<ExactLiraAssignment>(component))
                assertEquals(value == 1, assignment.bools[parsed.boolVarNames.getValue("result")])
            }
        }
    }

    @Test
    fun `a conditional should compare a branch from a different selector`() {
        val parsed = SmtLib.parse(
            """
            (declare-const s Int) (declare-const t Int)
            (assert (= s 0)) (assert (= t 1))
            (assert (= (ite (= s 0) t 2) 1))
            """.trimIndent(),
        )
        ExactLiraSearchComponent(parsed.model).use { component ->
            val session = SearchSession(
                listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>()), component),
                atoms = SearchAtomRegistry(parsed.model.numBoolVars),
            )
            assertIs<ComponentResult.Consistent>(session.initialize())

            val result = assertIs<SearchResult.Satisfied>(session.solve(parsed.model.numBoolVars))

            val assignment = assertNotNull(result.model.valueOf<ExactLiraAssignment>(component))
            assertEquals(bigIntOf(1), assignment.ints[parsed.intVarNames.getValue("t")])
        }
    }

    @Test
    fun `an equality key beyond a signed word should not exclude a feasible conditional branch`() {
        val parsed = SmtLib.parse(
            """
            (declare-const x Int)
            (assert (= x (- 9223372036854775808)))
            (assert (= (ite (not (= (- x) (- 9223372036854775808))) x 0) (- 9223372036854775808)))
            """.trimIndent(),
        )
        ExactLiraSearchComponent(parsed.model).use { component ->
            val session = SearchSession(
                listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>()), component),
                atoms = SearchAtomRegistry(parsed.model.numBoolVars),
            )
            assertIs<ComponentResult.Consistent>(session.initialize())

            val result = assertIs<SearchResult.Satisfied>(session.solve(parsed.model.numBoolVars))

            val assignment = assertNotNull(result.model.valueOf<ExactLiraAssignment>(component))
            assertEquals(bigIntOf(Long.MIN_VALUE), assignment.ints[parsed.intVarNames.getValue("x")])
        }
    }

    @Test
    fun `a mixed real constraint should retain its conditional value after unused definitions are omitted`() {
        val parsed = SmtLib.parse(
            """
            (declare-const b Bool) (declare-const x Int) (declare-const r Real)
            (assert b) (assert (= x 9))
            (assert (= (ite b 0 1) 0))
            (assert (= r (+ (to_real (ite b x 7)) 0.5)))
            """.trimIndent(),
        )
        ExactLiraSearchComponent(parsed.model).use { component ->
            val session = SearchSession(
                listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>()), component),
                atoms = SearchAtomRegistry(parsed.model.numBoolVars),
            )
            assertIs<ComponentResult.Consistent>(session.initialize())

            val result = assertIs<SearchResult.Satisfied>(session.solve(parsed.model.numBoolVars))

            val assignment = assertNotNull(result.model.valueOf<ExactLiraAssignment>(component))
            assertEquals(
                BigFraction.of(bigIntOf(19), bigIntOf(2)),
                assignment.reals[parsed.realVarNames.getValue("r")],
            )
        }
    }

    @Test
    fun `a complete symbolic selector table should resolve from its source Boolean values`() {
        for (branch in listOf(true, false)) {
            var images = (0..15).map { it.toString() }
            for (bit in 0..3) {
                images = images.chunked(2).map { pair -> "(ite b$bit ${pair[1]} ${pair[0]})" }
            }
            val declarations = (0..3).joinToString(" ") { "(declare-const b$it Bool)" }
            val assertions = (0..3).joinToString(" ") { "(assert ${if (branch) "b$it" else "(not b$it)"})" }
            val chain = (0..15).toList().foldRight("99") { key, rest -> "(ite (= selector $key) ${key * 10} $rest)" }
            val parsed = SmtLib.parse(
                """
                $declarations $assertions
                (assert (let ((selector ${images.single()}))
                    (let ((result $chain)) (= result ${if (branch) 150 else 0}))))
                """.trimIndent(),
            )
            val session = SearchSession(
                listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>())),
                atoms = SearchAtomRegistry(parsed.model.numBoolVars),
            )
            assertIs<ComponentResult.Consistent>(session.initialize())

            assertIs<SearchResult.Satisfied>(
                session.solve(parsed.model.numBoolVars, SearchSolveParams(maxDecisions = 0)),
            )

            for (bit in 0..3) assertEquals(branch, session.boolValue(parsed.boolVarNames.getValue("b$bit")))
        }
    }

    @Test
    fun `a decision list over a symbolic selector should complete without numeric predicate decisions`() {
        for (branch in listOf(true, false)) {
            val chain = (0..15).toList().foldRight("99") { key, rest -> "(ite (= selector $key) $key $rest)" }
            val parsed = SmtLib.parse(
                """
                (declare-const b Bool)
                (assert ${if (branch) "b" else "(not b)"})
                (assert (let ((selector (ite b 7 12))) (let ((result $chain)) (= result ${if (branch) 7 else 12}))))
                """.trimIndent(),
            )
            val session = SearchSession(
                listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>())),
                atoms = SearchAtomRegistry(parsed.model.numBoolVars),
            )
            assertIs<ComponentResult.Consistent>(session.initialize())

            assertIs<SearchResult.Satisfied>(
                session.solve(parsed.model.numBoolVars, SearchSolveParams(maxDecisions = 0)),
            )

            assertEquals(branch, session.boolValue(parsed.boolVarNames.getValue("b")))
        }
    }

    @Test
    fun `a determined conditional comparison should complete without Boolean decisions`() {
        val parsed = SmtLib.parse(
            "(declare-const b Bool) (assert (= (ite b 1 2) 1))",
        )
        val session = SearchSession(
            listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>())),
            atoms = SearchAtomRegistry(parsed.model.numBoolVars),
        )
        assertIs<ComponentResult.Consistent>(session.initialize())

        assertIs<SearchResult.Satisfied>(session.solve(parsed.model.numBoolVars, SearchSolveParams(maxDecisions = 0)))

        assertEquals(true, session.boolValue(parsed.boolVarNames.getValue("b")))
    }

    @Test
    fun `a conditional compared with a constant should retain its independent arithmetic use`() {
        for (branch in listOf(1, 2)) {
            val parsed = SmtLib.parse(
                """
                (declare-const b Bool) (declare-const x Int)
                (assert (let ((t (ite b 1 2))) (and (= t $branch) (= (+ t x) 4))))
                """.trimIndent(),
            )
            ExactLiraSearchComponent(parsed.model).use { component ->
                val session = SearchSession(
                    listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>()), component),
                    atoms = SearchAtomRegistry(parsed.model.numBoolVars),
                )
                assertIs<ComponentResult.Consistent>(session.initialize())

                val result = assertIs<SearchResult.Satisfied>(session.solve(parsed.model.numBoolVars))

                val assignment = assertNotNull(result.model.valueOf<ExactLiraAssignment>(component))
                assertEquals(bigIntOf(4 - branch), assignment.ints[parsed.intVarNames.getValue("x")])
            }
        }
    }

    @Test
    fun `nested numeric conditional reads should retain the definitions they depend on`() {
        val parsed = SmtLib.parse(
            """
            (declare-const b Bool) (declare-const c Bool) (declare-const x Int)
            (assert b) (assert (not c))
            (assert (let ((inner (ite c 4 9))) (let ((outer (ite b inner 8))) (= x outer))))
            """.trimIndent(),
        )
        ExactLiraSearchComponent(parsed.model).use { component ->
            val session = SearchSession(
                listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>()), component),
                atoms = SearchAtomRegistry(parsed.model.numBoolVars),
            )
            assertIs<ComponentResult.Consistent>(session.initialize())

            val result = assertIs<SearchResult.Satisfied>(session.solve(parsed.model.numBoolVars))

            val assignment = assertNotNull(result.model.valueOf<ExactLiraAssignment>(component))
            assertEquals(bigIntOf(9), assignment.ints[parsed.intVarNames.getValue("x")])
        }
    }

    @Test
    fun `a conditional objective should retain its branch value after unused definitions are omitted`() {
        for (branch in listOf(true, false)) {
            val parsed = SmtLib.parse(
                """
                (declare-const b Bool)
                (assert ${if (branch) "b" else "(not b)"})
                (assert (= (ite b 1 2) ${if (branch) 1 else 2}))
                (minimize (ite b 3 8))
                """.trimIndent(),
            )
            val result = BacktrackSolver.source(parsed.model).optimize(
                assertNotNull(parsed.objective).toLinearObjective(),
            )

            val optimum = assertIs<QuadraticOptimizationResult.Optimal>(result.result)
            assertEquals(BigFraction.ofLong(if (branch) 3 else 8), optimum.incumbent.value.rational)
        }
    }

    @Test
    fun `a conditional branch comparison should retain exact arithmetic beyond a signed word`() {
        val parsed = SmtLib.parse(
            """
            (declare-const b Bool) (declare-const x Int)
            (assert (or (= (ite b (+ x 9223372036854775807) 0) (- 9223372036854775808)) false))
            """.trimIndent(),
        )
        val result = BacktrackSolver.source(parsed.model).solve()

        val assignment = assertIs<OpenTheoryResult.Sat>(result).assignment
        assertEquals(true, assignment.boolValue(parsed.boolVarNames.getValue("b")))
        assertEquals("-18446744073709551615", assignment.intValue(parsed.intVarNames.getValue("x")))
    }

    @Test
    fun `nested shared conditional bindings should propagate through their complete definition chain`() {
        val prefix = (0..127).joinToString(" ") { index ->
            val branch = if (index == 0) "1" else "t${index - 1}"
            "(let ((t$index (ite b $branch 2)))"
        }
        val parsed = SmtLib.parse(
            "(declare-const b Bool) (assert $prefix (or (= t127 1) false)${")".repeat(128)} )",
        )
        val session = SearchSession(
            listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>())),
            atoms = SearchAtomRegistry(parsed.model.numBoolVars),
        )

        assertIs<ComponentResult.Consistent>(session.initialize())

        assertEquals(true, session.boolValue(parsed.boolVarNames.getValue("b")))
    }

    @Test
    fun `a nested conditional comparison should propagate its source guard without arithmetic checks`() {
        val parsed = SmtLib.parse(
            """
            (declare-const b Bool)
            (assert (not (= (ite b 5 (ite b 3 41)) 5)))
            """.trimIndent(),
        )
        val session = SearchSession(
            listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>())),
            atoms = SearchAtomRegistry(parsed.model.numBoolVars),
        )

        assertIs<ComponentResult.Consistent>(session.initialize())

        assertEquals(false, session.boolValue(parsed.boolVarNames.getValue("b")))
        assertIs<ComponentResult.Conflict>(
            session.push(SearchDecision.Bool(Lit.make(parsed.boolVarNames.getValue("b"), true))),
        )
    }

    @Test
    fun `a conditional constant outside every branch image should refute through Boolean clauses`() {
        val parsed = SmtLib.parse(
            """
            (declare-const b Bool) (declare-const c Bool)
            (assert (or (= (ite b 1 (ite c 3 5)) 2) false))
            """.trimIndent(),
        )
        val session = SearchSession(
            listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>())),
            atoms = SearchAtomRegistry(parsed.model.numBoolVars),
        )

        assertIs<ComponentResult.Conflict>(session.initialize())
    }

    @Test
    fun `an open default should retain its comparison when the constant arm is inactive`() {
        for (expected in listOf(true, false)) {
            val assertion = if (expected) "(= t 7)" else "(not (= t 7))"
            val parsed = SmtLib.parse(
                """
                (declare-const b Bool) (declare-const x Int) (declare-const result Bool)
                (assert (let ((t (ite b 7 x))) (and (not b) $assertion (= result (= x 7)))))
                """.trimIndent(),
            )
            val session = SearchSession(
                listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>())),
                atoms = SearchAtomRegistry(parsed.model.numBoolVars),
            )

            assertIs<ComponentResult.Consistent>(session.initialize())

            assertEquals(expected, session.boolValue(parsed.boolVarNames.getValue("result")))
        }
    }

    @Test
    fun `a completed decision list should exclude constants outside its arms and default`() {
        val chain = (0..15).toList().foldRight("99") { key, rest -> "(ite (= s $key) $key $rest)" }
        val parsed = SmtLib.parse(
            """
            (declare-const s Int)
            (assert (>= s 0)) (assert (<= s 16))
            (assert (let ((t $chain)) (or (= t 23) false)))
            """.trimIndent(),
        )
        val session = SearchSession(
            listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>())),
            atoms = SearchAtomRegistry(parsed.model.numBoolVars),
        )

        assertIs<ComponentResult.Conflict>(session.initialize())
    }

    @Test
    fun `shadowed bindings should keep conditional equality definitions attached to their own quantities`() {
        val parsed = SmtLib.parse(
            """
            (declare-const b Bool) (declare-const c Bool)
            (assert (let ((t (ite b 1 2)))
                (and (or (= t 1) false) (let ((t (ite c 1 2))) (not (= t 1))))))
            """.trimIndent(),
        )
        val session = SearchSession(
            listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>())),
            atoms = SearchAtomRegistry(parsed.model.numBoolVars),
        )

        assertIs<ComponentResult.Consistent>(session.initialize())

        assertEquals(true, session.boolValue(parsed.boolVarNames.getValue("b")))
        assertEquals(false, session.boolValue(parsed.boolVarNames.getValue("c")))
    }
}
