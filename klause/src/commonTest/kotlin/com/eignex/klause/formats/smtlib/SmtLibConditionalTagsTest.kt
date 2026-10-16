package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.lowering.IntComb
import com.eignex.klause.lowering.LinComb
import com.eignex.klause.lowering.PolynomialSourceInterrupted
import com.eignex.klause.lowering.reifyLinear
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.search.ClauseSearchComponent
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.theory.qflra.ExactLiraAssignment
import com.eignex.klause.theory.qflra.ExactLiraSearchComponent
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.StringCharSource
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.parseBigInt
import com.eignex.klause.util.plus
import com.eignex.klause.util.times
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmtLibConditionalTagsTest {
    @Test
    fun `different selected expressions retain numeric comparisons in both orientations`() {
        for ((branch, choice) in listOf(true to true, true to false, false to true, false to false)) {
            val value = if (branch) 2 else 4
            val variants = listOf(
                Triple("=", value == 2, 2 == value),
                Triple("<", value < 2, 2 < value),
                Triple("<=", value <= 2, 2 <= value),
                Triple(">", value > 2, 2 > value),
                Triple(">=", value >= 2, 2 >= value),
            )
            val declarations = variants.indices.joinToString(" ") {
                "(declare-const r${it}a Bool) (declare-const r${it}b Bool)"
            }
            val comparisons = variants.mapIndexed { index, variant ->
                "(= r${index}a (${variant.first} v 2)) (= r${index}b (${variant.first} 2 v))"
            }.joinToString(" ")
            val parsed = SmtLib.parse("""
                (declare-const b Bool) (declare-const c Bool) (declare-const x Int) (declare-const y Int)
                $declarations
                (assert ${if (branch) "b" else "(not b)"}) (assert ${if (choice) "c" else "(not c)"})
                (assert (= x 2)) (assert (= y 1))
                (assert (let ((shared (ite c x (+ y 1))))
                    (let ((v (ite b shared (+ x 2)))) (and $comparisons))))
            """.trimIndent())

            val result = BacktrackSolver.source(parsed.model)
                .solve(BacktrackParams(randomSeed = 0, maxDecisions = 100))

            val sample = assertIs<OpenTheoryResult.Sat>(result, result.toString()).assignment
            assertEquals(parsed.intVarNames.size, parsed.model.numIntVars)
            for ((index, variant) in variants.withIndex()) {
                assertEquals(variant.second, sample.boolValue(parsed.boolVarNames.getValue("r${index}a")))
                assertEquals(variant.third, sample.boolValue(parsed.boolVarNames.getValue("r${index}b")))
            }
        }
    }

    @Test
    fun `parents with different supports retain one shared child selection`() {
        val parsed = SmtLib.parse("""
            (declare-const b Bool) (declare-const c Bool) (declare-const d Bool)
            (declare-const x Int) (declare-const y Int) (declare-const z Int) (declare-const w Int)
            (assert (= x 1)) (assert (= y 2)) (assert (= z 3)) (assert (= w 4))
            (assert b) (assert d)
            (assert (let ((shared (ite c x y)))
                (and (= (ite b shared z) 1) (= (ite d shared w) 2))))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model)
            .solve(BacktrackParams(randomSeed = 0, maxDecisions = 100))

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `an opaque arithmetic leaf retains its private numeric operand`() {
        val parsed = SmtLib.parse("""
            (declare-const b Bool) (declare-const c Bool) (declare-const d Bool)
            (declare-const x Int) (declare-const y Int) (declare-const z Int) (declare-const w Int)
            (declare-const out Int) (declare-const r Bool)
            (assert (= x 1)) (assert (= y 4)) (assert (= z 6)) (assert (= w 7))
            (assert b) (assert (not c))
            (assert (let ((inner (ite c x y)))
                (let ((v (ite b (+ inner 1) (ite d z w)))) (and (= out inner) (= r (> v 3))))))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model)
            .solve(BacktrackParams(randomSeed = 0, maxDecisions = 100))

        val sample = assertIs<OpenTheoryResult.Sat>(result, result.toString()).assignment
        assertEquals("4", sample.intValue(parsed.intVarNames.getValue("out")))
        assertEquals(true, sample.boolValue(parsed.boolVarNames.getValue("r")))
        assertTrue(parsed.model.numIntVars > parsed.intVarNames.size)
    }

    @Test
    fun `capped expression preparation preserves exact numeric fallback`() {
        for (limit in listOf(1, 32, 96, 192)) {
            val builder = Compiler.Builder(
                Long.MIN_VALUE, Long.MAX_VALUE, false,
                conditionalEqualities = SmtLibConditionalEquality(workLimit = limit),
            )
            val reader = SExprReader(StringCharSource("""
                (declare-const b Bool) (declare-const c Bool) (declare-const x Int) (declare-const y Int)
                (declare-const eq Bool) (declare-const lt Bool) (declare-const ge Bool)
                (assert (= x 2)) (assert (= y 1)) (assert b)
                (assert (let ((v (ite b (ite c x (+ y 1)) (+ x 2))))
                    (and (= eq (= v 2)) (= lt (< v 2)) (= ge (>= v 2)))))
            """.trimIndent()))
            while (true) builder.command(reader.readCommandOrNull() ?: break)
            val parsed = builder.build()

            val result = BacktrackSolver.source(parsed.model)
                .solve(BacktrackParams(randomSeed = 0, maxDecisions = 100))

            val sample = assertIs<OpenTheoryResult.Sat>(result, "limit=$limit $result").assignment
            assertEquals(true, sample.boolValue(parsed.boolVarNames.getValue("eq")))
            assertEquals(false, sample.boolValue(parsed.boolVarNames.getValue("lt")))
            assertEquals(true, sample.boolValue(parsed.boolVarNames.getValue("ge")))
        }
    }

    @Test
    fun `shared choice work cannot starve a late image refutation`() {
        val builder = Compiler.Builder(
            Long.MIN_VALUE, Long.MAX_VALUE, false,
            conditionalEqualities = SmtLibConditionalEquality(workLimit = 16),
        )
        val reader = SExprReader(StringCharSource("""
            (declare-const b Bool) (declare-const c Bool) (declare-const d Bool)
            (declare-const s Int) (declare-const x Int) (assert (<= 0 s 1))
            (assert (= (ite b (ite (= s 0) 1 2) 3) 99))
            (assert (> (ite c (ite d x (+ x 1)) (+ x 2)) 0))
        """.trimIndent()))
        while (true) builder.command(reader.readCommandOrNull() ?: break)
        val parsed = builder.build()
        val session = SearchSession(
            listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>())),
            atoms = SearchAtomRegistry(parsed.model.numBoolVars),
        )

        val result = session.initialize()

        assertIs<ComponentResult.Conflict>(result)
    }

    @Test
    fun `strict selected leaf comparisons preserve arithmetic beyond signed words`() {
        for (comparison in listOf("(< v -9223372036854775808)", "(> -9223372036854775808 v)")) {
            val parsed = SmtLib.parse("""
                (declare-const b Bool) (declare-const c Bool) (declare-const x Int)
                (assert (let ((v (ite b (ite c (+ x 9223372036854775807) 0) 1))) $comparison))
            """.trimIndent())

            val result = BacktrackSolver.source(parsed.model)
                .solve(BacktrackParams(randomSeed = 0, maxDecisions = 100))

            val sample = assertIs<OpenTheoryResult.Sat>(result, result.toString()).assignment
            assertEquals(true, sample.boolValue(parsed.boolVarNames.getValue("b")))
            assertEquals(true, sample.boolValue(parsed.boolVarNames.getValue("c")))
            val coordinate = parseBigInt(sample.intValue(parsed.intVarNames.getValue("x")))
            assertTrue(coordinate + bigIntOf(Long.MAX_VALUE) < bigIntOf(Long.MIN_VALUE))
        }
    }

    @Test
    fun `conditional objective values survive shared expression preparation`() {
        val parsed = SmtLib.parse("""
            (declare-const b Bool) (declare-const c Bool) (declare-const x Int) (declare-const y Int)
            (assert (not b)) (assert (= x 3)) (assert (= y 8))
            (assert (>= (ite b (ite c x (+ y 1)) 7) 2))
            (minimize (ite b (ite c x (+ y 1)) 9))
        """.trimIndent())
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
            assertEquals(bigIntOf(9), value)
        }
    }

    @Test
    fun `cancellation during shared expression preparation aborts parsing`() {
        val definitions = SmtLibConditionalEquality()
        var observedBuilder: Compiler.Builder? = null
        var sourceIntegers = Int.MAX_VALUE
        var closedPolls = 0
        val stop = Cancellation {
            val current = observedBuilder
            current != null && current.nextInt > sourceIntegers &&
                (sourceIntegers until current.nextInt).all(definitions::isDefined) && ++closedPolls == 80
        }
        val builder = Compiler.Builder(
            Long.MIN_VALUE, Long.MAX_VALUE, false, conditionalEqualities = definitions, sourceCancellation = stop,
        )
        observedBuilder = builder
        val reader = SExprReader(StringCharSource("""
            (declare-const b Bool) (declare-const c Bool) (declare-const x Int) (declare-const y Int)
            (assert (= x 2)) (assert (= y 1))
            (assert (>= (ite b (ite c x (+ y 1)) (+ x 2)) 2))
        """.trimIndent()))
        while (true) builder.command(reader.readCommandOrNull() ?: break)
        sourceIntegers = builder.intNames.size

        assertFailsWith<PolynomialSourceInterrupted> { builder.build() }
    }

    @Test
    fun `a cyclic branch graph declines symbolic comparison`() {
        val builder = Compiler.Builder(Long.MIN_VALUE, Long.MAX_VALUE, false)
        val reader = SExprReader(StringCharSource("(declare-const v Int)"))
        builder.command(assertNotNull(reader.readCommandOrNull()))
        val variable = builder.intNames.getValue("v")
        val definition = SmtLibConditionalEquality.Definition(
            emptyList(), emptyList(), LinComb(mapOf(variable to 1L), 0), emptyList(), null, emptyList(),
        )
        val tags = SmtLibConditionalTags(mapOf(variable to definition)) { true }

        val literal = tags.compare(variable, "=", 0, builder)

        assertNull(literal)
        assertTrue(builder.factors.isEmpty())
    }

    @Test
    fun `an oversized structural support retains the arithmetic query`() {
        val builder = Compiler.Builder(Long.MIN_VALUE, Long.MAX_VALUE, false)
        val reader = SExprReader(StringCharSource("(declare-const v Int) (declare-const s Int)"))
        while (true) builder.command(reader.readCommandOrNull() ?: break)
        val variable = builder.intNames.getValue("v")
        val selector = builder.intNames.getValue("s")
        val guards = (0 until 1024).map { value ->
            builder.reifyRawRelation(
                "=", IntComb.Narrow(LinComb(mapOf(selector to 1L), 0)),
                IntComb.Narrow(LinComb(emptyMap(), value.toLong())),
            )
        }
        val definition = SmtLibConditionalEquality.Definition(
            guards, List(1024) { LinComb(emptyMap(), it.toLong()) }, LinComb(emptyMap(), 1024),
            emptyList(), null, List(1024) { null },
        )
        val tags = SmtLibConditionalTags(mapOf(variable to definition)) { true }
        val previousFactors = builder.factors.size

        val literal = tags.compare(variable, "=", 0, builder)

        assertNull(literal)
        assertEquals(previousFactors, builder.factors.size)
    }


    @Test
    fun `interrupted tag phases publish no query or unowned private state`() {
        val phases = listOf("codebook" to 4, "support" to 14, "completed bit" to 49, "formula" to 55)
        for ((phase, limit) in phases) {
            val builder = Compiler.Builder(Long.MIN_VALUE, Long.MAX_VALUE, false)
            val reader = SExprReader(StringCharSource(
                "(declare-const b Bool) (declare-const x Int) (declare-const y Int)",
            ))
            while (true) builder.command(reader.readCommandOrNull() ?: break)
            val variable = builder.newInt(null, null)
            val first = LinComb(mapOf(builder.intNames.getValue("x") to 1L), 0)
            val second = LinComb(mapOf(builder.intNames.getValue("y") to 1L), 0)
            val guard = Lit.make(builder.boolNames.getValue("b"), positive = true)
            val start = builder.factors.size
            val left = builder.reifyLinear(
                longArrayOf(1, -1), intArrayOf(variable, first.asSimpleVar()!!), LinearOp.EQ, 0,
            )
            val right = builder.reifyLinear(
                longArrayOf(1, -1), intArrayOf(variable, second.asSimpleVar()!!), LinearOp.EQ, 0,
            )
            builder.factors += Clause(intArrayOf(Lit.negate(guard), left))
            builder.factors += Clause(intArrayOf(guard, right))
            val factors = builder.factors.subList(start, builder.factors.size).toList()
            builder.conditionalEqualities.define(variable, listOf(guard), listOf(first), second, factors, listOf(null))
            val definition = SmtLibConditionalEquality.Definition(
                listOf(guard), listOf(first), second, factors, null, listOf(null),
            )
            var visits = 0
            val tags = SmtLibConditionalTags(mapOf(variable to definition)) { ++visits <= limit }

            val literal = tags.compare(variable, ">", 1, builder)
            val parsed = builder.build()
            val session = SearchSession(
                listOf(ClauseSearchComponent(parsed.model.factors.filterIsInstance<Clause>())),
                atoms = SearchAtomRegistry(parsed.model.numBoolVars),
            )

            assertNull(literal, phase)
            assertEquals(limit + 1, visits, phase)
            assertEquals(parsed.intVarNames.size, parsed.model.numIntVars, phase)
            assertIs<ComponentResult.Consistent>(session.initialize())
            for (column in 0 until parsed.model.numBoolVars) {
                if (column !in parsed.boolVarNames.values) assertNotNull(session.boolValue(column), phase)
            }
        }
    }

}
