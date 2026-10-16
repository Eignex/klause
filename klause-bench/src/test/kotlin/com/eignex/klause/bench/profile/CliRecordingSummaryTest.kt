package com.eignex.klause.bench.profile

import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CliRecordingSummaryTest {
    @Test
    fun `recording selections should preserve the original job and explicit order`() {
        val hash = "a".repeat(64)
        val uuid = "8e3d2767-0164-469c-a5b3-ebd4d8b64462"

        val request = CliRecordingSummary.parse(arrayOf(
            "sourceJob=5433", "recording=1:$uuid:$hash", "recording=0:$uuid:$hash",
        ))

        assertEquals(5433, request.sourceJob)
        assertEquals(listOf(1, 0), request.recordings.map { it.caseIndex })
        assertEquals(listOf(hash, hash), request.recordings.map { it.sha256 })
    }

    @Test
    fun `invalid and duplicate identifiers should be rejected before file access`() {
        val hash = "a".repeat(64)
        val uuid = "8e3d2767-0164-469c-a5b3-ebd4d8b64462"
        val recording = "recording=0:$uuid:$hash"
        for (args in listOf(
            arrayOf(recording),
            arrayOf("sourceJob=0", recording),
            arrayOf("sourceJob=1", "sourceJob=2", recording),
            arrayOf("sourceJob=1"),
            arrayOf("sourceJob=1", recording, recording),
            arrayOf("sourceJob=1", "recording=-1:$uuid:$hash"),
            arrayOf("sourceJob=1", "recording=0:1-1-1-1-1:$hash"),
            arrayOf("sourceJob=1", "recording=0:$uuid:not-a-hash"),
            arrayOf("sourceJob=1", recording, "path=/tmp/input.jfr"),
            arrayOf("sourceJob=1", *Array(9) { recording }),
        )) {
            assertFailsWith<IllegalArgumentException> { CliRecordingSummary.parse(args) }
        }
    }

    @Test
    fun `matching opaque recording bytes should load only their selected measurement`() {
        val root = Files.createTempDirectory("recording-summary")
        try {
            val uuid = "8e3d2767-0164-469c-a5b3-ebd4d8b64462"
            val hash = "3ebb153fb24e4411400e94a9a92b0ec458c3a8473e51e03cd37d4a34c99dfda6"
            val directory = Files.createDirectories(root.resolve("1/cases/0/profile-$uuid"))
            val path = directory.resolve("cli.jfr")
            Files.writeString(path, "recording")
            Files.writeString(directory.resolve("measurement.json"),
                """{"schemaVersion":1,"scope":"whole-cli-jvm","caseIndex":0,
                   "measurementId":"$uuid","recordSha256":null}""")

            val input = CliRecordingSummary.load(root, 1, CliRecordingSummary.Recording(0, uuid, hash))

            assertEquals(path.toRealPath(), input.path)
            assertNull(input.record)
            assertContentEquals("recording".toByteArray(), Files.readAllBytes(path))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a different recording digest should fail before parsing`() {
        val root = Files.createTempDirectory("recording-summary")
        try {
            val uuid = "8e3d2767-0164-469c-a5b3-ebd4d8b64462"
            val directory = Files.createDirectories(root.resolve("1/cases/0/profile-$uuid"))
            Files.writeString(directory.resolve("cli.jfr"), "recording")

            assertFailsWith<IllegalArgumentException> {
                CliRecordingSummary.load(root, 1, CliRecordingSummary.Recording(0, uuid, "0".repeat(64)))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a symlinked recording directory should be rejected`() {
        val root = Files.createTempDirectory("recording-summary")
        try {
            val uuid = "8e3d2767-0164-469c-a5b3-ebd4d8b64462"
            val outside = Files.createDirectory(root.resolve("outside"))
            Files.writeString(outside.resolve("cli.jfr"), "recording")
            val directory = Files.createDirectories(root.resolve("1/cases/0"))
            Files.createSymbolicLink(directory.resolve("profile-$uuid"), outside)

            assertFailsWith<IllegalArgumentException> {
                CliRecordingSummary.load(root, 1, CliRecordingSummary.Recording(0, uuid, "0".repeat(64)))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing retained recordings should fail explicitly`() {
        val root = Files.createTempDirectory("recording-summary")
        try {
            Files.createDirectory(root.resolve("1"))
            val recording = CliRecordingSummary.Recording(0, "8e3d2767-0164-469c-a5b3-ebd4d8b64462", "0".repeat(64))

            assertFailsWith<IOException> { CliRecordingSummary.load(root, 1, recording) }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `oversized recordings should be rejected before hashing or parsing`() {
        val root = Files.createTempDirectory("recording-summary")
        try {
            val uuid = "8e3d2767-0164-469c-a5b3-ebd4d8b64462"
            val directory = Files.createDirectories(root.resolve("1/cases/0/profile-$uuid"))
            RandomAccessFile(directory.resolve("cli.jfr").toFile(), "rw").use {
                it.setLength(CliRecordingSummary.MAX_RECORDING_BYTES + 1L)
            }

            assertFailsWith<IllegalArgumentException> {
                CliRecordingSummary.load(root, 1, CliRecordingSummary.Recording(0, uuid, "0".repeat(64)))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a different original solve record should withhold the replay input`() {
        val root = Files.createTempDirectory("recording-summary")
        try {
            val uuid = "8e3d2767-0164-469c-a5b3-ebd4d8b64462"
            val hash = "3ebb153fb24e4411400e94a9a92b0ec458c3a8473e51e03cd37d4a34c99dfda6"
            val directory = Files.createDirectories(root.resolve("1/cases/0/profile-$uuid"))
            Files.writeString(directory.resolve("cli.jfr"), "recording")
            Files.writeString(directory.resolve("solve-record.json"), "{}")
            val recordHash = "0".repeat(64)
            Files.writeString(directory.resolve("measurement.json"),
                """{"schemaVersion":1,"scope":"whole-cli-jvm","caseIndex":0,
                   "measurementId":"$uuid","recordSha256":"$recordHash"}""")

            assertFailsWith<IllegalArgumentException> {
                CliRecordingSummary.load(root, 1, CliRecordingSummary.Recording(0, uuid, hash))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
