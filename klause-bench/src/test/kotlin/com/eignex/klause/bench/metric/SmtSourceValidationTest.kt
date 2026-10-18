package com.eignex.klause.bench.metric

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

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
    fun `independent refutation should retain only an already reported native refutation`() {
        val reported = record.copy(feasible = false, proven = true, finalWitness = null)
        val result = SmtSourceValidation.Result(
            SourceValidation(
            "valid",
            "compared",
            scope = SmtSourceValidation.REFUTATION,
        ),
            mapOf(
            "independentSourceRefutation" to "true",
            "sourceValidationSolverQueries" to "1",
            "sourceValidationProofChecked" to "false",
        )
        )

        val checked = SmtSourceValidation.apply(reported, result)
        val unknown = SmtSourceValidation.apply(reported.copy(feasible = null, proven = false), result)

        assertEquals(false, checked.feasible)
        assertEquals(true, checked.proven)
        assertNull(checked.checkedWitness)
        assertEquals("false", checked.stats["sourceValidationProofChecked"])
        assertNull(unknown.feasible)
        assertFalse(unknown.proven)
    }

    @ParameterizedTest
    @ValueSource(strings = ["unmarked", "query", "reference", "unproven", "unknown", "scope"])
    fun `incoherent derivation publication should withhold the native claim`(failure: String) {
        val reported = record.copy(feasible = false, proven = true, finalWitness = null)
        val producer = when (failure) {
            "unproven" -> reported.copy(proven = false)
            "unknown" -> reported.copy(feasible = null, proven = false)
            else -> reported
        }
        val validation = SourceValidation("valid", "source contradiction",
            scope = if (failure == "scope") SmtSourceValidation.GROUND else SmtSourceValidation.REFUTATION)
        val stats = mapOf(
            "independentSourceRefutation" to "true",
            "sourceValidationSolverQueries" to if (failure == "query") "1" else "0",
            "sourceValidationDerivationChecked" to if (failure == "unmarked") "false" else "true",
            "sourceValidationReferenceLogic" to if (failure == "reference") "QF_NRA" else "",
        )

        val published = SmtSourceValidation.apply(producer, SmtSourceValidation.Result(validation, stats))

        assertNull(published.feasible)
        assertFalse(published.proven)
        assertNull(published.checkedWitness)
        assertEquals("false", published.stats["independentSourceRefutation"])
        assertEquals("false", published.stats["sourceValidationDerivationChecked"])
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["exit", "killed", "unsupported", "quarantined", "objective", "optimization", "missing-model"],
    )
    fun `ineligible producers should not enter source checking`(kind: String) {
        val changed = when (kind) {
            "exit" -> record.copy(stats = mapOf("exit" to "7"))
            "objective" -> record.copy(objective = 1.0)
            "optimization" -> record.copy(kind = "optimize")
            "missing-model" -> record.copy(finalWitness = null)
            else -> record.copy(stats = record.stats + (kind to "stopped"))
        }

        assertNull(SmtSourceValidation.eligible(changed))
    }
}
