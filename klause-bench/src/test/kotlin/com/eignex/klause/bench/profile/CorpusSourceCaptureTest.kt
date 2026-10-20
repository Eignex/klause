package com.eignex.klause.bench.profile

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

class CorpusSourceCaptureTest {
    @Test
    fun `exact catalog identities should resolve without fetching or parsing source models`() {
        val input = CorpusSourceCapture.decode(request("smtlib-qfnra", "family/input")).single()

        assertEquals("smtlib-qfnra", input.suite)
        assertEquals("family/input", input.problem)
        assertEquals("a".repeat(64), input.expectedSha256)
    }

    @Test
    fun `unknown transformed and traversal identities should not enter source capture`() {
        for ((suite, problem) in listOf("unknown" to "input", "miplib2017-csp" to "input",
            "smtlib-qfnra" to "../input", "smtlib-qfnra" to "family//input",
            "smtlib-qfnra" to "family\\input", "smtlib-qfnra" to "/input")) {
            assertFailsWith<IllegalArgumentException> {
                CorpusSourceCapture.decode(request(suite, problem))
            }
        }
    }

    @Test
    fun `every original source should need a full nonnull hash and unique identity`() {
        for (encoded in listOf(request(hash = ""), request(hash = "A".repeat(64)), request(copies = 2),
            "a".repeat(CorpusSourceCapture.MAX_REQUEST_BYTES * 2))) {
            assertFailsWith<IllegalArgumentException> { CorpusSourceCapture.decode(encoded) }
        }
    }

    @Test
    fun `extra fields and duplicate JSON keys should not alter source requests`() {
        val canonical = Base64.getDecoder().decode(request()).decodeToString()
        for (text in listOf(canonical.replace("\"cases\":", "\"cases\":[],\"cases\":"),
            canonical.replace("\"cases\":", "\"solve\":true,\"cases\":"))) {
            assertFailsWith<IllegalArgumentException> {
                CorpusSourceCapture.decode(Base64.getEncoder().encodeToString(text.toByteArray()))
            }
        }
    }

    @Test
    fun `original bytes should keep line endings and hash`() = inTemp { dir ->
        val raw = "opaque\r\nsource\n".toByteArray()
        val worker = CorpusSourceWorker(dir, checkpoint = {})

        val copy = worker.copySource(raw.inputStream(), dir.resolve("source.part"), CorpusSourceCapture.hash(raw))

        assertContentEquals(raw, dir.resolve("source.part").readBytes())
        assertEquals(raw.size.toLong(), copy.bytes)
        assertEquals(raw.size.toLong(), worker.sources.used)
    }

    @Test
    fun `a mismatched hash should retain the complete unaccepted bytes`() = inTemp { dir ->
        val raw = "opaque source".toByteArray()

        val error = assertFailsWith<IllegalArgumentException> {
            CorpusSourceWorker(dir, checkpoint = {}).copySource(raw.inputStream(), dir.resolve("source.part"), ZEROS)
        }

        assertTrue("hash differs" in error.message.orEmpty())
        assertContentEquals(raw, dir.resolve("source.part").readBytes())
    }

    @Test
    fun `a byte decline should retain only the bounded partial prefix`() = inTemp { dir ->
        val worker = CorpusSourceWorker(dir, checkpoint = {})

        val error = assertFailsWith<IllegalArgumentException> {
            worker.copySource("abcd".byteInputStream(), dir.resolve("source.part"), ZEROS, maximum = 3)
        }

        assertTrue("byte cap" in error.message.orEmpty())
        assertEquals("abc", dir.resolve("source.part").readText())
        assertEquals(3L, worker.sources.used)
    }

    @Test
    fun `a containing stop should never turn a partial copy into success`() = inTemp { dir ->
        val worker = CorpusSourceWorker(dir, checkpoint = { throw CaptureStopped("containing stop") })

        assertFailsWith<CaptureStopped> {
            worker.copySource("opaque".byteInputStream(), dir.resolve("source.part"), ZEROS)
        }

        assertEquals(0L, dir.resolve("source.part").length())
        assertFalse(receipt(dir, "source").getValue("hashMatched").jsonPrimitive.boolean)
    }

    @Test
    fun `a source read failure should be rethrown and recorded in its receipt`() = inTemp { dir ->
        val failure = IOException("source read failure")

        val caught = assertFailsWith<IOException> {
            CorpusSourceWorker(dir, checkpoint = {})
                .copySource(failingAfter("opaque", failure), dir.resolve("source.part"), ZEROS)
        }

        assertSame(failure, caught)
        assertEquals("opaque", dir.resolve("source.part").readText())
        val receipt = receipt(dir, "source")
        assertTrue("source read failure" in receipt.getValue("copyError").jsonPrimitive.content)
        assertFalse(receipt.getValue("streamComplete").jsonPrimitive.boolean)
    }

