package com.eignex.klause.bench.metric

import com.eignex.klause.bench.report.Reports
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MpsSourceValidationTest {
    private val record = SolveRecord("fixture", "klause", null, 1, "free", 3, 10_000,
        "optimize", false, true, 1.0, 10, proven = true, gitSha = "producer", timestamp = "t", command = "cli",
        finalWitness = "v x=1", sourceHashes = mapOf("model" to "source"), sourceOutputSha256 = "stdout")
    private val bindings = mapOf("source.mps" to "source", "stdout.txt" to "stdout",
        "record.json" to "producer-record", "request.json" to "request")
    private val tools = mapOf("checker" to "pinned-tool")
    private val receipt = MpsSourceValidation.Receipt("valid", "ground checked", "ground-original-mps-witness",
        bindings, tools, 0, false, true, "v x=1", "z3-4.15.3", 125, 4)

    @Test
    fun `a bound ground receipt should retain the literal witness and separate checker timings`() {
        val result = MpsSourceValidation.checkerResult(0, Reports.json.encodeToString(receipt), record, bindings, tools)

        assertEquals("valid", result.validation.status)
        assertEquals(record.finalWitness, result.validation.checkedWitness)
        assertEquals("125", result.stats["sourceValidationSetupMs"])
        assertEquals("4", result.stats["sourceValidationCheckMs"])
        assertEquals("false", result.stats["sourceValidationProofChecked"])
    }

    @Test
    fun `ordinary case JSON should retain the exact producer record receipt witness and source custody`() {
        val original = Reports.json.encodeToString(record)
        val receiptJson = Reports.json.encodeToString(receipt)
        val saved = record.sourceChecked(SourceValidation("valid", "checked", checkedWitness = record.finalWitness),
            approximation = false, witnessOnly = true).copy(stats = mapOf(
            "sourceValidationProducerRecordJson" to original,
            "sourceValidationRecordSha256" to sha256(original.toByteArray()),
            "sourceValidationReceiptJson" to receiptJson,
            "sourceValidationReceiptSha256" to sha256(receiptJson.toByteArray()),
        ))

        val decoded = Reports.json.decodeFromString<SolveRecord>(Reports.json.encodeToString(saved))
        val producer = decoded.stats.getValue("sourceValidationProducerRecordJson")
        val retainedReceipt = decoded.stats.getValue("sourceValidationReceiptJson")

        assertEquals(record, Reports.json.decodeFromString<SolveRecord>(producer))
        assertEquals(receipt, Reports.json.decodeFromString<MpsSourceValidation.Receipt>(retainedReceipt))
        assertEquals(sha256(producer.toByteArray()), decoded.stats["sourceValidationRecordSha256"])
        assertEquals(sha256(retainedReceipt.toByteArray()), decoded.stats["sourceValidationReceiptSha256"])
        assertEquals(record.finalWitness, decoded.finalWitness)
        assertEquals(record.finalWitness, decoded.checkedWitness)
        assertEquals(record.sourceHashes, decoded.sourceHashes)
        assertEquals(record.sourceOutputSha256, decoded.sourceOutputSha256)
    }

    @ParameterizedTest
    @ValueSource(strings = ["process", "malformed", "source", "record", "tools", "witness", "queries", "proof",
        "cleanup", "scope", "status", "runtime", "negative-time"])
    fun `incomplete or changed checker custody should remain unknown`(failure: String) {
        val changed = when (failure) {
            "source" -> receipt.copy(bindings = bindings + ("source.mps" to "changed"))
            "record" -> receipt.copy(bindings = bindings + ("record.json" to "changed"))
            "tools" -> receipt.copy(toolHashes = tools + ("checker" to "changed"))
            "witness" -> receipt.copy(checkedWitness = "v x=2")
            "queries" -> receipt.copy(solverSearchQueries = 1)
            "proof" -> receipt.copy(proofCertificateChecked = true)
            "cleanup" -> receipt.copy(cleanupComplete = false)
            "scope" -> receipt.copy(scope = "proof")
            "status" -> receipt.copy(status = "accepted")
            "runtime" -> receipt.copy(checker = "unversioned")
            "negative-time" -> receipt.copy(checkMs = -1)
            else -> receipt
        }
        val text = if (failure == "malformed") "{" else Reports.json.encodeToString(changed)

        val result = MpsSourceValidation.checkerResult(if (failure == "process") 1 else 0,
            text, record, bindings, tools)

        assertEquals("unknown", result.validation.status)
        assertNull(result.validation.checkedWitness)
    }
}
