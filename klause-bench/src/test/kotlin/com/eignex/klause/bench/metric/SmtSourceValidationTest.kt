package com.eignex.klause.bench.metric

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmtSourceValidationTest {
    private val record = SolveRecord(
        "source", "klause", "cp", 1, "free", 3, 10_000,
        "satisfy", false, true, null, 5, proven = false, stats = mapOf("exit" to "0"),
        gitSha = "producer", timestamp = "t", command = "cli source.smt2", finalWitness = "(model)",
    )

    @ParameterizedTest
    @ValueSource(strings = ["unknown", "invalid"])
    fun `unaccepted checks should withhold claims while retaining the reported witness`(status: String) {
        val checked = SmtSourceValidation.apply(
            record.copy(proven = true),
            SmtSourceValidation.Result(SourceValidation(status, "unaccepted")),
        )

        assertNull(checked.feasible)
        assertFalse(checked.proven)
        assertNull(checked.checkedWitness)
        assertEquals(record.finalWitness, checked.finalWitness)
        assertEquals("true", checked.stats["reportedFeasible"])
        assertEquals("true", checked.stats["reportedProven"])
    }

    @Test
    fun `ground acceptance should retain the exact model without proof credit`() {
        val checked = SmtSourceValidation.apply(
            record.copy(proven = true),
            SmtSourceValidation.Result(
            SourceValidation(
                "valid",
                "checked",
                scope = SmtSourceValidation.GROUND,
                checkedWitness = record.finalWitness,
            ),
        )
        )

        assertEquals(true, checked.feasible)
        assertEquals(record.finalWitness, checked.checkedWitness)
        assertFalse(checked.proven)
        assertEquals(record.timeToBestMs, checked.timeToBestMs)
    }

    @Test
    fun `reported refutation should stand unchecked for comparison with stored references`() {
        val reported = record.copy(feasible = false, proven = true, finalWitness = null)
        val checked = SmtSourceValidation.apply(
            reported, SmtSourceValidation.Result(SmtSourceValidation.unknown("not checked")),
        )

        assertEquals(false, checked.feasible)
        assertTrue(checked.proven)
        assertEquals("unknown", checked.stats["sourceValidation"])
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["exit", "killed", "unsupported", "quarantined", "objective", "optimization", "missing-model",
            "refutation"],
    )
    fun `ineligible producers should not enter source checking`(kind: String) {
        val changed = when (kind) {
            "exit" -> record.copy(stats = mapOf("exit" to "7"))
            "objective" -> record.copy(objective = 1.0)
            "optimization" -> record.copy(kind = "optimize")
            "missing-model" -> record.copy(finalWitness = null)
            "refutation" -> record.copy(feasible = false, proven = true, finalWitness = null)
            else -> record.copy(stats = record.stats + (kind to "stopped"))
        }

        assertFalse(SmtSourceValidation.eligible(changed))
    }
}
