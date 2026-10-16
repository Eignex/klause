package com.eignex.klause.cli

import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.pipeline.PreparedExecution
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame

@RunWith(Parameterized::class)
class SmtLibModeSetupTest(private val engine: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun engines(): List<String> = listOf("cp", "ls")
    }

    @Test
    fun `finite maximization should retain original objective units on its selected source plan`() {
        val file = File.createTempFile("smt-source-setup", ".smt2").apply {
            writeText("(declare-const i Int) (assert (and (>= i 0) (<= i 2))) " +
                "(maximize (+ 7 (* 2 i))) (check-sat)")
        }
        val common = CommonOptions().apply { this.engine = this@SmtLibModeSetupTest.engine }

        val solvable = try {
            SmtLibMode.newSession().load(file.absolutePath, common)
        } finally {
            file.delete()
        }

        val plan = solvable.finiteShape.preparedPlan
        val execution = assertIs<PreparedExecution.FiniteLinear>(plan.execution)
        val sample = Sample(BooleanArray(0), longArrayOf(2))
        assertSame(execution.ownership, solvable.finiteShape.componentPlan)
        assertEquals(11L, assertNotNull(plan.objective).evaluateLong(sample))
        assertEquals(-11L, assertNotNull(solvable.linearObjective).evaluateLong(sample))
        assertEquals(true, plan.maximize)
        assertEquals(11L, assertNotNull(solvable.objectiveValue)(sample))
    }

}
