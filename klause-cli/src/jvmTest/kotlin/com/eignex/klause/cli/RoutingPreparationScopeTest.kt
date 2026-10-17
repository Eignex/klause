package com.eignex.klause.cli

import com.eignex.klause.solver.pipeline.OpenTheoryExecution
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.SourceExecutionRequest
import com.eignex.klause.solver.pipeline.SourceExecutionResult
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.execute
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutingPreparationScopeTest {
    @Test
    fun `closing required admission should stop charging its completed routing ledger`() {
        val common = CommonOptions().apply { engineParams += "presolve-work-limit=1" }
        val stop = common.routingPreparationCancellation()
        val budget = assertNotNull(common.presolveBudget)
        stop.charge(2L)
        assertTrue(common.routingCancellation()())
        assertFalse(stop())

        stop.close()
        stop.charge(3L)

        assertEquals(2L, budget.spent())
    }

    @Test
    fun `a completed routing scope should retain the live containing deadline`() {
        val common = CommonOptions()
        val stop = common.routingPreparationCancellation()
        stop.close()
        assertFalse(stop())

        common.deadlineAtMs = nowMillis() - 1L

        assertTrue(stop())
    }

    @Test
    fun `failed admission should release its routing meter`() {
        val common = CommonOptions().apply { engineParams += "presolve-work-limit=1" }
        val stop = common.routingPreparationCancellation()
        assertNotNull(stop.workMeter())

        assertFailsWith<IllegalArgumentException> {
            stop.use { throw IllegalArgumentException("admission failed") }
        }

        assertNull(stop.workMeter())
    }

    @Test
    fun `native SMT execution should not debit the completed routing ledger`() {
        val file = File.createTempFile("routing-native-ledger", ".smt2").apply {
            writeText(
                "(declare-const x Int) (declare-const y Int) " +
                    "(assert (<= (+ (* 2 x) (* 3 y)) 1)) (check-sat)",
            )
        }
        val common = CommonOptions().apply {
            engine = "cp"
            engineParams += "presolve-work-limit=1"
        }
        val loaded = try {
            SmtLibMode.newSession().load(file.absolutePath, common)
        } finally {
            file.delete()
        }
        val invocation = assertNotNull(loaded.sourceInvocation)
        val budget = assertNotNull(common.presolveBudget)
        val preparationWork = budget.spent()
        assertTrue(preparationWork > 0L)

        val execution = assertIs<SourceExecutionResult.Linear>(
            SourceSetup.execute(SourceExecutionRequest(invocation)),
        )

        assertIs<OpenTheoryResult.Sat>(assertIs<OpenTheoryExecution.Satisfy>(execution.result).result)
        assertEquals(preparationWork, budget.spent())
    }

    @Test
    fun `a prepared SMT invocation should retain its containing stop after admission`() {
        val file = File.createTempFile("routing-native-deadline", ".smt2").apply {
            writeText(
                "(declare-const x Int) (declare-const y Int) " +
                    "(assert (<= (+ (* 2 x) (* 3 y)) 1)) (check-sat)",
            )
        }
        val common = CommonOptions().apply { engine = "cp" }
        val loaded = try {
            SmtLibMode.newSession().load(file.absolutePath, common)
        } finally {
            file.delete()
        }
        val invocation = assertNotNull(loaded.sourceInvocation)
        common.deadlineAtMs = nowMillis() - 1L

        val execution = assertIs<SourceExecutionResult.Linear>(
            SourceSetup.execute(SourceExecutionRequest(invocation)),
        )

        assertIs<OpenTheoryResult.Unknown>(assertIs<OpenTheoryExecution.Satisfy>(execution.result).result)
    }
}
