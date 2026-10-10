package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.search.ClauseSearchComponent
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SearchSolveParams
import com.eignex.klause.theory.qflra.ExactLiraAssignment
import com.eignex.klause.theory.qflra.ExactLiraSearchComponent
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.plus
import com.eignex.klause.util.times
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class SmtLibConditionalEqualityTest {
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

            assertIs<ComponentResult.Conflict>(session.initialize())
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
