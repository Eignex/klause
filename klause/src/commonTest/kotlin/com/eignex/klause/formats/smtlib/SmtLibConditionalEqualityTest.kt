package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.solver.search.ClauseSearchComponent
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.theory.qflra.ExactLiraAssignment
import com.eignex.klause.theory.qflra.ExactLiraSearchComponent
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class SmtLibConditionalEqualityTest {
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
    fun `a conditional used only by an objective retains its branch value`() {
        for (branch in listOf(true, false)) {
            val parsed = SmtLib.parse(
                """
                (declare-const b Bool)
                (assert ${if (branch) "b" else "(not b)"})
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