    @Test
    fun `an archive over its cap should debit every observed byte and retain a bounded prefix`() = inTemp { dir ->
        val worker = CorpusSourceWorker(dir, checkpoint = {})
        val input = SequenceInputStream("ab".byteInputStream(), "cdefgh".byteInputStream())

        assertFailsWith<IllegalArgumentException> {
            worker.transferArchive(input, null, dir.resolve("archive.part"), maximum = 5)
        }

        assertEquals("abcde", dir.resolve("archive.part").readText())
        assertEquals(6L, worker.downloads.used)
        val receipt = receipt(dir, "archive")
        assertEquals(6L, receipt.getValue("observedBytes").jsonPrimitive.long)
        assertEquals(5L, receipt.getValue("retainedBytes").jsonPrimitive.long)
        assertEquals(1L, receipt.getValue("omittedBytes").jsonPrimitive.long)
        assertEquals(CorpusSourceCapture.hash("abcde".toByteArray()), receipt.getValue("sha256").jsonPrimitive.content)
        assertFalse(receipt.getValue("transportComplete").jsonPrimitive.boolean)
        assertFalse(receipt.getValue("streamEof").jsonPrimitive.boolean)
    }

    @Test
    fun `a failed transfer receipt should not replace the original read failure`() = inTemp { dir ->
        val failure = IOException("connection reset")
        dir.resolve("archive.transfer.json").mkdir()

        val caught = assertFailsWith<IOException> {
            CorpusSourceWorker(dir, checkpoint = {})
                .transferArchive(failingAfter("ab", failure), null, dir.resolve("archive.part"))
        }

        assertSame(failure, caught)
        assertEquals("ab", dir.resolve("archive.part").readText())
        assertTrue(caught.suppressed.any { "transfer receipt" in it.message.orEmpty() })
    }

    @Test
    fun `archive paths should not escape the declared member identity`() {
        assertEquals("non-incremental/QF_NRA/family/input.smt2",
            CorpusSourceWorker.canonicalMember("./non-incremental/QF_NRA/family/input.smt2"))
        for (name in listOf("../input", "/input", "family/../input", "family\\input", "family//input")) {
            assertFailsWith<IllegalArgumentException> { CorpusSourceWorker.canonicalMember(name) }
        }
    }

    @Test
    fun `a requested member should be captured byte for byte from a tar zst archive`() = inTemp { dir ->
        val raw = "(check-sat)\r\n".toByteArray()
        val archive = archive(dir, "gnu", mapOf(MEMBER to raw, "non-incremental/other.smt2" to "x".toByteArray()))
        val out = dir.resolve("out").apply { mkdir() }

        val captured = worker(out, archive).capture(listOf(case(MEMBER, CorpusSourceCapture.hash(raw))))

        assertTrue(captured)
        assertContentEquals(raw, out.resolve("source-0.smt2").readBytes())
        assertFalse(out.resolve("archive-0.tar.zst").exists())
    }

    @Test
    fun `a member absent from the archive should fail without a captured source`() = inTemp { dir ->
        val archive = archive(dir, "gnu", mapOf("non-incremental/other.smt2" to "x".toByteArray()))
        val out = dir.resolve("out").apply { mkdir() }

        val captured = worker(out, archive).capture(listOf(case(MEMBER, ZEROS)))

        assertFalse(captured)
        assertFalse(out.resolve("source-0.smt2").exists())
        val result = Json.parseToJsonElement(out.resolve("capture.json").readText()).jsonObject
            .getValue("results").jsonArray.single().jsonObject
        assertEquals("failed", result.getValue("state").jsonPrimitive.content)
        assertEquals("requested member absent from archive",
            result.getValue("issues").jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun `long member names should resolve in gnu pax and ustar archives`() {
        val member = "non-incremental/QF_NRA/${"f".repeat(60)}/${"g".repeat(60)}/input.smt2"
        val raw = "(assert true)\n".toByteArray()
        for (format in listOf("gnu", "posix", "ustar")) inTemp { dir ->
            val archive = archive(dir, format, mapOf(member to raw))
            val out = dir.resolve("out").apply { mkdir() }

            val captured = worker(out, archive).capture(listOf(case(member, CorpusSourceCapture.hash(raw))))

            assertTrue(captured, format)
            assertContentEquals(raw, out.resolve("source-0.smt2").readBytes())
        }
    }

    private fun inTemp(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("corpus-source-capture-test-").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun failingAfter(prefix: String, failure: IOException): InputStream =
        SequenceInputStream(ByteArrayInputStream(prefix.toByteArray()), object : InputStream() {
            override fun read(): Int = throw failure
        })

    private fun receipt(dir: File, stem: String): JsonObject =
        Json.parseToJsonElement(dir.resolve("$stem.transfer.json").readText()).jsonObject

    private fun archive(dir: File, format: String, members: Map<String, ByteArray>): File {
        val tree = dir.resolve("tree")
        for ((name, bytes) in members) tree.resolve(name).apply { parentFile.mkdirs() }.writeBytes(bytes)
        val archive = dir.resolve("collection.tar.zst")
        val process = ProcessBuilder("tar", "--zstd", "--format=$format", "-cf", archive.path, "-C", tree.path, ".")
            .redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().decodeToString()
        assertEquals(0, process.waitFor(), output)
        return archive
    }

    private fun worker(out: File, archive: File) = CorpusSourceWorker(out, checkpoint = {}, open = {
        ArchiveResponse(it, archive.length(), archive.inputStream())
    })

    private fun case(member: String, sha256: String) = buildJsonObject {
        put("index", 0)
        put("expectedSha256", sha256)
        put("archiveUrl", "https://example.invalid/collection.tar.zst")
        put("member", member)
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

    private companion object {
        const val MEMBER = "non-incremental/QF_NRA/family/input.smt2"
        val ZEROS = "0".repeat(64)
    }
}
