package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class SmtGroundArraysSortsTest(private val constructor: String, private val arguments: List<SmtSort>) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "constructor={0}, arguments={1}")
        fun sorts(): List<Array<Any>> = listOf(
            arrayOf<Any>("|_|", listOf(SmtSort.Bool)),
            arrayOf<Any>("|_|", listOf(SmtSort.Bool, SmtSort.Real)),
            arrayOf<Any>("|U space|", listOf(SmtSort.Bool)),
        )
    }

    @Test
    fun `applied free array index sorts should retain their declared spelling`() {
        val sort = SmtSort.Uninterpreted(constructor, arguments)
        val parsed = SmtLib.parse("(declare-sort $constructor ${arguments.size}) " +
            "(declare-const a (Array ${sort.toSmtLib()} Bool)) " +
            "(declare-const i ${sort.toSmtLib()}) (assert (select a i))")

        val assignment = assertIs<OpenTheoryResult.Sat>(BacktrackSolver.source(parsed.model).solve()).assignment
        val model = parsed.reconstructModel(assignment)

        val array = assertIs<SmtValue.Array>(model.values.single { it.first.name == "a" }.second)
        assertEquals(sort, array.sort.index)
        assertEquals(SmtValue.Boolean(true), array.entries.single().second)
        assertTrue(model.toSmtLib().contains("(Array ${sort.toSmtLib()} Bool)"))
    }
}
