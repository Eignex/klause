package com.eignex.klause.bench.profile

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

class XcspArchiveReplayTest {
    @Test
    @EnabledOnOs(OS.LINUX)
    fun `checker diagnostics admit only successful structured source receipts`() {
        val directory = Files.createTempDirectory("archive-checker-receipt-").toFile()
        try {
            val worker = directory.resolve("replay.py")
            val stream = javaClass.getResourceAsStream("/xcsp-archive-replay/replay.py")
            worker.writeBytes(requireNotNull(stream).use { it.readBytes() })
            val script = """
                import runpy, sys
                result = runpy.run_path(sys.argv[1])['checker_result']
                valid = 'LOG: Check constraints\nOK\nklause-source-check: {\n"status":"valid"\n}'
                assert result(0, valid)['status'] == 'valid'
                assert result(1, valid)['status'] == 'unknown'
                assert result(0, 'OK')['status'] == 'unknown'
                assert result(0, 'klause-source-check: {')['status'] == 'unknown'
                invalid = 'klause-source-check: {"status":"invalid"}'
                assert result(0, invalid)['status'] == 'invalid'
            """.trimIndent()
            val process = ProcessBuilder("/usr/bin/python3", "-I", "-c", script, worker.path)
                .redirectErrorStream(true).start()

            assertEquals(0, process.waitFor(), process.inputStream.bufferedReader().readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `archive extraction accepts distribution roots and refuses escaping members`() {
        val directory = Files.createTempDirectory("archive-extraction-").toFile()
        try {
            val worker = directory.resolve("replay.py")
            val stream = javaClass.getResourceAsStream("/xcsp-archive-replay/replay.py")
            worker.writeBytes(requireNotNull(stream).use { it.readBytes() })
            val script = """
                import io, pathlib, runpy, sys, tarfile
                extract = runpy.run_path(sys.argv[1])['extract']
                root = pathlib.Path(sys.argv[2])
                for i, name in enumerate(['klause-cli/build/install', '../escape',
                        '/escape', 'other/file', 'klause-cli/build/install/../../escape']):
                    data = io.BytesIO()
                    with tarfile.open(fileobj=data, mode='w') as t:
                        entry = tarfile.TarInfo(name)
                        entry.type = tarfile.DIRTYPE if i == 0 else tarfile.REGTYPE
                        t.addfile(entry)
                    data.seek(0)
                    try:
                        with tarfile.open(fileobj=data) as t:
                            extract(t, root / str(i))
                        assert i == 0
                    except ValueError:
                        assert i != 0
                assert not (root / 'escape').exists()
            """.trimIndent()
            val process = ProcessBuilder("/usr/bin/python3", "-I", "-c", script, worker.path, directory.path)
                .redirectErrorStream(true).start()

            assertEquals(0, process.waitFor(), process.inputStream.bufferedReader().readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `an oversized compressed request is rejected before decoding`() {
        assertFailsWith<IllegalArgumentException> {
            XcspArchiveReplay.decode("a".repeat(XcspArchiveReplay.MAX_COMPRESSED_BYTES * 2))
        }
    }

    @Test
    fun `an expanded request cannot exceed the decompression allowance`() {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(ByteArray(XcspArchiveReplay.MAX_REQUEST_BYTES + 1)) }

        assertFailsWith<IllegalArgumentException> {
            XcspArchiveReplay.decode(Base64.getEncoder().encodeToString(output.toByteArray()))
        }
    }

    @Test
    fun `duplicate request keys cannot change replay admission`() {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write("{\"archives\":[],\"archives\":[{}]}".toByteArray()) }

        assertFailsWith<IllegalArgumentException> {
            XcspArchiveReplay.decode(Base64.getEncoder().encodeToString(output.toByteArray()))
        }
    }
}
