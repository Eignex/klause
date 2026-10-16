package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.Lit
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.search.ClauseSearchComponent
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SearchSolveParams
import com.eignex.klause.theory.qflra.ExactLiraAssignment
import com.eignex.klause.theory.qflra.ExactLiraSearchComponent
import com.eignex.klause.util.StringCharSource
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.parseBigInt
import com.eignex.klause.util.plus
import com.eignex.klause.util.times
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SmtLibConditionalEqualityTest {
    @Test
    fun `shared finite value comparisons preserve every signed branch value`() {
        val values = listOf(-1000000000000L, -307L, -99L, -13L, -1L, 0L, 2L, 17L,
            101L, 303L, 1009L, 104729L, 2147483648L, 4294967297L, 999999999999L, 1000000000000L)
        var terms = values.map { if (it < 0) "(- ${-it})" else "$it" }
        for (bit in 0..3) terms = terms.chunked(2).map { "(ite b$bit ${it[1]} ${it[0]})" }
        val source = buildString {
            for (bit in 0..3) append("(declare-const b$bit Bool)\n")
            for (index in values.indices) append("(declare-const r$index Bool)\n")
            append("(assert (let ((v ${terms.single()})) (and ")
            for (index in values.indices) {
                val value = values[index].let { if (it < 0) "(- ${-it})" else "$it" }
                append("(= r$index (= v $value)) ")
            }
            append(")))")
        }
        val parsed = SmtLib.parse(source)
        assertEquals(0, parsed.model.numIntVars)
        assertTrue(parsed.model.factors.all { it is Clause })
        for (selected in values.indices) {
            val session = SearchSession(
                listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>())),
                atoms = SearchAtomRegistry(parsed.model.numBoolVars),
            )
            assertIs<ComponentResult.Consistent>(session.initialize())

            for (bit in 0..3) {
                val literal = Lit.make(parsed.boolVarNames.getValue("b$bit"), selected and (1 shl bit) != 0)
                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(literal)), "$selected/$bit")
            }

            for (index in values.indices) {
                assertEquals(
                    index == selected, session.boolValue(parsed.boolVarNames.getValue("r$index")), "$selected/$index",
                )
            }
        }
    }

    @Test
    fun `finite encoding budget stops retain exact arithmetic and release unused gates`() {
        for (workLimit in listOf(24, 34)) {
            for (selected in listOf(false, true)) {
                val builder = Compiler.Builder(
                    Long.MIN_VALUE, Long.MAX_VALUE, false,
                    conditionalEqualities = SmtLibConditionalEquality(workLimit = workLimit),
                )
                val reader = SExprReader(StringCharSource("""
                    (declare-const b Bool) (declare-const c Bool) (declare-const r Bool)
                    (assert ${if (selected) "b" else "(not b)"}) (assert c)
                    (assert (= r (= (ite b (ite c (- 11) 29) (ite c 101 307)) (- 11))))
                """.trimIndent()))
                while (true) builder.command(reader.readCommandOrNull() ?: break)
                val parsed = builder.build()

                val result = BacktrackSolver.source(parsed.model)
                    .solve(BacktrackParams(randomSeed = 0, maxDecisions = 100))

                assertTrue(parsed.model.factors.any { it is Element })
                assertEquals(selected, assertIs<OpenTheoryResult.Sat>(result, result.toString()).assignment
                    .boolValue(parsed.boolVarNames.getValue("r")))
                val used = parsed.model.factors.flatMap { it.variables.boolVars.toList() }.toSet() +
                    parsed.boolVarNames.values
                assertEquals((0 until parsed.model.numBoolVars).toSet(), used)
            }
        }
    }

    @Test
    fun `affine comparisons expand across multiple pending generations`() {
        val depth = 16
        for ((op, target) in listOf("=" to depth, "<" to depth + 1, "<=" to depth)) {
            val source = buildString {
                for (index in 0..depth) append("(declare-const b$index Bool)\n")
                append("(assert (let ((v0 (ite b0 0 1000))) ")
                for (index in 1..depth) {
                    append("(let ((v$index (ite b$index (+ v${index - 1} 1) 1000))) ")
                }
                append("($op v$depth $target)")
                repeat(depth + 2) { append(')') }
            }
            val parsed = SmtLib.parse(source)
            val session = SearchSession(
                listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>())),
                atoms = SearchAtomRegistry(parsed.model.numBoolVars),
            )

            val result = session.initialize()

            assertEquals(0, parsed.model.numIntVars, op)
            assertTrue(parsed.model.factors.all { it is Clause }, op)
            assertIs<ComponentResult.Consistent>(result, op)
            for (index in 0..depth) assertEquals(true, session.boolValue(parsed.boolVarNames.getValue("b$index")), op)
        }
    }

    @Test
    fun `ordered comparisons select the matching conditional branch`() {
        for ((comparison, expected) in listOf(
            "(< t 8)" to true, "(<= t 2)" to true,
            "(> t 2)" to false, "(>= t 8)" to false,
            "(< 2 t)" to false, "(<= 8 t)" to false,
            "(> 8 t)" to true, "(>= 2 t)" to true,
        )) {
            val parsed = SmtLib.parse("(declare-const b Bool) (assert (let ((t (ite b 2 8))) $comparison))")
            val session = SearchSession(
                listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>())),
                atoms = SearchAtomRegistry(parsed.model.numBoolVars),
            )

            val result = session.initialize()

            assertEquals(0, parsed.model.numIntVars, comparison)
            assertTrue(parsed.model.factors.all { it is Clause }, comparison)
            assertIs<ComponentResult.Consistent>(result, comparison)
            assertEquals(expected, session.boolValue(parsed.boolVarNames.getValue("b")), comparison)
        }
    }

    @Test
    fun `signed unit affine orders retain their source branch`() {
        for ((comparison, expected) in listOf(
            "(<= (+ t 1) 3)" to true,
            "(< (+ t 1) 9)" to true,
            "(> (+ t 1) 3)" to false,
            "(>= 3 (+ t 1))" to true,
            "(<= (- t) (- 8))" to false,
            "(>= (+ (- t) 1) (- 1))" to true,
            "(> (- 1) (+ (- t) 1))" to false,
        )) {
            val parsed = SmtLib.parse("(declare-const b Bool) (assert (let ((t (ite b 2 8))) $comparison))")
            val session = SearchSession(
                listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>())),
                atoms = SearchAtomRegistry(parsed.model.numBoolVars),
            )

            val result = session.initialize()

            assertEquals(0, parsed.model.numIntVars, comparison)
            assertTrue(parsed.model.factors.all { it is Clause }, comparison)
            assertIs<ComponentResult.Consistent>(result, comparison)
            assertEquals(expected, session.boolValue(parsed.boolVarNames.getValue("b")), comparison)
        }
    }

    @Test
    fun `conditional comparisons at one threshold retain their operators`() {
        val parsed = SmtLib.parse("""
            (declare-const b Bool) (declare-const c Bool)
            (assert (and b (not c) (let ((t (ite b (- 1) (ite c 0 1)))) (and
                (not (= t 0)) (< t 0) (<= t 0) (not (> t 0)) (not (>= t 0))))))
        """.trimIndent())
        val session = SearchSession(
            listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>())),
            atoms = SearchAtomRegistry(parsed.model.numBoolVars),
        )

        val result = session.initialize()

        assertEquals(0, parsed.model.numIntVars)
        assertTrue(parsed.model.factors.all { it is Clause })
        assertIs<ComponentResult.Consistent>(result)
        assertEquals(true, session.boolValue(parsed.boolVarNames.getValue("b")))
        assertEquals(false, session.boolValue(parsed.boolVarNames.getValue("c")))
    }

    @Test
    fun `ordered defaults exclude a selected selector branch`() {
        val parsed = SmtLib.parse("""
            (declare-const i Int) (assert (<= 0 i 2))
            (assert (= i 1)) (assert (<= (ite (= i 1) 5 i) 2))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model)
            .solve(BacktrackParams(randomSeed = 0, maxDecisions = 100))

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `ordered arms preserve a matching selector branch`() {
        val parsed = SmtLib.parse("""
            (declare-const i Int) (assert (<= 0 i 2))
            (assert (= i 1)) (assert (<= (ite (= i 1) i 5) 2))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model)
            .solve(BacktrackParams(randomSeed = 0, maxDecisions = 100))

        assertEquals("1", assertIs<OpenTheoryResult.Sat>(result, result.toString()).assignment
            .intValue(parsed.intVarNames.getValue("i")))
    }

    @Test
    fun `an unselected open ordered branch preserves its source input`() {
        val parsed = SmtLib.parse("""
            (declare-const b Bool) (declare-const x Int)
            (assert (> x 1000000)) (assert (< (ite b x 0) 5))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model)
            .solve(BacktrackParams(randomSeed = 0, maxDecisions = 100))

        val sample = assertIs<OpenTheoryResult.Sat>(result, result.toString()).assignment
        assertEquals(false, sample.boolValue(parsed.boolVarNames.getValue("b")))
        assertTrue(parseBigInt(sample.intValue(parsed.intVarNames.getValue("x"))) > bigIntOf(1000000))
    }

    @Test
    fun `nonunit affine orders retain exact arithmetic rounding`() {
        val parsed = SmtLib.parse("(declare-const b Bool) (assert (< (+ (* 2 (ite b 2 8)) 1) 6))")

        val result = BacktrackSolver.source(parsed.model)
            .solve(BacktrackParams(randomSeed = 0, maxDecisions = 100))

        assertTrue(parsed.model.factors.any { it !is Clause })
        assertEquals(true, assertIs<OpenTheoryResult.Sat>(result, result.toString()).assignment
            .boolValue(parsed.boolVarNames.getValue("b")))
    }

    @Test
    fun `equality and order comparisons share one expansion allowance`() {
        val builder = Compiler.Builder(
            Long.MIN_VALUE, Long.MAX_VALUE, false,
            conditionalEqualities = SmtLibConditionalEquality(workLimit = 3),
        )
        val reader = SExprReader(StringCharSource(
            "(declare-const b Bool) (assert (let ((t (ite b 2 8))) (and (= t 2) (< t 5))))",
        ))
        while (true) builder.command(reader.readCommandOrNull() ?: break)
        val parsed = builder.build()

        val result = BacktrackSolver.source(parsed.model)
            .solve(BacktrackParams(randomSeed = 0, maxDecisions = 100))

        assertTrue(parsed.model.factors.any { it is Element })
        assertEquals(true, assertIs<OpenTheoryResult.Sat>(result, result.toString()).assignment
            .boolValue(parsed.boolVarNames.getValue("b")))
    }

    @Test
    fun `capped strict comparisons preserve thresholds outside a signed word`() {
        for ((comparison, expected) in listOf(
            "(< (ite b x 0) (- (- 9223372036854775807) 1))" to "-9223372036854775809",
            "(> (ite b x 0) 9223372036854775807)" to "9223372036854775808",
        )) {
            val builder = Compiler.Builder(
                Long.MIN_VALUE, Long.MAX_VALUE, false,
                conditionalEqualities = SmtLibConditionalEquality(workLimit = 0),
            )
            val reader = SExprReader(StringCharSource(
                "(declare-const b Bool) (declare-const x Int) (assert $comparison)",
            ))
            while (true) builder.command(reader.readCommandOrNull() ?: break)

            val parsed = builder.build()

            val row = parsed.model.factors.filterIsInstance<ReifiedLinear>().single { it.wideConstants != null }
            assertEquals(expected, row.constants.exactBound.toString(), comparison)
        }
    }

    @Test
    fun `signed affine equalities select the matching conditional branch`() {
        for ((comparison, expected) in listOf(
            "(= (+ (* 2 (ite b 2 8)) 1) 5)" to true,
            "(= 17 (+ (* 2 (ite b 2 8)) 1))" to false,
            "(= (- (* 2 (ite b 2 8)) 1) 3)" to true,
            "(= 15 (- (* 2 (ite b 2 8)) 1))" to false,
            "(= (+ (* (- 2) (ite b 2 8)) 1) (- 3))" to true,
            "(= (- 15) (+ (* (- 2) (ite b 2 8)) 1))" to false,
        )) {
            val parsed = SmtLib.parse("(declare-const b Bool) (assert $comparison)")
            val session = SearchSession(
                listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>())),
                atoms = SearchAtomRegistry(parsed.model.numBoolVars),
            )

            val result = session.initialize()

            assertEquals(0, parsed.model.numIntVars, comparison)
            assertTrue(parsed.model.factors.all { it is Clause }, comparison)
            assertIs<ComponentResult.Consistent>(result, comparison)
            assertEquals(expected, session.boolValue(parsed.boolVarNames.getValue("b")), comparison)
        }
    }

    @Test
    fun `nonintegral conditional affine targets refute without arithmetic`() {
        for (comparison in listOf(
            "(= (+ (* 2 (ite b 2 8)) 1) 6)",
            "(= 6 (+ (* 2 (ite b 2 8)) 1))",
            "(= (+ (* (- 2) (ite b 2 8)) 1) (- 4))",
        )) {
            val parsed = SmtLib.parse("(declare-const b Bool) (assert $comparison)")
            val session = SearchSession(
                listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>())),
                atoms = SearchAtomRegistry(parsed.model.numBoolVars),
            )

            val result = session.initialize()

            assertEquals(0, parsed.model.numIntVars, comparison)
            assertTrue(parsed.model.factors.all { it is Clause }, comparison)
            assertIs<ComponentResult.Conflict>(result, comparison)
        }
    }

    @Test
    fun `overflowing affine equality inversion retains arithmetic support`() {
        val minimum = "(- (- 9223372036854775807) 1)"
        for (comparison in listOf(
            "(= (- (* 2 (ite b 9223372036854775807 0)) 9223372036854775807) 9223372036854775807)",
            "(= (- (ite b $minimum 0)) $minimum)",
        )) {
            val parsed = SmtLib.parse("(declare-const b Bool) (assert b) (assert $comparison)")
            val session = SearchSession(
                listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>())),
                atoms = SearchAtomRegistry(parsed.model.numBoolVars),
            )

            val result = session.initialize()

            assertTrue(parsed.model.numIntVars > 0, comparison)
            assertTrue(parsed.model.factors.any { it !is Clause }, comparison)
            assertIs<ComponentResult.Consistent>(result, comparison)
        }
    }

    @Test
    fun `affine conditional images reject unreachable values without equality expansion`() {
        for (term in listOf(
            "(ite c (+ (ite b 2 8) 1) 17)",
            "(ite c 17 (+ (ite b 2 8) 1))",
            "(ite c (- (* 2 (ite b 2 8)) 1) 17)",
            "(ite c (+ (* (- 2) (ite b 2 8)) 1) 17)",
        )) {
            val builder = Compiler.Builder(
                Long.MIN_VALUE, Long.MAX_VALUE, false,
                conditionalEqualities = SmtLibConditionalEquality(workLimit = 0),
            )
            val reader = SExprReader(StringCharSource(
                "(declare-const b Bool) (declare-const c Bool) (assert (= $term 7))",
            ))
            while (true) builder.command(reader.readCommandOrNull() ?: break)
            val parsed = builder.build()
            val session = SearchSession(
                listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>())),
                atoms = SearchAtomRegistry(parsed.model.numBoolVars),
            )

            val result = session.initialize()

            assertEquals(0, parsed.model.numIntVars, term)
            assertTrue(parsed.model.factors.all { it is Clause }, term)
            assertIs<ComponentResult.Conflict>(result, term)
        }
    }

    @Test
    fun `affine image propagation preserves selected branch values`() {
        for (inner in listOf(false, true)) {
            for (outer in listOf(false, true)) {
                val parsed = SmtLib.parse("""
                    (declare-const b Bool) (declare-const c Bool) (declare-const z Int)
                    (assert ${if (inner) "b" else "(not b)"})
                    (assert ${if (outer) "c" else "(not c)"})
                    (assert (= z (ite c (+ (* (- 2) (ite b 2 8)) 1) 17)))
                """.trimIndent())

                val result = BacktrackSolver.source(parsed.model)
                    .solve(BacktrackParams(randomSeed = 0, maxDecisions = 100))

                val expected = if (!outer) "17" else if (inner) "-3" else "-15"
                assertEquals(expected, assertIs<OpenTheoryResult.Sat>(result, result.toString()).assignment
                    .intValue(parsed.intVarNames.getValue("z")))
            }
        }
    }

    @Test
    fun `overflowing affine images retain the arithmetic comparison`() {
        val parsed = SmtLib.parse("""
            (declare-const b Bool) (declare-const c Bool)
            (assert (and b c (let ((base (ite b 9223372036854775807 0)))
                (< (ite c (* 2 base) 0) 9223372036854775807))))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model)
            .solve(BacktrackParams(randomSeed = 0, maxDecisions = 100))

        assertTrue(parsed.model.factors.any { it !is Clause })
        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `uniformly true image comparisons omit their private selections`() {
        for (comparison in listOf(
            "(not (= (ite b 2 8) (ite c 5 7)))",
            "(= (ite b 3 3) (ite c 3 3))",
            "(< (ite b 2 3) (ite c 5 7))",
            "(<= (ite b 2 5) (ite c 5 7))",
            "(> (ite b 5 7) (ite c 2 3))",
            "(>= (ite b 5 7) (ite c 2 5))",
            "(< (- 9223372036854775807) (ite b 2 3))",
        )) {
            val parsed = SmtLib.parse("(declare-const b Bool) (declare-const c Bool) (assert $comparison)")
            val session = SearchSession(
                listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>())),
                atoms = SearchAtomRegistry(parsed.model.numBoolVars),
            )

            assertIs<ComponentResult.Consistent>(session.initialize(), comparison)
            val result = session.solve(parsed.model.numBoolVars)

            assertEquals(0, parsed.model.numIntVars, comparison)
            assertTrue(parsed.model.factors.all { it is Clause }, comparison)
            assertIs<SearchResult.Satisfied>(result, comparison)
        }
    }

    @Test
    fun `uniformly false image comparisons refute without arithmetic selections`() {
        for (comparison in listOf(
            "(= (ite b 2 8) (ite c 5 7))",
            "(< (ite b 5 7) (ite c 2 5))",
            "(<= (ite b 5 7) (ite c 2 3))",
            "(> (ite b 2 5) (ite c 5 7))",
            "(>= (ite b 2 3) (ite c 5 7))",
            "(> (ite b 2 3) 9223372036854775807)",
        )) {
            val parsed = SmtLib.parse("(declare-const b Bool) (declare-const c Bool) (assert $comparison)")
            val session = SearchSession(
                listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>())),
                atoms = SearchAtomRegistry(parsed.model.numBoolVars),
            )

            val result = session.initialize()

            assertEquals(0, parsed.model.numIntVars, comparison)
            assertTrue(parsed.model.factors.all { it is Clause }, comparison)
            assertIs<ComponentResult.Conflict>(result, comparison)
        }
    }

    @Test
    fun `an image comparison preserves an independently consumed derived guard`() {
        for (branch in listOf(false, true)) {
            val parsed = SmtLib.parse("""
                (declare-const b Bool) (declare-const c Bool)
                (declare-const x Int) (declare-const y Int)
                (assert (and (<= (- 4) x 4) (<= (- 4) y 4)))
                (assert (let ((g (and b c))) (and
                    (= x y) (= (+ x y) (ite g (+ x 1) (- y 1)))
                    (not (= (ite g 2 8) (ite (not g) 5 7)))
                    ${if (branch) "g" else "(not g)"})))
            """.trimIndent())

            val result = BacktrackSolver.source(parsed.model)
                .solve(BacktrackParams(randomSeed = 0, maxDecisions = 100))

            assertEquals(1, parsed.model.factors.filterIsInstance<Element>().size)
            val sample = assertIs<OpenTheoryResult.Sat>(result, result.toString()).assignment
            assertEquals(branch, sample.boolValue(parsed.boolVarNames.getValue("b")) &&
                sample.boolValue(parsed.boolVarNames.getValue("c")))
            assertEquals(if (branch) "1" else "-1", sample.intValue(parsed.intVarNames.getValue("x")))
            assertEquals(sample.intValue(parsed.intVarNames.getValue("x")),
                sample.intValue(parsed.intVarNames.getValue("y")))
        }
    }

    @Test
    fun `an open conditional branch retains its comparison`() {
        val parsed = SmtLib.parse("""
            (declare-const b Bool) (declare-const c Bool) (declare-const x Int)
            (assert (and (not b) (not c) (= (ite b 2 x) (ite c 5 7))))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model)
            .solve(BacktrackParams(randomSeed = 0, maxDecisions = 100))

        assertEquals("7", assertIs<OpenTheoryResult.Sat>(result, result.toString()).assignment
            .intValue(parsed.intVarNames.getValue("x")))
    }

    @Test
    fun `equal nonsingleton images do not imply equal selected values`() {
        val parsed = SmtLib.parse("""
            (declare-const b Bool) (declare-const c Bool)
            (assert (and b (not c) (= (ite b 2 8) (ite c 2 8))))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model)
            .solve(BacktrackParams(randomSeed = 0, maxDecisions = 100))

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `repeated conditional guards exclude unreachable values without equality expansion`() {
        val terms = listOf(
            "(ite c 5 (ite c 3 41))",
            "(ite c (ite c 5 3) 41)",
            "(ite c 5 (ite (not c) 41 3))",
            "(ite c (ite (not c) 3 5) 41)",
        )
        for (guard in listOf("b", "(= x 0)")) {
            for (term in terms) {
                val builder = Compiler.Builder(
                    Long.MIN_VALUE, Long.MAX_VALUE, false,
                    conditionalEqualities = SmtLibConditionalEquality(workLimit = 0),
                )
                val reader = SExprReader(StringCharSource(
                    "(declare-const b Bool) (declare-const x Int) (assert (let ((c $guard)) (= $term 3)))",
                ))
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
    fun `restricting one conditional use preserves an independent consumer of its inner definition`() {
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
    fun `an expanded conditional equality releases its arithmetic placeholder`() {
        val parsed = SmtLib.parse(
            "(declare-const b Bool) (assert (= (ite b 1 2) 1))",
        )
        val session = SearchSession(
            listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>())),
            atoms = SearchAtomRegistry(parsed.model.numBoolVars),
        )

        session.initialize()
        val result = session.solve(parsed.model.numBoolVars)

        assertEquals(0, parsed.model.numIntVars)
        assertIs<SearchResult.Satisfied>(result)
    }

    @Test
    fun `unused difference equality gates release their arithmetic reads`() {
        val parsed = SmtLib.parse(
            """
                (declare-const x Int) (declare-const y Int)
                (assert (let ((s (ite (= (- x y) 3) 1 2))) (= (ite true 1 s) 1)))
            """.trimIndent(),
        )

        assertTrue(parsed.model.factors.all { it.intVars.isEmpty() })
    }

    @Test
    fun `a covered identity default excludes its old selector values without expansion`() {
        val builder = Compiler.Builder(
            Long.MIN_VALUE, Long.MAX_VALUE, false, conditionalEqualities = SmtLibConditionalEquality(workLimit = 0),
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
    fun `a covered selector excludes an open default without expansion`() {
        val builder = Compiler.Builder(
            Long.MIN_VALUE, Long.MAX_VALUE, false, conditionalEqualities = SmtLibConditionalEquality(workLimit = 0),
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
    fun `an uncovered selector value retains its open default without expansion`() {
        val builder = Compiler.Builder(
            Long.MIN_VALUE, Long.MAX_VALUE, false, conditionalEqualities = SmtLibConditionalEquality(workLimit = 0),
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
    fun `a final comparison can refute the source after earlier comparisons exceed the expansion budget`() {
        val builder = Compiler.Builder(
            Long.MIN_VALUE, Long.MAX_VALUE, false, conditionalEqualities = SmtLibConditionalEquality(workLimit = 3),
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
    fun `an exhausted comparison expansion retains its exact arithmetic meaning`() {
        for (value in listOf(0, 1)) {
            val builder = Compiler.Builder(
                Long.MIN_VALUE, Long.MAX_VALUE, false, conditionalEqualities = SmtLibConditionalEquality(workLimit = 0),
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
    fun `a constant conditional result completes without arithmetic predicate decisions`() {
        val guards = listOf(
            "(<= x 0)", "(= x 0)", "(and (<= x 0) (= x 0))", "(or (<= x 0) (= x 0))",
            "(xor (<= x 0) (= x 0))", "(=> (<= x 0) (= x 0))", "(= (<= x 0) (= x 0))",
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
    fun `a Boolean gate retains its meaning when an arithmetic channel consumes it`() {
        val parsed = SmtLib.parse(
            """
            (declare-const b Bool) (declare-const c Bool)
            (assert (not b)) (assert (not c))
            (assert (= (ite b 1 1) 1))
            (assert (distinct (and b c) false))
            """.trimIndent(),
        )
        ExactLiraSearchComponent(parsed.model).use { component ->
            val session = SearchSession(
                listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>()), component),
                atoms = SearchAtomRegistry(parsed.model.numBoolVars),
            )

            session.initialize()

            assertIs<SearchResult.Exhausted>(session.solve(parsed.model.numBoolVars))
        }
    }

    @Test
    fun `a source Boolean retains a later arithmetic comparison after unused predicates are omitted`() {
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
    fun `a conditional cannot take a conflicting selector value without arithmetic checks`() {
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
    fun `a conditional retains its guard meaning after another selector shares the literal`() {
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
    fun `a conditional default remains available at a different selector value`() {
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
    fun `a conditional can compare a branch from a different selector`() {
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
    fun `an equality key beyond a signed word cannot exclude a feasible conditional branch`() {
        val parsed = SmtLib.parse(
            """
            (declare-const x Int)
            (assert (= x -9223372036854775808))
            (assert (= (ite (not (= (- x) -9223372036854775808)) x 0) -9223372036854775808))
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
    fun `a mixed real constraint retains its conditional value after unused definitions are omitted`() {
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
                BigFraction.of(bigIntOf(19), bigIntOf(2)), assignment.reals[parsed.realVarNames.getValue("r")],
            )
        }
    }

    @Test
    fun `a complete symbolic selector table resolves from its source Boolean values`() {
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
    fun `a decision list over a symbolic selector completes without numeric predicate decisions`() {
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
    fun `a determined conditional comparison completes without Boolean decisions`() {
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
    fun `a conditional compared with a constant retains its independent arithmetic use`() {
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
    fun `nested numeric conditional reads retain the definitions they depend on`() {
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
    fun `a conditional objective retains its branch value after unused definitions are omitted`() {
        for (branch in listOf(true, false)) {
            val parsed = SmtLib.parse(
                """
                (declare-const b Bool)
                (assert ${if (branch) "b" else "(not b)"})
                (assert (= (ite b 1 2) ${if (branch) 1 else 2}))
                (minimize (ite b 3 8))
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
                val objective = assertNotNull(parsed.objective)
                val value = objective.intCoefficients.withIndex().fold(bigIntOf(objective.constant)) { sum, (v, c) ->
                    sum + assignment.ints[v] * bigIntOf(c)
                }
                assertEquals(bigIntOf(if (branch) 3 else 8), value)
            }
        }
    }

    @Test
    fun `a conditional branch comparison retains exact arithmetic beyond a signed word`() {
        val parsed = SmtLib.parse(
            """
            (declare-const b Bool) (declare-const x Int)
            (assert (or (= (ite b (+ x 9223372036854775807) 0) -9223372036854775808) false))
            """.trimIndent(),
        )
        val session = SearchSession(
            listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>())),
            atoms = SearchAtomRegistry(parsed.model.numBoolVars),
        )

        assertIs<ComponentResult.Consistent>(session.initialize())

        assertEquals(true, session.boolValue(parsed.boolVarNames.getValue("b")))
    }

    @Test
    fun `nested shared conditional bindings propagate through their complete definition chain`() {
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
    fun `a nested conditional comparison propagates its source guard without arithmetic checks`() {
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
    fun `a conditional constant outside every branch image refutes through Boolean clauses`() {
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
    fun `an open default retains its comparison when the constant arm is inactive`() {
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
    fun `a completed decision list excludes constants outside its arms and default`() {
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
    fun `shadowed bindings keep conditional equality definitions attached to their own quantities`() {
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
