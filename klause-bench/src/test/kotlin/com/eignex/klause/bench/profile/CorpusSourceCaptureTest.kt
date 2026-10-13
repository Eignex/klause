package com.eignex.klause.bench.profile

import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class CorpusSourceCaptureTest {
    @Test
    fun `exact catalog identities resolve without fetching or parsing source models`() {
        val input = CorpusSourceCapture.decode(request("smtlib-qfnra", "family/input")).single()

        assertEquals("smtlib-qfnra", input.suite)
        assertEquals("family/input", input.problem)
        assertEquals("a".repeat(64), input.expectedSha256)
    }

    @Test
    fun `unknown transformed and traversal identities cannot enter source capture`() {
        for ((suite, problem) in listOf("unknown" to "input", "miplib2017-csp" to "input",
            "smtlib-qfnra" to "../input", "smtlib-qfnra" to "family//input",
            "smtlib-qfnra" to "family\\input", "smtlib-qfnra" to "/input")) {
            assertFailsWith<IllegalArgumentException> {
                CorpusSourceCapture.decode(request(suite, problem))
            }
        }
    }

    @Test
    fun `every original source needs a full nonnull hash and unique identity`() {
        for (encoded in listOf(request(hash = ""), request(hash = "A".repeat(64)), request(copies = 2),
            "a".repeat(CorpusSourceCapture.MAX_REQUEST_BYTES * 2))) {
            assertFailsWith<IllegalArgumentException> { CorpusSourceCapture.decode(encoded) }
        }
    }

    @Test
    fun `extra fields and duplicate JSON keys cannot alter source requests`() {
        val canonical = Base64.getDecoder().decode(request()).decodeToString()
        for (text in listOf(canonical.replace("\"cases\":", "\"cases\":[],\"cases\":"),
            canonical.replace("\"cases\":", "\"solve\":true,\"cases\":"))) {
            assertFailsWith<IllegalArgumentException> {
                CorpusSourceCapture.decode(Base64.getEncoder().encodeToString(text.toByteArray()))
            }
        }
    }

    @Test
    fun `owned source worker exit status and raw output remain distinct`() {
        for (exit in listOf(0, 7)) {
            val out = Files.createTempDirectory("source-worker-test-").toFile()
            try {
                val command = listOf("/usr/bin/setsid", "/bin/sh", "-c", "printf 'raw\\r\\n'; exit $exit")
                if (exit == 0) CorpusSourceCapture.execute(command, out)
                else assertFailsWith<IllegalStateException> { CorpusSourceCapture.execute(command, out) }

                assertEquals("raw\r\n", out.resolve("worker.stdout").readText())
                val receipt = Json.parseToJsonElement(out.resolve("worker-process.json").readText()).jsonObject
                assertEquals(exit.toString(), receipt.getValue("exit").jsonPrimitive.content)
                assertEquals("true", receipt.getValue("streamsComplete").jsonPrimitive.content)
            } finally {
                out.deleteRecursively()
            }
        }
    }

    @Test
    fun `worker overflow retains a bounded prefix and stops the owned group`() {
        val out = Files.createTempDirectory("source-overflow-test-").toFile()
        try {
            val command = listOf("/usr/bin/setsid", "/bin/sh", "-c", "yes x")

            assertFailsWith<IllegalStateException> { CorpusSourceCapture.execute(command, out) }

            assertTrue(out.resolve("worker.stdout").length() <= 1024 * 1024)
            val receipt = Json.parseToJsonElement(out.resolve("worker-process.json").readText()).jsonObject
            assertEquals("true", receipt.getValue("overflow").jsonPrimitive.content)
        } finally {
            out.deleteRecursively()
        }
    }

    @Test
    fun `a containing interruption closes the worker before returning`() {
        val out = Files.createTempDirectory("source-stop-test-").toFile()
        try {
            Thread.currentThread().interrupt()

            assertFailsWith<InterruptedException> {
                CorpusSourceCapture.execute(listOf("/usr/bin/setsid", "/bin/sh", "-c", "sleep 30"), out)
            }

            val receipt = Json.parseToJsonElement(out.resolve("worker-process.json").readText()).jsonObject
            assertEquals("true", receipt.getValue("containingStop").jsonPrimitive.content)
        } finally {
            Thread.interrupted()
            out.deleteRecursively()
        }
    }

    @Test
    fun `failed group cleanup retains the process receipt and releases its registration`() {
        val out = Files.createTempDirectory("source-cleanup-failure-test-").toFile()
        try {
            assertFailsWith<IllegalStateException> {
                CorpusSourceCapture.execute(listOf("/usr/bin/setsid", "/bin/sh", "-c", "exit 0"), out,
                    cleanupHelper = out.resolve("missing-runtime").path)
            }

            val process = Json.parseToJsonElement(out.resolve("worker-process.json").readText()).jsonObject
            val cleanup = Json.parseToJsonElement(out.resolve("worker-cleanup.json").readText()).jsonObject
            assertEquals("0", process.getValue("exit").jsonPrimitive.content)
            assertEquals("false", cleanup.getValue("cleanupComplete").jsonPrimitive.content)
            assertEquals("true", cleanup.getValue("registrationReleased").jsonPrimitive.content)
            assertEquals("true", cleanup.getValue("shutdownHookRemoved").jsonPrimitive.content)
        } finally {
            out.deleteRecursively()
        }
    }

    @Test
    fun `interruption during cleanup is restored after durable retirement receipts`() {
        val out = Files.createTempDirectory("source-cleanup-stop-test-").toFile()
        try {
            assertFailsWith<IllegalStateException> {
                CorpusSourceCapture.execute(listOf("/usr/bin/setsid", "/bin/sh", "-c", "exit 0"), out,
                    afterWorker = { Thread.currentThread().interrupt() })
            }

            assertTrue(Thread.currentThread().isInterrupted)
            val process = Json.parseToJsonElement(out.resolve("worker-process.json").readText()).jsonObject
            val cleanup = Json.parseToJsonElement(out.resolve("worker-cleanup.json").readText()).jsonObject
            assertEquals("true", process.getValue("containingStop").jsonPrimitive.content)
            assertEquals("false", cleanup.getValue("cleanupComplete").jsonPrimitive.content)
            assertEquals("true", cleanup.getValue("registrationReleased").jsonPrimitive.content)
            assertEquals("true", cleanup.getValue("shutdownHookRemoved").jsonPrimitive.content)
        } finally {
            Thread.interrupted()
            out.deleteRecursively()
        }
    }

    private fun request(
        suite: String = "smtlib-qfnra", problem: String = "family/input",
        hash: String = "a".repeat(64), copies: Int = 1,
    ): String {
        val root = buildJsonObject {
            put("schema", "corpus-original-source-request-v1")
            put("cases", buildJsonArray {
                repeat(copies) { add(buildJsonObject {
                    put("suite", suite)
                    put("problem", problem)
                    put("expectedSha256", hash)
                }) }
            })
        }
        return Base64.getEncoder().encodeToString(root.toString().toByteArray())
    }
}
